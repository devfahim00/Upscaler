package com.devfahim.upscaler.processing

import android.graphics.Bitmap
import com.devfahim.upscaler.data.engine.HdrCurve
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
 * "HDR" toggle in the photo options sheet.
 *
 * Runs the tiny curve network on a 1/12 downscaled copy of the photo
 * (exactly like the official Zero-DCE++ pipeline: the network sees a
 * low-resolution view and its per-pixel curve parameter is then applied at
 * full resolution), then applies the 8-iteration enhancement curve with
 * [HdrCurve]. The upscale itself is untouched - this pass only conditions
 * the input, so the whole flow is:
 *
 * ```
 * decode -> resample/cap -> [HDR pass] -> tile upscale -> encode
 * ```
 *
 * The network runs untiled on the small copy (a 12 MP photo maps to a
 * ~70 K px input), so peak memory stays in the tens of MB even on 4 MP
 * capped inputs.
 */
@Singleton
class HdrNetProcessor @Inject constructor(
    private val engine: InferenceEngine,
) {

    /**
     * Enhances [source] with the HDRNet pass.
     *
     * @return a NEW enhanced bitmap; [source] is recycled.
     */
    suspend fun enhance(
        source: Bitmap,
        decision: SelectBackendUseCase.Decision,
        inferenceDispatcher: CoroutineDispatcher,
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
        val argb = IntArray(width * height)
        source.getPixels(argb, 0, width, 0, 0, width, height)
        HdrCurve.applyCurve(argb, width, height, curveArgb, lowW, lowH)
        source.recycle()
        Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    }
}
