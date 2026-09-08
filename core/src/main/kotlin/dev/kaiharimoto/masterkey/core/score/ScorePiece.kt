package dev.kaiharimoto.masterkey.core.score

import dev.kaiharimoto.masterkey.core.hands.HandAssigner
import dev.kaiharimoto.masterkey.core.model.Hand
import dev.kaiharimoto.masterkey.core.model.KeySignature
import dev.kaiharimoto.masterkey.core.model.Note
import dev.kaiharimoto.masterkey.core.model.Piece
import dev.kaiharimoto.masterkey.core.model.TempoMap
import dev.kaiharimoto.masterkey.core.model.TimeSignature

/*
 * Builds a playable Piece out of an engraved score.
 *
 * Until now a song *was* a MIDI file, and a MusicXML could only ride along to
 * annotate it with staff and fingering. But a MusicXML already says everything a
 * MIDI does about which notes sound when — and rather more, since it also
 * carries the hand split and the fingering explicitly instead of leaving them to
 * be guessed. So a score on its own is enough to practise from, and requiring a
 * MIDI beside it only meant a perfectly good file could not be opened.
 *
 * What a score does *not* carry is dynamics we parse, so every note is struck at
 * one velocity. That is honest for a practice tool: the point is to hear which
 * notes and when, not to perform.
 */

/** Velocity for every note. MusicXML dynamics are not parsed. */
private const val DEFAULT_VELOCITY = 80

/** Grace notes have no written duration; they get a token length so they sound. */
private const val GRACE_DIVISIONS_FRACTION = 8

/**
 * Converts this score into a piece that can be played and drawn.
 *
 * [ticksPerQuarter] is the resolution of the result, not of the source — MusicXML
 * counts in its own `<divisions>`, which vary per file and can change mid-score.
 * 480 matches what most MIDI files use, so the highway and the scheduler see the
 * same sort of numbers either way.
 */
fun ScoreDocument.toPiece(ticksPerQuarter: Int = 480): Piece {
    val ppq = ticksPerQuarter.coerceAtLeast(1)
    val divisions = this.divisions.coerceAtLeast(1)

    /** Score divisions to ticks. Done in Long to keep long pieces exact. */
    fun toTicks(value: Long): Long = value * ppq / divisions

    val sounding = notes.filter { !it.isRest && it.pitch != null }
    if (sounding.isEmpty()) {
        return Piece(
            notes = emptyList(),
            tempoMap = tempoMap(ppq),
            timeSignatures = timeSignatureList(ppq, divisions),
            keySignatures = keySignatureList(),
            endTick = 0L,
        )
    }

    // Ties are the reason this is not a straight map. A note tied across a
    // barline is written as two noteheads and must sound as one — mapping them
    // one-to-one re-articulates the note halfway through, which is audibly wrong
    // and is exactly the kind of thing that makes a rendered score and the
    // playback disagree. Tie continuations are folded into the note that started
    // the chain, keyed by pitch and staff so two tied voices cannot cross.
    val open = HashMap<Pair<Int, Int>, Int>()
    val built = ArrayList<Note>(sounding.size)
    /** Staff of each entry in [built], parallel to it, for the hand split. */
    val staffOf = ArrayList<Int>(sounding.size)

    for (scoreNote in sounding) {
        val pitch = scoreNote.pitch ?: continue
        val start = toTicks(scoreNote.onset)
        val rawDuration = if (scoreNote.isGrace && scoreNote.duration == 0L) {
            (ppq / GRACE_DIVISIONS_FRACTION).toLong()
        } else {
            toTicks(scoreNote.duration)
        }
        val end = start + rawDuration.coerceAtLeast(1L)
        val key = pitch to scoreNote.staff

        val continues = scoreNote.tieStop && open.containsKey(key)
        if (continues) {
            val index = open.getValue(key)
            val existing = built[index]
            built[index] = existing.copy(endTick = maxOf(existing.endTick, end))
            if (!scoreNote.tieStart) open.remove(key)
            continue
        }

        built += Note(
            pitch = pitch,
            startTick = start,
            endTick = end,
            velocity = DEFAULT_VELOCITY,
            hand = Hand.RIGHT, // provisional; assigned below once all are known
            voice = scoreNote.voice,
            finger = scoreNote.finger,
        )
        staffOf += scoreNote.staff
        if (scoreNote.tieStart) open[key] = built.size - 1 else open.remove(key)
    }

    val withHands = assignHands(built, staffOf)

    return Piece(
        // Sorted by start tick: the highway culls by binary search over this and
        // the scheduler walks it forward without re-sorting, so an unsorted list
        // silently drops notes rather than failing.
        notes = withHands.sortedBy { it.startTick },
        tempoMap = tempoMap(ppq),
        timeSignatures = timeSignatureList(ppq, divisions),
        keySignatures = keySignatureList(),
        endTick = withHands.maxOf { it.endTick },
    )
}

/**
 * Splits the notes between the hands.
 *
 * A grand staff says it outright, and that beats any heuristic — it is what the
 * engraver drew and what the player will read. A single-staff score has to be
 * guessed, and [HandAssigner.splitPointFor] is the same histogram split the MIDI
 * path already uses, so both routes agree about where the hands divide.
 */
private fun assignHands(built: List<Note>, staffOf: List<Int>): List<Note> {
    if (staffOf.distinct().size > 1) {
        return built.mapIndexed { index, note ->
            note.copy(hand = if ((staffOf.getOrNull(index) ?: 1) >= 2) Hand.LEFT else Hand.RIGHT)
        }
    }

    val split = HandAssigner.splitPointFor(built.map { it.pitch })
    return built.map { it.copy(hand = if (it.pitch < split) Hand.LEFT else Hand.RIGHT) }
}

private fun ScoreDocument.tempoMap(ppq: Int): TempoMap {
    val bpm = tempoBpm?.takeIf { it > 0 } ?: 120.0
    val microsPerQuarter = (60_000_000.0 / bpm).toInt().coerceAtLeast(1)
    return TempoMap.build(ppq, listOf(0L to microsPerQuarter))
}

private fun ScoreDocument.timeSignatureList(ppq: Int, divisions: Int): List<TimeSignature> {
    val converted = timeSignatures
        .map { TimeSignature(it.onset * ppq / divisions, it.numerator, it.denominator) }
        .sortedBy { it.tick }
    // Piece.timeSignatureAt falls back to 4/4, but the metronome and the bar
    // grid both want one anchored at zero rather than one that starts late.
    return if (converted.firstOrNull()?.tick == 0L) converted else listOf(TimeSignature(0, 4, 4)) + converted
}

private fun ScoreDocument.keySignatureList(): List<KeySignature> =
    fifths?.let { listOf(KeySignature(0, it, isMinor)) } ?: emptyList()
