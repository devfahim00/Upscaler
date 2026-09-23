package com.devfahim.upscaler

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.devfahim.upscaler.data.engine.EngineBridge
import com.devfahim.upscaler.data.engine.ModelAssetManager
import com.devfahim.upscaler.domain.model.BackendMode
import com.devfahim.upscaler.domain.model.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke test: loads the bundled ncnn model, runs a 32x32 tile
 * through the CPU backend and checks that the output has the expected
 * dimensions and is not empty/black.
 */
@RunWith(AndroidJUnit4::class)
class InferenceSmokeTest {

    @Test
    fun cpuInferenceProducesUpscaledOutput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val assets = ModelAssetManager(context)
        assets.prepareIfNeeded()
        val dir = assets.modelDir(ModelType.GENERAL_PHOTO_X4.assetDir)

        val handle = EngineBridge.nativeCreateEngine(
            dir.resolve("model.param").absolutePath,
            dir.resolve("model.bin").absolutePath,
            useGpu = false,
            numThreads = 2,
        )
        assertTrue("engine handle was 0", handle != 0L)

        try {
            val size = 32
            val input = IntArray(size * size) { i ->
                // Simple gradient pattern (not uniform).
                val x = i % size
                val y = i / size
                (0xFF shl 24) or (x * 8 shl 16) or (y * 8 shl 8) or 0x40
            }
            val out = EngineBridge.nativeUpscaleTile(handle, input, size, size, 4)
            assertTrue("tile output was null", out != null)
            assertEquals(size * 4 * size * 4, out!!.size)

            // The network is a residual architecture: output cannot be all-black
            // for a non-black gradient input.
            var nonBlack = 0
            out.forEach { if (it and 0x00FFFFFF != 0) nonBlack++ }
            assertTrue("output looks black", nonBlack > out.size / 2)
        } finally {
            EngineBridge.nativeDestroyEngine(handle)
        }
    }

    @Test
    fun gpuProbeDoesNotCrash() {
        // On devices without Vulkan this must return 0 rather than throw.
        val count = EngineBridge.nativeGetGpuCount()
        assertTrue(count >= 0)
    }

    @Test
    fun ncnnVersionIsReported() {
        val v = EngineBridge.nativeGetNcnnVersion()
        assertTrue(v.isNotBlank())
    }
}
