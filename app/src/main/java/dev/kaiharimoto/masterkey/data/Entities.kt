package dev.kaiharimoto.masterkey.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A song in the library.
 *
 * Analysis results (pitch range, hand split, duration, bar count) are cached here
 * at import time. Re-deriving them means re-parsing the MIDI, which is fast but
 * not free, and opening a song should feel instant.
 */
@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val composer: String? = null,

    /** Directory name under `filesDir/library/` holding the copied source files. */
    val folder: String,
    val midiFileName: String,
    val scoreFileName: String? = null,

    val durationMicros: Long = 0,
    val barCount: Int = 0,
    val noteCount: Int = 0,
    val pitchLow: Int = 21,
    val pitchHigh: Int = 108,
    val keySignature: String? = null,
    val timeSignature: String? = null,
    val originalBpm: Double = 120.0,

    val importedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long? = null,

    // ---- per-song preferences, so each piece remembers how you practise it ----
    @ColumnInfo(defaultValue = "0.9") val tempoScale: Float = 0.9f,
    @ColumnInfo(defaultValue = "MINIMAL") val scaffoldLevel: String = "MINIMAL",
    @ColumnInfo(defaultValue = "OFF") val noteNameStyle: String = "OFF",
    @ColumnInfo(defaultValue = "BY_HAND") val colorMode: String = "BY_HAND",
    /** Manual key-range override; null means "decide automatically". */
    val pinnedRangeLow: Int? = null,
    val pinnedRangeHigh: Int? = null,
    @ColumnInfo(defaultValue = "0") val rightHandMuted: Boolean = false,
    @ColumnInfo(defaultValue = "0") val leftHandMuted: Boolean = false,
    @ColumnInfo(defaultValue = "0") val metronomeEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "1") val countInBars: Int = 1,
    @ColumnInfo(defaultValue = "1") val showScore: Boolean = true,
    @ColumnInfo(defaultValue = "8.0") val lookAheadBeats: Float = 8f,

    /** Highest tempo scale reached in a completed practice run, for progress. */
    @ColumnInfo(defaultValue = "0.0") val bestTempoScale: Float = 0f,
    @ColumnInfo(defaultValue = "0") val totalPracticeMillis: Long = 0,
)

/** A saved loop region, so a hard passage can be returned to across sessions. */
@Entity(
    tableName = "practice_sections",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("songId")],
)
data class PracticeSectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val label: String,
    val startBar: Int,
    val endBar: Int,
    val createdAt: Long = System.currentTimeMillis(),
    /** Best tempo scale cleared on this section, used by the tempo drill. */
    val bestTempoScale: Float = 0f,
)

/** One practice run, for the progress view. */
@Entity(
    tableName = "practice_sessions",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("songId")],
)
data class PracticeSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val startedAt: Long,
    val durationMillis: Long,
    val tempoScale: Float,
    val sectionLabel: String? = null,
)
