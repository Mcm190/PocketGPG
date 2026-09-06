package com.pocketgpg.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

val BrandBlue = Color(0xFF036CEF)
val BrandBlueLight = Color(0xFF179AFE)
val BrandBlueDeep = Color(0xFF0047C5)

private val Light = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E6FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF2C5AA8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE6F9),
    onSecondaryContainer = Color(0xFF10233F),
    tertiary = Color(0xFF00639B),
    background = Color(0xFFF8FAFF),
    onBackground = Color(0xFF11151C),
    surface = Color(0xFFF8FAFF),
    onSurface = Color(0xFF11151C),
    surfaceVariant = Color(0xFFE0E5EF),
    onSurfaceVariant = Color(0xFF434A55),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F4FC),
    surfaceContainer = Color(0xFFEBEFF8),
    surfaceContainerHigh = Color(0xFFE5EAF4),
    surfaceContainerHighest = Color(0xFFDFE4EF),
    surfaceTint = BrandBlue,
    outline = Color(0xFF737A86),
    outlineVariant = Color(0xFFC3C8D2),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF004788),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFB9C7E4),
    onSecondary = Color(0xFF243048),
    secondaryContainer = Color(0xFF3A4760),
    onSecondaryContainer = Color(0xFFD5E3FF),
    tertiary = Color(0xFF8ECDFF),
    background = Color(0xFF0F1319),
    onBackground = Color(0xFFE1E2E8),
    surface = Color(0xFF0F1319),
    onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF424750),
    onSurfaceVariant = Color(0xFFC2C7D0),
    surfaceContainerLowest = Color(0xFF0A0D12),
    surfaceContainerLow = Color(0xFF171B22),
    surfaceContainer = Color(0xFF1B1F27),
    surfaceContainerHigh = Color(0xFF252A33),
    surfaceContainerHighest = Color(0xFF30353E),
    surfaceTint = Color(0xFFA9C7FF),
    outline = Color(0xFF8C919B),
    outlineVariant = Color(0xFF424750),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

@Composable
fun PocketGpgTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) Dark else Light
    val context = LocalContext.current
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
