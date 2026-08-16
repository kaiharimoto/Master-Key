package dev.kaiharimoto.masterkey.core.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Per-song settings, mirrored into the manifest so they survive losing the database.
 *
 * Kept as plain strings/numbers rather than the UI enums so that adding or
 * renaming an enum case can never make an old manifest unreadable — the whole
 * point of this file is to be readable by a *future* version of the app.
 */
@Serializable
data class SongSettingsManifest(
    val tempoScale: Float = 0.9f,
    val scaffoldLevel: String = "MINIMAL",
    val noteNameStyle: String = "OFF",
    val colorMode: String = "BY_HAND",
    val pinnedRangeLow: Int? = null,
    val pinnedRangeHigh: Int? = null,
    val rightHandMuted: Boolean = false,
    val leftHandMuted: Boolean = false,
    val metronomeEnabled: Boolean = false,
    val countInBars: Int = 1,
    val showScore: Boolean = true,
    val lookAheadBeats: Float = 8f,
)

/**
 * Everything needed to reconstruct a library entry from its folder alone.
 *
 * The database is an index over these folders, not the source of truth. That
 * inverts the previous arrangement, where wiping the database orphaned the audio
 * files permanently because nothing else recorded what they were.
 */
@Serializable
data class SongManifest(
    val id: String,
    val title: String,
    val composer: String? = null,
    val midiFileName: String,
    val scoreFileName: String? = null,
    /** What the user's file was called, for re-deriving a title if needed. */
    val originalMidiName: String? = null,
    val originalScoreName: String? = null,
    val importedAt: Long = 0L,
    val settings: SongSettingsManifest = SongSettingsManifest(),
    /** Schema version of this file, so a future format change stays readable. */
    val manifestVersion: Int = 1,
)

/** A song folder found on disk, whether or not it had a readable manifest. */
data class DiscoveredSong(
    val folder: File,
    val manifest: SongManifest?,
    val midiFile: File?,
    val scoreFile: File?,
) {
    /** Usable only if there is actually a MIDI file to play. */
    val isUsable: Boolean get() = midiFile != null
}

/**
 * Reads and writes song folders under `filesDir/library`.
 *
 * Lives in `:core` with no Android dependency so the scan and the manifest
 * round-trip can be unit-tested against a temp directory instead of a device.
 */
object LibraryScanner {

    const val MANIFEST_NAME = "song.json"

    private val json = Json {
        prettyPrint = true
        // A manifest written by a newer build must not break an older one, and
        // vice versa — unknown keys are skipped rather than throwing.
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val MIDI_EXTENSIONS = setOf("mid", "midi", "smf")
    private val SCORE_EXTENSIONS = setOf("musicxml", "mxl", "xml")

    fun write(folder: File, manifest: SongManifest): Boolean = runCatching {
        folder.mkdirs()
        File(folder, MANIFEST_NAME).writeText(json.encodeToString(manifest))
        true
    }.getOrDefault(false)

    fun read(folder: File): SongManifest? = runCatching {
        val file = File(folder, MANIFEST_NAME)
        if (!file.exists()) return null
        json.decodeFromString<SongManifest>(file.readText())
    }.getOrNull()

    /**
     * Finds every song folder under [root].
     *
     * A folder with no manifest is still reported when it contains a MIDI file,
     * so libraries imported before manifests existed can still be recovered —
     * the caller re-derives the metadata by parsing the MIDI.
     */
    fun scan(root: File): List<DiscoveredSong> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?.map { folder ->
                val manifest = read(folder)
                val files = folder.listFiles()?.filter { it.isFile }.orEmpty()

                val midi = manifest?.midiFileName
                    ?.let { name -> files.firstOrNull { it.name == name } }
                    ?: files.firstOrNull { it.extension.lowercase() in MIDI_EXTENSIONS }

                val score = manifest?.scoreFileName
                    ?.let { name -> files.firstOrNull { it.name == name } }
                    ?: files.firstOrNull { it.extension.lowercase() in SCORE_EXTENSIONS }

                DiscoveredSong(folder, manifest, midi, score)
            }
            ?.filter { it.isUsable }
            .orEmpty()
    }

    /** Turns a filename into a readable title, for folders with no manifest. */
    fun titleFromFileName(fileName: String): String =
        fileName.substringBeforeLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(' ')
            .joinToString(" ") { word ->
                if (word.length <= 2 && word.all { it.isUpperCase() }) {
                    word
                } else {
                    word.replaceFirstChar { it.uppercase() }
                }
            }
            .ifBlank { "Untitled" }
}
