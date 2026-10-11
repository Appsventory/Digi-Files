package com.appsventory.digiapps.nemo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appsventory.digiapps.nemo.ui.theme.TextPrimary
import com.appsventory.digiapps.nemo.util.MediaKind
import com.appsventory.digiapps.nemo.util.MusicPlayer

/**
 * Pictures / Music / Movies content, read from MediaStore.
 * Used by the in-shell pages (phone) and by the standalone activities (large screens / TV).
 */
@Composable
fun MediaHost(
    shortcut: SideShortcut,
    player: MusicPlayer,
    showTitle: Boolean,
    /** "Show in folder": opens the file browser at that folder. */
    onShowInFolder: ((String) -> Unit)? = null
) {
    val access = rememberStorageAccess()
    Column(Modifier.fillMaxSize()) {
        if (showTitle) {
            Text(
                stringResource(shortcut.labelRes),
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
        when {
            !access.granted -> PermissionRequired(onRequest = access.request)
            shortcut == SideShortcut.PICTURES -> GalleryScreen(MediaKind.IMAGE, onShowInFolder)
            shortcut == SideShortcut.MOVIES -> GalleryScreen(MediaKind.VIDEO, onShowInFolder)
            shortcut == SideShortcut.MUSIC -> MusicScreen(player, onShowInFolder)
        }
    }
}
