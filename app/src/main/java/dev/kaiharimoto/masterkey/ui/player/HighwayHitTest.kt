package dev.kaiharimoto.masterkey.ui.player

import dev.kaiharimoto.masterkey.core.midi.Pitch
import kotlin.math.abs

/**
 * Which part of a note a finger landed on.
 *
 * Notes fall downward, so a note's **bottom** edge is where it starts and its
 * **top** edge is where it ends — the opposite of every horizontal piano roll,
 * and the single easiest thing to get backwards in this file.
 */
enum class DragZone {
    /** The bottom edge: trims or extends the note's start. */
    START,

    /** The middle: moves the whole note in time and pitch. */
    BODY,

    /** The top edge: makes the note longer or shorter. */
    END,
}

/**
 * Turning a touch on the highway back into a note.
 *
 * Pure arithmetic, no Compose types, so the fiddly parts — which of two
 * overlapping notes wins, what happens to a note too short to have three grips —
 * are settled by unit tests rather than by poking at a tablet.
 */
object HighwayHitTest {

    /** No note here. */
    const val NONE = -1

    /**
     * The tick at [y], inverting the mapping `drawNotes` uses to place a note.
     */
    fun tickAt(y: Float, playheadTick: Long, keyLineY: Float, pixelsPerTick: Float): Long {
        if (pixelsPerTick <= 0f) return playheadTick
        return playheadTick + ((keyLineY - y) / pixelsPerTick).toLong()
    }

    /** Where on screen the tick [tick] is drawn. */
    fun yAt(tick: Long, playheadTick: Long, keyLineY: Float, pixelsPerTick: Float): Float =
        keyLineY - (tick - playheadTick) * pixelsPerTick

    /**
     * Which lane [x] falls in, above the key line.
     *
     * Not the same question as which key was pressed on the drawn keyboard. Down
     * there the black keys occupy only the top part of the row, so the answer
     * depends on the height as well. Up here the black lanes are drawn over the
     * white ones for the full height of the pane, so a black key always wins its
     * strip — which is what [dev.kaiharimoto.masterkey.ui.player.HighwayLayout.pitchAt]
     * does when told it is on the black row.
     */
    fun laneAt(layout: HighwayLayout, x: Float, lowPitch: Int, highPitch: Int): Int? =
        layout.pitchAt(x, isBlackRow = true, lowPitch = lowPitch, highPitch = highPitch)

    /**
     * Index into `model.notes` of the note under ([x], [y]), or [NONE].
     *
     * [minTouchPx] inflates a note that is drawn thinner than a fingertip, so a
     * 1/32 note at a wide zoom is still catchable. [preferIndex] — normally the
     * current selection — wins any tie, which is what keeps a selected note's
     * grips grabbable when a neighbour overlaps them.
     */
    fun noteAt(
        model: HighwayModel,
        layout: HighwayLayout,
        x: Float,
        y: Float,
        playheadTick: Long,
        keyLineY: Float,
        pixelsPerTick: Float,
        minTouchPx: Float,
        preferIndex: Int = NONE,
    ): Int {
        if (pixelsPerTick <= 0f) return NONE

        val slopTicks = (minTouchPx / pixelsPerTick).toLong().coerceAtLeast(1L)
        val tick = tickAt(y, playheadTick, keyLineY, pixelsPerTick)
        val notes = model.notes

        var best = NONE
        var bestArea = Float.MAX_VALUE
        var bestDistance = Float.MAX_VALUE
        var bestIsBlack = false

        var index = model.firstVisibleIndex(tick - slopTicks)
        while (index < notes.size) {
            val note = notes[index]
            if (note.startTick > tick + slopTicks) break
            val here = index
            index++

            val left = layout.leftOf(note.pitch)
            val width = layout.widthOf(note.pitch)
            if (x < left || x > left + width) continue

            var bottom = yAt(note.startTick, playheadTick, keyLineY, pixelsPerTick)
            var top = yAt(note.endTick, playheadTick, keyLineY, pixelsPerTick)
            // Inflate a note drawn thinner than a finger, around its middle, so
            // aiming at it stays possible without moving where it appears to be.
            val shortfall = minTouchPx - (bottom - top)
            if (shortfall > 0f) {
                top -= shortfall / 2f
                bottom += shortfall / 2f
            }
            if (y < top || y > bottom) continue

            if (here == preferIndex) return here

            val isBlack = Pitch.isBlack(note.pitch)
            val area = width * (bottom - top)
            val distance = abs(y - (top + bottom) / 2f)

            // Black lanes are painted over white ones, so a black note is the
            // one visibly on top. Otherwise the smaller note wins: a short note
            // sitting on a long held one is the hard target, and the held note
            // can be grabbed anywhere else along its length.
            val better = when {
                isBlack != bestIsBlack -> isBlack
                area != bestArea -> area < bestArea
                else -> distance < bestDistance
            }
            if (best == NONE || better) {
                best = here
                bestArea = area
                bestDistance = distance
                bestIsBlack = isBlack
            }
        }
        return best
    }

    /**
     * Which grip [y] is on, for a note drawn between [topY] and [bottomY].
     *
     * A note has three zones only while it is tall enough for each to be aimable.
     * Below that the whole note is [DragZone.BODY]: offering a 4-pixel grip is
     * worse than not offering one, because it turns "move this note" into a coin
     * toss. Resizing a short note is done by zooming in — which makes every note
     * taller — or with the inspector's length buttons.
     */
    fun zoneAt(y: Float, topY: Float, bottomY: Float, handlePx: Float): DragZone {
        val height = bottomY - topY
        if (height < handlePx * MIN_ZONES) return DragZone.BODY
        val grip = handlePx.coerceAtMost(height / 3f)
        return when {
            y >= bottomY - grip -> DragZone.START
            y <= topY + grip -> DragZone.END
            else -> DragZone.BODY
        }
    }

    /** True when the note is tall enough for its grips to be worth drawing. */
    fun hasGrips(topY: Float, bottomY: Float, handlePx: Float): Boolean =
        bottomY - topY >= handlePx * MIN_ZONES

    /** Three zones need three fingertips' worth of height between them. */
    private const val MIN_ZONES = 3f
}
