package dev.kaiharimoto.masterkey.core

import com.google.common.truth.Truth.assertThat
import dev.kaiharimoto.masterkey.core.midi.MidiLoader
import dev.kaiharimoto.masterkey.core.midi.MidiParseException
import dev.kaiharimoto.masterkey.core.model.Hand
import org.junit.Test

class MidiLoaderTest {

    @Test
    fun `pairs note on and note off into a single note`() {
        val bytes = TestSmf().track {
            noteOn(0, channel = 0, pitch = 60)
            noteOff(480, channel = 0, pitch = 60)
        }.build()

        val piece = MidiLoader.load(bytes)

        assertThat(piece.notes).hasSize(1)
        with(piece.notes.first()) {
            assertThat(pitch).isEqualTo(60)
            assertThat(startTick).isEqualTo(0)
            assertThat(endTick).isEqualTo(480)
            assertThat(velocity).isEqualTo(100)
        }
    }

    @Test
    fun `treats note on with velocity zero as a note off`() {
        // This is how most real MIDI files encode note-off, so getting it wrong
        // produces notes that never end.
        val bytes = TestSmf().track {
            noteOn(0, channel = 0, pitch = 64)
            noteOnZeroVelocity(240, channel = 0, pitch = 64)
        }.build()

        val piece = MidiLoader.load(bytes)

        assertThat(piece.notes).hasSize(1)
        assertThat(piece.notes.first().endTick).isEqualTo(240)
    }

    @Test
    fun `handles running status`() {
        val bytes = TestSmf().track {
            noteOn(0, channel = 0, pitch = 60)
            runningStatus(120, 62, 100)   // note-on, status byte omitted
            runningStatus(120, 60, 0)     // note-off for 60 via velocity 0
            runningStatus(120, 62, 0)     // note-off for 62
        }.build()

        val piece = MidiLoader.load(bytes)

        assertThat(piece.notes).hasSize(2)
        assertThat(piece.notes.map { it.pitch }).containsExactly(60, 62)
        assertThat(piece.notes.first { it.pitch == 60 }.endTick).isEqualTo(240)
        assertThat(piece.notes.first { it.pitch == 62 }.endTick).isEqualTo(360)
    }

    @Test
    fun `handles the same key struck again before its note off`() {
        val bytes = TestSmf().track {
            noteOn(0, channel = 0, pitch = 60, velocity = 90)
            noteOn(120, channel = 0, pitch = 60, velocity = 110)
            noteOff(120, channel = 0, pitch = 60)
            noteOff(120, channel = 0, pitch = 60)
        }.build()

        val piece = MidiLoader.load(bytes)

        // Two strikes must yield two notes; collapsing them would drop a repeated
        // note from the highway entirely.
        assertThat(piece.notes).hasSize(2)
        assertThat(piece.notes.map { it.startTick }).containsExactly(0L, 120L)
    }

    @Test
    fun `ignores percussion on channel 10`() {
        val bytes = TestSmf().track {
            noteOn(0, channel = 0, pitch = 60)
            noteOn(0, channel = 9, pitch = 42) // closed hi-hat in GM
            noteOff(240, channel = 0, pitch = 60)
            noteOff(0, channel = 9, pitch = 42)
        }.build()

        val piece = MidiLoader.load(bytes)

        assertThat(piece.notes).hasSize(1)
        assertThat(piece.notes.first().pitch).isEqualTo(60)
    }

    @Test
    fun `closes notes left hanging at end of track`() {
        val bytes = TestSmf().track {
            noteOn(0, channel = 0, pitch = 60)
            noteOn(0, channel = 0, pitch = 64)
            raw(480, 0xFF, 0x01, 0x00) // a text meta event, then end of track
        }.build()

        val piece = MidiLoader.load(bytes)

        // Dropping these would silently lose the final chord of a malformed file.
        assertThat(piece.notes).hasSize(2)
        assertThat(piece.notes.all { it.endTick > it.startTick }).isTrue()
    }

    @Test
    fun `reads tempo, time signature and key signature`() {
        val bytes = TestSmf().track {
            tempo(0, 500_000)              // 120 bpm
            timeSignature(0, 3, 2)         // 3/4
            keySignature(0, -3, false)     // E flat major
            noteOn(0, channel = 0, pitch = 60)
            noteOff(480, channel = 0, pitch = 60)
            tempo(0, 750_000)              // 80 bpm
        }.build()

        val piece = MidiLoader.load(bytes)

        assertThat(piece.timeSignatures.first().numerator).isEqualTo(3)
        assertThat(piece.timeSignatures.first().denominator).isEqualTo(4)
        assertThat(piece.keySignatures.first().sharps).isEqualTo(-3)
        assertThat(piece.keySignatures.first().displayName).isEqualTo("Eb major")
        assertThat(piece.tempoMap.bpmAt(0)).isWithin(0.01).of(120.0)
        assertThat(piece.tempoMap.bpmAt(480)).isWithin(0.01).of(80.0)
    }

    @Test
    fun `separates hands across two tracks and labels them by pitch`() {
        // Deliberately puts the LOW part on track 0. The track order must not
        // decide which hand it is — pitch must.
        val bytes = TestSmf(format = 1)
            .track {
                noteOn(0, channel = 0, pitch = 40)
                noteOff(480, channel = 0, pitch = 40)
                noteOn(0, channel = 0, pitch = 43)
                noteOff(480, channel = 0, pitch = 43)
            }
            .track {
                noteOn(0, channel = 1, pitch = 76)
                noteOff(480, channel = 1, pitch = 76)
                noteOn(0, channel = 1, pitch = 79)
                noteOff(480, channel = 1, pitch = 79)
            }
            .build()

        val piece = MidiLoader.load(bytes)

        assertThat(piece.notes.filter { it.pitch < 60 }.map { it.hand }.toSet())
            .containsExactly(Hand.LEFT)
        assertThat(piece.notes.filter { it.pitch > 60 }.map { it.hand }.toSet())
            .containsExactly(Hand.RIGHT)
    }

    @Test
    fun `rejects type 2 files with an explanation`() {
        val bytes = TestSmf(format = 2).track {
            noteOn(0, channel = 0, pitch = 60)
            noteOff(480, channel = 0, pitch = 60)
        }.build()

        val error = runCatching { MidiLoader.load(bytes) }.exceptionOrNull()

        assertThat(error).isInstanceOf(MidiParseException::class.java)
        assertThat(error).hasMessageThat().contains("type 2")
    }

    @Test
    fun `rejects a file with no notes rather than showing an empty player`() {
        val bytes = TestSmf().track { tempo(0, 500_000) }.build()

        val error = runCatching { MidiLoader.load(bytes) }.exceptionOrNull()

        assertThat(error).isInstanceOf(MidiParseException::class.java)
        assertThat(error).hasMessageThat().contains("no playable notes")
    }

    @Test
    fun `notes come back sorted by start tick`() {
        val bytes = TestSmf(format = 1)
            .track {
                noteOn(960, channel = 0, pitch = 72)
                noteOff(240, channel = 0, pitch = 72)
            }
            .track {
                noteOn(0, channel = 1, pitch = 48)
                noteOff(240, channel = 1, pitch = 48)
            }
            .build()

        val piece = MidiLoader.load(bytes)

        // The highway binary-searches this list every frame; unsorted input would
        // silently cull the wrong notes.
        assertThat(piece.notes.map { it.startTick }).isInOrder()
    }
}
