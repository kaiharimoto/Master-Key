package dev.kaiharimoto.masterkey.core.keyboard

import dev.kaiharimoto.masterkey.core.midi.Pitch

/**
 * Measurements of a real piano keyboard, in millimetres.
 *
 * Kept as ratios rather than absolute sizes because only the proportions
 * survive being drawn at tablet scale. They matter: a keyboard whose keys are
 * the wrong shape relative to each other stops reading as a piano, and the
 * muscle memory of where a black key sits between its neighbours is most of what
 * the drawn keyboard is for.
 */
object PianoProportions {
    /** One octave spans 165 mm across seven white keys. */
    const val WHITE_KEY_WIDTH_MM = 23.5f
    const val WHITE_KEY_LENGTH_MM = 150f
    const val BLACK_KEY_WIDTH_MM = 13.7f
    const val BLACK_KEY_LENGTH_MM = 95f

    /** 0.583. A black key is a little over half the width of a white one. */
    const val BLACK_TO_WHITE_WIDTH = BLACK_KEY_WIDTH_MM / WHITE_KEY_WIDTH_MM

    /** 0.633. Black keys stop well short of the front edge. */
    const val BLACK_TO_WHITE_LENGTH = BLACK_KEY_LENGTH_MM / WHITE_KEY_LENGTH_MM

    /** 6.38. A white key is far longer than it is wide — the thing hardest to honour on a tablet. */
    const val WHITE_KEY_ASPECT = WHITE_KEY_LENGTH_MM / WHITE_KEY_WIDTH_MM
}

/**
 * Maps pitches to horizontal positions for the drawn keyboard and the falling-note
 * lanes above it.
 *
 * White keys are laid out edge to edge at equal width. Black keys straddle the
 * boundary between the two whites they sit between — that is what makes the
 * spacing read as a piano rather than as 12 equal lanes, and it is why
 * [KeyRange] insists both ends are white keys.
 *
 * Geometry is pure and resolution-independent so it can be unit-tested and so the
 * highway can precompute note rectangles once per song instead of per frame.
 */
class KeyboardGeometry(
    val range: KeyRange,
    val totalWidth: Float,
) {
    val whiteKeyCount: Int = range.whiteKeyCount

    val whiteKeyWidth: Float = if (whiteKeyCount > 0) totalWidth / whiteKeyCount else totalWidth

    val blackKeyWidth: Float = whiteKeyWidth * PianoProportions.BLACK_TO_WHITE_WIDTH

    /** Black keys stop short of the front edge, as on a real keyboard. */
    val blackKeyHeightFraction: Float = PianoProportions.BLACK_TO_WHITE_LENGTH

    /** Left edge of [pitch]'s lane. */
    fun leftOf(pitch: Int): Float = centerOf(pitch) - widthOf(pitch) / 2f

    fun widthOf(pitch: Int): Float =
        if (Pitch.isWhite(pitch)) whiteKeyWidth else blackKeyWidth

    /**
     * Centre of [pitch]'s lane.
     *
     * White keys sit at the middle of their slot. Black keys sit on the seam
     * between the white below and the white above them.
     */
    fun centerOf(pitch: Int): Float {
        if (!range.contains(pitch)) {
            // Clamp rather than throw: a note briefly outside an adaptive window
            // should slide off the edge, not crash the renderer.
            return if (pitch < range.low) 0f else totalWidth
        }
        return if (Pitch.isWhite(pitch)) {
            (range.whiteIndexOf(pitch) + 0.5f) * whiteKeyWidth
        } else {
            // The white key immediately below a black key is always pitch - 1.
            val whiteBelow = range.whiteIndexOf(pitch - 1)
            if (whiteBelow >= 0) {
                (whiteBelow + 1f) * whiteKeyWidth
            } else {
                // Black key at the very bottom edge of the range.
                0f
            }
        }
    }

    /** White keys in range, low to high — draw these first, then the blacks on top. */
    fun whiteKeys(): List<Int> = (range.low..range.high).filter { Pitch.isWhite(it) }

    /** Black keys in range, drawn over the whites. */
    fun blackKeys(): List<Int> = (range.low..range.high).filter { Pitch.isBlack(it) }

    /** Which key is under [x], preferring black keys since they sit on top. */
    fun pitchAt(x: Float, y: Float, keyboardHeight: Float): Int? {
        if (y <= keyboardHeight * blackKeyHeightFraction) {
            for (pitch in blackKeys()) {
                val left = leftOf(pitch)
                if (x >= left && x <= left + blackKeyWidth) return pitch
            }
        }
        val index = (x / whiteKeyWidth).toInt()
        val whites = whiteKeys()
        return whites.getOrNull(index.coerceIn(0, whites.size - 1))
    }
}
