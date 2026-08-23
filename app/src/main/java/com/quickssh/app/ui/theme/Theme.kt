package com.quickssh.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = QuickSshBlue,
    onPrimary = Color.White,
    primaryContainer = QuickSshBlueLight,
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = QuickSshTeal,
    onSecondary = Color.White,
    secondaryContainer = QuickSshTealLight,
    onSecondaryContainer = Color(0xFF002021),
    background = QuickSshCanvas,
    surface = Color.White,
    surfaceVariant = Color(0xFFE9EEF5),
    onSurface = QuickSshInk,
    onSurfaceVariant = Color(0xFF414A55),
    outline = Color(0xFF707A86),
    error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C8FF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF164A80),
    onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFF80D4D2),
    onSecondary = Color(0xFF003737),
    secondaryContainer = Color(0xFF00504F),
    onSecondaryContainer = Color(0xFF9CF1EF),
    background = QuickSshDarkCanvas,
    surface = QuickSshDarkSurface,
    surfaceVariant = Color(0xFF29323B),
    onSurface = Color(0xFFE2E7ED),
    onSurfaceVariant = Color(0xFFC1C9D2),
    outline = Color(0xFF89939E),
    error = Color(0xFFFFB4AB)
)

@Composable
fun QuickSshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = QuickSshTypography,
        content = content
    )
}

