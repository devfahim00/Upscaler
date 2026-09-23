package com.devfahim.upscaler

import com.devfahim.upscaler.domain.model.ModelType
import com.devfahim.upscaler.domain.model.ScaleOption
import com.devfahim.upscaler.domain.tiling.MemoryGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun `photo models expose 2x or 4x native scales`() {
        ModelType.photoModels.forEach { m ->
            assertTrue("${m.name} has invalid scale", m.nativeScale in 2..4)
        }
    }

    @Test
    fun `hdr net is an internal pass, not a selectable upscale model`() {
        // HDRNet runs at identity resolution on a 1/12 downscaled copy.
        assertEquals(1, ModelType.HDR_NET.nativeScale)
        assertFalse(ModelType.photoModels.contains(ModelType.HDR_NET))
        assertEquals("hdrnet", ModelType.HDR_NET.assetDir)
        assertFalse(ModelType.HDR_NET.supportsWdnInterpolation)
        assertNull(ModelType.HDR_NET.standInFor)
    }

    @Test
    fun `photo catalog contains exactly the four photo models`() {
        assertEquals(
            listOf(
                ModelType.GENERAL_PHOTO_X4,
                ModelType.HQ_PHOTO_X4,
                ModelType.GENERAL_PHOTO_X2,
                ModelType.ANIME_ILLUSTRATION_X4,
            ),
            ModelType.photoModels,
        )
    }

    @Test
    fun `high quality model uses smaller tiles than compact models`() {
        // RRDBNet keeps many more feature maps alive - must use smaller tiles.
        assertTrue(ModelType.HQ_PHOTO_X4.cpuTileSize < ModelType.GENERAL_PHOTO_X4.cpuTileSize)
        assertTrue(ModelType.HQ_PHOTO_X4.gpuTileSize < ModelType.GENERAL_PHOTO_X4.gpuTileSize)
        assertEquals(MemoryGuard.HQ_CPU_TILE_SIZE, ModelType.HQ_PHOTO_X4.cpuTileSize)
        assertEquals(MemoryGuard.HQ_GPU_TILE_SIZE, ModelType.HQ_PHOTO_X4.gpuTileSize)
    }

    @Test
    fun `tile size selection follows the backend`() {
        ModelType.entries.forEach { m ->
            assertEquals(m.gpuTileSize, m.tileSizeFor(backendUsesGpu = true))
            assertEquals(m.cpuTileSize, m.tileSizeFor(backendUsesGpu = false))
        }
    }

    @Test
    fun `only the general photo x4 model supports wdn interpolation`() {
        ModelType.entries.forEach { m ->
            assertEquals(
                "${m.name} wdn support",
                m == ModelType.GENERAL_PHOTO_X4,
                m.supportsWdnInterpolation,
            )
        }
    }

    @Test
    fun `wdn companion asset dir is distinct from the base model`() {
        assertTrue(ModelType.WDN_ASSET_DIR.isNotBlank())
        assertFalse(ModelType.entries.any { it.assetDir == ModelType.WDN_ASSET_DIR })
        assertFalse(ModelType.photoModels.any { it.assetDir == ModelType.WDN_ASSET_DIR })
    }

    @Test
    fun `stand-in models are explicitly flagged`() {
        assertNotNull(ModelType.GENERAL_PHOTO_X2.standInFor)
        // The purpose-trained entries are NOT stand-ins.
        assertNull(ModelType.GENERAL_PHOTO_X4.standInFor)
        assertNull(ModelType.HQ_PHOTO_X4.standInFor)
        assertNull(ModelType.ANIME_ILLUSTRATION_X4.standInFor)
    }

    @Test
    fun `scale options cover hdr-only 1 plus 2 and 4`() {
        assertEquals(listOf(1, 2, 4), ScaleOption.entries.map { it.factor })
    }

    @Test
    fun `hdr-only is not offered as a default scale`() {
        // The Settings default-scale picker only offers real upscaling;
        // X1 is a per-run choice in the options sheet.
        assertEquals(listOf(ScaleOption.X2, ScaleOption.X4), ScaleOption.upscaleDefaults)
        assertFalse(ScaleOption.upscaleDefaults.contains(ScaleOption.X1))
    }
}
