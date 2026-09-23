package com.devfahim.upscaler.processing

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import com.devfahim.upscaler.data.storage.StorageManager
import com.devfahim.upscaler.domain.model.ModelType
import com.devfahim.upscaler.domain.repository.InferenceEngine
import com.devfahim.upscaler.domain.repository.VideoInfo
import com.devfahim.upscaler.domain.repository.VideoMetadataReader
import com.devfahim.upscaler.domain.tiling.TilePlanner
import com.devfahim.upscaler.domain.usecase.PlanUpscaleUseCase
import com.devfahim.upscaler.domain.usecase.SelectBackendUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Frame-by-frame video upscaling pipeline (v1, sequential):
 *
 *   MediaExtractor -> MediaCodec decoder (YUV_420_888 Images)
 *     -> YUV->ARGB -> tiled ncnn inference (ONE session reused per frame)
 *     -> ARGB->YUV -> MediaCodec H.264 encoder
 *     -> MediaMuxer (video track + untouched audio track passthrough)
 *
 * The audio track is copied sample-by-sample from the source container and
 * is never re-encoded, preserving quality and saving time. Orchestration
 * (notification, cancellation, Room updates) lives in [VideoUpscaleService].
 */
@Singleton
class VideoUpscaleEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: InferenceEngine,
    private val backendResolver: BackendResolver,
    private val planUpscale: PlanUpscaleUseCase,
    private val metadataReader: VideoMetadataReader,
    private val storage: StorageManager,
) {

    /** Cooperative cancellation flag shared with the foreground service. */
    class CancelFlag {
        private val cancelled = AtomicBoolean(false)
        fun cancel() = cancelled.set(true)
        val isCancelled: Boolean get() = cancelled.get()
    }

    fun interface ProgressListener {
        suspend fun onProgress(frame: Int, totalFrames: Int, etaSeconds: Long?)
    }

    /** Tile edge for video frames (bigger than photos: fewer, larger tiles). */
    private fun tileSizeFor(backend: com.devfahim.upscaler.domain.model.BackendMode): Int =
        if (backend == com.devfahim.upscaler.domain.model.BackendMode.GPU) GPU_VIDEO_TILE else CPU_VIDEO_TILE

    // ------------------------------------------------------------------
    // Preview: upscale 2 sample frames for the "check quality first" flow
    // ------------------------------------------------------------------

    suspend fun previewFrames(
        inputUri: Uri,
        model: ModelType,
        inferenceDispatcher: CoroutineDispatcher,
        count: Int = 2,
    ): List<File> = withContext(Dispatchers.IO) {
        val info = metadataReader.read(inputUri.toString()) ?: return@withContext emptyList()
        val decision = backendResolver.resolve()
        val retriever = MediaMetadataRetriever()
        val results = ArrayList<File>(count)
        try {
            retriever.setDataSource(context, inputUri)
            for (i in 0 until count) {
                val tsUs = if (count <= 1) 0
                else info.durationMs * 1000L * i / (count - 1)
                val frame = retriever.getFrameAtTime(
                    tsUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                ) ?: continue
                val out = runCatching {
                    val argb = IntArray(frame.width * frame.height)
                    frame.getPixels(argb, 0, frame.width, 0, 0, frame.width, frame.height)
                    frame.recycle()
                    val up = upscaleFrame(argb, frame.width, frame.height, model, decision, tileSizeFor(decision.backend), null)
                    val bmp = Bitmap.createBitmap(
                        up.pixels, up.width, up.height, Bitmap.Config.ARGB_8888,
                    )
                    val file = File(context.cacheDir, "preview_${System.nanoTime()}_$i.png")
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, file.outputStream())
                    bmp.recycle()
                    file
                }.getOrNull()
                if (out != null) results += out
            }
        } finally {
            runCatching { retriever.release() }
        }
        results
    }

    // ------------------------------------------------------------------
    // Full processing
    // ------------------------------------------------------------------

    /**
     * @param capMaxHeight cap on the output *display* height (0 = uncapped)
     * @return the MP4 file written
     */
    suspend fun process(
        inputUri: Uri,
        outputFile: File,
        model: ModelType,
        requestedScale: Int,
        capMaxHeight: Int,
        inferenceDispatcher: CoroutineDispatcher,
        progress: ProgressListener,
        cancel: CancelFlag,
    ): File = try {
        withContext(inferenceDispatcher) {
            processInternal(
                inputUri, outputFile, model, requestedScale, capMaxHeight,
                progress, cancel, inferenceDispatcher,
            )
        }
        outputFile
    } catch (ce: CancellationException) {
        runCatching { outputFile.delete() }
        throw ce
    } catch (t: Throwable) {
        runCatching { outputFile.delete() }
        throw IllegalStateException("Video processing failed: ${t.message}", t)
    }

    private suspend fun processInternal(
        inputUri: Uri,
        outputFile: File,
        model: ModelType,
        requestedScale: Int,
        capMaxHeight: Int,
        progress: ProgressListener,
        cancel: CancelFlag,
        inferenceDispatcher: CoroutineDispatcher,
    ) {
        val decision = backendResolver.resolve()
        val tileSize = tileSizeFor(decision.backend)
        val info: VideoInfo = metadataReader.read(inputUri.toString())
            ?: error("Could not read the video's metadata.")
        // ---- Track discovery -----------------------------------------
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, inputUri, null)
        } catch (t: Throwable) {
            error("Could not open the selected video: ${t.message}")
        }
        var videoTrackIdx = -1
        var audioTrackIdx = -1
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (videoTrackIdx < 0 && mime.startsWith("video/")) {
                videoTrackIdx = i; videoFormat = f
            } else if (audioTrackIdx < 0 && mime.startsWith("audio/")) {
                audioTrackIdx = i; audioFormat = f
            }
        }
        if (videoTrackIdx < 0) {
            extractor.release()
            error("No video track found.")
        }
        val videoMime = videoFormat!!.getString(MediaFormat.KEY_MIME)!!
        val codedW = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val codedH = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val fps = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            runCatching { videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrDefault(30f)
        } else 30f
        val safeFps = if (fps.isNaN() || fps <= 0f || fps > 120f) 30f else fps
        val rotation = info.rotationDegrees

        // ---- Output geometry -----------------------------------------
        // Run the model at min(requested, native); the "requested < native"
        // case pre-shrinks frames instead of post-downscaling (bounded memory).
        val runScale = requestedScale.coerceAtMost(model.nativeScale)
        val preShrink = runScale.toFloat() / model.nativeScale
        val feedW = if (preShrink < 1f) (codedW * preShrink).roundToInt().coerceAtLeast(8) else codedW
        val feedH = if (preShrink < 1f) (codedH * preShrink).roundToInt().coerceAtLeast(8) else codedH

        // Display-space height after scaling (rotation-aware).
        val displayHAfter = (if (rotation == 90 || rotation == 270) feedW else feedH) * model.nativeScale
        var encW = feedW * model.nativeScale
        var encH = feedH * model.nativeScale
        if (capMaxHeight > 0 && displayHAfter > capMaxHeight) {
            val r = capMaxHeight.toFloat() / displayHAfter
            encW = (encW * r).roundToInt()
            encH = (encH * r).roundToInt()
        }
        encW = makeEven(encW)
        encH = makeEven(encH)

        // ---- Codecs & muxer ------------------------------------------
        val decoder = MediaCodec.createDecoderByType(videoMime)
        val encoder = MediaCodec.createEncoderByType("video/avc")
        val encFormat = MediaFormat.createVideoFormat("video/avc", encW, encH).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(encW, encH, safeFps))
            setInteger(MediaFormat.KEY_FRAME_RATE, safeFps.toInt())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        var semiPlanar = true
        try {
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (t: Throwable) {
            encFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            )
            semiPlanar = false
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotation)

        extractor.selectTrack(videoTrackIdx)
        decoder.configure(videoFormat, null, null, 0)

        try {
            decoder.start()
            encoder.start()
            runLoop(
                extractor = extractor,
                decoder = decoder,
                encoder = encoder,
                encoderSemiPlanar = semiPlanar,
                muxer = muxer,
                inputUri = inputUri,
                audioFormat = audioFormat,
                audioTrackIdx = audioTrackIdx,
                model = model,
                decision = decision,
                feedW = feedW,
                feedH = feedH,
                encW = encW,
                encH = encH,
                fps = safeFps,
                durationMs = info.durationMs,
                progress = progress,
                cancel = cancel,
                inferenceDispatcher = inferenceDispatcher,
                tileSize = tileSize,
            )
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { extractor.release() }
        }
    }

    // ------------------------------------------------------------------
    // The decode -> upscale -> encode -> mux loop
    // ------------------------------------------------------------------

    private suspend fun runLoop(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        encoder: MediaCodec,
        encoderSemiPlanar: Boolean,
        muxer: MediaMuxer,
        inputUri: Uri,
        audioFormat: MediaFormat?,
        audioTrackIdx: Int,
        model: ModelType,
        decision: SelectBackendUseCase.Decision,
        feedW: Int,
        feedH: Int,
        encW: Int,
        encH: Int,
        fps: Float,
        durationMs: Long,
        progress: ProgressListener,
        cancel: CancelFlag,
        inferenceDispatcher: CoroutineDispatcher,
        tileSize: Int,
    ) {
        val decInfo = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()

        var muxerStarted = false
        var muxVideoTrack = -1
        var muxAudioTrack = -1
        var inputDone = false
        var decodeDone = false
        var encodeDone = false
        var framesDone = 0
        var frameEmaMs = 0.0

        val totalFrames = if (durationMs > 0) {
            (durationMs / 1000.0 * fps).toInt().coerceAtLeast(1)
        } else 1

        val ySize = encW * encH
        val yBytes = ByteArray(ySize)
        val uvBytes = ByteArray(ySize / 2)
        val uBytes = ByteArray(ySize / 4)
        val vBytes = ByteArray(ySize / 4)

        while (!encodeDone) {
            if (cancel.isCancelled) throw CancellationException("Video job cancelled")

            // ---- 1. feed the decoder --------------------------------
            if (!inputDone) {
                val inIdx = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = decoder.getInputBuffer(inIdx)!!
                    val sampleSize = extractor.readSampleData(buf, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // ---- 2. drain the decoder -> upscale -> feed encoder -----
            if (!decodeDone) {
                val outIdx = decoder.dequeueOutputBuffer(decInfo, DEQUEUE_TIMEOUT_US)
                if (outIdx >= 0) {
                    val ptsUs = decInfo.presentationTimeUs
                    val eos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (!eos && decInfo.size > 0) {
                        val image = decoder.getOutputImage(outIdx)
                        if (image != null) {
                            val t0 = System.currentTimeMillis()
                            val frameArgb = IntArray(image.width * image.height)
                            Yuv.imageToArgb(image, frameArgb)
                            image.close()

                            // Pre-shrink if requested scale < native scale.
                            val feed: IntArray
                            val fw: Int
                            val fh: Int
                            if (image.width != feedW || image.height != feedH) {
                                feed = TileOps.resampleBilinear(frameArgb, image.width, image.height, feedW, feedH)
                                fw = feedW; fh = feedH
                            } else {
                                feed = frameArgb; fw = image.width; fh = image.height
                            }

                            val up = upscaleFrame(feed, fw, fh, model, decision, tileSize, cancel)

                            val finalArgb = if (up.width != encW || up.height != encH) {
                                TileOps.resampleBilinear(up.pixels, up.width, up.height, encW, encH)
                            } else up.pixels

                            if (encoderSemiPlanar) {
                                Yuv.argbToNv12(finalArgb, encW, encH, yBytes, uvBytes)
                            } else {
                                Yuv.argbToI420(finalArgb, encW, encH, yBytes, uBytes, vBytes)
                            }
                            queueEncoderInput(
                                encoder, encoderSemiPlanar, yBytes, uvBytes, uBytes, vBytes, ptsUs,
                            )

                            frameEmaMs = if (frameEmaMs == 0.0) {
                                (System.currentTimeMillis() - t0).toDouble()
                            } else {
                                0.15 * (System.currentTimeMillis() - t0) + 0.85 * frameEmaMs
                            }
                            framesDone++
                            val eta = ((totalFrames - framesDone) * frameEmaMs / 1000.0)
                                .toLong().coerceAtLeast(0)
                            progress.onProgress(framesDone, totalFrames, eta)
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (eos) {
                        decodeDone = true
                        queueEncoderEos(encoder)
                    }
                }
                // INFO_OUTPUT_FORMAT_CHANGED / TRY_AGAIN: nothing to do.
            }

            // ---- 3. drain the encoder -> mux ------------------------
            val encIdx = encoder.dequeueOutputBuffer(encInfo, DEQUEUE_TIMEOUT_US)
            when {
                encIdx >= 0 -> {
                    val encoded = encoder.getOutputBuffer(encIdx)!!
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        encInfo.size = 0
                    }
                    if (encInfo.size > 0 && muxerStarted) {
                        muxer.writeSampleData(muxVideoTrack, encoded, encInfo)
                    }
                    encoder.releaseOutputBuffer(encIdx, false)
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encodeDone = true
                    }
                }
                encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // First encoded data is available: now the muxer tracks
                    // can be created (audio must be added before start()).
                    muxVideoTrack = muxer.addTrack(encoder.outputFormat)
                    if (audioTrackIdx >= 0 && audioFormat != null) {
                        muxAudioTrack = muxer.addTrack(audioFormat)
                    }
                    muxer.start()
                    muxerStarted = true
                }
            }
        }

        // ---- 4. audio passthrough (untouched) ------------------------
        if (muxerStarted && muxAudioTrack >= 0 && audioFormat != null) {
            copyAudioTrack(inputUri, muxer, muxAudioTrack, audioTrackIdx)
        }

        if (muxerStarted) {
            muxer.stop()
        }
        muxer.release()
    }

    /** Copies the audio track sample-by-sample without re-encoding. */
    private fun copyAudioTrack(inputUri: Uri, muxer: MediaMuxer, muxTrack: Int, trackIdx: Int) {
        val audio = MediaExtractor()
        try {
            audio.setDataSource(context, inputUri, null)
            audio.selectTrack(trackIdx)
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buf.clear()
                val n = audio.readSampleData(buf, 0)
                if (n < 0) break
                info.offset = 0
                info.size = n
                info.presentationTimeUs = audio.sampleTime
                info.flags = if (audio.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0
                muxer.writeSampleData(muxTrack, buf, info)
                if (!audio.advance()) break
            }
        } finally {
            runCatching { audio.release() }
        }
    }

    // ------------------------------------------------------------------
    // Frame upscaling (one session for the whole video)
    // ------------------------------------------------------------------

    private class FrameSession(
        val handle: Long,
        val tiles: List<TilePlanner.Tile>,
        val outW: Int,
        val outH: Int,
        val tileBuf: IntArray,
        val out: IntArray,
    )

    private class UpscaleResult(val pixels: IntArray, val width: Int, val height: Int)

    private var cachedSession: FrameSession? = null
    private var sessionModel: ModelType? = null
    private var sessionW = -1
    private var sessionH = -1

    /**
     * Upscales one frame with a cached engine session (created on first
     * frame, reused for all subsequent frames of identical dimensions).
     */
    private fun upscaleFrame(
        argb: IntArray,
        w: Int,
        h: Int,
        model: ModelType,
        decision: SelectBackendUseCase.Decision,
        tileSize: Int,
        cancel: CancelFlag?,
    ): UpscaleResult {

        var session = cachedSession
        if (session == null || sessionModel != model || sessionW != w || sessionH != h) {
            releaseSession()
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            val handle = engine.createSession(model, decision.backend, threads)
            check(handle != 0L) { "Model could not be loaded (backend ${decision.backend})." }
            val plan = planUpscale(
                width = w,
                height = h,
                requestedScale = model.nativeScale,
                nativeModelScale = model.nativeScale,
                backendUsesGpu = decision.backend == com.devfahim.upscaler.domain.model.BackendMode.GPU,
                tileSizeOverride = tileSize,
            )
            session = FrameSession(
                handle = handle,
                tiles = plan.tiles,
                outW = w * model.nativeScale,
                outH = h * model.nativeScale,
                tileBuf = IntArray(plan.tiles.maxOf { it.inW * it.inH }),
                out = IntArray(w * model.nativeScale * h * model.nativeScale),
            )
            cachedSession = session
            sessionModel = model
            sessionW = w
            sessionH = h
        }

        java.util.Arrays.fill(session.out, 0)
        for (tile in session.tiles) {
            if (cancel?.isCancelled == true) {
                throw CancellationException("Video job cancelled")
            }
            TileOps.extractTile(argb, w, tile, session.tileBuf)
            val tileOut = engine.upscaleTile(
                session.handle, session.tileBuf, tile.inW, tile.inH, model.nativeScale,
            ) ?: error("Inference failed on a video frame tile.")
            TileOps.copyBody(tileOut, tile, session.out, session.outW)
        }
        return UpscaleResult(session.out, session.outW, session.outH)
    }

    fun releaseSession() {
        cachedSession?.let { engine.destroySession(it.handle) }
        cachedSession = null
        sessionModel = null
        sessionW = -1
        sessionH = -1
    }

    // ------------------------------------------------------------------
    // Encoder input helpers
    // ------------------------------------------------------------------

    private fun queueEncoderInput(
        encoder: MediaCodec,
        semiPlanar: Boolean,
        y: ByteArray,
        uv: ByteArray,
        u: ByteArray,
        v: ByteArray,
        ptsUs: Long,
    ) {
        val inIdx = encoder.dequeueInputBuffer(-1) // block until available
        if (inIdx < 0) return
        val buf = encoder.getInputBuffer(inIdx)!!
        buf.clear()
        buf.put(y)
        if (semiPlanar) {
            buf.put(uv)
        } else {
            buf.put(u)
            buf.put(v)
        }
        encoder.queueInputBuffer(inIdx, 0, buf.position(), ptsUs, 0)
    }

    private fun queueEncoderEos(encoder: MediaCodec) {
        val inIdx = encoder.dequeueInputBuffer(-1)
        if (inIdx < 0) return
        encoder.queueInputBuffer(
            inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
        )
    }

    private fun bitrateFor(w: Int, h: Int, fps: Float): Int {
        // ~0.12 bits per pixel per frame, clamped to a sane range.
        val bps = (w.toLong() * h.toLong() * fps.toLong() * 0.12).toLong()
        return bps.coerceIn(2_000_000L, 40_000_000L).toInt()
    }

    private fun makeEven(v: Int): Int = if (v % 2 == 0) v else v - 1

    companion object {
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        const val CPU_VIDEO_TILE = 256
        const val GPU_VIDEO_TILE = 512
    }
}
