package com.devfahim.upscaler.ui.screens.home

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.devfahim.upscaler.R
import com.devfahim.upscaler.domain.model.ModelType
import com.devfahim.upscaler.domain.model.OutputFormat
import com.devfahim.upscaler.domain.model.ScaleOption
import com.devfahim.upscaler.domain.model.UpscaleJob
import com.devfahim.upscaler.domain.repository.AppSettings
import com.devfahim.upscaler.domain.repository.JobsRepository
import com.devfahim.upscaler.domain.repository.SettingsRepository
import com.devfahim.upscaler.processing.JobController
import com.devfahim.upscaler.ui.screens.options.PhotoOptionsSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val jobController: JobController,
    private val settingsRepository: SettingsRepository,
    jobsRepository: JobsRepository,
) : ViewModel() {

    val recentJobs: StateFlow<List<UpscaleJob>> = jobsRepository.observeJobs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Persists the per-model HDR preference when the user toggles it. */
    fun setHdrEnabled(model: ModelType, enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHdrEnabled(model, enabled) }
    }

    /** Enqueues one job per selected image (batch queue). */
    fun startPhotoJobs(
        uris: List<Uri>,
        model: ModelType,
        scale: ScaleOption,
        format: OutputFormat,
        wdnAlpha: Float,
        hdrEnabled: Boolean,
        onFirstEnqueued: (String) -> Unit,
    ) {
        viewModelScope.launch {
            var firstId: String? = null
            for (uri in uris) {
                val id = jobController.enqueuePhotoJob(
                    inputUri = uri.toString(),
                    title = "Photo",
                    inputBytes = 0,
                    model = model,
                    scale = scale,
                    format = format,
                    wdnAlpha = wdnAlpha,
                    hdrEnabled = hdrEnabled,
                )
                if (firstId == null) firstId = id
            }
            firstId?.let(onFirstEnqueued)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenJob: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val recent by viewModel.recentJobs.collectAsStateWithLifecycle()

    var pickedPhotos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var optionsOpen by remember { mutableStateOf(false) }

    // Runtime notification permission (progress notifications on 13+).
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(8)
    ) { uris ->
        if (uris.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= 33) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            pickedPhotos = uris
            optionsOpen = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.app_name)) })

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EntryCard(
                modifier = Modifier.fillMaxWidth(),
                iconRes = R.drawable.ic_photo,
                titleRes = R.string.home_upscale_photo,
                subtitleRes = R.string.home_upscale_photo_sub,
                onClick = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
            )

            if (recent.isNotEmpty()) {
                Text(
                    stringResource(R.string.home_recent),
                    style = MaterialTheme.typography.titleMedium,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recent.take(10), key = { it.id }) { job ->
                        RecentChip(job = job, onClick = { onOpenJob(job.id) })
                    }
                }
            }

            // Offline trust banner - no INTERNET permission is declared.
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_cloud_off),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.home_offline_banner),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }

    if (optionsOpen && pickedPhotos.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { optionsOpen = false }) {
            PhotoOptionsSheet(
                count = pickedPhotos.size,
                defaultModel = settings?.defaultPhotoModel ?: ModelType.GENERAL_PHOTO_X4,
                defaultScale = settings?.defaultScale ?: ScaleOption.X4,
                defaultFormat = settings?.outputFormat ?: OutputFormat.PNG,
                initialHdrModels = settings?.hdrEnabledModels ?: emptySet(),
                onHdrToggle = viewModel::setHdrEnabled,
                onProcess = { model, scale, format, wdnAlpha, hdr ->
                    optionsOpen = false
                    val uris = pickedPhotos
                    pickedPhotos = emptyList()
                    viewModel.startPhotoJobs(
                        uris, model, scale, format, wdnAlpha, hdr,
                        onFirstEnqueued = onOpenJob,
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryCard(
    modifier: Modifier,
    iconRes: Int,
    titleRes: Int,
    subtitleRes: Int,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        onClick = onClick,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.secondaryContainer,
                            )
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                )
            }
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(subtitleRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentChip(job: UpscaleJob, onClick: () -> Unit) {
    ElevatedCard(
        shape = MaterialTheme.shapes.medium,
        onClick = onClick,
    ) {
        Column(Modifier.width(132.dp)) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(job.thumbnailPath ?: job.inputUri)
                    .crossfade(true)
                    .build(),
                contentDescription = job.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
            )
            Text(
                text = stringResource(R.string.kind_photo),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}
