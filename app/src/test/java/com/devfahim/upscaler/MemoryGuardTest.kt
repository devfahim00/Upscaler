package com.devfahim.upscaler

import com.devfahim.upscaler.domain.tiling.MemoryGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryGuardTest {

    @Test
    fun `small image keeps requested scale`() {
        val (scale, reduced) = MemoryGuard.effectiveScale(1000, 750, requestedScale = 4)
        assertEquals(4, scale)
        assertFalse(reduced)
    }

    @Test
    fun `huge image at 4x is reduced to fit`() {
        // 4000x3000 at 4x = 192 MP > 16 MP cap -> must come down.
        val (scale, reduced) = MemoryGuard.effectiveScale(4000, 3000, requestedScale = 4)
        assertTrue(reduced)
        assertTrue(scale < 4)
        val pixels = 4000L * 3000L * scale * scale
        assertTrue(pixels <= MemoryGuard.MAX_OUTPUT_PIXELS)
    }

    @Test
    fun `reduction picks the largest fitting scale`() {
        // 2000x1500 = 3 MP. 4x -> 48 MP (too big). 2x -> 12 MP (fits).
        val (scale, reduced) = MemoryGuard.effectiveScale(2000, 1500, requestedScale = 4)
        assertEquals(2, scale)
        assertTrue(reduced)
    }

    @Test
    fun `tile sizes differ by backend`() {
        assertEquals(MemoryGuard.CPU_TILE_SIZE, MemoryGuard.tileSizeFor(backendUsesGpu = false))
        assertEquals(MemoryGuard.GPU_TILE_SIZE, MemoryGuard.tileSizeFor(backendUsesGpu = true))
        assertTrue(MemoryGuard.GPU_TILE_SIZE > MemoryGuard.CPU_TILE_SIZE)
    }

    @Test
    fun `working memory estimate grows with tile and scale`() {
        val small = MemoryGuard.estimateTileWorkingBytes(128, 2)
        val big = MemoryGuard.estimateTileWorkingBytes(256, 4)
        assertTrue(big > small * 4)
    }
}
