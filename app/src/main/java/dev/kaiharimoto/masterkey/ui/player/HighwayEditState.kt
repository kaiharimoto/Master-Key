package dev.kaiharimoto.masterkey.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import dev.kaiharimoto.masterkey.core.edit.NoteDraft
import dev.kaiharimoto.masterkey.core.edit.SnapGrid
import dev.kaiharimoto.masterkey.core.model.Hand

/**
 * What the highway is doing *right now*, at pointer rate.
 *
 * This exists for the same reason the playhead does (see rule 2 in
 * [NoteHighway]): a finger dragging a note produces updates as fast as the
 * screen can report them, and routing those through `PlayerUiState` would
 * recompose the whole player — both panes, the transport, the score WebView's
 * wrapper — sixty times a second.
 *
 * So everything here is written by the gesture handler and read **only inside
 * the draw lambda**, which invalidates the draw phase alone. Anything that
 * changes once per gesture rather than once per frame — the selection, the snap
 * grid, whether edit mode is on at all — belongs in `PlayerUiState` instead,
 * where it can be seen by the toolbar and the inspector.
 */
@Stable
class HighwayEditState {

    /** Index into `model.notes` of the note being dragged, or [NO_DRAG]. */
    val dragIndex = mutableIntStateOf(NO_DRAG)

    /**
     * The note as the finger currently has it.
     *
     * Held as loose fields rather than a [NoteDraft] so a drag allocates nothing
     * per frame. Valid only while [dragIndex] is set or [ghosting] is true.
     */
    val ghostPitch = mutableIntStateOf(-1)
    val ghostStart = mutableLongStateOf(0L)
    val ghostEnd = mutableLongStateOf(0L)

    /**
     * Live pinch scale on the look-ahead, 1 when nothing is being pinched.
     *
     * Kept local and multiplied into the look-ahead in the draw phase rather
     * than written to settings on every frame: `setLookAheadBeats` writes the
     * database *and* the on-disk manifest, so a pinch that committed per frame
     * would be a few hundred file writes. Committed once, on release — the same
     * arrangement the tempo slider uses.
     */
    val zoom = mutableFloatStateOf(1f)

    /**
     * The marquee rectangle, in pixels, while one is being drawn.
     *
     * Four floats rather than a `Rect` so a sweep allocates nothing per frame,
     * for the same reason the ghost is loose scalars. [marqueeActive] gates them.
     */
    val marqueeActive = mutableStateOf(false)
    val marqueeLeft = mutableFloatStateOf(0f)
    val marqueeTop = mutableFloatStateOf(0f)
    val marqueeRight = mutableFloatStateOf(0f)
    val marqueeBottom = mutableFloatStateOf(0f)

    /**
     * A whole selection being dragged, held as one offset rather than per note.
     *
     * Storing a moved copy of every selected note would allocate on every frame
     * of the drag; an offset is two numbers however many notes are moving, and
     * the draw phase applies it to each selected note as it goes.
     */
    val groupDragging = mutableStateOf(false)
    val groupDeltaTicks = mutableLongStateOf(0L)
    val groupDeltaPitch = mutableIntStateOf(0)

    val ghosting: Boolean get() = ghostPitch.intValue >= 0

    fun showGroupDrag(deltaTicks: Long, deltaPitch: Int) {
        groupDeltaTicks.longValue = deltaTicks
        groupDeltaPitch.intValue = deltaPitch
        groupDragging.value = true
    }

    fun clearGroupDrag() {
        groupDragging.value = false
        groupDeltaTicks.longValue = 0L
        groupDeltaPitch.intValue = 0
    }

    fun showMarquee(x0: Float, y0: Float, x1: Float, y1: Float) {
        marqueeLeft.floatValue = minOf(x0, x1)
        marqueeRight.floatValue = maxOf(x0, x1)
        marqueeTop.floatValue = minOf(y0, y1)
        marqueeBottom.floatValue = maxOf(y0, y1)
        marqueeActive.value = true
    }

    fun clearMarquee() {
        marqueeActive.value = false
    }

    fun showGhost(index: Int, draft: NoteDraft) {
        dragIndex.intValue = index
        ghostPitch.intValue = draft.pitch
        ghostStart.longValue = draft.startTick
        ghostEnd.longValue = draft.endTick
    }

    fun clearGhost() {
        dragIndex.intValue = NO_DRAG
        ghostPitch.intValue = -1
    }

    companion object {
        /** No note is being dragged. A new note being drawn has no index either. */
        const val NO_DRAG = -1
    }
}

/**
 * Where a dragged note ends up.
 *
 * Pure, and separated from the gesture handler so the awkward parts — which end
 * a zone moves, what a note may not be shortened past, where the grid starts
 * after a change of metre — are settled by tests rather than by feel.
 */
object NoteDrag {

    /**
     * The note after a drag of [deltaTicks] with the finger over [pitch].
     *
     * Pitch is taken as an absolute lane rather than a delta because lanes are
     * not all the same width — accumulating sideways movement in pixels drifts
     * against the keyboard, while asking "which lane is the finger in" cannot.
     */
    fun apply(
        original: NoteDraft,
        zone: DragZone,
        deltaTicks: Long,
        pitch: Int,
        grid: SnapGrid,
        ticksPerQuarter: Int,
        anchor: Long,
        /** How far before the piece a drag may reach; see the pre-roll. */
        minTick: Long = 0L,
    ): NoteDraft {
        val minLength = minLengthTicks(grid, ticksPerQuarter)
        return when (zone) {
            // Moving keeps the length and snaps the start, so a phrase dragged
            // to a different beat arrives with its rhythm intact.
            DragZone.BODY -> {
                val start = grid.snap(original.startTick + deltaTicks, ticksPerQuarter, anchor, minTick)
                original.copy(
                    pitch = pitch,
                    startTick = start,
                    endTick = start + original.lengthTicks,
                )
            }

            // The bottom edge. Trimming the front must not walk past the back.
            DragZone.START -> {
                val start = grid.snap(original.startTick + deltaTicks, ticksPerQuarter, anchor, minTick)
                    .coerceAtMost(original.endTick - minLength)
                original.copy(startTick = start)
            }

            // The top edge.
            DragZone.END -> {
                val end = grid.snap(original.endTick + deltaTicks, ticksPerQuarter, anchor)
                    .coerceAtLeast(original.startTick + minLength)
                original.copy(endTick = end)
            }
        }
    }

    /**
     * Shortest a note may be made by dragging.
     *
     * One grid step, so a snapped note can never be shortened into something the
     * grid cannot express. In Free mode it is a single tick — the point of Free
     * is that nothing is being rounded.
     */
    fun minLengthTicks(grid: SnapGrid, ticksPerQuarter: Int): Long =
        if (grid.isFree) 1L else grid.unitTicks(ticksPerQuarter)

    /** Length a freshly drawn note starts at, before the finger sizes it. */
    fun newNoteLength(grid: SnapGrid, ticksPerQuarter: Int): Long =
        if (grid.isFree) (ticksPerQuarter / 2L).coerceAtLeast(1L) else grid.unitTicks(ticksPerQuarter)

    /** A draft for a note being drawn from [startTick] up to wherever the finger is. */
    fun drawn(
        pitch: Int,
        startTick: Long,
        toTick: Long,
        velocity: Int,
        hand: Hand,
        grid: SnapGrid,
        ticksPerQuarter: Int,
        anchor: Long,
        /** How far before the piece a new note may be drawn; see the pre-roll. */
        minTick: Long = 0L,
    ): NoteDraft {
        val start = grid.snap(startTick, ticksPerQuarter, anchor, minTick)
        val minLength = newNoteLength(grid, ticksPerQuarter)
        val end = grid.snap(toTick, ticksPerQuarter, anchor, minTick)
            .coerceAtLeast(start + minLength)
        return NoteDraft(
            pitch = pitch,
            startTick = start,
            endTick = end,
            velocity = velocity,
            hand = hand,
        )
    }
}
