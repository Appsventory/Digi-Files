package com.appsventory.digiapps.nemo

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.webkit.MimeTypeMap
import android.widget.Toast
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.appsventory.digiapps.nemo.util.ArchivePasswordCache
import com.appsventory.digiapps.nemo.util.volumesSignature
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.appsventory.digiapps.nemo.ui.*
import com.appsventory.digiapps.nemo.ui.theme.setDigiContent
import com.appsventory.digiapps.nemo.ui.UpdatePage
import com.appsventory.digiapps.nemo.util.AppSettings
import com.appsventory.digiapps.nemo.util.FileClipboard
import com.appsventory.digiapps.nemo.util.StoragePermission
import com.appsventory.digiapps.nemo.util.TrashCleaner
import com.appsventory.digiapps.nemo.util.ExtraMediaCache
import com.appsventory.digiapps.nemo.util.Vfs
import com.appsventory.digiapps.nemo.util.storageRoots
import com.appsventory.digiapps.nemo.smb.SmbClient
import com.appsventory.digiapps.nemo.smb.SmbConnection
import com.appsventory.digiapps.nemo.smb.SmbStore
import com.appsventory.digiapps.nemo.smb.smbHostOf
import com.appsventory.digiapps.nemo.util.isSameOrChild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import com.appsventory.digiapps.nemo.util.VolumeEject
import com.appsventory.digiapps.nemo.util.FileTasks
import com.appsventory.digiapps.nemo.util.VolumeKind
import com.appsventory.digiapps.nemo.util.detectVolumes
import com.appsventory.digiapps.nemo.util.freeLabel
import com.appsventory.digiapps.nemo.util.isTelevision
import com.appsventory.digiapps.nemo.util.isSameOrChild
import java.io.File

class MainActivity : ComponentActivity() {

    // "Show in folder" requests from the Pictures / Music / Movies screens (also arrives while already running)
    private var openFolderRequest by mutableStateOf<String?>(null)

    override fun onDestroy() {
        // leaving the app for good: remembered archive passwords go with it
        if (isFinishing) ArchivePasswordCache.clear()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(AppNavigator.EXTRA_OPEN_FOLDER)?.let { openFolderRequest = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intentInfo = parseLaunchIntent(intent)
        openFolderRequest = intent?.getStringExtra(AppNavigator.EXTRA_OPEN_FOLDER)

        setDigiContent {
            DigiFilesApp(
                initialPath = intentInfo.startPath,
                isPickMode = intentInfo.isPickMode,
                mimeFilter = intentInfo.mimeFilter,
                startOnFiles = intentInfo.isPickMode || intentInfo.fromView,
                onFilePicked = { path -> returnPickedFile(path) },
                openFolderRequest = openFolderRequest,
                onOpenFolderHandled = { openFolderRequest = null }
            )
        }
    }

    private data class LaunchInfo(
        val startPath: String,
        val isPickMode: Boolean,
        val mimeFilter: String?,
        val fromView: Boolean
    )

    private fun parseLaunchIntent(intent: Intent?): LaunchInfo {
        val root = Environment.getExternalStorageDirectory().absolutePath
        if (intent == null) return LaunchInfo(root, false, null, false)

        val action = intent.action
        val isPick = action == Intent.ACTION_GET_CONTENT ||
            action == Intent.ACTION_PICK ||
            action == Intent.ACTION_OPEN_DOCUMENT

        val rawMime = intent.type?.takeIf { it.isNotBlank() }
        val mime = normalizeMime(rawMime)

        var start: String? = null
        var fromView = false
        val data: Uri? = intent.data
        if (data != null && (action == Intent.ACTION_VIEW || action == Intent.ACTION_MAIN)) {
            val path = uriToPath(data)
            if (path != null) {
                fromView = true
                val f = File(path)
                start = if (f.isDirectory) path else (f.parent ?: root)
            }
        }
        if (start == null) start = defaultPathForMime(mime, root)
        return LaunchInfo(start, isPick, mime, fromView)
    }

    private fun defaultPathForMime(mime: String?, root: String): String {
        val type = mime?.substringBefore("/")?.lowercase() ?: ""
        val candidate = when {
            type == "image" || type == "images" ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            type == "video" || type == "videos" ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            type == "audio" || type == "audios" || type == "music" ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            else ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }
        return if (candidate.exists()) candidate.absolutePath else root
    }

    private fun normalizeMime(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "*/*") return null
        val lower = raw.lowercase().trim()
        return when {
            lower.startsWith("images/") -> "image/" + lower.substringAfter("/")
            lower.startsWith("videos/") -> "video/" + lower.substringAfter("/")
            lower.startsWith("audios/") -> "audio/" + lower.substringAfter("/")
            lower == "images/*" -> "image/*"
            lower == "videos/*" -> "video/*"
            lower == "audios/*" -> "audio/*"
            else -> lower
        }
    }

    private fun uriToPath(uri: Uri): String? {
        return when (uri.scheme) {
            "file" -> uri.path
            "content" -> {
                val p = uri.path ?: return null
                if (p.contains(":")) p.substringAfterLast(":") else null
            }
            else -> null
        }
    }

    private fun returnPickedFile(path: String) {
        val file = File(path)
        if (!file.exists() || file.isDirectory) {
            Toast.makeText(this, R.string.toast_invalid_file, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val result = Intent().apply {
                setDataAndType(uri, getMimeType(file) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_STREAM, uri)
            }
            setResult(Activity.RESULT_OK, result)
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_share_failed, e.message.orEmpty()), Toast.LENGTH_SHORT).show()
        }
    }

    private fun getMimeType(file: File): String? {
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }
}

@Composable
fun DigiFilesApp(
    initialPath: String,
    isPickMode: Boolean,
    mimeFilter: String?,
    startOnFiles: Boolean,
    onFilePicked: (String) -> Unit,
    openFolderRequest: String? = null,
    onOpenFolderHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    // first launch: the notification popup, then "allow access to your files" (skipped while another app is picking a file)
    if (!isPickMode) FirstRunPermissions()
    val rootPath = remember {
        Environment.getExternalStorageDirectory().absolutePath
    }

    val isWide = LocalConfiguration.current.screenWidthDp >= 700
    val isTv = remember { context.isTelevision() }
    // copy / move with progress + undo; app-wide, so it keeps running (in a foreground service) when the app is closed
    val tasks = remember { FileTasks.get(context) }
    // one player for the whole shell: music keeps playing when switching tabs
    val musicPlayer = rememberMusicPlayer()

    // ---- Volumes: internal + SD/USB (updates on plug/unplug) + SMB servers found on the LAN
    val internalTitle = stringResource(R.string.storage_internal)
    var volumeTick by remember { mutableIntStateOf(0) }
    // No background network scanning here on purpose: recent Android versions show a
    // system "connect to a device on your local network?" prompt for any always-on mDNS
    // scan, which would otherwise pop up every time the app opens. Scanning for SMB
    // servers now only happens on demand, from the "Add network location" dialog itself.
    // Drives can be plugged at any moment, so listen to every signal the system gives, and also poll
    // (cheap): some firmwares never send the MEDIA_* broadcasts to apps.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val main = Handler(Looper.getMainLooper())
        var last = volumesSignature(context)
        fun recheck(force: Boolean = false) {
            val now = volumesSignature(context)
            if (force || now != last) {
                last = now
                volumeTick++
            }
        }
        val mediaReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                recheck(force = true)
                main.postDelayed({ recheck() }, 1500) // the volume list settles a moment after the broadcast
            }
        }
        val mediaFilter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_CHECKING)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(context, mediaReceiver, mediaFilter, ContextCompat.RECEIVER_EXPORTED)

        // A USB device being plugged is seen at once, but its volume mounts a few seconds later
        val usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                listOf(1000L, 3000L, 6000L, 10000L).forEach { ms -> main.postDelayed({ recheck() }, ms) }
            }
        }
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, usbReceiver, usbFilter, ContextCompat.RECEIVER_EXPORTED)

        // Android 12+: the platform tells us directly when a volume changes state
        val sm = context.getSystemService(StorageManager::class.java)
        val volumeCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && sm != null) {
            object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) {
                    recheck()
                }
            }.also { sm.registerStorageVolumeCallback(ContextCompat.getMainExecutor(context), it) }
        } else null

        val poll = object : Runnable {
            override fun run() {
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) recheck()
                main.postDelayed(this, 2000)
            }
        }
        main.postDelayed(poll, 2000)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) recheck() // plugged while the app was in the background
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            main.removeCallbacksAndMessages(null)
            runCatching { context.unregisterReceiver(mediaReceiver) }
            runCatching { context.unregisterReceiver(usbReceiver) }
            if (volumeCallback != null && sm != null) {
                runCatching { sm.unregisterStorageVolumeCallback(volumeCallback) }
            }
        }
    }
    LaunchedEffect(Unit) {
        SmbStore.ensureLoaded(context)
        SmbClient.init(context)
    }
    var smbDialog by remember { mutableStateOf<SmbDialogState?>(null) }
    // SD / USB drive waiting for "Eject?" confirmation
    var ejectTarget by remember { mutableStateOf<ExploreVolume?>(null) }
    var ejecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val volumes = remember(volumeTick, internalTitle, SmbStore.connections, tasks.version) {
        val local = detectVolumes(context, internalTitle).map { v ->
            ExploreVolume(
                id = if (v.kind == VolumeKind.INTERNAL) "internal" else v.path,
                title = v.label,
                subtitle = freeSpaceLabel(context, File(v.path)),
                path = v.path,
                icon = when (v.kind) {
                    VolumeKind.INTERNAL -> Icons.Default.PhoneAndroid
                    VolumeKind.SD -> Icons.Default.SdCard
                    VolumeKind.USB -> Icons.Default.Usb
                },
                removable = v.kind != VolumeKind.INTERNAL,
                fsType = v.fsType
            )
        }
        val saved = SmbStore.connections.map { c ->
            ExploreVolume(id = "smb:${c.id}", title = c.title, subtitle = c.root, path = c.root, icon = Icons.Default.Storage)
        }
        local + saved
    }

    var showHome by remember { mutableStateOf(!startOnFiles && !isPickMode) }
    var currentPath by remember { mutableStateOf(initialPath) }
    var lastBackTime by remember { mutableStateOf(0L) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }
    // DIRECTORY = real folder; TRASH / APKS = virtual lists scanned from storage
    var browseMode by remember { mutableStateOf(BrowseMode.DIRECTORY) }
    // Phone only: Pictures / Music / Movies are pages inside the shell (bottom bar stays)
    var mediaPage by remember { mutableStateOf<SideShortcut?>(null) }
    // Large screens / TV: Settings and User Apps are pages inside the shell (sidebar stays); phones use their own activity
    var extraPage by remember { mutableStateOf<ExtraPage?>(null) }
    // Copy / Move waiting for "Paste here" (survives navigating between folders)
    var clipboard by remember { mutableStateOf<FileClipboard?>(null) }

    // Active sidebar items are derived from the current path, so several can be active at once:
    // e.g. /storage/emulated/0/Download -> "Internal Storage" AND "Download".
    val browsing = !showHome && extraPage == null && mediaPage == null && browseMode == BrowseMode.DIRECTORY
    val activeVolume = if (browsing) {
        volumes
            .filter { isSameOrChild(currentPath, it.path) }
            .maxByOrNull { it.path.length }
    } else null
    val storageRoot = activeVolume?.path ?: rootPath
    val activeShortcut = when {
        !showHome && extraPage == null && mediaPage == null && browseMode == BrowseMode.APKS -> SideShortcut.APPLICATIONS
        browsing -> SideShortcut.entries
            .filter { it != SideShortcut.APPLICATIONS }
            .firstOrNull { isSameOrChild(currentPath, shortcutDir(it, rootPath)) }
        else -> null
    }
    val selectedTab = when {
        mediaPage != null -> ShellTab.of(mediaPage)
        activeShortcut == SideShortcut.DOWNLOAD -> ShellTab.DOWNLOAD
        else -> ShellTab.HOME
    }

    fun openPath(path: String) {
        if (path.startsWith("smb://")) {
            val hostKey = smbHostOf(path)
            if (SmbStore.find(hostKey) == null) {
                smbDialog = SmbDialogState.Connect(host = hostKey, name = "", target = path)
                return
            }
        }
        extraPage = null
        showHome = false
        mediaPage = null
        browseMode = BrowseMode.DIRECTORY
        currentPath = path
    }

    // "Show in folder" from another screen: jump to that folder in the file browser
    LaunchedEffect(openFolderRequest) {
        openFolderRequest?.let {
            openPath(it)
            onOpenFolderHandled()
        }
    }

    fun onVolumeLongClick(volume: ExploreVolume) {
        if (volume.removable) {
            ejectTarget = volume
            return
        }
        if (!volume.path.startsWith("smb://")) return
        val hostKey = smbHostOf(volume.path)
        if (SmbStore.find(hostKey) != null) {
            smbDialog = SmbDialogState.Options(hostKey, volume.title)
        }
    }

    fun openVirtual(mode: BrowseMode) {
        extraPage = null
        showHome = false
        mediaPage = null
        browseMode = mode
        currentPath = rootPath
    }

    fun openShortcut(sc: SideShortcut) {
        if (sc == SideShortcut.APPLICATIONS) openVirtual(BrowseMode.APKS)
        else openPath(shortcutDir(sc, rootPath))
    }

    fun goHome() {
        extraPage = null
        showHome = true
        mediaPage = null
        browseMode = BrowseMode.DIRECTORY
        currentPath = rootPath
    }

    // The drive being browsed was pulled out: leave its (now empty) folders instead of showing a dead screen
    LaunchedEffect(volumes) {
        val p = currentPath
        if (!showHome && browseMode == BrowseMode.DIRECTORY && p.startsWith("/storage/") &&
            volumes.none { isSameOrChild(p, it.path) } && !File(p).exists()
        ) {
            goHome()
            Toast.makeText(context, R.string.toast_volume_removed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Phone: an in-shell page. Large screens / TV: its own activity. */
    fun openMedia(sc: SideShortcut) {
        extraPage = null
        if (isWide) AppNavigator.openMedia(context, sc) else mediaPage = sc
    }

    fun openExtra(page: ExtraPage) {
        if (isWide) {
            extraPage = page
            showHome = false
            mediaPage = null
        } else if (page == ExtraPage.SETTINGS) AppNavigator.openAppSettings(context)
        else AppNavigator.openInstalledApps(context)
    }

    // apply the "empty Trash automatically" setting (needs storage access)
    val retentionDays = AppSettings.trashRetentionDays
    LaunchedEffect(retentionDays, volumes) {
        if (retentionDays > 0 && StoragePermission.hasFullAccess(context)) {
            withContext(Dispatchers.IO) {
                TrashCleaner.purge(storageRoots(context, internalTitle), retentionDays)
            }
        }
    }

    fun onTab(tab: ShellTab) {
        when (tab) {
            ShellTab.HOME -> goHome()
            ShellTab.DOWNLOAD -> openShortcut(SideShortcut.DOWNLOAD)
            else -> tab.shortcut?.let { openMedia(it) }
        }
    }

    // A volume was unplugged while browsing it -> go home.
    LaunchedEffect(volumes) {
        if (browsing && !File(currentPath).exists()) goHome()
    }

    BackHandler {
        when {
            showCreateFolder -> showCreateFolder = false
            extraPage != null -> goHome()
            mediaPage != null -> goHome()
            !showHome && browseMode != BrowseMode.DIRECTORY -> goHome()
            !showHome && currentPath != storageRoot -> {
                val parent = Vfs.parent(currentPath) // handles both local paths and smb:// paths
                if (parent != null && isSameOrChild(parent, storageRoot)) {
                    currentPath = parent
                } else {
                    goHome()
                }
            }
            !showHome && !isPickMode -> goHome()
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackTime < 2000L) {
                    val act = context as? ComponentActivity
                    if (act != null) {
                        if (isPickMode) act.setResult(Activity.RESULT_CANCELED)
                        act.finish()
                    }
                } else {
                    lastBackTime = now
                    val msg = if (isPickMode) R.string.toast_press_again_cancel else R.string.toast_press_again_exit
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val page = when {
        extraPage == ExtraPage.SETTINGS -> ShellPage.SETTINGS
        extraPage == ExtraPage.APPS -> ShellPage.APPS
        extraPage == ExtraPage.UPDATE -> ShellPage.UPDATE
        mediaPage == SideShortcut.PICTURES -> ShellPage.PICTURES
        mediaPage == SideShortcut.MUSIC -> ShellPage.MUSIC
        mediaPage == SideShortcut.MOVIES -> ShellPage.MOVIES
        showHome && !isPickMode -> ShellPage.HOME
        else -> ShellPage.FILES
    }

    ResponsiveShell(
        wide = isWide,
        selectedShortcut = activeShortcut,
        selectedVolumeId = activeVolume?.id,
        volumes = volumes,
        selectedTab = selectedTab,
        onTab = { onTab(it) },
        onShortcut = { openShortcut(it) },
        onVolume = { openPath(it.path) },
        onVolumeLongClick = { onVolumeLongClick(it) }
    ) {
        // Only the page slides (direction follows tab order); the shell stays put.
        Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            targetState = page,
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(tween(300)) { it * dir } + fadeIn(tween(300))) togetherWith
                    (slideOutHorizontally(tween(300)) { -it * dir / 3 } + fadeOut(tween(200)))
            },
            label = "shellPage"
        ) { current ->
            when (current) {
                ShellPage.HOME -> HomeScreen(
                    volumes = volumes,
                    onOpenPath = { openPath(it) },
                    onVolumeLongClick = { onVolumeLongClick(it) },
                    isTv = isTv,
                    onTool = { action ->
                        when (action) {
                            ToolAction.TRASH -> openVirtual(BrowseMode.TRASH)
                            ToolAction.PICTURES -> openMedia(SideShortcut.PICTURES)
                            ToolAction.MUSIC -> openMedia(SideShortcut.MUSIC)
                            ToolAction.MOVIES -> openMedia(SideShortcut.MOVIES)
                            ToolAction.USER_APPS -> openExtra(ExtraPage.APPS)
                            ToolAction.SYSTEM_SETTINGS -> AppNavigator.openSystemSettings(context)
                            ToolAction.QUICK_SHARE -> if (!AppNavigator.openQuickShare(context)) {
                                Toast.makeText(context, R.string.toast_quick_share_unavailable, Toast.LENGTH_LONG).show()
                            }
                            ToolAction.SETTINGS -> openExtra(ExtraPage.SETTINGS)
                            ToolAction.EDIT -> Unit // handled inside HomeScreen
                            ToolAction.ADD_STORAGE -> smbDialog = SmbDialogState.Add
                        }
                    }
                )
                ShellPage.FILES -> key(refreshKey) {
                    FileBrowserScreen(
                        currentPath = currentPath,
                        rootLabel = activeVolume?.title ?: internalTitle,
                        onPathChange = { openPath(it) },
                        onGoHome = { goHome() },
                        mode = browseMode,
                        isPickMode = isPickMode,
                        mimeFilter = mimeFilter,
                        onFilePicked = onFilePicked,
                        onCreateFolder = { showCreateFolder = true },
                        storageRoot = storageRoot,
                        clipboard = clipboard,
                        onClipboardChange = { clipboard = it },
                        isTv = isTv,
                        tasks = tasks,
                        onEditSmb = { path ->
                            val key = smbHostOf(path)
                            SmbStore.find(key)?.let { smbDialog = SmbDialogState.Edit(it) }
                        },
                        onForgetSmb = { path -> smbDialog = SmbDialogState.ConfirmForget(smbHostOf(path)) },
                        onEjectVolume = activeVolume?.takeIf { it.removable }?.let { vol -> { ejectTarget = vol } },
                        volumesVersion = volumeTick
                    )
                }
                ShellPage.SETTINGS -> SettingsPage(onOpenUpdate = { extraPage = ExtraPage.UPDATE })
                ShellPage.UPDATE -> UpdatePage(onBack = { extraPage = ExtraPage.SETTINGS })
                ShellPage.APPS -> AppsPage()
                ShellPage.PICTURES -> MediaPage(SideShortcut.PICTURES, musicPlayer, onShowInFolder = { openPath(it) })
                ShellPage.MUSIC -> MediaPage(SideShortcut.MUSIC, musicPlayer, onShowInFolder = { openPath(it) })
                ShellPage.MOVIES -> MediaPage(SideShortcut.MOVIES, musicPlayer, onShowInFolder = { openPath(it) })
            }
        }
        TaskBanner(tasks, Modifier.align(Alignment.BottomCenter))
        }
    }

    smbDialog?.let { state ->
        when (state) {
            is SmbDialogState.Add -> SmbConnectionDialog(
                initial = null, prefillHost = "", prefillName = "",
                onConnected = { c ->
                    SmbStore.upsert(c); ExtraMediaCache.invalidate()
                    smbDialog = null
                    openPath(c.root)
                },
                onDismiss = { smbDialog = null }
            )
            is SmbDialogState.Connect -> SmbConnectionDialog(
                initial = null, prefillHost = state.host, prefillName = state.name,
                onConnected = { c ->
                    SmbStore.upsert(c); ExtraMediaCache.invalidate()
                    smbDialog = null
                    openPath(state.target)
                },
                onDismiss = { smbDialog = null }
            )
            is SmbDialogState.Edit -> SmbConnectionDialog(
                initial = state.connection, prefillHost = state.connection.host, prefillName = state.connection.name,
                onConnected = { c ->
                    com.appsventory.digiapps.nemo.smb.SmbClient.invalidate(c.id)
                    SmbStore.upsert(c); ExtraMediaCache.invalidate()
                    smbDialog = null
                },
                onDismiss = { smbDialog = null }
            )
            is SmbDialogState.Options -> AlertDialog(
                onDismissRequest = { smbDialog = null },
                title = { Text(state.label) },
                text = {},
                confirmButton = {
                    TextButton(onClick = {
                        SmbStore.find(state.hostKey)?.let { smbDialog = SmbDialogState.Edit(it) }
                    }) { Text(stringResource(R.string.smb_edit)) }
                },
                dismissButton = {
                    TextButton(onClick = { smbDialog = SmbDialogState.ConfirmForget(state.hostKey) }) {
                        Text(stringResource(R.string.smb_forget))
                    }
                }
            )
            is SmbDialogState.ConfirmForget -> ForgetSmbDialog(
                onConfirm = {
                    com.appsventory.digiapps.nemo.smb.SmbClient.invalidate(state.hostKey)
                    SmbStore.remove(state.hostKey); ExtraMediaCache.invalidate()
                    smbDialog = null
                    if (isSameOrChild(currentPath, "smb://${state.hostKey}/")) goHome()
                },
                onDismiss = { smbDialog = null }
            )
        }
    }

    ejectTarget?.let { vol ->
        AlertDialog(
            onDismissRequest = { if (!ejecting) ejectTarget = null },
            title = { Text(stringResource(R.string.eject_title, vol.title)) },
            text = {
                Text(
                    stringResource(if (ejecting) R.string.eject_working else R.string.eject_body)
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !ejecting,
                    onClick = {
                        if (tasks.isBusy) {
                            Toast.makeText(context, R.string.eject_busy, Toast.LENGTH_LONG).show()
                            ejectTarget = null
                            return@TextButton
                        }
                        ejecting = true
                        // release anything still reading from this drive before unmounting
                        val playing = musicPlayer.current?.path
                        if (playing != null && isSameOrChild(playing, vol.path)) musicPlayer.stopAndClear()
                        ExtraMediaCache.invalidate()
                        if (!showHome && isSameOrChild(currentPath, vol.path)) goHome()
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { VolumeEject.eject(context, vol.path) }
                            ejecting = false
                            ejectTarget = null
                            volumeTick++
                            when (result) {
                                VolumeEject.Result.EJECTED -> Toast.makeText(
                                    context, context.getString(R.string.eject_done, vol.title), Toast.LENGTH_LONG
                                ).show()
                                VolumeEject.Result.NEEDS_SYSTEM -> {
                                    Toast.makeText(context, R.string.eject_use_system, Toast.LENGTH_LONG).show()
                                    AppNavigator.openStorageSettings(context)
                                }
                            }
                        }
                    }
                ) { Text(stringResource(R.string.action_eject)) }
            },
            dismissButton = {
                TextButton(enabled = !ejecting, onClick = { ejectTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showCreateFolder) {
        AlertDialog(
            onDismissRequest = { showCreateFolder = false },
            title = { Text(stringResource(R.string.create_folder)) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.folder_name)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newFolderName.trim()
                    if (name.isNotEmpty()) {
                        val dir = File(currentPath, name)
                        if (dir.mkdirs()) {
                            refreshKey++
                            Toast.makeText(context, R.string.toast_folder_created, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, R.string.toast_folder_create_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                    showCreateFolder = false
                }) { Text(stringResource(R.string.action_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolder = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

/** Pages of the shell, ordered like the phone tabs (drives the slide direction). */
private enum class ShellPage { HOME, FILES, PICTURES, MUSIC, MOVIES, SETTINGS, APPS, UPDATE }

/** SMB dialogs shown from the sidebar / Add Storage / file browser menu. */
private sealed class SmbDialogState {
    object Add : SmbDialogState()
    class Connect(val host: String, val name: String, val target: String) : SmbDialogState()
    class Edit(val connection: SmbConnection) : SmbDialogState()
    class Options(val hostKey: String, val label: String) : SmbDialogState()
    class ConfirmForget(val hostKey: String) : SmbDialogState()
}

/** Extra pages that open inside the shell on large screens. */
private enum class ExtraPage { SETTINGS, APPS, UPDATE }

private fun freeSpaceLabel(context: Context, dir: File): String = try {
    context.freeLabel(StatFs(dir.absolutePath).availableBytes)
} catch (_: Exception) {
    ""
}

private fun shortcutDir(sc: SideShortcut, root: String): String {
    val dir = when (sc) {
        SideShortcut.DOWNLOAD -> Environment.DIRECTORY_DOWNLOADS
        SideShortcut.PICTURES -> Environment.DIRECTORY_PICTURES
        SideShortcut.MUSIC -> Environment.DIRECTORY_MUSIC
        SideShortcut.MOVIES -> Environment.DIRECTORY_MOVIES
        SideShortcut.DOCUMENTS -> Environment.DIRECTORY_DOCUMENTS
        SideShortcut.APPLICATIONS -> return root
    }
    return Environment.getExternalStoragePublicDirectory(dir).absolutePath
}
