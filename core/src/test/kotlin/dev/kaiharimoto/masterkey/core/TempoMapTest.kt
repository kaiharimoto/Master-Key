package dev.kaiharimoto.masterkey.core

import com.google.common.truth.Truth.assertThat
import dev.kaiharimoto.masterkey.core.model.TempoMap
import org.junit.Test

class TempoMapTest {

    @Test
    fun `defaults to 120 bpm when the file declares no tempo`() {
        val map = TempoMap.build(ticksPerQuarter = 480, rawChanges = emptyList())

        assertThat(map.bpmAt(0)).isWithin(0.001).of(120.0)
        // One quarter note at 120 bpm is half a second.
        assertThat(map.tickToMicros(480)).isEqualTo(500_000)
    }

    @Test
    fun `converts ticks to microseconds at a constant tempo`() {
        val map = TempoMap.build(480, listOf(0L to 500_000))

        assertThat(map.tickToMicros(0)).isEqualTo(0)
        assertThat(map.tickToMicros(240)).isEqualTo(250_000)
        assertThat(map.tickToMicros(1920)).isEqualTo(2_000_000)
    }

    @Test
    fun `accumulates elapsed time across several tempo changes`() {
        // 120 bpm for one bar, then 60 bpm for one bar, then 240 bpm.
        val map = TempoMap.build(
            ticksPerQuarter = 480,
            rawChanges = listOf(
                0L to 500_000,      // 120 bpm
                1920L to 1_000_000, // 60 bpm
                3840L to 250_000,   // 240 bpm
            ),
        )

        // First bar: 4 quarters x 0.5 s
        assertThat(map.tickToMicros(1920)).isEqualTo(2_000_000)
        // Second bar: 4 quarters x 1.0 s on top
        assertThat(map.tickToMicros(3840)).isEqualTo(6_000_000)
        // Third bar: 4 quarters x 0.25 s on top
        assertThat(map.tickToMicros(5760)).isEqualTo(7_000_000)
    }

    @Test
    fun `round trips ticks through microseconds across tempo changes`() {
        val map = TempoMap.build(
            480,
            listOf(0L to 500_000, 1920L to 1_000_000, 3840L to 250_000),
        )

        for (tick in listOf(0L, 480L, 1919L, 1920L, 2400L, 3840L, 5000L, 5760L)) {
            val micros = map.tickToMicros(tick)
            // Integer division makes this lossy by at most a tick; the seek bar
            // only needs to land in the right place, not be bit-exact.
            assertThat(map.microsToTick(micros)).isIn((tick - 1)..(tick + 1))
        }
    }

    @Test
    fun `assumes 120 bpm before the first explicit tempo event`() {
        // A tempo event that only appears at bar 2 must not retroactively apply
        // to bar 1, or everything before it plays at the wrong speed.
        val map = TempoMap.build(480, listOf(1920L to 1_000_000))

        assertThat(map.bpmAt(0)).isWithin(0.001).of(120.0)
        assertThat(map.tickToMicros(1920)).isEqualTo(2_000_000)
        assertThat(map.bpmAt(1920)).isWithin(0.001).of(60.0)
    }

    @Test
    fun `takes the last tempo when several share a tick`() {
        // Notation exports routinely emit duplicate tempo events at bar lines.
        val map = TempoMap.build(480, listOf(0L to 500_000, 0L to 400_000))

        assertThat(map.bpmAt(0)).isWithin(0.001).of(150.0)
    }

    @Test
    fun `ignores non positive tempo values`() {
        val map = TempoMap.build(480, listOf(0L to 0, 480L to 500_000))

        assertThat(map.bpmAt(0)).isWithin(0.001).of(120.0)
    }

    @Test
    fun `treats smpte ticks as absolute time`() {
        // 25 fps x 40 ticks per frame = 1000 ticks per second.
        val map = TempoMap.smpte(ticksPerSecond = 1000.0)

        assertThat(map.tickToMicros(1000)).isEqualTo(1_000_000)
        assertThat(map.microsToTick(500_000)).isEqualTo(500)
    }

    @Test
    fun `handles unsorted tempo input`() {
        val map = TempoMap.build(480, listOf(1920L to 1_000_000, 0L to 500_000))

        assertThat(map.tempoChanges.map { it.tick }).isInOrder()
        assertThat(map.tickToMicros(1920)).isEqualTo(2_000_000)
    }
}
