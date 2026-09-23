package com.devfahim.upscaler.ui.screens.result

import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devfahim.upscaler.R
import com.devfahim.upscaler.data.storage.StorageManager
import com.devfahim.upscaler.domain.model.JobStatus
import com.devfahim.upscaler.domain.model.UpscaleJob
import com.devfahim.upscaler.domain.repository.JobsRepository
import com.devfahim.upscaler.ui.components.CompareSlider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ResultViewModel @Inject constructor(
    private val jobsRepository: JobsRepository,
    private val storage: StorageManager,
) : ViewModel() {

    private var boundFlow: StateFlow<UpscaleJob?>? = null

    fun bind(jobId: String): StateFlow<UpscaleJob?> {
        if (boundFlow == null) {
            boundFlow = jobsRepository.observeJob(jobId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
        }
        return boundFlow!!
    }

    /**
     * Copies the private result into MediaStore (Gallery) and records it.
     * [onDone] receives the failure, if any - the caller decides how to
     * surface it (previously this swallowed every error silently, so a
     * failed save looked identical to a successful one).
     */
    fun saveToGallery(job: UpscaleJob, onDone: (Throwable?) -> Unit = {}) {
        viewModelScope.launch {
            val path = job.resultPath
            if (path == null) {
                onDone(IllegalStateException("No result file for this job."))
                return@launch
            }
            val result = runCatching {
                val uri = withContext(Dispatchers.IO) {
                    storage.saveToGallery(
                        file = File(path),
                        format = job.format,
                        displayName = "upscaler_${job.id.take(8)}.${job.format.fileExtension}",
                    )
                }
                jobsRepository.markSaved(job.id, uri.toString())
            }
            onDone(result.exceptionOrNull())
        }
    }

    fun deleteJob(jobId: String, onDeleted: () -> Unit) {
        viewModelScope.launch {
            // Remove row + private files.
            jobsRepository.getJob(jobId)?.let { job ->
                job.resultPath?.let { runCatching { File(it).delete() } }
                job.thumbnailPath?.let { runCatching { File(it).delete() } }
            }
            jobsRepository.delete(jobId)
            onDeleted()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    jobId: String,
    onBack: () -> Unit,
    viewModel: ResultViewModel = hiltViewModel(),
) {
    val job by viewModel.bind(jobId).collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    val j = job
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.result_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                }
            },
            actions = {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.action_delete))
                }
            },
        )

        when {
            j == null -> LoadingBody()
            j.status == JobStatus.COMPLETED && j.resultPath != null -> CompletedBody(job = j, viewModel = viewModel)
            j.status == JobStatus.FAILED || j.status == JobStatus.CANCELLED -> FailedBody(job = j, onBack = onBack)
            else -> ActiveBody(job = j)
        }
    }

    if (confirmDelete && j != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteJob(j!!.id) { onBack() }
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun LoadingBody() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ActiveBody(job: UpscaleJob) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.processing_photo_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { job.progress / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.processing_running, job.progress),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FailedBody(job: UpscaleJob, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Refresh,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(
                if (job.status == JobStatus.CANCELLED) R.string.result_cancelled
                else R.string.result_failed
            ),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (job.errorMessage != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                job.errorMessage!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onBack) {
            Text(stringResource(R.string.action_back))
        }
    }
}

@Composable
private fun CompletedBody(job: UpscaleJob, viewModel: ResultViewModel) {
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(380.dp)
                .padding(horizontal = 12.dp)
        ) {
            CompareSlider(
                originalModel = job.inputUri,
                enhancedModel = File(job.resultPath!!),
                contentDescription = job.title,
            )
        }

        Text(
            stringResource(
                R.string.result_info,
                job.outWidth, job.outHeight,
                if (job.processingDurationMs > 0) job.processingDurationMs / 1000 else 0,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val saveFailedText = stringResource(R.string.result_save_failed)
            FilledTonalButton(
                onClick = {
                    viewModel.saveToGallery(job) { error ->
                        if (error != null) {
                            android.widget.Toast.makeText(
                                context,
                                saveFailedText.format(error.message ?: error.javaClass.simpleName),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Done, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.action_save))
            }
            OutlinedButton(
                onClick = {
                    val uri = FileProvider.getUriForFile(
                        context,
                        context.packageName + ".fileprovider",
                        File(job.resultPath!!),
                    )
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = job.format.mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, context.getString(R.string.action_share)))
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.action_share))
            }
        }
        if (job.savedUri != null) {
            Text(
                stringResource(R.string.result_saved_notice),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
