package com.appsventory.digiapps.nemo.util

import android.content.Context
import android.os.StatFs
import com.appsventory.digiapps.nemo.R
import java.io.File

/** (used, total) bytes untuk volume yang berisi [dir]. (0, 0) jika gagal. */
fun storageStats(dir: File): Pair<Long, Long> = try {
    if (!dir.exists()) {
        Pair(0L, 0L)
    } else {
        val s = StatFs(dir.absolutePath)
        Pair(s.totalBytes - s.availableBytes, s.totalBytes)
    }
} catch (_: Exception) {
    Pair(0L, 0L)
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}

/** "12.3 GB Free" (localized). */
fun Context.freeLabel(freeBytes: Long): String =
    getString(R.string.storage_free, formatBytes(freeBytes))

/** Path sama dengan [parent] atau berada di bawahnya. */
fun isSameOrChild(path: String, parent: String): Boolean =
    path == parent || path.startsWith(parent.trimEnd('/') + "/")

/** Dot-file / dot-folder. */
fun File.isHiddenEntry(): Boolean = name.startsWith(".")
