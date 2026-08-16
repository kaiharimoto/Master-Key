package dev.kaiharimoto.masterkey.core.model

/** Which hand plays a note. Drives colour in both the highway and the score. */
enum class Hand {
    RIGHT,
    LEFT;

    /**
     * MIDI channel used by the synth. Keeping hands on separate channels is what
     * makes "mute the right hand while I practise it" a single volume write
     * instead of a re-render.
     */
    val synthChannel: Int get() = if (this == RIGHT) 0 else 1
}

/**
 * One sounding note, in musical time.
 *
 * Ticks rather than seconds throughout: tempo scaling, looping and seeking are
 * then all operations on musical time and stay correct across tempo changes
 * inside a piece. [TempoMap] is the only thing that converts to wall clock.
 */
data class Note(
    val pitch: Int,
    val startTick: Long,
    val endTick: Long,
    val velocity: Int,
    val hand: Hand,
    val track: Int = 0,
    val voice: Int = 0,
    /** Fingering from MusicXML `<technical><fingering>`, 1–5, when supplied. */
    val finger: Int? = null,
    /** Verovio `xml:id` of the notehead, for highlighting the score. */
    val scoreId: String? = null,
) {
    val durationTicks: Long get() = endTick - startTick

    init {
        require(pitch in 0..127) { "pitch out of MIDI range: $pitch" }
        require(endTick >= startTick) { "note ends before it starts" }
    }
}

/** A tempo change, with its absolute start time precomputed. */
data class TempoChange(
    val tick: Long,
    val microsPerQuarter: Int,
    /** Wall-clock microseconds from the start of the piece at [tick]. */
    val startMicros: Long,
) {
    val bpm: Double get() = 60_000_000.0 / microsPerQuarter
}

data class TimeSignature(
    val tick: Long,
    val numerator: Int,
    val denominator: Int,
) {
    /** Ticks per bar, given the file's PPQ resolution. */
    fun ticksPerBar(ticksPerQuarter: Int): Long =
        (ticksPerQuarter * 4L * numerator) / denominator

    fun ticksPerBeat(ticksPerQuarter: Int): Long =
        (ticksPerQuarter * 4L) / denominator

    override fun toString(): String = "$numerator/$denominator"
}

data class KeySignature(
    val tick: Long,
    /** Negative = flats, positive = sharps. */
    val sharps: Int,
    val isMinor: Boolean,
) {
    val displayName: String
        get() {
            val major = listOf("Cb", "Gb", "Db", "Ab", "Eb", "Bb", "F", "C", "G", "D", "A", "E", "B", "F#", "C#")
            val minor = listOf("Ab", "Eb", "Bb", "F", "C", "G", "D", "A", "E", "B", "F#", "C#", "G#", "D#", "A#")
            val index = (sharps + 7).coerceIn(0, 14)
            return if (isMinor) "${minor[index]} minor" else "${major[index]} major"
        }
}

/**
 * A fully-loaded piece, ready to play and draw.
 *
 * [notes] is sorted by start tick — the highway relies on that for binary-search
 * culling, and the scheduler relies on it to walk forward without re-sorting.
 */
data class Piece(
    val notes: List<Note>,
    val tempoMap: TempoMap,
    val timeSignatures: List<TimeSignature>,
    val keySignatures: List<KeySignature>,
    val endTick: Long,
) {
    val pitchRange: IntRange?
        get() = if (notes.isEmpty()) null else {
            var lo = Int.MAX_VALUE
            var hi = Int.MIN_VALUE
            for (n in notes) {
                if (n.pitch < lo) lo = n.pitch
                if (n.pitch > hi) hi = n.pitch
            }
            lo..hi
        }

    val durationMicros: Long get() = tempoMap.tickToMicros(endTick)

    fun timeSignatureAt(tick: Long): TimeSignature =
        timeSignatures.lastOrNull { it.tick <= tick }
            ?: TimeSignature(0, 4, 4)

    fun keySignatureAt(tick: Long): KeySignature? =
        keySignatures.lastOrNull { it.tick <= tick }

    companion object {
        val EMPTY = Piece(
            notes = emptyList(),
            tempoMap = TempoMap.default(480),
            timeSignatures = listOf(TimeSignature(0, 4, 4)),
            keySignatures = emptyList(),
            endTick = 0L,
        )
    }
}
