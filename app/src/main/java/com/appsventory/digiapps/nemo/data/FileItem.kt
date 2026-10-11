package com.appsventory.digiapps.nemo.data

import com.appsventory.digiapps.nemo.util.VNode
import java.io.File

data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    /** Storage label (Trash / APK lists span several storages). */
    val origin: String? = null
) {
    companion object {
        fun from(node: VNode): FileItem = FileItem(node.name, node.path, node.isDirectory, node.size, node.lastModified)

        fun from(file: File): FileItem = FileItem(
            name = file.name,
            path = file.absolutePath,
            isDirectory = file.isDirectory,
            size = if (file.isFile) file.length() else 0L,
            lastModified = file.lastModified()
        )
    }
}
