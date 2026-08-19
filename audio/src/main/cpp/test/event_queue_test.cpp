/*
 * Host-side tests for the scheduler queues.
 *
 * event_queue.h deliberately depends on nothing but the standard library, so
 * the ordering rules the audio callback relies on can be checked with a plain
 * compiler and no device:
 *
 *     c++ -std=c++17 -Wall -o /tmp/event_queue_test \
 *         audio/src/main/cpp/test/event_queue_test.cpp && /tmp/event_queue_test
 *
 * The case that matters most is chordPlaysAllNotes(). Reverting the timeline and
 * feeding the callback straight off the ring makes it fail, which is exactly the
 * bug it exists to keep out: chords collapsing to a single sounding note.
 */

#include "../event_queue.h"

#include <cstdio>
#include <string>
#include <vector>

using namespace masterkey;

namespace {

int gFailures = 0;

void check(bool condition, const std::string& what) {
    if (!condition) {
        std::printf("  FAIL  %s\n", what.c_str());
        ++gFailures;
    }
}

using Ring = EventRing<64>;
using Timeline = EventTimeline<64>;

Event makeEvent(uint8_t type, int64_t frame, uint8_t key, uint32_t generation = 1) {
    Event e{};
    e.frame = frame;
    e.generation = generation;
    e.type = type;
    e.channel = 0;
    e.key = key;
    e.velocity = 100;
    return e;
}

/** What the audio callback records: the frame each event was actually applied at. */
struct Applied {
    uint8_t type;
    uint8_t key;
    int64_t frame;
};

/**
 * Stands in for SynthEngine::onAudioReady, minus the synthesis.
 *
 * Same shape as the real thing: drain the ring into the timeline, then walk the
 * buffer applying everything due, splitting the render at event boundaries.
 */
class CallbackSim {
public:
    void run(Ring& ring, int32_t numFrames, uint32_t generation = 1) {
        while (const Event* e = ring.peek()) {
            if (e->generation != generation) {
                ring.pop();
                continue;
            }
            Event copy = *e;
            copy.sequence = sequence_++;
            if (!timeline_.push(copy)) break;
            ring.pop();
        }

        int32_t done = 0;
        while (done < numFrames) {
            while (const Event* next = timeline_.top()) {
                if (next->frame > position_) break;
                applied.push_back({next->type, next->key, position_});
                timeline_.pop();
            }

            int64_t limit = position_ + (numFrames - done);
            if (const Event* next = timeline_.top()) {
                if (next->frame < limit) limit = next->frame;
            }
            const auto segment = static_cast<int32_t>(limit - position_);
            if (segment <= 0) break;
            done += segment;
            position_ += segment;
        }
    }

    int64_t position() const { return position_; }

    std::vector<Applied> applied;

private:
    Timeline timeline_;
    int64_t position_ = 0;
    uint64_t sequence_ = 0;
};

// ---- tests -------------------------------------------------------------

void timelineSortsByFrame() {
    Timeline timeline;
    const int64_t frames[] = {500, 100, 900, 100, 300};
    for (size_t i = 0; i < 5; ++i) {
        Event e = makeEvent(kNoteOn, frames[i], static_cast<uint8_t>(i));
        e.sequence = i;
        timeline.push(e);
    }

    std::vector<int64_t> out;
    while (const Event* e = timeline.top()) {
        out.push_back(e->frame);
        timeline.pop();
    }
    check(out == std::vector<int64_t>({100, 100, 300, 500, 900}), "timeline sorts by frame");
}

void timelineKeepsProductionOrderWithinAFrame() {
    Timeline timeline;
    for (uint8_t i = 0; i < 5; ++i) {
        Event e = makeEvent(kNoteOn, 480, static_cast<uint8_t>(60 + i));
        e.sequence = i;
        timeline.push(e);
    }

    std::vector<int> keys;
    while (const Event* e = timeline.top()) {
        keys.push_back(e->key);
        timeline.pop();
    }
    check(keys == std::vector<int>({60, 61, 62, 63, 64}), "same-frame events keep production order");
}

void noteOffPrecedesNoteOnAtTheSameFrame() {
    Timeline timeline;
    Event on = makeEvent(kNoteOn, 1000, 60);
    on.sequence = 0; // pushed first, as the producer would
    Event off = makeEvent(kNoteOff, 1000, 60);
    off.sequence = 1;
    timeline.push(on);
    timeline.push(off);

    check(timeline.top()->type == kNoteOff, "a re-struck key is released before it is struck again");
    timeline.pop();
    check(timeline.top()->type == kNoteOn, "and struck immediately afterwards");
}

void controlChangesPrecedeTheNotesTheyAffect() {
    Timeline timeline;
    // Pushed in the order the scheduler produces them: notes first, then the
    // hand volumes that were set for the same frame.
    Event on = makeEvent(kNoteOn, 42, 60);
    on.sequence = 0;
    Event volume = makeEvent(kChannelVolume, 42, 0);
    volume.sequence = 1;
    Event off = makeEvent(kNoteOff, 42, 55);
    off.sequence = 2;
    timeline.push(on);
    timeline.push(volume);
    timeline.push(off);

    check(timeline.top()->type == kNoteOff, "releases happen first");
    timeline.pop();
    check(timeline.top()->type == kChannelVolume, "unmuting a hand takes effect before its notes");
    timeline.pop();
    check(timeline.top()->type == kNoteOn, "note-on last");
}

/**
 * The regression this file exists for.
 *
 * A three-note chord, scheduled the way PlaybackEngine schedules it: each note's
 * on and off pushed together. All three must sound at frame 0 and be released
 * together at frame 24000, not one note followed by two zero-length blips.
 */
void chordPlaysAllNotes() {
    Ring ring;
    const uint8_t chord[] = {60, 64, 67};
    for (uint8_t key : chord) {
        ring.push(makeEvent(kNoteOn, 0, key));
        ring.push(makeEvent(kNoteOff, 24000, key));
    }

    CallbackSim sim;
    // 50 buffers of 512 frames covers the whole half-second note.
    for (int i = 0; i < 50; ++i) sim.run(ring, 512);

    std::vector<Applied> onsets;
    std::vector<Applied> releases;
    for (const Applied& a : sim.applied) {
        (a.type == kNoteOn ? onsets : releases).push_back(a);
    }

    check(onsets.size() == 3, "every note of the chord sounds");
    for (const Applied& a : onsets) check(a.frame == 0, "chord notes start together");
    check(releases.size() == 3, "every note of the chord is released");
    for (const Applied& a : releases) {
        check(a.frame >= 24000 && a.frame < 24000 + 512, "chord notes are held for their full length");
    }
}

/** Two hands, interleaved and overlapping — the other half of "one note at a time". */
void overlappingHandsBothSound() {
    Ring ring;
    // Left hand holds a whole note while the right hand plays four quarters.
    ring.push(makeEvent(kNoteOn, 0, 36));
    ring.push(makeEvent(kNoteOff, 20000, 36));
    for (int i = 0; i < 4; ++i) {
        ring.push(makeEvent(kNoteOn, i * 5000, static_cast<uint8_t>(72 + i)));
        ring.push(makeEvent(kNoteOff, i * 5000 + 5000, static_cast<uint8_t>(72 + i)));
    }

    CallbackSim sim;
    for (int i = 0; i < 60; ++i) sim.run(ring, 512);

    int rightHandOnsets = 0;
    bool bassHeld = false;
    for (const Applied& a : sim.applied) {
        if (a.type == kNoteOn && a.key >= 72) ++rightHandOnsets;
        if (a.type == kNoteOff && a.key == 36 && a.frame >= 20000) bassHeld = true;
    }
    check(rightHandOnsets == 4, "the melody is not swallowed by the held bass note");
    check(bassHeld, "the held bass note runs its full length underneath");
}

void staleEventsAreDropped() {
    Ring ring;
    ring.push(makeEvent(kNoteOn, 0, 60, /*generation=*/1));
    ring.push(makeEvent(kNoteOn, 0, 61, /*generation=*/2));

    CallbackSim sim;
    sim.run(ring, 512, /*generation=*/2);

    check(sim.applied.size() == 1, "events from before a seek are discarded");
    check(!sim.applied.empty() && sim.applied[0].key == 61, "the surviving event is the current one");
}

void ringIsFifoAndBounded() {
    EventRing<4> ring;
    for (uint8_t i = 0; i < 4; ++i) check(ring.push(makeEvent(kNoteOn, i, i)), "ring accepts up to capacity");
    check(!ring.push(makeEvent(kNoteOn, 9, 9)), "a full ring drops rather than blocks");

    for (uint8_t i = 0; i < 4; ++i) {
        check(ring.peek() != nullptr && ring.peek()->key == i, "ring is FIFO");
        ring.pop();
    }
    check(ring.peek() == nullptr, "ring empties");
}

void timelineRefusesOverflowWithoutLosingOrder() {
    EventTimeline<4> timeline;
    for (uint8_t i = 0; i < 4; ++i) {
        Event e = makeEvent(kNoteOn, 100 - i, i);
        e.sequence = i;
        check(timeline.push(e), "timeline accepts up to capacity");
    }
    check(!timeline.push(makeEvent(kNoteOn, 0, 9)), "a full timeline refuses rather than overwriting");
    check(timeline.top()->frame == 97, "the earliest event is still on top");
}

struct Test {
    const char* name;
    void (*run)();
};

const Test kTests[] = {
    {"timeline sorts by frame", timelineSortsByFrame},
    {"same-frame events keep production order", timelineKeepsProductionOrderWithinAFrame},
    {"note-off precedes note-on at the same frame", noteOffPrecedesNoteOnAtTheSameFrame},
    {"control changes precede the notes they affect", controlChangesPrecedeTheNotesTheyAffect},
    {"a chord plays all of its notes", chordPlaysAllNotes},
    {"overlapping hands both sound", overlappingHandsBothSound},
    {"stale events are dropped", staleEventsAreDropped},
    {"ring is FIFO and bounded", ringIsFifoAndBounded},
    {"timeline refuses overflow", timelineRefusesOverflowWithoutLosingOrder},
};

} // namespace

int main() {
    for (const Test& test : kTests) {
        const int before = gFailures;
        test.run();
        std::printf("%s  %s\n", gFailures == before ? "ok  " : "FAIL", test.name);
    }
    if (gFailures > 0) {
        std::printf("\n%d check(s) failed\n", gFailures);
        return 1;
    }
    std::printf("\nall %zu tests passed\n", sizeof(kTests) / sizeof(kTests[0]));
    return 0;
}
