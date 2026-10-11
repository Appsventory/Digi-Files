package com.appsventory.digiapps.nemo.util

import android.content.Context

/** Setting sederhana yang tersimpan antar sesi. */
object AppPrefs {
    private const val FILE = "digifiles_prefs"
    private const val KEY_HIDDEN_TOOLS = "hidden_tools"
    private const val KEY_PERMISSIONS_ASKED = "permissions_asked"

    /** Names of Home tools the user removed via Edit. */
    fun hiddenTools(context: Context): Set<String> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet(KEY_HIDDEN_TOOLS, emptySet()).orEmpty()

    /** Has the first-launch permission flow already run (whatever the answers were)? */
    fun permissionsAsked(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_PERMISSIONS_ASKED, false)

    fun setPermissionsAsked(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_PERMISSIONS_ASKED, true).apply()
    }

    fun setHiddenTools(context: Context, value: Set<String>) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putStringSet(KEY_HIDDEN_TOOLS, value).apply()
    }
}
