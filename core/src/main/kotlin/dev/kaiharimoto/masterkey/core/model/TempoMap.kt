package dev.kaiharimoto.masterkey.core.model

/**
 * Converts between musical time (ticks) and wall clock (microseconds).
 *
 * You cannot do this with a single multiply: a piece with tempo changes needs
 * the elapsed time accumulated segment by segment. So the map precomputes a
 * sorted array of breakpoints, each carrying the absolute time at which it
 * begins, and both directions are then a binary search plus one multiply.
 *
 * That O(log n) lookup is what makes scrubbing and loop-jumping instant instead
 * of walking the event list every frame.
 *
 * SMPTE-division files (bit 15 of the division field set) express ticks as
 * absolute time already, so tempo events are irrelevant to them; [ticksPerSecond]
 * covers that case.
 */
class TempoMap private constructor(
    val ticksPerQuarter: Int,
    private val changes: List<TempoChange>,
    /** Non-null only for SMPTE-division files, where ticks are absolute time. */
    private val ticksPerSecond: Double?,
) {

    val tempoChanges: List<TempoChange> get() = changes

    fun tickToMicros(tick: Long): Long {
        ticksPerSecond?.let { return ((tick / it) * 1_000_000.0).toLong() }
        val change = changeAtTick(tick)
        val deltaTicks = tick - change.tick
        return change.startMicros +
                (deltaTicks * change.microsPerQuarter) / ticksPerQuarter
    }

    fun microsToTick(micros: Long): Long {
        ticksPerSecond?.let { return ((micros / 1_000_000.0) * it).toLong() }
        val change = changeAtMicros(micros)
        val deltaMicros = micros - change.startMicros
        return change.tick + (deltaMicros * ticksPerQuarter) / change.microsPerQuarter
    }

    /** Tempo in effect at [tick], in beats per minute. */
    fun bpmAt(tick: Long): Double {
        ticksPerSecond?.let { return 120.0 }
        return changeAtTick(tick).bpm
    }

    private fun changeAtTick(tick: Long): TempoChange {
        if (changes.size == 1 || tick <= changes.first().tick) return changes.first()
        var lo = 0
        var hi = changes.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (changes[mid].tick <= tick) lo = mid else hi = mid - 1
        }
        return changes[lo]
    }

    private fun changeAtMicros(micros: Long): TempoChange {
        if (changes.size == 1 || micros <= changes.first().startMicros) return changes.first()
        var lo = 0
        var hi = changes.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (changes[mid].startMicros <= micros) lo = mid else hi = mid - 1
        }
        return changes[lo]
    }

    companion object {
        const val DEFAULT_MICROS_PER_QUARTER = 500_000 // 120 bpm

        fun default(ticksPerQuarter: Int): TempoMap = TempoMap(
            ticksPerQuarter = ticksPerQuarter,
            changes = listOf(TempoChange(0, DEFAULT_MICROS_PER_QUARTER, 0)),
            ticksPerSecond = null,
        )

        fun smpte(ticksPerSecond: Double): TempoMap = TempoMap(
            ticksPerQuarter = 1,
            changes = listOf(TempoChange(0, DEFAULT_MICROS_PER_QUARTER, 0)),
            ticksPerSecond = ticksPerSecond,
        )

        /**
         * Builds a map from raw (tick, microsPerQuarter) pairs in any order,
         * accumulating the absolute start time of each segment.
         *
         * A file with no set-tempo event is not an error — MIDI's default is
         * 120 bpm, and plenty of real files rely on it.
         */
        fun build(ticksPerQuarter: Int, rawChanges: List<Pair<Long, Int>>): TempoMap {
            require(ticksPerQuarter > 0) { "ticksPerQuarter must be positive" }

            val sorted = rawChanges
                .filter { it.second > 0 }
                .sortedBy { it.first }
                // Several tempo events can share a tick (common when a file is
                // exported from notation software); the last one wins.
                .let { list ->
                    list.filterIndexed { i, item ->
                        i == list.lastIndex || list[i + 1].first != item.first
                    }
                }

            if (sorted.isEmpty() || sorted.first().first > 0) {
                // Implicit 120 bpm until the first explicit tempo.
                val seeded = listOf(0L to DEFAULT_MICROS_PER_QUARTER) + sorted
                return TempoMap(ticksPerQuarter, accumulate(ticksPerQuarter, seeded), null)
            }
            return TempoMap(ticksPerQuarter, accumulate(ticksPerQuarter, sorted), null)
        }

        private fun accumulate(
            ticksPerQuarter: Int,
            sorted: List<Pair<Long, Int>>,
        ): List<TempoChange> {
            val result = ArrayList<TempoChange>(sorted.size)
            var elapsedMicros = 0L
            var previousTick = 0L
            var previousTempo = DEFAULT_MICROS_PER_QUARTER

            sorted.forEachIndexed { index, (tick, micros) ->
                if (index > 0) {
                    elapsedMicros += ((tick - previousTick) * previousTempo) / ticksPerQuarter
                }
                result += TempoChange(tick, micros, elapsedMicros)
                previousTick = tick
                previousTempo = micros
            }
            return result
        }
    }
}
