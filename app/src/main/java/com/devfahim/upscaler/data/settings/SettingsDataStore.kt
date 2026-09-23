package com.devfahim.upscaler.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.devfahim.upscaler.domain.model.BackendPreference
import com.devfahim.upscaler.domain.model.ModelType
import com.devfahim.upscaler.domain.model.OutputFormat
import com.devfahim.upscaler.domain.model.ScaleOption
import com.devfahim.upscaler.domain.model.ThemeMode
import com.devfahim.upscaler.domain.repository.AppSettings
import com.devfahim.upscaler.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private object Keys {
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val DEFAULT_MODEL = stringPreferencesKey("default_photo_model")
        val DEFAULT_SCALE = stringPreferencesKey("default_scale")
        val OUTPUT_FORMAT = stringPreferencesKey("output_format")
        val JPEG_QUALITY = intPreferencesKey("jpeg_quality")
        val BACKEND_PREF = stringPreferencesKey("backend_preference")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LANGUAGE_TAG = stringPreferencesKey("language_tag")
        val GPU_BENCH_MS = doublePreferencesKey("gpu_benchmark_ms")
        val CPU_BENCH_MS = doublePreferencesKey("cpu_benchmark_ms")
        val BENCH_MODEL = stringPreferencesKey("benchmark_model_key")
        val HDR_MODELS = stringSetPreferencesKey("hdr_enabled_models")
    }

    override val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            onboardingDone = p[Keys.ONBOARDING_DONE] ?: false,
            defaultPhotoModel = enumOrDefault(
                p[Keys.DEFAULT_MODEL], ModelType.GENERAL_PHOTO_X4.name, ModelType.GENERAL_PHOTO_X4
            ),
            defaultScale = enumOrDefault(
                p[Keys.DEFAULT_SCALE], ScaleOption.X4.name, ScaleOption.X4
            ),
            outputFormat = enumOrDefault(
                p[Keys.OUTPUT_FORMAT], OutputFormat.PNG.name, OutputFormat.PNG
            ),
            jpegQuality = (p[Keys.JPEG_QUALITY] ?: 92).coerceIn(1, 100),
            backendPreference = enumOrDefault(
                p[Keys.BACKEND_PREF], BackendPreference.AUTO.name, BackendPreference.AUTO
            ),
            themeMode = enumOrDefault(
                p[Keys.THEME_MODE], ThemeMode.SYSTEM.name, ThemeMode.SYSTEM
            ),
            languageTag = p[Keys.LANGUAGE_TAG] ?: "system",
            gpuBenchmarkMs = p[Keys.GPU_BENCH_MS],
            cpuBenchmarkMs = p[Keys.CPU_BENCH_MS],
            benchmarkModelKey = p[Keys.BENCH_MODEL],
            hdrEnabledModels = p[Keys.HDR_MODELS].orEmpty()
                .mapNotNull { name -> runCatching { ModelType.valueOf(name) }.getOrNull() }
                .toSet(),
        )
    }

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun setOnboardingDone() {
        context.dataStore.edit { it[Keys.ONBOARDING_DONE] = true }
    }

    override suspend fun setDefaultPhotoModel(model: ModelType) {
        context.dataStore.edit { it[Keys.DEFAULT_MODEL] = model.name }
    }

    override suspend fun setDefaultScale(scale: ScaleOption) {
        context.dataStore.edit { it[Keys.DEFAULT_SCALE] = scale.name }
    }

    override suspend fun setOutputFormat(format: OutputFormat) {
        context.dataStore.edit { it[Keys.OUTPUT_FORMAT] = format.name }
    }

    override suspend fun setJpegQuality(quality: Int) {
        context.dataStore.edit { it[Keys.JPEG_QUALITY] = quality.coerceIn(1, 100) }
    }

    override suspend fun setBackendPreference(pref: BackendPreference) {
        context.dataStore.edit { it[Keys.BACKEND_PREF] = pref.name }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    override suspend fun setLanguageTag(tag: String) {
        context.dataStore.edit { it[Keys.LANGUAGE_TAG] = tag }
    }

    override suspend fun storeBenchmark(gpuMs: Double?, cpuMs: Double?, modelKey: String) {
        context.dataStore.edit {
            if (gpuMs != null) it[Keys.GPU_BENCH_MS] = gpuMs
            if (cpuMs != null) it[Keys.CPU_BENCH_MS] = cpuMs
            it[Keys.BENCH_MODEL] = modelKey
        }
    }

    override suspend fun setHdrEnabled(model: ModelType, enabled: Boolean) {
        context.dataStore.edit { p ->
            val current = p[Keys.HDR_MODELS].orEmpty()
            p[Keys.HDR_MODELS] = if (enabled) {
                (current + model.name).toSet()
            } else {
                (current - model.name).toSet()
            }
        }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(
        stored: String?,
        defaultName: String,
        default: T,
    ): T = stored?.let { s ->
        runCatching { enumValueOf<T>(s) }.getOrNull()
    } ?: default
}
