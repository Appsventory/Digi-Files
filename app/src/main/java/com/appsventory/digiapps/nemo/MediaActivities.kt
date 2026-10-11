package com.appsventory.digiapps.nemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.appsventory.digiapps.nemo.ui.PlaceholderScreen
import com.appsventory.digiapps.nemo.ui.SideShortcut
import com.appsventory.digiapps.nemo.ui.rememberMusicPlayer
import com.appsventory.digiapps.nemo.ui.theme.setDigiContent

/**
 * Large screens / TV open Pictures, Music and Movies as their own activity.
 * (Phones show the same content as an in-shell page, see MediaPage.)
 * Override onCreate in the concrete activity when building the real UI.
 */
abstract class PlaceholderActivity : ComponentActivity() {
    abstract val shortcut: SideShortcut

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setDigiContent {
            PlaceholderScreen(
                shortcut = shortcut,
                player = rememberMusicPlayer(),
                onBack = { finish() },
                // the file browser lives in MainActivity: go back to it, opened at that folder
                onShowInFolder = { folder ->
                    AppNavigator.showInFolder(this, folder)
                    finish()
                }
            )
        }
    }
}

class PicturesActivity : PlaceholderActivity() {
    override val shortcut = SideShortcut.PICTURES
}

class MusicActivity : PlaceholderActivity() {
    override val shortcut = SideShortcut.MUSIC
}

class MoviesActivity : PlaceholderActivity() {
    override val shortcut = SideShortcut.MOVIES
}
