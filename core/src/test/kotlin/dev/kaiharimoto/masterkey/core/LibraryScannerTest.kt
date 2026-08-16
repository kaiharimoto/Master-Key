package dev.kaiharimoto.masterkey.core

import com.google.common.truth.Truth.assertThat
import dev.kaiharimoto.masterkey.core.library.LibraryScanner
import dev.kaiharimoto.masterkey.core.library.SongManifest
import dev.kaiharimoto.masterkey.core.library.SongSettingsManifest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryScannerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun songFolder(
        id: String,
        midi: String? = "source.mid",
        score: String? = null,
        manifest: SongManifest? = null,
    ): File {
        val folder = temp.newFolder(id)
        midi?.let { File(folder, it).writeBytes(byteArrayOf(1, 2, 3)) }
        score?.let { File(folder, it).writeText("<score/>") }
        manifest?.let { LibraryScanner.write(folder, it) }
        return folder
    }

    @Test
    fun `manifest round trips`() {
        val folder = temp.newFolder("song-1")
        val original = SongManifest(
            id = "song-1",
            title = "Für Elise",
            composer = "Beethoven",
            midiFileName = "source.mid",
            scoreFileName = "score.musicxml",
            originalMidiName = "Fur_Elise.mid",
            importedAt = 1234567890L,
            settings = SongSettingsManifest(
                tempoScale = 0.7f,
                scaffoldLevel = "GUIDED",
                countInBars = 2,
                pinnedRangeLow = 48,
                pinnedRangeHigh = 84,
            ),
        )

        assertThat(LibraryScanner.write(folder, original)).isTrue()

        assertThat(LibraryScanner.read(folder)).isEqualTo(original)
    }

    @Test
    fun `unknown keys from a newer build do not break reading`() {
        // The manifest exists so a future version can recover an old library, and
        // vice versa. A field this build has never heard of must be skipped, not
        // throw — otherwise the recovery path fails exactly when it is needed.
        val folder = temp.newFolder("song-1")
        File(folder, LibraryScanner.MANIFEST_NAME).writeText(
            """
            {
              "id": "song-1",
              "title": "A Song",
              "midiFileName": "source.mid",
              "somethingFromTheFuture": { "nested": true },
              "manifestVersion": 99
            }
            """.trimIndent(),
        )

        val manifest = LibraryScanner.read(folder)

        assertThat(manifest).isNotNull()
        assertThat(manifest!!.title).isEqualTo("A Song")
        assertThat(manifest.manifestVersion).isEqualTo(99)
    }

    @Test
    fun `a corrupt manifest reads as absent rather than throwing`() {
        val folder = songFolder("song-1")
        File(folder, LibraryScanner.MANIFEST_NAME).writeText("{ this is not json")

        assertThat(LibraryScanner.read(folder)).isNull()
        // The folder is still recoverable, because the MIDI is what matters.
        assertThat(LibraryScanner.scan(temp.root)).hasSize(1)
    }

    @Test
    fun `scan finds songs and pairs their files`() {
        songFolder(
            "song-1",
            midi = "source.mid",
            score = "score.musicxml",
            manifest = SongManifest(
                id = "song-1",
                title = "First",
                midiFileName = "source.mid",
                scoreFileName = "score.musicxml",
            ),
        )
        songFolder("song-2", midi = "source.midi")

        val found = LibraryScanner.scan(temp.root)

        assertThat(found).hasSize(2)
        assertThat(found[0].manifest?.title).isEqualTo("First")
        assertThat(found[0].midiFile?.name).isEqualTo("source.mid")
        assertThat(found[0].scoreFile?.name).isEqualTo("score.musicxml")
        assertThat(found[1].manifest).isNull()
        assertThat(found[1].midiFile?.name).isEqualTo("source.midi")
    }

    @Test
    fun `scan recovers a folder that predates manifests`() {
        // Libraries imported by v1.0.0 and v1.0.1 have no song.json at all.
        songFolder("legacy", midi = "source.mid", score = "score.mxl")

        val found = LibraryScanner.scan(temp.root).single()

        assertThat(found.manifest).isNull()
        assertThat(found.midiFile).isNotNull()
        assertThat(found.scoreFile?.name).isEqualTo("score.mxl")
        assertThat(found.isUsable).isTrue()
    }

    @Test
    fun `folders with no midi are skipped`() {
        songFolder("orphan", midi = null, score = "score.musicxml")

        assertThat(LibraryScanner.scan(temp.root)).isEmpty()
    }

    @Test
    fun `scanning a missing directory is not an error`() {
        assertThat(LibraryScanner.scan(File(temp.root, "nope"))).isEmpty()
    }

    @Test
    fun `titles are derived from filenames`() {
        assertThat(LibraryScanner.titleFromFileName("Fur_Elise-Beethoven.mid"))
            .isEqualTo("Fur Elise Beethoven")
        assertThat(LibraryScanner.titleFromFileName("gymnopedie no1.midi"))
            .isEqualTo("Gymnopedie No1")
        assertThat(LibraryScanner.titleFromFileName(".mid")).isEqualTo("Untitled")
    }
}
