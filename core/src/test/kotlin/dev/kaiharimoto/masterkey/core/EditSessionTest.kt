package dev.kaiharimoto.masterkey.core

import com.google.common.truth.Truth.assertThat
import dev.kaiharimoto.masterkey.core.edit.EditCommand
import dev.kaiharimoto.masterkey.core.edit.EditSession
import dev.kaiharimoto.masterkey.core.edit.NoteDraft
import dev.kaiharimoto.masterkey.core.edit.NoteId
import dev.kaiharimoto.masterkey.core.edit.Rebase
import dev.kaiharimoto.masterkey.core.midi.Pitch
import dev.kaiharimoto.masterkey.core.model.Hand
import dev.kaiharimoto.masterkey.core.model.KeySignature
import dev.kaiharimoto.masterkey.core.model.Note
import dev.kaiharimoto.masterkey.core.model.Piece
import dev.kaiharimoto.masterkey.core.model.TempoMap
import dev.kaiharimoto.masterkey.core.model.TimeSignature
import org.junit.Test
import kotlin.random.Random

class EditSessionTest {

    private val ppq = 480

    private fun note(pitch: Int, start: Long, end: Long, hand: Hand = Hand.RIGHT) =
        Note(pitch, start, end, 80, hand)

    private fun pieceOf(vararg notes: Note, endTick: Long? = null) = Piece(
        notes = notes.sortedWith(compareBy({ it.startTick }, { it.pitch })),
        tempoMap = TempoMap.default(ppq),
        timeSignatures = listOf(TimeSignature(0, 4, 4)),
        keySignatures = emptyList(),
        endTick = endTick ?: (notes.maxOfOrNull { it.endTick } ?: 0L),
    )

    private fun Piece.summary() = notes.map { listOf(it.pitch, it.startTick, it.endTick, it.hand) }

    @Test
    fun `moving a note earlier keeps the list sorted`() {
        // The invariant that matters: an unsorted list does not throw, it just
        // makes the highway's binary search and the scheduler's cursor stop
        // finding notes, so the symptom is music silently going missing.
        val session = EditSession(pieceOf(note(60, 0, 480), note(62, 480, 960), note(64, 960, 1440)))
        val id = session.notes.idAt(2)!!

        session.apply(EditCommand.Replace(id, note(64, 0, 240)))

        assertThat(session.toPiece().notes.map { it.startTick }).isInOrder()
        assertThat(session.toPiece().notes.first().pitch).isEqualTo(60)
        assertThat(session.toPiece().notes[1].pitch).isEqualTo(64)
    }

    @Test
    fun `undo restores the piece exactly`() {
        val piece = pieceOf(note(60, 0, 480), note(64, 0, 480), note(48, 480, 1440, Hand.LEFT))
        val session = EditSession(piece)
        val id = session.notes.idAt(1)!!

        session.apply(EditCommand.Replace(id, note(71, 1920, 2400)))
        session.apply(EditCommand.Insert(note(67, 240, 480)))
        session.apply(EditCommand.Delete(session.notes.idAt(0)!!))
        repeat(3) { session.undo() }

        assertThat(session.toPiece().summary()).isEqualTo(piece.summary())
        assertThat(session.toPiece().endTick).isEqualTo(piece.endTick)
        assertThat(session.canUndo).isFalse()
    }

    @Test
    fun `redo replays what undo took back`() {
        val session = EditSession(pieceOf(note(60, 0, 480)))
        session.apply(EditCommand.Insert(note(67, 480, 960)))
        val after = session.toPiece().summary()

        session.undo()
        assertThat(session.toPiece().notes).hasSize(1)

        session.redo()
        assertThat(session.toPiece().summary()).isEqualTo(after)
    }

    @Test
    fun `undoing a delete keeps the note's identity, so redo can still find it`() {
        val session = EditSession(pieceOf(note(60, 0, 480), note(64, 0, 480)))
        val id = session.notes.idAt(1)!!

        session.apply(EditCommand.Delete(id))
        session.undo()

        assertThat(session[id]).isNotNull()
        session.redo()
        assertThat(session[id]).isNull()
        assertThat(session.toPiece().notes).hasSize(1)
    }

    @Test
    fun `a new edit after an undo abandons the redo branch`() {
        val session = EditSession(pieceOf(note(60, 0, 480)))
        session.apply(EditCommand.Insert(note(67, 480, 960)))
        session.undo()

        session.apply(EditCommand.Insert(note(72, 960, 1440)))

        assertThat(session.canRedo).isFalse()
        assertThat(session.toPiece().notes.map { it.pitch }).containsExactly(60, 72)
    }

    @Test
    fun `deleting the last note shortens the piece`() {
        val session = EditSession(pieceOf(note(60, 0, 480), note(64, 1440, 1920)))

        session.apply(EditCommand.Delete(session.notes.idAt(1)!!))

        assertThat(session.toPiece().endTick).isEqualTo(480)
    }

    @Test
    fun `lengthening the last note lengthens the piece`() {
        val session = EditSession(pieceOf(note(60, 0, 480)))

        session.apply(EditCommand.Replace(session.notes.idAt(0)!!, note(60, 0, 1920)))

        assertThat(session.toPiece().endTick).isEqualTo(1920)
    }

    @Test
    fun `a trailing rest is kept across edits`() {
        // The original ends a bar after its last note. Editing the notes must not
        // quietly trim that silence away.
        val piece = pieceOf(note(60, 0, 480), endTick = 1920)
        val session = EditSession(piece)

        session.apply(EditCommand.Replace(session.notes.idAt(0)!!, note(60, 0, 960)))

        assertThat(session.toPiece().endTick).isEqualTo(2400)
    }

    @Test
    fun `a new note joins the hand its register belongs to`() {
        // A piece that lives in the bass has its split well below middle C, so a
        // hardcoded 60 would shove its whole left hand into the right.
        val bass = pieceOf(
            *(0 until 12).map { note(36 + it % 5, it * 240L, it * 240L + 240, Hand.LEFT) }
                .toTypedArray(),
            *(0 until 12).map { note(55 + it % 5, it * 240L, it * 240L + 240, Hand.RIGHT) }
                .toTypedArray(),
        )
        val session = EditSession(bass)

        assertThat(session.handFor(38)).isEqualTo(Hand.LEFT)
        assertThat(session.handFor(57)).isEqualTo(Hand.RIGHT)
    }

    @Test
    fun `a new note takes the piece's median velocity`() {
        val session = EditSession(
            pieceOf(
                note(60, 0, 240).copy(velocity = 30),
                note(62, 240, 480).copy(velocity = 64),
                note(64, 480, 720).copy(velocity = 120),
            ),
        )

        assertThat(session.newNote(67, 0, 240).toNote(1).velocity).isEqualTo(64)
    }

    @Test
    fun `a note dragged past its own start is clamped, not crashed`() {
        // Note's init block throws on endTick < startTick, so a drag that passes
        // through an inverted state would crash under the user's finger. Drafts
        // are what let the drag hold that state and be clamped on the way out.
        val session = EditSession(pieceOf(note(60, 480, 960)))
        val id = session.notes.idAt(0)!!
        val dragged = NoteDraft.of(session[id]!!).copy(endTick = 200)

        session.apply(EditCommand.Replace(id, dragged.toNote(minLengthTicks = 60)))

        assertThat(session[id]!!.endTick).isEqualTo(540)
    }

    @Test
    fun `a draft can hold a state Note itself would refuse`() {
        val draft = NoteDraft(pitch = 200, startTick = 480, endTick = 0, velocity = 0, hand = Hand.RIGHT)

        val note = draft.toNote(minLengthTicks = 120)

        assertThat(note.pitch).isEqualTo(Pitch.HIGHEST_PIANO)
        assertThat(note.endTick).isEqualTo(600)
        assertThat(note.velocity).isEqualTo(1)
    }

    @Test
    fun `pitch is clamped to the piano, not to the MIDI range`() {
        val far = NoteDraft.of(note(60, 0, 240)).copy(pitch = 3).toNote(1)
        val high = NoteDraft.of(note(60, 0, 240)).copy(pitch = 126).toNote(1)

        assertThat(far.pitch).isEqualTo(Pitch.LOWEST_PIANO)
        assertThat(high.pitch).isEqualTo(Pitch.HIGHEST_PIANO)
    }

    @Test
    fun `the invariants survive a long run of random edits`() {
        val random = Random(20260915)
        val starting = (0 until 40).map { note(48 + it % 24, it * 240L, it * 240L + 240) }
        val piece = pieceOf(*starting.toTypedArray())
        val session = EditSession(piece)
        var applied = 0

        // Fewer commands than the history cap, so undoing everything is actually
        // possible — the cap deliberately forgets beyond that, and asserting
        // otherwise would be testing a promise the session does not make.
        repeat(150) {
            val ids = (0 until session.notes.size).mapNotNull { session.notes.idAt(it) }
            val command = when {
                ids.isEmpty() || random.nextInt(3) == 0 ->
                    EditCommand.Insert(
                        session.newNote(
                            pitch = random.nextInt(0, 128),
                            startTick = random.nextLong(-2_000, 12_000),
                            lengthTicks = random.nextLong(-500, 1_000),
                        ).toNote(minLengthTicks = 30),
                    )

                random.nextInt(4) == 0 -> EditCommand.Delete(ids.random(random))

                else -> {
                    // Deliberately asks for states Note itself would reject —
                    // an inverted length, a pitch off the keyboard, a negative
                    // start — because that is what a finger mid-drag produces.
                    val id = ids.random(random)
                    val start = random.nextLong(-2_000, 12_000)
                    EditCommand.Replace(
                        id,
                        NoteDraft.of(session[id]!!).copy(
                            pitch = random.nextInt(0, 128),
                            startTick = start,
                            endTick = start + random.nextLong(-500, 1_000),
                        ).toNote(minLengthTicks = 30),
                    )
                }
            }
            if (session.apply(command).isNotEmpty()) applied++

            val notes = session.toPiece().notes
            assertThat(notes.map { it.startTick }).isInOrder()
            for (n in notes) {
                assertThat(n.pitch).isIn(Pitch.LOWEST_PIANO..Pitch.HIGHEST_PIANO)
                assertThat(n.endTick).isGreaterThan(n.startTick)
            }
        }

        assertThat(applied).isGreaterThan(100)

        // Every successful command must undo cleanly back to the original.
        repeat(applied) { session.undo() }

        assertThat(session.canUndo).isFalse()
        assertThat(session.toPiece().summary()).isEqualTo(piece.summary())
        assertThat(session.toPiece().endTick).isEqualTo(piece.endTick)
    }

    @Test
    fun `a batch applies and undoes as a single step`() {
        // The whole point: transposing a chord is one press of a button, so it
        // has to be one press of Undo — not one per note.
        val session = EditSession(pieceOf(note(60, 0, 480), note(64, 0, 480), note(67, 0, 480)))
        val ids = (0..2).map { session.notes.idAt(it)!! }

        val touched = session.apply(
            EditCommand.Batch(
                ids.map { id ->
                    EditCommand.Replace(id, session[id]!!.let { it.copy(pitch = it.pitch + 12) })
                },
            ),
        )

        assertThat(touched).hasSize(3)
        assertThat(session.toPiece().notes.map { it.pitch }).containsExactly(72, 76, 79)

        session.undo()

        assertThat(session.toPiece().notes.map { it.pitch }).containsExactly(60, 64, 67)
        assertThat(session.canUndo).isFalse()
    }

    @Test
    fun `a batch undoes in reverse, so members touching one note unwind cleanly`() {
        val session = EditSession(pieceOf(note(60, 0, 480)))
        val id = session.notes.idAt(0)!!

        session.apply(
            EditCommand.Batch(
                listOf(
                    EditCommand.Replace(id, note(62, 0, 480)),
                    EditCommand.Replace(id, note(64, 0, 480)),
                ),
            ),
        )
        assertThat(session[id]!!.pitch).isEqualTo(64)

        session.undo()

        assertThat(session[id]!!.pitch).isEqualTo(60)
    }

    @Test
    fun `a batch survives a member that refers to a note already gone`() {
        val session = EditSession(pieceOf(note(60, 0, 480), note(64, 0, 480)))
        val live = session.notes.idAt(0)!!
        val stale = session.notes.idAt(1)!!
        session.apply(EditCommand.Delete(stale))

        val touched = session.apply(
            EditCommand.Batch(
                listOf(
                    EditCommand.Replace(stale, note(64, 0, 480)),
                    EditCommand.Replace(live, note(60, 960, 1440)),
                ),
            ),
        )

        assertThat(touched).containsExactly(live)
        assertThat(session[live]!!.startTick).isEqualTo(960)
    }

    @Test
    fun `a batch of nothing applicable is a no-op, not a phantom undo step`() {
        val session = EditSession(pieceOf(note(60, 0, 480)))
        val id = session.notes.idAt(0)!!
        session.apply(EditCommand.Delete(id))
        val before = session.toPiece().summary()

        val touched = session.apply(
            EditCommand.Batch(listOf(EditCommand.Replace(id, note(64, 0, 480)))),
        )

        assertThat(touched).isEmpty()
        assertThat(session.toPiece().summary()).isEqualTo(before)
    }

    @Test
    fun `rebasing moves the notes and the markings together`() {
        val piece = Piece(
            notes = listOf(note(60, 0, 480), note(64, 960, 1440)),
            tempoMap = TempoMap.build(ppq, listOf(0L to 500_000, 960L to 300_000)),
            timeSignatures = listOf(TimeSignature(0, 3, 4)),
            keySignatures = listOf(KeySignature(0, 2, false)),
            endTick = 1920,
        )
        val session = EditSession(piece)

        session.apply(EditCommand.Rebase(1440))
        val shifted = session.toPiece()

        assertThat(shifted.notes.map { it.startTick }).containsExactly(1440L, 2400L).inOrder()
        assertThat(shifted.endTick).isEqualTo(1920 + 1440)
        assertThat(session.offsetTicks).isEqualTo(1440)
        // The original tempo change moved with the music...
        assertThat(shifted.tempoMap.tempoChanges.map { it.tick }).contains(2400L)
        // ...and the new leading bars kept the tempo and metre the piece opened
        // with, rather than falling back to a synthetic 4/4 at 120bpm.
        assertThat(shifted.timeSignatureAt(0).numerator).isEqualTo(3)
        assertThat(shifted.tempoMap.bpmAt(0)).isWithin(0.01).of(120.0)
    }

    @Test
    fun `rebasing never makes time before the piece run backwards`() {
        // TempoMap gives elapsed-zero to its *first* change whatever tick it sits
        // at, so shifting the tick-0 tempo without seeding a new one would make
        // tickToMicros(0) negative and every duration in the app wrong.
        val piece = Piece(
            notes = listOf(note(60, 0, 480)),
            tempoMap = TempoMap.build(ppq, listOf(0L to 400_000)),
            timeSignatures = listOf(TimeSignature(0, 4, 4)),
            keySignatures = emptyList(),
            endTick = 480,
        )
        val session = EditSession(piece)

        session.apply(EditCommand.Rebase(1920))
        val shifted = session.toPiece()

        assertThat(shifted.tempoMap.tickToMicros(0)).isAtLeast(0L)
        assertThat(shifted.tempoMap.tickToMicros(1920)).isGreaterThan(0L)
        assertThat(shifted.durationMicros).isGreaterThan(piece.durationMicros)
    }

    @Test
    fun `undoing a rebase puts the piece back exactly`() {
        val piece = pieceOf(note(60, 0, 480), note(64, 960, 1440))
        val session = EditSession(piece)

        session.apply(EditCommand.Rebase(1920))
        session.undo()

        assertThat(session.toPiece().summary()).isEqualTo(piece.summary())
        assertThat(session.toPiece().endTick).isEqualTo(piece.endTick)
        assertThat(session.offsetTicks).isEqualTo(0)
    }

    @Test
    fun `a rebase and the note it made room for undo as one step`() {
        val piece = pieceOf(note(60, 1920, 2400))
        val session = EditSession(piece)

        session.apply(
            EditCommand.Batch(
                listOf(EditCommand.Rebase(1920), EditCommand.Insert(note(67, 0, 480))),
            ),
        )
        assertThat(session.toPiece().notes).hasSize(2)

        session.undo()

        assertThat(session.toPiece().summary()).isEqualTo(piece.summary())
        assertThat(session.canUndo).isFalse()
    }

    @Test
    fun `a rebase keeps every note's identity, so the selection survives it`() {
        val session = EditSession(pieceOf(note(60, 0, 480), note(64, 480, 960)))
        val id = session.notes.idAt(1)!!

        session.apply(EditCommand.Rebase(960))

        assertThat(session[id]).isNotNull()
        assertThat(session[id]!!.startTick).isEqualTo(1440)
    }

    @Test
    fun `the history is capped, so a long session cannot grow without bound`() {
        val session = EditSession(pieceOf(note(60, 0, 480)))

        repeat(500) { session.apply(EditCommand.Insert(session.newNote(72, it * 10L, 240).toNote(1))) }
        var undos = 0
        while (session.undo().isNotEmpty()) undos++

        assertThat(undos).isAtMost(200)
        // The oldest edits are forgotten rather than half-applied: what remains
        // is still a valid piece, just not the one we started from.
        assertThat(session.toPiece().notes.map { it.startTick }).isInOrder()
    }

    // ---- making room in front of the piece ----
    //
    // These are the tests v1.6.0 did not have. It had plenty that drove
    // `EditCommand.Rebase` straight at the session, all of which passed, while
    // the path a finger actually takes to reach one could not run at all.

    private val bar = ppq * 4L

    private fun draft(start: Long, end: Long, pitch: Int = 60) =
        NoteDraft(pitch, start, end, 80, Hand.RIGHT)

    @Test
    fun `a tick before the start shifts by a whole bar, never by less`() {
        // Rounding to the bar is the point: a pickup belongs in a bar of its
        // own, and an arbitrary offset would displace every bar line after it.
        assertThat(Rebase.shiftToFit(-1, bar)).isEqualTo(bar)
        assertThat(Rebase.shiftToFit(-bar, bar)).isEqualTo(bar)
        assertThat(Rebase.shiftToFit(-bar - 1, bar)).isEqualTo(bar * 2)
        assertThat(Rebase.shiftToFit(-bar * 3, bar)).isEqualTo(bar * 3)
    }

    @Test
    fun `a tick already inside the piece shifts by nothing`() {
        assertThat(Rebase.shiftToFit(0, bar)).isEqualTo(0)
        assertThat(Rebase.shiftToFit(1, bar)).isEqualTo(0)
        assertThat(Rebase.shiftToFit(10_000, bar)).isEqualTo(0)
    }

    @Test
    fun `the shift always lands the note at or after zero`() {
        // A bar length of zero cannot happen from a real file, but the metre is
        // read off the piece and dividing by it must not be a crash.
        for (perBar in listOf(0L, 1L, 160L, 720L, bar)) {
            for (start in listOf(-1L, -7L, -bar, -bar * 5 - 3)) {
                assertThat(start + Rebase.shiftToFit(start, perBar)).isAtLeast(0L)
            }
        }
    }

    @Test
    fun `the shift honours the metre in force where the note lands`() {
        // Three-four: the room made is three quarters, not four.
        assertThat(Rebase.shiftToFit(-1, ppq * 3L)).isEqualTo(ppq * 3L)
    }

    @Test
    fun `a note drawn before the start rebases the piece and inserts into the room`() {
        // The assertion that would have caught v1.6.0: the whole reason the
        // feature existed, and nothing asserted it end to end.
        val shifted = Rebase.commandFor(
            edits = listOf(null to draft(-ppq.toLong(), 0)),
            minLengthTicks = 1,
            ticksPerBar = bar,
        )!!

        assertThat(shifted.shiftTicks).isEqualTo(bar)
        val batch = shifted.command as EditCommand.Batch
        assertThat(batch.commands.first()).isEqualTo(EditCommand.Rebase(bar))
        val inserted = (batch.commands[1] as EditCommand.Insert).note
        assertThat(inserted.startTick).isEqualTo(bar - ppq)
        assertThat(inserted.endTick).isEqualTo(bar)
    }

    @Test
    fun `a note dragged before the start rebases too`() {
        // The other half of "I cannot change where the first note begins": a
        // note that is already in the piece, dragged earlier than bar 1.
        val id = NoteId(7)
        val shifted = Rebase.commandFor(
            edits = listOf(id to draft(-240, 240)),
            minLengthTicks = 1,
            ticksPerBar = bar,
        )!!

        val batch = shifted.command as EditCommand.Batch
        assertThat(batch.commands.first()).isEqualTo(EditCommand.Rebase(bar))
        val replaced = batch.commands[1] as EditCommand.Replace
        assertThat(replaced.id).isEqualTo(id)
        assertThat(replaced.note.startTick).isEqualTo(bar - 240)
    }

    @Test
    fun `a selection is shifted by its earliest note, and keeps its shape`() {
        val first = NoteId(1)
        val second = NoteId(2)
        val shifted = Rebase.commandFor(
            edits = listOf(second to draft(-240, 240, 64), first to draft(-bar, -240, 60)),
            minLengthTicks = 1,
            ticksPerBar = bar,
        )!!

        // One bar is enough for the earliest of them, so one bar is what is made.
        assertThat(shifted.shiftTicks).isEqualTo(bar)
        val batch = shifted.command as EditCommand.Batch
        val starts = batch.commands.drop(1).map { (it as EditCommand.Replace).note.startTick }
        assertThat(starts).containsExactly(bar - 240, 0L).inOrder()
    }

    @Test
    fun `an edit inside the piece carries no rebase at all`() {
        val shifted = Rebase.commandFor(
            edits = listOf(NoteId(1) to draft(480, 960)),
            minLengthTicks = 1,
            ticksPerBar = bar,
        )!!

        assertThat(shifted.shiftTicks).isEqualTo(0)
        assertThat(shifted.command).isInstanceOf(EditCommand.Replace::class.java)
    }

    @Test
    fun `nothing to apply is nothing to do`() {
        assertThat(Rebase.commandFor(emptyList(), 1, bar)).isNull()
    }

    @Test
    fun `the rebase and the insert undo as one step`() {
        // Half of it undone would leave the piece displaced with nothing in the
        // room it made — a shift the user never asked for and cannot see.
        val session = EditSession(pieceOf(note(60, 0, 480), endTick = 1920))
        val before = session.toPiece().summary()
        val shifted = Rebase.commandFor(
            edits = listOf(null to draft(-ppq.toLong(), 0, 55)),
            minLengthTicks = 1,
            ticksPerBar = bar,
        )!!

        session.apply(shifted.command)
        assertThat(session.toPiece().notes).hasSize(2)
        assertThat(session.offsetTicks).isEqualTo(bar)

        session.undo()

        assertThat(session.toPiece().summary()).isEqualTo(before)
        assertThat(session.offsetTicks).isEqualTo(0)
    }
}
