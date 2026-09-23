package com.devfahim.upscaler.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devfahim.upscaler.MainActivity
import com.devfahim.upscaler.R
import com.devfahim.upscaler.applyAppLanguage
import com.devfahim.upscaler.data.storage.StorageManager
import com.devfahim.upscaler.domain.model.BackendPreference
import com.devfahim.upscaler.domain.model.ModelType
import com.devfahim.upscaler.domain.model.OutputFormat
import com.devfahim.upscaler.domain.model.ScaleOption
import com.devfahim.upscaler.domain.model.ThemeMode
import com.devfahim.upscaler.domain.repository.AppSettings
import com.devfahim.upscaler.domain.repository.InferenceEngine
import com.devfahim.upscaler.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val engine: InferenceEngine,
    private val storage: StorageManager,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _benchBusy = MutableStateFlow(false)
    val benchBusy: StateFlow<Boolean> = _benchBusy.asStateFlow()

    private val _cacheBytes = MutableStateFlow(0L)
    val cacheBytes: StateFlow<Long> = _cacheBytes.asStateFlow()

    fun refreshCacheSize() {
        viewModelScope.launch {
            _cacheBytes.value = withContext(Dispatchers.IO) { storage.cacheSizeBytes() }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { storage.clearCache() }
            refreshCacheSize()
        }
    }

    fun setDefaultModel(m: ModelType) = viewModelScope.launch { settingsRepository.setDefaultPhotoModel(m) }
    fun setDefaultScale(s: ScaleOption) = viewModelScope.launch { settingsRepository.setDefaultScale(s) }
    fun setFormat(f: OutputFormat) = viewModelScope.launch { settingsRepository.setOutputFormat(f) }
    fun setQuality(q: Int) = viewModelScope.launch { settingsRepository.setJpegQuality(q) }
    fun setBackend(p: BackendPreference) = viewModelScope.launch { settingsRepository.setBackendPreference(p) }
    fun setTheme(t: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(t) }
    fun setLanguage(tag: String) = viewModelScope.launch { settingsRepository.setLanguageTag(tag) }

    /** Runs both backends on the default model and stores the timings. */
    fun runBenchmark() {
        if (_benchBusy.value) return
        _benchBusy.value = true
        viewModelScope.launch(Dispatchers.Default) {
            val model = settings.value?.defaultPhotoModel ?: ModelType.GENERAL_PHOTO_X4
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            val gpu = runCatching {
                engine.benchmark(model, com.devfahim.upscaler.domain.model.BackendMode.GPU, threads, 8)
            }.getOrDefault(-1.0)
            val cpu = runCatching {
                engine.benchmark(model, com.devfahim.upscaler.domain.model.BackendMode.CPU, threads, 8)
            }.getOrDefault(-1.0)
            settingsRepository.storeBenchmark(
                gpuMs = gpu.takeIf { it > 0 },
                cpuMs = cpu.takeIf { it > 0 },
                modelKey = model.name,
            )
            _benchBusy.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val benchBusy by viewModel.benchBusy.collectAsStateWithLifecycle()
    val cacheBytes by viewModel.cacheBytes.collectAsStateWithLifecycle()
    val context = LocalContext.current

    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refreshCacheSize() }

    val s = settings ?: return

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.nav_settings)) },
            navigationIcon = {
                IconButton(onClick = onAbout) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_about))
                }
            },
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---- Processing defaults ----
            SettingsHeader(stringResource(R.string.settings_processing))

            Text(stringResource(R.string.settings_default_model), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ModelType.GENERAL_PHOTO_X4, ModelType.ANIME_ILLUSTRATION_X4).forEach { m ->
                    FilterChip(
                        selected = s.defaultPhotoModel == m,
                        onClick = { viewModel.setDefaultModel(m) },
                        label = { Text(stringResource(m.displayNameRes)) },
                    )
                }
            }

            Text(stringResource(R.string.settings_default_scale), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScaleOption.upscaleDefaults.forEach { sc ->
                    FilterChip(
                        selected = s.defaultScale == sc,
                        onClick = { viewModel.setDefaultScale(sc) },
                        label = { Text("x${sc.factor}") },
                    )
                }
            }

            Text(stringResource(R.string.settings_format), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutputFormat.entries.forEach { f ->
                    FilterChip(
                        selected = s.outputFormat == f,
                        onClick = { viewModel.setFormat(f) },
                        label = { Text(stringResource(f.labelRes)) },
                    )
                }
            }

            if (s.outputFormat.supportsQuality) {
                var quality by remember(s.jpegQuality) { mutableFloatStateOf(s.jpegQuality.toFloat()) }
                Text(stringResource(R.string.options_quality, quality.toInt()), style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = quality,
                    onValueChange = { quality = it },
                    onValueChangeFinished = { viewModel.setQuality(quality.toInt()) },
                    valueRange = 50f..100f,
                    steps = 9,
                )
            }

            HorizontalDivider()
            SettingsHeader(stringResource(R.string.settings_backend))

            Text(stringResource(R.string.settings_backend_desc), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackendPreference.entries.forEach { p ->
                    FilterChip(
                        selected = s.backendPreference == p,
                        onClick = { viewModel.setBackend(p) },
                        label = {
                            Text(
                                stringResource(
                                    when (p) {
                                        BackendPreference.AUTO -> R.string.backend_auto
                                        BackendPreference.GPU -> R.string.backend_gpu
                                        BackendPreference.CPU -> R.string.backend_cpu
                                    }
                                )
                            )
                        },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { viewModel.runBenchmark() }, enabled = !benchBusy) {
                    Text(stringResource(R.string.settings_benchmark))
                }
                if (benchBusy) {
                    CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                } else if (s.gpuBenchmarkMs != null && s.cpuBenchmarkMs != null) {
                    Text(
                        stringResource(
                            R.string.settings_bench_result,
                            s.gpuBenchmarkMs!!, s.cpuBenchmarkMs!!,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()
            SettingsHeader(stringResource(R.string.settings_appearance))

            Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { t ->
                    FilterChip(
                        selected = s.themeMode == t,
                        onClick = { viewModel.setTheme(t) },
                        label = {
                            Text(
                                stringResource(
                                    when (t) {
                                        ThemeMode.SYSTEM -> R.string.theme_system
                                        ThemeMode.LIGHT -> R.string.theme_light
                                        ThemeMode.DARK -> R.string.theme_dark
                                    }
                                )
                            )
                        },
                    )
                }
            }

            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system", "en", "bn").forEach { tag ->
                    FilterChip(
                        selected = s.languageTag == tag,
                        onClick = {
                            viewModel.setLanguage(tag)
                            (context as? MainActivity)?.let { applyAppLanguage(it, tag) }
                        },
                        label = {
                            Text(
                                when (tag) {
                                    "en" -> "English"
                                    "bn" -> "বাংলা"
                                    else -> stringResource(R.string.language_system)
                                }
                            )
                        },
                    )
                }
            }

            HorizontalDivider()
            SettingsHeader(stringResource(R.string.settings_storage))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.settings_cache_size, cacheBytes / (1024.0 * 1024.0)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { viewModel.clearCache() }) {
                    Text(stringResource(R.string.settings_clear_cache))
                }
            }

            HorizontalDivider()
            SettingsHeader(stringResource(R.string.settings_about))

            OutlinedButton(onClick = onAbout, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.nav_about))
            }

            Text(
                stringResource(R.string.settings_version, com.devfahim.upscaler.BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}
