package dev.kaiharimoto.masterkey.core

import com.google.common.truth.Truth.assertThat
import dev.kaiharimoto.masterkey.core.midi.MidiLoader
import dev.kaiharimoto.masterkey.core.midi.MidiWriteException
import dev.kaiharimoto.masterkey.core.midi.MidiWriter
import dev.kaiharimoto.masterkey.core.model.Hand
import dev.kaiharimoto.masterkey.core.model.KeySignature
import dev.kaiharimoto.masterkey.core.model.Note
import dev.kaiharimoto.masterkey.core.model.Piece
import dev.kaiharimoto.masterkey.core.model.TempoMap
import dev.kaiharimoto.masterkey.core.model.TimeSignature
import org.junit.Test

/**
 * The writer is checked by round trip, not by asserting bytes.
 *
 * What matters is that a piece written and read back is the same piece — the
 * exact encoding is an implementation detail, and pinning it would make every
 * future change to the writer look like a regression. The loader is already
 * covered against real-world files by [MidiLoaderTest], so pairing the two is a
 * genuine end-to-end check rather than two halves of the same mistake.
 */
class MidiWriterTest {

    private val ppq = 480

    private fun pieceOf(
        notes: List<Note>,
        tempos: List<Pair<Long, Int>> = emptyList(),
        timeSignatures: List<TimeSignature> = listOf(TimeSignature(0, 4, 4)),
        keySignatures: List<KeySignature> = emptyList(),
        endTick: Long = notes.maxOfOrNull { it.endTick } ?: 0L,
    ) = Piece(
        notes = notes.sortedWith(compareBy({ it.startTick }, { it.pitch })),
        tempoMap = TempoMap.build(ppq, tempos),
        timeSignatures = timeSignatures,
        keySignatures = keySignatures,
        endTick = endTick,
    )

    private fun note(
        pitch: Int,
        start: Long,
        end: Long,
        hand: Hand = Hand.RIGHT,
        velocity: Int = 90,
    ) = Note(pitch, start, end, velocity, hand)

    private fun roundTrip(piece: Piece): Piece = MidiLoader.load(MidiWriter.write(piece))

    private fun Note.core() = listOf(pitch, startTick, endTick, velocity, hand)

    @Test
    fun `a single note survives the round trip`() {
        val piece = pieceOf(listOf(note(60, 0, 480)))

        val reloaded = roundTrip(piece)

        assertThat(reloaded.notes).hasSize(1)
        with(reloaded.notes.first()) {
            assertThat(pitch).isEqualTo(60)
            assertThat(startTick).isEqualTo(0)
            assertThat(endTick).isEqualTo(480)
            assertThat(velocity).isEqualTo(90)
        }
    }

    @Test
    fun `a chord keeps every note`() {
        // The failure this guards against is real: an event ordering that plays
        // the first note of a chord and drops the rest has already shipped once
        // in this app, in the audio queue.
        val piece = pieceOf(
            listOf(note(60, 0, 480), note(64, 0, 480), note(67, 0, 480), note(72, 0, 480)),
        )

        val reloaded = roundTrip(piece)

        assertThat(reloaded.notes.map { it.pitch }).containsExactly(60, 64, 67, 72)
        assertThat(reloaded.notes.map { it.endTick }.toSet()).containsExactly(480L)
    }

    @Test
    fun `hands survive because they are written to separate tracks`() {
        val piece = pieceOf(
            listOf(
                note(72, 0, 480, Hand.RIGHT),
                note(76, 480, 960, Hand.RIGHT),
                note(48, 0, 960, Hand.LEFT),
                note(43, 960, 1440, Hand.LEFT),
                note(79, 960, 1440, Hand.RIGHT),
            ),
        )

        val reloaded = roundTrip(piece)

        val byPitch = reloaded.notes.associateBy { it.pitch }
        assertThat(byPitch.getValue(72).hand).isEqualTo(Hand.RIGHT)
        assertThat(byPitch.getValue(76).hand).isEqualTo(Hand.RIGHT)
        assertThat(byPitch.getValue(79).hand).isEqualTo(Hand.RIGHT)
        assertThat(byPitch.getValue(48).hand).isEqualTo(Hand.LEFT)
        assertThat(byPitch.getValue(43).hand).isEqualTo(Hand.LEFT)
    }

    @Test
    fun `a key struck again before the first has ended stays two notes`() {
        val piece = pieceOf(listOf(note(60, 0, 960), note(60, 480, 1440)))

        val reloaded = roundTrip(piece)

        assertThat(reloaded.notes).hasSize(2)
        assertThat(reloaded.notes.map { it.startTick }).containsExactly(0L, 480L)
    }

    @Test
    fun `a key released and re-struck on the same tick is not silenced`() {
        // Note-off must be emitted before note-on at a shared tick. Emit them the
        // other way round and the release kills the note that has just started.
        val piece = pieceOf(listOf(note(60, 0, 480), note(60, 480, 960)))

        val reloaded = roundTrip(piece)

        assertThat(reloaded.notes).hasSize(2)
        assertThat(reloaded.notes.map { it.startTick to it.endTick })
            .containsExactly(0L to 480L, 480L to 960L)
    }

    @Test
    fun `tempo changes survive`() {
        val piece = pieceOf(
            notes = listOf(note(60, 0, 480), note(62, 1920, 2400)),
            tempos = listOf(0L to 500_000, 1920L to 300_000),
        )

        val reloaded = roundTrip(piece)

        assertThat(reloaded.tempoMap.tempoChanges.map { it.tick to it.microsPerQuarter })
            .containsExactly(0L to 500_000, 1920L to 300_000)
            .inOrder()
        assertThat(reloaded.tempoMap.tickToMicros(2400))
            .isEqualTo(piece.tempoMap.tickToMicros(2400))
    }

    @Test
    fun `time and key signature changes survive`() {
        val piece = pieceOf(
            notes = listOf(note(60, 0, 480), note(62, 1440, 1920)),
            timeSignatures = listOf(TimeSignature(0, 3, 4), TimeSignature(1440, 6, 8)),
            keySignatures = listOf(KeySignature(0, -3, true), KeySignature(1440, 2, false)),
        )

        val reloaded = roundTrip(piece)

        assertThat(reloaded.timeSignatures.map { Triple(it.tick, it.numerator, it.denominator) })
            .containsExactly(Triple(0L, 3, 4), Triple(1440L, 6, 8))
            .inOrder()
        assertThat(reloaded.keySignatures.map { Triple(it.tick, it.sharps, it.isMinor) })
            .containsExactly(Triple(0L, -3, true), Triple(1440L, 2, false))
            .inOrder()
    }

    @Test
    fun `a trailing rest is kept, so the piece does not shrink on every save`() {
        val piece = pieceOf(listOf(note(60, 0, 480)), endTick = 3840)

        val reloaded = roundTrip(piece)

        assertThat(reloaded.endTick).isEqualTo(3840)
    }

    @Test
    fun `a gap longer than a single variable-length byte is encoded correctly`() {
        // 127 ticks is the boundary: anything past it needs a multi-byte delta,
        // and getting that wrong shifts every event after it.
        val piece = pieceOf(listOf(note(60, 0, 128), note(62, 100_000, 100_480)))

        val reloaded = roundTrip(piece)

        assertThat(reloaded.notes.map { it.startTick }).containsExactly(0L, 100_000L)
        assertThat(reloaded.notes.last().endTick).isEqualTo(100_480)
    }

    @Test
    fun `a dense piece survives note for note`() {
        val notes = buildList {
            for (bar in 0 until 24) {
                for (step in 0 until 8) {
                    val start = bar * 1920L + step * 240L
                    add(note(60 + (step % 5) * 2, start, start + 240, Hand.RIGHT, 70 + step))
                    if (step % 2 == 0) {
                        add(note(36 + (bar % 7), start, start + 480, Hand.LEFT, 60))
                    }
                }
            }
        }
        val piece = pieceOf(notes)

        val reloaded = roundTrip(piece)

        assertThat(reloaded.notes.map { it.core() })
            .containsExactlyElementsIn(piece.notes.map { it.core() })
            .inOrder()
    }

    @Test
    fun `writing is stable, so saving twice changes nothing`() {
        val piece = pieceOf(
            notes = listOf(note(60, 0, 480), note(48, 0, 960, Hand.LEFT), note(64, 240, 720)),
            tempos = listOf(0L to 400_000),
            keySignatures = listOf(KeySignature(0, 1, false)),
        )

        val once = MidiWriter.write(piece)
        val twice = MidiWriter.write(MidiLoader.load(once))

        assertThat(twice).isEqualTo(once)
    }

    @Test
    fun `a file built by hand survives load, write and load again`() {
        val bytes = TestSmf()
            .track {
                tempo(0, 500_000)
                timeSignature(0, 4, 2)
            }
            .track {
                noteOn(0, channel = 0, pitch = 72)
                noteOn(0, channel = 0, pitch = 76)
                noteOff(480, channel = 0, pitch = 72)
                noteOff(0, channel = 0, pitch = 76)
            }
            .track {
                noteOn(0, channel = 1, pitch = 48)
                noteOnZeroVelocity(960, channel = 1, pitch = 48)
            }
            .build()

        val first = MidiLoader.load(bytes)
        val second = MidiLoader.load(MidiWriter.write(first))

        assertThat(second.notes.map { it.core() })
            .containsExactlyElementsIn(first.notes.map { it.core() })
            .inOrder()
        assertThat(second.endTick).isEqualTo(first.endTick)
    }

    @Test
    fun `velocity zero is written as one, because zero would mean note off`() {
        val piece = pieceOf(listOf(note(60, 0, 480, velocity = 0)))

        val reloaded = roundTrip(piece)

        assertThat(reloaded.notes).hasSize(1)
        assertThat(reloaded.notes.first().velocity).isEqualTo(1)
    }

    @Test
    fun `refuses a piece with no notes rather than writing an unopenable file`() {
        val piece = pieceOf(emptyList())

        val error = runCatching { MidiWriter.write(piece) }.exceptionOrNull()

        assertThat(error).isInstanceOf(MidiWriteException::class.java)
    }

    @Test
    fun `refuses an SMPTE piece, whose ticks are not musical time`() {
        val piece = Piece(
            notes = listOf(note(60, 0, 480)),
            tempoMap = TempoMap.smpte(2400.0),
            timeSignatures = listOf(TimeSignature(0, 4, 4)),
            keySignatures = emptyList(),
            endTick = 480,
        )

        val error = runCatching { MidiWriter.write(piece) }.exceptionOrNull()

        assertThat(MidiWriter.canWrite(piece)).isFalse()
        assertThat(error).isInstanceOf(MidiWriteException::class.java)
    }
}
