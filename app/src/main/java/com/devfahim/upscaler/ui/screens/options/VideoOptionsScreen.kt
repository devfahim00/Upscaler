package com.devfahim.upscaler.ui.screens.options

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.devfahim.upscaler.R
import com.devfahim.upscaler.di.InferenceDispatcher
import com.devfahim.upscaler.domain.model.ModelType
import com.devfahim.upscaler.domain.model.ScaleOption
import com.devfahim.upscaler.domain.model.VideoCapOption
import com.devfahim.upscaler.domain.repository.VideoInfo
import com.devfahim.upscaler.domain.repository.VideoMetadataReader
import com.devfahim.upscaler.domain.usecase.EstimateVideoJobUseCase
import com.devfahim.upscaler.processing.JobController
import com.devfahim.upscaler.processing.VideoUpscaleEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class VideoOptionsViewModel @Inject constructor(
    private val metadataReader: VideoMetadataReader,
    private val estimate: EstimateVideoJobUseCase,
    private val videoEngine: VideoUpscaleEngine,
    private val jobController: JobController,
    @InferenceDispatcher private val inferenceDispatcher: CoroutineDispatcher,
) : ViewModel() {

    data class UiState(
        val info: VideoInfo? = null,
        val model: ModelType = ModelType.VIDEO_X2,
        val scale: ScaleOption = ScaleOption.X2,
        val cap: VideoCapOption = VideoCapOption.NONE,
        val previewBusy: Boolean = false,
        val previewFiles: List<File> = emptyList(),
        val starting: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load(uri: Uri) {
        if (_state.value.info != null) return
        viewModelScope.launch {
            val info = runCatching { metadataReader.read(uri.toString()) }.getOrNull()
            _state.value = _state.value.copy(info = info)
        }
    }

    fun setModel(model: ModelType) {
        _state.value = _state.value.copy(
            model = model,
            scale = if (model.nativeScale < _state.value.scale.factor) ScaleOption.X2 else _state.value.scale,
        )
    }

    fun setScale(scale: ScaleOption) {
        _state.value = _state.value.copy(scale = scale)
    }

    fun setCap(cap: VideoCapOption) {
        _state.value = _state.value.copy(cap = cap)
    }

    fun estimateSeconds(): Long? {
        val s = _state.value
        val info = s.info ?: return null
        val e = estimate(
            width = info.width,
            height = info.height,
            fps = info.fps,
            durationMs = info.durationMs,
            requestedScale = s.scale.factor,
            maxOutputHeight = s.cap.maxHeight,
        )
        return e.estimatedSeconds
    }

    /** Upscales 2 sample frames so the user can judge quality first. */
    fun preview(uri: Uri) {
        val s = _state.value
        if (s.previewBusy || s.info == null) return
        _state.value = s.copy(previewBusy = true, previewFiles = emptyList())
        viewModelScope.launch {
            val files = runCatching {
                videoEngine.previewFrames(
                    inputUri = uri,
                    model = s.model,
                    inferenceDispatcher = inferenceDispatcher,
                )
            }.getOrDefault(emptyList())
            _state.value = _state.value.copy(previewBusy = false, previewFiles = files)
        }
    }

    fun start(uri: Uri, onStarted: (String) -> Unit) {
        val s = _state.value
        if (s.starting || s.info == null) return
        _state.value = s.copy(starting = true)
        viewModelScope.launch {
            val id = jobController.enqueueVideoJob(
                inputUri = uri.toString(),
                title = "Video",
                inputBytes = 0,
                sourceDurationMs = s.info.durationMs,
                model = s.model,
                scale = s.scale,
                capMaxHeight = s.cap.maxHeight,
            )
            onStarted(id)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoOptionsScreen(
    encodedUri: String,
    onBack: () -> Unit,
    onStart: (String) -> Unit,
    viewModel: VideoOptionsViewModel = hiltViewModel(),
) {
    val uri = remember(encodedUri) { Uri.decode(encodedUri).let(Uri::parse) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(uri) { viewModel.load(uri) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.video_options_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                }
            },
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- Source metadata card ----
            val info = state.info
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.video_metadata_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (info != null) {
                        Text(
                            stringResource(
                                R.string.video_metadata_dims, info.width, info.height,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.video_metadata_fps, info.fps),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.video_metadata_duration, info.durationMs / 1000),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(
                                if (info.hasAudio) R.string.video_metadata_audio_yes
                                else R.string.video_metadata_audio_no
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            stringResource(R.string.video_metadata_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---- Model ----
            Text(
                stringResource(R.string.options_model),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            state.let { s ->
                ModelType.videoModels.forEach { m ->
                    val selected = s.model == m
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.setModel(m) },
                        label = {
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Text(stringResource(m.displayNameRes))
                                Text(
                                    stringResource(m.descriptionRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            // ---- Scale ----
            Text(
                stringResource(R.string.options_scale),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScaleOption.entries.forEach { s ->
                    FilterChip(
                        selected = state.scale == s,
                        enabled = s.factor <= state.model.nativeScale,
                        onClick = { viewModel.setScale(s) },
                        label = { Text("x${s.factor}") },
                    )
                }
            }

            // ---- Output cap ----
            Text(
                stringResource(R.string.options_cap),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoCapOption.entries.forEach { c ->
                    FilterChip(
                        selected = state.cap == c,
                        onClick = { viewModel.setCap(c) },
                        label = { Text(stringResource(c.labelRes)) },
                    )
                }
            }

            // ---- Estimate ----
            val seconds = viewModel.estimateSeconds()
            if (seconds != null && info != null) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            stringResource(R.string.video_est_time, seconds / 60, seconds % 60)
                        )
                    },
                )
            }

            // ---- Preview ----
            OutlinedButton(
                onClick = { viewModel.preview(uri) },
                enabled = state.info != null && !state.previewBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.previewBusy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(stringResource(R.string.video_preview))
            }

            if (state.previewFiles.isNotEmpty()) {
                Text(
                    stringResource(R.string.video_preview_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.previewFiles.forEach { file ->
                        AsyncImage(
                            model = file,
                            contentDescription = null,
                            modifier = Modifier
                                .weight(1f)
                                .height(140.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { viewModel.start(uri, onStart) },
                enabled = state.info != null && !state.starting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.video_start))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
