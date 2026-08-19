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
import dev.kaiharimoto.masterkey.core.model.Piece
import dev.kaiharimoto.masterkey.core.score.MusicXmlParser
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
        LibraryScanner.write(folderFor(song), song.toManifest())
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

        if (midis.isEmpty()) {
            return@withContext ImportResult.Failure(
                if (scores.isEmpty()) {
                    "Pick a MIDI file (.mid). You can select its MusicXML score at the " +
                        "same time and they'll be paired automatically."
                } else {
                    "That's a score with no MIDI. Select the .mid file too."
                },
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

    /** Parses the stored MIDI into a playable piece. */
    suspend fun loadPiece(song: SongEntity): Result<Piece> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(File(libraryRoot, song.folder), song.midiFileName)
            MidiLoader.load(file.readBytes())
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

        runCatching {
            if (name.endsWith(".mxl", ignoreCase = true)) {
                MusicXmlParser.extractXml(file)
            } else {
                file.readText()
            }
        }.onFailure { Log.w(TAG, "couldn't read $name", it) }
    }

    fun scoreFile(song: SongEntity): File? {
        val name = song.scoreFileName ?: return null
        return File(File(libraryRoot, song.folder), name).takeIf { it.exists() }
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
            val midi = found.midiFile ?: continue

            val piece = runCatching { MidiLoader.load(midi.readBytes()) }.getOrNull() ?: continue
            val manifest = found.manifest
            val range = piece.pitchRange
            val settings = manifest?.settings ?: SongSettingsManifest()

            songDao.upsert(
                SongEntity(
                    id = manifest?.id ?: found.folder.name,
                    title = manifest?.title
                        ?: LibraryScanner.titleFromFileName(
                            manifest?.originalMidiName ?: midi.name,
                        ),
                    composer = manifest?.composer,
                    folder = found.folder.name,
                    midiFileName = midi.name,
                    scoreFileName = found.scoreFile?.name,
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
    ),
)

/** Suggested key range for a song, honouring any manual pin. */
fun SongEntity.preferredRange() =
    if (pinnedRangeLow != null && pinnedRangeHigh != null) {
        KeyRangeSelector.forPitchesClamped(listOf(pinnedRangeLow, pinnedRangeHigh))
    } else {
        KeyRangeSelector.forPitchesClamped(listOf(pitchLow, pitchHigh))
    }
