package com.appsventory.digiapps.nemo.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

enum class VolumeKind { INTERNAL, SD, USB }

data class DeviceVolume(
    val path: String,
    val label: String,
    val kind: VolumeKind,
    /** Filesystem as mounted by the OS (e.g. "ext4", "f2fs", "exFAT"), when it can be read; null if unknown. */
    val fsType: String? = null
)

/**
 * Mounted local volumes: internal storage first, then SD cards / USB OTG drives.
 * Falls back to internal only if the platform can't list volumes.
 */
fun detectVolumes(context: Context, internalLabel: String): List<DeviceVolume> {
    val internalPath = Environment.getExternalStorageDirectory().absolutePath
    val result = mutableListOf(DeviceVolume(internalPath, internalLabel, VolumeKind.INTERNAL))

    val sm = context.getSystemService(StorageManager::class.java) ?: return result
    val volumes = runCatching { sm.storageVolumes }.getOrNull().orEmpty()
    for (volume in volumes) {
        if (volume.isPrimary) continue
        if (volume.state != Environment.MEDIA_MOUNTED && volume.state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
        val path = volumePath(volume) ?: continue
        if (result.any { it.path == path } || !File(path).exists()) continue

        val description = runCatching { volume.getDescription(context) }.getOrNull().orEmpty()
        val kind = if (description.contains("sd", ignoreCase = true) &&
            !description.contains("usb", ignoreCase = true)
        ) VolumeKind.SD else VolumeKind.USB
        result += DeviceVolume(path, description.ifBlank { File(path).name }, kind, volumeFsType(path))
    }
    return result
}

/**
 * Cheap fingerprint of every volume + its state. It changes when a drive is plugged in, mounted,
 * ejected or pulled out, so it can be polled to catch changes the system never broadcasts.
 */
fun volumesSignature(context: Context): String {
    val sm = context.getSystemService(StorageManager::class.java) ?: return ""
    return runCatching {
        sm.storageVolumes.joinToString("|") { "${volumePath(it)}:${it.state}" }
    }.getOrDefault("")
}

internal fun volumePath(volume: StorageVolume): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return volume.directory?.absolutePath
    // Before API 30 the path getter is hidden but reflectable.
    return runCatching {
        StorageVolume::class.java.getMethod("getPath").invoke(volume) as? String
    }.getOrNull()
}

/**
 * Best-effort filesystem name of a removable volume, read from /proc/mounts.
 * The OS (vold) does the actual mounting; this only reports what it mounted, so ext4 / f2fs
 * show up here only when the firmware supports them. Returns null when it can't be determined.
 */
fun volumeFsType(path: String): String? = runCatching {
    val name = File(path).name
    var found: String? = null
    File("/proc/mounts").forEachLine { line ->
        val parts = line.split(' ')
        if (parts.size < 3) return@forEachLine
        val mountPoint = parts[1]
        val type = parts[2]
        // the emulation layers on top (fuse / sdcardfs) hide the real type; skip them
        if (type == "fuse" || type == "sdcardfs" || type == "esdfs") return@forEachLine
        if (mountPoint == path || mountPoint.endsWith("/$name")) found = type
    }
    found?.let(::prettyFsName)
}.getOrNull()

private fun prettyFsName(raw: String): String = when (raw.lowercase()) {
    "vfat", "msdos" -> "FAT32"
    "exfat", "texfat" -> "exFAT"
    "ntfs", "ntfs3", "fuseblk" -> "NTFS"
    "ext4" -> "ext4"
    "ext3" -> "ext3"
    "ext2" -> "ext2"
    "f2fs" -> "F2FS"
    else -> raw
}
