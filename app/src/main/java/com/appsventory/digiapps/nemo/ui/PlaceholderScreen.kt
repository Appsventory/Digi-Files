package com.appsventory.digiapps.nemo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.appsventory.digiapps.nemo.util.MusicPlayer

/** Standalone screen (large screens / TV open Pictures, Music, Movies as their own activity). */
@Composable
fun PlaceholderScreen(
    shortcut: SideShortcut,
    player: MusicPlayer,
    onBack: () -> Unit,
    onShowInFolder: ((String) -> Unit)? = null
) {
    ScreenScaffold(title = stringResource(shortcut.labelRes), onBack = onBack) {
        MediaHost(shortcut, player, showTitle = false, onShowInFolder = onShowInFolder)
    }
}

/** In-shell page (phone): the bottom bar stays put, only this page slides. */
@Composable
fun MediaPage(shortcut: SideShortcut, player: MusicPlayer, onShowInFolder: ((String) -> Unit)? = null) {
    MediaHost(shortcut, player, showTitle = true, onShowInFolder = onShowInFolder)
}
