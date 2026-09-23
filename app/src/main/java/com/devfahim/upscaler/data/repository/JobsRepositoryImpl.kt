package com.devfahim.upscaler.data.repository

import com.devfahim.upscaler.data.db.JobEntity
import com.devfahim.upscaler.domain.model.HdrAdjust
import com.devfahim.upscaler.domain.model.JobStatus
import com.devfahim.upscaler.domain.model.ModelType
import com.devfahim.upscaler.domain.model.OutputFormat
import com.devfahim.upscaler.domain.model.UpscaleJob
import com.devfahim.upscaler.domain.repository.JobsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JobsRepositoryImpl @Inject constructor(
    private val dao: com.devfahim.upscaler.data.db.JobDao,
) : JobsRepository {

    override fun observeJobs(): Flow<List<UpscaleJob>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeJob(id: String): Flow<UpscaleJob?> =
        dao.observeById(id).map { it?.toDomain() }

    override suspend fun getJob(id: String): UpscaleJob? = dao.getById(id)?.toDomain()

    override suspend fun insert(job: UpscaleJob) = dao.insert(job.toEntity())

    override suspend fun update(job: UpscaleJob) = dao.update(job.toEntity())

    override suspend fun updateProgress(id: String, progress: Int) =
        dao.updateProgress(id, progress.coerceIn(0, 100))

    override suspend fun markCompleted(
        id: String,
        resultPath: String,
        thumbnailPath: String,
        outW: Int,
        outH: Int,
        outputBytes: Long,
        processingMs: Long,
    ) {
        val e = dao.getById(id) ?: return
        dao.update(
            e.copy(
                status = JobStatus.COMPLETED.name,
                resultPath = resultPath,
                thumbnailPath = thumbnailPath,
                outWidth = outW,
                outHeight = outH,
                outputBytes = outputBytes,
                processingDurationMs = processingMs,
                completedAt = System.currentTimeMillis(),
                progress = 100,
                errorMessage = null,
            )
        )
    }

    override suspend fun markFailed(id: String, error: String) {
        val e = dao.getById(id) ?: return
        dao.update(
            e.copy(
                status = JobStatus.FAILED.name,
                errorMessage = error.take(500),
                completedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun markCancelled(id: String) {
        val e = dao.getById(id) ?: return
        dao.update(
            e.copy(
                status = JobStatus.CANCELLED.name,
                completedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun markSaved(id: String, savedUri: String) {
        val e = dao.getById(id) ?: return
        dao.update(e.copy(savedUri = savedUri))
    }

    override suspend fun delete(id: String) = dao.deleteById(id)

    override suspend fun deleteAll() = dao.deleteAll()

    override suspend fun orphanedJobs(): List<UpscaleJob> =
        dao.activeJobs().map { it.toDomain() }

    override suspend fun failOrphans() {
        dao.failActive(
            message = "Processing was interrupted before it could finish.",
            now = System.currentTimeMillis(),
        )
    }
}

// ---------------------------------------------------------------------------
// Mapping
// ---------------------------------------------------------------------------

internal fun JobEntity.toDomain(): UpscaleJob = UpscaleJob(
    id = id,
    status = runCatching { JobStatus.valueOf(status) }.getOrDefault(JobStatus.FAILED),
    model = runCatching { ModelType.valueOf(modelKey) }.getOrDefault(ModelType.GENERAL_PHOTO_X4),
    requestedScale = requestedScale,
    format = runCatching { OutputFormat.valueOf(format) }.getOrDefault(OutputFormat.PNG),
    wdnAlpha = wdnAlpha,
    hdrEnabled = hdrEnabled,
    hdrAdjust = HdrAdjust.decode(hdrAdjust),
    inputUri = inputUri,
    resultPath = resultPath,
    savedUri = savedUri,
    thumbnailPath = thumbnailPath,
    progress = progress,
    createdAt = createdAt,
    completedAt = completedAt,
    inputBytes = inputBytes,
    outputBytes = outputBytes,
    outWidth = outWidth,
    outHeight = outHeight,
    processingDurationMs = processingDurationMs,
    errorMessage = errorMessage,
    title = title,
)

internal fun UpscaleJob.toEntity(): JobEntity = JobEntity(
    id = id,
    status = status.name,
    modelKey = model.name,
    requestedScale = requestedScale,
    format = format.name,
    wdnAlpha = wdnAlpha,
    hdrEnabled = hdrEnabled,
    hdrAdjust = if (hdrEnabled) hdrAdjust.encode() else "",
    inputUri = inputUri,
    resultPath = resultPath,
    savedUri = savedUri,
    thumbnailPath = thumbnailPath,
    progress = progress,
    createdAt = createdAt,
    completedAt = completedAt,
    inputBytes = inputBytes,
    outputBytes = outputBytes,
    outWidth = outWidth,
    outHeight = outHeight,
    processingDurationMs = processingDurationMs,
    errorMessage = errorMessage,
    title = title,
)
