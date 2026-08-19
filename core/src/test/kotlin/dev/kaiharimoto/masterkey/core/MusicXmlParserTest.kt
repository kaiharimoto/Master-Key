package dev.kaiharimoto.masterkey.core

import com.google.common.truth.Truth.assertThat
import dev.kaiharimoto.masterkey.core.score.MusicXmlParser
import org.junit.Test

class MusicXmlParserTest {

    private fun score(body: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <score-partwise version="4.0">
          <work><work-title>Test Piece</work-title></work>
          <identification><creator type="composer">A Composer</creator></identification>
          <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
          <part id="P1">
            $body
          </part>
        </score-partwise>
    """.trimIndent()

    @Test
    fun `reads title and composer`() {
        val doc = MusicXmlParser.parse(
            score(
                """
                <measure number="1">
                  <attributes><divisions>4</divisions></attributes>
                  <note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration></note>
                </measure>
                """,
            ).toByteArray(),
        )

        assertThat(doc.title).isEqualTo("Test Piece")
        assertThat(doc.composer).isEqualTo("A Composer")
        assertThat(doc.divisions).isEqualTo(4)
    }

    @Test
    fun `converts step, alter and octave to midi pitch`() {
        val doc = MusicXmlParser.parse(
            score(
                """
                <measure number="1">
                  <attributes><divisions>1</divisions></attributes>
                  <note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration></note>
                  <note><pitch><step>A</step><alter>-1</alter><octave>3</octave></pitch><duration>1</duration></note>
                  <note><pitch><step>F</step><alter>1</alter><octave>5</octave></pitch><duration>1</duration></note>
                </measure>
                """,
            ).toByteArray(),
        )

        assertThat(doc.soundingNotes.map { it.pitch }).containsExactly(60, 56, 78).inOrder()
    }

    @Test
    fun `chord members share the onset of the note they follow`() {
        // Getting this wrong spreads a chord out into an arpeggio.
        val doc = MusicXmlParser.parse(
            score(
                """
                <measure number="1">
                  <attributes><divisions>4</divisions></attributes>
                  <note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration></note>
                  <note><chord/><pitch><step>E</step><octave>4</octave></pitch><duration>4</duration></note>
                  <note><chord/><pitch><step>G</step><octave>4</octave></pitch><duration>4</duration></note>
                  <note><pitch><step>D</step><octave>4</octave></pitch><duration>4</duration></note>
                </measure>
                """,
            ).toByteArray(),
        )

        val onsets = doc.soundingNotes.associate { it.pitch to it.onset }
        assertThat(onsets[60]).isEqualTo(0)
        assertThat(onsets[64]).isEqualTo(0)
        assertThat(onsets[67]).isEqualTo(0)
        // The following note starts one quarter later, not four.
        assertThat(onsets[62]).isEqualTo(4)
    }

    @Test
    fun `backup rewinds the cursor so the second staff lines up`() {
        // This is how grand-staff piano music is encoded. Ignoring backup would
        // place the left hand after the right instead of underneath it.
        val doc = MusicXmlParser.parse(
            score(
                """
                <measure number="1">
                  <attributes><divisions>4</divisions><staves>2</staves></attributes>
                  <note><pitch><step>C</step><octave>5</octave></pitch><duration>4</duration><staff>1</staff></note>
                  <note><pitch><step>D</step><octave>5</octave></pitch><duration>4</duration><staff>1</staff></note>
                  <backup><duration>8</duration></backup>
                  <note><pitch><step>C</step><octave>3</octave></pitch><duration>8</duration><staff>2</staff></note>
                </measure>
                """,
            ).toByteArray(),
        )

        val bass = doc.soundingNotes.first { it.pitch == 48 }
        val firstTreble = doc.soundingNotes.first { it.pitch == 72 }
        assertThat(bass.onset).isEqualTo(firstTreble.onset)
        assertThat(bass.staff).isEqualTo(2)
        assertThat(doc.hasTwoStaves).isTrue()
    }

    @Test
    fun `forward advances the cursor`() {
        val doc = MusicXmlParser.parse(
            score(
                """
                <measure number="1">
                  <attributes><divisions>4</divisions></attributes>
                  <forward><duration>4</duration></forward>
                  <note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration></note>
                </measure>
                """,
            ).toByteArray(),
        )

        assertThat(doc.soundingNotes.single().onset).isEqualTo(4)
    }

    @Test
    fun `grace notes do not advance time`() {
        val doc = MusicXmlParser.parse(
            score(
                """
                <measure number="1">
                  <attributes><divisions>4</divisions></attributes>
                  <note><grace/><pitch><step>B</step><octave>3</octave></pitch></note>
                  <note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration></note>
                  <note><pitch><step>D</step><octave>4</octave></pitch><duration>4</duration></note>
                </measure>
                """,
            ).toByteArray(),
        )

        // The grace note is recorded but excluded from the sounding sequence, and
        // the notes after it keep their proper onsets.
        assertThat(doc.notes.any { it.isGrace }).isTrue()
        assertThat(doc.soundingNotes.map { it.onset }).containsExactly(0L, 4L).inOrder()
    }

    @Test
    fun `measures accumulate onsets across bars`() {
        val doc = MusicXmlParser.parse(
            score(
                """
                <measure number="1">
                  <attributes><divisions>4</divisions></attributes>
                  <note><pitch><step>C</step><octave>4</octave></pitch><duration>16</duration></note>
                </measure>
                <measure number="2">
                  <note><pitch><step>D</step><octave>4</octave></pitch><duration>16</duration></note>
                </measure>
                """,
            ).toByteArray(),
        )

        assertThat(doc.measures).hasSize(2)
        assertThat(doc.soundingNotes.map { it.onset }).containsExactly(0L, 16L).inOrder()
    }

    @Test
    fun `recognises a pickup bar`() {
        val doc = MusicXmlParser.parse(
            score(
                """
                <measure number="0" implicit="yes">
                  <attributes><divisions>4</divisions></attributes>
                  <note><pitch><step>G</step><octave>4</octave></pitch><duration>4</duration></note>
                </measure>
                <measure number="1">
                  <note><pitch><step>C</step><octave>4</octave></pitch><duration>16</duration></note>
                </measure>
                """,
            ).toByteArray(),
        )

        assertThat(doc.measures.first().implicit).isTrue()
        assertThat(doc.measures.first().number).isEqualTo("0")
        // The pickup is one quarter long, so bar 1 starts at 4, not 16.
        assertThat(doc.measures[1].onset).isEqualTo(4)
    }

    @Test
    fun `reads fingering`() {
        val doc = MusicXmlParser.parse(
            score(
                """
                <measure number="1">
                  <attributes><divisions>4</divisions></attributes>
                  <note>
                    <pitch><step>C</step><octave>4</octave></pitch><duration>4</duration>
                    <notations><technical><fingering>1</fingering></technical></notations>
                  </note>
                </measure>
                """,
            ).toByteArray(),
        )

        assertThat(doc.hasFingering).isTrue()
        assertThat(doc.fingeringByOnsetPitch[0L to 60]).isEqualTo(1)
    }

    @Test
    fun `reads repeats and endings`() {
        val doc = MusicXmlParser.parse(
            score(
                """
                <measure number="1">
                  <attributes><divisions>4</divisions></attributes>
                  <barline location="left"><repeat direction="forward"/></barline>
                  <note><pitch><step>C</step><octave>4</octave></pitch><duration>16</duration></note>
                </measure>
                <measure number="2">
                  <barline location="left"><ending number="1" type="start"/></barline>
                  <note><pitch><step>D</step><octave>4</octave></pitch><duration>16</duration></note>
                  <barline location="right"><repeat direction="backward"/></barline>
                </measure>
                """,
            ).toByteArray(),
        )

        assertThat(doc.hasRepeats).isTrue()
        assertThat(doc.measures.first().repeatForward).isTrue()
        assertThat(doc.measures[1].repeatBackward).isTrue()
        assertThat(doc.measures[1].endingNumbers).isEqualTo("1")
    }

    @Test
    fun `tracks a mid score divisions change`() {
        val doc = MusicXmlParser.parse(
            score(
                """
                <measure number="1">
                  <attributes><divisions>4</divisions></attributes>
                  <note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration></note>
                </measure>
                <measure number="2">
                  <attributes><divisions>8</divisions></attributes>
                  <note><pitch><step>D</step><octave>4</octave></pitch><duration>8</duration></note>
                </measure>
                """,
            ).toByteArray(),
        )

        // Divisions can legitimately change part-way through; caching the first
        // value would misplace everything after the change.
        assertThat(doc.divisions).isEqualTo(8)
        assertThat(doc.measures).hasSize(2)
    }

    @Test
    fun `rests are parsed but excluded from the sounding sequence`() {
        val doc = MusicXmlParser.parse(
            score(
                """
                <measure number="1">
                  <attributes><divisions>4</divisions></attributes>
                  <note><rest/><duration>4</duration></note>
                  <note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration></note>
                </measure>
                """,
            ).toByteArray(),
        )

        assertThat(doc.notes.count { it.isRest }).isEqualTo(1)
        assertThat(doc.soundingNotes).hasSize(1)
        assertThat(doc.soundingNotes.single().onset).isEqualTo(4)
    }

    /**
     * Every MusicXML export names a DTD in its DOCTYPE, and none of them need it.
     *
     * A parser that goes looking for `musicxml.org` fails on a tablet that is
     * offline and hangs on one that is merely slow — and because the caller only
     * uses this for hand and fingering hints, the throw used to be swallowed
     * whole. The score then never reached the engraver and the pane rendered as
     * an unexplained black rectangle.
     *
     * There is no network here to fail against, so what this pins down is that
     * the document parses at all with the DOCTYPE present, on whichever SAX
     * implementation is underneath.
     */
    @Test
    fun `parses a document that declares the MusicXML DTD`() {
        val withDoctype = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN"
                "http://www.musicxml.org/dtds/partwise.dtd">
            <score-partwise version="4.0">
              <work><work-title>Doctyped</work-title></work>
              <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
              <part id="P1">
                <measure number="1">
                  <attributes><divisions>2</divisions></attributes>
                  <note><pitch><step>G</step><octave>4</octave></pitch><duration>2</duration></note>
                </measure>
              </part>
            </score-partwise>
        """.trimIndent()

        val doc = MusicXmlParser.parse(withDoctype.toByteArray())

        assertThat(doc.title).isEqualTo("Doctyped")
        assertThat(doc.soundingNotes.map { it.pitch }).containsExactly(67)
    }

    @Test
    fun `reads a tempo direction`() {
        val doc = MusicXmlParser.parse(
            score(
                """
                <measure number="1">
                  <attributes><divisions>4</divisions></attributes>
                  <direction><sound tempo="76"/></direction>
                  <note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration></note>
                </measure>
                """,
            ).toByteArray(),
        )

        assertThat(doc.tempoBpm).isWithin(0.01).of(76.0)
    }
}
