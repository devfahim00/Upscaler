package com.devfahim.upscaler.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.devfahim.upscaler.R
import com.devfahim.upscaler.util.AppUpdater

/**
 * Shown automatically (once per launch, from UpscalerRoot) when GitHub
 * Releases has a newer version than the installed build. "Download update"
 * hands the APK URL to the browser, which downloads it; tapping the finished
 * file installs it.
 */
@Composable
fun UpdateDialog(
    release: AppUpdater.Release,
    currentVersion: String,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_dialog_title)) },
        text = {
            Text(stringResource(R.string.update_dialog_body, release.version, currentVersion))
        },
        confirmButton = {
            Button(onClick = onDownload) {
                Text(stringResource(R.string.update_dialog_download, release.version))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_dialog_later))
            }
        },
    )
}
