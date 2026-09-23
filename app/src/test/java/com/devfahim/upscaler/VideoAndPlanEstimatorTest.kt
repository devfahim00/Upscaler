package com.devfahim.upscaler

import com.devfahim.upscaler.domain.usecase.EstimateVideoJobUseCase
import com.devfahim.upscaler.domain.usecase.PlanUpscaleUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EstimateVideoJobUseCaseTest {

    private val useCase = EstimateVideoJobUseCase()

    @Test
    fun `frame count derives from duration and fps`() {
        val e = useCase(1920, 1080, fps = 30f, durationMs = 10_000, requestedScale = 2, maxOutputHeight = 0)
        assertEquals(300, e.totalFrames)
    }

    @Test
    fun `zero fps falls back to 30`() {
        val e = useCase(1920, 1080, fps = 0f, durationMs = 1000, requestedScale = 2, maxOutputHeight = 0)
        assertEquals(30, e.totalFrames)
    }

    @Test
    fun `cap limits output height`() {
        val e = useCase(1920, 1080, fps = 30f, durationMs = 1000, requestedScale = 4, maxOutputHeight = 2160)
        assertTrue(e.outputHeight <= 2160)
        // Even dimensions for the encoder.
        assertEquals(0, e.outputHeight % 2)
        assertEquals(0, e.outputWidth % 2)
    }

    @Test
    fun `estimate is present with default per-frame cost`() {
        val e = useCase(640, 480, fps = 30f, durationMs = 2000, requestedScale = 2, maxOutputHeight = 0)
        assertNotNull(e.estimatedSeconds)
        assertTrue(e.estimatedSeconds!! > 0)
    }
}

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
        val p = useCase(3000, 2000, requestedScale = 4, nativeModelScale = 4, backendUsesGpu = true)
        assertEquals(2, p.effectiveScale)
        assertNotNull(p.scaleReducedNotice)
    }

    @Test
    fun `tiles generated for larger than tile images`() {
        val p = useCase(1000, 1000, requestedScale = 2, nativeModelScale = 2, backendUsesGpu = false)
        // CPU tile size 128 -> more than one tile.
        assertTrue(p.tiles.size > 1)
    }
}
