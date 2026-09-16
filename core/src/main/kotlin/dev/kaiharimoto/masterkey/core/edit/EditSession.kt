package dev.kaiharimoto.masterkey.core.edit

import dev.kaiharimoto.masterkey.core.hands.HandAssigner
import dev.kaiharimoto.masterkey.core.midi.Pitch
import dev.kaiharimoto.masterkey.core.model.Hand
import dev.kaiharimoto.masterkey.core.model.Note
import dev.kaiharimoto.masterkey.core.model.Piece
import dev.kaiharimoto.masterkey.core.model.TempoMap

/**
 * A note's identity for the duration of an editing session.
 *
 * [Note] has no id, and its position in `Piece.notes` is not one either: the list
 * is kept sorted by start tick, so dragging a note earlier moves it in the list
 * and every index after it shifts. Anything holding "the note at index 7" — a
 * selection, a drag in progress, an undo entry — would then be pointing at a
 * different note.
 *
 * Adding an id to [Note] itself would reach into the MIDI loader, the hand
 * assigner, the MusicXML path and note equality everywhere, for the sake of a
 * concept that only exists while someone is editing. So identity lives here
 * instead, beside the edit, and is discarded when the session ends.
 */
@JvmInline
value class NoteId(val raw: Int)

/**
 * The notes of a piece plus their session identities, sorted and immutable.
 *
 * Every mutation returns a new [EditNotes] with the sort order re-established by
 * construction. That matters more than it looks: `Piece.notes` must be sorted by
 * start tick, and a list that isn't does not throw — the highway's binary search
 * and the scheduler's forward cursor both simply stop finding notes, so the
 * symptom is music quietly going missing. Making it impossible to build an
 * unsorted list is cheaper than remembering to sort.
 */
class EditNotes private constructor(
    private val entries: List<Entry>,
    private val nextId: Int,
) {
    class Entry(val id: NoteId, val note: Note)

    val size: Int get() = entries.size

    fun notes(): List<Note> = entries.map { it.note }

    fun idAt(index: Int): NoteId? = entries.getOrNull(index)?.id

    fun noteAtIndex(index: Int): Note? = entries.getOrNull(index)?.note

    fun indexOf(id: NoteId): Int = entries.indexOfFirst { it.id == id }

    operator fun get(id: NoteId): Note? = entries.firstOrNull { it.id == id }?.note

    fun insert(note: Note): Pair<EditNotes, NoteId> {
        val id = NoteId(nextId)
        return restore(id, note).let { it to id }
    }

    /** Puts a note back under an id it already had, so undo does not renumber. */
    fun restore(id: NoteId, note: Note): EditNotes {
        val updated = entries.filterTo(ArrayList(entries.size + 1)) { it.id != id }
        updated.add(insertionPoint(updated, note), Entry(id, note))
        return EditNotes(updated, maxOf(nextId, id.raw + 1))
    }

    fun replace(id: NoteId, note: Note): EditNotes =
        if (get(id) == null) this else restore(id, note)

    fun remove(id: NoteId): EditNotes {
        val without = entries.filter { it.id != id }
        return if (without.size == entries.size) this else EditNotes(without, nextId)
    }

    /** Moves every note by [delta], keeping ids and order. */
    fun shiftedBy(delta: Long): EditNotes {
        if (delta == 0L) return this
        val moved = entries.map { entry ->
            Entry(
                entry.id,
                entry.note.copy(
                    startTick = entry.note.startTick + delta,
                    endTick = entry.note.endTick + delta,
                ),
            )
        }
        return EditNotes(moved, nextId)
    }

    /**
     * Rebuilds [piece] around these notes.
     *
     * `endTick` is recomputed rather than carried: shortening the last note of a
     * piece should shorten the scrubber, and lengthening one past the end should
     * extend it. Whatever silence the original had after its final note — a
     * written rest, an empty closing bar — is measured once and kept, so the
     * piece does not creep shorter every time it is saved.
     */
    fun toPiece(piece: Piece, originalEndTick: Long): Piece {
        val updated = notes()
        val lastNoteEnd = updated.maxOfOrNull { it.endTick } ?: 0L
        val originalLastNoteEnd = piece.notes.maxOfOrNull { it.endTick } ?: 0L
        val tail = (originalEndTick - originalLastNoteEnd).coerceAtLeast(0L)
        return piece.copy(notes = updated, endTick = lastNoteEnd + tail)
    }

    private fun insertionPoint(list: List<Entry>, note: Note): Int {
        var low = 0
        var high = list.size
        while (low < high) {
            val mid = (low + high) / 2
            if (precedes(list[mid].note, note)) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        fun from(piece: Piece): EditNotes {
            val entries = piece.notes
                .sortedWith(ORDER)
                .mapIndexed { index, note -> Entry(NoteId(index), note) }
            return EditNotes(entries, entries.size)
        }

        /**
         * The order `Piece.notes` is required to be in.
         *
         * Pitch is the tie-break because the MIDI loader already sorts that way,
         * so a piece saved and reloaded comes back in the same order and a
         * selection does not appear to jump to a different note of the chord.
         */
        private val ORDER = compareBy<Note>({ it.startTick }, { it.pitch })

        private fun precedes(a: Note, b: Note): Boolean =
            a.startTick < b.startTick || (a.startTick == b.startTick && a.pitch < b.pitch)
    }
}

/**
 * A note mid-drag, before it is checked back into the piece.
 *
 * [Note] validates in its `init` block and *throws* on a pitch outside 0..127 or
 * an end before its start — which is precisely the state a finger passes through
 * on the way somewhere else. Dragging a note's end above its own start would
 * therefore crash under the user rather than simply refusing to go further. So
 * a drag works in drafts, which hold whatever the finger says, and [toNote]
 * clamps once at the point the draft becomes real.
 */
data class NoteDraft(
    val pitch: Int,
    val startTick: Long,
    val endTick: Long,
    val velocity: Int,
    val hand: Hand,
    val track: Int = 0,
    val voice: Int = 0,
    val finger: Int? = null,
    val scoreId: String? = null,
    val handPinned: Boolean = false,
) {
    val lengthTicks: Long get() = endTick - startTick

    /**
     * Clamps the draft into something [Note] will accept.
     *
     * The pitch clamp is the *piano's* range, not MIDI's: a note at MIDI 3 is
     * perfectly legal and completely undrawable, so letting a drag leave one
     * there would lose it from the highway with no way to get it back.
     */
    fun toNote(minLengthTicks: Long): Note {
        val start = startTick.coerceAtLeast(0L)
        val minLength = minLengthTicks.coerceAtLeast(1L)
        return Note(
            pitch = pitch.coerceIn(Pitch.LOWEST_PIANO, Pitch.HIGHEST_PIANO),
            startTick = start,
            endTick = maxOf(endTick, start + minLength),
            velocity = velocity.coerceIn(1, 127),
            hand = hand,
            track = track,
            voice = voice,
            finger = finger,
            scoreId = scoreId,
            handPinned = handPinned,
        )
    }

    companion object {
        fun of(note: Note) = NoteDraft(
            pitch = note.pitch,
            startTick = note.startTick,
            endTick = note.endTick,
            velocity = note.velocity,
            hand = note.hand,
            track = note.track,
            voice = note.voice,
            finger = note.finger,
            scoreId = note.scoreId,
            handPinned = note.handPinned,
        )
    }
}

/**
 * One reversible change to the notes.
 *
 * Move, resize from either end and every inspector nudge are all [Replace] — one
 * code path, one set of clamps, and undo cannot tell them apart, which is
 * exactly right.
 */
sealed interface EditCommand {
    data class Insert(val note: Note) : EditCommand
    data class Delete(val id: NoteId) : EditCommand
    data class Replace(val id: NoteId, val note: Note) : EditCommand

    /** Undoing a delete. Carries the id so a redo can still find the note. */
    data class Restore(val id: NoteId, val note: Note) : EditCommand

    /**
     * Several changes that undo as one.
     *
     * Without this, transposing a twelve-note chord would push twelve entries
     * onto the undo stack and take twelve presses to take back — which is not
     * what anyone means by "undo that". Every plural operation goes through here.
     */
    data class Batch(val commands: List<EditCommand>) : EditCommand

    /**
     * Shifts the whole piece later, to make room before its start.
     *
     * MIDI ticks cannot be negative, so "add a bar in front" has to mean "move
     * everything else back". Notes, tempo changes and both kinds of signature
     * all move together, which is why this cannot be expressed as a batch of
     * note edits.
     */
    data class Rebase(val deltaTicks: Long) : EditCommand
}

/**
 * Editing state for one song: the notes, the undo stack and the redo stack.
 *
 * Deliberately plain and synchronous. Everything here runs on the main thread in
 * response to a finger lifting — a few hundred small objects at a time — and the
 * simplicity is worth more than any cleverness would be.
 */
class EditSession(private val originalPiece: Piece) {

    private val originalEndTick = originalPiece.endTick

    var notes: EditNotes = EditNotes.from(originalPiece)
        private set

    /**
     * Everything about the piece except its notes, which [EditCommand.Rebase] moves.
     *
     * Held apart from [notes] because the two change on completely different
     * occasions: notes change constantly, this only when the piece is shifted.
     */
    private var frame: Piece = originalPiece

    /** How far the piece has been shifted in this session, in ticks. */
    var offsetTicks: Long = 0L
        private set

    private val undoStack = ArrayDeque<EditCommand>()
    private val redoStack = ArrayDeque<EditCommand>()

    /**
     * Where the hands part, computed once for the session.
     *
     * A note added by hand should join whichever hand the rest of that register
     * belongs to. Using the same split the importer uses means a new bass note in
     * a piece that lives below middle C is still a left-hand note, instead of
     * being shoved into the right by a hardcoded 60.
     */
    private val handSplit: Int = HandAssigner.splitPointFor(originalPiece.notes.map { it.pitch })

    /** Loudness for a new note: the piece's own median, so it does not stand out. */
    private val defaultVelocity: Int = originalPiece.notes
        .map { it.velocity }
        .sorted()
        .let { if (it.isEmpty()) DEFAULT_VELOCITY else it[it.size / 2] }
        .coerceIn(1, 127)

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val isDirty: Boolean get() = undoStack.isNotEmpty()

    fun handFor(pitch: Int): Hand = if (pitch >= handSplit) Hand.RIGHT else Hand.LEFT

    operator fun get(id: NoteId): Note? = notes[id]

    /** A new note at [pitch], starting at [startTick] and lasting [lengthTicks]. */
    fun newNote(pitch: Int, startTick: Long, lengthTicks: Long): NoteDraft {
        val clamped = pitch.coerceIn(Pitch.LOWEST_PIANO, Pitch.HIGHEST_PIANO)
        val start = startTick.coerceAtLeast(0L)
        return NoteDraft(
            pitch = clamped,
            startTick = start,
            endTick = start + lengthTicks,
            velocity = defaultVelocity,
            hand = handFor(clamped),
        )
    }

    /**
     * Applies [command] and records how to undo it.
     *
     * Returns the ids the command acted on, so the caller can keep the selection
     * on whatever just moved, or an empty list when nothing did — because the
     * command named notes that are no longer there.
     */
    fun apply(command: EditCommand): List<NoteId> {
        val outcome = run(command) ?: return emptyList()
        undoStack.addLast(outcome.inverse)
        if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        // A new edit after an undo abandons the branch that was undone. Keeping
        // it would let redo replay changes that no longer make sense against the
        // notes now on screen.
        redoStack.clear()
        return outcome.ids
    }

    fun undo(): List<NoteId> {
        val command = undoStack.removeLastOrNull() ?: return emptyList()
        val outcome = run(command) ?: return emptyList()
        redoStack.addLast(outcome.inverse)
        return outcome.ids
    }

    fun redo(): List<NoteId> {
        val command = redoStack.removeLastOrNull() ?: return emptyList()
        val outcome = run(command) ?: return emptyList()
        undoStack.addLast(outcome.inverse)
        return outcome.ids
    }

    fun toPiece(): Piece = notes.toPiece(frame, originalEndTick + offsetTicks)

    /**
     * Moves every note and marking later by [deltaTicks].
     *
     * The tempo map has to be rebuilt rather than shifted in place: it assigns
     * elapsed time zero to its *first* change whatever tick that sits at, so
     * simply moving the tick-0 tempo to N would make `tickToMicros(0)` negative
     * and every duration in the app wrong. Seeding a change at 0 keeps the origin
     * where everything else assumes it is.
     */
    private fun rebased(delta: Long): Piece {
        val shifted = frame.tempoMap.tempoChanges
            .map { (it.tick + delta) to it.microsPerQuarter }
        val seeded = if (shifted.none { it.first <= 0L }) {
            // The stretch now in front of the music keeps the tempo the piece
            // started with, so a count-in over it is not at some other speed.
            listOf(0L to (shifted.firstOrNull()?.second ?: TempoMap.DEFAULT_MICROS_PER_QUARTER)) +
                shifted
        } else {
            shifted
        }

        return frame.copy(
            // The frame's own notes move too. They are replaced by the live ones
            // in `toPiece`, but it measures the piece's trailing silence against
            // them — leave them behind and every rebase adds that silence twice.
            notes = frame.notes.map {
                it.copy(startTick = it.startTick + delta, endTick = it.endTick + delta)
            },
            tempoMap = TempoMap.build(frame.tempoMap.ticksPerQuarter, seeded),
            timeSignatures = frame.timeSignatures.map { it.copy(tick = it.tick + delta) }
                // The new leading bars need a metre of their own, or
                // `timeSignatureAt` falls back to a synthetic 4/4 for them.
                .let { list ->
                    if (list.none { it.tick <= 0L }) {
                        listOf(list.first().copy(tick = 0L)) + list
                    } else {
                        list
                    }
                },
            keySignatures = frame.keySignatures.map { it.copy(tick = it.tick + delta) }
                .let { list ->
                    if (list.isNotEmpty() && list.none { it.tick <= 0L }) {
                        listOf(list.first().copy(tick = 0L)) + list
                    } else {
                        list
                    }
                },
        )
    }

    private class Outcome(val ids: List<NoteId>, val inverse: EditCommand)

    /**
     * Runs a command and hands back its inverse.
     *
     * The inverse has to be built here rather than by the caller, because it
     * needs state that the command destroys: the note a delete is about to
     * remove, the note a replace is about to overwrite, and — for an insert —
     * the id that does not exist until the insert has happened.
     */
    private fun run(command: EditCommand): Outcome? = when (command) {
        is EditCommand.Insert -> {
            val (updated, id) = notes.insert(command.note)
            notes = updated
            Outcome(listOf(id), EditCommand.Delete(id))
        }

        is EditCommand.Restore -> {
            notes = notes.restore(command.id, command.note)
            Outcome(listOf(command.id), EditCommand.Delete(command.id))
        }

        is EditCommand.Delete -> {
            val existing = notes[command.id]
            if (existing == null) null else {
                notes = notes.remove(command.id)
                Outcome(listOf(command.id), EditCommand.Restore(command.id, existing))
            }
        }

        is EditCommand.Replace -> {
            val existing = notes[command.id]
            if (existing == null) null else {
                notes = notes.replace(command.id, command.note)
                Outcome(listOf(command.id), EditCommand.Replace(command.id, existing))
            }
        }

        // Members that no-op are dropped rather than failing the whole batch —
        // a stale id among twelve should not cost the other eleven. The inverse
        // is built from only what actually ran, and **reversed**: undoing means
        // walking back out the way you came in, which matters as soon as two
        // members touch the same note.
        is EditCommand.Rebase -> {
            val delta = command.deltaTicks
            if (delta == 0L) null else {
                frame = rebased(delta)
                offsetTicks += delta
                notes = notes.shiftedBy(delta)
                // Every id survives a shift, so the whole selection is still
                // whatever it was — just somewhere else.
                Outcome(emptyList(), EditCommand.Rebase(-delta))
            }
        }

        is EditCommand.Batch -> {
            val ids = ArrayList<NoteId>(command.commands.size)
            val inverses = ArrayList<EditCommand>(command.commands.size)
            for (member in command.commands) {
                val outcome = run(member) ?: continue
                ids += outcome.ids
                inverses += outcome.inverse
            }
            if (ids.isEmpty()) null else {
                Outcome(ids, EditCommand.Batch(inverses.asReversed().toList()))
            }
        }
    }

    companion object {
        /** Deep enough to cover a practice session's worth of fixes. */
        private const val MAX_HISTORY = 200

        /**
         * Matches the velocity the MusicXML path gives every note and the one a
         * tapped key is struck with, so a hand-added note sits with the others.
         */
        const val DEFAULT_VELOCITY = 80
    }
}
