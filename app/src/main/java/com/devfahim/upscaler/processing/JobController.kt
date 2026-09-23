package com.devfahim.upscaler.processing

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.devfahim.upscaler.domain.model.JobStatus
import com.devfahim.upscaler.domain.model.ModelType
import com.devfahim.upscaler.domain.model.OutputFormat
import com.devfahim.upscaler.domain.model.ScaleOption
import com.devfahim.upscaler.domain.model.UpscaleJob
import com.devfahim.upscaler.domain.repository.JobsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point the UI uses to start photo jobs. Creates the Room
 * row first (so the Library + Processing screen can observe it), then hands
 * execution to WorkManager so jobs survive app backgrounding and process
 * death.
 */
@Singleton
class JobController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val jobsRepository: JobsRepository,
) {

    suspend fun enqueuePhotoJob(
        inputUri: String,
        title: String,
        inputBytes: Long,
        model: ModelType,
        scale: ScaleOption,
        format: OutputFormat,
        wdnAlpha: Float = 0f,
    ): String {
        val job = UpscaleJob(
            id = UUID.randomUUID().toString(),
            status = JobStatus.QUEUED,
            model = model,
            requestedScale = scale.factor,
            format = format,
            wdnAlpha = if (model.supportsWdnInterpolation) wdnAlpha.coerceIn(0f, 1f) else 0f,
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
}
