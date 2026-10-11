package com.appsventory.digiapps.nemo.update

import java.io.File

/** Why a download did not finish. The UI maps each to a string resource. */
enum class DownloadFailure {
    /** Connection lost / server unreachable / data error. */
    NETWORK,
    /** Not enough free space, or the storage is unavailable. */
    STORAGE,
    /** Server answered with an HTTP error (404, 5xx, …). */
    HTTP,
    /** Finished, but the file on disk is missing, short or not a valid package. */
    INCOMPLETE,
    UNKNOWN
}

/** State of ONE release asset's local copy. */
sealed interface AssetDownloadState {
    /** No usable local copy: show **Download**. */
    data object NotDownloaded : AssetDownloadState

    /**
     * [totalBytes] <= 0 means the size is not reliably known → indeterminate progress.
     * [waiting] = the system paused it (no network / waiting for Wi-Fi); it resumes by itself.
     */
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val waiting: Boolean = false
    ) : AssetDownloadState {
        /** 0f..1f, or null when no reliable total exists (never invent a percentage). */
        val fraction: Float?
            get() = if (totalBytes > 0 && !waiting) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
        val percent: Int? get() = fraction?.let { (it * 100).toInt().coerceIn(0, 100) }
    }

    /** Verified, complete local file. */
    data class Downloaded(val path: String, val sizeBytes: Long) : AssetDownloadState

    data class Failed(val reason: DownloadFailure) : AssetDownloadState
}

/** Stable identity of one asset of one release: a different asset never reuses another's state. */
fun assetKey(releaseTag: String, asset: GitHubAsset): String =
    "${releaseTag.trim()}\n${asset.name}\n${asset.browserDownloadUrl}".let { raw ->
        // short, prefs-friendly, deterministic
        java.lang.Long.toHexString(raw.hashCode().toLong() and 0xffffffffL) +
            java.lang.Long.toHexString(raw.reversed().hashCode().toLong() and 0xffffffffL)
    }

/** Folder/file-name safe segment. */
fun safeSegment(s: String): String = s.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "_" }.take(120)

/** What is persisted so a finished/in-flight download survives the screen closing or the app dying. */
data class DownloadRecord(
    val downloadId: Long,
    val path: String,
    val expectedSize: Long
) {
    fun encode(): String = "$downloadId|$expectedSize|$path"

    companion object {
        fun decode(raw: String?): DownloadRecord? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split('|', limit = 3)
            if (parts.size != 3) return null
            val id = parts[0].toLongOrNull() ?: return null
            val size = parts[1].toLongOrNull() ?: return null
            if (parts[2].isBlank()) return null
            return DownloadRecord(id, parts[2], size)
        }
    }
}

enum class FileVerdict { OK, MISSING, INCOMPLETE, INVALID }

object LocalFileCheck {
    /**
     * A local copy counts only if it exists, is non-empty, matches the published size (when known)
     * and — for APKs — the package parser can actually read it ([apkReadable]).
     */
    fun verify(
        file: File,
        expectedSize: Long,
        isApk: Boolean,
        apkReadable: (File) -> Boolean
    ): FileVerdict {
        if (!file.isFile) return FileVerdict.MISSING
        val len = file.length()
        if (len <= 0L) return FileVerdict.INCOMPLETE
        if (expectedSize > 0 && len != expectedSize) return FileVerdict.INCOMPLETE
        if (isApk && !apkReadable(file)) return FileVerdict.INVALID
        return FileVerdict.OK
    }
}

/** True only for assets that go through Android's package installer. ZIPs etc. are never "installed". */
val GitHubAsset.isInstallable: Boolean get() = isApk
