package dev.kaiharimoto.masterkey.core.midi

import dev.atsushieno.ktmidi.Midi1CompoundMessage
import dev.atsushieno.ktmidi.Midi1Music
import dev.atsushieno.ktmidi.MidiMetaType
import dev.atsushieno.ktmidi.read
import dev.kaiharimoto.masterkey.core.hands.HandAssigner
import dev.kaiharimoto.masterkey.core.model.KeySignature
import dev.kaiharimoto.masterkey.core.model.Note
import dev.kaiharimoto.masterkey.core.model.Piece
import dev.kaiharimoto.masterkey.core.model.TempoMap
import dev.kaiharimoto.masterkey.core.model.TimeSignature

/** Raw note event before hands are assigned. */
internal data class RawNote(
    val pitch: Int,
    val startTick: Long,
    val endTick: Long,
    val velocity: Int,
    val track: Int,
    val channel: Int,
)

class MidiParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Turns a Standard MIDI File into a [Piece].
 *
 * ktmidi does the binary decoding (running status, variable-length quantities,
 * chunk framing); this layer handles the parts that are data quirks rather than
 * format details, and every one of them shows up in real files:
 *
 *  - note-off written as note-on with velocity 0, which most files actually use
 *  - the same key struck again before its note-off arrives
 *  - type 0 files, where every channel shares one track and hands must be inferred
 *  - percussion on channel 9, which a piano app should ignore
 *  - dangling note-ons with no matching note-off at end of track
 */
object MidiLoader {

    private const val STATUS_NOTE_OFF = 0x80
    private const val STATUS_NOTE_ON = 0x90
    private const val META_STATUS = 0xFF
    private const val PERCUSSION_CHANNEL = 9

    fun load(bytes: ByteArray): Piece {
        val music = Midi1Music()
        try {
            music.read(bytes.toList())
        } catch (t: Throwable) {
            throw MidiParseException("Could not read this MIDI file: ${t.message}", t)
        }

        if (music.format.toInt() == 2) {
            throw MidiParseException(
                "This is a type 2 MIDI file, which holds independent sequences rather " +
                    "than one piece. Master Key can't lay that out as a single song.",
            )
        }

        val division = music.deltaTimeSpec
        val isSmpte = division < 0 || (division and 0x8000) != 0

        val rawNotes = mutableListOf<RawNote>()
        val tempoChanges = mutableListOf<Pair<Long, Int>>()
        val timeSignatures = mutableListOf<TimeSignature>()
        val keySignatures = mutableListOf<KeySignature>()
        var endTick = 0L

        music.tracks.forEachIndexed { trackIndex, track ->
            var tick = 0L
            // Open note-ons keyed by (channel, pitch). A list rather than a single
            // slot because a key can legitimately be re-struck before its note-off.
            val pending = HashMap<Int, ArrayDeque<Pair<Long, Int>>>()

            for (event in track.events) {
                tick += event.deltaTime.toLong()
                val message = event.message
                val status = message.statusCode.toInt() and 0xFF

                when {
                    status == META_STATUS -> {
                        val metaType = message.msb.toInt() and 0xFF
                        val compound = message as? Midi1CompoundMessage
                        val data = compound?.extraData
                        val offset = compound?.extraDataOffset ?: 0
                        val length = compound?.extraDataLength ?: 0

                        when (metaType) {
                            MidiMetaType.TEMPO -> if (data != null && length >= 3) {
                                val micros = ((data[offset].toInt() and 0xFF) shl 16) or
                                    ((data[offset + 1].toInt() and 0xFF) shl 8) or
                                    (data[offset + 2].toInt() and 0xFF)
                                if (micros > 0) tempoChanges += tick to micros
                            }

                            MidiMetaType.TIME_SIGNATURE -> if (data != null && length >= 2) {
                                val numerator = data[offset].toInt() and 0xFF
                                // The denominator is stored as a power-of-two exponent:
                                // 3 means an eighth note, not three.
                                val denominator = 1 shl (data[offset + 1].toInt() and 0xFF)
                                if (numerator in 1..64 && denominator in 1..64) {
                                    timeSignatures += TimeSignature(tick, numerator, denominator)
                                }
                            }

                            MidiMetaType.KEY_SIGNATURE -> if (data != null && length >= 2) {
                                val sharps = data[offset].toInt() // signed: negative = flats
                                val isMinor = (data[offset + 1].toInt() and 0xFF) == 1
                                if (sharps in -7..7) {
                                    keySignatures += KeySignature(tick, sharps, isMinor)
                                }
                            }

                            MidiMetaType.END_OF_TRACK -> endTick = maxOf(endTick, tick)
                        }
                    }

                    status and 0xF0 == STATUS_NOTE_ON -> {
                        val channel = message.channel.toInt() and 0x0F
                        val pitch = message.msb.toInt() and 0x7F
                        val velocity = message.lsb.toInt() and 0x7F
                        if (channel != PERCUSSION_CHANNEL) {
                            if (velocity > 0) {
                                pending.getOrPut(key(channel, pitch)) { ArrayDeque() }
                                    .addLast(tick to velocity)
                            } else {
                                // Velocity 0 is the note-off most files actually emit.
                                closeNote(pending, rawNotes, channel, pitch, tick, trackIndex)
                            }
                        }
                    }

                    status and 0xF0 == STATUS_NOTE_OFF -> {
                        val channel = message.channel.toInt() and 0x0F
                        val pitch = message.msb.toInt() and 0x7F
                        if (channel != PERCUSSION_CHANNEL) {
                            closeNote(pending, rawNotes, channel, pitch, tick, trackIndex)
                        }
                    }
                }
            }

            // A note left hanging at end of track gets a short nominal length
            // rather than being dropped — dropping it would silently lose the
            // last chord of a badly-terminated file.
            for ((composite, queue) in pending) {
                val channel = composite shr 8
                val pitch = composite and 0xFF
                while (queue.isNotEmpty()) {
                    val (start, velocity) = queue.removeFirst()
                    rawNotes += RawNote(pitch, start, maxOf(tick, start + 1), velocity, trackIndex, channel)
                }
            }
            endTick = maxOf(endTick, tick)
        }

        if (rawNotes.isEmpty()) {
            throw MidiParseException("This MIDI file contains no playable notes.")
        }

        val ticksPerQuarter = if (isSmpte) 1 else division
        val tempoMap = if (isSmpte) {
            TempoMap.smpte(Midi1Music.getSmpteTicksPerSeconds(division).toDouble())
        } else {
            TempoMap.build(ticksPerQuarter.coerceAtLeast(1), tempoChanges)
        }

        val notes = HandAssigner.assign(rawNotes)
            .sortedWith(compareBy({ it.startTick }, { it.pitch }))

        return Piece(
            notes = notes,
            tempoMap = tempoMap,
            timeSignatures = timeSignatures.sortedBy { it.tick }
                .ifEmpty { listOf(TimeSignature(0, 4, 4)) },
            keySignatures = keySignatures.sortedBy { it.tick },
            endTick = maxOf(endTick, notes.maxOf { it.endTick }),
        )
    }

    private fun key(channel: Int, pitch: Int) = (channel shl 8) or pitch

    private fun closeNote(
        pending: MutableMap<Int, ArrayDeque<Pair<Long, Int>>>,
        out: MutableList<RawNote>,
        channel: Int,
        pitch: Int,
        tick: Long,
        track: Int,
    ) {
        val queue = pending[key(channel, pitch)] ?: return
        val started = queue.removeFirstOrNull() ?: return
        val (startTick, velocity) = started
        // Zero-length notes are meaningless to draw and inaudible; give them one
        // tick so they still appear on the highway.
        out += RawNote(pitch, startTick, maxOf(tick, startTick + 1), velocity, track, channel)
    }
}

/** MIDI pitch helpers, shared by the highway, the keyboard and the score. */
object Pitch {
    const val LOWEST_PIANO = 21   // A0
    const val HIGHEST_PIANO = 108 // C8
    const val MIDDLE_C = 60

    private val NAMES_SHARP = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val NAMES_FLAT = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
    private val SOLFEGE = arrayOf("Do", "Di", "Re", "Ri", "Mi", "Fa", "Fi", "Sol", "Si", "La", "Li", "Ti")

    private val WHITE_PITCH_CLASSES = booleanArrayOf(
        true, false, true, false, true, true, false, true, false, true, false, true,
    )

    fun isWhite(pitch: Int): Boolean = WHITE_PITCH_CLASSES[Math.floorMod(pitch, 12)]

    fun isBlack(pitch: Int): Boolean = !isWhite(pitch)

    /** Scientific pitch notation octave, where middle C (60) is C4. */
    fun octave(pitch: Int): Int = pitch / 12 - 1

    fun name(pitch: Int, useFlats: Boolean = false): String =
        (if (useFlats) NAMES_FLAT else NAMES_SHARP)[Math.floorMod(pitch, 12)]

    fun nameWithOctave(pitch: Int, useFlats: Boolean = false): String =
        "${name(pitch, useFlats)}${octave(pitch)}"

    /** German naming, where B natural is "H" and B flat is "B". */
    fun germanName(pitch: Int): String = when (Math.floorMod(pitch, 12)) {
        11 -> "H"
        10 -> "B"
        else -> name(pitch, useFlats = false)
    }

    fun solfege(pitch: Int): String = SOLFEGE[Math.floorMod(pitch, 12)]
}
