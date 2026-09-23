package com.devfahim.upscaler.ui.screens.onboarding

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devfahim.upscaler.R
import com.devfahim.upscaler.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    fun finish() {
        viewModelScope.launch { settingsRepository.setOnboardingDone() }
    }
}

private data class OnboardingPage(
    val icon: ImageVector,
    val titleRes: Int,
    val bodyRes: Int,
)

private val pages = listOf(
    OnboardingPage(
        icon = Icons.Filled.PlayArrow,
        titleRes = R.string.onboarding_1_title,
        bodyRes = R.string.onboarding_1_body,
    ),
    OnboardingPage(
        icon = Icons.Filled.CheckCircle,
        titleRes = R.string.onboarding_2_title,
        bodyRes = R.string.onboarding_2_body,
    ),
    OnboardingPage(
        icon = Icons.Filled.Info,
        titleRes = R.string.onboarding_3_title,
        bodyRes = R.string.onboarding_3_body,
    ),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    viewModel: OnboardingViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            OnboardingPageContent(pages[page])
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Page indicators.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(pages.size) { i ->
                    val selected = pagerState.currentPage == i
                    Box(
                        Modifier
                            .size(if (selected) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                    )
                }
            }

            if (pagerState.currentPage < pages.size - 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        // Skipping also counts as "seen" - otherwise the
                        // onboarding would reappear on every cold start
                        // and first-launch flow would never settle.
                        viewModel.finish()
                        onFinish()
                    }) {
                        Text(stringResource(R.string.action_skip))
                    }
                    Button(onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }) {
                        Text(stringResource(R.string.action_next))
                    }
                }
            } else {
                Button(onClick = {
                    viewModel.finish()
                    onFinish()
                }) {
                    Text(stringResource(R.string.action_get_started))
                }
            }
        }
    }
}

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Icon(
                page.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .size(120.dp)
                    .padding(24.dp),
            )
        }
        Spacer(Modifier.height(32.dp))
        Text(
            stringResource(page.titleRes),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(page.bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
