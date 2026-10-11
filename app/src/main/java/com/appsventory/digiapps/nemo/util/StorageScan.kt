package com.appsventory.digiapps.nemo.util

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Prefix Android gives to files/folders moved to trash: ".trashed-<timestamp>-<name>". */
const val TRASH_PREFIX = ".trashed"

/**
 * Walk [root] and return every entry (file or folder) where [match] is true.
 * - does not descend into matched trash folders
 * - skips Android/data and Android/obb (not readable, slow)
 * - cancellable
 */
suspend fun scanStorage(
    root: File,
    match: (File) -> Boolean,
    limit: Int = Int.MAX_VALUE,
    descend: (File) -> Boolean = { true }
): List<File> {
    val ctx = currentCoroutineContext()
    val skip = setOf(File(root, "Android/data").path, File(root, "Android/obb").path)
    val result = mutableListOf<File>()
    val stack = ArrayDeque<File>().apply { add(root) }
    while (stack.isNotEmpty()) {
        ctx.ensureActive()
        val children = stack.removeLast().listFiles() ?: continue
        for (child in children) {
            if (match(child)) {
                result += child
                if (result.size >= limit) return result
            }
            if (child.isDirectory && !child.name.startsWith(TRASH_PREFIX) && child.path !in skip && descend(child)) {
                stack.add(child)
            }
        }
    }
    return result
}
