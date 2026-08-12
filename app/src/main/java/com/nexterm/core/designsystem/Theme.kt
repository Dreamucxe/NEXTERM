package com.nexterm.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nexterm.data.model.TerminalTheme

/**
 * Material 3 theming for NEXTERM, seeded from the active terminal palette.
 *
 * The chrome around the terminal takes its accent from whichever theme the terminal
 * itself is using, so choosing a palette recolours the whole app rather than leaving
 * a purple toolbar sitting above a green terminal. Dynamic colour is honoured on
 * Android 12+ when the user asks for it, because that is the platform convention.
 */
@Composable
fun NextermTheme(
    terminalTheme: TerminalTheme,
    useDynamicColor: Boolean = false,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = remember(terminalTheme, useDynamicColor, darkTheme) {
        when {
            useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

            else -> schemeFrom(terminalTheme, darkTheme)
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NextermTypography,
        content = content,
    )
}

/**
 * Builds a scheme from the terminal palette.
 *
 * Terminal themes only define a handful of colours, so the rest are derived: surfaces
 * are the terminal background lifted in steps, and the "on" colours are picked by
 * luminance so text stays legible whether the palette is dark or light.
 */
private fun schemeFrom(theme: TerminalTheme, dark: Boolean): ColorScheme {
    val background = Color(theme.background)
    val accent = Color(theme.accent)
    val onAccent = contrastAgainst(accent)
    val surface = if (dark) background.lift(0.04f) else background
    val base = if (dark) darkColorScheme() else lightColorScheme()

    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accent.copy(alpha = 0.22f).composite(surface),
        onPrimaryContainer = contrastAgainst(surface),
        secondary = Color(theme.ansi[6]),
        onSecondary = contrastAgainst(Color(theme.ansi[6])),
        tertiary = Color(theme.ansi[5]),
        onTertiary = contrastAgainst(Color(theme.ansi[5])),
        background = background,
        onBackground = Color(theme.foreground),
        surface = surface,
        onSurface = Color(theme.foreground),
        surfaceVariant = surface.lift(0.06f),
        onSurfaceVariant = Color(theme.foreground).copy(alpha = 0.75f).composite(surface),
        surfaceContainerLowest = background,
        surfaceContainerLow = surface.lift(0.02f),
        surfaceContainer = surface.lift(0.05f),
        surfaceContainerHigh = surface.lift(0.08f),
        surfaceContainerHighest = surface.lift(0.11f),
        outline = Color(theme.foreground).copy(alpha = 0.30f).composite(surface),
        outlineVariant = Color(theme.foreground).copy(alpha = 0.14f).composite(surface),
        error = Color(theme.ansi[1]),
        onError = contrastAgainst(Color(theme.ansi[1])),
        errorContainer = Color(theme.ansi[1]).copy(alpha = 0.24f).composite(surface),
        onErrorContainer = contrastAgainst(surface),
        inverseSurface = Color(theme.foreground),
        inverseOnSurface = background,
        scrim = Color.Black,
    )
}

/** Moves a colour towards white (dark themes) or black (light themes) by [amount]. */
private fun Color.lift(amount: Float): Color {
    val target = if (luminance() < 0.5f) Color.White else Color.Black
    return Color(
        red = red + (target.red - red) * amount,
        green = green + (target.green - green) * amount,
        blue = blue + (target.blue - blue) * amount,
        alpha = alpha,
    )
}

/** Flattens a translucent colour over an opaque one, since M3 wants opaque roles. */
private fun Color.composite(over: Color): Color = Color(
    red = red * alpha + over.red * (1 - alpha),
    green = green * alpha + over.green * (1 - alpha),
    blue = blue * alpha + over.blue * (1 - alpha),
    alpha = 1f,
)

/** Black or white, whichever stays readable on [color]. */
private fun contrastAgainst(color: Color): Color =
    if (color.luminance() > 0.45f) Color(0xFF0A0A0A) else Color(0xFFF6F8FC)

/** Terminal-adjacent type: tight, technical, and monospaced where it matters. */
val NextermTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
        bodySmall = base.bodySmall.copy(lineHeight = 18.sp),
    )
}

/** For paths, sizes, permissions and command text — anywhere alignment carries meaning. */
val MonoTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)
