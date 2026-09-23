package com.devfahim.upscaler

import com.devfahim.upscaler.data.engine.HdrAdjustOps
import com.devfahim.upscaler.domain.model.HdrAdjust
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectors for these tests were generated with numpy (see
 * scripts/gen_hdradjust_vectors.py in the project workspace); the Kotlin
 * math is emulated there in float64 while the app runs float32, so channel
 * assertions use +/-1 guards (the previous convention of HdrCurveTest).
 */
class HdrAdjustOpsTest {

    private fun px(r: Int, g: Int, b: Int) = 0xFF shl 24 or (r shl 16) or (g shl 8) or b

    private fun ch(p: Int, shift: Int) = (p shr shift) and 0xFF

    /** Runs one adjustment over a single (100, 150, 200) pixel and returns RGB. */
    private fun apply1(adj: HdrAdjust): IntArray {
        val img = intArrayOf(px(100, 150, 200))
        HdrAdjustOps.apply(img, 1, 1, adj)
        return intArrayOf(ch(img[0], 16), ch(img[0], 8), ch(img[0], 0))
    }

    private fun assertClose(expected: IntArray, actual: IntArray, what: String) {
        for (i in expected.indices) {
            assertTrue(
                "$what channel $i: ${actual[i]} not in ${expected[i] - 1}..${expected[i] + 1}",
                actual[i] in (expected[i] - 1)..(expected[i] + 1),
            )
        }
    }

    @Test
    fun `neutral adjust is an exact identity`() {
        val img = intArrayOf(px(100, 150, 200), px(0, 255, 13), px(255, 255, 255))
        HdrAdjustOps.apply(img, 3, 1, HdrAdjust())
        assertArrayEquals(
            intArrayOf(px(100, 150, 200), px(0, 255, 13), px(255, 255, 255)),
            img,
        )
    }

    @Test
    fun `exposure is multiplicative in ev`() {
        // numpy: +0.5 EV -> (141, 212, 255); -0.5 EV -> (71, 106, 141)
        assertClose(intArrayOf(141, 212, 255), apply1(HdrAdjust(exposure = 0.5f)), "exposure+")
        assertClose(intArrayOf(71, 106, 141), apply1(HdrAdjust(exposure = -0.5f)), "exposure-")
    }

    @Test
    fun `brightness is additive`() {
        // numpy: +0.5 -> (132, 182, 232); -1.0 -> (36, 86, 136)
        assertClose(intArrayOf(132, 182, 232), apply1(HdrAdjust(brightness = 0.5f)), "brightness+")
        assertClose(intArrayOf(36, 86, 136), apply1(HdrAdjust(brightness = -1f)), "brightness-")
    }

    @Test
    fun `contrast pivots around mid gray`() {
        // numpy: +0.6 -> (86, 162, 237); -0.6 -> (109, 142, 175)
        assertClose(intArrayOf(86, 162, 237), apply1(HdrAdjust(contrast = 0.6f)), "contrast+")
        assertClose(intArrayOf(109, 142, 175), apply1(HdrAdjust(contrast = -0.6f)), "contrast-")
    }

    @Test
    fun `gamma moves midtones in both directions`() {
        // numpy: +1.0 -> (170, 202, 229); -1.0 -> (30, 75, 146)
        assertClose(intArrayOf(170, 202, 229), apply1(HdrAdjust(gamma = 1f)), "gamma+")
        assertClose(intArrayOf(30, 75, 146), apply1(HdrAdjust(gamma = -1f)), "gamma-")
    }

    @Test
    fun `saturation scales distance from luma`() {
        // numpy: +0.8 -> (66, 156, 246); -1.0 -> (143, 143, 143) grayscale
        assertClose(intArrayOf(66, 156, 246), apply1(HdrAdjust(saturation = 0.8f)), "sat+")
        assertClose(intArrayOf(143, 143, 143), apply1(HdrAdjust(saturation = -1f)), "sat-")
    }

    @Test
    fun `temperature shifts warm and cool`() {
        // numpy: +1.0 (warm) -> (115, 150, 170); -1.0 (cool) -> (85, 150, 230)
        assertClose(intArrayOf(115, 150, 170), apply1(HdrAdjust(temperature = 1f)), "temp+")
        assertClose(intArrayOf(85, 150, 230), apply1(HdrAdjust(temperature = -1f)), "temp-")
    }

    @Test
    fun `tint shifts magenta and green`() {
        // numpy: +1.0 (magenta) -> (112, 132, 224); -1.0 (green) -> (88, 168, 176)
        assertClose(intArrayOf(112, 132, 224), apply1(HdrAdjust(tint = 1f)), "tint+")
        assertClose(intArrayOf(88, 168, 176), apply1(HdrAdjust(tint = -1f)), "tint-")
    }

    @Test
    fun `all advanced knobs chained together match the reference`() {
        // numpy combined: (166, 203, 210)
        assertClose(
            intArrayOf(166, 203, 210),
            apply1(
                HdrAdjust(
                    exposure = 0.3f, brightness = -0.2f, contrast = 0.4f,
                    gamma = 0.25f, saturation = -0.5f, temperature = 0.6f, tint = -0.3f,
                )
            ),
            "combined",
        )
    }

    @Test
    fun `extremes clamp inside the byte range`() {
        // numpy: white with +1 EV stays white; black with -1 EV stays black;
        // white with -1 EV lands at (128, 128, 128).
        run {
            val img = intArrayOf(px(255, 255, 255))
            HdrAdjustOps.apply(img, 1, 1, HdrAdjust(exposure = 1f))
            assertEquals(px(255, 255, 255), img[0])
        }
        run {
            val img = intArrayOf(px(0, 0, 0))
            HdrAdjustOps.apply(img, 1, 1, HdrAdjust(exposure = -1f))
            assertEquals(px(0, 0, 0), img[0])
        }
        assertClose(
            intArrayOf(128, 128, 128),
            run {
                val img = intArrayOf(px(255, 255, 255))
                HdrAdjustOps.apply(img, 1, 1, HdrAdjust(exposure = -1f))
                intArrayOf(ch(img[0], 16), ch(img[0], 8), ch(img[0], 0))
            },
            "white -1EV",
        )
    }

    @Test
    fun `unsharp mask matches the 3x3 numpy reference`() {
        // 3x3 image, amount 0.5 (k = 0.75). numpy reference rows.
        val expected = arrayOf(
            arrayOf(intArrayOf(0, 0, 9), intArrayOf(95, 106, 117), intArrayOf(220, 231, 241)),
            arrayOf(intArrayOf(23, 34, 45), intArrayOf(131, 125, 120), intArrayOf(178, 189, 201)),
            arrayOf(intArrayOf(59, 69, 80), intArrayOf(169, 180, 192), intArrayOf(255, 255, 255)),
        )
        val img = intArrayOf(
            px(10, 20, 30), px(100, 110, 120), px(200, 210, 220),
            px(40, 50, 60), px(128, 128, 128), px(180, 190, 200),
            px(70, 80, 90), px(160, 170, 180), px(240, 250, 255),
        )
        HdrAdjustOps.unsharp(img, 3, 3, amount = 0.5f)
        for (y in 0 until 3) {
            for (x in 0 until 3) {
                val p = img[y * 3 + x]
                val e = expected[y][x]
                assertClose(e, intArrayOf(ch(p, 16), ch(p, 8), ch(p, 0)), "unsharp ($y,$x)")
            }
        }
    }

    @Test
    fun `unsharp with amount zero is an identity`() {
        val img = intArrayOf(px(10, 20, 30), px(100, 110, 120), px(200, 210, 220), px(240, 250, 255))
        val copy = img.copyOf()
        HdrAdjustOps.unsharp(img, 2, 2, amount = 0f)
        assertArrayEquals(copy, img)
    }

    @Test
    fun `hdr adjust csv round trips and clamps hostile input`() {
        val adj = HdrAdjust(
            strength = 0.4f, exposure = -0.3f, brightness = 0.25f, contrast = -0.1f,
            gamma = 0.9f, saturation = -0.6f, temperature = 0.35f, tint = -0.2f,
            highlightKnee = 0.7f, sharpness = 0.8f,
        )
        assertEquals(adj, HdrAdjust.decode(adj.encode()))

        // Defaults for null / blank / garbage.
        assertEquals(HdrAdjust(), HdrAdjust.decode(null))
        assertEquals(HdrAdjust(), HdrAdjust.decode(""))
        assertEquals(HdrAdjust(), HdrAdjust.decode("junk"))
        assertEquals(HdrAdjust(), HdrAdjust.decode("1,2,3"))

        // Out-of-range values are clamped, not crashed on.
        val clamped = HdrAdjust.decode("5,-2,2,-2,2,-2,2,-2,0.1,2")
        assertEquals(1f, clamped.strength, 1e-6f)
        assertEquals(-1f, clamped.exposure, 1e-6f)
        assertEquals(1f, clamped.brightness, 1e-6f)
        assertEquals(-1f, clamped.contrast, 1e-6f)
        assertEquals(1f, clamped.gamma, 1e-6f)
        assertEquals(-1f, clamped.saturation, 1e-6f)
        assertEquals(1f, clamped.temperature, 1e-6f)
        assertEquals(-1f, clamped.tint, 1e-6f)
        assertEquals(0.6f, clamped.highlightKnee, 1e-6f)
        assertEquals(1f, clamped.sharpness, 1e-6f)
    }

    @Test
    fun `default adjust is natural strength with protection on`() {
        assertEquals(0.65f, HdrAdjust.DEFAULT_STRENGTH, 1e-6f)
        assertEquals(0.8f, HdrAdjust.DEFAULT_HIGHLIGHT_KNEE, 1e-6f)
        assertTrue(HdrAdjust().advancedNeutral)
        assertTrue(!HdrAdjust().copy(exposure = 0.1f).advancedNeutral)
    }
}
