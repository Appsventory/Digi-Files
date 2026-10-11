package com.appsventory.digiapps.nemo.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Every color the UI uses, resolved for the current theme (dark / light) and brand color.
 * Hex values live ONLY in res/values/colors.xml; Theme.kt builds a Palette from them.
 */
class Palette(
    val bg: Color,
    val surface: Color,
    val card: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val active: Color,
    val accent: Color,
    val accentDim: Color,
    val onAccent: Color,
    val onAccentVariant: Color,
    val focusShadow: Color,
    val idleDashed: Color,
    val error: Color,
    val onError: Color
)

val LocalPalette = staticCompositionLocalOf<Palette> { error("DigiFilesTheme missing") }
val LocalIsDark = staticCompositionLocalOf { true }

private val palette: Palette @Composable @ReadOnlyComposable get() = LocalPalette.current

// Brand (changes with Settings > Accent color)
val CyanAccent: Color @Composable @ReadOnlyComposable get() = palette.accent
val CyanDim: Color @Composable @ReadOnlyComposable get() = palette.accentDim

// Surface
val BgDark: Color @Composable @ReadOnlyComposable get() = palette.bg
val SurfaceDark: Color @Composable @ReadOnlyComposable get() = palette.surface
val CardDark: Color @Composable @ReadOnlyComposable get() = palette.card
val DividerDark: Color @Composable @ReadOnlyComposable get() = palette.divider

// Text
val TextPrimary: Color @Composable @ReadOnlyComposable get() = palette.textPrimary
val TextSecondary: Color @Composable @ReadOnlyComposable get() = palette.textSecondary
val TextMuted: Color @Composable @ReadOnlyComposable get() = palette.textMuted

// State: focus = brand color, active = neutral
val FocusColor: Color @Composable @ReadOnlyComposable get() = palette.accent
val ActiveColor: Color @Composable @ReadOnlyComposable get() = palette.active

// Content on top of the brand color
val OnAccent: Color @Composable @ReadOnlyComposable get() = palette.onAccent
val OnAccentVariant: Color @Composable @ReadOnlyComposable get() = palette.onAccentVariant

// Focus effect on cards
val FocusStroke: Color @Composable @ReadOnlyComposable get() = palette.accent
val FocusShadow: Color @Composable @ReadOnlyComposable get() = palette.focusShadow
val MarkStroke: Color @Composable @ReadOnlyComposable get() = palette.accentDim
val IdleDashed: Color @Composable @ReadOnlyComposable get() = palette.idleDashed

// Error
val ErrorRed: Color @Composable @ReadOnlyComposable get() = palette.error
val OnError: Color @Composable @ReadOnlyComposable get() = palette.onError
