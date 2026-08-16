/*
 * Master Key native audio engine.
 *
 * Design notes that matter:
 *
 *  - Oboe forbids blocking (mutexes, allocation) inside the audio callback, but
 *    tsf_note_on() must not race tsf_render_float(). The resolution is that the
 *    UI/scheduler thread NEVER calls into TSF. It pushes timestamped events into
 *    a lock-free single-producer/single-consumer ring buffer, and the audio
 *    callback drains that buffer itself.
 *
 *  - Because the callback applies events at exact frame offsets (splitting each
 *    render into segments at event boundaries), note timing is sample-accurate
 *    rather than buffer-accurate. That is what keeps a 120 bpm run of 16ths from
 *    sounding lumpy.
 *
 *  - The callback also owns the transport position. Everything visual reads that
 *    counter, so the falling notes cannot drift away from what you hear.
 *
 * Threading contract: all producer-side entry points (schedule*, seek, setVolume)
 * must be called from ONE thread. AudioEngine.kt enforces this with a dedicated
 * single-thread dispatcher.
 */

#include <jni.h>
#include <oboe/Oboe.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <vector>

#include "third_party/tsf.h"

#include <android/log.h>
#define LOG_TAG "MasterKeySynth"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kChannels = 2;              // stereo interleaved
constexpr size_t kRingCapacity = 1 << 14; // 16384 events, power of two
constexpr size_t kRingMask = kRingCapacity - 1;

enum EventType : uint8_t {
    kNoteOn = 0,
    kNoteOff = 1,
    kAllNotesOff = 2,
    kChannelVolume = 3,
    kSustain = 4,
    kMetronome = 5,
};

struct Event {
    int64_t frame;
    uint32_t generation;
    uint8_t type;
    uint8_t channel;
    uint8_t key;
    uint8_t velocity;
    float value;
};

/**
 * Lock-free SPSC ring. `writeIndex` is only ever written by the producer,
 * `readIndex` only by the audio callback, so no CAS is needed — release/acquire
 * ordering on the two indices is sufficient.
 */
class EventRing {
public:
    bool push(const Event& e) {
        const size_t w = writeIndex_.load(std::memory_order_relaxed);
        const size_t r = readIndex_.load(std::memory_order_acquire);
        if (w - r >= kRingCapacity) return false; // full; drop rather than block
        buffer_[w & kRingMask] = e;
        writeIndex_.store(w + 1, std::memory_order_release);
        return true;
    }

    const Event* peek() const {
        const size_t r = readIndex_.load(std::memory_order_relaxed);
        const size_t w = writeIndex_.load(std::memory_order_acquire);
        if (r == w) return nullptr;
        return &buffer_[r & kRingMask];
    }

    void pop() {
        const size_t r = readIndex_.load(std::memory_order_relaxed);
        readIndex_.store(r + 1, std::memory_order_release);
    }

private:
    Event buffer_[kRingCapacity]{};
    std::atomic<size_t> writeIndex_{0};
    std::atomic<size_t> readIndex_{0};
};

/**
 * A tiny click generator for the metronome.
 *
 * The bundled soundfont is piano-only, so there is no GM drum kit to borrow a
 * click from. A short decaying sine is cheaper than shipping a second bank and
 * cuts through a real piano better than a woodblock sample anyway.
 */
class Click {
public:
    void trigger(float frequency, float gain) {
        phase_ = 0.0f;
        frequency_ = frequency;
        gain_ = gain;
        remaining_ = static_cast<int>(sampleRate_ * 0.035f); // 35 ms
    }

    void setSampleRate(float sr) { sampleRate_ = sr; }

    void render(float* out, int frames) {
        if (remaining_ <= 0) return;
        const float step = 2.0f * 3.14159265358979f * frequency_ / sampleRate_;
        const int n = std::min(frames, remaining_);
        for (int i = 0; i < n; ++i) {
            // Linear decay is inaudibly different from exponential over 35 ms
            // and avoids a call to expf() per sample.
            const float env = static_cast<float>(remaining_ - i) /
                              static_cast<float>(sampleRate_ * 0.035f);
            const float s = std::sin(phase_) * env * gain_;
            out[i * 2] += s;
            out[i * 2 + 1] += s;
            phase_ += step;
        }
        remaining_ -= n;
    }

private:
    float phase_ = 0.0f;
    float frequency_ = 1000.0f;
    float gain_ = 0.0f;
    float sampleRate_ = 48000.0f;
    int remaining_ = 0;
};

class SynthEngine : public oboe::AudioStreamDataCallback {
public:
    bool loadSoundFont(const void* data, int size, int sampleRate) {
        tsf* loaded = tsf_load_memory(data, size);
        if (loaded == nullptr) {
            LOGE("tsf_load_memory failed");
            return false;
        }
        tsf_set_output(loaded, TSF_STEREO_INTERLEAVED, sampleRate, 0.0f);
        tsf_set_max_voices(loaded, 96);
        // Channels 0 and 1 are right and left hand; both play preset 0, which is
        // the only preset in a piano-only bank.
        tsf_channel_set_presetindex(loaded, 0, 0);
        tsf_channel_set_presetindex(loaded, 1, 0);
        tsf_channel_set_volume(loaded, 0, 1.0f);
        tsf_channel_set_volume(loaded, 1, 1.0f);

        tsf_ = loaded;
        sampleRate_ = sampleRate;
        click_.setSampleRate(static_cast<float>(sampleRate));
        return true;
    }

    ~SynthEngine() override {
        if (tsf_ != nullptr) {
            tsf_close(tsf_);
            tsf_ = nullptr;
        }
    }

    bool start(int requestedSampleRate) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(oboe::SharingMode::Shared)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(kChannels)
                ->setSampleRate(requestedSampleRate)
                ->setDataCallback(this);

        const oboe::Result result = builder.openStream(stream_);
        if (result != oboe::Result::OK) {
            LOGE("openStream failed: %s", oboe::convertToText(result));
            return false;
        }
        stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2);
        const oboe::Result started = stream_->requestStart();
        if (started != oboe::Result::OK) {
            LOGE("requestStart failed: %s", oboe::convertToText(started));
            return false;
        }
        return true;
    }

    void stop() {
        if (stream_) {
            stream_->requestStop();
            stream_->close();
            stream_.reset();
        }
    }

    int actualSampleRate() const {
        return stream_ ? stream_->getSampleRate() : sampleRate_;
    }

    // ---- producer side -------------------------------------------------

    void schedule(const Event& e) { ring_.push(e); }

    uint32_t generation() const { return generation_.load(std::memory_order_relaxed); }

    /**
     * Bumping the generation invalidates every event already queued, so a seek
     * never leaves stale notes to fire at the new position. The callback then
     * applies the position change itself.
     */
    void requestSeek(int64_t frame) {
        generation_.fetch_add(1, std::memory_order_release);
        seekTarget_.store(frame, std::memory_order_release);
        seekPending_.store(true, std::memory_order_release);
    }

    void setPlaying(bool playing) { playing_.store(playing, std::memory_order_release); }

    int64_t transportFrames() const { return transportFrames_.load(std::memory_order_acquire); }

    void setMasterGain(float gain) { masterGain_.store(gain, std::memory_order_relaxed); }

    // ---- audio callback ------------------------------------------------

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData,
                                          int32_t numFrames) override {
        auto* out = static_cast<float*>(audioData);
        std::memset(out, 0, sizeof(float) * numFrames * kChannels);

        if (tsf_ == nullptr) return oboe::DataCallbackResult::Continue;

        if (seekPending_.exchange(false, std::memory_order_acq_rel)) {
            tsf_note_off_all(tsf_);
            transportFrames_.store(seekTarget_.load(std::memory_order_acquire),
                                   std::memory_order_release);
        }

        if (!playing_.load(std::memory_order_acquire)) {
            // Still render so release tails and the metronome finish cleanly,
            // but do not advance musical time.
            tsf_render_float(tsf_, out, numFrames, 0);
            click_.render(out, numFrames);
            applyMasterGain(out, numFrames);
            return oboe::DataCallbackResult::Continue;
        }

        int64_t pos = transportFrames_.load(std::memory_order_relaxed);
        int32_t done = 0;

        while (done < numFrames) {
            dropStaleEvents();

            // Apply everything due at or before the current position.
            const Event* next = ring_.peek();
            while (next != nullptr && next->frame <= pos) {
                applyEvent(*next);
                ring_.pop();
                dropStaleEvents();
                next = ring_.peek();
            }

            // Render up to the next event, or to the end of the buffer.
            int64_t limit = pos + (numFrames - done);
            if (next != nullptr && next->frame < limit) limit = next->frame;

            const auto segment = static_cast<int32_t>(limit - pos);
            if (segment <= 0) {
                // An event landed exactly on `pos`; the loop above will consume
                // it on the next pass. If there is nothing left, we are done.
                if (next == nullptr) break;
                continue;
            }

            tsf_render_float(tsf_, out + static_cast<size_t>(done) * kChannels, segment, 0);
            click_.render(out + static_cast<size_t>(done) * kChannels, segment);
            done += segment;
            pos += segment;
        }

        if (done < numFrames) {
            const int32_t rest = numFrames - done;
            tsf_render_float(tsf_, out + static_cast<size_t>(done) * kChannels, rest, 0);
            click_.render(out + static_cast<size_t>(done) * kChannels, rest);
            pos += rest;
        }

        transportFrames_.store(pos, std::memory_order_release);
        applyMasterGain(out, numFrames);
        return oboe::DataCallbackResult::Continue;
    }

private:
    void applyMasterGain(float* out, int32_t numFrames) {
        const float g = masterGain_.load(std::memory_order_relaxed);
        if (g == 1.0f) return;
        const int32_t n = numFrames * kChannels;
        for (int32_t i = 0; i < n; ++i) out[i] *= g;
    }

    void dropStaleEvents() {
        const uint32_t gen = generation_.load(std::memory_order_acquire);
        const Event* e = ring_.peek();
        while (e != nullptr && e->generation != gen) {
            ring_.pop();
            e = ring_.peek();
        }
    }

    void applyEvent(const Event& e) {
        switch (e.type) {
            case kNoteOn:
                tsf_channel_note_on(tsf_, e.channel, e.key,
                                    static_cast<float>(e.velocity) / 127.0f);
                break;
            case kNoteOff:
                tsf_channel_note_off(tsf_, e.channel, e.key);
                break;
            case kAllNotesOff:
                tsf_note_off_all(tsf_);
                break;
            case kChannelVolume:
                tsf_channel_set_volume(tsf_, e.channel, e.value);
                break;
            case kSustain:
                tsf_channel_set_sustain(tsf_, e.channel, e.value > 0.5f ? 1 : 0);
                break;
            case kMetronome:
                // velocity != 0 marks the downbeat: higher and louder.
                click_.trigger(e.velocity != 0 ? 1600.0f : 1000.0f, e.value);
                break;
            default:
                break;
        }
    }

    tsf* tsf_ = nullptr;
    int sampleRate_ = 48000;
    std::shared_ptr<oboe::AudioStream> stream_;
    EventRing ring_;
    Click click_;

    std::atomic<int64_t> transportFrames_{0};
    std::atomic<int64_t> seekTarget_{0};
    std::atomic<bool> seekPending_{false};
    std::atomic<bool> playing_{false};
    std::atomic<uint32_t> generation_{1};
    std::atomic<float> masterGain_{1.0f};
};

std::unique_ptr<SynthEngine> gEngine;

Event makeEvent(uint8_t type, int64_t frame, uint8_t channel, uint8_t key,
                uint8_t velocity, float value) {
    Event e{};
    e.frame = frame;
    e.generation = gEngine ? gEngine->generation() : 0;
    e.type = type;
    e.channel = channel;
    e.key = key;
    e.velocity = velocity;
    e.value = value;
    return e;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_kaiharimoto_masterkey_audio_NativeSynth_nativeCreate(
        JNIEnv* env, jobject, jbyteArray soundFont, jint sampleRate) {
    gEngine = std::make_unique<SynthEngine>();

    const jsize size = env->GetArrayLength(soundFont);
    jbyte* bytes = env->GetByteArrayElements(soundFont, nullptr);
    const bool ok = gEngine->loadSoundFont(bytes, size, sampleRate);
    env->ReleaseByteArrayElements(soundFont, bytes, JNI_ABORT);

    if (!ok) {
        gEngine.reset();
        return JNI_FALSE;
    }
    if (!gEngine->start(sampleRate)) {
        gEngine.reset();
        return JNI_FALSE;
    }
    LOGI("synth started at %d Hz", gEngine->actualSampleRate());
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_kaiharimoto_masterkey_audio_NativeSynth_nativeDestroy(JNIEnv*, jobject) {
    if (gEngine) {
        gEngine->stop();
        gEngine.reset();
    }
}

JNIEXPORT jint JNICALL
Java_dev_kaiharimoto_masterkey_audio_NativeSynth_nativeSampleRate(JNIEnv*, jobject) {
    return gEngine ? gEngine->actualSampleRate() : 0;
}

JNIEXPORT void JNICALL
Java_dev_kaiharimoto_masterkey_audio_NativeSynth_nativeSetPlaying(JNIEnv*, jobject,
                                                                  jboolean playing) {
    if (gEngine) gEngine->setPlaying(playing == JNI_TRUE);
}

JNIEXPORT jlong JNICALL
Java_dev_kaiharimoto_masterkey_audio_NativeSynth_nativeTransportFrames(JNIEnv*, jobject) {
    return gEngine ? gEngine->transportFrames() : 0;
}

JNIEXPORT void JNICALL
Java_dev_kaiharimoto_masterkey_audio_NativeSynth_nativeSeek(JNIEnv*, jobject, jlong frame) {
    if (gEngine) gEngine->requestSeek(frame);
}

JNIEXPORT void JNICALL
Java_dev_kaiharimoto_masterkey_audio_NativeSynth_nativeScheduleNoteOn(
        JNIEnv*, jobject, jlong frame, jint channel, jint key, jint velocity) {
    if (!gEngine) return;
    gEngine->schedule(makeEvent(kNoteOn, frame, static_cast<uint8_t>(channel),
                                static_cast<uint8_t>(key),
                                static_cast<uint8_t>(velocity), 0.0f));
}

JNIEXPORT void JNICALL
Java_dev_kaiharimoto_masterkey_audio_NativeSynth_nativeScheduleNoteOff(
        JNIEnv*, jobject, jlong frame, jint channel, jint key) {
    if (!gEngine) return;
    gEngine->schedule(makeEvent(kNoteOff, frame, static_cast<uint8_t>(channel),
                                static_cast<uint8_t>(key), 0, 0.0f));
}

JNIEXPORT void JNICALL
Java_dev_kaiharimoto_masterkey_audio_NativeSynth_nativeScheduleMetronome(
        JNIEnv*, jobject, jlong frame, jboolean accent, jfloat gain) {
    if (!gEngine) return;
    gEngine->schedule(makeEvent(kMetronome, frame, 0, 0,
                                accent == JNI_TRUE ? 1 : 0, gain));
}

JNIEXPORT void JNICALL
Java_dev_kaiharimoto_masterkey_audio_NativeSynth_nativeAllNotesOff(JNIEnv*, jobject,
                                                                   jlong frame) {
    if (!gEngine) return;
    gEngine->schedule(makeEvent(kAllNotesOff, frame, 0, 0, 0, 0.0f));
}

JNIEXPORT void JNICALL
Java_dev_kaiharimoto_masterkey_audio_NativeSynth_nativeSetChannelVolume(
        JNIEnv*, jobject, jlong frame, jint channel, jfloat volume) {
    if (!gEngine) return;
    gEngine->schedule(makeEvent(kChannelVolume, frame, static_cast<uint8_t>(channel),
                                0, 0, volume));
}

JNIEXPORT void JNICALL
Java_dev_kaiharimoto_masterkey_audio_NativeSynth_nativeSetSustain(
        JNIEnv*, jobject, jlong frame, jint channel, jboolean on) {
    if (!gEngine) return;
    gEngine->schedule(makeEvent(kSustain, frame, static_cast<uint8_t>(channel), 0, 0,
                                on == JNI_TRUE ? 1.0f : 0.0f));
}

JNIEXPORT void JNICALL
Java_dev_kaiharimoto_masterkey_audio_NativeSynth_nativeSetMasterGain(JNIEnv*, jobject,
                                                                     jfloat gain) {
    if (gEngine) gEngine->setMasterGain(gain);
}

} // extern "C"
