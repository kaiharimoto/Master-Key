package dev.kaiharimoto.masterkey.ui.player

import com.google.common.truth.Truth.assertThat
import dev.kaiharimoto.masterkey.core.midi.Pitch
import dev.kaiharimoto.masterkey.core.model.Hand
import dev.kaiharimoto.masterkey.core.model.Note
import dev.kaiharimoto.masterkey.core.model.Piece
import dev.kaiharimoto.masterkey.core.model.TempoMap
import dev.kaiharimoto.masterkey.core.model.TimeSignature
import org.junit.Test

/**
 * Plain JUnit, no Robolectric: [HighwayModel], [HighwayLayout] and
 * [HighwayHitTest] all deliberately avoid Android types, so the geometry the
 * whole editor rests on is checkable in milliseconds.
 */
class HighwayHitTestTest {

    private val ppq = 480
    private val keyLineY = 800f
    private val pixelsPerTick = 0.25f // 8 beats of look-ahead over 800px

    /** One octave from C4, so both black and white lanes are on screen. */
    private val layout = HighwayLayout(
        leftWhiteIndex = Pitch.whiteIndex(60).toFloat(),
        visibleWhiteKeys = 8f,
        width = 800f,
    )

    private fun note(pitch: Int, start: Long, end: Long) =
        Note(pitch, start, end, 80, Hand.RIGHT)

    private fun modelOf(vararg notes: Note) = HighwayModel(
        Piece(
            notes = notes.sortedWith(compareBy({ it.startTick }, { it.pitch })),
            tempoMap = TempoMap.default(ppq),
            timeSignatures = listOf(TimeSignature(0, 4, 4)),
            keySignatures = emptyList(),
            endTick = notes.maxOfOrNull { it.endTick } ?: 0L,
        ),
    )

    private fun hit(
        model: HighwayModel,
        x: Float,
        y: Float,
        playheadTick: Long = 0L,
        preferIndex: Int = HighwayHitTest.NONE,
    ) = HighwayHitTest.noteAt(
        model = model,
        layout = layout,
        x = x,
        y = y,
        playheadTick = playheadTick,
        keyLineY = keyLineY,
        pixelsPerTick = pixelsPerTick,
        minTouchPx = 24f,
        preferIndex = preferIndex,
    )

    @Test
    fun `tick and y invert each other`() {
        val y = HighwayHitTest.yAt(1200, playheadTick = 240, keyLineY = keyLineY, pixelsPerTick = pixelsPerTick)

        assertThat(HighwayHitTest.tickAt(y, 240, keyLineY, pixelsPerTick)).isEqualTo(1200)
    }

    @Test
    fun `the key line is the playhead`() {
        assertThat(HighwayHitTest.tickAt(keyLineY, 960, keyLineY, pixelsPerTick)).isEqualTo(960)
    }

    @Test
    fun `a note is found under its own centre`() {
        val model = modelOf(note(62, 480, 960))
        val centreY = HighwayHitTest.yAt(720, 0, keyLineY, pixelsPerTick)

        assertThat(hit(model, layout.centerOf(62), centreY)).isEqualTo(0)
    }

    @Test
    fun `a gap between notes hits nothing`() {
        val model = modelOf(note(62, 0, 240), note(62, 960, 1200))
        val gapY = HighwayHitTest.yAt(600, 0, keyLineY, pixelsPerTick)

        assertThat(hit(model, layout.centerOf(62), gapY)).isEqualTo(HighwayHitTest.NONE)
    }

    @Test
    fun `a note still sounding from long before the window is found`() {
        // The list is sorted by START tick, so a whole note that began four bars
        // ago sits far behind the playhead in the list. Missing it is how held
        // notes become unselectable.
        val model = modelOf(note(60, 0, 7680), note(72, 7000, 7200))
        val y = HighwayHitTest.yAt(6000, playheadTick = 5800, keyLineY = keyLineY, pixelsPerTick = pixelsPerTick)

        assertThat(hit(model, layout.centerOf(60), y, playheadTick = 5800)).isEqualTo(0)
    }

    @Test
    fun `a black note wins the strip it is drawn over`() {
        // C#5 (61) is drawn on the seam over C5 and D5's lanes. Both rects
        // contain the point; the black one is the one you can see.
        val model = modelOf(note(60, 0, 960), note(61, 0, 960))
        val x = layout.centerOf(61)
        val y = HighwayHitTest.yAt(480, 0, keyLineY, pixelsPerTick)

        val index = hit(model, x, y)

        assertThat(model.notes[index].pitch).isEqualTo(61)
    }

    @Test
    fun `a short note wins over the held note behind it`() {
        // The held note can be grabbed anywhere along its length; the short one
        // can only be grabbed here.
        val model = modelOf(note(64, 0, 3840), note(64, 1920, 2040))
        val x = layout.centerOf(64)
        val y = HighwayHitTest.yAt(1980, 0, keyLineY, pixelsPerTick)

        val index = hit(model, x, y)

        assertThat(model.notes[index].startTick).isEqualTo(1920)
    }

    @Test
    fun `the selected note keeps priority over anything overlapping it`() {
        val model = modelOf(note(64, 0, 3840), note(64, 1920, 2040))
        val held = model.notes.indexOfFirst { it.startTick == 0L }
        val x = layout.centerOf(64)
        val y = HighwayHitTest.yAt(1980, 0, keyLineY, pixelsPerTick)

        assertThat(hit(model, x, y, preferIndex = held)).isEqualTo(held)
    }

    @Test
    fun `a note thinner than a fingertip is still catchable`() {
        // 30 ticks at this zoom is 7.5px — unhittable without the inflation, and
        // a 1/32 note at a comfortable look-ahead is exactly that thin.
        val model = modelOf(note(65, 960, 990))
        val y = HighwayHitTest.yAt(975, 0, keyLineY, pixelsPerTick) - 8f

        assertThat(hit(model, layout.centerOf(65), y)).isEqualTo(0)
    }

    @Test
    fun `a touch outside the lane misses`() {
        val model = modelOf(note(62, 480, 960))
        val y = HighwayHitTest.yAt(720, 0, keyLineY, pixelsPerTick)
        val farLeft = layout.leftOf(62) - layout.whiteKeyWidth

        assertThat(hit(model, farLeft, y)).isEqualTo(HighwayHitTest.NONE)
    }

    @Test
    fun `the bottom grip moves the start and the top grip moves the end`() {
        // Notes fall downward, so this is the reverse of a horizontal piano roll
        // and the easiest thing in the file to get backwards.
        val top = 100f
        val bottom = 300f

        assertThat(HighwayHitTest.zoneAt(295f, top, bottom, 20f)).isEqualTo(DragZone.START)
        assertThat(HighwayHitTest.zoneAt(105f, top, bottom, 20f)).isEqualTo(DragZone.END)
        assertThat(HighwayHitTest.zoneAt(200f, top, bottom, 20f)).isEqualTo(DragZone.BODY)
    }

    @Test
    fun `a note too short for three grips is all body`() {
        // A 4px grip is not a control, it is a coin toss.
        val top = 100f
        val bottom = 140f

        assertThat(HighwayHitTest.zoneAt(138f, top, bottom, 20f)).isEqualTo(DragZone.BODY)
        assertThat(HighwayHitTest.zoneAt(102f, top, bottom, 20f)).isEqualTo(DragZone.BODY)
        assertThat(HighwayHitTest.hasGrips(top, bottom, 20f)).isFalse()
        assertThat(HighwayHitTest.hasGrips(100f, 300f, 20f)).isTrue()
    }

    @Test
    fun `grips never take more than a third of the note each`() {
        // At exactly the threshold the three zones must still be equal thirds,
        // not two grips meeting in the middle with no body between them.
        val top = 0f
        val bottom = 60f

        assertThat(HighwayHitTest.zoneAt(30f, top, bottom, 20f)).isEqualTo(DragZone.BODY)
    }

    @Test
    fun `lanes resolve black keys before white ones`() {
        assertThat(HighwayHitTest.laneAt(layout, layout.centerOf(61), 60, 72)).isEqualTo(61)
        assertThat(HighwayHitTest.laneAt(layout, layout.centerOf(60), 60, 72)).isEqualTo(60)
    }
}
