package com.appsventory.digiapps.nemo.ui

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.os.FileObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/*
 * Live-update helpers: each returns a counter that goes up whenever something changed on disk.
 * Use the counter as a key of a LaunchedEffect (add a short delay() first, so a burst of events
 * - e.g. a download writing its file - becomes one reload).
 */

private const val WATCH_MASK = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or
    FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF

/** Goes up when a file is added / removed / renamed / finished writing directly inside [path] (local folders only). */
@Composable
fun rememberDirectoryTick(path: String?): Int {
    var tick by remember(path) { mutableIntStateOf(0) }
    DisposableEffect(path) {
        if (path == null) return@DisposableEffect onDispose { }
        val main = Handler(Looper.getMainLooper())
        // the String constructor is deprecated but is the only one that exists below Android 10 (minSdk 24)
        @Suppress("DEPRECATION")
        val observer = object : FileObserver(path, WATCH_MASK) {
            override fun onEvent(event: Int, name: String?) {
                main.post { tick++ }
            }
        }
        runCatching { observer.startWatching() }
        onDispose { runCatching { observer.stopWatching() } }
    }
    return tick
}

/**
 * Goes up when MediaStore changes anywhere (download finished, photo taken, USB drive scanned...).
 * Catches changes made by other apps, which a folder watcher can miss.
 */
@Composable
fun rememberMediaStoreTick(): Int {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                tick++
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(
                Uri.parse("content://${MediaStore.AUTHORITY}/external"), true, observer
            )
        }
        onDispose { runCatching { context.contentResolver.unregisterContentObserver(observer) } }
    }
    return tick
}
