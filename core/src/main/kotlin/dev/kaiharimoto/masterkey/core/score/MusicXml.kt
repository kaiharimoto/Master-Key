package dev.kaiharimoto.masterkey.core.score

/** A note or rest as written in the score. */
data class ScoreNote(
    /** MIDI pitch, or null for a rest. */
    val pitch: Int?,
    /** Onset in divisions from the start of the score. */
    val onset: Long,
    val duration: Long,
    /** 1 = right hand (upper staff), 2 = left hand. */
    val staff: Int,
    val voice: Int,
    val measureIndex: Int,
    val measureNumber: String,
    val isChordMember: Boolean,
    val isGrace: Boolean,
    val tieStart: Boolean,
    val tieStop: Boolean,
    /** Fingering from `<technical><fingering>`, 1–5. */
    val finger: Int?,
) {
    val isRest: Boolean get() = pitch == null
}

data class ScoreMeasure(
    val index: Int,
    val number: String,
    val onset: Long,
    val duration: Long,
    /** True for a pickup bar, which is shorter than the time signature implies. */
    val implicit: Boolean,
    val repeatForward: Boolean,
    val repeatBackward: Boolean,
    val endingNumbers: String?,
)

/**
 * A `<time>` from the score, at the point in divisions where it takes effect.
 *
 * Kept in the score's own units rather than converted here: [ScoreDocument] does
 * not know the tick resolution anything downstream will want to use.
 */
data class ScoreTimeSignature(
    val onset: Long,
    val numerator: Int,
    val denominator: Int,
)

/**
 * The parts of a MusicXML file the app reasons about, independent of whatever
 * engine draws the notation.
 */
data class ScoreDocument(
    val title: String?,
    val composer: String?,
    val divisions: Int,
    val notes: List<ScoreNote>,
    val measures: List<ScoreMeasure>,
    val tempoBpm: Double?,
    val hasFingering: Boolean,
    val hasTwoStaves: Boolean,
    val timeSignatures: List<ScoreTimeSignature> = emptyList(),
    /** `<key><fifths>`: negative = flats, positive = sharps. Null when unwritten. */
    val fifths: Int? = null,
    val isMinor: Boolean = false,
) {
    /** Sounding notes only, in time order — what pairs against MIDI events. */
    val soundingNotes: List<ScoreNote> by lazy {
        notes.filter { !it.isRest && !it.isGrace }.sortedWith(compareBy({ it.onset }, { it.pitch }))
    }

    /** Fingering keyed by (onset, pitch), for annotating the highway. */
    val fingeringByOnsetPitch: Map<Pair<Long, Int>, Int> by lazy {
        buildMap {
            for (note in notes) {
                val pitch = note.pitch ?: continue
                val finger = note.finger ?: continue
                put(note.onset to pitch, finger)
            }
        }
    }

    /** Staff (1 or 2) keyed by (onset, pitch), which beats guessing a hand split. */
    val staffByOnsetPitch: Map<Pair<Long, Int>, Int> by lazy {
        buildMap {
            for (note in notes) {
                val pitch = note.pitch ?: continue
                put(note.onset to pitch, note.staff)
            }
        }
    }

    val hasRepeats: Boolean
        get() = measures.any { it.repeatForward || it.repeatBackward || it.endingNumbers != null }
}
