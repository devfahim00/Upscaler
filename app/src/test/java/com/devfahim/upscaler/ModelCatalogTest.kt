package com.devfahim.upscaler

import com.devfahim.upscaler.domain.model.ModelType
import com.devfahim.upscaler.domain.model.ScaleOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun `all models expose positive native scales`() {
        ModelType.entries.forEach { m ->
            assertTrue("${m.name} has invalid scale", m.nativeScale in 2..4)
        }
    }

    @Test
    fun `video models are flagged and separated`() {
        assertEquals(2, ModelType.videoModels.size)
        assertEquals(4, ModelType.photoModels.size)
        ModelType.videoModels.forEach { assertTrue(it.isVideoOptimized) }
        ModelType.photoModels.forEach { assertTrue(!it.isVideoOptimized) }
    }

    @Test
    fun `stand-in models are explicitly flagged`() {
        assertNotNull(ModelType.GENERAL_PHOTO_X2.standInFor)
        assertNotNull(ModelType.DENOISE_X4.standInFor)
        // The two purpose-trained entries are NOT stand-ins.
        assertEquals(null, ModelType.GENERAL_PHOTO_X4.standInFor)
        assertEquals(null, ModelType.VIDEO_X2.standInFor)
        assertEquals(null, ModelType.VIDEO_X4.standInFor)
        assertEquals(null, ModelType.ANIME_ILLUSTRATION_X4.standInFor)
    }

    @Test
    fun `a 2x and a 4x model exist for video`() {
        val scales = ModelType.videoModels.map { it.nativeScale }.toSet()
        assertEquals(setOf(2, 4), scales)
    }

    @Test
    fun `scale options cover 2 and 4`() {
        assertEquals(listOf(2, 4), ScaleOption.entries.map { it.factor })
    }
}
