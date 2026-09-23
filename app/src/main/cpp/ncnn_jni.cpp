// ============================================================================
// Upscaler - JNI bridge to ncnn
// ============================================================================
// Exposes a minimal, stable surface to Kotlin:
//
//   nativeGetNcnnVersion()                     -> String
//   nativeGetGpuCount()                        -> Int      (Vulkan devices)
//   nativeCreateEngine(param, bin, gpu, threads) -> Long    (engine handle)
//   nativeDestroyEngine(handle)                 -> Unit
//   nativeUpscaleTile(handle, pixels, w, h, s)  -> IntArray (ARGB, w*s x h*s)
//   nativeBenchmark(param, bin, gpu, threads, iters) -> Double (avg ms)
//
// Pixel format: ARGB_8888 packed in an IntArray (Android Bitmap.getPixels
// layout: 0xAARRGGBB). Channel order fed to the network is R, G, B with
// values normalized to [0, 1] - this matches how the Real-ESRGAN ncnn
// models were converted (from_rgb + mean_vals=0 / norm_vals=1/255).
//
// IMPORTANT: the ncnn prebuilt propagates -fno-rtti -fno-exceptions to this
// translation unit through its imported CMake target. Do NOT use try/catch,
// dynamic_cast or std::current_exception() here.
// ============================================================================

#include <jni.h>

#include <chrono>
#include <cstdint>
#include <mutex>
#include <string>

#include <ncnn/gpu.h>
#include <ncnn/mat.h>
#include <ncnn/net.h>
#include <ncnn/platform.h>

// Input/output blob names. These match every model bundled in
// app/src/main/assets/models (realesr-general-x4v3, realesr-animevideov3-x2,
// realesr-animevideov3-x4 - all SRVGGNetCompact exports). If you add a model
// with different blob names, adjust here (or convert the model so its input
// blob is "data" and output blob is "output" - see README.md).
static const char* kInputBlob = "data";
static const char* kOutputBlob = "output";

namespace {

struct NativeEngine {
    ncnn::Net net;
    std::mutex mutex; // serializes inference on this engine
};

bool g_gpu_instance_initialized = false;

void ensure_gpu_instance() {
    if (!g_gpu_instance_initialized) {
        ncnn::create_gpu_instance();
        g_gpu_instance_initialized = true;
    }
}

inline uint8_t clamp_u8(float v) {
    // round-half-up and clamp to [0, 255]
    int i = (int) (v + 0.5f);
    if (i < 0) return 0;
    if (i > 255) return 255;
    return (uint8_t) i;
}

// ARGB IntArray -> ncnn::Mat with 3 float planes (R, G, B) in [0,1].
void argb_to_mat(const jint* px, int w, int h, ncnn::Mat& m) {
    m.create(w, h, 3);
    float* rplane = m.channel(0);
    float* gplane = m.channel(1);
    float* bplane = m.channel(2);
    const int n = w * h;
    const float scale = 1.0f / 255.0f;
    for (int i = 0; i < n; i++) {
        const jint p = px[i];
        rplane[i] = (float) ((p >> 16) & 0xff) * scale;
        gplane[i] = (float) ((p >> 8) & 0xff) * scale;
        bplane[i] = (float) (p & 0xff) * scale;
    }
}

// ncnn::Mat (3 float planes in [0,1]) -> ARGB IntArray.
void mat_to_argb(const ncnn::Mat& m, jint* out) {
    const float* rplane = m.channel(0);
    const float* gplane = m.channel(1);
    const float* bplane = m.channel(2);
    const int n = m.w * m.h;
    for (int i = 0; i < n; i++) {
        const jint a = 0xff;
        const jint r = clamp_u8(rplane[i] * 255.0f);
        const jint g = clamp_u8(gplane[i] * 255.0f);
        const jint b = clamp_u8(bplane[i] * 255.0f);
        out[i] = (a << 24) | (r << 16) | (g << 8) | b;
    }
}

inline std::string jstring_to_std(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

} // namespace

// ----------------------------------------------------------------------------
// Version / device capabilities
// ----------------------------------------------------------------------------

extern "C" JNIEXPORT jstring JNICALL
Java_com_devfahim_upscaler_data_engine_EngineBridge_nativeGetNcnnVersion(
        JNIEnv* env, jclass /*clazz*/) {
    return env->NewStringUTF(NCNN_VERSION_STRING);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_devfahim_upscaler_data_engine_EngineBridge_nativeGetGpuCount(
        JNIEnv* /*env*/, jclass /*clazz*/) {
    ensure_gpu_instance();
    return ncnn::get_gpu_count();
}

// ----------------------------------------------------------------------------
// Engine lifecycle
// ----------------------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_com_devfahim_upscaler_data_engine_EngineBridge_nativeCreateEngine(
        JNIEnv* env, jclass /*clazz*/, jstring paramPath, jstring binPath,
        jboolean useGpu, jint numThreads) {
    const std::string param = jstring_to_std(env, paramPath);
    const std::string bin = jstring_to_std(env, binPath);

    auto* engine = new NativeEngine();

    if (useGpu) {
        ensure_gpu_instance();
        if (ncnn::get_gpu_count() <= 0) {
            delete engine;
            return 0; // caller falls back to CPU
        }
        engine->net.opt.use_vulkan_compute = true;
        engine->net.opt.gpu_index = 0;
    } else {
        engine->net.opt.use_vulkan_compute = false;
    }

    // Size the CPU thread pool. When the Vulkan path is active ncnn still
    // uses CPU threads for pre/post processing stages.
    engine->net.opt.num_threads =
            numThreads > 0 ? numThreads : ncnn::get_cpu_count();

    if (engine->net.load_param(param.c_str()) != 0) {
        delete engine;
        return 0;
    }
    if (engine->net.load_model(bin.c_str()) != 0) {
        delete engine;
        return 0;
    }
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_devfahim_upscaler_data_engine_EngineBridge_nativeDestroyEngine(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    if (handle == 0) return;
    auto* engine = reinterpret_cast<NativeEngine*>(handle);
    delete engine;
}

// ----------------------------------------------------------------------------
// Tile inference
// ----------------------------------------------------------------------------

extern "C" JNIEXPORT jintArray JNICALL
Java_com_devfahim_upscaler_data_engine_EngineBridge_nativeUpscaleTile(
        JNIEnv* env, jclass /*clazz*/, jlong handle, jintArray pixels,
        jint width, jint height, jint /*nativeScale*/) {
    if (handle == 0 || pixels == nullptr || width <= 0 || height <= 0) {
        return nullptr;
    }
    auto* engine = reinterpret_cast<NativeEngine*>(handle);

    jboolean isCopy = JNI_FALSE;
    jint* in = env->GetIntArrayElements(pixels, &isCopy);
    if (in == nullptr) return nullptr;

    ncnn::Mat in_mat;
    argb_to_mat(in, width, height, in_mat);
    if (isCopy == JNI_TRUE) {
        env->ReleaseIntArrayElements(pixels, in, JNI_ABORT);
        in = nullptr;
    }

    ncnn::Mat out_mat;
    {
        std::lock_guard<std::mutex> lock(engine->mutex);
        ncnn::Extractor ex = engine->net.create_extractor();
        if (ex.input(kInputBlob, in_mat) != 0) {
            if (in != nullptr) env->ReleaseIntArrayElements(pixels, in, JNI_ABORT);
            return nullptr;
        }
        if (ex.extract(kOutputBlob, out_mat) != 0) {
            if (in != nullptr) env->ReleaseIntArrayElements(pixels, in, JNI_ABORT);
            return nullptr;
        }
    }
    if (in != nullptr) {
        env->ReleaseIntArrayElements(pixels, in, JNI_ABORT);
    }

    if (out_mat.empty() || out_mat.c != 3) return nullptr;

    const jint ow = out_mat.w;
    const jint oh = out_mat.h;
    jintArray result = env->NewIntArray((jsize) (ow * oh));
    if (result == nullptr) return nullptr;

    jint* out = env->GetIntArrayElements(result, &isCopy);
    if (out == nullptr) return nullptr;
    mat_to_argb(out_mat, out);
    env->ReleaseIntArrayElements(result, out, 0); // copy back; keep contents

    return result;
}

// ----------------------------------------------------------------------------
// Benchmark: average milliseconds for one 64x64 -> native-scale inference.
// Returns a positive double on success, -1 on failure (model could not be
// loaded or the requested backend is not usable).
// ----------------------------------------------------------------------------

extern "C" JNIEXPORT jdouble JNICALL
Java_com_devfahim_upscaler_data_engine_EngineBridge_nativeBenchmark(
        JNIEnv* env, jclass /*clazz*/, jstring paramPath, jstring binPath,
        jboolean useGpu, jint numThreads, jint iterations) {
    const std::string param = jstring_to_std(env, paramPath);
    const std::string bin = jstring_to_std(env, binPath);

    ncnn::Net net;
    if (useGpu) {
        ensure_gpu_instance();
        if (ncnn::get_gpu_count() <= 0) return -1.0;
        net.opt.use_vulkan_compute = true;
        net.opt.gpu_index = 0;
    } else {
        net.opt.use_vulkan_compute = false;
    }
    net.opt.num_threads = numThreads > 0 ? numThreads : ncnn::get_cpu_count();

    if (net.load_param(param.c_str()) != 0) return -1.0;
    if (net.load_model(bin.c_str()) != 0) return -1.0;

    ncnn::Mat in_mat(64, 64, 3);
    in_mat.fill(0.5f);

    // Warm-up (shader compilation / pipeline cache population on GPU).
    {
        ncnn::Extractor ex = net.create_extractor();
        ncnn::Mat out_mat;
        if (ex.input(kInputBlob, in_mat) != 0) return -1.0;
        if (ex.extract(kOutputBlob, out_mat) != 0) return -1.0;
    }

    const int iters = iterations > 0 ? iterations : 8;
    const auto t0 = std::chrono::steady_clock::now();
    for (int i = 0; i < iters; i++) {
        ncnn::Extractor ex = net.create_extractor();
        ncnn::Mat out_mat;
        if (ex.input(kInputBlob, in_mat) != 0) return -1.0;
        if (ex.extract(kOutputBlob, out_mat) != 0) return -1.0;
    }
    const auto t1 = std::chrono::steady_clock::now();

    const double ms =
            std::chrono::duration<double, std::milli>(t1 - t0).count() / iters;
    return (jdouble) ms;
}
