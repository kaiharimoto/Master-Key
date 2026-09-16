package dev.kaiharimoto.masterkey.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dev.kaiharimoto.masterkey.core.keyboard.KeyRangeSelector
import dev.kaiharimoto.masterkey.core.library.LibraryScanner
import dev.kaiharimoto.masterkey.core.library.SongManifest
import dev.kaiharimoto.masterkey.core.library.SongSettingsManifest
import dev.kaiharimoto.masterkey.core.midi.MidiLoader
import dev.kaiharimoto.masterkey.core.midi.MidiParseException
import dev.kaiharimoto.masterkey.core.midi.MidiWriter
import dev.kaiharimoto.masterkey.core.model.Piece
import dev.kaiharimoto.masterkey.core.score.MusicXmlParser
import dev.kaiharimoto.masterkey.core.score.toPiece
import dev.kaiharimoto.masterkey.core.score.ScoreDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

/** What happened when the user picked files. */
sealed interface ImportResult {
    data class Success(val songs: List<SongEntity>, val pairedScores: Int) : ImportResult
    data class Failure(val message: String) : ImportResult
}

/**
 * Owns the song library: importing, storing and loading.
 *
 * Files picked through the Storage Access Framework are **copied** into app
 * storage rather than referenced by URI. A URI permission can be revoked, the
 * source file can be moved or deleted, and a song that silently stops opening a
 * month later is far worse than the few megabytes a copy costs.
 */
class SongRepository(
    private val context: Context,
    private val songDao: SongDao,
) {
    private val libraryRoot: File
        get() = File(context.filesDir, "library").apply { mkdirs() }

    fun observeSongs(): Flow<List<SongEntity>> = songDao.observeAll()

    fun observeSong(id: String): Flow<SongEntity?> = songDao.observe(id)

    suspend fun find(id: String): SongEntity? = songDao.find(id)

    /**
     * Persists a song, mirroring its settings into the on-disk manifest.
     *
     * The manifest is what makes the database rebuildable, so it has to stay
     * current — otherwise a recovery would restore the music but reset how you
     * had set the piece up.
     */
    suspend fun update(song: SongEntity) = withContext(Dispatchers.IO) {
        songDao.update(song)
        val folder = folderFor(song)
        // The original filenames are only known at import time, and
        // `toManifest()` defaults them to null — so rewriting the manifest from
        // a row alone used to erase them on every settings change, quietly
        // undoing the one thing that lets a recovered library re-derive a title.
        // Carrying them forward from whatever is already on disk costs one read.
        val existing = LibraryScanner.read(folder)
        LibraryScanner.write(
            folder,
            song.toManifest(
                originalMidiName = existing?.originalMidiName,
                originalScoreName = existing?.originalScoreName,
            ),
        )
    }

    suspend fun touch(id: String) = songDao.touch(id)

    /**
     * Imports whatever the user selected.
     *
     * Multi-select is the happy path: picking `Fur_Elise.mid` and
     * `Fur_Elise.musicxml` together pairs them automatically by filename, so the
     * common case needs no extra step. Scores can also be attached later.
     */
    suspend fun import(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext ImportResult.Failure("Nothing selected.")

        val named = uris.mapNotNull { uri ->
            displayName(uri)?.let { name -> uri to name }
        }
        if (named.isEmpty()) {
            return@withContext ImportResult.Failure("Couldn't read the selected files.")
        }

        val midis = named.filter { isMidi(it.second) }
        val scores = named.filter { isScore(it.second) }

        if (midis.isEmpty() && scores.isEmpty()) {
            return@withContext ImportResult.Failure(
                "Pick a MIDI file (.mid) or a MusicXML score (.musicxml, .mxl). " +
                    "Select both at once and they'll be paired automatically.",
            )
        }

        val imported = mutableListOf<SongEntity>()
        var paired = 0
        val unusedScores = scores.toMutableList()

        for ((midiUri, midiName) in midis) {
            val match = unusedScores.firstOrNull {
                baseName(it.second).equals(baseName(midiName), ignoreCase = true)
            } ?: unusedScores.takeIf { midis.size == 1 && it.size == 1 }?.firstOrNull()

            val result = runCatching {
                importOne(midiUri, midiName, match?.first, match?.second)
            }
            result.getOrNull()?.let { song ->
                imported += song
                if (match != null) {
                    paired++
                    unusedScores.remove(match)
                }
            } ?: return@withContext ImportResult.Failure(
                result.exceptionOrNull()?.message ?: "Couldn't import $midiName.",
            )
        }

        // Whatever is left is a score that paired with nothing. It used to be
        // dropped in silence, which is the worst of both worlds: the import
        // reported success and the sheet music pane then had nothing to show and
        // no reason to give. A MusicXML says everything a MIDI does about which
        // notes sound when, so it becomes a song in its own right.
        for ((scoreUri, scoreName) in unusedScores) {
            val result = runCatching { importScoreOnly(scoreUri, scoreName) }
            result.getOrNull()?.let { song ->
                imported += song
                paired++
            } ?: return@withContext ImportResult.Failure(
                result.exceptionOrNull()?.message ?: "Couldn't import $scoreName.",
            )
        }

        ImportResult.Success(imported, paired)
    }

    /** Attaches (or replaces) the score for an existing song. */
    suspend fun attachScore(songId: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val song = songDao.find(songId) ?: return@withContext false
        val name = displayName(uri) ?: return@withContext false
        if (!isScore(name)) return@withContext false

        val folder = File(libraryRoot, song.folder).apply { mkdirs() }
        val target = File(folder, "score.${extension(name)}")
        copy(uri, target) || return@withContext false

        songDao.update(song.copy(scoreFileName = target.name))
        true
    }

    suspend fun delete(song: SongEntity) = withContext(Dispatchers.IO) {
        File(libraryRoot, song.folder).deleteRecursively()
        songDao.delete(song)
    }

    /**
     * Parses the song into a playable piece.
     *
     * Usually that means the MIDI. A song imported from a MusicXML alone has no
     * MIDI to parse, and its notation is the score of record, so the piece is
     * derived from the same file the engraver is given.
     */
    suspend fun loadPiece(song: SongEntity): Result<Piece> = withContext(Dispatchers.IO) {
        runCatching {
            val folder = File(libraryRoot, song.folder)
            val midi = File(folder, song.midiFileName)
            if (song.midiFileName != SongEntity.NO_MIDI && midi.exists()) {
                MidiLoader.load(midi.readBytes())
            } else {
                val name = song.scoreFileName
                    ?: throw IllegalStateException("This song has no MIDI and no sheet music.")
                MusicXmlParser.parse(File(folder, name).readBytes()).toPiece()
            }
        }
    }

    /**
     * Parses the linked MusicXML, if there is one.
     *
     * Only ever used to *enrich* the MIDI with staff and fingering. Failing here
     * costs those two annotations and nothing else — in particular it must not
     * stop the score being engraved, which needs the raw XML and not this.
     */
    suspend fun loadScore(song: SongEntity): ScoreDocument? = withContext(Dispatchers.IO) {
        val name = song.scoreFileName ?: return@withContext null
        val file = File(File(libraryRoot, song.folder), name)
        if (!file.exists()) return@withContext null
        runCatching { MusicXmlParser.parse(file) }
            .onFailure { Log.w(TAG, "couldn't parse $name for hand/fingering data", it) }
            .getOrNull()
    }

    /**
     * The score as an XML string for the engraver.
     *
     * `.mxl` is a ZIP, so it is unpacked here rather than handed over compressed —
     * the renderer receives plain MusicXML either way and does not need to care
     * which form the user supplied.
     */
    suspend fun scoreXml(song: SongEntity): Result<String> = withContext(Dispatchers.IO) {
        val name = song.scoreFileName
            ?: return@withContext Result.failure(IllegalStateException("No sheet music is linked."))
        val file = File(File(libraryRoot, song.folder), name)
        if (!file.exists()) {
            return@withContext Result.failure(IllegalStateException("$name is missing from the library."))
        }

        // Detected from the bytes, not the extension: a compressed score named
        // .xml still opens, and a UTF-16 export decodes as text rather than as
        // mojibake the engraver silently refuses.
        runCatching { MusicXmlParser.readXml(file) }
            .onFailure { Log.w(TAG, "couldn't read $name", it) }
    }

    fun scoreFile(song: SongEntity): File? {
        val name = song.scoreFileName ?: return null
        return File(File(libraryRoot, song.folder), name).takeIf { it.exists() }
    }

    // ---- editing the notes ----------------------------------------------------
    //
    // Saving an edit rewrites the song's MIDI. That is a deliberate choice: it
    // keeps one source of truth, so the highway, playback and the
    // rebuild-from-disk recovery path can never disagree about what the notes
    // are. The cost is that a rewritten file carries only what Master Key
    // parses — notes, tempo, metre, key — and loses sustain pedal, other
    // instruments and every kind of text the original may have had.
    //
    // So the imported file is stashed before the first write and never deleted.
    // It goes in a *subdirectory* rather than beside the song, because
    // `LibraryScanner.scan` resolves a folder's MIDI by manifest name and falls
    // back to "the first file with a MIDI extension" — a sibling backup would be
    // a live candidate for that fallback, with directory listing order deciding
    // which file a recovered library played. A subdirectory is invisible to the
    // scanner, which only considers plain files, and `delete()` already removes
    // the folder recursively.

    /** True when this song still has the file it was imported from. */
    fun hasImportedBackup(song: SongEntity): Boolean = importedBackup(song)?.exists() == true

    /**
     * Writes [piece] over the song's MIDI, backing up the import the first time.
     *
     * Returns the song with its cached analysis refreshed — note count, duration,
     * bar count and pitch range all change with the notes, and they are what the
     * library list and the initial key range are drawn from.
     */
    suspend fun saveEditedPiece(
        song: SongEntity,
        piece: Piece,
    ): Result<SongEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val folder = folderFor(song).apply { mkdirs() }
            val targetName = song.midiFileName.takeIf { it != SongEntity.NO_MIDI }
                ?: EDITED_MIDI_NAME
            val target = File(folder, targetName)

            // Encode before touching anything on disk, so a piece the writer
            // refuses leaves the library exactly as it was.
            val bytes = MidiWriter.write(piece)

            if (target.exists() && !hasImportedBackup(song)) {
                val backupDir = File(folder, ORIGINAL_DIR).apply { mkdirs() }
                target.copyTo(File(backupDir, targetName), overwrite = true)
            }

            // Write beside, then rename. A crash mid-write must not leave half a
            // MIDI where the song used to be; a rename within one directory is
            // atomic, a partial overwrite is not.
            val temp = File(folder, "$targetName.writing")
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IllegalStateException("Couldn't replace ${target.name}.")
            }

            val updated = song.copy(midiFileName = targetName).withAnalysisOf(piece)
            update(updated)
            updated
        }.onFailure { Log.w(TAG, "couldn't save edits for ${song.title}", it) }
    }

    /**
     * Puts the imported file back.
     *
     * The backup is kept rather than consumed, so reverting twice is harmless and
     * a song can be edited again afterwards.
     */
    suspend fun revertToImported(song: SongEntity): Result<SongEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val folder = folderFor(song)
            val backup = importedBackup(song)

            if (backup == null || !backup.exists()) {
                // No backup means the song never had a MIDI: it was imported from
                // a MusicXML alone and saving created one. Reverting is deleting
                // that file and letting `loadPiece` fall back to the score again.
                val created = File(folder, song.midiFileName)
                if (song.midiFileName == SongEntity.NO_MIDI || song.scoreFileName == null) {
                    throw IllegalStateException("There's no imported version of this song to go back to.")
                }
                created.delete()
                val piece = MusicXmlParser.parse(File(folder, song.scoreFileName).readBytes()).toPiece()
                val restored = song.copy(midiFileName = SongEntity.NO_MIDI).withAnalysisOf(piece)
                update(restored)
                return@runCatching restored
            }

            backup.copyTo(File(folder, backup.name), overwrite = true)
            val piece = MidiLoader.load(File(folder, backup.name).readBytes())
            val restored = song.copy(midiFileName = backup.name).withAnalysisOf(piece)
            update(restored)
            restored
        }.onFailure { Log.w(TAG, "couldn't revert ${song.title}", it) }
    }

    private fun importedBackup(song: SongEntity): File? {
        val dir = File(folderFor(song), ORIGINAL_DIR)
        if (!dir.isDirectory) return null
        return dir.listFiles()?.firstOrNull { it.isFile }
    }

    /** Refreshes the cached fields that change when the notes do. */
    private fun SongEntity.withAnalysisOf(piece: Piece): SongEntity {
        val range = piece.pitchRange
        return copy(
            durationMicros = piece.durationMicros,
            barCount = countBars(piece),
            noteCount = piece.notes.size,
            pitchLow = range?.first ?: 21,
            pitchHigh = range?.last ?: 108,
        )
    }

    /**
     * Imports a MusicXML with no MIDI beside it.
     *
     * The score is both halves at once: it is engraved as notation *and* parsed
     * into the piece that gets played, so the two can never disagree about the
     * music. It also carries the hand split and the fingering explicitly, which
     * the MIDI path has to guess at.
     */
    private suspend fun importScoreOnly(scoreUri: Uri, scoreName: String): SongEntity {
        val id = UUID.randomUUID().toString()
        val folder = File(libraryRoot, id).apply { mkdirs() }

        val scoreTarget = File(folder, "score.${extension(scoreName)}")
        if (!copy(scoreUri, scoreTarget)) {
            folder.deleteRecursively()
            throw IllegalStateException("Couldn't read $scoreName.")
        }

        val document = try {
            MusicXmlParser.parse(scoreTarget.readBytes())
        } catch (e: Exception) {
            folder.deleteRecursively()
            throw IllegalStateException(describeScoreProblem(scoreName, scoreTarget, e))
        }

        val piece = document.toPiece()
        if (piece.notes.isEmpty()) {
            folder.deleteRecursively()
            throw IllegalStateException("$scoreName has no notes in it.")
        }

        val range = piece.pitchRange
        val song = SongEntity(
            id = id,
            title = document.title?.takeIf { it.isNotBlank() } ?: prettyTitle(scoreName),
            composer = document.composer,
            folder = id,
            midiFileName = SongEntity.NO_MIDI,
            scoreFileName = scoreTarget.name,
            durationMicros = piece.durationMicros,
            barCount = countBars(piece),
            noteCount = piece.notes.size,
            pitchLow = range?.first ?: 21,
            pitchHigh = range?.last ?: 108,
            keySignature = piece.keySignatureAt(0)?.displayName,
            timeSignature = piece.timeSignatureAt(0).toString(),
            originalBpm = piece.tempoMap.bpmAt(0),
        )
        songDao.upsert(song)
        LibraryScanner.write(folder, song.toManifest(originalScoreName = scoreName))
        return song
    }

    /**
     * Says what is actually wrong with a score, rather than "couldn't be read".
     *
     * `score-timewise` is the case worth naming: it is valid MusicXML, both our
     * parser and Verovio only handle `score-partwise`, and the two look
     * identical to anyone who has not read the spec.
     */
    private fun describeScoreProblem(name: String, file: File, error: Throwable): String {
        val head = runCatching {
            MusicXmlParser.readXml(file).take(2000)
        }.getOrDefault("")
        return when {
            head.contains("score-timewise") ->
                "$name is a timewise MusicXML, which this app can't read. " +
                    "Most notation apps can export the partwise form instead."

            else -> "$name couldn't be read.\n${error.message ?: error::class.simpleName.orEmpty()}"
        }
    }

    private suspend fun importOne(
        midiUri: Uri,
        midiName: String,
        scoreUri: Uri?,
        scoreName: String?,
    ): SongEntity {
        val id = UUID.randomUUID().toString()
        val folder = File(libraryRoot, id).apply { mkdirs() }

        val midiTarget = File(folder, "source.${extension(midiName)}")
        if (!copy(midiUri, midiTarget)) {
            folder.deleteRecursively()
            throw IllegalStateException("Couldn't read $midiName.")
        }

        val piece = try {
            MidiLoader.load(midiTarget.readBytes())
        } catch (e: MidiParseException) {
            folder.deleteRecursively()
            throw IllegalStateException(e.message ?: "That MIDI file couldn't be read.")
        }

        var scoreFileName: String? = null
        if (scoreUri != null && scoreName != null) {
            val scoreTarget = File(folder, "score.${extension(scoreName)}")
            if (copy(scoreUri, scoreTarget)) scoreFileName = scoreTarget.name
        }

        val range = piece.pitchRange
        val song = SongEntity(
            id = id,
            title = prettyTitle(midiName),
            folder = id,
            midiFileName = midiTarget.name,
            scoreFileName = scoreFileName,
            durationMicros = piece.durationMicros,
            barCount = countBars(piece),
            noteCount = piece.notes.size,
            pitchLow = range?.first ?: 21,
            pitchHigh = range?.last ?: 108,
            keySignature = piece.keySignatureAt(0)?.displayName,
            timeSignature = piece.timeSignatureAt(0).toString(),
            originalBpm = piece.tempoMap.bpmAt(0),
        )
        songDao.upsert(song)
        LibraryScanner.write(
            folder,
            song.toManifest(originalMidiName = midiName, originalScoreName = scoreName),
        )
        return song
    }

    /**
     * Rebuilds the database index by rescanning the song folders on disk.
     *
     * The files are the source of truth; the database is a cache over them. This
     * is what makes a database reset recoverable rather than destructive, and it
     * is also how a restore onto a new device repopulates itself — the backup
     * carries the music, not the fragile index.
     *
     * Existing rows are left alone, so this is safe to run at any time.
     */
    suspend fun rebuildFromDisk(): Int = withContext(Dispatchers.IO) {
        val known = songDao.all().map { it.folder }.toSet()
        var recovered = 0

        for (found in LibraryScanner.scan(libraryRoot)) {
            if (found.folder.name in known) continue

            // Same two sources as an import, and in the same order: the MIDI
            // when there is one, otherwise the score, which carries the notes
            // too. A folder with neither is not reported by the scanner.
            val midi = found.midiFile
            val score = found.scoreFile
            val piece = when {
                midi != null -> runCatching { MidiLoader.load(midi.readBytes()) }.getOrNull()
                score != null ->
                    runCatching { MusicXmlParser.parse(score.readBytes()).toPiece() }.getOrNull()
                else -> null
            } ?: continue
            val manifest = found.manifest
            val range = piece.pitchRange
            val settings = manifest?.settings ?: SongSettingsManifest()

            songDao.upsert(
                SongEntity(
                    id = manifest?.id ?: found.folder.name,
                    title = manifest?.title
                        ?: LibraryScanner.titleFromFileName(
                            manifest?.originalMidiName
                                ?: manifest?.originalScoreName
                                ?: midi?.name
                                ?: score?.name.orEmpty(),
                        ),
                    composer = manifest?.composer,
                    folder = found.folder.name,
                    midiFileName = midi?.name ?: SongEntity.NO_MIDI,
                    scoreFileName = score?.name,
                    durationMicros = piece.durationMicros,
                    barCount = countBars(piece),
                    noteCount = piece.notes.size,
                    pitchLow = range?.first ?: 21,
                    pitchHigh = range?.last ?: 108,
                    keySignature = piece.keySignatureAt(0)?.displayName,
                    timeSignature = piece.timeSignatureAt(0).toString(),
                    originalBpm = piece.tempoMap.bpmAt(0),
                    importedAt = manifest?.importedAt?.takeIf { it > 0 }
                        ?: System.currentTimeMillis(),
                    tempoScale = settings.tempoScale,
                    scaffoldLevel = settings.scaffoldLevel,
                    noteNameStyle = settings.noteNameStyle,
                    colorMode = settings.colorMode,
                    pinnedRangeLow = settings.pinnedRangeLow,
                    pinnedRangeHigh = settings.pinnedRangeHigh,
                    rightHandMuted = settings.rightHandMuted,
                    leftHandMuted = settings.leftHandMuted,
                    metronomeEnabled = settings.metronomeEnabled,
                    countInBars = settings.countInBars,
                    showScore = settings.showScore,
                    lookAheadBeats = settings.lookAheadBeats,
                    startOffsetTicks = settings.startOffsetTicks,
                ),
            )
            recovered++
        }
        recovered
    }

    /** True when there are song folders on disk that the database doesn't know about. */
    suspend fun hasUnindexedSongs(): Boolean = withContext(Dispatchers.IO) {
        val known = songDao.all().map { it.folder }.toSet()
        LibraryScanner.scan(libraryRoot).any { it.folder.name !in known }
    }

    private fun folderFor(song: SongEntity) = File(libraryRoot, song.folder)

    private fun countBars(piece: Piece): Int {
        val signature = piece.timeSignatureAt(0)
        val ticksPerBar = signature.ticksPerBar(piece.tempoMap.ticksPerQuarter).coerceAtLeast(1L)
        return ((piece.endTick + ticksPerBar - 1) / ticksPerBar).toInt().coerceAtLeast(1)
    }

    private fun copy(uri: Uri, target: File): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } != null
    }.getOrDefault(false)

    private fun displayName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun extension(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.US).ifEmpty { "dat" }

    private fun baseName(name: String): String = name.substringBeforeLast('.')

    private fun isMidi(name: String) = extension(name) in setOf("mid", "midi", "smf")

    private fun isScore(name: String) = extension(name) in setOf("musicxml", "mxl", "xml")

    private companion object {
        const val TAG = "MasterKeyLibrary"

        /** Subdirectory holding the file a song was imported from. */
        const val ORIGINAL_DIR = "original"

        /** Filename for the MIDI created when a score-only song is first edited. */
        const val EDITED_MIDI_NAME = "source.mid"
    }

    /**
     * Turns `Fur_Elise-Beethoven.mid` into `Fur Elise Beethoven`.
     *
     * Filenames from score sites are full of underscores and dashes; a library
     * that displays them raw looks like a file manager rather than a music app.
     */
    private fun prettyTitle(fileName: String): String =
        baseName(fileName)
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(' ')
            .joinToString(" ") { word ->
                if (word.length <= 2 && word.all { it.isUpperCase() }) word
                else word.replaceFirstChar { it.uppercase(Locale.US) }
            }
            .ifBlank { "Untitled" }
}

/** Projects a row into the manifest stored beside its files. */
fun SongEntity.toManifest(
    originalMidiName: String? = null,
    originalScoreName: String? = null,
) = SongManifest(
    id = id,
    title = title,
    composer = composer,
    midiFileName = midiFileName,
    scoreFileName = scoreFileName,
    originalMidiName = originalMidiName,
    originalScoreName = originalScoreName,
    importedAt = importedAt,
    settings = SongSettingsManifest(
        tempoScale = tempoScale,
        scaffoldLevel = scaffoldLevel,
        noteNameStyle = noteNameStyle,
        colorMode = colorMode,
        pinnedRangeLow = pinnedRangeLow,
        pinnedRangeHigh = pinnedRangeHigh,
        rightHandMuted = rightHandMuted,
        leftHandMuted = leftHandMuted,
        metronomeEnabled = metronomeEnabled,
        countInBars = countInBars,
        showScore = showScore,
        lookAheadBeats = lookAheadBeats,
        startOffsetTicks = startOffsetTicks,
    ),
)

/** Suggested key range for a song, honouring any manual pin. */
fun SongEntity.preferredRange() =
    if (pinnedRangeLow != null && pinnedRangeHigh != null) {
        KeyRangeSelector.forPitchesClamped(listOf(pinnedRangeLow, pinnedRangeHigh))
    } else {
        KeyRangeSelector.forPitchesClamped(listOf(pitchLow, pitchHigh))
    }
