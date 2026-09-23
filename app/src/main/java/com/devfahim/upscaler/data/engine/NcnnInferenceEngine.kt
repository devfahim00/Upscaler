package com.devfahim.upscaler.data.engine

import android.util.Log
import com.devfahim.upscaler.domain.model.BackendMode
import com.devfahim.upscaler.domain.model.ModelType
import com.devfahim.upscaler.domain.repository.EngineCapabilities
import com.devfahim.upscaler.domain.repository.InferenceEngine
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [InferenceEngine] backed by the real ncnn JNI bridge.
 *
 * The Vulkan instance is created lazily exactly once per process
 * (mirroring `ensure_gpu_instance()` on the native side).
 */
@Singleton
class NcnnInferenceEngine @Inject constructor(
    private val modelAssets: ModelAssetManager,
) : InferenceEngine {

    private val gpuProbed = AtomicBoolean(false)

    @Volatile
    private var cachedGpuCount: Int = -1

    override fun capabilities(): EngineCapabilities {
        if (gpuProbed.compareAndSet(false, true)) {
            cachedGpuCount = try {
                EngineBridge.nativeGetGpuCount()
            } catch (t: Throwable) {
                Log.w(TAG, "gpu probe failed", t)
                0
            }
        }
        return EngineCapabilities(
            ncnnVersion = try {
                EngineBridge.nativeGetNcnnVersion()
            } catch (t: Throwable) {
                Log.w(TAG, "version probe failed", t)
                "unknown"
            },
            gpuCount = cachedGpuCount,
        )
    }

    override fun createSession(model: ModelType, backend: BackendMode, threads: Int): Long {
        val dir = modelAssets.modelDir(model.assetDir)
        return EngineBridge.nativeCreateEngine(
            paramPath = dir.resolve("model.param").absolutePath,
            binPath = dir.resolve("model.bin").absolutePath,
            useGpu = backend == BackendMode.GPU,
            numThreads = threads,
        )
    }

    override fun destroySession(handle: Long) {
        if (handle != 0L) EngineBridge.nativeDestroyEngine(handle)
    }

    override fun upscaleTile(
        handle: Long,
        argb: IntArray,
        width: Int,
        height: Int,
        nativeScale: Int,
    ): IntArray? = EngineBridge.nativeUpscaleTile(handle, argb, width, height, nativeScale)

    override fun benchmark(model: ModelType, backend: BackendMode, threads: Int, iterations: Int): Double {
        val dir = modelAssets.modelDir(model.assetDir)
        return EngineBridge.nativeBenchmark(
            dir.resolve("model.param").absolutePath,
            dir.resolve("model.bin").absolutePath,
            backend == BackendMode.GPU,
            threads,
            iterations,
        )
    }

    private companion object {
        const val TAG = "NcnnInferenceEngine"
    }
}
