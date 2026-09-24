package com.devfahim.upscaler.data.engine

/**
 * Thin declaration surface for the C++ bridge in app/src/main/cpp/ncnn_jni.cpp.
 * Method names are looked up by the JVM - keep them in sync with the
 * `Java_com_devfahim_upscaler_data_engine_EngineBridge_*` symbols.
 */
object EngineBridge {
    init {
        System.loadLibrary("upscaler")
    }

    /** ncnn version string, e.g. "1.0.20260526". */
    external fun nativeGetNcnnVersion(): String

    /** Number of usable Vulkan devices; 0 on devices without usable Vulkan. */
    external fun nativeGetGpuCount(): Int

    /**
     * Loads a model; returns a native engine handle or 0 on failure.
     *
     * @param forceFp32 disables ncnn's fp16 blob storage/packing/arithmetic
     *        for this engine. Required for models whose activations exceed
     *        the fp16 range (e.g. 4x-PurePhoto's channel-attention logits);
     *        with fp16 buffers those models produce NaN, i.e. a black image.
     */
    external fun nativeCreateEngine(
        paramPath: String,
        binPath: String,
        useGpu: Boolean,
        numThreads: Int,
        forceFp32: Boolean,
    ): Long

    external fun nativeDestroyEngine(handle: Long)

    /**
     * Upscales one ARGB tile through the loaded model.
     * @return ARGB pixels of size (width*nativeScale) x (height*nativeScale),
     *         or null on inference failure.
     */
    external fun nativeUpscaleTile(handle: Long, pixels: IntArray, width: Int, height: Int, nativeScale: Int): IntArray?

    /**
     * Average ms per 64x64 inference; negative on failure.
     * [forceFp32] mirrors [nativeCreateEngine]'s fp32 override so backend
     * benchmarks measure the precision the session will actually use.
     */
    external fun nativeBenchmark(
        paramPath: String,
        binPath: String,
        useGpu: Boolean,
        numThreads: Int,
        iterations: Int,
        forceFp32: Boolean,
    ): Double
}
