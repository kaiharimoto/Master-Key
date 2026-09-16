package dev.kaiharimoto.masterkey.ui.player

import com.google.common.truth.Truth.assertThat
import dev.kaiharimoto.masterkey.core.edit.NoteDraft
import dev.kaiharimoto.masterkey.core.edit.SnapGrid
import dev.kaiharimoto.masterkey.core.model.Hand
import org.junit.Test

class NoteDragTest {

    private val ppq = 480

    private val note = NoteDraft(
        pitch = 60,
        startTick = 960,
        endTick = 1440,
        velocity = 80,
        hand = Hand.RIGHT,
    )

    private fun drag(
        zone: DragZone,
        deltaTicks: Long,
        pitch: Int = note.pitch,
        grid: SnapGrid = SnapGrid.SIXTEENTH,
        anchor: Long = 0L,
    ) = NoteDrag.apply(note, zone, deltaTicks, pitch, grid, ppq, anchor)

    @Test
    fun `moving keeps the length and snaps the start`() {
        // A phrase dragged to a different beat has to arrive with its rhythm
        // intact; snapping both ends independently would stretch it.
        val moved = drag(DragZone.BODY, deltaTicks = 190)

        assertThat(moved.startTick).isEqualTo(1200) // 960 + 190 -> nearest 1/16
        assertThat(moved.lengthTicks).isEqualTo(note.lengthTicks)
    }

    @Test
    fun `moving follows the lane the finger is over`() {
        val moved = drag(DragZone.BODY, deltaTicks = 0, pitch = 67)

        assertThat(moved.pitch).isEqualTo(67)
    }

    @Test
    fun `only a body drag changes the pitch`() {
        assertThat(drag(DragZone.START, 0, pitch = 67).pitch).isEqualTo(60)
        assertThat(drag(DragZone.END, 0, pitch = 67).pitch).isEqualTo(60)
    }

    @Test
    fun `the bottom grip moves the start and leaves the end alone`() {
        val trimmed = drag(DragZone.START, deltaTicks = 120)

        assertThat(trimmed.startTick).isEqualTo(1080)
        assertThat(trimmed.endTick).isEqualTo(1440)
    }

    @Test
    fun `the top grip moves the end and leaves the start alone`() {
        val lengthened = drag(DragZone.END, deltaTicks = 240)

        assertThat(lengthened.startTick).isEqualTo(960)
        assertThat(lengthened.endTick).isEqualTo(1680)
    }

    @Test
    fun `the start cannot be dragged past the end`() {
        val trimmed = drag(DragZone.START, deltaTicks = 5_000)

        assertThat(trimmed.startTick).isEqualTo(1440 - 120) // one grid step of room
        assertThat(trimmed.endTick).isEqualTo(1440)
    }

    @Test
    fun `the end cannot be dragged past the start`() {
        val shortened = drag(DragZone.END, deltaTicks = -5_000)

        assertThat(shortened.startTick).isEqualTo(960)
        assertThat(shortened.endTick).isEqualTo(960 + 120)
    }

    @Test
    fun `free mode leaves a drag exactly where the finger put it`() {
        val moved = drag(DragZone.BODY, deltaTicks = 37, grid = SnapGrid.FREE)

        assertThat(moved.startTick).isEqualTo(997)
        assertThat(moved.lengthTicks).isEqualTo(note.lengthTicks)
    }

    @Test
    fun `free mode can still be shortened to a single tick but no further`() {
        val shortened = drag(DragZone.END, deltaTicks = -5_000, grid = SnapGrid.FREE)

        assertThat(shortened.endTick).isEqualTo(961)
    }

    @Test
    fun `the grid follows the anchor, so a pickup bar snaps to its own beats`() {
        val moved = drag(DragZone.BODY, deltaTicks = 200, grid = SnapGrid.QUARTER, anchor = 100)

        // Grid lines sit at 100, 580, 1060, 1540 — not at 960 or 1440.
        assertThat(moved.startTick).isEqualTo(1060)
    }

    @Test
    fun `a new note is one grid step long before the finger sizes it`() {
        assertThat(NoteDrag.newNoteLength(SnapGrid.SIXTEENTH, ppq)).isEqualTo(120)
        assertThat(NoteDrag.newNoteLength(SnapGrid.EIGHTH_TRIPLET, ppq)).isEqualTo(160)
        // Free has no step to take, so it falls back to something playable.
        assertThat(NoteDrag.newNoteLength(SnapGrid.FREE, ppq)).isEqualTo(240)
    }

    @Test
    fun `a note being drawn grows upward from where it started`() {
        val drawn = NoteDrag.drawn(
            pitch = 64,
            startTick = 950,
            toTick = 1900,
            velocity = 70,
            hand = Hand.LEFT,
            grid = SnapGrid.SIXTEENTH,
            ticksPerQuarter = ppq,
            anchor = 0L,
        )

        assertThat(drawn.startTick).isEqualTo(960)
        assertThat(drawn.endTick).isEqualTo(1920)
        assertThat(drawn.hand).isEqualTo(Hand.LEFT)
        assertThat(drawn.velocity).isEqualTo(70)
    }

    @Test
    fun `a note drawn with no movement is still long enough to hear`() {
        val drawn = NoteDrag.drawn(
            pitch = 64,
            startTick = 960,
            toTick = 960,
            velocity = 70,
            hand = Hand.RIGHT,
            grid = SnapGrid.SIXTEENTH,
            ticksPerQuarter = ppq,
            anchor = 0L,
        )

        assertThat(drawn.endTick).isEqualTo(1080)
    }

    // ---- the pre-roll floor ----

    /** Four bars of four-four, the room edit mode offers in front of bar 1. */
    private val preRoll = -(ppq * 4L * 4)

    @Test
    fun `a note drawn in the pre-roll keeps its negative tick`() {
        // It has to arrive negative for anything downstream to know room is
        // needed. Clamping it here is what made the feature unreachable.
        val drawn = NoteDrag.drawn(
            pitch = 60,
            startTick = -500,
            toTick = -100,
            velocity = 70,
            hand = Hand.RIGHT,
            grid = SnapGrid.SIXTEENTH,
            ticksPerQuarter = ppq,
            anchor = 0L,
            minTick = preRoll,
        )

        assertThat(drawn.startTick).isEqualTo(-480)
        assertThat(drawn.endTick).isEqualTo(-120)
    }

    @Test
    fun `a note dragged before the start of the piece goes there`() {
        val moved = NoteDrag.apply(
            original = note.copy(startTick = 0, endTick = 480),
            zone = DragZone.BODY,
            deltaTicks = -960,
            pitch = note.pitch,
            grid = SnapGrid.SIXTEENTH,
            ticksPerQuarter = ppq,
            anchor = 0L,
            minTick = preRoll,
        )

        assertThat(moved.startTick).isEqualTo(-960)
        assertThat(moved.lengthTicks).isEqualTo(480)
    }

    @Test
    fun `a drag cannot go further back than there is room drawn`() {
        // Scrolling into unbounded blank space is just being lost, so the floor
        // is the pre-roll that is actually on screen.
        val moved = NoteDrag.apply(
            original = note,
            zone = DragZone.BODY,
            deltaTicks = -100_000,
            pitch = note.pitch,
            grid = SnapGrid.SIXTEENTH,
            ticksPerQuarter = ppq,
            anchor = 0L,
            minTick = preRoll,
        )

        assertThat(moved.startTick).isEqualTo(preRoll)
    }

    @Test
    fun `without a floor a drag still stops at the start of the piece`() {
        val moved = drag(DragZone.BODY, deltaTicks = -100_000)

        assertThat(moved.startTick).isEqualTo(0)
    }
}
