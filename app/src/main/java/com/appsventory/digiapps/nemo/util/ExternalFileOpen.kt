package com.appsventory.digiapps.nemo.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.appsventory.digiapps.nemo.R
import com.appsventory.digiapps.nemo.VideoPlayerActivity
import com.appsventory.digiapps.nemo.ui.ViewerImage
import java.io.File
import java.io.FileOutputStream

/**
 * Resolves an external ACTION_VIEW / share Intent into something DigiFiles can open,
 * and routes it to the correct in-app viewer when possible.
 */
object ExternalFileOpen {

    enum class Kind { ARCHIVE, IMAGE, VIDEO, AUDIO, PDF, TEXT, FOLDER, OTHER }

    data class Resolved(
        val kind: Kind,
        /** Local filesystem path when available (preferred for archives). */
        val path: String? = null,
        /** Content or file URI for media players. */
        val uri: Uri? = null,
        val displayName: String = "",
        val mime: String? = null,
        /** Parent folder path when we only want to show the location. */
        val parentPath: String? = null
    )

    private val IMAGE_EXTS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif"
    )
    private val VIDEO_EXTS = setOf(
        "mp4", "mkv", "avi", "webm", "3gp", "mov", "wmv", "flv", "m4v", "ts", "m2ts"
    )
    private val AUDIO_EXTS = setOf(
        "mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus", "amr"
    )
    private val TEXT_EXTS = setOf(
        "txt", "log", "md", "csv", "json", "xml", "html", "htm", "css", "js", "kt", "java", "py", "sh", "cfg", "ini", "properties"
    )

    fun resolve(context: Context, intent: Intent?): Resolved? {
        if (intent == null) return null
        val action = intent.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) return null

        val uri: Uri? = when (action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> intent.data
        }
        if (uri == null) return null

        // Take temporary read permission when granted
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val mime = normalizeMime(
            intent.type
                ?: context.contentResolver.getType(uri)
                ?: guessMimeFromUri(uri)
        )
        val name = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "file"
        val path = resolveLocalPath(context, uri)

        if (path != null) {
            val f = File(path)
            if (f.isDirectory) {
                return Resolved(Kind.FOLDER, path = path, displayName = name, mime = mime)
            }
            if (f.isFile) {
                return classified(path, Uri.fromFile(f), name, mime)
            }
        }

        // Content URI without a local path — still usable for media
        return classified(null, uri, name, mime)
    }

    private fun classified(path: String?, uri: Uri?, name: String, mime: String?): Resolved {
        val ext = name.substringAfterLast('.', "").lowercase()
        val kind = when {
            path != null && File(path).isDirectory -> Kind.FOLDER
            archiveFormatOf(name) != null -> Kind.ARCHIVE
            mime?.startsWith("image/") == true || ext in IMAGE_EXTS -> Kind.IMAGE
            mime?.startsWith("video/") == true || ext in VIDEO_EXTS -> Kind.VIDEO
            mime?.startsWith("audio/") == true || ext in AUDIO_EXTS -> Kind.AUDIO
            mime == "application/pdf" || ext == "pdf" -> Kind.PDF
            mime?.startsWith("text/") == true || ext in TEXT_EXTS -> Kind.TEXT
            else -> Kind.OTHER
        }
        val parent = path?.let { File(it).parent }
        return Resolved(kind, path, uri, name, mime, parent)
    }

    /** Pure classification for unit tests (no Context / filesystem). */
    fun classifyForTest(name: String, mime: String?): Kind =
        classified(null, null, name, normalizeMime(mime)).kind

    /**
     * Open the resolved file in the appropriate DigiFiles viewer.
     * Returns true if handled in-app; false if caller should fall back (e.g. show folder only).
     *
     * [onArchive] is invoked with a local File ready for the archive browser.
     * [onImage] is invoked with a single ViewerImage list.
     * [onPdf] / [onText] receive a content or file URI for document viewers.
     * [onNavigateFolder] is called when we only navigate to a parent folder.
     * [onUnsupported] is called when the type is not viewable in DigiFiles.
     */
    fun open(
        context: Context,
        resolved: Resolved,
        musicPlayer: MusicPlayer,
        onArchive: (File) -> Unit,
        onImage: (List<ViewerImage>, Int) -> Unit,
        onPdf: (Uri, String) -> Unit = { _, _ -> },
        onText: (Uri, String) -> Unit = { _, _ -> },
        onNavigateFolder: (String) -> Unit,
        onUnsupported: () -> Unit = {
            Toast.makeText(context, R.string.viewer_unsupported, Toast.LENGTH_SHORT).show()
        }
    ): Boolean {
        when (resolved.kind) {
            Kind.FOLDER -> {
                resolved.path?.let { onNavigateFolder(it) }
                return resolved.path != null
            }
            Kind.ARCHIVE -> {
                val local = resolved.path?.let { File(it) }?.takeIf { it.isFile }
                    ?: resolved.uri?.let { copyUriToCache(context, it, resolved.displayName) }
                if (local == null || !local.isFile) {
                    Toast.makeText(context, R.string.toast_invalid_file, Toast.LENGTH_SHORT).show()
                    return true
                }
                onArchive(local)
                return true
            }
            Kind.IMAGE -> {
                val uri = resolved.uri ?: pathToProviderUri(context, resolved.path)
                if (uri == null) {
                    Toast.makeText(context, R.string.toast_invalid_file, Toast.LENGTH_SHORT).show()
                    return true
                }
                onImage(listOf(ViewerImage(uri, resolved.displayName, resolved.path)), 0)
                return true
            }
            Kind.VIDEO -> {
                val uri = resolved.uri ?: resolved.path?.let { Uri.fromFile(File(it)) }
                if (uri == null) {
                    Toast.makeText(context, R.string.toast_invalid_file, Toast.LENGTH_SHORT).show()
                    return true
                }
                VideoPlayerActivity.start(context, uri, resolved.displayName)
                return true
            }
            Kind.AUDIO -> {
                val uri = resolved.uri ?: resolved.path?.let { Uri.fromFile(File(it)) }
                if (uri == null) {
                    Toast.makeText(context, R.string.toast_invalid_file, Toast.LENGTH_SHORT).show()
                    return true
                }
                val entry = MediaEntry(
                    id = uri.toString().hashCode().toLong(),
                    uri = uri,
                    title = resolved.displayName.substringBeforeLast('.').ifBlank { resolved.displayName },
                    kind = MediaKind.AUDIO,
                    dateMillis = System.currentTimeMillis(),
                    size = 0L,
                    durationMs = 0L,
                    group = "",
                    artist = "",
                    albumId = 0L,
                    path = resolved.path
                )
                musicPlayer.play(listOf(entry), 0)
                return true
            }
            Kind.PDF -> {
                val uri = resolved.uri ?: pathToProviderUri(context, resolved.path)
                if (uri == null) {
                    Toast.makeText(context, R.string.toast_invalid_file, Toast.LENGTH_SHORT).show()
                    return true
                }
                onPdf(uri, resolved.displayName)
                return true
            }
            Kind.TEXT -> {
                val uri = resolved.uri ?: pathToProviderUri(context, resolved.path)
                if (uri == null) {
                    Toast.makeText(context, R.string.toast_invalid_file, Toast.LENGTH_SHORT).show()
                    return true
                }
                onText(uri, resolved.displayName)
                return true
            }
            Kind.OTHER -> {
                // Do not silently open an unrelated folder for unsupported types.
                onUnsupported()
                return true
            }
        }
    }

    private fun pathToProviderUri(context: Context, path: String?): Uri? {
        if (path.isNullOrBlank()) return null
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
        }.getOrNull() ?: Uri.fromFile(File(path))
    }

    private fun normalizeMime(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "*/*") return null
        return raw.lowercase().trim()
    }

    private fun guessMimeFromUri(uri: Uri): String? {
        val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            ?: uri.lastPathSegment?.substringAfterLast('.', "")
        if (ext.isNullOrBlank()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path?.let { File(it).name }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
        }.getOrNull()
    }

    /**
     * Best-effort local path for a content URI (MediaStore, Downloads, etc.).
     * Returns null when the provider does not expose a filesystem path.
     */
    fun resolveLocalPath(context: Context, uri: Uri): String? {
        when (uri.scheme) {
            "file" -> return uri.path
            "content" -> {
                // MediaStore path columns (may be null on scoped storage)
                val candidates = listOf(
                    MediaStore.MediaColumns.DATA,
                    "_data"
                )
                for (col in candidates) {
                    val path = runCatching {
                        context.contentResolver.query(uri, arrayOf(col), null, null, null)?.use { c ->
                            val idx = c.getColumnIndex(col)
                            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                        }
                    }.getOrNull()
                    if (!path.isNullOrBlank() && File(path).exists()) return path
                }
                // Document providers sometimes encode the real path after the last colon
                val p = uri.path ?: return null
                if (p.contains(":")) {
                    val after = p.substringAfterLast(":")
                    val guessed = if (after.startsWith("/")) after else "/storage/emulated/0/$after"
                    if (File(guessed).exists()) return guessed
                }
            }
        }
        return null
    }

    /** Copy a content URI into the app cache so archive APIs that need a File can open it. */
    fun copyUriToCache(context: Context, uri: Uri, displayName: String): File? {
        return runCatching {
            val safeName = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "open.bin" }
            val dir = File(context.cacheDir, "external_open").also { it.mkdirs() }
            val out = File(dir, "${System.currentTimeMillis()}_$safeName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            } ?: return null
            out
        }.getOrNull()
    }
}
