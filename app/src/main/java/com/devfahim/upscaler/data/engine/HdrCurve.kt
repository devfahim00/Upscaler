package com.devfahim.upscaler.data.engine

/**
 * HDR enhancement curve, applied on top of the HDRNet (Zero-DCE++) network
 * output - pure Kotlin, no Android dependencies, unit tested in
 * [com.devfahim.upscaler.HdrCurveTest].
 *
 * The network ([ModelType.HDR_NET]) runs on a 1/12-scaled copy of the photo
 * and produces a low-resolution per-pixel curve parameter r in [-1, 1]
 * (one value per RGB channel). This object
 *
 *  1. bilinearly upsamples that low-res curve map to the photo resolution
 *     (align_corners = true, matching PyTorch's UpsamplingBilinear2d used
 *     by the official Zero-DCE++ pipeline), and
 *  2. applies the 8-iteration enhancement curve to every pixel/channel:
 *
 * ```
 * x <- x + r * (x^2 - x)          // x in [0,1], 8 iterations
 * ```
 *
 * r > 0 darkens (pulls midtones down), r < 0 brightens; the network was
 * trained to choose r so the result looks well-exposed with an HDR-style
 * punch.
 *
 * The curve map arrives as ARGB pixels from the ncnn bridge: the network
 * output is already encoded as (r + 1) / 2 in [0, 1] and quantized to
 * 8 bits by the bridge (round-half-up, like every other model), so
 * `r = byte / 255 * 2 - 1` (worst-case error 2/255, invisible in the final
 * curve; note byte 128 means r = +0.0039, not exactly neutral).
 */
object HdrCurve {

    /** Curve iterations used by Zero-DCE++ (matches the trained model). */
    const val CURVE_ITERATIONS = 8

    /** The HDRNet network input is the photo downscaled by this factor. */
    const val NET_DOWNSCALE = 12

    /**
     * Low-res width for a photo of [width] px (floor, at least 1) -
     * matches the floor() output size of F.interpolate(scale=1/12) used at
     * conversion-validation time.
     */
    fun lowDim(width: Int): Int = (width / NET_DOWNSCALE).coerceAtLeast(1)

    /**
     * Applies the HDR curve in place.
     *
     * @param argb     photo pixels (width x height), modified in place
     * @param width    photo width
     * @param height   photo height
     * @param curveArgb low-res curve map from HDRNet, as ARGB pixels
     * @param curveW   curve map width
     * @param curveH   curve map height
     */
    fun applyCurve(
        argb: IntArray,
        width: Int,
        height: Int,
        curveArgb: IntArray,
        curveW: Int,
        curveH: Int,
    ) {
        require(width > 0 && height > 0) { "invalid image ${width}x$height" }
        require(curveW > 0 && curveH > 0) { "invalid curve map ${curveW}x$curveH" }
        require(argb.size >= width * height) { "argb too small" }
        require(curveArgb.size >= curveW * curveH) { "curve map too small" }

        val xRatio = if (curveW > 1) (curveW - 1).toFloat() / (width - 1) else 0f
        val yRatio = if (curveH > 1) (curveH - 1).toFloat() / (height - 1) else 0f

        var i = 0
        for (y in 0 until height) {
            val sy = y * yRatio
            val y0 = sy.toInt()
            val y1 = if (y0 + 1 < curveH) y0 + 1 else curveH - 1
            val fy = sy - y0
            val row0 = y0 * curveW
            val row1 = y1 * curveW
            val w0 = 1f - fy
            val w1 = fy

            for (x in 0 until width) {
                val sx = x * xRatio
                val x0 = sx.toInt()
                val x1 = if (x0 + 1 < curveW) x0 + 1 else curveW - 1
                val fx = sx - x0
                val u0 = 1f - fx
                val u1 = fx

                val p00 = curveArgb[row0 + x0]
                val p10 = curveArgb[row0 + x1]
                val p01 = curveArgb[row1 + x0]
                val p11 = curveArgb[row1 + x1]

                // Bilinear sample of the encoded curve byte per channel,
                // then decode: r = byte / 255 * 2 - 1.
                val rR = sampleChannel(p00, p10, p01, p11, 16, w0, w1, u0, u1)
                val rG = sampleChannel(p00, p10, p01, p11, 8, w0, w1, u0, u1)
                val rB = sampleChannel(p00, p10, p01, p11, 0, w0, w1, u0, u1)

                val px = argb[i]
                var r = ((px shr 16) and 0xFF) / 255f
                var g = ((px shr 8) and 0xFF) / 255f
                var b = (px and 0xFF) / 255f

                repeat(CURVE_ITERATIONS) {
                    r += rR * (r * r - r)
                    g += rG * (g * g - g)
                    b += rB * (b * b - b)
                }

                argb[i] =
                    (0xFF shl 24) or
                        (toByte255(r) shl 16) or
                        (toByte255(g) shl 8) or
                        toByte255(b)
                i++
            }
        }
    }

    /**
     * Bilinear interpolation of one ARGB channel across the four
     * neighbouring curve-map pixels, decoded to r in [-1, 1].
     */
    private fun sampleChannel(
        p00: Int, p10: Int, p01: Int, p11: Int,
        shift: Int,
        w0: Float, w1: Float, u0: Float, u1: Float,
    ): Float {
        val v =
            ((p00 shr shift) and 0xFF) * (w0 * u0) +
                ((p10 shr shift) and 0xFF) * (w0 * u1) +
                ((p01 shr shift) and 0xFF) * (w1 * u0) +
                ((p11 shr shift) and 0xFF) * (w1 * u1)
        return v / 255f * 2f - 1f
    }

    /** Round-half-up to [0, 255], matching the native clamp_u8. */
    private fun toByte255(v: Float): Int =
        ((v * 255f + 0.5f).toInt()).coerceIn(0, 255)
}
