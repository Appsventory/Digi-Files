package com.appsventory.digiapps.nemo.ui.theme

import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import com.appsventory.digiapps.nemo.R
import com.appsventory.digiapps.nemo.util.AppSettings

/**
 * Entry point for EVERY activity.
 * - Loads settings, applies the theme (dark / light / auto) and the brand color.
 * - Edge-to-edge with system bar icons matching the theme (light icons on dark, dark on light).
 * - Safe area: content is padded below the status bar / cutout and away from side bars.
 *   The bottom inset is left to the screen (NavigationBar pads itself; plain screens use
 *   Modifier.navigationBarsPadding()).
 */
fun ComponentActivity.setDigiContent(content: @Composable () -> Unit) {
    AppSettings.ensureLoaded(this)
    val bar = ContextCompat.getColor(this, R.color.bg_dark)
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(bar),
        navigationBarStyle = SystemBarStyle.dark(bar)
    )
    setContent {
        DigiFilesTheme {
            val dark = LocalIsDark.current
            val bg = BgDark
            LaunchedEffect(dark, bg) {
                val scrim = bg.toArgb()
                val style = if (dark) SystemBarStyle.dark(scrim) else SystemBarStyle.light(scrim, scrim)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                window.setBackgroundDrawable(ColorDrawable(scrim)) // no dark flash in light theme
            }
            Surface(modifier = Modifier.fillMaxSize(), color = bg) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                        )
                ) { content() }
            }
        }
    }
}
