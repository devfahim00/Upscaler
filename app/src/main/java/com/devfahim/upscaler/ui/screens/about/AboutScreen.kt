package com.devfahim.upscaler.ui.screens.about

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.devfahim.upscaler.BuildConfig
import com.devfahim.upscaler.R
import com.devfahim.upscaler.util.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * About / Help: app identity, app updates (manual check), Telegram contact,
 * how it works, open-source credits and privacy.
 * Model and library attribution lives here (required by the BSD-3
 * licenses of ncnn and Real-ESRGAN).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // ---- Manual update check state ----
    var checking by remember { mutableStateOf(false) }
    var upToDateMessage by remember { mutableStateOf<String?>(null) }
    var newRelease by remember { mutableStateOf<AppUpdater.Release?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Manual "Check for updates": asks GitHub Releases for the latest version
    // and reports back. The app also checks silently on every launch.
    LaunchedEffect(checking) {
        if (!checking) return@LaunchedEffect
        when (val result = withContext(Dispatchers.IO) { AppUpdater.check(context) }) {
            is AppUpdater.Result.UpToDate -> {
                newRelease = null
                errorMessage = null
                upToDateMessage = context.getString(
                    R.string.about_up_to_date, result.currentVersion
                )
            }
            is AppUpdater.Result.Available -> {
                upToDateMessage = null
                errorMessage = null
                newRelease = result.release
            }
            is AppUpdater.Result.Error -> {
                upToDateMessage = null
                newRelease = null
                errorMessage = context.getString(R.string.about_check_failed, result.message)
            }
        }
        checking = false
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.nav_about)) },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- App identity ----
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(R.string.about_offline),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // ---- App updates ----
            AboutCard(
                icon = Icons.Filled.Refresh,
                title = stringResource(R.string.about_updates_title),
            ) {
                Text(
                    stringResource(R.string.about_updates_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = { checking = true }, enabled = !checking) {
                        Text(
                            if (checking) stringResource(R.string.about_checking)
                            else stringResource(R.string.about_check_updates)
                        )
                    }
                    if (checking) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                newRelease?.let { release ->
                    Text(
                        stringResource(R.string.about_update_available, release.version),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Button(
                        onClick = { AppUpdater.openLink(context, release.apkUrl) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.about_download_update, release.version))
                    }
                }
                upToDateMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                errorMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // ---- Telegram ----
            AboutCard(
                icon = Icons.Filled.Send,
                title = stringResource(R.string.about_telegram_title),
            ) {
                Text(
                    stringResource(R.string.about_telegram_desc, AppUpdater.TELEGRAM_HANDLE),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { AppUpdater.openLink(context, AppUpdater.TELEGRAM_URL) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.about_open_telegram))
                }
            }

            // ---- How it works ----
            AboutCard(
                icon = null,
                title = stringResource(R.string.about_how_title),
            ) {
                Text(
                    stringResource(R.string.about_how_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- Open-source credits ----
            AboutCard(
                icon = null,
                title = stringResource(R.string.about_credits_title),
            ) {
                Text(
                    stringResource(R.string.about_credits_ncnn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.about_credits_realesrgan),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.about_credits_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- Privacy ----
            AboutCard(
                icon = null,
                title = stringResource(R.string.about_privacy_title),
            ) {
                Text(
                    stringResource(R.string.about_privacy_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AboutCard(
    icon: ImageVector?,
    title: String,
    content: @Composable () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            content()
        }
    }
}
