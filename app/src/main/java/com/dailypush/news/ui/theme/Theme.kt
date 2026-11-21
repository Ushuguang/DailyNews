package com.dailypush.news.ui.theme

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
    primary = Color(0xFF1F4E79),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E6F7),
    onPrimaryContainer = Color(0xFF0B2740),
    secondary = Color(0xFF4C6172),
    onSecondary = Color.White,
    tertiary = Color(0xFFB05309),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF191C1E),
    surface = Color.White,
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFE6EBF1),
    outline = Color(0xFF77828C)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FC9EE),
    onPrimary = Color(0xFF06283F),
    primaryContainer = Color(0xFF1F4E79),
    onPrimaryContainer = Color(0xFFD5E6F7),
    secondary = Color(0xFFB4C9D9),
    tertiary = Color(0xFFFFB77C),
    background = Color(0xFF111416),
    onBackground = Color(0xFFE2E2E5),
    surface = Color(0xFF191C1E),
    onSurface = Color(0xFFE2E2E5),
    surfaceVariant = Color(0xFF3F484E),
    outline = Color(0xFF8A9299)
)

@Composable
fun DailyNewsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
