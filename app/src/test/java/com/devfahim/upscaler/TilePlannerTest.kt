package com.devfahim.upscaler

import com.devfahim.upscaler.domain.tiling.TilePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tiling math is the correctness backbone of the whole app: bodies must
 * exactly partition the output image (no gaps, no overlaps), inputs must be
 * clamped inside the image, and overlap must be respected on interior sides.
 */
class TilePlannerTest {

    @Test
    fun `single tile when image fits`() {
        val tiles = TilePlanner.plan(100, 80, tileSize = 128, overlap = 16, scale = 4)
        assertEquals(1, tiles.size)
        val t = tiles[0]
        assertEquals(0, t.inX)
        assertEquals(0, t.inY)
        assertEquals(100, t.inW)
        assertEquals(80, t.inH)
        assertEquals(400, t.tileOutW)
        assertEquals(320, t.tileOutH)
        assertEquals(0, t.outX)
        assertEquals(0, t.outY)
        assertEquals(400, t.bodyW)
        assertEquals(320, t.bodyH)
    }

    @Test
    fun `bodies partition output exactly for even grid`() {
        val w = 512
        val h = 256
        val scale = 4
        val tiles = TilePlanner.plan(w, h, tileSize = 256, overlap = 16, scale = scale)
        assertEquals(2 * 1, tiles.size)

        val covered = LongArray(w * scale * h * scale / 64 + 1) // simple coverage bitmap
        val outW = w * scale
        tiles.forEach { t ->
            assertTrue(t.outX >= 0 && t.outY >= 0)
            assertTrue(t.outX + t.bodyW <= outW)
            for (row in 0 until t.bodyH) {
                for (col in 0 until t.bodyW) {
                    val x = t.outX + col
                    val y = t.outY + row
                    val idx = y * outW + x
                    covered[idx / 64] = covered[idx / 64] or (1L shl (idx % 64))
                }
            }
        }
        val total = w * scale * h * scale
        for (i in 0 until total) {
            assertTrue("pixel $i not covered", covered[i / 64] and (1L shl (i % 64)) != 0L)
        }
    }

    @Test
    fun `bodies partition output for ragged sizes`() {
        val w = 600
        val h = 300
        val scale = 2
        val tiles = TilePlanner.plan(w, h, tileSize = 200, overlap = 12, scale = scale)

        val outW = w * scale
        val outH = h * scale
        val seen = HashSet<Long>()
        tiles.forEach { t ->
            for (row in 0 until t.bodyH) {
                for (col in 0 until t.bodyW) {
                    val x = t.outX + col
                    val y = t.outY + row
                    val key = y.toLong() * outW + x
                    assertTrue("overlap at $key", seen.add(key))
                }
            }
        }
        assertEquals((outW * outH).toLong(), seen.size.toLong())
    }

    @Test
    fun `interior tiles read overlap context`() {
        val tiles = TilePlanner.plan(700, 300, tileSize = 200, overlap = 16, scale = 2)
        // The second tile along x starts at 200; its input must begin 16px earlier.
        val second = tiles.first { it.inX > 0 && it.inY == 0 }
        assertEquals(200 - 16, second.inX)
        // Body sits inside the tile output at the overlap-scaled offset.
        assertEquals(16 * 2, second.bodyOffX)
    }

    @Test
    fun `edge tiles clamp to image bounds`() {
        val tiles = TilePlanner.plan(300, 300, tileSize = 200, overlap = 16, scale = 2)
        tiles.forEach { t ->
            assertTrue(t.inX >= 0)
            assertTrue(t.inY >= 0)
            assertTrue(t.inX + t.inW <= 300)
            assertTrue(t.inY + t.inH <= 300)
        }
        // Last tile body ends exactly at the image edge.
        val lastX = tiles.maxOf { it.outX + it.bodyW }
        assertEquals(600, lastX)
    }

    @Test
    fun `axis positions step by tile size`() {
        assertEquals(listOf(0), TilePlanner.axisPositions(100, 128))
        assertEquals(listOf(0, 128), TilePlanner.axisPositions(256, 128))
        assertEquals(listOf(0, 128, 256), TilePlanner.axisPositions(384, 128))
        assertEquals(listOf(0, 128, 256, 384), TilePlanner.axisPositions(500, 128))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid dimensions rejected`() {
        TilePlanner.plan(0, 10, tileSize = 64, overlap = 8, scale = 2)
    }
}
