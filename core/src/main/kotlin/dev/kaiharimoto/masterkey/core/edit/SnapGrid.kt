package dev.kaiharimoto.masterkey.core.edit

import dev.kaiharimoto.masterkey.core.model.Piece
import kotlin.math.roundToLong

/**
 * How finely an edit is allowed to land.
 *
 * Dragging a note by hand on a tablet is accurate to a few pixels, which at a
 * typical zoom is a few dozen ticks — nowhere near enough to put a note exactly
 * on a beat. Snapping is therefore not a convenience, it is the thing that makes
 * touch editing produce music rather than approximately-music. [FREE] exists for
 * the opposite case: fixing a passage that was played in rather than written,
 * where the grid is the wrong answer.
 */
enum class SnapGrid(
    val label: String,
    /** How many of these fit in a whole note. Zero means no grid at all. */
    private val perWhole: Int,
    private val triplet: Boolean,
) {
    QUARTER("1/4", 4, false),
    EIGHTH("1/8", 8, false),
    SIXTEENTH("1/16", 16, false),
    THIRTY_SECOND("1/32", 32, false),
    EIGHTH_TRIPLET("1/8T", 8, true),
    SIXTEENTH_TRIPLET("1/16T", 16, true),
    FREE("Free", 0, false);

    val isFree: Boolean get() = this == FREE

    /**
     * One grid step, in ticks.
     *
     * Multiplied before dividing so triplets stay exact at every resolution that
     * turns up in practice — 480 ppq gives an eighth-note triplet of 160, 96 ppq
     * gives 32, and neither loses a tick to rounding.
     */
    fun unitTicks(ticksPerQuarter: Int): Long {
        if (perWhole == 0) return 1L
        val ppq = ticksPerQuarter.coerceAtLeast(1).toLong()
        return if (triplet) {
            (ppq * 4L * 2L) / (perWhole * 3L)
        } else {
            (ppq * 4L) / perWhole
        }.coerceAtLeast(1L)
    }

    /**
     * Rounds [tick] to the nearest grid line.
     *
     * [anchor] is where the grid starts, and it is not always zero: a piece with
     * a pickup bar or a mid-piece change of metre has its beats displaced from
     * the top of the file, and snapping to absolute tick 0 would place every
     * note after the change fractionally off the beat.
     *
     * [minTick] is the floor. It defaults to zero, which is right everywhere
     * inside a piece — but it has to be a parameter rather than a hardcoded
     * clamp, because editing can reach *before* the start of the piece to make
     * room there. Hardcoding it is what made the whole pre-roll feature dead
     * code in v1.6.0: the rebase that runs when a note lands at a negative tick
     * could never fire, because no note could ever land at one.
     */
    fun snap(tick: Long, ticksPerQuarter: Int, anchor: Long = 0L, minTick: Long = 0L): Long {
        if (isFree) return tick.coerceAtLeast(minTick)
        val unit = unitTicks(ticksPerQuarter)
        val steps = ((tick - anchor).toDouble() / unit).roundToLong()
        return (anchor + steps * unit).coerceAtLeast(minTick)
    }

    companion object {
        val DEFAULT = SIXTEENTH

        /**
         * The grid origin in force at [tick]: the start of the bar-grid segment
         * the current time signature began.
         */
        fun anchorFor(piece: Piece, tick: Long): Long = piece.timeSignatureAt(tick).tick
    }
}
