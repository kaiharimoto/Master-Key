package dev.kaiharimoto.masterkey.core

import com.google.common.truth.Truth.assertThat
import dev.kaiharimoto.masterkey.core.model.Hand
import dev.kaiharimoto.masterkey.core.score.MusicXmlParser
import dev.kaiharimoto.masterkey.core.score.toPiece
import org.junit.Test

/**
 * Covers the MusicXML-only path: a score with no MIDI beside it has to produce
 * the same sort of [dev.kaiharimoto.masterkey.core.model.Piece] the MIDI loader
 * does, because everything downstream — the scheduler, the highway's binary
 * search, the metronome — assumes the invariants that loader upholds.
 */
class ScorePieceTest {

    private fun score(body: String, attributes: String = DEFAULT_ATTRIBUTES): String =
        """<?xml version="1.0" encoding="UTF-8"?>
           <score-partwise version="4.0">
             <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
             <part id="P1">$body</part>
           </score-partwise>""".trimIndent().replace("%ATTRIBUTES%", attributes)

    private fun parse(xml: String) = MusicXmlParser.parse(xml.toByteArray())

    @Test
    fun `converts a simple melody into ticks`() {
        val piece = parse(
            score(
                """<measure number="1">%ATTRIBUTES%
                     <note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><staff>1</staff></note>
                     <note><pitch><step>D</step><octave>4</octave></pitch><duration>4</duration><staff>1</staff></note>
                   </measure>""",
            ),
        ).toPiece()

        assertThat(piece.notes).hasSize(2)
        // divisions = 4, so one division is a sixteenth; a duration of 4 is a
        // quarter, which is 480 ticks at the default resolution.
        assertThat(piece.notes[0].pitch).isEqualTo(60)
        assertThat(piece.notes[0].startTick).isEqualTo(0)
        assertThat(piece.notes[0].endTick).isEqualTo(480)
        assertThat(piece.notes[1].startTick).isEqualTo(480)
        assertThat(piece.endTick).isEqualTo(960)
    }

    @Test
    fun `merges a note tied across a barline into one`() {
        val piece = parse(
            score(
                """<measure number="1">%ATTRIBUTES%
                     <note><pitch><step>C</step><octave>4</octave></pitch><duration>16</duration>
                       <staff>1</staff><tie type="start"/></note>
                   </measure>
                   <measure number="2">
                     <note><pitch><step>C</step><octave>4</octave></pitch><duration>16</duration>
                       <staff>1</staff><tie type="stop"/></note>
                   </measure>""",
            ),
        ).toPiece()

        // Two noteheads, one sounding note, spanning both bars. Mapping them
        // one-to-one would re-strike the key halfway through.
        assertThat(piece.notes).hasSize(1)
        assertThat(piece.notes[0].startTick).isEqualTo(0)
        assertThat(piece.notes[0].endTick).isEqualTo(3840)
    }

    @Test
    fun `chord members share an onset`() {
        val piece = parse(
            score(
                """<measure number="1">%ATTRIBUTES%
                     <note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><staff>1</staff></note>
                     <note><chord/><pitch><step>E</step><octave>4</octave></pitch><duration>4</duration><staff>1</staff></note>
                     <note><chord/><pitch><step>G</step><octave>4</octave></pitch><duration>4</duration><staff>1</staff></note>
                   </measure>""",
            ),
        ).toPiece()

        assertThat(piece.notes).hasSize(3)
        assertThat(piece.notes.map { it.startTick }.distinct()).containsExactly(0L)
        assertThat(piece.notes.map { it.pitch }).containsExactly(60, 64, 67)
    }

    @Test
    fun `a grand staff assigns hands from the staff number`() {
        val piece = parse(
            score(
                """<measure number="1">%ATTRIBUTES%
                     <note><pitch><step>G</step><octave>5</octave></pitch><duration>16</duration><voice>1</voice><staff>1</staff></note>
                     <backup><duration>16</duration></backup>
                     <note><pitch><step>C</step><octave>3</octave></pitch><duration>16</duration><voice>5</voice><staff>2</staff></note>
                   </measure>""",
            ),
        ).toPiece()

        val byPitch = piece.notes.associateBy { it.pitch }
        assertThat(byPitch.getValue(79).hand).isEqualTo(Hand.RIGHT)
        assertThat(byPitch.getValue(48).hand).isEqualTo(Hand.LEFT)
        // <backup> put them at the same moment, which is the whole point of a
        // grand staff — getting this wrong staggers the hands.
        assertThat(byPitch.getValue(79).startTick).isEqualTo(byPitch.getValue(48).startTick)
    }

    @Test
    fun `keeps the written time signature, tempo and fingering`() {
        val piece = parse(
            score(
                """<measure number="1">
                     <attributes><divisions>4</divisions>
                       <key><fifths>-3</fifths><mode>minor</mode></key>
                       <time><beats>3</beats><beat-type>4</beat-type></time>
                       <clef number="1"><sign>G</sign><line>2</line></clef>
                     </attributes>
                     <sound tempo="90"/>
                     <note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><staff>1</staff>
                       <notations><technical><fingering>3</fingering></technical></notations></note>
                   </measure>""",
            ),
        ).toPiece()

        assertThat(piece.timeSignatureAt(0).numerator).isEqualTo(3)
        assertThat(piece.timeSignatureAt(0).denominator).isEqualTo(4)
        assertThat(piece.tempoMap.bpmAt(0)).isWithin(0.5).of(90.0)
        assertThat(piece.keySignatureAt(0)?.sharps).isEqualTo(-3)
        assertThat(piece.keySignatureAt(0)?.isMinor).isTrue()
        assertThat(piece.notes.single().finger).isEqualTo(3)
    }

    @Test
    fun `notes come out sorted by start tick`() {
        // <backup> makes document order differ from time order, which is exactly
        // the case that would leave the list unsorted.
        val piece = parse(
            score(
                """<measure number="1">%ATTRIBUTES%
                     <note><pitch><step>C</step><octave>5</octave></pitch><duration>8</duration><voice>1</voice><staff>1</staff></note>
                     <note><pitch><step>D</step><octave>5</octave></pitch><duration>8</duration><voice>1</voice><staff>1</staff></note>
                     <backup><duration>16</duration></backup>
                     <note><pitch><step>C</step><octave>3</octave></pitch><duration>4</duration><voice>5</voice><staff>2</staff></note>
                     <note><pitch><step>D</step><octave>3</octave></pitch><duration>4</duration><voice>5</voice><staff>2</staff></note>
                   </measure>""",
            ),
        ).toPiece()

        assertThat(piece.notes.map { it.startTick }).isInOrder()
    }

    @Test
    fun `a single-staff score still splits the hands`() {
        val notes = (0 until 8).joinToString("") { index ->
            val octave = if (index % 2 == 0) 5 else 2
            """<note><pitch><step>C</step><octave>$octave</octave></pitch>
                 <duration>4</duration><staff>1</staff></note>"""
        }
        val piece = parse(score("""<measure number="1">%ATTRIBUTES%$notes</measure>""")).toPiece()

        assertThat(piece.notes.filter { it.pitch >= 72 }.map { it.hand }.distinct())
            .containsExactly(Hand.RIGHT)
        assertThat(piece.notes.filter { it.pitch <= 48 }.map { it.hand }.distinct())
            .containsExactly(Hand.LEFT)
    }

    @Test
    fun `a score with no sounding notes yields an empty piece rather than throwing`() {
        val piece = parse(
            score("""<measure number="1">%ATTRIBUTES%<note><rest/><duration>16</duration><staff>1</staff></note></measure>"""),
        ).toPiece()

        assertThat(piece.notes).isEmpty()
        assertThat(piece.endTick).isEqualTo(0)
    }

    private companion object {
        const val DEFAULT_ATTRIBUTES =
            "<attributes><divisions>4</divisions>" +
                "<time><beats>4</beats><beat-type>4</beat-type></time>" +
                "<clef number=\"1\"><sign>G</sign><line>2</line></clef></attributes>"
    }
}
