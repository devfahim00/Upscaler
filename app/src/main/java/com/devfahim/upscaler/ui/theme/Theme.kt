package com.devfahim.upscaler.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.devfahim.upscaler.domain.model.ThemeMode

/** Generous rounded corners per the UI direction (16-24dp on cards/sheets). */
val UpscalerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val LightScheme = lightColorScheme(
    primary = Teal40,
    onPrimary = Color.White,
    primaryContainer = Teal90,
    onPrimaryContainer = Teal10,
    secondary = Copper40,
    onSecondary = Color.White,
    secondaryContainer = Copper90,
    onSecondaryContainer = Copper30,
    tertiary = Teal40,
    error = ErrorRed40,
    errorContainer = ErrorRed90,
    onError = Color.White,
    onErrorContainer = ErrorRed10,
    background = TealGrey90,
    onBackground = TealGrey10,
    surface = TealGrey90,
    onSurface = TealGrey10,
    surfaceVariant = TealGrey80,
    onSurfaceVariant = TealGrey30,
    outline = TealGrey40,
)

private val DarkScheme = darkColorScheme(
    primary = Teal80,
    onPrimary = Teal10,
    primaryContainer = Teal30,
    onPrimaryContainer = Teal90,
    secondary = Copper80,
    onSecondary = TealGrey10,
    secondaryContainer = Copper30,
    onSecondaryContainer = Copper90,
    tertiary = Teal80,
    error = ErrorRed80,
    errorContainer = ErrorRed10,
    onError = ErrorRed10,
    onErrorContainer = ErrorRed80,
    background = TealGrey10,
    onBackground = TealGrey90,
    surface = TealGrey10,
    onSurface = TealGrey90,
    surfaceVariant = TealGrey30,
    onSurfaceVariant = TealGrey80,
    outline = TealGrey40,
)

@Composable
fun UpscalerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = UpscalerTypography,
        shapes = UpscalerShapes,
        content = content,
    )
}
