package com.devfahim.upscaler

import com.devfahim.upscaler.data.engine.HdrCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectors for these tests were generated and cross-checked with numpy
 * against the official Zero-DCE++ reference pipeline (see
 * scripts/gen_hdrcurve_vectors.py in the project workspace); the curve
 * math itself is emulated there in float32 to match Kotlin arithmetic.
 */
class HdrCurveTest {

    private fun gray(v: Int) = 0xFF shl 24 or (v shl 16) or (v shl 8) or v

    private fun curvePixel(r: Int, g: Int, b: Int) =
        0xFF shl 24 or (r shl 16) or (g shl 8) or b

    @Test
    fun `low dims floor divide with a minimum of one`() {
        assertEquals(42, HdrCurve.lowDim(504))
        assertEquals(1, HdrCurve.lowDim(12))
        assertEquals(1, HdrCurve.lowDim(13))
        assertEquals(2, HdrCurve.lowDim(24))
        assertEquals(1, HdrCurve.lowDim(1))
    }

    @Test
    fun `same-size curve map applies its exact r per pixel`() {
        // Curve byte 200 (all channels) -> r = +0.5686: darkens midtones.
        val img = intArrayOf(gray(60), gray(128), gray(250))
        val curve = IntArray(3) { curvePixel(200, 200, 200) }
        HdrCurve.applyCurve(img, 3, 1, curve, 3, 1)
        // numpy: 60 -> 0, 128 -> 1, 250 -> 132
        assertEquals(gray(0), img[0])
        assertEquals(gray(1), img[1])
        assertEquals(gray(132), img[2])
    }

    @Test
    fun `single-pixel curve map spreads one r over the whole image`() {
        // Curve byte 100 (all channels) -> r = -0.2157: brightens.
        val img = intArrayOf(gray(30), gray(90), gray(160), gray(230))
        val curve = intArrayOf(curvePixel(100, 100, 100))
        HdrCurve.applyCurve(img, 2, 2, curve, 1, 1)
        // numpy: 30 -> 104, 90 -> 193, 160 -> 233, 230 -> 251
        assertEquals(gray(104), img[0])
        assertEquals(gray(193), img[1])
        assertEquals(gray(233), img[2])
        assertEquals(gray(251), img[3])
    }

    @Test
    fun `bilinear upsampling of the curve map matches reference vectors`() {
        // 2x2 curve map (per-channel planes R=[[10,30],[50,70]],
        // G=[[0,64],[128,255]], B=[[255,200],[100,50]]) over a uniform
        // gray-128 4x4 image.
        val curve = intArrayOf(
            curvePixel(10, 0, 255), curvePixel(30, 64, 200),
            curvePixel(50, 128, 100), curvePixel(70, 255, 50),
        )
        val img = IntArray(16) { gray(128) }
        HdrCurve.applyCurve(img, 4, 4, curve, 2, 2)

        fun px(r: Int, g: Int, b: Int) = 0xFF shl 24 or (r shl 16) or (g shl 8) or b

        // numpy reference (see gen_hdrcurve_vectors.py); +/-1 guards the
        // float32-vs-float64 rounding of the last bit.
        val expected = arrayOf(
            intArrayOf(255, 255, 0), intArrayOf(255, 255, 0), intArrayOf(255, 255, 0), intArrayOf(255, 253, 1),
            intArrayOf(255, 255, 0), intArrayOf(255, 251, 3), intArrayOf(255, 221, 15), intArrayOf(255, 127, 49),
            intArrayOf(255, 242, 44), intArrayOf(255, 155, 101), intArrayOf(255, 34, 168), intArrayOf(254, 2, 219),
            intArrayOf(255, 126, 219), intArrayOf(254, 13, 244), intArrayOf(253, 0, 253), intArrayOf(252, 0, 255),
        )
        for (i in 0 until 16) {
            val out = img[i]
            val er = expected[i][0]
            val eg = expected[i][1]
            val eb = expected[i][2]
            val r = (out shr 16) and 0xFF
            val g = (out shr 8) and 0xFF
            val b = out and 0xFF
            assertTrue("R at $i: $r not in ${er - 1}..${er + 1}", r in (er - 1)..(er + 1))
            assertTrue("G at $i: $g not in ${eg - 1}..${eg + 1}", g in (eg - 1)..(eg + 1))
            assertTrue("B at $i: $b not in ${eb - 1}..${eb + 1}", b in (eb - 1)..(eb + 1))
        }
    }

    @Test
    fun `curve map larger than the image is bilinearly downsampled`() {
        // 3x1 curve map over a 2x1 image: align_corners samples land exactly
        // on curve pixels 0 and 2 -> bytes 100 and 250.
        val img = intArrayOf(gray(128), gray(128))
        val curve = intArrayOf(
            curvePixel(100, 100, 100),
            curvePixel(200, 200, 200),
            curvePixel(250, 250, 250),
        )
        HdrCurve.applyCurve(img, 2, 1, curve, 3, 1)
        // numpy: byte 100 (r=-0.2157) -> 219; byte 250 (r=+0.9608) -> 0
        assertEquals(gray(219), img[0])
        assertEquals(gray(0), img[1])
    }

    @Test
    fun `curve keeps pixels inside the unit interval for any r`() {
        // r = +1 everywhere (byte 255): each iteration maps x -> x^2, so
        // 8 iterations give x^256 - always inside [0, 1].
        val img = intArrayOf(gray(60), gray(128), gray(255))
        val curve = IntArray(3) { curvePixel(255, 255, 255) }
        HdrCurve.applyCurve(img, 3, 1, curve, 3, 1)
        // numpy: 60 -> 0, 128 -> 0, 255 -> 255
        assertEquals(gray(0), img[0])
        assertEquals(gray(0), img[1])
        assertEquals(gray(255), img[2])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mismatched curve map size is rejected`() {
        HdrCurve.applyCurve(
            IntArray(4), 2, 2,
            IntArray(1), 1, 2,
        )
    }
}
