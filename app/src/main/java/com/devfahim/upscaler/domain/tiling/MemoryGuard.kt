package com.devfahim.upscaler.domain.tiling

/**
 * Keeps upscale jobs inside a safe memory envelope.
 *
 * The dominant allocations while processing a photo are:
 *  1. the output pixel array + output Bitmap (4 bytes per pixel each), and
 *  2. per-tile working memory (input pixels, ncnn feature maps).
 *
 * (1) is bounded by capping the total *output* pixels: when the requested
 * scale would exceed the cap we transparently reduce the effective scale and
 * surface a notice. (2) is bounded by choosing a conservative tile size for
 * the active backend.
 *
 * Pure Kotlin - unit tested in MemoryGuardTest.
 */
object MemoryGuard {

    /** Max output pixels for a single photo (16 MP -> ~64 MB per buffer). */
    const val MAX_OUTPUT_PIXELS: Int = 16_000_000

    /** Overlap (input px) between tiles; hides patch-border artifacts. */
    const val TILE_OVERLAP: Int = 16

    /** Default tile body edge when running on the CPU backend. */
    const val CPU_TILE_SIZE: Int = 128

    /** Default tile body edge when running on the Vulkan GPU backend. */
    const val GPU_TILE_SIZE: Int = 256

    /**
     * Chooses the effective scale so the output stays within budget.
     *
     * @return the effective scale (never larger than [requestedScale]) and
     *         whether it had to be reduced.
     */
    fun effectiveScale(
        width: Int,
        height: Int,
        requestedScale: Int,
        maxOutputPixels: Int = MAX_OUTPUT_PIXELS,
    ): Pair<Int, Boolean> {
        require(width > 0 && height > 0)
        val requestedPixels = width.toLong() * height.toLong() * requestedScale.toLong() * requestedScale.toLong()
        if (requestedPixels <= maxOutputPixels) return requestedScale to false

        // Try every smaller integer scale, prefer the largest that fits.
        var effective = 1
        for (s in requestedScale downTo 1) {
            val px = width.toLong() * height.toLong() * s.toLong() * s.toLong()
            if (px <= maxOutputPixels) {
                effective = s
                break
            }
        }
        return effective to (effective != requestedScale)
    }

    /** Tile body edge appropriate for the backend and output size. */
    fun tileSizeFor(backendUsesGpu: Boolean): Int =
        if (backendUsesGpu) GPU_TILE_SIZE else CPU_TILE_SIZE

    /** Rough estimate of peak extra memory (bytes) for tile processing. */
    fun estimateTileWorkingBytes(tileSize: Int, scale: Int): Long {
        val inPx = tileSize.toLong() + 2L * TILE_OVERLAP
        val outPx = inPx * scale
        // input ARGB + input float planes (3) + output float planes (3)
        // + output ARGB + a few intermediate ncnn blobs (64ch feature maps).
        val featureBlob = inPx * inPx * 64L * 4L
        return inPx * inPx * 4L + inPx * inPx * 3L * 4L +
                outPx * outPx * 3L * 4L + outPx * outPx * 4L +
                featureBlob * 2L
    }
}
