package com.devfahim.upscaler.processing

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.devfahim.upscaler.MainActivity
import com.devfahim.upscaler.R
import com.devfahim.upscaler.UpscalerApp
import com.devfahim.upscaler.data.storage.StorageManager
import com.devfahim.upscaler.di.InferenceDispatcher
import com.devfahim.upscaler.domain.model.JobStatus
import com.devfahim.upscaler.domain.model.MediaKind
import com.devfahim.upscaler.domain.repository.JobsRepository
import com.devfahim.upscaler.domain.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Foreground service that keeps video jobs alive while the app is in the
 * background. The persistent notification shows frame progress, ETA and a
 * Cancel action; cancelling is cooperative (the pipeline checks the flag
 * between tiles and between frames).
 */
@AndroidEntryPoint
class VideoUpscaleService : LifecycleService() {

    @Inject lateinit var engine: VideoUpscaleEngine
    @Inject lateinit var jobsRepository: JobsRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var storage: StorageManager
    @Inject @InferenceDispatcher lateinit var inferenceDispatcher: CoroutineDispatcher

    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val cancelFlags = ConcurrentHashMap<String, VideoUpscaleEngine.CancelFlag>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CANCEL -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID)
                if (jobId != null) {
                    cancelFlags[jobId]?.cancel()
                }
            }
            else -> {
                val jobId = intent?.getStringExtra(EXTRA_JOB_ID) ?: return START_NOT_STICKY
                if (runningJobs.containsKey(jobId)) return START_NOT_STICKY

                val notif = buildNotification(jobId, 0, 0, null)
                val notifId = NOTIF_ID_BASE + jobId.hashCode() % 1000
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(notifId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(notifId, notif)
                }

                val flag = VideoUpscaleEngine.CancelFlag()
                cancelFlags[jobId] = flag
                val job = lifecycleScope.launch {
                    runJob(jobId, flag)
                }
                runningJobs[jobId] = job
                job.invokeOnCompletion {
                    runningJobs.remove(jobId)
                    cancelFlags.remove(jobId)
                    if (runningJobs.isEmpty()) stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runJob(jobId: String, flag: VideoUpscaleEngine.CancelFlag) {
        val job = jobsRepository.getJob(jobId) ?: return
        if (job.kind != MediaKind.VIDEO) return

        jobsRepository.update(job.copy(status = JobStatus.RUNNING, progress = 0))
        updateNotification(jobId, 0, 0, null)

        val started = System.currentTimeMillis()
        val outputFile = storage.videoResultFile(jobId)
        val notifId = NOTIF_ID_BASE + jobId.hashCode() % 1000

        var lastFrame = 0
        try {
            engine.process(
                inputUri = Uri.parse(job.inputUri),
                outputFile = outputFile,
                model = job.model,
                requestedScale = job.requestedScale,
                capMaxHeight = job.capMaxHeight,
                inferenceDispatcher = inferenceDispatcher,
                progress = { frame, total, eta ->
                    // The UI's progress bar reads this on every frame - it's a
                    // cheap DB write and shouldn't be throttled, or the screen
                    // looks stuck at 0% until NOTIF_EVERY_N_FRAMES frames have
                    // finished (which, at CPU tile-inference speeds, can be a
                    // long wait). Only the system notification (comparatively
                    // expensive to rebuild) is throttled.
                    val percent = if (total > 0) frame * 100 / total else 0
                    jobsRepository.updateProgress(jobId, percent.coerceIn(0, 99))
                    if (frame - lastFrame >= NOTIF_EVERY_N_FRAMES || frame >= total) {
                        lastFrame = frame
                        updateNotification(jobId, frame, total, eta)
                    }
                },
                cancel = flag,
            )
            // Thumbnail from the finished file.
            val thumb = storage.thumbFile(jobId)
            runCatching {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(outputFile.absolutePath)
                    val b = mmr.frameAtTime
                    if (b != null) {
                        b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, thumb.outputStream())
                        b.recycle()
                    }
                } finally {
                    mmr.release()
                }
            }
            jobsRepository.markCompleted(
                id = jobId,
                resultPath = outputFile.absolutePath,
                thumbnailPath = thumb.absolutePath,
                outW = 0,
                outH = 0,
                outputBytes = outputFile.length(),
                processingMs = System.currentTimeMillis() - started,
            )
        } catch (ce: CancellationException) {
            runCatching { outputFile.delete() }
            jobsRepository.markCancelled(jobId)
        } catch (t: Throwable) {
            runCatching { outputFile.delete() }
            jobsRepository.markFailed(jobId, t.message ?: "Video processing failed")
        } finally {
            engine.releaseSession()
            notifyDone(notifId, jobId)
        }
    }

    // ------------------------------------------------------------------
    // Notification plumbing
    // ------------------------------------------------------------------

    private fun buildNotification(jobId: String, frame: Int, total: Int, eta: Long?): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getService(
            this, jobId.hashCode(),
            Intent(this, VideoUpscaleService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_JOB_ID, jobId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val etaText = eta?.let { formatEta(it) } ?: ""
        val percent = if (total > 0) frame * 100 / total else 0

        return NotificationCompat.Builder(this, UpscalerApp.CHANNEL_PROCESSING)
            .setContentTitle(getString(R.string.notif_video_processing_title))
            .setContentText(
                getString(R.string.notif_video_progress, frame, total) + etaText
            )
            .setSmallIcon(R.drawable.ic_stat_upscale)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, false)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.action_cancel), cancelIntent)
            .build()
    }

    private fun updateNotification(jobId: String, frame: Int, total: Int, eta: Long?) {
        val notifId = NOTIF_ID_BASE + jobId.hashCode() % 1000
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(notifId, buildNotification(jobId, frame, total, eta))
    }

    private fun notifyDone(notifId: Int, jobId: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.cancel(notifId)
    }

    private fun formatEta(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) getString(R.string.eta_minutes, m, s) else getString(R.string.eta_seconds, s)
    }

    companion object {
        const val ACTION_CANCEL = "com.devfahim.upscaler.action.CANCEL_VIDEO"
        const val EXTRA_JOB_ID = "job_id"
        const val NOTIF_ID_BASE = 2000
        const val NOTIF_EVERY_N_FRAMES = 6

        fun start(context: Context, jobId: String) {
            val intent = Intent(context, VideoUpscaleService::class.java).apply {
                putExtra(EXTRA_JOB_ID, jobId)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context, jobId: String) {
            val intent = Intent(context, VideoUpscaleService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_JOB_ID, jobId)
            }
            context.startService(intent)
        }
    }
}
