package com.devfahim.upscaler.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.devfahim.upscaler.R
import kotlin.math.abs

/**
 * The signature before/after comparison slider.
 *
 * - The "enhanced" image is clipped to the right of the handle; the
 *   "original" shows on the left. Labels fade based on handle position.
 * - Tap-to-position + smooth drag, with a haptic tick when the handle
 *   crosses the center.
 */
@Composable
fun CompareSlider(
    originalModel: Any?,
    enhancedModel: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var fraction by remember { mutableFloatStateOf(0.5f) }
    var crossedCenter by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    fraction = (offset.x / size.width).coerceIn(0f, 1f)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val next = (fraction + dragAmount.x / size.width).coerceIn(0f, 1f)
                    val near = abs(next - 0.5f) < 0.02f
                    if (near && !crossedCenter) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        crossedCenter = true
                    } else if (!near) {
                        crossedCenter = false
                    }
                    fraction = next
                }
            },
    ) {
        val handleX = maxWidth * fraction

        // Original (full).
        AsyncImage(
            model = originalModel,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        // Enhanced (clipped to the right of the handle; the original shows
        // through on the left, matching the labels below).
        AsyncImage(
            model = enhancedModel,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(
                        left = size.width * fraction,
                        top = 0f,
                        right = size.width,
                        bottom = size.height,
                    ) {
                        this@drawWithContent.drawContent()
                    }
                },
        )

        // Vertical divider line.
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(x = handleX - 1.dp)
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)),
        )

        // Draggable knob (shadowed circle + compare glyph).
        Surface(
            shape = CircleShape,
            color = Color.White,
            shadowElevation = 6.dp,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = handleX - 24.dp)
                .size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_compare),
                    contentDescription = null,
                    tint = Color(0xFF202425),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // Labels that fade based on handle position.
        CompareLabel(
            text = stringResource(R.string.compare_original),
            visible = fraction > 0.18f,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
        )
        CompareLabel(
            text = stringResource(R.string.compare_enhanced),
            visible = fraction < 0.82f,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
        )
    }
}

@Composable
private fun CompareLabel(text: String, visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(150)),
        modifier = modifier,
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = Color.Black.copy(alpha = 0.55f),
        ) {
            Text(
                text,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}
