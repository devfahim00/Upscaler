package com.devfahim.upscaler.data.engine

import com.devfahim.upscaler.domain.model.HdrAdjust

/**
 * HDR enhancement curve, applied on top of the HDRNet (Zero-DCE++) network
 * output - pure Kotlin, no Android dependencies, unit tested in
 * [com.devfahim.upscaler.HdrCurveTest].
 *
 * The network ([com.devfahim.upscaler.domain.model.ModelType.HDR_NET]) runs
 * on a 1/12-scaled copy of the photo and produces a low-resolution
 * per-pixel curve parameter r in [-1, 1] (one value per RGB channel). This
 * object
 *
 *  1. bilinearly upsamples that low-res curve map to the photo resolution
 *     (align_corners = true, matching PyTorch's UpsamplingBilinear2d used
 *     by the official Zero-DCE++ pipeline), and
 *  2. applies the 8-iteration enhancement curve to every pixel/channel:
 *
 * ```
 * x <- x + r' * (x^2 - x)          // x in [0,1], 8 iterations
 * ```
 *
 *  where r' = strength * r. At strength 1 this is the original Zero-DCE++
 *  behaviour - which looked great on the low-light images the network was
 *  trained on, but over-brightened normally-exposed photos. The natural
 *  pipeline (see [enhanceNatural]) therefore combines three guards:
 *
 *  * **strength** (default 0.65) weakens the curve itself,
 *  * **auto exposure anchor** ([anchorCorrection]) limits how far the
 *    *global* mean luminance may rise: only +8% on well-exposed photos,
 *    up to +150% on near-black scenes where lifting is the point,
 *  * **highlight protection** ([maskHighlights]) blends the output back
 *    towards the original near white, so bright skies / lamps never clip.
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

    /** Rec.709 luma weights (applied in sRGB space, like the rest of the pipeline). */
    private const val LUMA_R = 0.2126f
    private const val LUMA_G = 0.7152f
    private const val LUMA_B = 0.0722f

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
     * @param strength curve strength in [0, 1]; scales r before iterating
     *                 (1 = the original Zero-DCE++ effect, 0 = identity).
     */
    fun applyCurve(
        argb: IntArray,
        width: Int,
        height: Int,
        curveArgb: IntArray,
        curveW: Int,
        curveH: Int,
        strength: Float = 1f,
    ) {
        require(width > 0 && height > 0) { "invalid image ${width}x$height" }
        require(curveW > 0 && curveH > 0) { "invalid curve map ${curveW}x$curveH" }
        require(argb.size >= width * height) { "argb too small" }
        require(curveArgb.size >= curveW * curveH) { "curve map too small" }
        val s = strength.coerceIn(0f, 1f)

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
                // then decode: r = byte / 255 * 2 - 1 (scaled by strength).
                val rR = s * sampleChannel(p00, p10, p01, p11, 16, w0, w1, u0, u1)
                val rG = s * sampleChannel(p00, p10, p01, p11, 8, w0, w1, u0, u1)
                val rB = s * sampleChannel(p00, p10, p01, p11, 0, w0, w1, u0, u1)

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

    /** Mean Rec.709 luma of the given pixels, in [0, 1]. */
    fun meanLuma(argb: IntArray, pixelCount: Int): Float {
        if (pixelCount <= 0) return 0f
        // Accumulate in double: 50 MP worth of adds, keep the sum exact-ish.
        var sum = 0.0
        for (i in 0 until pixelCount) {
            val px = argb[i]
            sum += LUMA_R * (((px shr 16) and 0xFF) / 255f).toDouble() +
                LUMA_G * (((px shr 8) and 0xFF) / 255f).toDouble() +
                LUMA_B * ((px and 0xFF) / 255f).toDouble()
        }
        return (sum / pixelCount).toFloat()
    }

    /**
     * Auto exposure anchor - the core of the "natural look" fix.
     *
     * Returns a multiplicative correction (<= 1) that pulls the enhanced
     * mean luminance back towards the original when the curve lifted the
     * *global* exposure too far. How much lift is allowed depends on how
     * dark the original was:
     *
     * ```
     * budget  = clamp((0.45 - origMean) * 1.6, 0.08, 1.5)
     * allowed = 1 + budget               // +8% .. +150%
     * ```
     *
     * A well-exposed photo (origMean >= 0.45) may gain at most +8% mean
     * brightness - the HDR punch has to come from local contrast, not from
     * a global exposure shift. A night shot (origMean ~ 0.05) may gain up
     * to +64%; a mean below 0.01 is treated as unstable noise and skipped.
     *
     * @return gain in (0, 1] to apply to the enhanced pixels, or 1 when no
     *         correction is needed.
     */
    fun anchorCorrection(origMean: Float, enhancedMean: Float): Float {
        if (origMean < 0.01f || enhancedMean <= origMean) return 1f
        val budget = ((0.45f - origMean) * 1.6f).coerceIn(0.08f, 1.5f)
        val allowed = 1f + budget
        val actual = enhancedMean / origMean
        return if (actual > allowed) (allowed / actual).coerceIn(0.25f, 1f) else 1f
    }

    /** Multiplies every pixel by [gain] (> 0) and re-quantizes to a byte. */
    fun scaleRgb(argb: IntArray, pixelCount: Int, gain: Float) {
        if (gain >= 0.999999f && gain <= 1.000001f) return
        val g = gain.coerceIn(0.0625f, 16f)
        for (i in 0 until pixelCount) {
            val px = argb[i]
            argb[i] = (0xFF shl 24) or
                (toByte255((((px shr 16) and 0xFF) / 255f) * g) shl 16) or
                (toByte255((((px shr 8) and 0xFF) / 255f) * g) shl 8) or
                toByte255(((px and 0xFF) / 255f) * g)
        }
    }

    /**
     * Highlight protection: for pixels whose ORIGINAL max channel rises
     * above [knee] (smoothstep towards 1.0), blend the enhanced value back
     * towards the original so bright detail never clips.
     *
     * ```
     * mask = 1 - smoothstep(knee, 1, origMaxChannel)
     * out  = orig + (enhanced - orig) * mask
     * ```
     *
     * The mask is per-pixel (same weight for R, G, B) so near-white pixels
     * keep their original color balance. knee >= 1 disables the pass.
     *
     * @param orig original pixels (unmodified)
     * @param out  enhanced pixels; blended result written back in place
     */
    fun maskHighlights(orig: IntArray, out: IntArray, pixelCount: Int, knee: Float) {
        val k = knee.coerceIn(0.6f, 1f)
        if (k >= 1f) return
        for (i in 0 until pixelCount) {
            val po = orig[i]
            val ro = ((po shr 16) and 0xFF) / 255f
            val go = ((po shr 8) and 0xFF) / 255f
            val bo = (po and 0xFF) / 255f
            val t = ((maxOf(ro, go, bo) - k) / (1f - k)).coerceIn(0f, 1f)
            if (t <= 0f) continue // below the knee: keep the full enhancement
            val mask = 1f - t * t * (3f - 2f * t) // 1 -> 0 as we approach white
            if (mask >= 1f) {
                out[i] = po
                continue
            }
            val pe = out[i]
            val re = ((pe shr 16) and 0xFF) / 255f
            val ge = ((pe shr 8) and 0xFF) / 255f
            val be = (pe and 0xFF) / 255f
            out[i] = (0xFF shl 24) or
                (toByte255(ro + (re - ro) * mask) shl 16) or
                (toByte255(go + (ge - go) * mask) shl 8) or
                toByte255(bo + (be - bo) * mask)
        }
    }

    /**
     * Full natural-look enhancement pipeline over raw pixels. [orig] is the
     * untouched input (needed by the anchor + highlight mask), [out] receives
     * the result; both arrays must hold the same width x height pixels.
     *
     * Order: scaled curve -> auto exposure anchor -> highlight protection
     * -> bounded second anchor pass -> advanced adjustments (incl. unsharp
     * sharpness, see [HdrAdjustOps]). Every step short-circuits when neutral.
     *
     * The second anchor pass exists because restoring original highlights
     * can nudge the global mean back over the budget: whatever is still
     * over the allowed lift after the mask is pulled back with a bounded
     * extra gain (at most -15%), so the promised "+8% max on well-exposed
     * photos" holds on the *final* pixels, not just mid-pipeline.
     */
    fun enhanceNatural(
        orig: IntArray,
        out: IntArray,
        width: Int,
        height: Int,
        curveArgb: IntArray,
        curveW: Int,
        curveH: Int,
        adjust: HdrAdjust,
    ) {
        val n = width * height
        applyCurve(out, width, height, curveArgb, curveW, curveH, adjust.strength)

        // 1. Auto exposure anchor: cap the global brightness lift.
        val origMean = meanLuma(orig, n)
        val enhancedMean = meanLuma(out, n)
        val correction = anchorCorrection(origMean, enhancedMean)
        if (correction < 1f) scaleRgb(out, n, correction)

        // 2. Highlight protection against the ORIGINAL pixels.
        maskHighlights(orig, out, n, adjust.highlightKnee)

        // 3. Bounded second anchor pass (see doc above).
        val finalMean = meanLuma(out, n)
        val second = anchorCorrection(origMean, finalMean)
        if (second < 1f) {
            val bounded = (second * 0.85f + 0.15f).coerceIn(0.85f, 1f)
            scaleRgb(out, n, bounded)
        }

        // 4. Advanced user knobs (no-op at defaults; includes sharpness).
        HdrAdjustOps.apply(out, width, height, adjust)
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
    internal fun toByte255(v: Float): Int =
        ((v * 255f + 0.5f).toInt()).coerceIn(0, 255)
}
