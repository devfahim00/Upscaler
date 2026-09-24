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
     * Tile body edge for the full RRDBNet "High Quality" photo model
     * (RealESRGAN_x4plus) on the CPU backend. RRDBNet keeps ~140 feature
     * maps alive per tile (dense blocks + per-block outputs), so it needs
     * much smaller tiles than the compact SRVGG networks to stay inside a
     * low-RAM budget.
     */
    const val HQ_CPU_TILE_SIZE: Int = 64

    /** Tile body edge for the HQ (RRDBNet) model on the Vulkan GPU backend. */
    const val HQ_GPU_TILE_SIZE: Int = 96

    /**
     * Tile body edge for the RealPLSKR photo model (4x-purephoto-realplksr)
     * on the CPU backend. Its blocks are sequential (no dense growth), but
     * the 17x17 large kernels plus the attention branch keep a handful of
     * 64-channel maps alive per tile - mid-size tiles, between the compact
     * and RRDBNet budgets.
     */
    const val PLKSR_CPU_TILE_SIZE: Int = 96

    /** Tile body edge for the RealPLSKR model on the Vulkan GPU backend. */
    const val PLKSR_GPU_TILE_SIZE: Int = 128

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

    /**
     * Shrinks (width, height) so the model's *native*-scale pass over it
     * stays within [maxOutputPixels].
     *
     * The native-scale output buffer (input dims x nativeScale, in both
     * directions) is always allocated in full before any later downscale to
     * the requested resolution - it's the single biggest allocation in the
     * pipeline, and the one [effectiveScale] alone doesn't bound, since that
     * only caps the *final* output. Large photos run
     * through a x4 model can demand a 500MB+ buffer here and OOM. Callers
     * should resample their source to the returned size before upscaling.
     */
    fun capInputForNativeScale(
        width: Int,
        height: Int,
        nativeScale: Int,
        maxOutputPixels: Int = MAX_OUTPUT_PIXELS,
    ): Pair<Int, Int> {
        require(width > 0 && height > 0 && nativeScale > 0)
        val nativePixels = width.toLong() * height.toLong() * nativeScale.toLong() * nativeScale.toLong()
        if (nativePixels <= maxOutputPixels) return width to height
        val factor = kotlin.math.sqrt(maxOutputPixels.toDouble() / nativePixels.toDouble())
        val w = (width * factor).toInt().coerceAtLeast(8)
        val h = (height * factor).toInt().coerceAtLeast(8)
        return w to h
    }

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
