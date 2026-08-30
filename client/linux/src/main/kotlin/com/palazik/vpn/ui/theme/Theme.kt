package com.palazik.vpn.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.palazik.vpn.data.model.DesignSystem

// DYNAMIC (Android 12 wallpaper colors) has no Linux counterpart — every other
// theme from the Android app is here.
enum class AppTheme { CYBER, OCEAN, FOREST, SUNSET, ROSE, VIOLET, AMOLED }
enum class DarkModePreference { SYSTEM, ALWAYS_DARK, ALWAYS_LIGHT }

val LocalAppTheme      = compositionLocalOf { AppTheme.CYBER }
val LocalDarkMode      = compositionLocalOf { DarkModePreference.SYSTEM }
val LocalDesignSystem  = compositionLocalOf { DesignSystem.MD3 }

// ── MD3 color schemes ───────────────────────────────────────────────────────

private fun cyberDarkScheme() = darkColorScheme(
    primary            = CyberPrimary,
    onPrimary          = CyberOnPrimary,
    primaryContainer   = CyberPrimaryContainer,
    onPrimaryContainer = CyberOnPrimaryContainer,
    secondary          = CyberSecondary,
    background         = CyberBackground,
    surface            = CyberSurface,
    surfaceVariant     = CyberSurfaceVariant,
    onSurface          = CyberOnSurface,
    outline            = CyberOutline,
)

private fun cyberLightScheme() = lightColorScheme(
    primary            = CyberPrimaryContainer,
    onPrimary          = CyberOnPrimary,
    primaryContainer   = CyberPrimary,
    onPrimaryContainer = CyberBackground,
    secondary          = CyberSecondary,
    background         = Color(0xFFF0FDFF),
    surface            = Color(0xFFFFFFFF),
    onSurface          = CyberBackground,
)

// Shared dark surfaces so every coloured theme reads as a proper deep-dark UI instead of
// falling back to Material's grey defaults (which clashed with the light containers the
// themes ship for light mode). Each theme just supplies its own accent colours.
private val DarkBg        = Color(0xFF0A0E13)
private val DarkSurface   = Color(0xFF121822)
private val DarkSurfaceV  = Color(0xFF1C2430)
private val DarkOnSurface = Color(0xFFE2E8F0)
private val DarkOutline   = Color(0xFF2C3744)

private fun darkScheme(
    primary: Color,
    secondary: Color,
    container: Color,
    onContainer: Color,
    background: Color = DarkBg,
    surface: Color = DarkSurface,
) = darkColorScheme(
    primary            = primary,
    onPrimary          = Color(0xFF06121A),
    primaryContainer   = container,
    onPrimaryContainer = onContainer,
    secondary          = secondary,
    background         = background,
    surface            = surface,
    surfaceVariant     = DarkSurfaceV,
    onSurface          = DarkOnSurface,
    onSurfaceVariant   = DarkOnSurface.copy(alpha = 0.72f),
    outline            = DarkOutline,
)

private fun oceanScheme(dark: Boolean) = if (dark) darkScheme(
    primary = Color(0xFF4FC3F7), secondary = OceanSecondary,
    container = Color(0xFF00405C), onContainer = Color(0xFFCAE9FF),
) else lightColorScheme(
    primary = OceanPrimary, onPrimary = OceanOnPrimary,
    primaryContainer = OceanPrimaryContainer,
    onPrimaryContainer = OceanOnPrimaryContainer,
    secondary = OceanSecondary,
    background = OceanBackground, surface = OceanSurface,
    surfaceVariant = OceanSurfaceVariant, onSurface = OceanOnSurface,
    outline = OceanOutline,
)

private fun forestScheme(dark: Boolean) = if (dark) darkScheme(
    primary = Color(0xFF74C69D), secondary = Color(0xFF95D5B2),
    container = Color(0xFF1B4332), onContainer = Color(0xFFB7E4C7),
) else lightColorScheme(
    primary = ForestPrimary, onPrimary = ForestOnPrimary,
    primaryContainer = ForestPrimaryContainer,
    onPrimaryContainer = ForestOnPrimaryContainer,
    secondary = ForestSecondary,
    background = ForestBackground, surface = ForestSurface,
    surfaceVariant = ForestSurfaceVariant, onSurface = ForestOnSurface,
    outline = ForestOutline,
)

private fun sunsetScheme(dark: Boolean) = if (dark) darkScheme(
    primary = Color(0xFFFFB74D), secondary = SunsetSecondary,
    container = Color(0xFF5A2E00), onContainer = Color(0xFFFFDCC2),
) else lightColorScheme(
    primary = SunsetPrimary, onPrimary = SunsetOnPrimary,
    primaryContainer = SunsetPrimaryContainer,
    onPrimaryContainer = SunsetOnPrimaryContainer,
    secondary = SunsetSecondary,
    background = SunsetBackground, surface = SunsetSurface,
    surfaceVariant = SunsetSurfaceVariant, onSurface = SunsetOnSurface,
    outline = SunsetOutline,
)

private fun roseScheme(dark: Boolean) = if (dark) darkScheme(
    primary = Color(0xFFFF6FA5), secondary = Color(0xFFFF8FB0),
    container = Color(0xFF5C0030), onContainer = Color(0xFFFFD9E2),
) else lightColorScheme(
    primary = Color(0xFFB3005D), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9E2), onPrimaryContainer = Color(0xFF3E0021),
    secondary = Color(0xFFE0608F),
    background = Color(0xFFFFF8F9), surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF6DDE4), onSurface = Color(0xFF1F1A1C),
    outline = Color(0xFFD8A9B8),
)

private fun violetScheme(dark: Boolean) = if (dark) darkScheme(
    primary = Color(0xFFB388FF), secondary = Color(0xFF9C6BFF),
    container = Color(0xFF3A1A5C), onContainer = Color(0xFFE9DDFF),
) else lightColorScheme(
    primary = Color(0xFF6A1B9A), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEBDCFF), onPrimaryContainer = Color(0xFF26003E),
    secondary = Color(0xFF8E5BD0),
    background = Color(0xFFFBF7FF), surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9DEF2), onSurface = Color(0xFF1C1B1F),
    outline = Color(0xFFC4B2D6),
)

// Pure-black OLED-friendly theme — true #000 surfaces, monochrome accent.
private fun amoledScheme(dark: Boolean) = if (dark) darkScheme(
    primary = Color(0xFFEDEDED), secondary = Color(0xFFB0B0B0),
    container = Color(0xFF1C1C1C), onContainer = Color(0xFFEDEDED),
    background = Color(0xFF000000), surface = Color(0xFF000000),
) else lightColorScheme(
    primary = Color(0xFF1A1A1A), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E0E0), onPrimaryContainer = Color(0xFF111111),
    secondary = Color(0xFF555555),
    background = Color(0xFFFAFAFA), surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE7E7E7), onSurface = Color(0xFF1A1A1A),
    outline = Color(0xFFC2C2C2),
)

// ── Resolve MD3 ColorScheme from AppTheme ───────────────────────────────────

fun resolveColorScheme(appTheme: AppTheme, isDark: Boolean): ColorScheme = when (appTheme) {
    AppTheme.CYBER   -> if (isDark) cyberDarkScheme()  else cyberLightScheme()
    AppTheme.OCEAN   -> oceanScheme(isDark)
    AppTheme.FOREST  -> forestScheme(isDark)
    AppTheme.SUNSET  -> sunsetScheme(isDark)
    AppTheme.ROSE    -> roseScheme(isDark)
    AppTheme.VIOLET  -> violetScheme(isDark)
    AppTheme.AMOLED  -> amoledScheme(isDark)
}

/**
 * Android keeps Miuix's springy overscroll here; the desktop scrollbar pipeline has
 * no overscroll hook, so this is a no-op kept for source parity with the screens.
 */
@Composable
fun Modifier.miuixSpringScroll(): Modifier = this

/** Smoothly animate every scheme colour — the Miuix "animated theme transitions". */
@Composable
private fun ColorScheme.animated(): ColorScheme {
    val spec = tween<Color>(durationMillis = 450)
    return copy(
        primary = animateColorAsState(primary, spec, label = "c_primary").value,
        onPrimary = animateColorAsState(onPrimary, spec, label = "c_onPrimary").value,
        primaryContainer = animateColorAsState(primaryContainer, spec, label = "c_primaryContainer").value,
        onPrimaryContainer = animateColorAsState(onPrimaryContainer, spec, label = "c_onPrimaryContainer").value,
        secondary = animateColorAsState(secondary, spec, label = "c_secondary").value,
        background = animateColorAsState(background, spec, label = "c_background").value,
        surface = animateColorAsState(surface, spec, label = "c_surface").value,
        surfaceVariant = animateColorAsState(surfaceVariant, spec, label = "c_surfaceVariant").value,
        onSurface = animateColorAsState(onSurface, spec, label = "c_onSurface").value,
        onSurfaceVariant = animateColorAsState(onSurfaceVariant, spec, label = "c_onSurfaceVariant").value,
        outline = animateColorAsState(outline, spec, label = "c_outline").value,
    )
}

// ── Main theme wrapper ────────────────────────────────────────────────────────

@Composable
fun palazikVPNTheme(
    appTheme: AppTheme = AppTheme.CYBER,
    darkModePreference: DarkModePreference = DarkModePreference.SYSTEM,
    useMiuix: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (darkModePreference) {
        DarkModePreference.ALWAYS_DARK  -> true
        DarkModePreference.ALWAYS_LIGHT -> false
        DarkModePreference.SYSTEM       -> systemDark
    }

    CompositionLocalProvider(
        LocalAppTheme     provides appTheme,
        LocalDarkMode     provides darkModePreference,
        LocalDesignSystem provides if (useMiuix) DesignSystem.MIUIX else DesignSystem.MD3,
    ) {
        // The UI is always Material 3. When Miuix animations are enabled, theme/dark-mode
        // switches crossfade every scheme colour (the desktop equivalent of MiuixTheme's
        // animated transitions on Android).
        val colorScheme = resolveColorScheme(appTheme, isDark)
        MaterialTheme(
            colorScheme = if (useMiuix) colorScheme.animated() else colorScheme,
            typography  = palazikTypography,
            content     = content,
        )
    }
}
