package com.devfahim.upscaler.domain.model

import androidx.annotation.StringRes
import com.devfahim.upscaler.R

/** Compute backend used for ncnn inference. */
enum class BackendMode {
    /** ncnn Vulkan GPU compute. */
    GPU,

    /** ncnn multi-threaded CPU (ARM NEON). */
    CPU,
}

/** User preference (Settings). `AUTO` resolves via benchmark on device. */
enum class BackendPreference {
    AUTO, GPU, CPU;
}

/** Theme preference. */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;
}

/** Output image format for photos. */
enum class OutputFormat(
    val mimeType: String,
    val fileExtension: String,
    val supportsQuality: Boolean,
    @StringRes val labelRes: Int,
) {
    PNG("image/png", "png", false, R.string.format_png),
    JPEG("image/jpeg", "jpg", true, R.string.format_jpeg),
    WEBP("image/webp", "webp", true, R.string.format_webp),
}

/** Lifecycle of an upscale job (persisted in Room). */
enum class JobStatus {
    QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED;
}

/**
 * A single photo upscale job. Persisted in Room so that jobs survive process
 * death; the Library screen is built on top of this.
 */
data class UpscaleJob(
    val id: String,
    val status: JobStatus,
    val model: ModelType,
    val requestedScale: Int,
    val format: OutputFormat,
    /**
     * WDN interpolation strength for [ModelType.GENERAL_PHOTO_X4]:
     * 0 = pure realesr-general-x4v3 (most texture), 1 = pure
     * realesr-general-wdn-x4v3 (strongest denoise / smoothest).
     * Ignored by models without [ModelType.supportsWdnInterpolation].
     */
    val wdnAlpha: Float = 0f,
    /**
     * Whether the HDRNet (Zero-DCE++) HDR enhancement pass ran/should run
     * before upscaling. Chosen per model in the photo options sheet.
     */
    val hdrEnabled: Boolean = false,
    /** Original content Uri (as string). */
    val inputUri: String,
    /** App-private result file (filesDir/results/<id>.<ext>). */
    val resultPath: String? = null,
    /** MediaStore Uri after the user taps "Save to Gallery" (nullable until then). */
    val savedUri: String? = null,
    /** Small thumbnail (filesDir/thumbs/<id>.jpg) used by the Library grid. */
    val thumbnailPath: String? = null,
    val progress: Int = 0, // 0..100
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val inputBytes: Long = 0,
    val outputBytes: Long = 0,
    val outWidth: Int = 0,
    val outHeight: Int = 0,
    val processingDurationMs: Long = 0,
    val errorMessage: String? = null,
    val title: String = "",
)

/** Result of the device capability probe (cached after first launch). */
data class DeviceCapability(
    val gpuCount: Int,
    val recommendedBackend: BackendMode,
)
