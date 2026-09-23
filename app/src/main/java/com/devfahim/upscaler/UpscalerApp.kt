package com.devfahim.upscaler

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.devfahim.upscaler.domain.repository.JobsRepository
import com.devfahim.upscaler.domain.repository.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class UpscalerApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var jobsRepository: JobsRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Crash-safe recovery: jobs stuck in QUEUED/RUNNING after a process
        // death are marked FAILED with an "interrupted" message; the Library
        // screen surfaces them so the user can retry with one tap.
        appScope.launch {
            runCatching { jobsRepository.failOrphans() }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROCESSING,
                getString(R.string.channel_processing),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_processing_desc)
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_PROCESSING = "processing"

        /** SharedPreferences file used for the synchronous locale lookup. */
        const val LOCALE_PREFS = "locale_prefs"
        const val LOCALE_KEY = "locale_tag"
    }
}
