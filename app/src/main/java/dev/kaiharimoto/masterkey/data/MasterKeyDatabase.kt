package dev.kaiharimoto.masterkey.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {

    @Query("SELECT * FROM songs ORDER BY lastOpenedAt DESC, importedAt DESC")
    fun observeAll(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id = :id")
    fun observe(id: String): Flow<SongEntity?>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun find(id: String): SongEntity?

    @Query("SELECT * FROM songs")
    suspend fun all(): List<SongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(song: SongEntity)

    @Update
    suspend fun update(song: SongEntity)

    @Delete
    suspend fun delete(song: SongEntity)

    @Query("UPDATE songs SET lastOpenedAt = :timestamp WHERE id = :id")
    suspend fun touch(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE songs SET totalPracticeMillis = totalPracticeMillis + :millis WHERE id = :id")
    suspend fun addPracticeTime(id: String, millis: Long)
}

@Dao
interface PracticeDao {

    @Query("SELECT * FROM practice_sections WHERE songId = :songId ORDER BY startBar")
    fun observeSections(songId: String): Flow<List<PracticeSectionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSection(section: PracticeSectionEntity): Long

    @Delete
    suspend fun deleteSection(section: PracticeSectionEntity)

    @Insert
    suspend fun insertSession(session: PracticeSessionEntity)

    @Query("SELECT * FROM practice_sessions WHERE songId = :songId ORDER BY startedAt DESC LIMIT :limit")
    fun observeSessions(songId: String, limit: Int = 50): Flow<List<PracticeSessionEntity>>

    @Query("SELECT COALESCE(SUM(durationMillis), 0) FROM practice_sessions WHERE songId = :songId")
    suspend fun totalPracticeMillis(songId: String): Long
}

/**
 * Adds the `countInBars` column introduced in v1.0.1.
 *
 * v1.0.1 shipped that column without bumping the database version. Room stores a
 * hash of the schema in the file; on open it saw the version was unchanged, so it
 * skipped migration entirely and then failed the identity check — crashing the
 * app on every launch, because the first query happens at startup.
 *
 * Bumping to 2 makes Room take the migration branch it previously skipped.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE songs ADD COLUMN countInBars INTEGER NOT NULL DEFAULT 1",
        )
    }
}

@Database(
    entities = [SongEntity::class, PracticeSectionEntity::class, PracticeSessionEntity::class],
    version = 2,
    // Exported schemas are committed under app/schemas and checked in CI, so a
    // schema change without a version bump fails the build instead of the app.
    exportSchema = true,
)
abstract class MasterKeyDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao
    abstract fun practiceDao(): PracticeDao

    companion object {
        const val NAME = "master-key.db"

        fun create(context: Context): MasterKeyDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                MasterKeyDatabase::class.java,
                NAME,
            )
                .addMigrations(MIGRATION_1_2)
                // Installing an older APK should degrade, not brick. The library
                // is rebuildable from the files on disk, so dropping the index is
                // recoverable rather than destructive.
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
