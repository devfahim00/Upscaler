package com.devfahim.upscaler.processing

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.devfahim.upscaler.R
import com.devfahim.upscaler.UpscalerApp
import com.devfahim.upscaler.data.storage.StorageManager
import com.devfahim.upscaler.domain.model.JobStatus
import com.devfahim.upscaler.domain.repository.JobsRepository
import com.devfahim.upscaler.di.InferenceDispatcher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Processes one photo job. Photo jobs go through WorkManager so they
 * survive app backgrounding and process death (WorkManager re-runs them).
 */
@HiltWorker
class PhotoUpscaleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val processor: PhotoUpscaleProcessor,
    private val jobsRepository: JobsRepository,
    private val storage: StorageManager,
    @InferenceDispatcher private val inferenceDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val job = jobsRepository.getJob(jobId) ?: return Result.failure()

        if (job.status == JobStatus.COMPLETED) return Result.success()

        jobsRepository.update(job.copy(status = JobStatus.RUNNING, progress = 0))

        setForeground(createForegroundInfo(0))

        var lastReported = -1
        val result = processor.process(job, inferenceDispatcher) { percent ->
            if (percent != lastReported) {
                lastReported = percent
                jobsRepository.updateProgress(jobId, percent)
                setForeground(createForegroundInfo(percent))
            }
        }

        return result.fold(
            onSuccess = { photo ->
                jobsRepository.markCompleted(
                    id = jobId,
                    resultPath = photo.file.absolutePath,
                    thumbnailPath = storage.thumbFile(jobId).absolutePath,
                    outW = photo.width,
                    outH = photo.height,
                    outputBytes = photo.bytes,
                    processingMs = photo.durationMs,
                )
                Result.success()
            },
            onFailure = { error ->
                jobsRepository.markFailed(jobId, error.message ?: "Unknown error")
                if (runAttemptCount < MAX_RETRIES && error !is kotlinx.coroutines.CancellationException) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            },
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo(0)

    private fun createForegroundInfo(progress: Int): ForegroundInfo {
        val notification: Notification =
            NotificationCompat.Builder(applicationContext, UpscalerApp.CHANNEL_PROCESSING)
                .setContentTitle(applicationContext.getString(R.string.notif_photo_processing_title))
                .setContentText(applicationContext.getString(R.string.notif_processing_desc))
                .setSmallIcon(R.drawable.ic_stat_upscale)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, progress, false)
                .build()

        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val NOTIF_ID = 1001
        const val MAX_RETRIES = 2

        /** Enqueue data helper. */
        fun input(jobId: String) = androidx.work.workDataOf(KEY_JOB_ID to jobId)
    }
}
