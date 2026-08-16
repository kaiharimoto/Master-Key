package dev.kaiharimoto.masterkey.core.keyboard

import dev.kaiharimoto.masterkey.core.midi.Pitch
import dev.kaiharimoto.masterkey.core.model.Note
import dev.kaiharimoto.masterkey.core.model.Piece

/**
 * A span of the keyboard to draw.
 *
 * Both ends are always white keys. That is not cosmetic: black keys are drawn
 * straddling the boundary between their neighbouring whites, so a range starting
 * or ending on a black key leaves a half-key hanging off the edge.
 */
data class KeyRange(val low: Int, val high: Int) {

    init {
        require(low <= high) { "range is inverted: $low..$high" }
    }

    val semitoneSpan: Int get() = high - low + 1

    val whiteKeyCount: Int get() = (low..high).count { Pitch.isWhite(it) }

    fun contains(pitch: Int): Boolean = pitch in low..high

    /** Zero-based index of a white key within this range, or -1 if not white/in range. */
    fun whiteIndexOf(pitch: Int): Int {
        if (!contains(pitch) || !Pitch.isWhite(pitch)) return -1
        return (low until pitch).count { Pitch.isWhite(it) }
    }

    /** Every C in range, for the octave labels that anchor reading. */
    fun octaveMarkers(): List<Int> = (low..high).filter { Math.floorMod(it, 12) == 0 }

    companion object {
        val FULL_PIANO = KeyRange(Pitch.LOWEST_PIANO, Pitch.HIGHEST_PIANO)
    }
}

/** One span of the piece that shares a key range, for adaptive panning. */
data class RangeSection(
    val startTick: Long,
    val endTick: Long,
    val range: KeyRange,
)

/**
 * Chooses which part of the keyboard to draw by analysing the score.
 *
 * Drawing all 88 keys wastes most of the screen on a piece that only uses two
 * octaves — the keys end up so narrow that the falling notes are hard to line up
 * with them, which defeats the point. Equally, a range clamped too tightly makes
 * keys enormous and the layout jumpy.
 */
object KeyRangeSelector {

    /** Roughly two octaves; below this the keys get comically wide. */
    const val DEFAULT_MIN_WHITE_KEYS = 15

    /** Roughly five octaves; beyond this the keys get too thin to aim at. */
    const val DEFAULT_MAX_WHITE_KEYS = 36

    /**
     * Fixed range covering every note in the piece.
     *
     * Returns null when the music is wider than [maxWhiteKeys], which is the
     * caller's signal to use [adaptiveSections] instead.
     */
    fun forPitches(
        pitches: Iterable<Int>,
        minWhiteKeys: Int = DEFAULT_MIN_WHITE_KEYS,
        maxWhiteKeys: Int = DEFAULT_MAX_WHITE_KEYS,
    ): KeyRange? {
        val iterator = pitches.iterator()
        if (!iterator.hasNext()) return null

        var lowest = Int.MAX_VALUE
        var highest = Int.MIN_VALUE
        for (p in pitches) {
            if (p < lowest) lowest = p
            if (p > highest) highest = p
        }

        val snapped = snapOutward(lowest, highest)
        val padded = padToMinimum(snapped, minWhiteKeys)
        return if (padded.whiteKeyCount > maxWhiteKeys) null else padded
    }

    /** Same as [forPitches] but always returns something, clamping to the max. */
    fun forPitchesClamped(
        pitches: Iterable<Int>,
        minWhiteKeys: Int = DEFAULT_MIN_WHITE_KEYS,
        maxWhiteKeys: Int = DEFAULT_MAX_WHITE_KEYS,
    ): KeyRange {
        forPitches(pitches, minWhiteKeys, maxWhiteKeys)?.let { return it }
        val list = pitches.toList()
        if (list.isEmpty()) return KeyRange.FULL_PIANO
        val snapped = snapOutward(list.min(), list.max())
        return trimToMaximum(snapped, maxWhiteKeys, list)
    }

    fun forPiece(
        piece: Piece,
        minWhiteKeys: Int = DEFAULT_MIN_WHITE_KEYS,
        maxWhiteKeys: Int = DEFAULT_MAX_WHITE_KEYS,
    ): KeyRange = forPitchesClamped(piece.notes.map { it.pitch }, minWhiteKeys, maxWhiteKeys)

    /**
     * Splits a wide piece into sections that each fit comfortably, so the view can
     * pan between them.
     *
     * Boundaries are only placed where there is a real gap in the music — panning
     * mid-phrase is disorienting, and a piece that yanks sideways while you are
     * reading it is worse than one drawn slightly too small.
     */
    fun adaptiveSections(
        piece: Piece,
        minWhiteKeys: Int = DEFAULT_MIN_WHITE_KEYS,
        maxWhiteKeys: Int = DEFAULT_MAX_WHITE_KEYS,
        /** A rest at least this long (in ticks) is a safe place to pan. */
        minGapTicks: Long = piece.tempoMap.ticksPerQuarter.toLong(),
    ): List<RangeSection> {
        if (piece.notes.isEmpty()) return emptyList()

        val fixed = forPitches(piece.notes.map { it.pitch }, minWhiteKeys, maxWhiteKeys)
        if (fixed != null) {
            return listOf(RangeSection(0, piece.endTick, fixed))
        }

        // Candidate boundaries: points where nothing is sounding for a while.
        val boundaries = findGaps(piece.notes, minGapTicks)

        val sections = mutableListOf<RangeSection>()
        var sectionStart = 0L
        var index = 0
        val notes = piece.notes

        for (boundary in boundaries + piece.endTick) {
            val slice = mutableListOf<Int>()
            var scan = index
            while (scan < notes.size && notes[scan].startTick < boundary) {
                slice += notes[scan].pitch
                scan++
            }
            if (slice.isNotEmpty()) {
                val candidate = forPitchesClamped(slice, minWhiteKeys, maxWhiteKeys)
                val previous = sections.lastOrNull()
                // Merge with the previous section when the range barely moved —
                // a two-key shift is not worth an animated pan.
                if (previous != null && rangesAreClose(previous.range, candidate)) {
                    sections[sections.lastIndex] = previous.copy(
                        endTick = boundary,
                        range = union(previous.range, candidate, maxWhiteKeys, slice),
                    )
                } else {
                    sections += RangeSection(sectionStart, boundary, candidate)
                }
            }
            sectionStart = boundary
            index = scan
        }

        return sections.ifEmpty {
            listOf(RangeSection(0, piece.endTick, forPiece(piece, minWhiteKeys, maxWhiteKeys)))
        }
    }

    private fun findGaps(notes: List<Note>, minGapTicks: Long): List<Long> {
        val gaps = mutableListOf<Long>()
        var soundingUntil = 0L
        for (note in notes) {
            if (note.startTick - soundingUntil >= minGapTicks && soundingUntil > 0) {
                gaps += note.startTick
            }
            if (note.endTick > soundingUntil) soundingUntil = note.endTick
        }
        return gaps
    }

    private fun rangesAreClose(a: KeyRange, b: KeyRange): Boolean =
        kotlin.math.abs(a.low - b.low) <= 4 && kotlin.math.abs(a.high - b.high) <= 4

    private fun union(a: KeyRange, b: KeyRange, maxWhiteKeys: Int, pitches: List<Int>): KeyRange {
        val merged = snapOutward(minOf(a.low, b.low), maxOf(a.high, b.high))
        return if (merged.whiteKeyCount <= maxWhiteKeys) merged else b
    }

    /** Pushes both ends outward to the nearest white key, staying on the piano. */
    internal fun snapOutward(lowest: Int, highest: Int): KeyRange {
        var low = lowest.coerceIn(Pitch.LOWEST_PIANO, Pitch.HIGHEST_PIANO)
        var high = highest.coerceIn(Pitch.LOWEST_PIANO, Pitch.HIGHEST_PIANO)
        while (low > Pitch.LOWEST_PIANO && !Pitch.isWhite(low)) low--
        while (high < Pitch.HIGHEST_PIANO && !Pitch.isWhite(high)) high++
        // If we hit the very bottom or top on a black key, step inward instead —
        // the boundary must still be white.
        while (!Pitch.isWhite(low)) low++
        while (!Pitch.isWhite(high)) high--
        return KeyRange(low, high)
    }

    /** Widens a narrow range symmetrically until it reaches [minWhiteKeys]. */
    internal fun padToMinimum(range: KeyRange, minWhiteKeys: Int): KeyRange {
        var low = range.low
        var high = range.high
        var extendLow = true

        while (countWhite(low, high) < minWhiteKeys) {
            val canGoLower = low > Pitch.LOWEST_PIANO
            val canGoHigher = high < Pitch.HIGHEST_PIANO
            if (!canGoLower && !canGoHigher) break

            if (extendLow && canGoLower) {
                low = previousWhite(low)
            } else if (canGoHigher) {
                high = nextWhite(high)
            } else if (canGoLower) {
                low = previousWhite(low)
            }
            extendLow = !extendLow
        }
        return KeyRange(low, high)
    }

    /**
     * Narrows an over-wide range, dropping whichever end carries fewer notes —
     * outlying notes at one extreme shouldn't force the whole keyboard wide.
     */
    private fun trimToMaximum(range: KeyRange, maxWhiteKeys: Int, pitches: List<Int>): KeyRange {
        var low = range.low
        var high = range.high
        while (countWhite(low, high) > maxWhiteKeys && low < high) {
            val notesLow = pitches.count { it < low + 12 }
            val notesHigh = pitches.count { it > high - 12 }
            if (notesLow <= notesHigh) low = nextWhite(low) else high = previousWhite(high)
        }
        return KeyRange(low, high)
    }

    private fun countWhite(low: Int, high: Int) = (low..high).count { Pitch.isWhite(it) }

    private fun nextWhite(pitch: Int): Int {
        var p = pitch + 1
        while (p < Pitch.HIGHEST_PIANO && !Pitch.isWhite(p)) p++
        return p
    }

    private fun previousWhite(pitch: Int): Int {
        var p = pitch - 1
        while (p > Pitch.LOWEST_PIANO && !Pitch.isWhite(p)) p--
        return p
    }
}
