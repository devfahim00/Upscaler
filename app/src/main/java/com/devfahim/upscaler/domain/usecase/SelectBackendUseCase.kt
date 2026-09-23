package com.devfahim.upscaler.domain.usecase

import com.devfahim.upscaler.domain.model.BackendMode
import com.devfahim.upscaler.domain.model.BackendPreference
import com.devfahim.upscaler.domain.model.DeviceCapability

/**
 * Decides which ncnn backend to use.
 *
 * 1. If the user forced a backend in Settings, honour it (with a CPU
 *    fallback when GPU is forced but unavailable).
 * 2. AUTO: prefer Vulkan when a GPU is present, unless a stored benchmark
 *    proves the GPU is slower than the CPU (some low-end GPUs lose to a
 *    modern big.LITTLE CPU).
 *
 * Pure Kotlin - unit tested in BackendSelectorTest.
 */
class SelectBackendUseCase @javax.inject.Inject constructor() {

    data class Decision(val backend: BackendMode, val fellBackToCpu: Boolean)

    operator fun invoke(
        preference: BackendPreference,
        gpuCount: Int,
        gpuBenchmarkMs: Double?,   // null = never benchmarked
        cpuBenchmarkMs: Double?,
    ): Decision {
        val gpuAvailable = gpuCount > 0

        return when (preference) {
            BackendPreference.GPU ->
                if (gpuAvailable) Decision(BackendMode.GPU, fellBackToCpu = false)
                else Decision(BackendMode.CPU, fellBackToCpu = true)

            BackendPreference.CPU -> Decision(BackendMode.CPU, fellBackToCpu = false)

            BackendPreference.AUTO -> {
                if (!gpuAvailable) {
                    Decision(BackendMode.CPU, fellBackToCpu = true)
                } else if (gpuBenchmarkMs != null && cpuBenchmarkMs != null) {
                    // 15% hysteresis: keep the GPU unless it is clearly slower.
                    val gpuSlower = gpuBenchmarkMs > cpuBenchmarkMs * 1.15
                    if (gpuSlower) Decision(BackendMode.CPU, fellBackToCpu = false)
                    else Decision(BackendMode.GPU, fellBackToCpu = false)
                } else {
                    Decision(BackendMode.GPU, fellBackToCpu = false)
                }
            }
        }
    }

    /** Convenience: capability snapshot from a decision (cached app-wide). */
    fun capability(gpuCount: Int, decision: Decision): DeviceCapability =
        DeviceCapability(gpuCount = gpuCount, recommendedBackend = decision.backend)
}
