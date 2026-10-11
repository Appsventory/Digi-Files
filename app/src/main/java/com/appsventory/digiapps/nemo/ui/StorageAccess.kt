package com.appsventory.digiapps.nemo.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.appsventory.digiapps.nemo.R
import com.appsventory.digiapps.nemo.ui.theme.CardDark
import com.appsventory.digiapps.nemo.ui.theme.CyanAccent
import com.appsventory.digiapps.nemo.ui.theme.TextPrimary
import com.appsventory.digiapps.nemo.ui.theme.TextSecondary
import com.appsventory.digiapps.nemo.util.AppPrefs
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.appsventory.digiapps.nemo.util.StoragePermission

class StorageAccess(val granted: Boolean, val request: () -> Unit)

/** Live storage-permission state (re-checked on resume, e.g. after the settings screen). */
@Composable
fun rememberStorageAccess(): StorageAccess {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(StoragePermission.hasFullAccess(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = StoragePermission.hasFullAccess(context)
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = StoragePermission.hasFullAccess(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return StorageAccess(granted) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) StoragePermission.openManageAllFilesSettings(context)
        else launcher.launch(StoragePermission.legacyPermissions())
    }
}

/** The app-wide player (kept alive by MusicService, not tied to this screen). */
@Composable
fun rememberMusicPlayer(): com.appsventory.digiapps.nemo.util.MusicPlayer {
    val context = LocalContext.current
    return remember { com.appsventory.digiapps.nemo.util.MusicPlayer.get(context) }
}

/**
 * Returns a function that asks for the notification permission (Android 13+) when it is missing.
 * Call it before starting music or a long copy so the progress / media notification can show.
 */
@Composable
fun rememberNotificationPermissionRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
}

private const val STEP_NOTIFICATIONS = 0
private const val STEP_STORAGE = 1
private const val STEP_DONE = 2

/**
 * The permission popups an app normally shows when it is opened for the first time:
 *  1. notifications (Android 13+): the system popup, so copy / music progress can show in the shade
 *  2. files: Android 11+ has no popup for "All files access" - it is a settings switch - so a short
 *     explanation first, then the settings page; below Android 11 it is the normal system popup
 * Runs once (whatever the answers were); the file browser still explains again if files stay blocked.
 */
@Composable
fun FirstRunPermissions() {
    val context = LocalContext.current
    val storage = rememberStorageAccess()
    var step by androidx.compose.runtime.saveable.rememberSaveable {
        mutableIntStateOf(if (AppPrefs.permissionsAsked(context)) STEP_DONE else STEP_NOTIFICATIONS)
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        step = STEP_STORAGE // granted or not: on to the next question
    }

    LaunchedEffect(step, storage.granted) {
        when (step) {
            STEP_NOTIFICATIONS -> {
                val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (needed) notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) else step = STEP_STORAGE
            }
            STEP_STORAGE -> if (storage.granted) step = STEP_DONE
            STEP_DONE -> AppPrefs.setPermissionsAsked(context)
        }
    }

    if (step == STEP_STORAGE && !storage.granted) {
        AlertDialog(
            onDismissRequest = { step = STEP_DONE },
            containerColor = CardDark,
            title = { Text(stringResource(R.string.permission_first_run_title), color = TextPrimary) },
            text = { Text(stringResource(R.string.permission_first_run_body), color = TextSecondary, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    step = STEP_DONE
                    storage.request()
                }) { Text(stringResource(R.string.permission_first_run_allow), color = CyanAccent) }
            },
            dismissButton = {
                TextButton(onClick = { step = STEP_DONE }) {
                    Text(stringResource(R.string.permission_first_run_later), color = TextSecondary)
                }
            }
        )
    }
}
