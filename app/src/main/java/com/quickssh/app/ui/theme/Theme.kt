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
    primary = QuickSshOrange,
    onPrimary = QuickSshDeepWarmText,
    primaryContainer = QuickSshOrangeContainer,
    onPrimaryContainer = Color(0xFF4B1F16),
    secondary = QuickSshGreen,
    onSecondary = Color.White,
    secondaryContainer = QuickSshGreenContainer,
    onSecondaryContainer = Color(0xFF191D0F),
    tertiary = QuickSshTeal,
    onTertiary = Color.White,
    tertiaryContainer = QuickSshTealContainer,
    onTertiaryContainer = Color(0xFF10201C),
    background = QuickSshCanvas,
    surface = Color.White,
    surfaceVariant = Color(0xFFEFEDE7),
    onSurface = QuickSshInk,
    onSurfaceVariant = Color(0xFF6F6D66),
    outline = Color(0xFF6F6D66),
    outlineVariant = Color(0xFFDCD9D1),
    inverseSurface = QuickSshDeepWarm,
    inverseOnSurface = QuickSshDeepWarmText,
    error = Color(0xFFA43F35)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE7A99A),
    onPrimary = Color(0xFF541F15),
    primaryContainer = Color(0xFF713A31),
    onPrimaryContainer = Color(0xFFFFDAD3),
    secondary = Color(0xFFB7CBAE),
    onSecondary = Color(0xFF23351F),
    secondaryContainer = Color(0xFF3D5138),
    onSecondaryContainer = Color(0xFFD3E8C9),
    tertiary = Color(0xFFA9CCC0),
    onTertiary = Color(0xFF12352E),
    tertiaryContainer = Color(0xFF2D5048),
    onTertiaryContainer = Color(0xFFC5E9DD),
    background = QuickSshDarkCanvas,
    surface = QuickSshDarkSurface,
    surfaceVariant = Color(0xFF494741),
    onSurface = Color(0xFFF0EEE8),
    onSurfaceVariant = Color(0xFFD2CFC7),
    outline = Color(0xFFD2CFC7),
    outlineVariant = Color(0xFF5B5953),
    inverseSurface = QuickSshDeepWarm,
    inverseOnSurface = QuickSshDeepWarmText,
    error = Color(0xFFFFB4AB)
)

@Composable
fun QuickSshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
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
