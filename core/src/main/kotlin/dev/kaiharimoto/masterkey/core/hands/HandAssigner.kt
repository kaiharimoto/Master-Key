package dev.kaiharimoto.masterkey.core.hands

import dev.kaiharimoto.masterkey.core.midi.RawNote
import dev.kaiharimoto.masterkey.core.model.Hand
import dev.kaiharimoto.masterkey.core.model.Note

/**
 * Works out which hand plays each note.
 *
 * Resolution order, best evidence first:
 *
 *  1. MusicXML `<staff>` — handled upstream in the score layer, and by far the
 *     most reliable, because it is what the engraver actually wrote.
 *  2. Separate MIDI tracks/channels — usual for notation exports, but the
 *     convention "track 1 is the right hand" is not guaranteed, so the grouping
 *     is trusted while the *labelling* is decided by pitch.
 *  3. A split point inferred from the pitch distribution — required for type 0
 *     files where everything shares one track.
 *
 * Step 3 deliberately does not hardcode middle C. A piece that lives in the bass
 * needs a lower split, and a hardcoded 60 would shove the whole left hand into
 * the right. Otsu's method finds the threshold that best separates the pitch
 * histogram into two clusters, which is exactly the question being asked.
 */
object HandAssigner {

    /** Below this many notes, a split point is guesswork; treat it as one hand. */
    private const val MIN_NOTES_FOR_SPLIT = 8

    private const val DEFAULT_SPLIT = 60 // middle C, only as a last resort

    internal fun assign(raw: List<RawNote>): List<Note> {
        if (raw.isEmpty()) return emptyList()

        val groups = raw.groupBy { it.track to it.channel }
            .filterValues { it.isNotEmpty() }

        return if (groups.size >= 2) {
            assignByGroup(raw, groups)
        } else {
            assignBySplitPoint(raw, splitPointFor(raw.map { it.pitch }))
        }
    }

    /**
     * Two or more note groups: trust the grouping, but label by mean pitch so a
     * file that puts the left hand on track 1 still comes out right.
     */
    private fun assignByGroup(
        raw: List<RawNote>,
        groups: Map<Pair<Int, Int>, List<RawNote>>,
    ): List<Note> {
        val means = groups.mapValues { (_, notes) -> notes.sumOf { it.pitch }.toDouble() / notes.size }

        // With exactly two groups the higher one is the right hand. With more,
        // split them around the midpoint of the group means — some exports put
        // each voice on its own track.
        val handForGroup: Map<Pair<Int, Int>, Hand> = if (means.size == 2) {
            val ordered = means.entries.sortedBy { it.value }
            mapOf(ordered[0].key to Hand.LEFT, ordered[1].key to Hand.RIGHT)
        } else {
            val midpoint = (means.values.minOrNull()!! + means.values.maxOrNull()!!) / 2.0
            means.mapValues { (_, mean) -> if (mean >= midpoint) Hand.RIGHT else Hand.LEFT }
        }

        // Guard against a degenerate grouping (e.g. two tracks that both cover the
        // full range): if everything landed on one hand, fall back to a split point.
        val distinct = handForGroup.values.toSet()
        if (distinct.size < 2 && raw.size >= MIN_NOTES_FOR_SPLIT) {
            return assignBySplitPoint(raw, splitPointFor(raw.map { it.pitch }))
        }

        return raw.map { note ->
            note.toNote(handForGroup[note.track to note.channel] ?: Hand.RIGHT)
        }
    }

    private fun assignBySplitPoint(raw: List<RawNote>, split: Int): List<Note> =
        raw.map { it.toNote(if (it.pitch >= split) Hand.RIGHT else Hand.LEFT) }

    /**
     * Otsu's method over the pitch histogram: pick the threshold that minimises
     * the summed within-cluster variance, i.e. the most convincing gap between a
     * low cluster and a high cluster.
     */
    fun splitPointFor(pitches: List<Int>): Int {
        if (pitches.size < MIN_NOTES_FOR_SPLIT) return DEFAULT_SPLIT

        val histogram = IntArray(128)
        for (p in pitches) histogram[p.coerceIn(0, 127)]++

        val lowest = histogram.indexOfFirst { it > 0 }
        val highest = histogram.indexOfLast { it > 0 }
        if (lowest < 0 || highest <= lowest) return DEFAULT_SPLIT

        // A piece spanning less than an octave and a half is almost certainly
        // one hand; splitting it would be inventing a left hand that isn't there.
        if (highest - lowest < 18) return if (highest < DEFAULT_SPLIT) highest + 1 else lowest

        val total = pitches.size.toDouble()
        val totalWeighted = histogram.withIndex().sumOf { (pitch, count) -> pitch.toDouble() * count }

        var backgroundCount = 0.0
        var backgroundWeighted = 0.0
        var bestVariance = -1.0
        var bestSplit = DEFAULT_SPLIT

        for (threshold in lowest..highest) {
            backgroundCount += histogram[threshold]
            if (backgroundCount == 0.0) continue
            val foregroundCount = total - backgroundCount
            if (foregroundCount <= 0.0) break

            backgroundWeighted += threshold.toDouble() * histogram[threshold]
            val backgroundMean = backgroundWeighted / backgroundCount
            val foregroundMean = (totalWeighted - backgroundWeighted) / foregroundCount

            // Between-class variance; maximising it minimises within-class variance.
            val between = backgroundCount * foregroundCount *
                (backgroundMean - foregroundMean) * (backgroundMean - foregroundMean)

            if (between > bestVariance) {
                bestVariance = between
                bestSplit = threshold + 1
            }
        }
        return bestSplit.coerceIn(lowest + 1, highest)
    }

    private fun RawNote.toNote(hand: Hand) = Note(
        pitch = pitch,
        startTick = startTick,
        endTick = endTick,
        velocity = velocity,
        hand = hand,
        track = track,
    )
}
