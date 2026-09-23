package com.devfahim.upscaler.processing

import com.devfahim.upscaler.domain.tiling.TilePlanner

/**
 * Row-copy primitives shared by the photo pipeline and the video pipeline
 * (kept free of Android types so they stay trivially unit-testable).
 */
object TileOps {

    /** Row-copies a tile's input rectangle out of a packed ARGB buffer. */
    fun extractTile(argb: IntArray, srcW: Int, tile: TilePlanner.Tile, tileBuf: IntArray) {
        for (r in 0 until tile.inH) {
            val srcStart = (tile.inY + r) * srcW + tile.inX
            System.arraycopy(argb, srcStart, tileBuf, r * tile.inW, tile.inW)
        }
    }

    /** Copies the body rectangle of a tile output into the final buffer. */
    fun copyBody(tileOut: IntArray, tile: TilePlanner.Tile, out: IntArray, outW: Int) {
        for (row in 0 until tile.bodyH) {
            val srcStart = (tile.bodyOffY + row) * tile.tileOutW + tile.bodyOffX
            val dstStart = (tile.outY + row) * outW + tile.outX
            System.arraycopy(tileOut, srcStart, out, dstStart, tile.bodyW)
        }
    }

    /** Bilinear resample of a packed ARGB buffer (used for resolution caps). */
    fun resampleBilinear(src: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        if (srcW == dstW && srcH == dstH) return src
        val dst = IntArray(dstW * dstH)
        val xRatio = srcW.toFloat() / dstW
        val yRatio = srcH.toFloat() / dstH

        // Precompute horizontal source indices and fractions.
        val xSrc0 = IntArray(dstW)
        val xSrc1 = IntArray(dstW)
        val xFrac = FloatArray(dstW)
        for (x in 0 until dstW) {
            val sx = x * xRatio
            val x0 = sx.toInt().coerceAtMost(srcW - 1)
            xSrc0[x] = x0
            xSrc1[x] = (x0 + 1).coerceAtMost(srcW - 1)
            xFrac[x] = sx - x0
        }

        var di = 0
        for (y in 0 until dstH) {
            val sy = y * yRatio
            val y0 = if (sy.toInt() >= srcH - 1) srcH - 1 else sy.toInt()
            val y1 = if (y0 + 1 <= srcH - 1) y0 + 1 else y0
            val fy = sy - y0
            val row0 = y0 * srcW
            val row1 = y1 * srcW
            for (x in 0 until dstW) {
                val xs0 = xSrc0[x]
                val xs1 = xSrc1[x]
                val fx = xFrac[x]
                val p00 = src[row0 + xs0]
                val p01 = src[row0 + xs1]
                val p10 = src[row1 + xs0]
                val p11 = src[row1 + xs1]

                val a = lerp((p00 ushr 24) and 0xFF, (p01 ushr 24) and 0xFF, (p10 ushr 24) and 0xFF, (p11 ushr 24) and 0xFF, fx, fy)
                val r = lerp((p00 shr 16) and 0xFF, (p01 shr 16) and 0xFF, (p10 shr 16) and 0xFF, (p11 shr 16) and 0xFF, fx, fy)
                val g = lerp((p00 shr 8) and 0xFF, (p01 shr 8) and 0xFF, (p10 shr 8) and 0xFF, (p11 shr 8) and 0xFF, fx, fy)
                val b = lerp(p00 and 0xFF, p01 and 0xFF, p10 and 0xFF, p11 and 0xFF, fx, fy)

                dst[di++] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return dst
    }

    private inline fun lerp(c00: Int, c01: Int, c10: Int, c11: Int, fx: Float, fy: Float): Int {
        val top = c00 + ((c01 - c00) * fx).toInt()
        val bottom = c10 + ((c11 - c10) * fx).toInt()
        return (top + ((bottom - top) * fy).toInt()).coerceIn(0, 255)
    }
}
