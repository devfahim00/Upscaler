package com.devfahim.upscaler

import com.devfahim.upscaler.domain.model.BackendMode
import com.devfahim.upscaler.domain.model.BackendPreference
import com.devfahim.upscaler.domain.usecase.SelectBackendUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendSelectorTest {

    private val useCase = SelectBackendUseCase()

    @Test
    fun `forced cpu always wins`() {
        val d = useCase(BackendPreference.CPU, gpuCount = 4, gpuBenchmarkMs = 1.0, cpuBenchmarkMs = 100.0)
        assertEquals(BackendMode.CPU, d.backend)
        assertFalse(d.fellBackToCpu)
    }

    @Test
    fun `forced gpu on gpu-less device falls back`() {
        val d = useCase(BackendPreference.GPU, gpuCount = 0, gpuBenchmarkMs = null, cpuBenchmarkMs = null)
        assertEquals(BackendMode.CPU, d.backend)
        assertTrue(d.fellBackToCpu)
    }

    @Test
    fun `forced gpu on capable device uses gpu`() {
        val d = useCase(BackendPreference.GPU, gpuCount = 1, gpuBenchmarkMs = null, cpuBenchmarkMs = null)
        assertEquals(BackendMode.GPU, d.backend)
        assertFalse(d.fellBackToCpu)
    }

    @Test
    fun `auto without gpu falls back`() {
        val d = useCase(BackendPreference.AUTO, gpuCount = 0, gpuBenchmarkMs = null, cpuBenchmarkMs = null)
        assertEquals(BackendMode.CPU, d.backend)
        assertTrue(d.fellBackToCpu)
    }

    @Test
    fun `auto with gpu and no benchmark prefers gpu`() {
        val d = useCase(BackendPreference.AUTO, gpuCount = 2, gpuBenchmarkMs = null, cpuBenchmarkMs = null)
        assertEquals(BackendMode.GPU, d.backend)
    }

    @Test
    fun `auto uses benchmark when gpu clearly slower`() {
        val d = useCase(BackendPreference.AUTO, gpuCount = 1, gpuBenchmarkMs = 200.0, cpuBenchmarkMs = 100.0)
        assertEquals(BackendMode.CPU, d.backend)
        assertFalse(d.fellBackToCpu)
    }

    @Test
    fun `auto hysteresis keeps gpu when only slightly slower`() {
        // GPU 10% slower than CPU: within the 15% hysteresis band -> GPU stays.
        val d = useCase(BackendPreference.AUTO, gpuCount = 1, gpuBenchmarkMs = 110.0, cpuBenchmarkMs = 100.0)
        assertEquals(BackendMode.GPU, d.backend)
    }

    @Test
    fun `auto prefers faster gpu`() {
        val d = useCase(BackendPreference.AUTO, gpuCount = 1, gpuBenchmarkMs = 40.0, cpuBenchmarkMs = 100.0)
        assertEquals(BackendMode.GPU, d.backend)
    }
}
