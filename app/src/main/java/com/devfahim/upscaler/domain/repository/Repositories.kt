package com.devfahim.upscaler.domain.repository

import com.devfahim.upscaler.domain.model.BackendMode
import com.devfahim.upscaler.domain.model.BackendPreference
import com.devfahim.upscaler.domain.model.JobStatus
import com.devfahim.upscaler.domain.model.MediaKind
import com.devfahim.upscaler.domain.model.ModelType
import com.devfahim.upscaler.domain.model.OutputFormat
import com.devfahim.upscaler.domain.model.ScaleOption
import com.devfahim.upscaler.domain.model.ThemeMode
import com.devfahim.upscaler.domain.model.UpscaleJob
import kotlinx.coroutines.flow.Flow

/** Persisted processing history. */
interface JobsRepository {
    fun observeJobs(): Flow<List<UpscaleJob>>
    fun observeJob(id: String): Flow<UpscaleJob?>
    suspend fun getJob(id: String): UpscaleJob?
    suspend fun insert(job: UpscaleJob)
    suspend fun update(job: UpscaleJob)
    suspend fun updateProgress(id: String, progress: Int)
    suspend fun markCompleted(id: String, resultPath: String, thumbnailPath: String, outW: Int, outH: Int, outputBytes: Long, processingMs: Long)
    suspend fun markFailed(id: String, error: String)
    suspend fun markCancelled(id: String)
    suspend fun markSaved(id: String, savedUri: String)
    suspend fun delete(id: String)
    suspend fun deleteAll()
    /** Jobs stuck in a non-terminal state after a process death. */
    suspend fun orphanedJobs(): List<UpscaleJob>
    suspend fun failOrphans()
}

/** User settings. */
data class AppSettings(
    val onboardingDone: Boolean,
    val defaultPhotoModel: ModelType,
    val defaultScale: ScaleOption,
    val outputFormat: OutputFormat,
    val jpegQuality: Int, // 1..100 (JPEG/WEBP lossy)
    val backendPreference: BackendPreference,
    val themeMode: ThemeMode,
    val languageTag: String, // "system" | "en" | "bn"
    val gpuBenchmarkMs: Double?,
    val cpuBenchmarkMs: Double?,
    val benchmarkModelKey: String?,
)

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun setOnboardingDone()
    suspend fun setDefaultPhotoModel(model: ModelType)
    suspend fun setDefaultScale(scale: ScaleOption)
    suspend fun setOutputFormat(format: OutputFormat)
    suspend fun setJpegQuality(quality: Int)
    suspend fun setBackendPreference(pref: BackendPreference)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setLanguageTag(tag: String)
    suspend fun storeBenchmark(gpuMs: Double?, cpuMs: Double?, modelKey: String)
}

/** Results of probing the device's inference capabilities. */
data class EngineCapabilities(
    val ncnnVersion: String,
    val gpuCount: Int,
)

/** Abstraction over the ncnn JNI bridge (fakeable in unit tests). */
interface InferenceEngine {
    fun capabilities(): EngineCapabilities
    fun createSession(model: ModelType, backend: BackendMode, threads: Int): Long
    fun destroySession(handle: Long)
    /**
     * Runs one tile through the network.
     * @return upscaled ARGB pixels sized (w*nativeScale) x (h*nativeScale).
     */
    fun upscaleTile(handle: Long, argb: IntArray, width: Int, height: Int, nativeScale: Int): IntArray?
    fun benchmark(model: ModelType, backend: BackendMode, threads: Int, iterations: Int): Double
}

/** Reads video metadata without decoding frames. */
data class VideoInfo(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val fps: Float,
    val durationMs: Long,
    val hasAudio: Boolean,
    val videoMime: String,
)

interface VideoMetadataReader {
    fun read(uri: String): VideoInfo?
}
