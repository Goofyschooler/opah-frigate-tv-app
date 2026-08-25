package app.opah.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import app.opah.tv.data.model.AppearanceMode
import app.opah.tv.data.model.CustomThemeColors
import app.opah.tv.data.model.ThemeColorPolicy

private val OpahDarkColors = darkColorScheme(
    primary = Color(0xFFFF7048),
    onPrimary = Color(0xFF24120D),
    secondary = Color(0xFFFFB14A),
    onSecondary = Color(0xFF261500),
    background = Color(0xFF07111F),
    onBackground = Color(0xFFF8FAFF),
    surface = Color(0xFF0D1B30),
    onSurface = Color(0xFFF5F8FC),
    surfaceVariant = Color(0xFF172A46),
    onSurfaceVariant = Color(0xFFBAC7D9),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val OpahLightColors = lightColorScheme(
    primary = Color(0xFFC84331),
    onPrimary = Color.White,
    secondary = Color(0xFF17365F),
    onSecondary = Color.White,
    background = Color(0xFFF7F9FD),
    onBackground = Color(0xFF15294F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17263E),
    surfaceVariant = Color(0xFFE7EDF5),
    onSurfaceVariant = Color(0xFF526179),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

@Composable
fun OpahTheme(
    appearanceMode: AppearanceMode = AppearanceMode.SYSTEM,
    customThemeColors: CustomThemeColors = CustomThemeColors(),
    reducedMotion: Boolean = false,
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when (appearanceMode) {
        AppearanceMode.SYSTEM -> if (isSystemInDarkTheme()) OpahDarkColors else OpahLightColors
        AppearanceMode.DARK -> OpahDarkColors
        AppearanceMode.LIGHT -> OpahLightColors
        AppearanceMode.CUSTOM -> customColorScheme(customThemeColors)
    }
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(
            LocalContentColor provides colors.onBackground,
            LocalOpahUiPreferences provides OpahUiPreferences(reducedMotion, highContrast),
            LocalOpahSemanticColors provides semanticColors(colors.primary, highContrast),
        ) {
            content()
        }
    }
}

private fun semanticColors(selection: Color, highContrast: Boolean) = OpahSemanticColors(
    alert = if (highContrast) Color(0xFFFF6B52) else Color(0xFFE85D45),
    detection = if (highContrast) Color(0xFF67C5FF) else Color(0xFF4EA8DE),
    warning = if (highContrast) Color(0xFFFFD166) else Color(0xFFF2B84B),
    offline = if (highContrast) Color(0xFFD8DEE8) else Color(0xFF9AA4B2),
    destructive = if (highContrast) Color(0xFFFF5C64) else Color(0xFFE5484D),
    success = if (highContrast) Color(0xFF6EE7B7) else Color(0xFF55C59A),
    selection = selection,
    focus = Color.White,
)

private fun customColorScheme(colors: CustomThemeColors) = ThemeColorPolicy.sanitize(colors).let { safe ->
    val background = Color(safe.backgroundArgb)
    val onBackground = Color(ThemeColorPolicy.readableForeground(safe.backgroundArgb))
    val primary = Color(safe.accentArgb)
    val secondaryArgb = ThemeColorPolicy.secondaryAccent(safe)
    val secondary = Color(secondaryArgb)
    val surface = lerp(background, onBackground, 0.06f)
    val surfaceVariant = lerp(background, onBackground, 0.14f)
    val onSurfaceVariant = lerp(onBackground, background, 0.24f)
    val darkBackground = ThemeColorPolicy.readableForeground(safe.backgroundArgb) == 0xFFF8FAFF.toInt()
    if (darkBackground) {
        darkColorScheme(
            primary = primary,
            onPrimary = Color(ThemeColorPolicy.readableForeground(safe.accentArgb)),
            secondary = secondary,
            onSecondary = Color(ThemeColorPolicy.readableForeground(secondaryArgb)),
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onBackground,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = Color(ThemeColorPolicy.readableForeground(safe.accentArgb)),
            secondary = secondary,
            onSecondary = Color(ThemeColorPolicy.readableForeground(secondaryArgb)),
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onBackground,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            error = Color(0xFFBA1A1A),
            onError = Color.White,
        )
    }
}
