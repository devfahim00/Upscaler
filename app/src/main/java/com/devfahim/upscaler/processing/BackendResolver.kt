package com.devfahim.upscaler.processing

import com.devfahim.upscaler.domain.repository.InferenceEngine
import com.devfahim.upscaler.domain.repository.SettingsRepository
import com.devfahim.upscaler.domain.usecase.SelectBackendUseCase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the active ncnn backend by combining the user preference, the
 * device's GPU count and any stored benchmark results.
 */
@Singleton
class BackendResolver @Inject constructor(
    private val engine: InferenceEngine,
    private val selectBackend: SelectBackendUseCase,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun resolve(): SelectBackendUseCase.Decision {
        val settings = settingsRepository.current()
        val caps = engine.capabilities()
        return selectBackend(
            preference = settings.backendPreference,
            gpuCount = caps.gpuCount,
            gpuBenchmarkMs = settings.gpuBenchmarkMs,
            cpuBenchmarkMs = settings.cpuBenchmarkMs,
        )
    }
}
