package com.devfahim.upscaler.data.engine

import kotlin.math.roundToInt

/**
 * float32 <-> float16 (IEEE 754 binary16) bit-level conversion with
 * round-to-nearest-even semantics, matching how ncnn stores fp16 weights.
 *
 * Pure Kotlin - unit tested in [com.devfahim.upscaler.HalfFloatTest].
 */
object HalfFloat {

    /** @return the 16-bit pattern (0..0xFFFF) of [value] as a half float. */
    fun toHalf(value: Float): Int {
        val x = value.toRawBits()
        var h = (x ushr 16) and 0x8000 // sign bit
        val exp32 = (x ushr 23) and 0xFF
        val man32 = x and 0x7FFFFF

        if (exp32 == 0xFF) {
            // Inf / NaN
            return h or 0x7C00 or (if (man32 != 0) 0x0200 else 0)
        }
        if (exp32 == 0) {
            // zero / fp32-denormal: weights never rely on denormals - signed zero
            return h
        }

        var t = exp32 - 112 // rebiased exponent field for fp16
        if (t >= 0x1F) return h or 0x7C00 // overflow -> +/-Inf

        if (t >= 1) {
            // normal fp16: keep top 10 mantissa bits, round-half-even
            var m = man32 ushr 13
            val rem = man32 and 0x1FFF
            val halfway = 0x1000
            if (rem > halfway || (rem == halfway && (m and 1) == 1)) m++
            if (m == 0x400) { // mantissa overflowed into the next exponent
                m = 0
                t++
            }
            if (t >= 0x1F) return h or 0x7C00
            return h or (t shl 10) or m
        }

        // subnormal fp16 territory: value < 2^-14
        if (t < -10) return h // underflow -> signed zero
        val shift = 14 - t // 14..24 bits to drop from the 24-bit mantissa
        val full = 0x800000 or man32 // mantissa with implicit leading 1
        var m = full ushr shift
        val rem = full and ((1 shl shift) - 1)
        val halfway = 1 shl (shift - 1)
        if (rem > halfway || (rem == halfway && (m and 1) == 1)) m++
        if (m == 0x400) {
            // rounded up right into the smallest normal (2^-14)
            return h or 0x0400
        }
        return h or m
    }

    /** @return the float value of the 16-bit half pattern [half]. */
    fun toFloat(half: Int): Float {
        val h = half and 0xFFFF
        val sign = (h and 0x8000) shl 16
        val exp = (h ushr 10) and 0x1F
        val man = h and 0x3FF
        val bits = when {
            exp == 0 -> if (man == 0) {
                sign
            } else {
                // subnormal: normalize (value = man * 2^-24)
                var e = 0
                var m = man
                while (m and 0x400 == 0) {
                    m = m shl 1
                    e++
                }
                sign or ((113 - e) shl 23) or ((m and 0x3FF) shl 13)
            }
            exp == 0x1F -> sign or 0x7F800000 or (man shl 13) // +/-Inf / NaN
            else -> sign or ((exp - 15 + 127) shl 23) or (man shl 13)
        }
        return Float.fromBits(bits)
    }
}

/**
 * Weight-blob layout rules for the ncnn `.bin` files this app bundles,
 * derived from ncnn's ModelBin reader (src/modelbin.cpp) and writer
 * (tools/modelwriter.h):
 *
 *  * Convolution weight - `load(weight_data_size, 0)` (auto): stored as
 *    [4-byte tag][data][pad to 4-byte alignment]. Tag 0x01306B47 marks
 *    float16 data (2 bytes/value); tag 0 marks raw float32 (4 bytes/value).
 *  * Convolution bias / PReLU slope - `load(n, 1)`: stored as raw
 *    float32 with no tag and no padding.
 *
 * Pure Kotlin - unit tested in [com.devfahim.upscaler.WdnBlendTest].
 */
object WdnBlend {

    /** ncnn's "half-precision data" blob tag (see modelwriter.h). */
    const val FP16_TAG = 0x01306B47

    /** One weight blob of a `.bin` file. */
    sealed interface BlobSpec {
        val count: Int

        /** Tagged (auto) blob: 4-byte tag + fp16/fp32 data + 4-byte alignment. */
        data class TaggedWeight(override val count: Int) : BlobSpec

        /** Untagged blob: raw little-endian float32 values. */
        data class RawFloat32(override val count: Int) : BlobSpec
    }

    /**
     * Parses an ncnn `.param` file and returns the ordered sequence of
     * weight blobs its layers load from the `.bin` file. Only the layer
     * types used by the bundled SRVGGNetCompact models are handled.
     */
    fun parseBlobSpecs(paramText: String): List<BlobSpec> {
        val specs = ArrayList<BlobSpec>()
        val lines = paramText.split('\n')
        for (i in 2 until lines.size) { // skip magic + layer-count header
            val line = lines[i].trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val tokens = line.split(' ')
            if (tokens.size < 3) continue
            val params = HashMap<Int, String>()
            for (t in tokens.drop(4)) {
                val eq = t.indexOf('=')
                if (eq <= 0) continue
                val key = t.substring(0, eq).toIntOrNull() ?: continue
                params[key] = t.substring(eq + 1)
            }
            when (tokens[0]) {
                "Convolution" -> {
                    val weightSize = params[6]?.toIntOrNull() ?: error("Convolution without 6=weight_data_size: $line")
                    specs += BlobSpec.TaggedWeight(weightSize)
                    if (params[5]?.toIntOrNull() == 1) {
                        specs += BlobSpec.RawFloat32(params[0]?.toIntOrNull() ?: error("Convolution without 0=num_output: $line"))
                    }
                }
                "PReLU" -> {
                    specs += BlobSpec.RawFloat32(params[0]?.toIntOrNull() ?: error("PReLU without 0=num_slope: $line"))
                }
                // every other layer type bundled here loads no weights
            }
        }
        return specs
    }

    /**
     * Blends the two model weight files blob-by-blob:
     *
     * ```
     * out = (1 - alpha) * general + alpha * wdn
     * ```
     *
     * Both inputs must share the same architecture (i.e. the same blob
     * layout produced by [parseBlobSpecs]); fp16 blobs are blended in
     * float32 and re-encoded to fp16. The output has exactly the same size
     * and layout as the inputs and loads through the base model's `.param`.
     *
     * @throws IllegalArgumentException on layout mismatch between the inputs.
     */
    fun blend(
        generalBin: ByteArray,
        wdnBin: ByteArray,
        specs: List<BlobSpec>,
        alpha: Float,
    ): ByteArray {
        require(alpha in 0f..1f) { "alpha must be in [0,1], was $alpha" }
        require(generalBin.size == wdnBin.size) {
            "bin size mismatch: ${generalBin.size} vs ${wdnBin.size} - different architectures?"
        }
        // Endpoints are returned verbatim: besides skipping the work, this
        // keeps -0.0 weight values bit-identical to the chosen source file.
        if (alpha == 0f) return generalBin.copyOf()
        if (alpha == 1f) return wdnBin.copyOf()
        val out = ByteArray(generalBin.size)
        var gp = 0
        var wp = 0
        var op = 0

        fun requireLeft(bin: ByteArray, pos: Int, n: Int, label: String) {
            require(pos + n <= bin.size) { "$label truncated at $pos (need $n)" }
        }

        for (spec in specs) {
            when (spec) {
                is BlobSpec.TaggedWeight -> {
                    requireLeft(generalBin, gp, 4, "general")
                    requireLeft(wdnBin, wp, 4, "wdn")
                    val gTag = readTag(generalBin, gp)
                    val wTag = readTag(wdnBin, wp)
                    require(gTag == wTag) { "tag mismatch at $gp: $gTag vs $wTag" }
                    writeTag(out, op, gTag)
                    gp += 4; wp += 4; op += 4
                    when (gTag) {
                        FP16_TAG -> {
                            val dataLen = (spec.count * 2).let { (it + 3) and (3).inv() } // align4
                            requireLeft(generalBin, gp, dataLen, "general")
                            requireLeft(wdnBin, wp, dataLen, "wdn")
                            val inv = 1f - alpha
                            for (i in 0 until spec.count) {
                                val g = HalfFloat.toFloat(readU16(generalBin, gp + i * 2))
                                val w = HalfFloat.toFloat(readU16(wdnBin, wp + i * 2))
                                writeU16(out, op + i * 2, HalfFloat.toHalf(g * inv + w * alpha))
                            }
                            // copy the alignment padding bytes verbatim
                            for (i in spec.count * 2 until dataLen) {
                                out[op + i] = generalBin[gp + i]
                            }
                            gp += dataLen; wp += dataLen; op += dataLen
                        }
                        0 -> {
                            val dataLen = spec.count * 4
                            requireLeft(generalBin, gp, dataLen, "general")
                            requireLeft(wdnBin, wp, dataLen, "wdn")
                            val inv = 1f - alpha
                            for (i in 0 until spec.count) {
                                val g = Float.fromBits(readI32(generalBin, gp + i * 4))
                                val w = Float.fromBits(readI32(wdnBin, wp + i * 4))
                                writeI32(out, op + i * 4, (g * inv + w * alpha).toRawBits())
                            }
                            gp += dataLen; wp += dataLen; op += dataLen
                        }
                        else -> throw IllegalArgumentException("unsupported blob tag 0x${gTag.toString(16)} at $gp")
                    }
                }
                is BlobSpec.RawFloat32 -> {
                    val dataLen = spec.count * 4
                    requireLeft(generalBin, gp, dataLen, "general")
                    requireLeft(wdnBin, wp, dataLen, "wdn")
                    val inv = 1f - alpha
                    for (i in 0 until spec.count) {
                        val g = Float.fromBits(readI32(generalBin, gp + i * 4))
                        val w = Float.fromBits(readI32(wdnBin, wp + i * 4))
                        writeI32(out, op + i * 4, (g * inv + w * alpha).toRawBits())
                    }
                    gp += dataLen; wp += dataLen; op += dataLen
                }
            }
        }
        require(gp == generalBin.size && wp == wdnBin.size && op == out.size) {
            "blob specs did not consume the files exactly: general=$gp/${generalBin.size} wdn=$wp/${wdnBin.size} out=$op/${out.size}"
        }
        return out
    }

    /**
     * Snaps an arbitrary slider value to the supported interpolation steps
     * (multiples of 1/[steps]) so the blended model can be cached and
     * re-used. 0 = pure general model, 1 = pure WDN model.
     */
    fun quantizeAlpha(alpha: Float, steps: Int = 10): Float {
        val clamped = alpha.coerceIn(0f, 1f)
        return (clamped * steps).roundToInt() / steps.toFloat()
    }

    // ---- little-endian byte helpers ----

    internal fun readTag(b: ByteArray, off: Int): Int = readI32(b, off)

    internal fun writeTag(b: ByteArray, off: Int, v: Int) = writeI32(b, off, v)

    internal fun readU16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    internal fun writeU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    internal fun readI32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    internal fun writeI32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }
}
