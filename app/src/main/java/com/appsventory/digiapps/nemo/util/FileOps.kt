package com.appsventory.digiapps.nemo.util

import android.content.Context
import com.appsventory.digiapps.nemo.smb.SmbClient
import com.appsventory.digiapps.nemo.smb.smbHostOf
import android.media.MediaScannerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Files waiting to be pasted (copy or move). */
data class FileClipboard(val paths: List<String>, val move: Boolean)

/** What to do when the destination already has an item with the same name. */
enum class ConflictPolicy {
    /** Keep both: the new one becomes "name (1)". */
    KEEP_BOTH,
    /** Replace: the old item goes to Trash (local) or is deleted (network), the new one takes its name. */
    REPLACE,
    /** Leave the old one alone and don't copy / move the new one. */
    SKIP
}

/** Outcome of copying / moving one top-level item. */
sealed interface OpResult {
    /**
     * Done; [target] is the created path. [replaced] is true when an existing item was swapped out:
     * undoing that would delete the only copy left, so such items are not offered for Undo.
     */
    class Done(val target: String, val replaced: Boolean = false) : OpResult
    /** Nothing to do (moving into the same folder, or a folder into itself). */
    object Skipped : OpResult
    /** Could not be done (read/write error, no space, source could not be removed...). */
    object Failed : OpResult
}

/** Blocking file operations — call from Dispatchers.IO. */
object FileOps {
    private val TRASH_NAME = Regex("^\\.trashed-\\d+-")

    fun originalName(trashedName: String): String = trashedName.replace(TRASH_NAME, "")

    /** "a.txt" -> "a (1).txt" when the name is taken. */
    fun uniqueTarget(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var i = 1
        while (target.exists()) {
            target = File(dir, "$base ($i)$ext")
            i++
        }
        return target
    }

    /** Total bytes of a file, or of everything under a folder (local or SMB). */
    fun sizeOf(path: String): Long = Vfs.sizeOf(path)

    /** Names in [paths] that [destDir] already has (the item itself being in [destDir] doesn't count). */
    fun findConflicts(paths: List<String>, destDir: String): List<String> =
        paths.filter { src ->
            val same = Vfs.child(destDir, Vfs.name(src))
            !Vfs.samePath(same, src) && Vfs.exists(same)
        }.map { Vfs.name(it) }

    /**
     * After [temp] was copied / moved in next to [existing]: remove the old one (to Trash when local, so a
     * mistake is recoverable) and give [temp] its name. If the old one can't be removed both stay.
     */
    private fun swapIn(existing: String, temp: String, name: String): OpResult.Done {
        val removed = if (Vfs.isSmb(existing)) Vfs.deleteRecursively(existing) else trash(File(existing)) != null
        if (!removed && Vfs.exists(existing)) return OpResult.Done(temp)
        return OpResult.Done(Vfs.rename(temp, name) ?: temp, replaced = true)
    }

    /**
     * Copy with byte progress; works between local storage and SMB in any direction.
     * A failed or cancelled copy removes the partial result (and never touches an existing item).
     */
    /**
     * Copy with byte progress; works between local storage and SMB in any direction.
     *
     * Directory merge rules (Feature C):
     * - Destination folder does not exist → create it and copy normally.
     * - Both source and destination are directories with the same name → merge contents recursively;
     *   never replace or delete the entire destination folder merely because names match.
     * - File name conflict → Apply [policy] (Replace / Keep both / Skip) at that level.
     * - File-versus-directory conflict → treated as a name conflict; policy decides.
     * - A failed or cancelled file-level copy removes only the partial file (never the whole tree).
     */
    suspend fun copyTracked(
        src: String,
        destDir: String,
        policy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
        onBytes: (Long) -> Unit
    ): OpResult {
        if (Vfs.isDirectory(src) && isSameOrChild(destDir, src)) return OpResult.Skipped
        val name = Vfs.name(src)
        val plain = Vfs.child(destDir, name)
        val conflict = Vfs.exists(plain) && !Vfs.samePath(plain, src)

        // Same-name directories: merge into the existing folder (never swap the whole tree).
        if (conflict && Vfs.isDirectory(src) && Vfs.isDirectory(plain)) {
            return try {
                mergeDirectory(src, plain, policy, onBytes)
                OpResult.Done(plain)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OpResult.Failed
            }
        }

        if (conflict && policy == ConflictPolicy.SKIP) return OpResult.Skipped

        // File vs file, or file vs dir, or dir into free name: unique name then optional swap for REPLACE.
        val target = if (conflict && policy == ConflictPolicy.KEEP_BOTH) {
            Vfs.uniqueChild(destDir, name)
        } else if (conflict && policy == ConflictPolicy.REPLACE) {
            // Write to a temp unique name first so a failed write never destroys the existing item.
            Vfs.uniqueChild(destDir, name)
        } else {
            plain // free name
        }
        try {
            copyInto(src, target, onBytes)
        } catch (e: CancellationException) {
            Vfs.deleteRecursively(target)
            throw e
        } catch (e: Exception) {
            Vfs.deleteRecursively(target)
            return OpResult.Failed
        }
        return if (conflict && policy == ConflictPolicy.REPLACE) swapIn(plain, target, name) else OpResult.Done(target)
    }

    /**
     * Recursively merge [srcDir] into an existing [destDir].
     * Child conflicts use the same [policy]. Never deletes [destDir] itself.
     */
    private suspend fun mergeDirectory(
        srcDir: String,
        destDir: String,
        policy: ConflictPolicy,
        onBytes: (Long) -> Unit
    ) {
        val ctx = currentCoroutineContext()
        for (child in Vfs.list(srcDir)) {
            ctx.ensureActive()
            val childSrc = child.path
            val childName = child.name
            val childDest = Vfs.child(destDir, childName)
            val childConflict = Vfs.exists(childDest)

            if (Vfs.isDirectory(childSrc) && (!childConflict || Vfs.isDirectory(childDest))) {
                if (!childConflict) {
                    if (!Vfs.mkdir(childDest) && !Vfs.isDirectory(childDest)) {
                        throw java.io.IOException("Can't create $childDest")
                    }
                }
                // Recurse whether the dest folder already existed or we just created it.
                mergeDirectory(childSrc, childDest, policy, onBytes)
                continue
            }

            // File (or file-vs-dir) conflict handling
            if (childConflict) {
                when (policy) {
                    ConflictPolicy.SKIP -> continue
                    ConflictPolicy.KEEP_BOTH -> {
                        val unique = Vfs.uniqueChild(destDir, childName)
                        copyInto(childSrc, unique, onBytes)
                    }
                    ConflictPolicy.REPLACE -> {
                        // Write to unique temp, then swap so a failed write cannot destroy the original.
                        val temp = Vfs.uniqueChild(destDir, childName)
                        try {
                            copyInto(childSrc, temp, onBytes)
                            swapIn(childDest, temp, childName)
                        } catch (e: CancellationException) {
                            Vfs.deleteRecursively(temp)
                            throw e
                        } catch (e: Exception) {
                            Vfs.deleteRecursively(temp)
                            throw e
                        }
                    }
                }
            } else {
                copyInto(childSrc, childDest, onBytes)
            }
        }
    }

    private suspend fun copyInto(src: String, target: String, onBytes: (Long) -> Unit) {
        val ctx = currentCoroutineContext()
        if (Vfs.isDirectory(src)) {
            // mkdirs() returns false on failure (read-only target, no space): don't carry on writing into nothing
            if (!Vfs.mkdir(target) && !Vfs.isDirectory(target)) throw java.io.IOException("Can't create $target")
            for (child in Vfs.list(src)) copyInto(child.path, Vfs.child(target, child.name), onBytes)
        } else {
            Vfs.input(src).use { input ->
                Vfs.output(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ctx.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        onBytes(n.toLong())
                    }
                    output.flush()
                }
            }
            // keep the original "modified" date on local copies
            if (!Vfs.isSmb(src) && !Vfs.isSmb(target)) {
                runCatching { File(target).setLastModified(File(src).lastModified()) }
            }
        }
    }

    /**
     * Move: instant rename when possible (same disk / same server, no name conflict that needs merge),
     * otherwise copy (with merge) + delete source only after the destination is verified.
     * Same-name directories always go through merge so the destination folder is never replaced wholesale.
     */
    suspend fun moveTracked(
        src: String,
        destDir: String,
        policy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
        onBytes: (Long) -> Unit
    ): OpResult {
        if (Vfs.parent(src)?.let { Vfs.samePath(it, destDir) } == true) return OpResult.Skipped
        if (Vfs.isDirectory(src) && isSameOrChild(destDir, src)) return OpResult.Skipped
        val name = Vfs.name(src)
        val plain = Vfs.child(destDir, name)
        val conflict = Vfs.exists(plain) && !Vfs.samePath(plain, src)
        if (conflict && policy == ConflictPolicy.SKIP) return OpResult.Skipped

        // Directory merge cannot be done with rename; fall through to copy+delete.
        val needsMerge = conflict && Vfs.isDirectory(src) && Vfs.isDirectory(plain)
        if (!needsMerge) {
            val replace = conflict && policy == ConflictPolicy.REPLACE
            if (!Vfs.isSmb(src) && !Vfs.isSmb(destDir)) {
                val target = if (conflict) Vfs.uniqueChild(destDir, name) else plain
                if (File(src).renameTo(File(target))) {
                    return if (replace) swapIn(plain, target, name) else OpResult.Done(target)
                }
            } else if (Vfs.isSmb(src) && Vfs.isSmb(destDir) && smbHostOf(src) == smbHostOf(destDir)) {
                val target = if (conflict) Vfs.uniqueChild(destDir, name) else plain
                if (SmbClient.renameTo(src, target)) {
                    return if (replace) swapIn(plain, target, name) else OpResult.Done(target)
                }
            }
        }
        val copied = copyTracked(src, destDir, policy, onBytes)
        if (copied !is OpResult.Done) return copied
        // Source is deleted only after a verified successful copy/merge.
        if (!Vfs.deleteRecursively(src) && Vfs.exists(src)) return OpResult.Failed
        return copied
    }

    /** Local files under [path] (the path itself when it is a file), capped so huge trees stay cheap. */
    fun localFiles(path: String, limit: Int = 2000): List<String> {
        if (Vfs.isSmb(path)) return emptyList()
        val out = ArrayList<String>()
        fun walk(f: File) {
            if (out.size >= limit) return
            if (f.isDirectory) f.listFiles()?.forEach { walk(it) } else out += f.absolutePath
        }
        walk(File(path))
        return out
    }

    /** Tell MediaStore about added / removed files so Gallery and Music match the disk. */
    fun scanMedia(context: Context, paths: Collection<String>) {
        if (paths.isEmpty()) return
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
    }

    /** Rename inside the same folder. Null if invalid or the name is taken. */
    fun rename(file: File, newName: String): File? {
        val name = newName.trim()
        if (name.isEmpty() || name.contains('/') || name == file.name) return null
        val target = File(file.parentFile ?: return null, name)
        if (target.exists()) return null
        return if (file.renameTo(target)) target else null
    }

    /** Move to Trash = rename to ".trashed-<seconds>-<name>" (same convention as Android's own trash). */
    fun trash(file: File): File? {
        val dir = file.parentFile ?: return null
        val target = uniqueTarget(dir, ".trashed-${System.currentTimeMillis() / 1000}-${file.name}")
        return if (file.renameTo(target)) target else null
    }

    /** Trash (default) or delete for good, then refresh MediaStore so galleries stay in sync. */
    fun deleteOrTrash(context: Context, path: String, permanent: Boolean): Boolean {
        if (Vfs.isSmb(path)) return Vfs.deleteRecursively(path) // no Trash on network shares
        val file = File(path)
        if (!file.exists()) return false
        val ok = if (permanent) file.deleteRecursively() else trash(file) != null
        MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        return ok
    }

    /** Drop the ".trashed-<ts>-" prefix; the file stays in its original folder. */
    fun restore(trashed: File): Boolean = runCatching {
        val dir = trashed.parentFile ?: return false
        trashed.renameTo(uniqueTarget(dir, originalName(trashed.name)))
    }.getOrDefault(false)
}
