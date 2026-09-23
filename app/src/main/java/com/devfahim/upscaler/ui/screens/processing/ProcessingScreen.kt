package com.devfahim.upscaler.ui.screens.processing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devfahim.upscaler.R
import com.devfahim.upscaler.domain.model.JobStatus
import com.devfahim.upscaler.domain.model.UpscaleJob
import com.devfahim.upscaler.domain.repository.JobsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ProcessingViewModel @Inject constructor(
    private val jobsRepository: JobsRepository,
) : ViewModel() {

    private var boundFlow: StateFlow<UpscaleJob?>? = null

    fun bind(jobId: String): StateFlow<UpscaleJob?> {
        if (boundFlow == null) {
            boundFlow = jobsRepository.observeJob(jobId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
        }
        return boundFlow!!
    }
}

@Composable
fun ProcessingScreen(
    jobId: String,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    viewModel: ProcessingViewModel = hiltViewModel(),
) {
    val job by viewModel.bind(jobId).collectAsStateWithLifecycle()

    LaunchedEffect(job?.status) {
        if (job?.status == JobStatus.COMPLETED) onDone()
    }

    val j = job
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(24.dp))
        Text(
            text = when {
                j == null -> stringResource(R.string.processing_preparing)
                else -> stringResource(R.string.processing_photo_title)
            },
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = j?.let {
                when (it.status) {
                    JobStatus.QUEUED -> stringResource(R.string.processing_queued)
                    else -> stringResource(R.string.processing_running, it.progress)
                }
            } ?: stringResource(R.string.processing_preparing),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        LinearProgressIndicator(
            progress = { (j?.progress ?: 0) / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onCancel) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}
