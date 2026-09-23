package com.devfahim.upscaler

import com.devfahim.upscaler.domain.model.ModelType
import com.devfahim.upscaler.domain.tiling.MemoryGuard
import com.devfahim.upscaler.domain.usecase.PlanUpscaleUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanUpscaleUseCaseTest {

    private val useCase = PlanUpscaleUseCase()

    @Test
    fun `plan keeps scale when output fits budget`() {
        val p = useCase(800, 600, requestedScale = 4, nativeModelScale = 4, backendUsesGpu = true)
        assertEquals(4, p.effectiveScale)
        assertNull(p.scaleReducedNotice)
        assertEquals(3200, p.outputWidth)
        assertEquals(2400, p.outputHeight)
    }

    @Test
    fun `plan reduces scale when output too large`() {
        val p = useCase(2000, 1500, requestedScale = 4, nativeModelScale = 4, backendUsesGpu = true)
        assertEquals(2, p.effectiveScale)
        assertNotNull(p.scaleReducedNotice)
    }

    @Test
    fun `tiles generated for larger than tile images`() {
        val p = useCase(1000, 1000, requestedScale = 2, nativeModelScale = 2, backendUsesGpu = false)
        // CPU tile size 128 -> more than one tile.
        assertTrue(p.tiles.size > 1)
    }

    @Test
    fun `high quality model plans use smaller tiles`() {
        // The caller passes the model tile size explicitly (see
        // PhotoUpscaleProcessor.upscaleArgb) - verify the plumbing contract.
        val gpuHq = useCase(
            1000, 1000, requestedScale = 4, nativeModelScale = 4,
            backendUsesGpu = true,
            tileSizeOverride = ModelType.HQ_PHOTO_X4.tileSizeFor(backendUsesGpu = true),
        )
        val gpuCompact = useCase(
            1000, 1000, requestedScale = 4, nativeModelScale = 4,
            backendUsesGpu = true,
            tileSizeOverride = ModelType.GENERAL_PHOTO_X4.tileSizeFor(backendUsesGpu = true),
        )
        assertEquals(MemoryGuard.HQ_GPU_TILE_SIZE, gpuHq.tileSize)
        assertTrue(gpuHq.tiles.size > gpuCompact.tiles.size)
    }
}
