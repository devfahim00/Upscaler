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
    /**
     * Tuning for the HDRNet pass (curve strength, highlight protection and
     * the advanced exposure/color controls). Only meaningful when
     * [hdrEnabled] is true (or in HDR-only mode, requestedScale == 1).
     */
    val hdrAdjust: HdrAdjust = HdrAdjust(),
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

/**
 * Tuning knobs for the HDRNet (Zero-DCE++) pass.
 *
 * The defaults are deliberately conservative so the pass keeps photos
 * natural: the curve runs at 65% strength and bright areas are protected
 * from blowing out (see HdrCurve/HdrNetProcessor for the pipeline).
 *
 * Everything except [strength] and [highlightKnee] is an *advanced*
 * per-pixel adjustment applied after the curve (see HdrAdjustOps); all
 * default to 0 = no-op.
 *
 * @param strength     curve strength in [0, 1] - scales the network's
 *                     per-pixel curve parameter before the 8 iterations;
 *                     0 disables the curve, 1 is the full Zero-DCE++ effect.
 * @param exposure     exposure in EV, [-1, 1] -> gain 2^v (multiplicative).
 * @param brightness   additive offset, [-1, 1] -> plus/minus 0.25.
 * @param contrast     contrast factor, [-1, 1] -> 2^v around pivot 0.5.
 * @param gamma        gamma exponent, [-1, 1] -> 2^(-1.2 v) (positive =
 *                     brighter midtones).
 * @param saturation   saturation factor, [-1, 1] -> (1 + v); -1 = grayscale.
 * @param temperature  white balance, [-1, 1]: positive = warmer (R up, B down).
 * @param tint         green/magenta balance, [-1, 1]: positive = magenta.
 * @param highlightKnee where highlight protection starts, [0.6, 1.0];
 *                     1.0 = off. Pixels whose ORIGINAL max channel is above
 *                     the knee keep progressively more of their original
 *                     value (smoothstep towards 1.0), so highlights never clip.
 * @param sharpness    unsharp-mask amount in [0, 1]; 0 = off.
 */
data class HdrAdjust(
    val strength: Float = DEFAULT_STRENGTH,
    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val gamma: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val highlightKnee: Float = DEFAULT_HIGHLIGHT_KNEE,
    val sharpness: Float = 0f,
) {
    /** Clamped copy - use after decode()/from-storage (never trust ranges). */
    fun clamped(): HdrAdjust = HdrAdjust(
        strength = strength.coerceIn(0f, 1f),
        exposure = exposure.coerceIn(-1f, 1f),
        brightness = brightness.coerceIn(-1f, 1f),
        contrast = contrast.coerceIn(-1f, 1f),
        gamma = gamma.coerceIn(-1f, 1f),
        saturation = saturation.coerceIn(-1f, 1f),
        temperature = temperature.coerceIn(-1f, 1f),
        tint = tint.coerceIn(-1f, 1f),
        highlightKnee = highlightKnee.coerceIn(0.6f, 1f),
        sharpness = sharpness.coerceIn(0f, 1f),
    )

    /** True when every advanced post-curve knob is neutral (fast-path skip). */
    val advancedNeutral: Boolean
        get() = exposure == 0f && brightness == 0f && contrast == 0f &&
            gamma == 0f && saturation == 0f && temperature == 0f &&
            tint == 0f && sharpness == 0f

    /**
     * Encodes to a compact CSV "s,e,b,c,g,sat,t,tint,knee,sh" of floats -
     * Float.toString is locale-independent, so this round-trips safely.
     */
    fun encode(): String = listOf(
        strength, exposure, brightness, contrast, gamma, saturation,
        temperature, tint, highlightKnee, sharpness,
    ).joinToString(",")

    companion object {
        /** Default curve strength - the "natural" look (full 1.0 over-brightens). */
        const val DEFAULT_STRENGTH = 0.65f

        /** Default highlight-protection knee. */
        const val DEFAULT_HIGHLIGHT_KNEE = 0.8f

        /** Decodes [encode] output; falls back to clamped defaults on any mismatch. */
        fun decode(csv: String?): HdrAdjust {
            if (csv.isNullOrBlank()) return HdrAdjust()
            val parts = csv.trim().split(',')
            if (parts.size != 10) return HdrAdjust()
            val v = FloatArray(10)
            for (i in 0 until 10) {
                v[i] = parts[i].toFloatOrNull() ?: return HdrAdjust()
            }
            return HdrAdjust(
                strength = v[0], exposure = v[1], brightness = v[2],
                contrast = v[3], gamma = v[4], saturation = v[5],
                temperature = v[6], tint = v[7], highlightKnee = v[8],
                sharpness = v[9],
            ).clamped()
        }
    }
}
