package com.yaprak.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF275E50), onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EDE6), onPrimaryContainer = Color(0xFF214D41),
    secondary = Color(0xFF806447), secondaryContainer = Color(0xFFF1E7D7),
    background = Color(0xFFFAF9F5), onBackground = Color(0xFF232D28),
    surface = Color(0xFFFAF9F5), onSurface = Color(0xFF232D28),
    surfaceVariant = Color(0xFFEEEEE7), onSurfaceVariant = Color(0xFF626B64),
    surfaceContainerLow = Color(0xFFF3F2EC), surfaceContainer = Color(0xFFEEEEE7),
    outlineVariant = Color(0xFFDDE1D8),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA1D1B9), onPrimary = Color(0xFF13392B),
    primaryContainer = Color(0xFF244A39), onPrimaryContainer = Color(0xFFC3E9D4),
    background = Color(0xFF151C18), surface = Color(0xFF151C18),
    onBackground = Color(0xFFE4E8DF), onSurface = Color(0xFFE4E8DF),
    surfaceContainerLow = Color(0xFF1D2620), surfaceContainer = Color(0xFF263028),
    surfaceVariant = Color(0xFF303B32), onSurfaceVariant = Color(0xFFB9C4B9),
    outlineVariant = Color(0xFF424D43),
)
private val YaprakTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 38.sp, lineHeight = 46.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 36.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 16.sp, letterSpacing = 1.3.sp),
)

@Composable
fun YaprakTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = YaprakTypography,
        content = content,
    )
}
