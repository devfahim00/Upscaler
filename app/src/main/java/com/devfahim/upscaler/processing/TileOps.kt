package com.devfahim.upscaler.processing

import com.devfahim.upscaler.domain.tiling.TilePlanner

/**
 * Row-copy primitives used by the photo tiling pipeline
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
}
