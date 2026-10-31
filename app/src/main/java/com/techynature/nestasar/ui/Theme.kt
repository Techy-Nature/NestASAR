package com.techynature.nestasar.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Always-dark "forest at night" palette: mint moss + lantern amber. */
private val NestDark = darkColorScheme(
    primary = Color(0xFF8BD3B0),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF1F4D3D),
    onPrimaryContainer = Color(0xFFB5F0D4),
    secondary = Color(0xFFE3BC7A),
    onSecondary = Color(0xFF402D00),
    secondaryContainer = Color(0xFF4A3A1C),
    onSecondaryContainer = Color(0xFFFFDFA8),
    tertiary = Color(0xFF9CC5E8),
    onTertiary = Color(0xFF00324D),
    background = Color(0xFF0E1311),
    onBackground = Color(0xFFDDE5E0),
    surface = Color(0xFF0E1311),
    onSurface = Color(0xFFDDE5E0),
    surfaceVariant = Color(0xFF1C2622),
    onSurfaceVariant = Color(0xFFAFC0B8),
    surfaceContainerLowest = Color(0xFF090D0B),
    surfaceContainerLow = Color(0xFF131A17),
    surfaceContainer = Color(0xFF17201C),
    surfaceContainerHigh = Color(0xFF1D2723),
    surfaceContainerHighest = Color(0xFF242F2A),
    outline = Color(0xFF52625B),
    outlineVariant = Color(0xFF2C3833),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5C1A16),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun NestTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NestDark, content = content)
}
