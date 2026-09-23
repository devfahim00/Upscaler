package com.devfahim.upscaler.processing

import android.media.Image

/**
 * YUV <-> RGB conversions used by the video pipeline.
 *
 * Decode side: MediaCodec hands us `Image` (YUV_420_888) with arbitrary row
 * and pixel strides depending on the device. We fold them into a packed
 * ARGB IntArray before inference.
 *
 * Encode side: MediaCodec encoders accept either NV12 (semi-planar) or
 * I420 (planar) in a flat ByteBuffer; we produce both from ARGB.
 *
 * BT.601 limited-range, the standard for H.264 4:2:0 video.
 */
object Yuv {

    // ------------------------------------------------------------------
    // Decode: Image (YUV_420_888) -> ARGB
    // ------------------------------------------------------------------

    /**
     * Converts a decoded video frame into a packed ARGB IntArray.
     * Handles both interleaved (pixelStride=2) and planar (pixelStride=1)
     * chroma layouts, plus any padding in row strides.
     */
    fun imageToArgb(image: Image, out: IntArray): Boolean {
        val width = image.width
        val height = image.height
        if (out.size < width * height) return false

        val planes = image.planes
        if (planes.size < 3) return false

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val yPixStride = yPlane.pixelStride
        val uPixStride = uPlane.pixelStride
        val vPixStride = vPlane.pixelStride

        var index = 0
        for (row in 0 until height) {
            val yRowStart = row * yRowStride
            val chromaRow = row ushr 1
            val uRowStart = chromaRow * uRowStride
            val vRowStart = chromaRow * vRowStride

            for (col in 0 until width) {
                val y = (yBuf.get(yRowStart + col * yPixStride).toInt() and 0xFF)

                val uvCol = col ushr 1
                val u = (uBuf.get(uRowStart + uvCol * uPixStride).toInt() and 0xFF) - 128
                val v = (vBuf.get(vRowStart + uvCol * vPixStride).toInt() and 0xFF) - 128

                // BT.601 limited-range YUV -> RGB.
                var r = y + ((91881 * v) shr 16)
                var g = y - ((22554 * u + 46802 * v) shr 16)
                var b = y + ((116130 * u) shr 16)

                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255

                out[index++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    // Encode: ARGB -> YUV planes
    // ------------------------------------------------------------------

    /**
     * ARGB -> NV12 (YYYY... UVUV...). Writes into [y] and [uv] arrays the
     * caller sizes for the *encoded* frame dimensions.
     */
    fun argbToNv12(argb: IntArray, width: Int, height: Int, y: ByteArray, uv: ByteArray) {
        var yIdx = 0
        var uvIdx = 0
        for (row in 0 until height) {
            for (col in 0 until width) {
                val p = argb[row * width + col]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                // BT.601: Y = 0.299R + 0.587G + 0.114B (full->limited range).
                val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                y[yIdx++] = yy.toByte()

                if ((row and 1) == 0 && (col and 1) == 0) {
                    val cb = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val cr = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    uv[uvIdx++] = cb.toByte()
                    uv[uvIdx++] = cr.toByte()
                }
            }
        }
    }

    /** ARGB -> I420 (YYYY... UUUU... VVVV...). */
    fun argbToI420(argb: IntArray, width: Int, height: Int, y: ByteArray, u: ByteArray, v: ByteArray) {
        val chromaCount = ((width + 1) / 2) * ((height + 1) / 2)
        if (y.size < width * height || u.size < chromaCount || v.size < chromaCount) return

        var yIdx = 0
        var uIdx = 0
        var vIdx = 0
        for (row in 0 until height) {
            for (col in 0 until width) {
                val p = argb[row * width + col]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                y[yIdx++] = yy.toByte()

                if ((row and 1) == 0 && (col and 1) == 0) {
                    val cb = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val cr = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    u[uIdx++] = cb.toByte()
                    v[vIdx++] = cr.toByte()
                }
            }
        }
    }
}
