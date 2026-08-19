/*
 * The scheduler's two queues, kept free of Oboe and TSF so they can be compiled
 * and tested on the host. See test/event_queue_test.cpp.
 *
 * There are two of them because they answer different questions:
 *
 *  - EventRing gets events across the thread boundary without locking. It is
 *    strictly FIFO, so its order is *production* order.
 *  - EventTimeline puts them back into *musical* order before they are applied.
 *
 * The second stage is not optional. The producer emits a note-on and its
 * matching note-off together, one note at a time, so a three-note chord arrives
 * as on/off, on/off, on/off — with the first note's off sitting in front of the
 * second note's on even though it happens a beat later. A consumer that only
 * looks at the head of a FIFO stalls behind that off event: the rest of the
 * chord is applied a beat late, its own note-offs are then already due, and the
 * notes are silenced the instant they start. The audible result is a piece that
 * plays one note where a chord was written.
 */

#ifndef MASTERKEY_EVENT_QUEUE_H
#define MASTERKEY_EVENT_QUEUE_H

#include <atomic>
#include <cstddef>
#include <cstdint>

namespace masterkey {

enum EventType : uint8_t {
    kNoteOn = 0,
    kNoteOff = 1,
    kChannelVolume = 2,
    kSustain = 3,
    kMetronome = 4,
};

struct Event {
    /** Absolute frame on the audio clock at which this event applies. */
    int64_t frame;
    /**
     * Tie-break for events sharing a frame and a priority. Stamped as the event
     * leaves the ring, so it preserves production order.
     */
    uint64_t sequence;
    /** Events from before a seek carry an older generation and are discarded. */
    uint32_t generation;
    uint8_t type;
    uint8_t channel;
    uint8_t key;
    uint8_t velocity;
    float value;
};

/**
 * Order in which events landing on the same frame must be applied.
 *
 * Note-off before note-on matters whenever a key is re-struck on the beat it was
 * released: same channel, same pitch, one frame. Applied the other way round the
 * new note is started and then immediately killed by the old note's off.
 *
 * Volume and sustain sit between the two so that unmuting a hand, or lifting the
 * pedal, is in effect before the notes that were scheduled alongside it.
 */
inline int eventPriority(uint8_t type) {
    switch (type) {
        case kNoteOff:
            return 0;
        case kChannelVolume:
        case kSustain:
            return 1;
        default: // kNoteOn, kMetronome
            return 2;
    }
}

inline bool eventPrecedes(const Event& a, const Event& b) {
    if (a.frame != b.frame) return a.frame < b.frame;
    const int pa = eventPriority(a.type);
    const int pb = eventPriority(b.type);
    if (pa != pb) return pa < pb;
    return a.sequence < b.sequence;
}

/**
 * Lock-free single-producer/single-consumer ring.
 *
 * `writeIndex` is only ever written by the producer and `readIndex` only by the
 * audio callback, so no CAS is needed — release/acquire ordering on the two
 * indices is sufficient.
 */
template <size_t Capacity>
class EventRing {
public:
    static_assert((Capacity & (Capacity - 1)) == 0, "capacity must be a power of two");

    bool push(const Event& e) {
        const size_t w = writeIndex_.load(std::memory_order_relaxed);
        const size_t r = readIndex_.load(std::memory_order_acquire);
        if (w - r >= Capacity) return false; // full; drop rather than block
        buffer_[w & kMask] = e;
        writeIndex_.store(w + 1, std::memory_order_release);
        return true;
    }

    const Event* peek() const {
        const size_t r = readIndex_.load(std::memory_order_relaxed);
        const size_t w = writeIndex_.load(std::memory_order_acquire);
        if (r == w) return nullptr;
        return &buffer_[r & kMask];
    }

    void pop() {
        const size_t r = readIndex_.load(std::memory_order_relaxed);
        readIndex_.store(r + 1, std::memory_order_release);
    }

private:
    static constexpr size_t kMask = Capacity - 1;

    Event buffer_[Capacity]{};
    std::atomic<size_t> writeIndex_{0};
    std::atomic<size_t> readIndex_{0};
};

/**
 * Fixed-capacity binary min-heap over [eventPrecedes], owned by the audio
 * callback.
 *
 * A heap rather than a sorted insert because push is what happens in bulk: every
 * callback drains a whole pump's worth of events, and O(log n) sift-up beats
 * shuffling an array. Storage is inline and the size never changes, so nothing
 * here allocates or blocks — which is the whole requirement for code running
 * inside the callback.
 */
template <size_t Capacity>
class EventTimeline {
public:
    bool push(const Event& e) {
        if (size_ >= Capacity) return false;
        size_t i = size_++;
        heap_[i] = e;
        while (i > 0) {
            const size_t parent = (i - 1) / 2;
            if (!eventPrecedes(heap_[i], heap_[parent])) break;
            swap(i, parent);
            i = parent;
        }
        return true;
    }

    /** The next event to apply, or null when the timeline is empty. */
    const Event* top() const { return size_ > 0 ? &heap_[0] : nullptr; }

    void pop() {
        if (size_ == 0) return;
        heap_[0] = heap_[--size_];
        size_t i = 0;
        for (;;) {
            const size_t left = 2 * i + 1;
            const size_t right = left + 1;
            size_t best = i;
            if (left < size_ && eventPrecedes(heap_[left], heap_[best])) best = left;
            if (right < size_ && eventPrecedes(heap_[right], heap_[best])) best = right;
            if (best == i) break;
            swap(i, best);
            i = best;
        }
    }

    void clear() { size_ = 0; }

    size_t size() const { return size_; }

    bool full() const { return size_ >= Capacity; }

private:
    void swap(size_t a, size_t b) {
        const Event tmp = heap_[a];
        heap_[a] = heap_[b];
        heap_[b] = tmp;
    }

    Event heap_[Capacity]{};
    size_t size_ = 0;
};

} // namespace masterkey

#endif // MASTERKEY_EVENT_QUEUE_H
