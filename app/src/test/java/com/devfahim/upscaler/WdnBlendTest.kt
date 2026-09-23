package com.devfahim.upscaler

import com.devfahim.upscaler.data.engine.HalfFloat
import com.devfahim.upscaler.data.engine.WdnBlend
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Vector tables verified against numpy's float16 conversion
 * (round-to-nearest-even), which matches ncnn's weight storage.
 */
class HalfFloatTest {

    private val toHalfVectors = listOf(
        0.0f to 0x0000,
        1.0f to 0x3C00,
        -1.0f to 0xBC00,
        0.5f to 0x3800,
        2.0f to 0x4000,
        0.1f to 0x2E66,
        1f / 3f to 0x3555,
        1.126461386680603f to 0x3C81,
        -69.1875f to 0xD453,
        50.3359375f to 0x524B,
        65504.0f to 0x7BFF,
        65520.0f to 0x7C00, // overflow -> +Inf
        Float.NEGATIVE_INFINITY to 0xFC00,
        5.960464477539063e-08f to 0x0001, // 2^-24 smallest subnormal
        2.9802322387695312e-08f to 0x0000, // 2^-25 exact halfway -> even 0
        8.940696716308594e-08f to 0x0002, // 1.5 * 2^-24 -> rounds up
        4.470348358154297e-08f to 0x0001, // 0.75 * 2^-24 -> smallest subnormal
        6.103515625e-05f to 0x0400, // 2^-14 smallest normal
        3.0517578125e-05f to 0x0200, // 2^-15 subnormal
        6.1e-05f to 0x03FF,
        0.30000001192092896f to 0x34CD, // 0.3f
    )

    @Test
    fun `toHalf matches numpy float16 bit patterns`() {
        toHalfVectors.forEach { (value, bits) ->
            assertEquals("toHalf($value)", bits, HalfFloat.toHalf(value))
        }
    }

    @Test
    fun `toFloat decodes the canonical bit patterns`() {
        assertEquals(0.0f, HalfFloat.toFloat(0x0000), 0f)
        assertEquals(-0.0f, HalfFloat.toFloat(0x8000), 0f)
        assertTrue(HalfFloat.toFloat(0x8000).toRawBits() == (-0.0f).toRawBits())
        assertEquals(5.960464477539063e-08f, HalfFloat.toFloat(0x0001), 0f)
        assertEquals(3.0517578125e-05f, HalfFloat.toFloat(0x0200), 0f)
        assertEquals(6.103515625e-05f, HalfFloat.toFloat(0x0400), 0f)
        assertEquals(1.0f, HalfFloat.toFloat(0x3C00), 0f)
        assertEquals(50.34375f, HalfFloat.toFloat(0x524B), 0f)
        assertEquals(Float.POSITIVE_INFINITY, HalfFloat.toFloat(0x7C00), 0f)
        assertEquals(Float.NEGATIVE_INFINITY, HalfFloat.toFloat(0xFC00), 0f)
        assertTrue(HalfFloat.toFloat(0x7E00).isNaN())
        assertTrue(HalfFloat.toFloat(0x7C01).isNaN())
    }

    @Test
    fun `finite values round-trip through half precision`() {
        val samples = floatArrayOf(
            0.001f, -0.25f, 3.75f, 100.125f, -2048.5f, 0.0000305f, 12345f, -0.7f,
        )
        samples.forEach { v ->
            val half = HalfFloat.toHalf(v)
            val back = HalfFloat.toFloat(half)
            // fp16 relative precision is ~2^-11
            assertTrue("round trip $v -> $back", kotlin.math.abs(back - v) <= kotlin.math.abs(v) * 2e-3f + 1e-7f)
        }
    }
}

class WdnBlendTest {

    // A tiny SRVGGNetCompact-shaped network: conv -> prelu -> conv(out).
    private val param = listOf(
        "7767517",
        "4 5",
        "Input                    input.1                  0 1 data",
        "Convolution              Conv_0                   1 1 data 102 0=2 1=3 5=1 6=18",
        "PReLU                    PRelu_1                  1 1 102 103 0=2",
        "Convolution              Conv_2                   1 1 103 output 0=1 1=3 5=1 6=9",
        // weightless tail layers must be ignored
        "PixelShuffle             DepthToSpace_3           1 1 104 105 0=2 1=0",
        "Interp                   Resize_4                 1 1 input.1 106 0=1 1=2.000000e+00 2=2.000000e+00",
        "BinaryOp                 Add_5                    2 1 105 106 output 0=0",
    ).joinToString("\n")

    private val specs = WdnBlend.parseBlobSpecs(param)

    private val gWeights = FloatArray(18) { it * 0.25f - 2.375f }
    private val gBias = floatArrayOf(0.5f, -1.5f)
    private val gPrelu = floatArrayOf(0.25f, 0.125f)
    private val gTailWeights = FloatArray(9) { -0.1f * (it + 1) }
    private val gTailBias = floatArrayOf(0.75f)

    private val wWeights = FloatArray(18) { 2.375f - it * 0.25f }
    private val wBias = floatArrayOf(1.5f, -0.5f)
    private val wPrelu = floatArrayOf(0.75f, 0.625f)
    private val wTailWeights = FloatArray(9) { 0.1f * (it + 1) }
    private val wTailBias = floatArrayOf(0.25f)

    private fun tag(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

    private fun f16Padded(values: FloatArray): ByteArray {
        val data = ByteArray(values.size * 2)
        values.forEachIndexed { i, v -> WdnBlend.writeU16(data, i * 2, HalfFloat.toHalf(v)) }
        val pad = (4 - data.size % 4) % 4
        return data + ByteArray(pad)
    }

    private fun f32(values: FloatArray): ByteArray {
        val data = ByteArray(values.size * 4)
        values.forEachIndexed { i, v -> WdnBlend.writeI32(data, i * 4, v.toRawBits()) }
        return data
    }

    private fun buildBin(
        weights1: FloatArray, bias1: FloatArray, prelu: FloatArray,
        weights2: FloatArray, bias2: FloatArray,
    ): ByteArray =
        tag(WdnBlend.FP16_TAG) + f16Padded(weights1) +
            f32(bias1) +
            f32(prelu) +
            tag(WdnBlend.FP16_TAG) + f16Padded(weights2) +
            f32(bias2)

    private val generalBin = buildBin(gWeights, gBias, gPrelu, gTailWeights, gTailBias)
    private val wdnBin = buildBin(wWeights, wBias, wPrelu, wTailWeights, wTailBias)

    @Test
    fun `blob specs parse in load order with the right counts`() {
        val expected = listOf(
            WdnBlend.BlobSpec.TaggedWeight(18),
            WdnBlend.BlobSpec.RawFloat32(2),
            WdnBlend.BlobSpec.RawFloat32(2),
            WdnBlend.BlobSpec.TaggedWeight(9),
            WdnBlend.BlobSpec.RawFloat32(1),
        )
        assertEquals(expected, specs)
        assertEquals(32, specs.sumOf { it.count })
    }

    @Test
    fun `odd-count fp16 blobs carry alignment padding`() {
        // 9 halfs = 18 bytes -> padded to 20 (+4-byte tag).
        assertEquals(24, tag(WdnBlend.FP16_TAG).size + f16Padded(FloatArray(9)).size)
    }

    @Test
    fun `blend at half produces midpoint values`() {
        val out = WdnBlend.blend(generalBin, wdnBin, specs, 0.5f)
        assertEquals(generalBin.size, out.size)

        // fp16 weights: blend in fp32 then re-encode (fp16 carries ~2^-11
        // relative precision, so compare with a small tolerance)
        val expectedW1 = FloatArray(18) { (gWeights[it] + wWeights[it]) / 2f }
        val actualW1 = FloatArray(18)
        for (i in 0 until 18) {
            actualW1[i] = HalfFloat.toFloat(WdnBlend.readU16(out, 4 + i * 2))
        }
        expectedW1.forEachIndexed { i, e ->
            assertTrue("weight1[$i]", kotlin.math.abs(actualW1[i] - e) <= kotlin.math.abs(e) * 2e-3f + 1e-6f)
        }

        // raw fp32 biases: exact
        // layout: [tag][18 halfs][bias1 x2][prelu x2][tag][9 halfs+pad][tailBias]
        //          0     4..40     40..48     48..56   56    60..80        80..84
        assertEquals((gBias[0] + wBias[0]) / 2f, Float.fromBits(WdnBlend.readI32(out, 40)), 0f)
        assertEquals((gPrelu[0] + wPrelu[0]) / 2f, Float.fromBits(WdnBlend.readI32(out, 48)), 0f)
        assertEquals((gTailBias[0] + wTailBias[0]) / 2f, Float.fromBits(WdnBlend.readI32(out, out.size - 4)), 0f)
    }

    @Test
    fun `blend endpoints are exact copies`() {
        assertArrayEquals(generalBin, WdnBlend.blend(generalBin, wdnBin, specs, 0f))
        assertArrayEquals(wdnBin, WdnBlend.blend(generalBin, wdnBin, specs, 1f))
    }

    @Test
    fun `mismatched architectures are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            WdnBlend.blend(generalBin, wdnBin + byteArrayOf(0), specs, 0.5f)
        }
    }

    @Test
    fun `mismatched blob tags are rejected`() {
        val broken = wdnBin.copyOf().also { WdnBlend.writeTag(it, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) {
            WdnBlend.blend(generalBin, broken, specs, 0.5f)
        }
    }

    @Test
    fun `specs that do not consume the file exactly are rejected`() {
        val partial = specs.dropLast(1)
        assertThrows(IllegalArgumentException::class.java) {
            WdnBlend.blend(generalBin, wdnBin, partial, 0.5f)
        }
    }

    @Test
    fun `alpha outside the unit interval is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            WdnBlend.blend(generalBin, wdnBin, specs, -0.1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WdnBlend.blend(generalBin, wdnBin, specs, 1.1f)
        }
    }

    @Test
    fun `alpha quantizes to ten-percent steps`() {
        assertEquals(0f, WdnBlend.quantizeAlpha(0f))
        assertEquals(0f, WdnBlend.quantizeAlpha(0.04f))
        assertEquals(0.1f, WdnBlend.quantizeAlpha(0.06f))
        assertEquals(0.1f, WdnBlend.quantizeAlpha(0.1f))
        assertEquals(0.5f, WdnBlend.quantizeAlpha(0.47f))
        assertEquals(1f, WdnBlend.quantizeAlpha(0.99f))
        assertEquals(1f, WdnBlend.quantizeAlpha(1f))
        assertEquals(1f, WdnBlend.quantizeAlpha(1.5f))
        assertEquals(0f, WdnBlend.quantizeAlpha(-0.5f))
    }

    /** Runs only when the repo asset is reachable from the working dir. */
    @Test
    fun `real bundled param parses into the expected blob layout`() {
        val paramFile = File("src/main/assets/models/realesr-general-x4v3/model.param")
        assumeTrue(paramFile.isFile)
        val realSpecs = WdnBlend.parseBlobSpecs(paramFile.readText())
        assertEquals(101, realSpecs.size)
        assertEquals(1213296, realSpecs.sumOf { it.count })
    }

    /**
     * HDRNet (Zero-DCE++): 7 CSDN blocks = 14 conv layers x (weight + bias)
     * = 28 blobs, 10,561 values total. Runs only when the asset is
     * reachable; also proves the depthwise-conv branch of the parser.
     */
    @Test
    fun `real hdrnet param parses into the expected blob layout`() {
        val paramFile = File("src/main/assets/models/hdrnet/model.param")
        assumeTrue(paramFile.isFile)
        val specs = WdnBlend.parseBlobSpecs(paramFile.readText())
        assertEquals(28, specs.size)
        assertEquals(10561, specs.sumOf { it.count })
        // the 14 weight blobs are exactly the tagged (fp16) ones
        assertEquals(14, specs.count { it is WdnBlend.BlobSpec.TaggedWeight })
        assertEquals(14, specs.count { it is WdnBlend.BlobSpec.RawFloat32 })
    }
}
