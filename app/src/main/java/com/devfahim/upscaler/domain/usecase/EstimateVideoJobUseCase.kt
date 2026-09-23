package com.devfahim.upscaler.domain.usecase

import kotlin.math.ceil

/**
 * Estimates video processing time and computes the effective output size
 * under the user-selected resolution cap.
 *
 * Pure Kotlin - unit tested in EstimateVideoJobUseCaseTest.
 */
class EstimateVideoJobUseCase @javax.inject.Inject constructor() {

    data class Estimate(
        val totalFrames: Int,
        val outputWidth: Int,
        val outputHeight: Int,
        /** null when we have no per-frame timing yet. */
        val estimatedSeconds: Long?,
    )

    /**
     * @param width/height/fps source video properties
     * @param requestedScale 2 or 4
     * @param maxOutputHeight user cap (e.g. 1080, 2160); 0 = no cap
     * @param perFrameMs measured or assumed per-frame cost
     */
    operator fun invoke(
        width: Int,
        height: Int,
        fps: Float,
        durationMs: Long,
        requestedScale: Int,
        maxOutputHeight: Int,
        perFrameMs: Double = DEFAULT_PER_FRAME_MS,
    ): Estimate {
        require(width > 0 && height > 0)
        val safeFps = if (fps.isNaN() || fps <= 0f) 30f else fps
        val frames = ceil(durationMs / 1000.0 * safeFps).toInt().coerceAtLeast(1)

        var outW = width * requestedScale
        var outH = height * requestedScale
        if (maxOutputHeight > 0 && outH > maxOutputHeight) {
            val ratio = maxOutputHeight.toDouble() / outH
            outH = maxOutputHeight
            outW = (outW * ratio).toInt()
        }
        // Encoders want even dimensions.
        outW = outW - (outW % 2)
        outH = outH - (outH % 2)

        val seconds = ceil(frames * perFrameMs / 1000.0).toLong()
        return Estimate(
            totalFrames = frames,
            outputWidth = outW,
            outputHeight = outH,
            estimatedSeconds = if (perFrameMs > 0) seconds else null,
        )
    }

    companion object {
        /** Conservative default until real measurements arrive (~0.25 s/frame). */
        const val DEFAULT_PER_FRAME_MS = 250.0
    }
}
