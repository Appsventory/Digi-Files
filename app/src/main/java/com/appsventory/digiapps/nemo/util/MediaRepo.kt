package com.appsventory.digiapps.nemo.util

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.appsventory.digiapps.nemo.smb.SmbConnection
import com.appsventory.digiapps.nemo.smb.SmbStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class MediaKind { IMAGE, VIDEO, AUDIO }

/**
 * One MediaStore row.
 * [group] = album folder (images/videos) or album name (audio).
 */
data class MediaEntry(
    val id: Long,
    val uri: Uri,
    val title: String,
    val kind: MediaKind,
    val dateMillis: Long,
    val size: Long,
    val durationMs: Long,
    val group: String,
    val artist: String,
    val albumId: Long,
    /** File path (for delete / rename), when MediaStore exposes it. */
    val path: String? = null,
    /** Pixel width from MediaStore (0 when unknown). Used by the Gallery masonry grid. */
    val width: Int = 0,
    /** Pixel height from MediaStore (0 when unknown). */
    val height: Int = 0
)

/** True for an entry read from an SMB server rather than MediaStore. */
val MediaEntry.isSmb: Boolean get() = path?.startsWith("smb://") == true

private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "ts")
private val AUDIO_EXTS = setOf("mp3", "m4a", "flac", "wav", "ogg", "aac", "wma", "opus")

/** Reads photos / videos / music from MediaStore (needs storage access), and from saved SMB servers. */
object MediaRepo {

    suspend fun load(context: Context, kind: MediaKind): List<MediaEntry> = withContext(Dispatchers.IO) {
        val base = when (kind) {
            MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = when (kind) {
            MediaKind.IMAGE -> arrayOf(
                MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.SIZE, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.DATA,
                MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT
            )
            MediaKind.VIDEO -> arrayOf(
                MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DATE_TAKEN,
                MediaStore.Video.Media.SIZE, MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Video.Media.DURATION, MediaStore.MediaColumns.DATA,
                MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT
            )
            MediaKind.AUDIO -> arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
                MediaStore.MediaColumns.DATA
            )
        }
        val selection = if (kind == MediaKind.AUDIO) "${MediaStore.Audio.Media.IS_MUSIC} != 0" else null

        val result = mutableListOf<MediaEntry>()
        runCatching {
            context.contentResolver.query(base, projection, selection, null, null)?.use { c ->
                fun long(name: String) = c.getColumnIndex(name).let { if (it >= 0 && !c.isNull(it)) c.getLong(it) else 0L }
                fun text(name: String) = c.getColumnIndex(name).let { if (it >= 0 && !c.isNull(it)) c.getString(it) else "" }
                while (c.moveToNext()) {
                    val id = long(MediaStore.MediaColumns._ID)
                    val taken = long("datetaken")
                    val date = if (taken > 0) taken else long(MediaStore.MediaColumns.DATE_ADDED) * 1000
                    result += when (kind) {
                        MediaKind.AUDIO -> MediaEntry(
                            id, ContentUris.withAppendedId(base, id),
                            text(MediaStore.Audio.Media.TITLE), kind, date,
                            long(MediaStore.MediaColumns.SIZE), long(MediaStore.Audio.Media.DURATION),
                            text(MediaStore.Audio.Media.ALBUM).ifBlank { "Unknown" },
                            text(MediaStore.Audio.Media.ARTIST).let { if (it.isBlank() || it == "<unknown>") "Unknown" else it },
                            long(MediaStore.Audio.Media.ALBUM_ID),
                            text(MediaStore.MediaColumns.DATA).ifEmpty { null }
                        )
                        else -> MediaEntry(
                            id, ContentUris.withAppendedId(base, id),
                            text(MediaStore.MediaColumns.DISPLAY_NAME), kind, date,
                            long(MediaStore.MediaColumns.SIZE),
                            if (kind == MediaKind.VIDEO) long(MediaStore.Video.Media.DURATION) else 0L,
                            text("bucket_display_name").ifBlank { "Other" }, "", 0L,
                            text(MediaStore.MediaColumns.DATA).ifEmpty { null },
                            long("width").toInt(), long("height").toInt()
                        )
                    }
                }
            }
        }
        if (kind == MediaKind.AUDIO) result.sortedBy { it.title.lowercase() }
        else result.sortedByDescending { it.dateMillis }
    }

    /** Local (MediaStore, internal storage) + whichever extra sources are enabled in Settings. */
    suspend fun loadCombined(context: Context, kind: MediaKind, forceRefresh: Boolean = false): List<MediaEntry> {
        val local = load(context, kind)
        val extra = ExtraMediaCache.get(context, kind, forceRefresh)
        val all = local + extra
        return if (kind == MediaKind.AUDIO) all.sortedBy { it.title.lowercase() } else all.sortedByDescending { it.dateMillis }
    }

    /** One volume/server's worth of media, capped so one huge one can't hang the scan. */
    private const val SCAN_LIMIT_PER_SOURCE = 500

    /** "smb:<connection id>" - the id stored in AppSettings.enabledMediaSources for a server. */
    fun sourceIdFor(connection: SmbConnection): String = "smb:${connection.id}"

    private fun extsFor(kind: MediaKind): Set<String> = when (kind) {
        MediaKind.IMAGE -> IMAGE_EXTS
        MediaKind.VIDEO -> VIDEO_EXTS
        MediaKind.AUDIO -> AUDIO_EXTS
    }

    /** SD card / USB drives the user turned on in Settings > Media sources, plus every saved
     *  SMB server that's turned on the same way. Internal storage isn't included here - it's
     *  already covered by [load] via MediaStore. */
    suspend fun loadExtraSources(context: Context, kind: MediaKind): List<MediaEntry> = withContext(Dispatchers.IO) {
        val exts = extsFor(kind)
        val enabled = AppSettings.enabledMediaSources

        val localVolumes = detectVolumes(context, "")
            .filter { it.kind != VolumeKind.INTERNAL && it.path in enabled }
        val local = localVolumes.flatMap { vol -> scanLocalVolume(vol.path, exts, kind) }

        val smbConnections = SmbStore.connections.filter { sourceIdFor(it) in enabled }
        val smb = smbConnections.flatMap { conn -> scanServer(conn, exts, kind) }

        local + smb
    }

    private fun scanLocalVolume(root: String, exts: Set<String>, kind: MediaKind): List<MediaEntry> {
        val result = mutableListOf<MediaEntry>()
        val stack = ArrayDeque<File>().apply { add(File(root)) }
        while (stack.isNotEmpty() && result.size < SCAN_LIMIT_PER_SOURCE) {
            val children = runCatching { stack.removeLast().listFiles() }.getOrNull() ?: continue
            for (child in children) {
                if (child.isHiddenEntry()) continue
                if (child.isDirectory) {
                    stack.add(child)
                } else if (child.extension.lowercase() in exts) {
                    result += MediaEntry(
                        id = child.absolutePath.hashCode().toLong(),
                        uri = Uri.fromFile(child), // a real local file - Coil can read it directly, no extra cost
                        title = child.name,
                        kind = kind,
                        dateMillis = child.lastModified(),
                        size = child.length(),
                        durationMs = 0L,
                        group = child.parentFile?.name ?: "Other",
                        artist = "",
                        albumId = 0L,
                        path = child.absolutePath
                    )
                    if (result.size >= SCAN_LIMIT_PER_SOURCE) break
                }
            }
        }
        return result
    }

    private fun scanServer(conn: SmbConnection, exts: Set<String>, kind: MediaKind): List<MediaEntry> {
        val result = mutableListOf<MediaEntry>()
        val stack = ArrayDeque<String>().apply { add(conn.root) }
        // best-effort: an unreachable server just yields nothing for this pass, it isn't retried
        // until the cache goes stale - see ExtraMediaCache
        while (stack.isNotEmpty() && result.size < SCAN_LIMIT_PER_SOURCE) {
            val dir = stack.removeLast()
            val children = runCatching { Vfs.list(dir) }.getOrDefault(emptyList())
            for (child in children) {
                if (child.isDirectory) {
                    stack.add(child.path)
                } else if (child.name.substringAfterLast('.', "").lowercase() in exts) {
                    result += MediaEntry(
                        id = child.path.hashCode().toLong(),
                        uri = Uri.EMPTY, // no local/MediaStore Uri for a network file - see MediaEntry.isSmb
                        title = child.name,
                        kind = kind,
                        dateMillis = child.lastModified,
                        size = child.size,
                        durationMs = 0L,
                        group = conn.title,
                        artist = "",
                        albumId = 0L,
                        path = child.path
                    )
                    if (result.size >= SCAN_LIMIT_PER_SOURCE) break
                }
            }
        }
        return result
    }
}

/**
 * Caches each media kind's extra-source scan (SD/USB + SMB) for a while so opening
 * Gallery/Music/Movies repeatedly doesn't re-scan every time - that's the actual cost worth
 * avoiding, since browsing a folder/share is already on-demand (FileBrowserScreen) and
 * unaffected by this. A manual refresh (icon next to the tabs) forces an early re-scan.
 */
object ExtraMediaCache {
    private const val STALE_MS = 10 * 60 * 1000L // 10 minutes
    private class Cached(val items: List<MediaEntry>, val at: Long)

    private val cache = mutableMapOf<MediaKind, Cached>()
    private val mutex = Mutex()

    suspend fun get(context: Context, kind: MediaKind, forceRefresh: Boolean = false): List<MediaEntry> {
        if (AppSettings.enabledMediaSources.isEmpty()) return emptyList()
        fun fresh(c: Cached?) = c != null && !forceRefresh && System.currentTimeMillis() - c.at < STALE_MS
        cache[kind]?.let { if (fresh(it)) return it.items }
        return mutex.withLock {
            cache[kind]?.let { if (fresh(it)) return@withLock it.items } // someone else refreshed while we waited
            val items = runCatching { MediaRepo.loadExtraSources(context, kind) }.getOrDefault(emptyList())
            cache[kind] = Cached(items, System.currentTimeMillis())
            items
        }
    }

    /** Call when a source is toggled, or a server added/removed/edited, for an immediate re-scan. */
    fun invalidate() {
        cache.clear()
    }
}
