package dev.kaiharimoto.masterkey.core

import com.google.common.truth.Truth.assertThat
import dev.kaiharimoto.masterkey.core.hands.HandAssigner
import dev.kaiharimoto.masterkey.core.keyboard.KeyRangeSelector
import dev.kaiharimoto.masterkey.core.keyboard.KeyboardGeometry
import dev.kaiharimoto.masterkey.core.midi.Pitch
import org.junit.Test

class KeyRangeTest {

    @Test
    fun `both ends always land on white keys`() {
        // A black key at either end would leave a half-drawn key hanging off the
        // edge, since black keys straddle the seam between two whites.
        val range = KeyRangeSelector.forPitchesClamped(listOf(61, 66)) // C#4 .. F#4

        assertThat(Pitch.isWhite(range.low)).isTrue()
        assertThat(Pitch.isWhite(range.high)).isTrue()
        assertThat(range.low).isAtMost(61)
        assertThat(range.high).isAtLeast(66)
    }

    @Test
    fun `a tiny exercise is widened to a usable minimum`() {
        // Five notes around middle C would otherwise render as five enormous keys.
        val range = KeyRangeSelector.forPitchesClamped(listOf(60, 62, 64, 65, 67))

        assertThat(range.whiteKeyCount).isAtLeast(KeyRangeSelector.DEFAULT_MIN_WHITE_KEYS)
        assertThat(range.contains(60)).isTrue()
        assertThat(range.contains(67)).isTrue()
    }

    @Test
    fun `a normal two octave piece is covered exactly`() {
        val pitches = (48..72).toList()

        val range = KeyRangeSelector.forPitchesClamped(pitches)

        assertThat(range.contains(48)).isTrue()
        assertThat(range.contains(72)).isTrue()
    }

    @Test
    fun `a piece wider than the maximum returns null so the caller can pan`() {
        val pitches = listOf(Pitch.LOWEST_PIANO, Pitch.HIGHEST_PIANO)

        val range = KeyRangeSelector.forPitches(pitches)

        assertThat(range).isNull()
    }

    @Test
    fun `the clamped variant always produces something drawable`() {
        val range = KeyRangeSelector.forPitchesClamped(
            listOf(Pitch.LOWEST_PIANO, Pitch.HIGHEST_PIANO),
        )

        assertThat(range.whiteKeyCount).isAtMost(KeyRangeSelector.DEFAULT_MAX_WHITE_KEYS)
        assertThat(Pitch.isWhite(range.low)).isTrue()
        assertThat(Pitch.isWhite(range.high)).isTrue()
    }

    @Test
    fun `never runs past the ends of an 88 key piano`() {
        val low = KeyRangeSelector.forPitchesClamped(listOf(Pitch.LOWEST_PIANO))
        val high = KeyRangeSelector.forPitchesClamped(listOf(Pitch.HIGHEST_PIANO))

        assertThat(low.low).isAtLeast(Pitch.LOWEST_PIANO)
        assertThat(high.high).isAtMost(Pitch.HIGHEST_PIANO)
    }

    @Test
    fun `empty input yields no range`() {
        assertThat(KeyRangeSelector.forPitches(emptyList())).isNull()
    }

    @Test
    fun `white key indices are contiguous from zero`() {
        val range = KeyRangeSelector.forPitchesClamped((60..72).toList())
        val whites = (range.low..range.high).filter { Pitch.isWhite(it) }

        whites.forEachIndexed { index, pitch ->
            assertThat(range.whiteIndexOf(pitch)).isEqualTo(index)
        }
    }

    @Test
    fun `octave markers land on every C in range`() {
        val range = KeyRangeSelector.forPitchesClamped((48..84).toList())

        assertThat(range.octaveMarkers()).containsAtLeast(48, 60, 72)
        assertThat(range.octaveMarkers().all { Math.floorMod(it, 12) == 0 }).isTrue()
    }
}

class KeyboardGeometryTest {

    @Test
    fun `white keys tile the full width with no gaps`() {
        val range = KeyRangeSelector.forPitchesClamped((60..72).toList())
        val geometry = KeyboardGeometry(range, totalWidth = 1000f)
        val whites = geometry.whiteKeys()

        assertThat(geometry.leftOf(whites.first())).isWithin(0.01f).of(0f)
        val lastRight = geometry.leftOf(whites.last()) + geometry.whiteKeyWidth
        assertThat(lastRight).isWithin(0.01f).of(1000f)
    }

    @Test
    fun `black keys sit on the seam between their neighbouring whites`() {
        val range = KeyRangeSelector.forPitchesClamped((60..72).toList())
        val geometry = KeyboardGeometry(range, totalWidth = 700f)

        // C#4 sits on the boundary between C4 and D4.
        val boundary = geometry.leftOf(60) + geometry.whiteKeyWidth
        assertThat(geometry.centerOf(61)).isWithin(0.01f).of(boundary)
    }

    @Test
    fun `black keys are narrower than white keys`() {
        val range = KeyRangeSelector.forPitchesClamped((60..72).toList())
        val geometry = KeyboardGeometry(range, totalWidth = 700f)

        assertThat(geometry.blackKeyWidth).isLessThan(geometry.whiteKeyWidth)
    }

    @Test
    fun `a pitch outside the range clamps instead of throwing`() {
        // During an adaptive pan a note can briefly sit outside the window; it
        // should slide off the edge rather than crash the renderer.
        val range = KeyRangeSelector.forPitchesClamped((60..72).toList())
        val geometry = KeyboardGeometry(range, totalWidth = 700f)

        assertThat(geometry.centerOf(21)).isEqualTo(0f)
        assertThat(geometry.centerOf(108)).isEqualTo(700f)
    }
}

class HandSplitTest {

    @Test
    fun `splits a normally voiced piece near the middle`() {
        val leftHand = listOf(41, 45, 48, 41, 45, 48, 36, 40)
        val rightHand = listOf(72, 74, 76, 77, 79, 72, 74, 76)

        val split = HandAssigner.splitPointFor(leftHand + rightHand)

        assertThat(split).isGreaterThan(48)
        assertThat(split).isAtMost(72)
    }

    @Test
    fun `moves the split down for a bass heavy piece`() {
        // Both parts sit low. A hardcoded middle C would shove the entire piece
        // into the left hand, which is exactly what this must avoid.
        val lower = List(20) { 28 + it % 5 }
        val upper = List(20) { 50 + it % 5 }

        val split = HandAssigner.splitPointFor(lower + upper)

        assertThat(split).isGreaterThan(32)
        assertThat(split).isLessThan(60)
    }

    @Test
    fun `moves the split up for a treble heavy piece`() {
        val lower = List(20) { 62 + it % 4 }
        val upper = List(20) { 86 + it % 4 }

        val split = HandAssigner.splitPointFor(lower + upper)

        assertThat(split).isGreaterThan(64)
    }

    @Test
    fun `does not invent a split for a narrow single hand passage`() {
        // A one-octave melody is one hand; splitting it would fabricate a left
        // hand that is not in the music.
        val melody = listOf(60, 62, 64, 65, 67, 69, 71, 72)

        val split = HandAssigner.splitPointFor(melody)

        assertThat(split).isAtMost(60)
    }

    @Test
    fun `falls back to middle C when there is too little to go on`() {
        assertThat(HandAssigner.splitPointFor(listOf(60, 64))).isEqualTo(60)
    }
}
