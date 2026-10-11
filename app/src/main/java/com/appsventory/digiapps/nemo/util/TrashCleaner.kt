package com.appsventory.digiapps.nemo.util

import java.io.File

/** Deletes trashed items (".trashed-<seconds>-name") that are older than the retention setting. */
object TrashCleaner {
    private val STAMP = Regex("^\\.trashed-(\\d+)-")

    /** @return how many items were deleted */
    suspend fun purge(roots: List<StorageRoot>, days: Int): Int {
        if (days <= 0) return 0
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        var deleted = 0
        for (root in roots) {
            val trashed = scanStorage(File(root.path), match = { it.name.startsWith(TRASH_PREFIX) })
            for (file in trashed) {
                val stamp = STAMP.find(file.name)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)
                    ?: file.lastModified()
                if (stamp < cutoff && file.deleteRecursively()) deleted++
            }
        }
        return deleted
    }
}
