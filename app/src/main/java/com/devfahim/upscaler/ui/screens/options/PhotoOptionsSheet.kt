package com.devfahim.upscaler.ui.screens.options

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.devfahim.upscaler.R
import com.devfahim.upscaler.domain.model.ModelType
import com.devfahim.upscaler.domain.model.OutputFormat
import com.devfahim.upscaler.domain.model.ScaleOption

/**
 * Bottom sheet shown right after picking photos (the "fast path"):
 * model, scale, output format + JPEG/WEBP quality.
 */
@Composable
fun PhotoOptionsSheet(
    count: Int,
    defaultModel: ModelType,
    defaultScale: ScaleOption,
    defaultFormat: OutputFormat,
    onProcess: (ModelType, ScaleOption, OutputFormat) -> Unit,
) {
    var model by remember { mutableStateOf(defaultModel) }
    var scale by remember { mutableStateOf(defaultScale) }
    var format by remember { mutableStateOf(defaultFormat) }
    var quality by remember { mutableFloatStateOf(92f) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (count > 1) stringResource(R.string.options_title_batch, count)
            else stringResource(R.string.options_title),
            style = MaterialTheme.typography.titleLarge,
        )

        // ---- Model ----
        SectionLabel(stringResource(R.string.options_model))
        ModelType.photoModels.forEach { m ->
            SelectableRow(
                selected = model == m,
                title = stringResource(m.displayNameRes),
                subtitle = buildString {
                    append(stringResource(m.descriptionRes))
                    if (m.standInFor != null) {
                        append(" ")
                        append(stringResource(R.string.model_standin_note))
                    }
                },
                onClick = {
                    model = m
                    // Reset an unsupported scale when switching models.
                    if (m.nativeScale < scale.factor) scale = ScaleOption.X2
                },
            )
        }

        // ---- Scale ----
        SectionLabel(stringResource(R.string.options_scale))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScaleOption.entries.forEach { s ->
                val enabled = s.factor <= model.nativeScale
                FilterChip(
                    selected = scale == s,
                    enabled = enabled,
                    onClick = { scale = s },
                    label = { Text("x${s.factor}") },
                )
            }
        }

        // ---- Format ----
        SectionLabel(stringResource(R.string.options_format))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutputFormat.entries.forEach { f ->
                FilterChip(
                    selected = format == f,
                    onClick = { format = f },
                    label = { Text(stringResource(f.labelRes)) },
                )
            }
        }

        if (format.supportsQuality) {
            SectionLabel(stringResource(R.string.options_quality, quality.toInt()))
            Slider(
                value = quality,
                onValueChange = { quality = it },
                valueRange = 50f..100f,
                steps = 9,
            )
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { onProcess(model, scale, format) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_process))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectableRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    androidx.compose.material3.ElevatedFilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
