package com.devfahim.upscaler.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.devfahim.upscaler.R
import com.devfahim.upscaler.domain.model.JobStatus
import com.devfahim.upscaler.domain.model.MediaKind
import com.devfahim.upscaler.domain.model.UpscaleJob
import com.devfahim.upscaler.domain.repository.JobsRepository
import com.devfahim.upscaler.ui.components.EmptyState
import com.devfahim.upscaler.ui.components.ShimmerPlaceholder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val jobsRepository: JobsRepository,
) : ViewModel() {

    enum class Filter { ALL, PHOTOS, VIDEOS }

    private val filter = MutableStateFlow(Filter.ALL)

    val jobs: StateFlow<List<UpscaleJob>> =
        combine(jobsRepository.observeJobs(), filter) { list, f ->
            when (f) {
                Filter.ALL -> list
                Filter.PHOTOS -> list.filter { it.kind == MediaKind.PHOTO }
                Filter.VIDEOS -> list.filter { it.kind == MediaKind.VIDEO }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentFilter: StateFlow<Filter> = filter.asStateFlow()

    fun setFilter(f: Filter) {
        filter.value = f
    }

    fun delete(job: UpscaleJob) {
        viewModelScope.launch {
            job.resultPath?.let { runCatching { File(it).delete() } }
            job.thumbnailPath?.let { runCatching { File(it).delete() } }
            jobsRepository.delete(job.id)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenJob: (String) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val filter by viewModel.currentFilter.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.nav_library)) })

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryViewModel.Filter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { viewModel.setFilter(f) },
                    label = {
                        Text(
                            stringResource(
                                when (f) {
                                    LibraryViewModel.Filter.ALL -> R.string.filter_all
                                    LibraryViewModel.Filter.PHOTOS -> R.string.filter_photos
                                    LibraryViewModel.Filter.VIDEOS -> R.string.filter_videos
                                }
                            )
                        )
                    },
                )
            }
        }

        if (jobs.isEmpty()) {
            EmptyState(
                iconRes = R.drawable.ic_photo_library,
                titleRes = R.string.library_empty_title,
                subtitleRes = R.string.library_empty_sub,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(jobs, key = { it.id }) { job ->
                    LibraryCell(
                        job = job,
                        onClick = { onOpenJob(job.id) },
                        onDelete = { viewModel.delete(job) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryCell(job: UpscaleJob, onClick: () -> Unit, onDelete: () -> Unit) {
    androidx.compose.material3.ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.layout.Box {
            AsyncImage(
                model = job.thumbnailPath ?: job.inputUri,
                contentDescription = job.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
            if (job.status != JobStatus.COMPLETED) {
                StatusBadge(job, Modifier.align(androidx.compose.ui.Alignment.TopStart))
            }
            IconButton(onClick = onDelete, modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
            if (job.kind == MediaKind.VIDEO) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.kind_video),
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.BottomStart)
                        .padding(6.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(job: UpscaleJob, modifier: Modifier = Modifier) {
    val (labelRes, color) = when (job.status) {
        JobStatus.RUNNING -> R.string.status_running to androidx.compose.ui.graphics.Color(0xCC0288D1)
        JobStatus.QUEUED -> R.string.status_queued to androidx.compose.ui.graphics.Color(0xCC616161)
        JobStatus.FAILED -> R.string.status_failed to androidx.compose.ui.graphics.Color(0xCCBA1A1A)
        JobStatus.CANCELLED -> R.string.status_cancelled to androidx.compose.ui.graphics.Color(0xCC616161)
        JobStatus.COMPLETED -> R.string.status_completed to androidx.compose.ui.graphics.Color(0xCC2E7D32)
    }
    androidx.compose.material3.Surface(
        color = color,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.padding(6.dp),
    ) {
        Text(
            stringResource(labelRes),
            color = androidx.compose.ui.graphics.Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
