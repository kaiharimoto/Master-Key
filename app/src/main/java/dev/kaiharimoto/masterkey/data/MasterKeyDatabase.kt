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

@Database(
    entities = [SongEntity::class, PracticeSectionEntity::class, PracticeSessionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MasterKeyDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao
    abstract fun practiceDao(): PracticeDao

    companion object {
        fun create(context: Context): MasterKeyDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                MasterKeyDatabase::class.java,
                "master-key.db",
            ).build()
    }
}
