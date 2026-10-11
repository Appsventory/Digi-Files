package com.appsventory.digiapps.nemo

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import com.appsventory.digiapps.nemo.ui.SideShortcut

/** Single place that launches other screens / system settings. */
object AppNavigator {

    /** Large screens / TV: Pictures / Music / Movies open their own activity (phones use in-shell pages). */
    fun openMedia(context: Context, shortcut: SideShortcut) {
        val target = when (shortcut) {
            SideShortcut.PICTURES -> PicturesActivity::class.java
            SideShortcut.MUSIC -> MusicActivity::class.java
            SideShortcut.MOVIES -> MoviesActivity::class.java
            else -> return
        }
        context.startActivity(Intent(context, target))
    }

    /** MainActivity extra: open the file browser at this folder. */
    const val EXTRA_OPEN_FOLDER = "com.appsventory.digiapps.nemo.OPEN_FOLDER"

    /** "Show in folder" from a standalone screen: back to the file browser (reusing it) at [folder]. */
    fun showInFolder(context: Context, folder: String) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_FOLDER, folder)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    fun openAppSettings(context: Context) {
        context.startActivity(Intent(context, SettingsActivity::class.java))
    }

    fun openUpdateCheck(context: Context) {
        context.startActivity(Intent(context, UpdateActivity::class.java))
    }

    fun openInstalledApps(context: Context) {
        context.startActivity(Intent(context, AppsActivity::class.java))
    }

    fun openSystemSettings(context: Context) =
        startFirst(context, Settings.ACTION_SETTINGS)

    private const val GMS = "com.google.android.gms"

    /**
     * Opens Quick Share (Google's, from Google Play services; Samsung phones also have their own).
     * Google doesn't document an activity for it and the screens moved between Android versions, so
     * these are tried in order until one opens:
     *  1. GMS .nearby.sharing.main.MainActivity - the Quick Share screen on Android 11 (checked on a real device)
     *  2. the "receive" action GMS answers to: it is an action name, so it survives activity renames
     *  3. the receive screen's alias activity
     *  4. Samsung's own Quick Share app
     * @return false when none of them exists on this device (no Google services, most TV boxes)
     */
    fun openQuickShare(context: Context): Boolean {
        val candidates = buildList {
            add(Intent().setComponent(ComponentName(GMS, "com.google.android.gms.nearby.sharing.main.MainActivity")))
            add(Intent("com.google.android.gms.RECEIVE_NEARBY").setPackage(GMS))
            add(Intent().setComponent(ComponentName(GMS, "com.google.android.gms.nearby.sharing.receive.ReceiveActivityQrCodeAlias")))
            context.packageManager.getLaunchIntentForPackage("com.samsung.android.app.sharelive")?.let { add(it) }
        }
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
                // missing or disabled on this device: next one
            } catch (_: SecurityException) {
                // exists but isn't exported to other apps (newer versions): next one
            }
        }
        return false
    }

    /** Storage settings (where the system lets the user eject a drive), falling back to general settings. */
    fun openStorageSettings(context: Context) =
        startFirst(
            context,
            Settings.ACTION_MEMORY_CARD_SETTINGS,
            Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            Settings.ACTION_SETTINGS
        )

    private fun startFirst(context: Context, vararg actions: String) {
        for (action in actions) {
            try {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // try next
            }
        }
        Toast.makeText(context, R.string.toast_settings_unavailable, Toast.LENGTH_SHORT).show()
    }
}
