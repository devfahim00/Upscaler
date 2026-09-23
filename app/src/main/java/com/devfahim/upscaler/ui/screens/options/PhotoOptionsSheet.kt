package com.devfahim.upscaler.ui.screens.options

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.devfahim.upscaler.R
import com.devfahim.upscaler.domain.model.HdrAdjust
import com.devfahim.upscaler.domain.model.ModelType
import com.devfahim.upscaler.domain.model.OutputFormat
import com.devfahim.upscaler.domain.model.ScaleOption

/**
 * Bottom sheet shown right after picking photos (the "fast path"):
 * model, scale (incl. the HDR-only 1x option), HDR enhancement (per model)
 * with a strength slider + an "Advanced adjustments" expander for advanced
 * users, WDN denoise strength (photo x4 model), output format + JPEG/WEBP
 * quality.
 */
@Composable
fun PhotoOptionsSheet(
    count: Int,
    defaultModel: ModelType,
    defaultScale: ScaleOption,
    defaultFormat: OutputFormat,
    initialHdrModels: Set<ModelType>,
    onHdrToggle: (ModelType, Boolean) -> Unit,
    onProcess: (ModelType, ScaleOption, OutputFormat, Float, Boolean, HdrAdjust) -> Unit,
) {
    var model by remember { mutableStateOf(defaultModel) }
    var scale by remember { mutableStateOf(defaultScale) }
    var format by remember { mutableStateOf(defaultFormat) }
    var quality by remember { mutableFloatStateOf(92f) }
    var wdnAlpha by remember { mutableFloatStateOf(0f) }
    var adjust by remember { mutableStateOf(HdrAdjust()) }
    var advancedOpen by remember { mutableStateOf(false) }
    // The HDR preference is per upscale model; kept locally so switching
    // models shows (and processes with) that model's own saved choice.
    val hdrOn = remember {
        mutableStateMapOf<ModelType, Boolean>().apply {
            initialHdrModels.forEach { put(it, true) }
        }
    }

    // HDR-only mode: the 1x chip - enhancement without any upscaling.
    val hdrOnly = scale == ScaleOption.X1
    val hdrActive = hdrOnly || hdrOn[model] == true

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

        // ---- Model (ignored in HDR-only mode) ----
        SectionLabel(stringResource(R.string.options_model))
        if (hdrOnly) {
            Text(
                stringResource(R.string.options_hdr_only_model_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ModelType.photoModels.forEach { m ->
            SelectableRow(
                selected = model == m,
                enabled = !hdrOnly,
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
                val enabled = s == ScaleOption.X1 || s.factor <= model.nativeScale
                FilterChip(
                    selected = scale == s,
                    enabled = enabled,
                    onClick = { scale = s },
                    label = {
                        Text(
                            if (s == ScaleOption.X1) stringResource(R.string.scale_hdr_only)
                            else "x${s.factor}"
                        )
                    },
                )
            }
        }

        // ---- HDR enhancement (remembered per model; forced on for 1x) ----
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.options_hdr_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(
                        if (hdrOnly) R.string.options_hdr_only_hint else R.string.options_hdr_hint
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = hdrActive,
                enabled = !hdrOnly,
                onCheckedChange = { on ->
                    hdrOn[model] = on
                    onHdrToggle(model, on)
                },
            )
        }

        if (hdrActive) {
            // ---- HDR strength (the main "natural look" control) ----
            SectionLabel(
                stringResource(R.string.options_hdr_strength, (adjust.strength * 100).toInt())
            )
            Text(
                stringResource(R.string.options_hdr_strength_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = adjust.strength,
                onValueChange = { adjust = adjust.copy(strength = it) },
                valueRange = 0f..1f,
            )

            // ---- Advanced adjustments (collapsed by default) ----
            ExpandHeader(
                label = stringResource(R.string.options_hdr_advanced),
                expanded = advancedOpen,
                onToggle = { advancedOpen = !advancedOpen },
            )
            if (advancedOpen) {
                Text(
                    stringResource(R.string.options_hdr_advanced_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AdjustSlider(
                    label = stringResource(R.string.adjust_exposure),
                    value = adjust.exposure,
                ) { adjust = adjust.copy(exposure = it) }
                AdjustSlider(
                    label = stringResource(R.string.adjust_brightness),
                    value = adjust.brightness,
                ) { adjust = adjust.copy(brightness = it) }
                AdjustSlider(
                    label = stringResource(R.string.adjust_contrast),
                    value = adjust.contrast,
                ) { adjust = adjust.copy(contrast = it) }
                AdjustSlider(
                    label = stringResource(R.string.adjust_gamma),
                    value = adjust.gamma,
                ) { adjust = adjust.copy(gamma = it) }
                AdjustSlider(
                    label = stringResource(R.string.adjust_saturation),
                    value = adjust.saturation,
                ) { adjust = adjust.copy(saturation = it) }
                AdjustSlider(
                    label = stringResource(R.string.adjust_temperature),
                    value = adjust.temperature,
                ) { adjust = adjust.copy(temperature = it) }
                AdjustSlider(
                    label = stringResource(R.string.adjust_tint),
                    value = adjust.tint,
                ) { adjust = adjust.copy(tint = it) }
                // Highlight protection slider (0..100% -> knee 1.0..0.6):
                // how strongly bright areas are kept from blowing out.
                AdjustSlider(
                    label = stringResource(R.string.adjust_highlights),
                    value = (1f - adjust.highlightKnee) / 0.4f,
                    bipolar = false,
                ) { adjust = adjust.copy(highlightKnee = 1f - 0.4f * it) }
                AdjustSlider(
                    label = stringResource(R.string.adjust_sharpness),
                    value = adjust.sharpness,
                    bipolar = false,
                ) { adjust = adjust.copy(sharpness = it) }

                TextButton(onClick = { adjust = HdrAdjust() }) {
                    Text(stringResource(R.string.options_hdr_reset))
                }
            }
        }

        // ---- WDN interpolation (photo x4 only, not in HDR-only mode) ----
        if (!hdrOnly && model.supportsWdnInterpolation) {
            SectionLabel(stringResource(R.string.options_wdn_title, (wdnAlpha * 100).toInt()))
            Text(
                stringResource(R.string.options_wdn_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = wdnAlpha,
                onValueChange = { wdnAlpha = it },
                valueRange = 0f..1f,
                steps = 9, // 10% steps, matching the cached interpolation models
            )
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
            onClick = { onProcess(model, scale, format, wdnAlpha, hdrActive, adjust) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (hdrOnly) R.string.action_enhance else R.string.action_process
                )
            )
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

/**
 * One bipolar (-100%..+100%) or unipolar (0..100%) adjustment slider with
 * a "label: value%" caption, used by the Advanced adjustments section.
 */
@Composable
private fun AdjustSlider(
    label: String,
    value: Float,
    bipolar: Boolean = true,
    onValueChange: (Float) -> Unit,
) {
    val range = if (bipolar) -1f..1f else 0f..1f
    val pct = (value * 100).toInt()
    Column {
        Text(
            if (bipolar && pct > 0) "$label: +$pct%" else "$label: $pct%",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
        )
    }
}

@Composable
private fun ExpandHeader(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectableRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    androidx.compose.material3.ElevatedFilterChip(
        selected = selected,
        enabled = enabled,
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
