package com.appsventory.digiapps.nemo.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import com.appsventory.digiapps.nemo.R
import com.appsventory.digiapps.nemo.util.AppSettings
import com.appsventory.digiapps.nemo.util.BrandColor
import com.appsventory.digiapps.nemo.util.ThemeMode

@Composable
fun DigiFilesTheme(
    content: @Composable () -> Unit
) {
    val dark = when (AppSettings.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.AUTO -> isSystemInDarkTheme()
    }
    val palette = buildPalette(dark, AppSettings.brand)

    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.accent, onPrimary = palette.onAccent,
            primaryContainer = palette.accentDim, onPrimaryContainer = palette.onAccent,
            secondary = palette.accentDim, onSecondary = palette.onAccent,
            background = palette.bg, onBackground = palette.textPrimary,
            surface = palette.surface, onSurface = palette.textPrimary,
            surfaceVariant = palette.card, onSurfaceVariant = palette.textSecondary,
            outline = palette.divider, error = palette.error, onError = palette.onError
        )
    } else {
        lightColorScheme(
            primary = palette.accent, onPrimary = palette.onAccent,
            primaryContainer = palette.accentDim, onPrimaryContainer = palette.onAccent,
            secondary = palette.accentDim, onSecondary = palette.onAccent,
            background = palette.bg, onBackground = palette.textPrimary,
            surface = palette.surface, onSurface = palette.textPrimary,
            surfaceVariant = palette.card, onSurfaceVariant = palette.textSecondary,
            outline = palette.divider, error = palette.error, onError = palette.onError
        )
    }

    CompositionLocalProvider(LocalPalette provides palette, LocalIsDark provides dark) {
        MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
    }
}

/** Brand color of a preset, read from colors.xml (or the wallpaper color for SYSTEM). */
@Composable
fun brandColor(brand: BrandColor, dark: Boolean): Color {
    val context = LocalContext.current
    if (brand == BrandColor.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).primary
    }
    val res = when (brand) {
        BrandColor.BLUE -> R.color.brand_blue
        BrandColor.GREEN -> R.color.brand_green
        BrandColor.ORANGE -> R.color.brand_orange
        BrandColor.PINK -> R.color.brand_pink
        BrandColor.PURPLE -> R.color.brand_purple
        BrandColor.RED -> R.color.brand_red
        BrandColor.CYAN, BrandColor.SYSTEM -> R.color.cyan_accent
    }
    return colorResource(res)
}

@Composable
private fun buildPalette(dark: Boolean, brand: BrandColor): Palette {
    val accent = brandColor(brand, dark)
    val onDark = colorResource(R.color.on_accent)
    val onLight = colorResource(R.color.on_accent_light)
    // readable content on the brand color, whatever hue the user picks
    val onAccent = if (accent.luminance() > 0.35f) onDark else onLight
    // keep the shadow opacity defined in colors.xml, take the hue from the brand color
    val shadowAlpha = colorResource(R.color.focus_shadow).alpha

    return Palette(
        bg = colorResource(if (dark) R.color.bg_dark else R.color.light_bg),
        surface = colorResource(if (dark) R.color.surface_dark else R.color.light_surface),
        card = colorResource(if (dark) R.color.card_dark else R.color.light_card),
        divider = colorResource(if (dark) R.color.divider_dark else R.color.light_divider),
        textPrimary = colorResource(if (dark) R.color.text_primary else R.color.light_text_primary),
        textSecondary = colorResource(if (dark) R.color.text_secondary else R.color.light_text_secondary),
        textMuted = colorResource(if (dark) R.color.text_muted else R.color.light_text_muted),
        active = colorResource(if (dark) R.color.active_color else R.color.light_active),
        accent = accent,
        accentDim = lerp(accent, onDark, 0.2f),
        onAccent = onAccent,
        onAccentVariant = onAccent.copy(alpha = colorResource(R.color.on_accent_variant).alpha),
        focusShadow = accent.copy(alpha = shadowAlpha),
        idleDashed = colorResource(if (dark) R.color.idle_dashed else R.color.light_idle_dashed),
        error = colorResource(R.color.error_red),
        onError = colorResource(R.color.on_error)
    )
}
