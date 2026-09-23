package com.devfahim.upscaler.processing

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.devfahim.upscaler.domain.model.JobStatus
import com.devfahim.upscaler.domain.model.MediaKind
import com.devfahim.upscaler.domain.model.ModelType
import com.devfahim.upscaler.domain.model.OutputFormat
import com.devfahim.upscaler.domain.model.ScaleOption
import com.devfahim.upscaler.domain.model.UpscaleJob
import com.devfahim.upscaler.domain.repository.JobsRepository
import com.devfahim.upscaler.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point the UI uses to start jobs. Creates the Room row
 * first (so the Library + Processing screen can observe it), then hands
 * execution to WorkManager (photos) or the foreground service (videos).
 */
@Singleton
class JobController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val jobsRepository: JobsRepository,
    private val settingsRepository: SettingsRepository,
) {

    suspend fun enqueuePhotoJob(
        inputUri: String,
        title: String,
        inputBytes: Long,
        model: ModelType,
        scale: ScaleOption,
        format: OutputFormat,
    ): String {
        val job = UpscaleJob(
            id = UUID.randomUUID().toString(),
            kind = MediaKind.PHOTO,
            status = JobStatus.QUEUED,
            model = model,
            requestedScale = scale.factor,
            format = format,
            inputUri = inputUri,
            inputBytes = inputBytes,
            title = title,
        )
        jobsRepository.insert(job)

        WorkManager.getInstance(context).enqueueUniqueWork(
            "photo-${job.id}",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PhotoUpscaleWorker>()
                .setInputData(PhotoUpscaleWorker.input(job.id))
                .build(),
        )
        return job.id
    }

    suspend fun enqueueVideoJob(
        inputUri: String,
        title: String,
        inputBytes: Long,
        sourceDurationMs: Long,
        model: ModelType,
        scale: ScaleOption,
        capMaxHeight: Int = 0,
    ): String {
        val job = UpscaleJob(
            id = UUID.randomUUID().toString(),
            kind = MediaKind.VIDEO,
            status = JobStatus.QUEUED,
            model = model,
            requestedScale = scale.factor,
            format = OutputFormat.PNG, // unused for videos
            inputUri = inputUri,
            capMaxHeight = capMaxHeight,
            inputBytes = inputBytes,
            sourceDurationMs = sourceDurationMs,
            title = title,
        )
        jobsRepository.insert(job)
        VideoUpscaleService.start(context, job.id)
        return job.id
    }

    fun cancelVideoJob(jobId: String) {
        VideoUpscaleService.cancel(context, jobId)
    }
}
