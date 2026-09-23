package com.devfahim.upscaler.processing

import android.graphics.Bitmap
import com.devfahim.upscaler.data.engine.HdrCurve
import com.devfahim.upscaler.domain.model.HdrAdjust
import com.devfahim.upscaler.domain.model.ModelType
import com.devfahim.upscaler.domain.repository.InferenceEngine
import com.devfahim.upscaler.domain.usecase.SelectBackendUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HDRNet (Zero-DCE++) enhancement pass - the engine behind the per-model
 * "HDR" toggle in the photo options sheet and behind the HDR-only mode.
 *
 * Runs the tiny curve network on a 1/12 downscaled copy of the photo
 * (exactly like the official Zero-DCE++ pipeline: the network sees a
 * low-resolution view and its per-pixel curve parameter is then applied at
 * full resolution). The upscale itself is untouched - this pass only
 * conditions the input, so the whole flow is:
 *
 * ```
 * decode -> resample/cap -> [HDR pass] -> tile upscale -> encode
 * ```
 *
 * The full-resolution stage applies, in order (see [HdrCurve]):
 *
 *  1. the 8-iteration enhancement curve, scaled by [HdrAdjust.strength]
 *     (default 0.65 - the full-strength curve over-brightens),
 *  2. the **auto exposure anchor**: the global mean luminance may rise by
 *     at most +8% on well-exposed photos (more on dark scenes), which is
 *     what keeps results looking natural instead of washed out,
 *  3. **highlight protection**: pixels whose original value approaches
 *     white keep more of their original value, so highlights never clip,
 *  4. the advanced adjustments from [HdrAdjustOps] (exposure, brightness,
 *     contrast, gamma, temperature, tint, saturation, unsharp sharpness).
 *
 * The network runs untiled on the small copy (a 12 MP photo maps to a
 * ~70 K px input), so peak memory stays in the tens of MB even on 4 MP
 * capped inputs; the full-res stage needs the original + one working
 * buffer (both bounded by the pipeline's input cap).
 */
@Singleton
class HdrNetProcessor @Inject constructor(
    private val engine: InferenceEngine,
) {

    /**
     * Enhances [source] with the HDRNet pass using the given [adjust]
     * tuning (defaults = the natural look).
     *
     * @return a NEW enhanced bitmap; [source] is recycled.
     */
    suspend fun enhance(
        source: Bitmap,
        decision: SelectBackendUseCase.Decision,
        inferenceDispatcher: CoroutineDispatcher,
        adjust: HdrAdjust = HdrAdjust(),
        onProgress: suspend (percent: Int) -> Unit = {},
    ): Bitmap = withContext(inferenceDispatcher) {
        val width = source.width
        val height = source.height
        val lowW = HdrCurve.lowDim(width)
        val lowH = HdrCurve.lowDim(height)

        ensureActive()
        val low = Bitmap.createScaledBitmap(source, lowW, lowH, true)
        val lowArgb = IntArray(lowW * lowH)
        low.getPixels(lowArgb, 0, lowW, 0, 0, lowW, lowH)
        low.recycle()

        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val handle = engine.createSession(ModelType.HDR_NET, decision.backend, threads)
        val curveArgb = try {
            check(handle != 0L) {
                "The HDRNet model could not be loaded (backend ${decision.backend})."
            }
            engine.upscaleTile(handle, lowArgb, lowW, lowH, ModelType.HDR_NET.nativeScale)
                ?: error("HDRNet inference failed.")
        } finally {
            engine.destroySession(handle)
        }
        onProgress(50)

        ensureActive()
        // Keep the original pixels: the anchor and the highlight mask both
        // need them, and the output goes into a separate working buffer.
        val n = width * height
        val orig = IntArray(n)
        source.getPixels(orig, 0, width, 0, 0, width, height)
        source.recycle()
        val out = orig.copyOf()

        // Full-res natural-look pipeline:
        //   scaled curve -> auto exposure anchor -> highlight protection
        //   -> bounded second anchor pass -> advanced adjustments.
        // (Pure Kotlin, unit-tested as HdrCurve.enhanceNatural.)
        HdrCurve.enhanceNatural(orig, out, width, height, curveArgb, lowW, lowH, adjust)
        onProgress(100)

        Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }
}
