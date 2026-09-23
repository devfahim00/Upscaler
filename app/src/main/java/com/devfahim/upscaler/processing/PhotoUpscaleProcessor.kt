package com.devfahim.upscaler.processing

import android.graphics.Bitmap
import android.net.Uri
import com.devfahim.upscaler.data.storage.MediaIO
import com.devfahim.upscaler.data.storage.StorageManager
import com.devfahim.upscaler.domain.model.BackendMode
import com.devfahim.upscaler.domain.model.ModelType
import com.devfahim.upscaler.domain.model.OutputFormat
import com.devfahim.upscaler.domain.model.UpscaleJob
import com.devfahim.upscaler.domain.repository.InferenceEngine
import com.devfahim.upscaler.domain.repository.SettingsRepository
import com.devfahim.upscaler.domain.tiling.MemoryGuard
import com.devfahim.upscaler.domain.tiling.TilePlanner
import com.devfahim.upscaler.domain.usecase.PlanUpscaleUseCase
import com.devfahim.upscaler.domain.usecase.SelectBackendUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Photo upscaling pipeline:
 *
 *   decode (EXIF-corrected) -> [optional input downscale when the requested
 *   scale is below the model's native scale] -> [optional HDRNet pass] ->
 *   tile plan -> per-tile ncnn inference -> body copy into the output
 *   buffer -> encode PNG/JPEG/WEBP -> thumbnail for the Library grid.
 *
 * HDR-only mode (requestedScale == 1, the "HDR only" scale chip) skips the
 * model entirely: decode -> cap -> HDRNet pass -> encode, so users can
 * apply the HDR enhancement without upscaling.
 *
 * The tile loop lives in [upscaleArgb]. The optional HDR pass (per-model
 * "HDR" toggle, [UpscaleJob.hdrEnabled], tuned by [UpscaleJob.hdrAdjust])
 * conditions the capped input with the Zero-DCE++ curve network before
 * upscaling and covers progress 0-15, the upscale itself 15-99.
 */
@Singleton
class PhotoUpscaleProcessor @Inject constructor(
    private val engine: InferenceEngine,
    private val mediaIO: MediaIO,
    private val storage: StorageManager,
    private val planUpscale: PlanUpscaleUseCase,
    private val backendResolver: BackendResolver,
    private val settingsRepository: SettingsRepository,
    private val hdrNet: HdrNetProcessor,
) {

    /** What the processor hands back to the caller. */
    data class PhotoResult(
        val file: File,
        val width: Int,
        val height: Int,
        val bytes: Long,
        val durationMs: Long,
        val backend: BackendMode,
        val scaleReduced: Boolean,
    )

    suspend fun process(
        job: UpscaleJob,
        inferenceDispatcher: CoroutineDispatcher,
        onProgress: suspend (percent: Int) -> Unit = {},
    ): Result<PhotoResult> = try {
        val started = System.currentTimeMillis()
        val settings = settingsRepository.current()
        val decision = backendResolver.resolve()

        val source = withContext(Dispatchers.IO) {
            mediaIO.decodeBitmap(Uri.parse(job.inputUri))
        } ?: return Result.failure(IllegalStateException("Could not decode the selected image."))

        val requested = job.requestedScale
        val native = job.model.nativeScale

        // HDR-only mode (ScaleOption.X1): just the enhancement pass, no
        // model upscale. The input is still capped to the shared
        // output-pixel budget so the full-resolution curve pass stays
        // inside the memory envelope.
        val hdrOnly = requested <= 1

        var work = source
        if (hdrOnly) {
            val (capW, capH) =
                MemoryGuard.capInputForNativeScale(source.width, source.height, 1)
            if (capW != source.width || capH != source.height) {
                work = Bitmap.createScaledBitmap(source, capW, capH, true)
                if (work != source) source.recycle()
            }
        } else {
            // Requested < native (e.g. 2x with a x4 model): shrink the input so
            // the model's native output equals the requested output. Keeps the
            // output buffer inside the memory budget and runs faster than a
            // native pass + downscale.
            //
            // Independently, the native-scale pass itself must stay within the
            // memory budget regardless of the requested/native relationship -
            // a large photo run through a x4 model can demand a 500MB+
            // intermediate buffer and OOM before we ever get to downscale to
            // the requested resolution. Take whichever bound is smaller.
            val (safeW, safeH) = MemoryGuard.capInputForNativeScale(source.width, source.height, native)
            var targetW = safeW
            var targetH = safeH
            if (requested < native) {
                val factor = requested.toFloat() / native.toFloat()
                targetW = minOf(targetW, (source.width * factor).toInt().coerceAtLeast(8))
                targetH = minOf(targetH, (source.height * factor).toInt().coerceAtLeast(8))
            }
            if (targetW != source.width || targetH != source.height) {
                work = Bitmap.createScaledBitmap(source, targetW, targetH, true)
                if (work != source) source.recycle()
            }
        }

        // Optional HDRNet pass (per-model "HDR" toggle, always on in
        // HDR-only mode): conditions the input before upscaling. Covers
        // progress 0..15 (or 0..100 in HDR-only mode).
        val runHdr = job.hdrEnabled || hdrOnly
        val progressBase: Int
        val progressSpan: Int
        if (runHdr) {
            work = hdrNet.enhance(
                work, decision, inferenceDispatcher, job.hdrAdjust,
            ) { pct ->
                if (hdrOnly) {
                    onProgress(pct)
                } else {
                    onProgress((pct * 15 / 100).coerceIn(0, 15))
                }
            }
            progressBase = 15
            progressSpan = 84
        } else {
            progressBase = 0
            progressSpan = 100
        }

        val final: Bitmap
        val outW: Int
        val outH: Int
        val scaleReduced: Boolean
        if (hdrOnly) {
            // No upscale: the enhanced bitmap is the result, at its own size.
            final = work
            outW = work.width
            outH = work.height
            scaleReduced = false
        } else {
            val outBitmap = upscaleBitmap(
                work, job.model, decision, inferenceDispatcher,
                onProgress = { pct -> onProgress(progressBase + pct * progressSpan / 100) },
                wdnAlpha = if (job.model.supportsWdnInterpolation) job.wdnAlpha else 0f,
            )

            val plan = planUpscale(
                width = work.width,
                height = work.height,
                requestedScale = requested,
                nativeModelScale = native,
                backendUsesGpu = decision.backend == BackendMode.GPU,
            )
            work.recycle()

            var f = outBitmap
            if (f.width != plan.outputWidth || f.height != plan.outputHeight) {
                val scaled = Bitmap.createScaledBitmap(f, plan.outputWidth, plan.outputHeight, true)
                f.recycle()
                f = scaled
            }
            final = f
            outW = plan.outputWidth
            outH = plan.outputHeight
            scaleReduced = plan.scaleReducedNotice != null
        }

        val resultFile = storage.resultFile(job.id, job.format)
        withContext(Dispatchers.IO) {
            final.compress(
                when (job.format) {
                    OutputFormat.PNG -> Bitmap.CompressFormat.PNG
                    OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
                    OutputFormat.WEBP -> Bitmap.CompressFormat.WEBP
                },
                if (job.format.supportsQuality) settings.jpegQuality else 100,
                resultFile.outputStream(),
            )
            mediaIO.writeThumbnail(final, storage.thumbFile(job.id))
            final.recycle()
        }
        Result.success(
            PhotoResult(
                file = resultFile,
                width = outW,
                height = outH,
                bytes = resultFile.length(),
                durationMs = System.currentTimeMillis() - started,
                backend = decision.backend,
                scaleReduced = scaleReduced,
            )
        )
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Result.failure(t)
    }

    /**
     * Upscales a whole bitmap through the model at its native scale.
     */
    suspend fun upscaleBitmap(
        source: Bitmap,
        model: ModelType,
        decision: SelectBackendUseCase.Decision,
        inferenceDispatcher: CoroutineDispatcher,
        onProgress: suspend (percent: Int) -> Unit = {},
        tileSize: Int? = null,
        wdnAlpha: Float = 0f,
    ): Bitmap {
        val argb = IntArray(source.width * source.height)
        source.getPixels(argb, 0, source.width, 0, 0, source.width, source.height)
        val out = upscaleArgb(argb, source.width, source.height, model, decision, inferenceDispatcher, onProgress, tileSize, wdnAlpha)
        return Bitmap.createBitmap(out, source.width * model.nativeScale, source.height * model.nativeScale, Bitmap.Config.ARGB_8888)
    }

    /**
     * Core tile loop: ARGB in (w x h) -> ARGB out (w*nativeScale x h*nativeScale).
     */
    internal suspend fun upscaleArgb(
        argb: IntArray,
        w: Int,
        h: Int,
        model: ModelType,
        decision: SelectBackendUseCase.Decision,
        inferenceDispatcher: CoroutineDispatcher,
        onProgress: suspend (percent: Int) -> Unit = {},
        tileSizeOverride: Int? = null,
        wdnAlpha: Float = 0f,
    ): IntArray = withContext(inferenceDispatcher) {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val plan = planUpscale(
            width = w,
            height = h,
            requestedScale = model.nativeScale,
            nativeModelScale = model.nativeScale,
            backendUsesGpu = decision.backend == BackendMode.GPU,
            tileSizeOverride = tileSizeOverride ?: model.tileSizeFor(decision.backend == BackendMode.GPU),
        )
        val outW = w * model.nativeScale
        val outH = h * model.nativeScale
        val out = IntArray(outW * outH)
        val handle = engine.createSession(model, decision.backend, threads, wdnAlpha)
        try {
            check(handle != 0L) { "The model could not be loaded (backend ${decision.backend})." }
            val tileBuf = IntArray(plan.tiles.maxOf { it.inW * it.inH })
            var done = 0
            for (tile in plan.tiles) {
                ensureActive()
                TileOps.extractTile(argb, w, tile, tileBuf)
                val tileOut = engine.upscaleTile(handle, tileBuf, tile.inW, tile.inH, model.nativeScale)
                    ?: error("Inference failed on a tile.")
                TileOps.copyBody(tileOut, tile, out, outW)
                done++
                onProgress((done * 100 / plan.tiles.size).coerceIn(0, 99))
            }
        } finally {
            engine.destroySession(handle)
        }
        out
    }
}
