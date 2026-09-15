package dev.kaiharimoto.masterkey.core

import com.google.common.truth.Truth.assertThat
import dev.kaiharimoto.masterkey.core.edit.SnapGrid
import dev.kaiharimoto.masterkey.core.model.Piece
import dev.kaiharimoto.masterkey.core.model.TempoMap
import dev.kaiharimoto.masterkey.core.model.TimeSignature
import org.junit.Test

class SnapGridTest {

    @Test
    fun `plain divisions at 480 ticks per quarter`() {
        assertThat(SnapGrid.QUARTER.unitTicks(480)).isEqualTo(480)
        assertThat(SnapGrid.EIGHTH.unitTicks(480)).isEqualTo(240)
        assertThat(SnapGrid.SIXTEENTH.unitTicks(480)).isEqualTo(120)
        assertThat(SnapGrid.THIRTY_SECOND.unitTicks(480)).isEqualTo(60)
    }

    @Test
    fun `triplets are exact at the resolutions that turn up in real files`() {
        // Three eighth-note triplets fill one quarter; three sixteenth triplets
        // fill one eighth. If the arithmetic rounds, a triplet run drifts.
        assertThat(SnapGrid.EIGHTH_TRIPLET.unitTicks(480)).isEqualTo(160)
        assertThat(SnapGrid.SIXTEENTH_TRIPLET.unitTicks(480)).isEqualTo(80)
        assertThat(SnapGrid.EIGHTH_TRIPLET.unitTicks(96)).isEqualTo(32)
        assertThat(SnapGrid.EIGHTH_TRIPLET.unitTicks(240)).isEqualTo(80)
        assertThat(SnapGrid.EIGHTH_TRIPLET.unitTicks(480) * 3).isEqualTo(480)
    }

    @Test
    fun `a unit is never zero, however coarse the file`() {
        assertThat(SnapGrid.THIRTY_SECOND.unitTicks(1)).isAtLeast(1)
        assertThat(SnapGrid.SIXTEENTH_TRIPLET.unitTicks(4)).isAtLeast(1)
    }

    @Test
    fun `snapping rounds to the nearest line, not down`() {
        assertThat(SnapGrid.SIXTEENTH.snap(70, 480)).isEqualTo(120)
        assertThat(SnapGrid.SIXTEENTH.snap(50, 480)).isEqualTo(0)
        assertThat(SnapGrid.SIXTEENTH.snap(121, 480)).isEqualTo(120)
    }

    @Test
    fun `free leaves a tick exactly where it was`() {
        assertThat(SnapGrid.FREE.snap(4_097, 480)).isEqualTo(4_097)
        assertThat(SnapGrid.FREE.unitTicks(480)).isEqualTo(1)
    }

    @Test
    fun `snapping never goes negative`() {
        assertThat(SnapGrid.QUARTER.snap(-500, 480)).isAtLeast(0)
        assertThat(SnapGrid.FREE.snap(-1, 480)).isEqualTo(0)
    }

    @Test
    fun `the grid follows a mid-piece change of metre`() {
        // After the change at tick 1000 the beats no longer line up with tick 0,
        // so snapping against an absolute zero would put every later note off
        // the beat by the size of the displacement.
        val piece = Piece(
            notes = emptyList(),
            tempoMap = TempoMap.default(480),
            timeSignatures = listOf(TimeSignature(0, 4, 4), TimeSignature(1_000, 3, 4)),
            keySignatures = emptyList(),
            endTick = 4_000,
        )

        val anchor = SnapGrid.anchorFor(piece, 1_100)

        assertThat(anchor).isEqualTo(1_000)
        assertThat(SnapGrid.QUARTER.snap(1_100, 480, anchor)).isEqualTo(1_000)
        assertThat(SnapGrid.QUARTER.snap(1_300, 480, anchor)).isEqualTo(1_480)
        assertThat(SnapGrid.anchorFor(piece, 500)).isEqualTo(0)
    }
}
