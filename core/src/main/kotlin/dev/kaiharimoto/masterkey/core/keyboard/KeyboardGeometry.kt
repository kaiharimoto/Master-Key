package dev.kaiharimoto.masterkey.core.keyboard

import dev.kaiharimoto.masterkey.core.midi.Pitch

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

    /** Proportion taken from real pianos; wide enough to hit, narrow enough to read. */
    val blackKeyWidth: Float = whiteKeyWidth * 0.62f

    /** Black keys stop short of the front edge, as on a real keyboard. */
    val blackKeyHeightFraction: Float = 0.62f

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
