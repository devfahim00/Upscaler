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
 *   scale is below the model's native scale] -> tile plan -> per-tile ncnn
 *   inference -> body copy into the output buffer -> encode PNG/JPEG/WEBP
 *   -> thumbnail for the Library grid.
 *
 * The tile loop lives in [upscaleArgb] and is shared with the video engine
 * and the "preview upscaled frame" feature.
 */
@Singleton
class PhotoUpscaleProcessor @Inject constructor(
    private val engine: InferenceEngine,
    private val mediaIO: MediaIO,
    private val storage: StorageManager,
    private val planUpscale: PlanUpscaleUseCase,
    private val backendResolver: BackendResolver,
    private val settingsRepository: SettingsRepository,
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

        // Requested < native (e.g. 2x with a x4 model): shrink the input so
        // the model's native output equals the requested output. Keeps the
        // output buffer inside the memory budget and runs faster than a
        // native pass + downscale.
        var work = source
        if (requested < native) {
            val factor = requested.toFloat() / native.toFloat()
            val w = (source.width * factor).toInt().coerceAtLeast(8)
            val h = (source.height * factor).toInt().coerceAtLeast(8)
            work = Bitmap.createScaledBitmap(source, w, h, true)
            if (work != source) source.recycle()
        }

        val outBitmap = upscaleBitmap(work, job.model, decision, inferenceDispatcher, onProgress)
        work.recycle()

        val plan = planUpscale(
            width = outBitmap.width / native * requested.coerceAtLeast(1),
            height = outBitmap.height / native * requested.coerceAtLeast(1),
            requestedScale = requested,
            nativeModelScale = native,
            backendUsesGpu = decision.backend == BackendMode.GPU,
        )

        var final = outBitmap
        if (final.width != plan.outputWidth || final.height != plan.outputHeight) {
            val scaled = Bitmap.createScaledBitmap(final, plan.outputWidth, plan.outputHeight, true)
            final.recycle()
            final = scaled
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
                width = plan.outputWidth,
                height = plan.outputHeight,
                bytes = resultFile.length(),
                durationMs = System.currentTimeMillis() - started,
                backend = decision.backend,
                scaleReduced = plan.scaleReducedNotice != null,
            )
        )
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Result.failure(t)
    }

    /**
     * Upscales a whole bitmap through the model at its native scale.
     * Used for photos and for video "quality preview" frames.
     */
    suspend fun upscaleBitmap(
        source: Bitmap,
        model: ModelType,
        decision: SelectBackendUseCase.Decision,
        inferenceDispatcher: CoroutineDispatcher,
        onProgress: suspend (percent: Int) -> Unit = {},
        tileSize: Int? = null,
    ): Bitmap {
        val argb = IntArray(source.width * source.height)
        source.getPixels(argb, 0, source.width, 0, 0, source.width, source.height)
        val out = upscaleArgb(argb, source.width, source.height, model, decision, inferenceDispatcher, onProgress, tileSize)
        return Bitmap.createBitmap(out, source.width * model.nativeScale, source.height * model.nativeScale, Bitmap.Config.ARGB_8888)
    }

    /**
     * Core tile loop: ARGB in (w x h) -> ARGB out (w*nativeScale x h*nativeScale).
     * Shared by the photo pipeline, the video pipeline and frame previews.
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
    ): IntArray = withContext(inferenceDispatcher) {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val plan = planUpscale(
            width = w,
            height = h,
            requestedScale = model.nativeScale,
            nativeModelScale = model.nativeScale,
            backendUsesGpu = decision.backend == BackendMode.GPU,
            tileSizeOverride = tileSizeOverride,
        )
        val outW = w * model.nativeScale
        val outH = h * model.nativeScale
        val out = IntArray(outW * outH)
        val handle = engine.createSession(model, decision.backend, threads)
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
