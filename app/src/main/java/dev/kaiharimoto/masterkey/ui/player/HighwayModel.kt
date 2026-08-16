package dev.kaiharimoto.masterkey.ui.player

import dev.kaiharimoto.masterkey.core.midi.Pitch
import dev.kaiharimoto.masterkey.core.model.Note
import dev.kaiharimoto.masterkey.core.model.Piece

/**
 * Per-song data the highway needs every frame, computed once at load.
 *
 * The renderer's per-frame job is then only "where is the playhead, which notes
 * are near it" — no scanning the whole piece, no allocation. That is the
 * difference between a smooth 120 Hz highway and one that stutters on a long
 * piece.
 */
class HighwayModel(val piece: Piece) {

    val notes: List<Note> = piece.notes

    /**
     * Longest note in the piece. Needed because [notes] is sorted by *start*
     * tick: to find everything still sounding at time T we must look back this
     * far, or a held whole note would vanish the moment a later note started.
     */
    private val maxDurationTicks: Long = notes.maxOfOrNull { it.durationTicks } ?: 0L

    /** Tick of every bar line, honouring time-signature changes. */
    val barTicks: List<Long>

    /** Tick of every beat that is not also a bar line. */
    val beatTicks: List<Long>

    /** Bar number (1-based) for each entry in [barTicks]. */
    val barNumbers: List<Int>

    init {
        val bars = mutableListOf<Long>()
        val beats = mutableListOf<Long>()
        val numbers = mutableListOf<Int>()

        val ppq = piece.tempoMap.ticksPerQuarter
        var tick = 0L
        var barNumber = 1
        var guard = 0

        while (tick <= piece.endTick && guard < MAX_GRID_LINES) {
            val signature = piece.timeSignatureAt(tick)
            val ticksPerBar = signature.ticksPerBar(ppq).coerceAtLeast(1L)
            val ticksPerBeat = signature.ticksPerBeat(ppq).coerceAtLeast(1L)

            bars += tick
            numbers += barNumber

            var beat = tick + ticksPerBeat
            while (beat < tick + ticksPerBar && beat <= piece.endTick) {
                beats += beat
                beat += ticksPerBeat
            }

            tick += ticksPerBar
            barNumber++
            guard++
        }

        barTicks = bars
        beatTicks = beats
        barNumbers = numbers
    }

    /**
     * Index of the first note that could still be visible at [tick].
     *
     * Binary search rather than a linear scan — this runs every frame, and on a
     * five-minute piece the difference is thousands of comparisons per frame
     * versus about a dozen.
     */
    fun firstVisibleIndex(tick: Long): Int {
        val target = tick - maxDurationTicks
        var low = 0
        var high = notes.size
        while (low < high) {
            val mid = (low + high) / 2
            if (notes[mid].startTick < target) low = mid + 1 else high = mid
        }
        return low
    }

    /** Notes sounding exactly at [tick], for lighting up keys on the keyboard. */
    fun soundingAt(tick: Long, into: MutableList<Note>) {
        into.clear()
        var i = firstVisibleIndex(tick)
        while (i < notes.size && notes[i].startTick <= tick) {
            val note = notes[i]
            if (note.endTick > tick) into += note
            i++
        }
    }

    /** Bar number containing [tick], 1-based, for the measure readout. */
    fun barNumberAt(tick: Long): Int {
        if (barTicks.isEmpty()) return 1
        var low = 0
        var high = barTicks.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (barTicks[mid] <= tick) low = mid else high = mid - 1
        }
        return barNumbers.getOrElse(low) { 1 }
    }

    /** Start tick of bar [number] (1-based), for loop selection by measure. */
    fun tickOfBar(number: Int): Long {
        val index = barNumbers.indexOf(number)
        return if (index >= 0) barTicks[index] else 0L
    }

    val barCount: Int get() = barNumbers.size

    companion object {
        /**
         * Safety valve. A malformed file can declare a one-tick bar length, which
         * would otherwise generate grid lines until memory runs out.
         */
        private const val MAX_GRID_LINES = 20_000
    }
}

/** Horizontal layout in continuous white-key units, so panning can be animated. */
class HighwayLayout(
    /** White-key index of the left edge. Fractional while panning. */
    val leftWhiteIndex: Float,
    /** How many white keys are visible. Fractional while panning. */
    val visibleWhiteKeys: Float,
    val width: Float,
) {
    val whiteKeyWidth: Float = if (visibleWhiteKeys > 0f) width / visibleWhiteKeys else width
    val blackKeyWidth: Float = whiteKeyWidth * 0.62f

    fun centerOf(pitch: Int): Float =
        (Pitch.whitePosition(pitch) - leftWhiteIndex) * whiteKeyWidth

    fun widthOf(pitch: Int): Float =
        if (Pitch.isWhite(pitch)) whiteKeyWidth else blackKeyWidth

    fun leftOf(pitch: Int): Float = centerOf(pitch) - widthOf(pitch) / 2f

    /** True when any part of the key is on screen. */
    fun isVisible(pitch: Int): Boolean {
        val left = leftOf(pitch)
        return left + widthOf(pitch) >= 0f && left <= width
    }

    /** Which key is under [x] on the drawn keyboard. */
    fun pitchAt(x: Float, isBlackRow: Boolean, lowPitch: Int, highPitch: Int): Int? {
        if (isBlackRow) {
            for (pitch in lowPitch..highPitch) {
                if (!Pitch.isBlack(pitch)) continue
                val left = leftOf(pitch)
                if (x >= left && x <= left + blackKeyWidth) return pitch
            }
        }
        for (pitch in lowPitch..highPitch) {
            if (!Pitch.isWhite(pitch)) continue
            val left = leftOf(pitch)
            if (x >= left && x <= left + whiteKeyWidth) return pitch
        }
        return null
    }
}
