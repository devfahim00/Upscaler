package com.devfahim.upscaler.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey val id: String,
    val kind: String,           // MediaKind.name
    val status: String,         // JobStatus.name
    val modelKey: String,       // ModelType.name
    val requestedScale: Int,
    val format: String,         // OutputFormat.name
    val inputUri: String,
    val capMaxHeight: Int,
    val resultPath: String?,
    val savedUri: String?,
    val thumbnailPath: String?,
    val progress: Int,
    val createdAt: Long,
    val completedAt: Long?,
    val inputBytes: Long,
    val outputBytes: Long,
    val outWidth: Int,
    val outHeight: Int,
    val processingDurationMs: Long,
    val sourceDurationMs: Long,
    val errorMessage: String?,
    val title: String,
)

@Dao
interface JobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: JobEntity)

    @Update
    suspend fun update(entity: JobEntity)

    @Query("SELECT * FROM jobs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs WHERE id = :id")
    fun observeById(id: String): Flow<JobEntity?>

    @Query("SELECT * FROM jobs WHERE id = :id")
    suspend fun getById(id: String): JobEntity?

    @Query("UPDATE jobs SET progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: String, progress: Int)

    @Query("DELETE FROM jobs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM jobs")
    suspend fun deleteAll()

    @Query("SELECT * FROM jobs WHERE status IN ('QUEUED', 'RUNNING')")
    suspend fun activeJobs(): List<JobEntity>

    @Query(
        "UPDATE jobs SET status = 'FAILED', errorMessage = :message, completedAt = :now " +
            "WHERE status IN ('QUEUED', 'RUNNING')"
    )
    suspend fun failActive(message: String, now: Long)
}

@Database(entities = [JobEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun jobDao(): JobDao
}
