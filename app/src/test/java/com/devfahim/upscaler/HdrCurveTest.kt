package com.devfahim.upscaler

import com.devfahim.upscaler.data.engine.HdrCurve
import com.devfahim.upscaler.domain.model.HdrAdjust
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

    // -----------------------------------------------------------------
    // Natural-look pipeline: strength, anchor, highlight protection.
    // Vectors generated with scripts/gen_hdradjust_vectors.py (numpy).
    // -----------------------------------------------------------------

    @Test
    fun `strength zero leaves every pixel untouched`() {
        val img = intArrayOf(gray(30), gray(90), gray(160), gray(230))
        val curve = IntArray(1) { curvePixel(100, 100, 100) }
        HdrCurve.applyCurve(img, 2, 2, curve, 1, 1, strength = 0f)
        assertEquals(gray(30), img[0])
        assertEquals(gray(90), img[1])
        assertEquals(gray(160), img[2])
        assertEquals(gray(230), img[3])
    }

    @Test
    fun `half strength weakens the curve between identity and full effect`() {
        // Curve byte 100 (r = -0.2157, brightens) over a 2x2 mixed image.
        val img = intArrayOf(gray(30), gray(90), gray(160), gray(230))
        val curve = IntArray(1) { curvePixel(100, 100, 100) }
        HdrCurve.applyCurve(img, 2, 2, curve, 1, 1, strength = 0.5f)
        // numpy: 30 -> 60, 90 -> 143, 160 -> 205, 230 -> 244
        assertEquals(gray(60), img[0])
        assertEquals(gray(143), img[1])
        assertEquals(gray(205), img[2])
        assertEquals(gray(244), img[3])
    }

    @Test
    fun `half strength stays between identity and full curve`() {
        val base = intArrayOf(gray(30), gray(90), gray(160), gray(230))
        val curve = IntArray(1) { curvePixel(100, 100, 100) } // r < 0: brightens

        val half = base.copyOf().also {
            HdrCurve.applyCurve(it, 2, 2, curve, 1, 1, strength = 0.5f)
        }
        val full = base.copyOf().also {
            HdrCurve.applyCurve(it, 2, 2, curve, 1, 1, strength = 1f)
        }
        for (i in base.indices) {
            val b = (base[i] shr 16) and 0xFF
            val h = (half[i] shr 16) and 0xFF
            val f = (full[i] shr 16) and 0xFF
            assertTrue("half $h not between $b and $f", h in b..f)
        }
    }

    @Test
    fun `mean luma matches the numpy reference`() {
        fun px(r: Int, g: Int, b: Int) = 0xFF shl 24 or (r shl 16) or (g shl 8) or b
        val img = intArrayOf(px(60, 60, 60), px(128, 128, 128), px(250, 10, 5), px(0, 255, 0), px(17, 200, 99))
        assertEquals(0.4587f, HdrCurve.meanLuma(img, img.size), 1e-3f)
    }

    @Test
    fun `anchor correction allows little lift on well-exposed photos`() {
        // Dark/unstable means are skipped entirely.
        assertEquals(1f, HdrCurve.anchorCorrection(0.005f, 0.02f), 1e-6f)
        // Dark photo, gain 2.0 -> allowed 1.64 -> correction 0.82.
        assertEquals(0.82f, HdrCurve.anchorCorrection(0.05f, 0.10f), 1e-4f)
        // Dark photo, gain 1.4 within budget -> untouched.
        assertEquals(1f, HdrCurve.anchorCorrection(0.05f, 0.07f), 1e-6f)
        // Mid photo, gain 1.3 -> allowed 1.24 -> correction ~0.9538.
        assertEquals(0.9538f, HdrCurve.anchorCorrection(0.30f, 0.39f), 1e-3f)
        // Well-exposed, gain 1.111 -> allowed 1.08 -> correction ~0.972.
        assertEquals(0.972f, HdrCurve.anchorCorrection(0.45f, 0.50f), 1e-3f)
        // Well-exposed, gain 1.04 within the +8% budget -> untouched.
        assertEquals(1f, HdrCurve.anchorCorrection(0.50f, 0.52f), 1e-6f)
        // Darkened output is never "corrected" back up.
        assertEquals(1f, HdrCurve.anchorCorrection(0.60f, 0.50f), 1e-6f)
    }

    @Test
    fun `scale rgb multiplies and clamps`() {
        fun px(r: Int, g: Int, b: Int) = 0xFF shl 24 or (r shl 16) or (g shl 8) or b
        val img = intArrayOf(px(100, 150, 200), px(250, 128, 10))
        HdrCurve.scaleRgb(img, 2, 0.5f)
        // 100->50, 150->75, 200->100; 250->125, 128->64, 10->5.
        assertEquals(px(50, 75, 100), img[0])
        assertEquals(px(125, 64, 5), img[1])
    }

    @Test
    fun `highlight mask keeps near-white pixels at their original value`() {
        fun px(r: Int, g: Int, b: Int) = 0xFF shl 24 or (r shl 16) or (g shl 8) or b
        val orig = intArrayOf(
            px(100, 100, 100), px(200, 210, 190), px(220, 220, 220),
            px(250, 250, 250), px(255, 255, 255), px(204, 204, 204),
        )
        val out = intArrayOf(
            px(130, 125, 120), px(230, 235, 210), px(250, 245, 240),
            px(255, 255, 255), px(255, 255, 255), px(234, 229, 224),
        )
        HdrCurve.maskHighlights(orig, out, 6, knee = 0.8f)

        // Below the knee: full enhancement survives.
        assertEquals(px(130, 125, 120), out[0])
        // Exactly at the knee (204/255 = 0.8): still fully enhanced.
        assertEquals(px(234, 229, 224), out[5])
        // Just above: partially blended (numpy: 229,234,209 / 243,239,235).
        fun ch(p: Int, s: Int) = (p shr s) and 0xFF
        assertTrue(ch(out[1], 16) in 228..230)
        assertTrue(ch(out[1], 8) in 233..235)
        assertTrue(ch(out[1], 0) in 208..210)
        assertTrue(ch(out[2], 16) in 242..244)
        assertTrue(ch(out[2], 8) in 238..240)
        assertTrue(ch(out[2], 0) in 234..236)
        // At/near white: original wins.
        assertEquals(px(250, 250, 250), out[3])
        assertEquals(px(255, 255, 255), out[4])
    }

    @Test
    fun `highlight mask with knee 1 is a no-op`() {
        fun px(r: Int, g: Int, b: Int) = 0xFF shl 24 or (r shl 16) or (g shl 8) or b
        val orig = intArrayOf(px(255, 255, 255))
        val out = intArrayOf(px(200, 200, 200))
        HdrCurve.maskHighlights(orig, out, 1, knee = 1f)
        assertEquals(px(200, 200, 200), out[0])
    }

    // -----------------------------------------------------------------
    // End-to-end natural pipeline (curve -> anchor -> mask -> bounded
    // second anchor). Traced with the numpy replica; +/-1 guards the
    // float32-vs-float64 rounding of the anchor gains.
    // -----------------------------------------------------------------

    @Test
    fun `enhanceNatural keeps a well-exposed photo inside the natural budget`() {
        val orig = intArrayOf(
            gray(90), gray(110), gray(130), gray(150),
            gray(170), gray(190), gray(230), gray(250),
        )
        val out = orig.copyOf()
        val curve = IntArray(1) { curvePixel(100, 100, 100) } // strong brighten

        HdrCurve.enhanceNatural(orig, out, 8, 1, curve, 1, 1, HdrAdjust())

        // numpy: 90->131, 110->146, 130->160, 150->171, 170->181,
        //        190->189, 230->213, 250->242
        val expected = intArrayOf(131, 146, 160, 171, 181, 189, 213, 242)
        for (i in expected.indices) {
            val v = (out[i] shr 16) and 0xFF
            assertTrue("px $i: $v not in ${expected[i] - 1}..${expected[i] + 1}",
                v in (expected[i] - 1)..(expected[i] + 1))
        }
        // The *global* mean stays within the +8% budget (+ slack).
        val lift = HdrCurve.meanLuma(out, 8) / HdrCurve.meanLuma(orig, 8)
        assertTrue("mean lift $lift over budget", lift <= 1.12f)
        // Nothing clips to white.
        for (i in out.indices) {
            assertTrue("px $i clipped", ((out[i] shr 16) and 0xFF) < 255)
        }
    }

    @Test
    fun `enhanceNatural still lifts dark scenes strongly`() {
        val orig = intArrayOf(gray(15), gray(25), gray(35), gray(45))
        val out = orig.copyOf()
        val curve = IntArray(1) { curvePixel(100, 100, 100) }

        HdrCurve.enhanceNatural(orig, out, 4, 1, curve, 1, 1, HdrAdjust())

        // numpy: 15->33, 25->52, 35->69, 45->84 (dark budget allows ~+53%).
        val expected = intArrayOf(33, 52, 69, 84)
        for (i in expected.indices) {
            val v = (out[i] shr 16) and 0xFF
            assertTrue("px $i: $v not in ${expected[i] - 1}..${expected[i] + 1}",
                v in (expected[i] - 1)..(expected[i] + 1))
        }
        val lift = HdrCurve.meanLuma(out, 4) / HdrCurve.meanLuma(orig, 4)
        assertTrue("dark lift $lift too weak", lift >= 1.3f)
    }
}
