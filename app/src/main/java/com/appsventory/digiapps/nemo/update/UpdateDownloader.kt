package com.appsventory.digiapps.nemo.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import com.appsventory.digiapps.nemo.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads release assets with the system [DownloadManager] and tracks them per
 * (release, asset) — see [assetKey]. Nothing here relies on an in-memory flag alone:
 *
 *  - every download is recorded in SharedPreferences ([DownloadRecord]) so it can be re-attached
 *    after the screen closes or the process dies;
 *  - a file only becomes [AssetDownloadState.Downloaded] after [LocalFileCheck] passed
 *    (exists, non-empty, published size, and for APKs a readable package);
 *  - progress comes from DownloadManager's own byte counters, polled off the main thread.
 *
 * Files live in the app's own external files dir, so no storage permission is needed to verify or
 * install them (FileProvider's `external-files-path` already covers it).
 */
object UpdateDownloader {

    private const val PREFS = "update_downloads"
    private const val POLL_MS = 400L
    private const val SUBDIR = "updates"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val lock = Any()

    private val _states = MutableStateFlow<Map<String, AssetDownloadState>>(emptyMap())

    /** key ([assetKey]) → state. Missing key = not resolved yet (the UI treats it as NotDownloaded). */
    val states: StateFlow<Map<String, AssetDownloadState>> = _states.asStateFlow()

    private fun put(key: String, state: AssetDownloadState) {
        _states.update { it + (key to state) }
    }

    fun isAnyDownloading(): Boolean = _states.value.values.any { it is AssetDownloadState.Downloading }

    // ───────────────────────── paths + persistence ─────────────────────────

    private fun relativePath(releaseTag: String, asset: GitHubAsset): String =
        "$SUBDIR/${safeSegment(releaseTag)}/${safeSegment(asset.name)}"

    private fun targetFile(context: Context, releaseTag: String, asset: GitHubAsset): File? {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        return File(base, relativePath(releaseTag, asset))
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun record(context: Context, key: String): DownloadRecord? =
        DownloadRecord.decode(prefs(context).getString(key, null))

    private fun saveRecord(context: Context, key: String, r: DownloadRecord) {
        prefs(context).edit().putString(key, r.encode()).apply()
    }

    private fun clearRecord(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }

    // ───────────────────────── public API ─────────────────────────

    /**
     * Start downloading [asset]. Returns false (and does nothing) when a download is already
     * running — this is the guard that makes repeated taps harmless.
     */
    fun start(context: Context, releaseTag: String, asset: GitHubAsset): Boolean {
        if (asset.browserDownloadUrl.isBlank()) return false
        val app = context.applicationContext
        val key = assetKey(releaseTag, asset)
        synchronized(lock) {
            if (isAnyDownloading()) return false
            put(key, AssetDownloadState.Downloading(0L, asset.size))
        }
        scope.launch { runDownload(app, key, releaseTag, asset) }
        return true
    }

    /** Cancel an in-flight download and delete its partial file. */
    fun cancel(context: Context, releaseTag: String, asset: GitHubAsset) {
        val app = context.applicationContext
        val key = assetKey(releaseTag, asset)
        jobs.remove(key)?.cancel()
        put(key, AssetDownloadState.NotDownloaded) // also stops a start() that has not enqueued yet
        scope.launch {
            record(app, key)?.let { rec ->
                runCatching { app.getSystemService(DownloadManager::class.java)?.remove(rec.downloadId) }
            }
            clearRecord(app, key)
            targetFile(app, releaseTag, asset)?.delete()
        }
    }

    /**
     * Work out the real state of [asset] from DownloadManager + the file on disk and publish it.
     * Blocking disk/IPC work: call from a background dispatcher.
     * Leaves alone a download that is currently being tracked in this process.
     */
    fun refresh(context: Context, releaseTag: String, asset: GitHubAsset) {
        val app = context.applicationContext
        val key = assetKey(releaseTag, asset)
        if (_states.value[key] is AssetDownloadState.Downloading && jobs[key]?.isActive == true) return

        val rec = record(app, key)
        val dm = app.getSystemService(DownloadManager::class.java)

        if (rec != null && dm != null) {
            val snap = query(dm, rec.downloadId)
            if (snap != null) {
                when (snap.status) {
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PAUSED -> {
                        // started earlier (screen closed / process restarted): re-attach
                        put(
                            key,
                            AssetDownloadState.Downloading(
                                snap.bytes,
                                totalOf(snap.total, asset.size),
                                snap.status == DownloadManager.STATUS_PAUSED
                            )
                        )
                        track(app, key, rec.downloadId, File(rec.path), asset.size, asset.isApk)
                        return
                    }
                    DownloadManager.STATUS_FAILED -> {
                        clearRecord(app, key)
                        File(rec.path).delete()
                        put(key, AssetDownloadState.Failed(reasonToFailure(snap.reason)))
                        return
                    }
                }
            }
        }

        // SUCCESSFUL, or no record at all: trust only what is verifiably on disk.
        val file = rec?.let { File(it.path) } ?: targetFile(app, releaseTag, asset)
        if (file == null) {
            put(key, AssetDownloadState.NotDownloaded)
            return
        }
        put(key, finalState(app, key, file, asset.size, asset.isApk))
    }

    // ───────────────────────── internals ─────────────────────────

    private fun runDownload(app: Context, key: String, releaseTag: String, asset: GitHubAsset) {
        val dm = app.getSystemService(DownloadManager::class.java)
        val target = targetFile(app, releaseTag, asset)
        if (dm == null || target == null) {
            put(key, AssetDownloadState.Failed(DownloadFailure.STORAGE))
            return
        }
        try {
            val dir = target.parentFile
            if (dir == null) {
                put(key, AssetDownloadState.Failed(DownloadFailure.STORAGE))
                return
            }
            dir.mkdirs()
            if (!dir.isDirectory) {
                put(key, AssetDownloadState.Failed(DownloadFailure.STORAGE))
                return
            }
            // stale copy / stale DownloadManager row of the same asset
            record(app, key)?.let { runCatching { dm.remove(it.downloadId) } }
            clearRecord(app, key)
            if (target.exists()) target.delete()

            // insufficient storage: fail early with a clear reason instead of a vague DM error
            if (asset.size > 0) {
                val free = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
                if (free < asset.size + asset.size / 10 + 2L * 1024 * 1024) {
                    put(key, AssetDownloadState.Failed(DownloadFailure.STORAGE))
                    return
                }
            }

            val request = DownloadManager.Request(Uri.parse(asset.browserDownloadUrl)).apply {
                setTitle(asset.name)
                setDescription(app.getString(R.string.update_downloading))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, relativePath(releaseTag, asset))
                if (asset.isApk) setMimeType("application/vnd.android.package-archive")
            }
            val id = dm.enqueue(request)

            // cancelled while we were enqueuing?
            if (_states.value[key] !is AssetDownloadState.Downloading) {
                runCatching { dm.remove(id) }
                return
            }
            saveRecord(app, key, DownloadRecord(id, target.absolutePath, asset.size))
            track(app, key, id, target, asset.size, asset.isApk)
        } catch (t: Throwable) {
            put(key, AssetDownloadState.Failed(DownloadFailure.UNKNOWN))
        }
    }

    /** Poll DownloadManager until the download ends; publishes progress without touching the UI thread. */
    private fun track(app: Context, key: String, id: Long, file: File, expectedSize: Long, isApk: Boolean) {
        jobs[key]?.cancel()
        jobs[key] = scope.launch {
            val dm = app.getSystemService(DownloadManager::class.java)
            if (dm == null) {
                put(key, AssetDownloadState.Failed(DownloadFailure.UNKNOWN))
                return@launch
            }
            while (isActive) {
                val snap = query(dm, id)
                if (snap == null) {
                    // row vanished (user removed it in the system UI)
                    clearRecord(app, key)
                    put(key, AssetDownloadState.NotDownloaded)
                    break
                }
                var finished = false
                when (snap.status) {
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING ->
                        put(key, AssetDownloadState.Downloading(snap.bytes, totalOf(snap.total, expectedSize)))
                    DownloadManager.STATUS_PAUSED ->
                        put(key, AssetDownloadState.Downloading(snap.bytes, totalOf(snap.total, expectedSize), waiting = true))
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val result = finalState(app, key, file, expectedSize, isApk)
                        // a "successful" download whose file does not verify is a failed download, not a blank slate
                        put(
                            key,
                            if (result is AssetDownloadState.NotDownloaded)
                                AssetDownloadState.Failed(DownloadFailure.INCOMPLETE)
                            else result
                        )
                        finished = true
                    }
                    DownloadManager.STATUS_FAILED -> {
                        clearRecord(app, key)
                        file.delete()
                        put(key, AssetDownloadState.Failed(reasonToFailure(snap.reason)))
                        finished = true
                    }
                }
                if (finished) break
                delay(POLL_MS)
            }
        }
    }

    /** Verify [file]; Downloaded only when it passes, otherwise NotDownloaded (and the bad file is removed). */
    private fun finalState(
        app: Context,
        key: String,
        file: File,
        expectedSize: Long,
        isApk: Boolean
    ): AssetDownloadState {
        val pm = app.packageManager
        val verdict = LocalFileCheck.verify(file, expectedSize, isApk) { f ->
            runCatching { pm.getPackageArchiveInfo(f.absolutePath, 0) != null }.getOrDefault(false)
        }
        return if (verdict == FileVerdict.OK) {
            AssetDownloadState.Downloaded(file.absolutePath, file.length())
        } else {
            clearRecord(app, key)
            if (verdict != FileVerdict.MISSING) file.delete()
            AssetDownloadState.NotDownloaded
        }
    }

    private class Snap(val status: Int, val reason: Int, val bytes: Long, val total: Long)

    private fun query(dm: DownloadManager, id: Long): Snap? =
        runCatching {
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) {
                    null
                } else {
                    Snap(
                        status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                        bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    )
                }
            }
        }.getOrNull()

    /** DownloadManager's total when it knows it, else GitHub's published size, else unknown (-1 → indeterminate). */
    private fun totalOf(dmTotal: Long, publishedSize: Long): Long =
        if (dmTotal > 0) dmTotal else if (publishedSize > 0) publishedSize else -1L

    private fun reasonToFailure(reason: Int): DownloadFailure = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE,
        DownloadManager.ERROR_DEVICE_NOT_FOUND,
        DownloadManager.ERROR_FILE_ERROR,
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> DownloadFailure.STORAGE
        DownloadManager.ERROR_HTTP_DATA_ERROR,
        DownloadManager.ERROR_CANNOT_RESUME,
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> DownloadFailure.NETWORK
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> DownloadFailure.HTTP
        in 400..599 -> DownloadFailure.HTTP // raw HTTP status reported as the reason
        else -> DownloadFailure.UNKNOWN
    }
}
