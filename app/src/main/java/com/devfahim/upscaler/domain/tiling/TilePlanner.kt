package com.devfahim.upscaler.domain.tiling

/**
 * Pure geometry for splitting a large image into overlapping input tiles
 * whose *bodies* exactly partition the output image.
 *
 * This is the scheme used by the reference Real-ESRGAN ncnn implementation:
 *  - The image is divided into a grid of non-overlapping "bodies" of
 *    `tileSize` (the last body on each axis may be shorter).
 *  - Each tile's *input* patch is the body expanded by `overlap` pixels on
 *    every side, clamped to the image bounds. The extra context lets the
 *    fully-convolutional network produce clean output near patch borders.
 *  - Only the part of the tile output that corresponds to the body is
 *    copied into the final image, so bodies tile the output exactly.
 *
 * Pure Kotlin, no Android dependencies - unit tested in TilePlannerTest.
 */
object TilePlanner {

    /**
     * @param width    input image width in pixels
     * @param height   input image height in pixels
     * @param tileSize body edge length in input pixels (e.g. 128 / 256)
     * @param overlap  context padding read on each interior side (e.g. 16)
     * @param scale    the model's native scale factor (2 or 4)
     */
    fun plan(width: Int, height: Int, tileSize: Int, overlap: Int, scale: Int): List<Tile> {
        require(width > 0 && height > 0) { "invalid dimensions ${width}x$height" }
        require(tileSize > 0) { "tileSize must be positive" }
        require(overlap >= 0) { "overlap must be non-negative" }
        require(scale > 0) { "scale must be positive" }

        val xs = axisPositions(width, tileSize)
        val ys = axisPositions(height, tileSize)
        val tiles = ArrayList<Tile>(xs.size * ys.size)

        for (y in ys) {
            for (x in xs) {
                val bodyW = minOf(x + tileSize, width) - x
                val bodyH = minOf(y + tileSize, height) - y

                val inX = (x - overlap).coerceAtLeast(0)
                val inY = (y - overlap).coerceAtLeast(0)
                val inXEnd = minOf(x + tileSize + overlap, width)
                val inYEnd = minOf(y + tileSize + overlap, height)

                tiles += Tile(
                    inX = inX,
                    inY = inY,
                    inW = inXEnd - inX,
                    inH = inYEnd - inY,
                    bodyOffX = (x - inX) * scale,
                    bodyOffY = (y - inY) * scale,
                    bodyW = bodyW * scale,
                    bodyH = bodyH * scale,
                    outX = x * scale,
                    outY = y * scale,
                    tileOutW = (inXEnd - inX) * scale,
                    tileOutH = (inYEnd - inY) * scale,
                )
            }
        }
        return tiles
    }

    /** Start positions of tile bodies along one axis; bodies cover `[0, length)`. */
    fun axisPositions(length: Int, tileSize: Int): List<Int> {
        require(length > 0) { "length must be positive" }
        require(tileSize > 0) { "tileSize must be positive" }
        val positions = ArrayList<Int>()
        var p = 0
        while (p < length) {
            positions += p
            p += tileSize
        }
        return positions
    }

    /**
     * @param inX/inY/inW/inH    input-pixel rectangle fed to the network
     * @param bodyOffX/bodyOffY  body origin inside the tile's own output
     * @param bodyW/bodyH        body size in *output* pixels
     * @param outX/outY          body origin in the final output image
     * @param tileOutW/tileOutH  full tile output size (`inW*scale x inH*scale`)
     */
    data class Tile(
        val inX: Int,
        val inY: Int,
        val inW: Int,
        val inH: Int,
        val bodyOffX: Int,
        val bodyOffY: Int,
        val bodyW: Int,
        val bodyH: Int,
        val outX: Int,
        val outY: Int,
        val tileOutW: Int,
        val tileOutH: Int,
    ) {
        init {
            require(inW > 0 && inH > 0) { "empty input patch" }
            require(bodyW > 0 && bodyH > 0) { "empty body" }
            require(bodyOffX + bodyW <= tileOutW) { "body exceeds tile output (x)" }
            require(bodyOffY + bodyH <= tileOutH) { "body exceeds tile output (y)" }
        }
    }
}
