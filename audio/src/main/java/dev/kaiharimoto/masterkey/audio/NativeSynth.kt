package dev.kaiharimoto.masterkey.audio

/**
 * Thin binding to the native TinySoundFont + Oboe engine.
 *
 * Threading contract, enforced by [PlaybackEngine]: every method here except
 * [transportFrames] must be called from ONE thread. The native side uses a
 * single-producer ring buffer, so two threads scheduling concurrently would
 * corrupt it. [transportFrames] is an atomic read and is safe from anywhere,
 * which matters because the UI polls it every frame.
 */
internal class NativeSynth {

    private var created = false

    fun create(soundFont: ByteArray, sampleRate: Int): Boolean {
        check(!created) { "synth already created" }
        created = nativeCreate(soundFont, sampleRate)
        return created
    }

    fun destroy() {
        if (created) {
            nativeDestroy()
            created = false
        }
    }

    val isCreated: Boolean get() = created

    fun sampleRate(): Int = if (created) nativeSampleRate() else 0

    fun setPlaying(playing: Boolean) = nativeSetPlaying(playing)

    /** Frames rendered so far. This is the clock everything else reads. */
    fun transportFrames(): Long = if (created) nativeTransportFrames() else 0L

    fun seek(frame: Long) = nativeSeek(frame)

    fun scheduleNoteOn(frame: Long, channel: Int, key: Int, velocity: Int) =
        nativeScheduleNoteOn(frame, channel, key, velocity)

    fun scheduleNoteOff(frame: Long, channel: Int, key: Int) =
        nativeScheduleNoteOff(frame, channel, key)

    fun scheduleMetronome(frame: Long, accent: Boolean, gain: Float) =
        nativeScheduleMetronome(frame, accent, gain)

    fun allNotesOff(frame: Long) = nativeAllNotesOff(frame)

    fun setChannelVolume(frame: Long, channel: Int, volume: Float) =
        nativeSetChannelVolume(frame, channel, volume)

    fun setSustain(frame: Long, channel: Int, on: Boolean) =
        nativeSetSustain(frame, channel, on)

    fun setMasterGain(gain: Float) = nativeSetMasterGain(gain)

    private external fun nativeCreate(soundFont: ByteArray, sampleRate: Int): Boolean
    private external fun nativeDestroy()
    private external fun nativeSampleRate(): Int
    private external fun nativeSetPlaying(playing: Boolean)
    private external fun nativeTransportFrames(): Long
    private external fun nativeSeek(frame: Long)
    private external fun nativeScheduleNoteOn(frame: Long, channel: Int, key: Int, velocity: Int)
    private external fun nativeScheduleNoteOff(frame: Long, channel: Int, key: Int)
    private external fun nativeScheduleMetronome(frame: Long, accent: Boolean, gain: Float)
    private external fun nativeAllNotesOff(frame: Long)
    private external fun nativeSetChannelVolume(frame: Long, channel: Int, volume: Float)
    private external fun nativeSetSustain(frame: Long, channel: Int, on: Boolean)
    private external fun nativeSetMasterGain(gain: Float)

    companion object {
        init {
            System.loadLibrary("masterkey_synth")
        }
    }
}
