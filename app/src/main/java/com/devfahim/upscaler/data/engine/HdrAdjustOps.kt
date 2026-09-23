package com.devfahim.upscaler.data.engine

import com.devfahim.upscaler.domain.model.HdrAdjust
import kotlin.math.pow

/**
 * Advanced per-pixel HDR adjustments - the "for advanced users" section of
 * the photo options sheet. Pure Kotlin, no Android dependencies, unit
 * tested against numpy vectors in [com.devfahim.upscaler.HdrAdjustOpsTest].
 *
 * Applied AFTER the Zero-DCE++ curve + anchor + highlight protection (see
 * [HdrCurve.enhanceNatural]), in a fixed order per channel value x in [0, 1]:
 *
 * ```
 * 1. exposure    x *= 2^v                 (multiplicative, EV)
 * 2. brightness  x += 0.25 * v            (additive)
 *                clamp to [0, 1]
 * 3. gamma       x = x ^ (2^(-1.2 v))     (positive v = brighter midtones)
 * 4. contrast    x = (x - 0.5) * 2^v + 0.5
 *                clamp to [0, 1]
 * 5. temperature R *= (1 + 0.15 v), B *= (1 - 0.15 v)
 * 6. tint        R,B *= (1 + 0.12 v), G *= (1 - 0.12 v)
 *                clamp to [0, 1]
 * 7. saturation  L = 0.2126 R + 0.7152 G + 0.0722 B
 *                x  = L + (x - L) * (1 + v)
 *                clamp to [0, 1]
 * ```
 *
 * [sharpness] is a separate 3x3 unsharp mask ([unsharp]) with a
 * [1 2 1; 2 4 2; 1 2 1]/16 blur, replicate edge handling and
 * `out = x + 1.5 * amount * (x - blur)`.
 *
 * Everything is a no-op at the neutral defaults (v = 0), and the whole pass
 * short-circuits via [HdrAdjust.advancedNeutral].
 */
object HdrAdjustOps {

    /** Rec.709 luma weights - matches [HdrCurve.meanLuma]. */
    private const val LUMA_R = 0.2126f
    private const val LUMA_G = 0.7152f
    private const val LUMA_B = 0.0722f

    /** Additive brightness range: v in [-1, 1] maps to +/- 0.25. */
    private const val BRIGHTNESS_RANGE = 0.25f

    /** Temperature channel gain range. */
    private const val TEMP_RANGE = 0.15f

    /** Tint channel gain range. */
    private const val TINT_RANGE = 0.12f

    /**
     * Applies all advanced adjustments (everything except [HdrAdjust.strength]
     * and [HdrAdjust.highlightKnee], which are consumed by the curve stage).
     * No-op when [HdrAdjust.advancedNeutral].
     */
    fun apply(argb: IntArray, width: Int, height: Int, a: HdrAdjust) {
        if (a.advancedNeutral) return

        // pow(2f, v) == 2^v (kotlin.math has no exp2 in this Kotlin version).
        val expGain = if (a.exposure != 0f) pow(2f, a.exposure) else 1f
        val bright = a.brightness * BRIGHTNESS_RANGE
        val contrastF = if (a.contrast != 0f) pow(2f, a.contrast) else 1f
        val gammaV = if (a.gamma != 0f) pow(2f, -1.2f * a.gamma) else 1f
        val tempR = 1f + a.temperature * TEMP_RANGE
        val tempB = 1f - a.temperature * TEMP_RANGE
        val tintRB = 1f + a.tint * TINT_RANGE
        val tintG = 1f - a.tint * TINT_RANGE
        val satF = 1f + a.saturation
        val n = width * height
        require(argb.size >= n) { "argb too small" }

        for (i in 0 until n) {
            val px = argb[i]
            var r = ((px shr 16) and 0xFF) / 255f
            var g = ((px shr 8) and 0xFF) / 255f
            var b = (px and 0xFF) / 255f

            // 1. exposure (multiplicative) + 2. brightness (additive)
            r = r * expGain + bright
            g = g * expGain + bright
            b = b * expGain + bright
            // clamp BEFORE gamma: pow of a negative base is NaN.
            r = r.coerceIn(0f, 1f)
            g = g.coerceIn(0f, 1f)
            b = b.coerceIn(0f, 1f)

            // 3. gamma
            if (gammaV != 1f) {
                r = r.pow(gammaV)
                g = g.pow(gammaV)
                b = b.pow(gammaV)
            }

            // 4. contrast around mid-gray
            if (contrastF != 1f) {
                r = ((r - 0.5f) * contrastF + 0.5f).coerceIn(0f, 1f)
                g = ((g - 0.5f) * contrastF + 0.5f).coerceIn(0f, 1f)
                b = ((b - 0.5f) * contrastF + 0.5f).coerceIn(0f, 1f)
            }

            // 5. temperature (warm/cool) + 6. tint (green/magenta)
            if (a.temperature != 0f) {
                r *= tempR
                b *= tempB
            }
            if (a.tint != 0f) {
                r *= tintRB
                g *= tintG
                b *= tintRB
            }
            r = r.coerceIn(0f, 1f)
            g = g.coerceIn(0f, 1f)
            b = b.coerceIn(0f, 1f)

            // 7. saturation (Rec.709 luma mix, always last so white
            // balance shifts above do not get desaturated)
            if (satF != 1f) {
                val l = LUMA_R * r + LUMA_G * g + LUMA_B * b
                r = (l + (r - l) * satF).coerceIn(0f, 1f)
                g = (l + (g - l) * satF).coerceIn(0f, 1f)
                b = (l + (b - l) * satF).coerceIn(0f, 1f)
            }

            argb[i] = (0xFF shl 24) or
                (HdrCurve.toByte255(r) shl 16) or
                (HdrCurve.toByte255(g) shl 8) or
                HdrCurve.toByte255(b)
        }

        // Sharpness runs on the adjusted pixels (separate spatial pass).
        if (a.sharpness > 0f) unsharp(argb, width, height, a.sharpness)
    }

    /**
     * In-place 3x3 unsharp mask. Kernel [1 2 1; 2 4 2; 1 2 1] / 16, edges
     * replicated (clamp-to-edge), `out = x + k * (x - blur)` with
     * `k = 1.5 * amount`. Allocation-free inside the pixel loop.
     */
    fun unsharp(argb: IntArray, width: Int, height: Int, amount: Float) {
        require(width > 0 && height > 0) { "invalid image ${width}x$height" }
        require(argb.size >= width * height) { "argb too small" }
        val k = 1.5f * amount.coerceIn(0f, 1f)
        if (k <= 0f) return

        val src = argb.copyOf()
        for (y in 0 until height) {
            val rowU = (y - 1).coerceAtLeast(0) * width
            val rowM = y * width
            val rowD = (y + 1).coerceAtMost(height - 1) * width
            for (x in 0 until width) {
                val xL = (x - 1).coerceAtLeast(0)
                val xR = (x + 1).coerceAtMost(width - 1)

                // 3x3 neighbourhood, weights [1 2 1; 2 4 2; 1 2 1] / 16,
                // fully unrolled (no per-pixel allocations).
                val pUL = src[rowU + xL]; val pUM = src[rowU + x]; val pUR = src[rowU + xR]
                val pML = src[rowM + xL]; val pMM = src[rowM + x]; val pMR = src[rowM + xR]
                val pDL = src[rowD + xL]; val pDM = src[rowD + x]; val pDR = src[rowD + xR]

                val r = (
                    ch(pUL, 16) + 2 * ch(pUM, 16) + ch(pUR, 16) +
                        2 * ch(pML, 16) + 4 * ch(pMM, 16) + 2 * ch(pMR, 16) +
                        ch(pDL, 16) + 2 * ch(pDM, 16) + ch(pDR, 16)
                    ) / 16f
                val g = (
                    ch(pUL, 8) + 2 * ch(pUM, 8) + ch(pUR, 8) +
                        2 * ch(pML, 8) + 4 * ch(pMM, 8) + 2 * ch(pMR, 8) +
                        ch(pDL, 8) + 2 * ch(pDM, 8) + ch(pDR, 8)
                    ) / 16f
                val b = (
                    ch(pUL, 0) + 2 * ch(pUM, 0) + ch(pUR, 0) +
                        2 * ch(pML, 0) + 4 * ch(pMM, 0) + 2 * ch(pMR, 0) +
                        ch(pDL, 0) + 2 * ch(pDM, 0) + ch(pDR, 0)
                    ) / 16f

                argb[rowM + x] = (0xFF shl 24) or
                    (HdrCurve.toByte255(ch(pMM, 16) + k * (ch(pMM, 16) - r)) shl 16) or
                    (HdrCurve.toByte255(ch(pMM, 8) + k * (ch(pMM, 8) - g)) shl 8) or
                    HdrCurve.toByte255(ch(pMM, 0) + k * (ch(pMM, 0) - b))
            }
        }
    }

    /** One ARGB channel as a float in [0, 1]. */
    private fun ch(px: Int, shift: Int): Float = ((px shr shift) and 0xFF) / 255f
}
