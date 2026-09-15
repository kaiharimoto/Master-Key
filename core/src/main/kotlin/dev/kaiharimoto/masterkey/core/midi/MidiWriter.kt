package dev.kaiharimoto.masterkey.core.midi

import dev.kaiharimoto.masterkey.core.model.Hand
import dev.kaiharimoto.masterkey.core.model.Note
import dev.kaiharimoto.masterkey.core.model.Piece
import java.io.ByteArrayOutputStream

class MidiWriteException(message: String) : Exception(message)

/**
 * Turns a [Piece] back into a Standard MIDI File.
 *
 * The counterpart to [MidiLoader], and written by hand rather than through
 * ktmidi for the same reason the loader's quirk handling is ours: the byte
 * layout is the thing being tested, and a file this app writes over a song the
 * user imported has to be one we can reason about completely.
 *
 * Layout is always type 1 with three tracks:
 *
 *  0. the conductor — tempo, time signature, key signature, end of track
 *  1. right-hand notes on channel 0
 *  2. left-hand notes on channel 1
 *
 * That split is not cosmetic. Hands are not stored in a MIDI file; they are
 * *inferred* on load by [dev.kaiharimoto.masterkey.core.hands.HandAssigner],
 * which groups notes by (track, channel) and labels the higher-pitched group as
 * the right hand. Writing the two hands to two tracks is therefore what makes
 * the hand assignment survive a save and reload. It also matches the channels
 * the synth already uses, via [Hand.synthChannel].
 *
 * **What round-trips:** pitch, start, end, velocity, hand, the tempo map, time
 * and key signatures, and `endTick`.
 *
 * **What does not:** fingering and score ids, which are re-derived from the
 * MusicXML at load and were never in the MIDI; the voice number; and every kind
 * of event this app has never parsed — program changes, controllers, sustain
 * pedal, track names, lyrics, markers. A file written here is the *notes*, not
 * a faithful copy of whatever came in. That is why saving an edit keeps the
 * imported original beside it.
 */
object MidiWriter {

    private const val STATUS_NOTE_OFF = 0x80
    private const val STATUS_NOTE_ON = 0x90
    private const val META_STATUS = 0xFF

    private const val META_TIME_SIGNATURE = 0x58
    private const val META_KEY_SIGNATURE = 0x59
    private const val META_TEMPO = 0x51
    private const val META_END_OF_TRACK = 0x2F

    /** The largest delta a variable-length quantity can carry: four 7-bit bytes. */
    private const val MAX_DELTA = 0x0FFF_FFFFL

    /**
     * Whether [piece] can be written back at all.
     *
     * False only for SMPTE-division files, whose ticks are absolute time rather
     * than musical time. Callers should check this before offering to edit, so
     * the refusal happens before the work rather than after it.
     */
    fun canWrite(piece: Piece): Boolean = piece.tempoMap.smpteTicksPerSecond == null

    fun write(piece: Piece): ByteArray {
        if (!canWrite(piece)) {
            throw MidiWriteException(
                "This song uses SMPTE timing, where a tick is a fraction of a second " +
                    "rather than a fraction of a beat. Master Key can't write that back.",
            )
        }
        if (piece.notes.isEmpty()) {
            // The loader rejects a file with no notes, so writing one would
            // produce a song that can never be opened again.
            throw MidiWriteException("A song needs at least one note.")
        }

        val ppq = piece.tempoMap.ticksPerQuarter
        if (ppq <= 0) throw MidiWriteException("This song has no usable tick resolution.")

        val tracks = listOf(
            conductorTrack(piece),
            noteTrack(piece.notes.filter { it.hand == Hand.RIGHT }, Hand.RIGHT.synthChannel),
            noteTrack(piece.notes.filter { it.hand == Hand.LEFT }, Hand.LEFT.synthChannel),
        )

        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray())
        writeInt32(out, 6)
        writeInt16(out, 1) // format 1
        writeInt16(out, tracks.size)
        writeInt16(out, ppq)
        for (track in tracks) {
            out.write("MTrk".toByteArray())
            writeInt32(out, track.size)
            out.write(track)
        }
        return out.toByteArray()
    }

    /**
     * One event, held at its absolute tick until delta times are computed.
     *
     * [rank] orders events that share a tick. Note-offs come first so that a key
     * released and immediately re-struck on the same tick is not silenced by its
     * own predecessor — the same ordering rule the audio callback applies in
     * `event_queue.h`.
     */
    private class AbsoluteEvent(val tick: Long, val rank: Int, val bytes: ByteArray)

    private fun conductorTrack(piece: Piece): ByteArray {
        val events = mutableListOf<AbsoluteEvent>()

        for (change in piece.tempoMap.tempoChanges) {
            val micros = change.microsPerQuarter
            events += AbsoluteEvent(
                change.tick,
                RANK_META,
                meta(
                    META_TEMPO,
                    byteArrayOf(
                        ((micros shr 16) and 0xFF).toByte(),
                        ((micros shr 8) and 0xFF).toByte(),
                        (micros and 0xFF).toByte(),
                    ),
                ),
            )
        }

        for (signature in piece.timeSignatures) {
            events += AbsoluteEvent(
                signature.tick,
                RANK_META,
                meta(
                    META_TIME_SIGNATURE,
                    byteArrayOf(
                        signature.numerator.coerceIn(1, 255).toByte(),
                        denominatorExponent(signature.denominator).toByte(),
                        24, // metronome click, in MIDI clocks; the standard default
                        8,  // 32nd notes per quarter; likewise
                    ),
                ),
            )
        }

        for (signature in piece.keySignatures) {
            events += AbsoluteEvent(
                signature.tick,
                RANK_META,
                meta(
                    META_KEY_SIGNATURE,
                    byteArrayOf(
                        signature.sharps.coerceIn(-7, 7).toByte(),
                        if (signature.isMinor) 1 else 0,
                    ),
                ),
            )
        }

        // End of track carries the piece's length, which is not always the end of
        // the last note — a piece can finish with a rest, and losing that would
        // shorten the scrubber every time the song was saved.
        val endTick = maxOf(piece.endTick, events.maxOfOrNull { it.tick } ?: 0L)
        return assemble(events, endTick)
    }

    private fun noteTrack(notes: List<Note>, channel: Int): ByteArray {
        val events = ArrayList<AbsoluteEvent>(notes.size * 2)
        for (note in notes) {
            val pitch = note.pitch.coerceIn(0, 127)
            // Velocity 0 on a note-on *is* a note-off in MIDI, so a note written
            // with it would be silently dropped by any reader, ours included.
            val velocity = note.velocity.coerceIn(1, 127)
            events += AbsoluteEvent(
                note.startTick,
                RANK_NOTE_ON,
                byteArrayOf((STATUS_NOTE_ON or channel).toByte(), pitch.toByte(), velocity.toByte()),
            )
            events += AbsoluteEvent(
                maxOf(note.endTick, note.startTick + 1),
                RANK_NOTE_OFF,
                byteArrayOf((STATUS_NOTE_OFF or channel).toByte(), pitch.toByte(), 0),
            )
        }
        return assemble(events, events.maxOfOrNull { it.tick } ?: 0L)
    }

    /**
     * Sorts by absolute tick and emits delta times.
     *
     * A status byte is written on every event; running status would be legal and
     * slightly smaller, but it is also the single most common source of
     * off-by-one bugs in MIDI writers, and these files are a few kilobytes.
     */
    private fun assemble(events: List<AbsoluteEvent>, endTick: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var previousTick = 0L

        for (event in events.sortedWith(compareBy({ it.tick }, { it.rank }))) {
            val tick = maxOf(event.tick, previousTick)
            writeVarLen(out, tick - previousTick)
            out.write(event.bytes)
            previousTick = tick
        }

        writeVarLen(out, maxOf(0L, endTick - previousTick))
        out.write(META_STATUS)
        out.write(META_END_OF_TRACK)
        out.write(0)
        return out.toByteArray()
    }

    private fun meta(type: Int, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(META_STATUS)
        out.write(type)
        out.write(data.size)
        out.write(data)
        return out.toByteArray()
    }

    /**
     * MIDI stores a time signature's denominator as a power-of-two exponent: 3
     * means an eighth note, not three.
     *
     * Anything that is not a power of two — which the MIDI format simply cannot
     * express — is rounded down to the nearest one rather than refused, because
     * losing a save over an exotic signature would be worse than engraving it as
     * the closest thing the format has.
     */
    private fun denominatorExponent(denominator: Int): Int {
        var exponent = 0
        var value = denominator.coerceIn(1, 64)
        while (value > 1) {
            value = value shr 1
            exponent++
        }
        return exponent
    }

    private const val RANK_NOTE_OFF = 0
    private const val RANK_META = 1
    private const val RANK_NOTE_ON = 2

    private fun writeInt16(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeInt32(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 24) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    /** Variable-length quantity: seven bits per byte, high bit set on all but the last. */
    private fun writeVarLen(out: ByteArrayOutputStream, value: Long) {
        if (value > MAX_DELTA) {
            throw MidiWriteException("This song is too long to write as a MIDI file.")
        }
        val v = value.coerceAtLeast(0L)
        var buffer = v and 0x7F
        var remaining = v ushr 7
        while (remaining > 0) {
            buffer = (buffer shl 8) or 0x80 or (remaining and 0x7F)
            remaining = remaining ushr 7
        }
        while (true) {
            out.write((buffer and 0xFF).toInt())
            if (buffer and 0x80L != 0L) buffer = buffer shr 8 else break
        }
    }
}
