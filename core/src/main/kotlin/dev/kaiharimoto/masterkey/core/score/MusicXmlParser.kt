package dev.kaiharimoto.masterkey.core.score

import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Parses MusicXML into a [ScoreDocument].
 *
 * SAX rather than a library: `org.audiveris:proxymusic` is JAXB, which does not
 * exist on Android, and everything else in this space is abandoned. SAX ships in
 * both the JVM and Android runtimes, so the same parser runs on-device and in
 * fast unit tests.
 *
 * The subtleties handled here are the ones that silently corrupt timing if you
 * miss them:
 *
 *  - `<divisions>` is per-part and **can change mid-score**, so it is tracked
 *    rather than read once.
 *  - `<backup>` and `<forward>` move the time cursor within a measure. They are
 *    how multi-voice and grand-staff writing is encoded, and they mean document
 *    order is not time order.
 *  - `<chord/>` means "starts with the previous note", so the cursor must not
 *    advance for it.
 *  - Grace notes carry no duration and must not advance the cursor either.
 *  - `<measure number>` is a *string*: pickup bars are commonly `0` or carry
 *    `implicit="yes"`.
 */
object MusicXmlParser {

    fun parse(file: File): ScoreDocument = file.inputStream().use { stream ->
        if (file.extension.equals("mxl", ignoreCase = true)) {
            parseCompressed(stream)
        } else {
            parse(stream)
        }
    }

    fun parse(bytes: ByteArray): ScoreDocument =
        if (looksLikeZip(bytes)) {
            parseCompressed(ByteArrayInputStream(bytes))
        } else {
            parse(ByteArrayInputStream(bytes))
        }

    fun parse(stream: InputStream): ScoreDocument {
        val handler = Handler()
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            // Belt: ask the parser not to go looking for the DTD every MusicXML
            // file names in its DOCTYPE. These are Xerces feature names, so they
            // work on the JVM and are quietly rejected on Android, whose parser
            // is Expat-based — hence the braces, and hence the resolver below.
            runCatching {
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }

        val reader = factory.newSAXParser().xmlReader
        reader.contentHandler = handler
        // Braces: whatever the feature flags did or did not take, every external
        // entity now resolves to nothing. A parser that reaches for
        // musicxml.org over the network fails on a tablet that is offline, or
        // hangs on one that is merely slow — and the whole score is lost to a
        // reference the file does not actually need.
        reader.entityResolver = EntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
        reader.parse(InputSource(stream))
        return handler.build()
    }

    /**
     * Reads a score file as plain MusicXML text, for handing to a renderer.
     *
     * Both halves of this are auto-detection rather than trust in the filename.
     * A compressed score is recognised by its ZIP header, because plenty of them
     * are handed out named `.xml`; and the text is decoded by what the file
     * actually declares, because Finale and Sibelius both emit UTF-16 and
     * reading that as UTF-8 produces mojibake that the engraver rejects with
     * nothing more useful to say than "this score could not be read".
     */
    fun readXml(file: File): String = decodeXml(file.readBytes())

    /** As [readXml], for bytes already in hand. */
    fun decodeXml(bytes: ByteArray): String {
        val raw = if (looksLikeZip(bytes)) {
            readZippedScore(ByteArrayInputStream(bytes))
        } else {
            bytes
        }
        // The byte-order mark decodes to a real character, and a U+FEFF sitting
        // in front of the declaration makes every XML parser reject the prolog.
        return String(raw, charsetOf(raw)).removePrefix("\uFEFF")
    }

    /**
     * Works out how a score is encoded: byte-order mark first, then the XML
     * declaration, then UTF-8 as the standard's own default.
     */
    private fun charsetOf(bytes: ByteArray): Charset {
        fun startsWith(vararg prefix: Int): Boolean =
            bytes.size >= prefix.size && prefix.withIndex().all { (i, b) -> bytes[i] == b.toByte() }

        when {
            startsWith(0xEF, 0xBB, 0xBF) -> return Charsets.UTF_8
            startsWith(0xFE, 0xFF) -> return Charsets.UTF_16BE
            startsWith(0xFF, 0xFE) -> return Charsets.UTF_16LE
        }

        // No BOM. A UTF-16 file without one still gives itself away: every other
        // byte of `<?xml` is zero.
        if (bytes.size >= 4 && bytes[0] == 0x3C.toByte() && bytes[1] == 0x00.toByte()) {
            return Charsets.UTF_16LE
        }
        if (bytes.size >= 4 && bytes[0] == 0x00.toByte() && bytes[1] == 0x3C.toByte()) {
            return Charsets.UTF_16BE
        }

        val head = String(bytes.copyOfRange(0, minOf(bytes.size, 200)), Charsets.ISO_8859_1)
        val declared = Regex("encoding\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.get(1)
        return declared?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }
            ?: Charsets.UTF_8
    }

    /** `.mxl` is a ZIP; `META-INF/container.xml` names the real score inside. */
    private fun parseCompressed(stream: InputStream): ScoreDocument =
        parse(ByteArrayInputStream(readZippedScore(stream)))

    private fun readZippedScore(stream: InputStream): ByteArray {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val rootPath = entries["META-INF/container.xml"]
            ?.let { Regex("full-path\\s*=\\s*\"([^\"]+)\"").find(String(it))?.groupValues?.get(1) }

        return rootPath?.let { entries[it] }
            ?: entries.entries.firstOrNull { (name, _) ->
                !name.startsWith("META-INF") &&
                    (name.endsWith(".xml", true) || name.endsWith(".musicxml", true))
            }?.value
            ?: throw IllegalArgumentException("No score found inside this .mxl archive.")
    }

    private fun looksLikeZip(bytes: ByteArray) =
        bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    private class Handler : DefaultHandler() {

        private val notes = mutableListOf<ScoreNote>()
        private val measures = mutableListOf<ScoreMeasure>()
        private val timeSignatures = mutableListOf<ScoreTimeSignature>()

        private var divisions = 1
        private var title: String? = null
        private var composer: String? = null
        private var tempoBpm: Double? = null
        private var hasFingering = false
        private var maxStaff = 1
        private var fifths: Int? = null
        private var isMinor = false

        /** `<beats>` arrives before `<beat-type>`; the pair is banked on the second. */
        private var pendingBeats: Int? = null

        private val text = StringBuilder()
        private var capture = false

        // Where we are in time, in divisions from the start of the score.
        private var measureStart = 0L
        private var cursor = 0L
        private var maxCursorInMeasure = 0L
        private var measureIndex = -1
        private var measureNumber = ""
        private var measureImplicit = false
        private var repeatForward = false
        private var repeatBackward = false
        private var endingNumbers: String? = null

        // Current note being assembled.
        private var inNote = false
        private var step: String? = null
        private var alter = 0
        private var octave: Int? = null
        private var isRest = false
        private var isChord = false
        private var isGrace = false
        private var duration = 0L
        private var staff = 1
        private var voice = 1
        private var finger: Int? = null
        private var tieStart = false
        private var tieStop = false
        private var previousOnset = 0L
        private var previousDuration = 0L

        private var creatorType: String? = null

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            text.setLength(0)
            when (qName) {
                "work-title", "movement-title" -> capture = true
                "creator" -> {
                    creatorType = attrs.getValue("type")
                    capture = true
                }

                "divisions", "step", "alter", "octave", "duration", "staff", "voice",
                "fingering", "beats", "beat-type", "fifths", "mode",
                -> capture = true

                // Every part restarts at bar one. Without this the second part's
                // measures are appended *after* the first part's instead of
                // sounding alongside them, which turns a two-part score into the
                // same music played twice in a row.
                "part" -> {
                    measureStart = 0L
                    cursor = 0L
                    maxCursorInMeasure = 0L
                    measureIndex = -1
                    previousOnset = 0L
                }

                "measure" -> {
                    measureIndex++
                    measureNumber = attrs.getValue("number") ?: (measureIndex + 1).toString()
                    measureImplicit = attrs.getValue("implicit") == "yes"
                    measureStart += maxCursorInMeasure
                    cursor = 0
                    maxCursorInMeasure = 0
                    repeatForward = false
                    repeatBackward = false
                    endingNumbers = null
                }

                "note" -> {
                    inNote = true
                    step = null
                    alter = 0
                    octave = null
                    isRest = false
                    isChord = false
                    isGrace = false
                    duration = 0
                    finger = null
                    tieStart = false
                    tieStop = false
                    staff = 1
                    voice = 1
                }

                "rest" -> isRest = true
                "chord" -> isChord = true
                "grace" -> isGrace = true

                "tie" -> when (attrs.getValue("type")) {
                    "start" -> tieStart = true
                    "stop" -> tieStop = true
                }

                "repeat" -> when (attrs.getValue("direction")) {
                    "forward" -> repeatForward = true
                    "backward" -> repeatBackward = true
                }

                "ending" -> endingNumbers = attrs.getValue("number")

                "sound" -> attrs.getValue("tempo")?.toDoubleOrNull()?.let {
                    if (tempoBpm == null) tempoBpm = it
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (capture) text.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val value = text.toString().trim()
            when (qName) {
                "work-title", "movement-title" -> if (title.isNullOrBlank()) title = value
                "creator" -> if (creatorType == "composer" && composer.isNullOrBlank()) {
                    composer = value
                }

                "divisions" -> value.toIntOrNull()?.let { if (it > 0) divisions = it }
                "beats" -> pendingBeats = value.toIntOrNull()
                "beat-type" -> {
                    val numerator = pendingBeats
                    val denominator = value.toIntOrNull()
                    pendingBeats = null
                    if (numerator != null && denominator != null && numerator > 0 && denominator > 0) {
                        // A multi-part score repeats the same <time> in every
                        // part, so the same change arrives once per part.
                        val last = timeSignatures.lastOrNull()
                        val duplicate = timeSignatures.any { it.onset == measureStart } ||
                            (last != null && last.numerator == numerator && last.denominator == denominator)
                        if (!duplicate) {
                            timeSignatures += ScoreTimeSignature(measureStart, numerator, denominator)
                        }
                    }
                }

                "fifths" -> if (fifths == null) fifths = value.toIntOrNull()
                "mode" -> if (value.equals("minor", ignoreCase = true)) isMinor = true
                "step" -> step = value
                "alter" -> alter = value.toDoubleOrNull()?.toInt() ?: 0
                "octave" -> octave = value.toIntOrNull()
                "duration" -> duration = value.toLongOrNull() ?: 0L
                "staff" -> staff = value.toIntOrNull()?.also { maxStaff = maxOf(maxStaff, it) } ?: 1
                "voice" -> voice = value.toIntOrNull() ?: 1
                "fingering" -> value.toIntOrNull()?.let {
                    finger = it
                    hasFingering = true
                }

                "note" -> finishNote()

                // backup/forward move the time cursor; they are how grand staff and
                // multiple voices are encoded, so ignoring them scrambles onsets.
                "backup" -> {
                    cursor = (cursor - lastDuration()).coerceAtLeast(0L)
                }

                "forward" -> {
                    cursor += lastDuration()
                    maxCursorInMeasure = maxOf(maxCursorInMeasure, cursor)
                }

                "measure" -> {
                    measures += ScoreMeasure(
                        index = measureIndex,
                        number = measureNumber,
                        onset = measureStart,
                        duration = maxCursorInMeasure,
                        implicit = measureImplicit,
                        repeatForward = repeatForward,
                        repeatBackward = repeatBackward,
                        endingNumbers = endingNumbers,
                    )
                }
            }
            capture = false
            text.setLength(0)
        }

        /** `<backup>`/`<forward>` carry their own `<duration>` child. */
        private fun lastDuration(): Long = duration.also { duration = 0 }

        private fun finishNote() {
            inNote = false

            val pitch = if (isRest || step == null || octave == null) {
                null
            } else {
                midiPitch(step!!, alter, octave!!)
            }

            // A chord member shares the previous note's onset; a grace note has no
            // duration of its own. In both cases the cursor must not advance.
            val onset = if (isChord) previousOnset else measureStart + cursor

            notes += ScoreNote(
                pitch = pitch,
                onset = onset,
                duration = duration,
                staff = staff,
                voice = voice,
                measureIndex = measureIndex,
                measureNumber = measureNumber,
                isChordMember = isChord,
                isGrace = isGrace,
                tieStart = tieStart,
                tieStop = tieStop,
                finger = finger,
            )

            if (!isChord && !isGrace) {
                previousOnset = onset
                previousDuration = duration
                cursor += duration
                maxCursorInMeasure = maxOf(maxCursorInMeasure, cursor)
            }
            duration = 0
        }

        private fun midiPitch(step: String, alter: Int, octave: Int): Int {
            val base = when (step.uppercase()) {
                "C" -> 0; "D" -> 2; "E" -> 4; "F" -> 5
                "G" -> 7; "A" -> 9; "B" -> 11
                else -> 0
            }
            return ((octave + 1) * 12 + base + alter).coerceIn(0, 127)
        }

        fun build() = ScoreDocument(
            title = title?.takeIf { it.isNotBlank() },
            composer = composer?.takeIf { it.isNotBlank() },
            divisions = divisions,
            notes = notes,
            // Parts restart at bar one, so a multi-part score describes the same
            // bars once per part; the first description of each is enough.
            measures = measures.distinctBy { it.index },
            tempoBpm = tempoBpm,
            hasFingering = hasFingering,
            hasTwoStaves = maxStaff >= 2,
            timeSignatures = timeSignatures.sortedBy { it.onset },
            fifths = fifths,
            isMinor = isMinor,
        )
    }
}
