package com.appsventory.digiapps.nemo.util

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode { DARK, LIGHT, AUTO }

/** Brand color presets. Hex values live in res/values/colors.xml (brand_*), SYSTEM = wallpaper color (Android 12+). */
enum class BrandColor { CYAN, BLUE, GREEN, ORANGE, PINK, PURPLE, RED, SYSTEM }

/**
 * User settings. Values are Compose state, so changing one (e.g. theme) updates every
 * open screen immediately; each change is written to SharedPreferences.
 */
object AppSettings {
    private const val FILE = "digifiles_prefs"
    private lateinit var prefs: SharedPreferences

    @set:JvmName("setThemeModeProperty")
    var themeMode by mutableStateOf(ThemeMode.DARK)
        private set
    @set:JvmName("setBrandProperty")
    var brand by mutableStateOf(BrandColor.CYAN)
        private set
    @set:JvmName("setDefaultGridProperty")
    var defaultGrid by mutableStateOf(true)
        private set
    @set:JvmName("setShowHiddenProperty")
    var showHidden by mutableStateOf(false)
        private set

    /** Days before trashed items are deleted for good; 0 = keep until emptied. */
    @set:JvmName("setTrashRetentionDaysProperty")
    var trashRetentionDays by mutableIntStateOf(0)
        private set

    /**
     * Which non-internal sources get scanned for Gallery/Music/Movies: SD card and USB paths
     * as-is, saved SMB servers as "smb:<id>". Internal storage is always scanned (MediaStore
     * already indexes it) and isn't part of this set.
     */
    @set:JvmName("setEnabledMediaSourcesProperty")
    var enabledMediaSources by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Off by default: downloading a preview costs bandwidth/time per photo. Photos only -
     *  downloading a whole video just to grab one frame isn't a good trade even opted in. */
    @set:JvmName("setSmbThumbnailsProperty")
    var smbThumbnails by mutableStateOf(false)
        private set

    /** After a successful "extract": move the archive to Trash (so a mistake is recoverable). Off by default. */
    @set:JvmName("setDeleteArchiveAfterExtractProperty")
    var deleteArchiveAfterExtract by mutableStateOf(false)
        private set

    /** When true, the in-app video player advances to the next playlist item when one ends. */
    @set:JvmName("setVideoAutoNextProperty")
    var videoAutoNext by mutableStateOf(false)
        private set

    fun ensureLoaded(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        themeMode = enumOr(prefs.getString("theme_mode", null), ThemeMode.DARK)
        brand = enumOr(prefs.getString("brand", null), BrandColor.CYAN)
        defaultGrid = prefs.getBoolean("default_grid", true)
        showHidden = prefs.getBoolean("show_hidden", false)
        trashRetentionDays = prefs.getInt("trash_days", 0)
        enabledMediaSources = prefs.getStringSet("media_sources", emptySet()).orEmpty()
        smbThumbnails = prefs.getBoolean("smb_thumbnails", false)
        deleteArchiveAfterExtract = prefs.getBoolean("delete_archive_after_extract", false)
        videoAutoNext = prefs.getBoolean("video_auto_next", false)
    }

    fun setMediaSourceEnabled(id: String, enabled: Boolean) {
        enabledMediaSources = if (enabled) enabledMediaSources + id else enabledMediaSources - id
        prefs.edit().putStringSet("media_sources", enabledMediaSources).apply()
    }

    fun setSmbThumbnails(value: Boolean) {
        smbThumbnails = value
        prefs.edit().putBoolean("smb_thumbnails", value).apply()
    }

    fun setThemeMode(value: ThemeMode) {
        themeMode = value
        prefs.edit().putString("theme_mode", value.name).apply()
    }

    fun setBrand(value: BrandColor) {
        brand = value
        prefs.edit().putString("brand", value.name).apply()
    }

    fun setDefaultGrid(value: Boolean) {
        defaultGrid = value
        prefs.edit().putBoolean("default_grid", value).apply()
    }

    fun setDeleteArchiveAfterExtract(value: Boolean) {
        deleteArchiveAfterExtract = value
        prefs.edit().putBoolean("delete_archive_after_extract", value).apply()
    }

    fun setVideoAutoNext(value: Boolean) {
        videoAutoNext = value
        prefs.edit().putBoolean("video_auto_next", value).apply()
    }

    fun setShowHidden(value: Boolean) {
        showHidden = value
        prefs.edit().putBoolean("show_hidden", value).apply()
    }

    fun setTrashRetentionDays(value: Int) {
        trashRetentionDays = value
        prefs.edit().putInt("trash_days", value).apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}
