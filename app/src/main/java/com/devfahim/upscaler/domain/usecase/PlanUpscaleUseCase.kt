package com.devfahim.upscaler.domain.usecase

import com.devfahim.upscaler.domain.tiling.MemoryGuard
import com.devfahim.upscaler.domain.tiling.TilePlanner

/**
 * Turns "upscale this image with this model" into a concrete, memory-safe
 * execution plan: effective scale, tile geometry and a user-facing notice.
 *
 * Pure Kotlin - unit tested in PlanUpscaleUseCaseTest.
 */
class PlanUpscaleUseCase @javax.inject.Inject constructor() {

    data class Plan(
        val effectiveScale: Int,
        val tiles: List<TilePlanner.Tile>,
        val tileSize: Int,
        val overlap: Int,
        val outputWidth: Int,
        val outputHeight: Int,
        /** Non-null when we had to reduce the scale to stay memory-safe. */
        val scaleReducedNotice: String?,
    )

    operator fun invoke(
        width: Int,
        height: Int,
        requestedScale: Int,
        nativeModelScale: Int,
        backendUsesGpu: Boolean,
        maxOutputPixels: Int = MemoryGuard.MAX_OUTPUT_PIXELS,
        tileSizeOverride: Int? = null,
    ): Plan {
        val (requestedEffective, reduced) =
            MemoryGuard.effectiveScale(width, height, requestedScale, maxOutputPixels)

        // The network always runs at its native scale; a smaller requested
        // scale is achieved by running the model once and downscaling the
        // result (cheaper than running a x4 model twice, and quality loss
        // vs. a true x2 pass is negligible for preview purposes).
        val tileSize = tileSizeOverride ?: MemoryGuard.tileSizeFor(backendUsesGpu)
        val overlap = MemoryGuard.TILE_OVERLAP

        val tiles = TilePlanner.plan(width, height, tileSize, overlap, nativeModelScale)

        val outW = width * requestedEffective
        val outH = height * requestedEffective

        return Plan(
            effectiveScale = requestedEffective,
            tiles = tiles,
            tileSize = tileSize,
            overlap = overlap,
            outputWidth = outW,
            outputHeight = outH,
            scaleReducedNotice = if (reduced) {
                "Resolution limited to ${outW}x$outH to keep memory usage safe on this device."
            } else null,
        )
    }
}
