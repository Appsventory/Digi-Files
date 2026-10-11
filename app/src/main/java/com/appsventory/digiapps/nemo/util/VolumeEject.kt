package com.appsventory.digiapps.nemo.util

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import kotlinx.coroutines.delay
import java.io.File

/**
 * Unmounts ("ejects") a removable volume so the drive can be pulled safely.
 *
 * Android only lets the system (or a privileged / platform-signed app) unmount a volume:
 * the hidden StorageManager.unmount() needs MOUNT_UNMOUNT_FILESYSTEMS. So this tries it first
 * and, when the platform refuses, reports [Result.NEEDS_SYSTEM] so the caller can open the
 * system storage settings where the user can eject it.
 */
object VolumeEject {

    enum class Result {
        /** The volume is no longer mounted: safe to remove. */
        EJECTED,
        /** This app isn't allowed to unmount; the user has to do it in system settings. */
        NEEDS_SYSTEM
    }

    suspend fun eject(context: Context, path: String): Result {
        val sm = context.getSystemService(StorageManager::class.java) ?: return Result.NEEDS_SYSTEM
        val volume = runCatching { sm.getStorageVolume(File(path)) }.getOrNull() ?: return Result.NEEDS_SYSTEM
        if (volume.isPrimary) return Result.NEEDS_SYSTEM

        val id = runCatching {
            StorageVolume::class.java.getMethod("getId").invoke(volume) as? String
        }.getOrNull() ?: return Result.NEEDS_SYSTEM

        val requested = try {
            StorageManager::class.java.getMethod("unmount", String::class.java).invoke(sm, id)
            true
        } catch (_: Throwable) {
            // NoSuchMethod (hidden API blocked) or SecurityException (no privileged permission)
            false
        }
        if (!requested) return Result.NEEDS_SYSTEM

        // unmount is asynchronous: wait (briefly) until the volume really left the mounted state
        repeat(20) {
            val state = runCatching { sm.getStorageVolume(File(path))?.state }.getOrNull()
            if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY &&
                state != Environment.MEDIA_CHECKING
            ) return Result.EJECTED
            delay(250)
        }
        return Result.NEEDS_SYSTEM
    }
}
