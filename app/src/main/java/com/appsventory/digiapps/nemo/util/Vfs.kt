package com.appsventory.digiapps.nemo.util

import com.appsventory.digiapps.nemo.smb.SmbClient
import com.appsventory.digiapps.nemo.smb.isSmbPath
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** One file or folder, local or on an SMB server. */
class VNode(val path: String, val name: String, val isDirectory: Boolean, val size: Long, val lastModified: Long)

/**
 * Path-based file access that works for local paths (/storage/...) and SMB paths (smb://host/...).
 * Blocking — call from Dispatchers.IO.
 */
object Vfs {
    fun isSmb(path: String) = isSmbPath(path)

    fun name(path: String): String =
        if (isSmb(path)) path.trimEnd('/').substringAfterLast('/') else File(path).name

    fun parent(path: String): String? =
        if (isSmb(path)) SmbClient.parent(path) else File(path).parent

    fun child(dir: String, name: String): String =
        if (isSmb(dir)) SmbClient.join(dir, name) else File(dir, name).path

    private fun norm(path: String) = path.trimEnd('/')

    fun samePath(a: String, b: String) = norm(a) == norm(b)

    fun node(path: String): VNode? = if (isSmb(path)) {
        SmbClient.stat(path)?.let { VNode(it.path, it.name, it.isDirectory, it.size, it.lastModified) }
    } else {
        File(path).takeIf { it.exists() }?.let {
            VNode(it.absolutePath, it.name, it.isDirectory, if (it.isFile) it.length() else 0L, it.lastModified())
        }
    }

    fun exists(path: String): Boolean = if (isSmb(path)) SmbClient.exists(path) else File(path).exists()

    fun isDirectory(path: String): Boolean = node(path)?.isDirectory == true

    fun list(path: String): List<VNode> = if (isSmb(path)) {
        SmbClient.list(path).map { VNode(it.path, it.name, it.isDirectory, it.size, it.lastModified) }
    } else {
        File(path).listFiles().orEmpty().map {
            VNode(it.absolutePath, it.name, it.isDirectory, if (it.isFile) it.length() else 0L, it.lastModified())
        }
    }

    fun input(path: String): InputStream = if (isSmb(path)) SmbClient.input(path) else File(path).inputStream()

    fun output(path: String): OutputStream = if (isSmb(path)) SmbClient.output(path) else File(path).outputStream()

    fun mkdir(path: String): Boolean = if (isSmb(path)) SmbClient.mkdir(path) else File(path).mkdirs()

    fun deleteRecursively(path: String): Boolean =
        if (isSmb(path)) SmbClient.delete(path) else File(path).deleteRecursively()

    /** Rename inside the same folder. Returns the new path, or null if invalid / taken / failed. */
    fun rename(path: String, newName: String): String? {
        val name = newName.trim()
        if (name.isEmpty() || name.contains('/') || name == name(path)) return null
        val parent = parent(path) ?: return null
        val target = child(parent, name)
        if (exists(target)) return null
        val ok = if (isSmb(path)) SmbClient.renameTo(path, target) else File(path).renameTo(File(target))
        return if (ok) target else null
    }

    /** "a.txt" -> "a (1).txt" when the name is taken inside [dir]. */
    fun uniqueChild(dir: String, name: String): String {
        var target = child(dir, name)
        if (!exists(target)) return target
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var i = 1
        while (exists(target)) {
            target = child(dir, "$base ($i)$ext")
            i++
        }
        return target
    }

    /** Bytes of a file or of everything under a folder. */
    fun sizeOf(path: String): Long {
        val n = node(path) ?: return 0L
        if (!n.isDirectory) return n.size
        return list(path).sumOf { sizeOf(it.path) }
    }

    /** (files, folders) under a folder. */
    fun countContents(path: String): Pair<Int, Int> {
        var files = 0
        var folders = 0
        fun walk(dir: String) {
            list(dir).forEach {
                if (it.isDirectory) {
                    folders++
                    walk(it.path)
                } else files++
            }
        }
        walk(path)
        return files to folders
    }
}
