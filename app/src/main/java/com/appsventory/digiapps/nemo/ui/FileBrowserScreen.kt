package com.appsventory.digiapps.nemo.ui

import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import android.widget.Toast
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.activity.compose.BackHandler
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.TextButton
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.appsventory.digiapps.nemo.R
import com.appsventory.digiapps.nemo.data.FileItem
import com.appsventory.digiapps.nemo.ui.theme.*
import com.appsventory.digiapps.nemo.util.AppSettings
import com.appsventory.digiapps.nemo.util.ArchiveFormat
import com.appsventory.digiapps.nemo.util.ArchivePasswordCache
import com.appsventory.digiapps.nemo.util.Archives
import com.appsventory.digiapps.nemo.util.archiveBaseName
import com.appsventory.digiapps.nemo.util.archiveFormatOf
import com.appsventory.digiapps.nemo.util.StorageRoot
import com.appsventory.digiapps.nemo.util.ConflictPolicy
import com.appsventory.digiapps.nemo.util.isSameOrChild
import com.appsventory.digiapps.nemo.util.storageRoots
import com.appsventory.digiapps.nemo.util.FileClipboard
import com.appsventory.digiapps.nemo.util.FileOpener
import com.appsventory.digiapps.nemo.smb.SmbClient
import com.appsventory.digiapps.nemo.smb.SmbStore
import com.appsventory.digiapps.nemo.smb.smbHostOf
import com.appsventory.digiapps.nemo.util.FileOps
import com.appsventory.digiapps.nemo.util.Vfs
import com.appsventory.digiapps.nemo.util.FileTasks
import com.appsventory.digiapps.nemo.util.TRASH_PREFIX
import com.appsventory.digiapps.nemo.util.scanStorage
import com.appsventory.digiapps.nemo.util.StoragePermission
import com.appsventory.digiapps.nemo.util.formatBytes
import com.appsventory.digiapps.nemo.util.isHiddenEntry
import com.appsventory.digiapps.nemo.util.storageStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** What the browser lists: a real directory, or a flat virtual list scanned from storage. */
enum class BrowseMode(@StringRes val titleRes: Int?) {
    DIRECTORY(null),
    TRASH(R.string.tool_trash),
    APKS(R.string.shortcut_applications)
}

@Composable
fun FileBrowserScreen(
    currentPath: String,
    rootLabel: String,
    onPathChange: (String) -> Unit,
    onGoHome: () -> Unit = {},
    isPickMode: Boolean = false,
    mimeFilter: String? = null,
    onFilePicked: (String) -> Unit = {},
    onCreateFolder: () -> Unit = {},
    mode: BrowseMode = BrowseMode.DIRECTORY,
    storageRoot: String = Environment.getExternalStorageDirectory().absolutePath,
    clipboard: FileClipboard? = null,
    onClipboardChange: (FileClipboard?) -> Unit = {},
    isTv: Boolean = false,
    tasks: FileTasks,
    onEditSmb: (String) -> Unit = {},
    onForgetSmb: (String) -> Unit = {},
    /** Non-null while browsing an SD card / USB drive: adds "Eject" to the top bar menu. */
    onEjectVolume: (() -> Unit)? = null,
    /** Goes up when a drive is plugged / removed, so the storage list used by Trash / APK scans is rebuilt. */
    volumesVersion: Int = 0
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(BgDark)) {
        FileBrowserContent(
            availableWidth = maxWidth,
            currentPath = currentPath,
            rootLabel = rootLabel,
            onPathChange = onPathChange,
            onGoHome = onGoHome,
            isPickMode = isPickMode,
            mimeFilter = mimeFilter,
            onFilePicked = onFilePicked,
            onCreateFolder = onCreateFolder,
            mode = mode,
            storageRoot = storageRoot,
            clipboard = clipboard,
            onClipboardChange = onClipboardChange,
            isTv = isTv,
            tasks = tasks,
            onEditSmb = onEditSmb,
            onForgetSmb = onForgetSmb,
            onEjectVolume = onEjectVolume,
            volumesVersion = volumesVersion
        )
    }
}

@Composable
private fun FileBrowserContent(
    availableWidth: Dp,
    currentPath: String,
    rootLabel: String,
    onPathChange: (String) -> Unit,
    onGoHome: () -> Unit,
    isPickMode: Boolean,
    mimeFilter: String?,
    onFilePicked: (String) -> Unit,
    onCreateFolder: () -> Unit,
    mode: BrowseMode,
    storageRoot: String,
    clipboard: FileClipboard?,
    onClipboardChange: (FileClipboard?) -> Unit,
    isTv: Boolean,
    tasks: FileTasks,
    onEditSmb: (String) -> Unit,
    onForgetSmb: (String) -> Unit,
    onEjectVolume: (() -> Unit)?,
    volumesVersion: Int
) {
    val isCompact = availableWidth < 700.dp
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var hasPermission by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var permissionChecked by remember { mutableStateOf(false) }
    var folderGridMode by remember { mutableStateOf(AppSettings.defaultGrid) }
    val showHidden = AppSettings.showHidden
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var documentViewer by remember { mutableStateOf<Pair<Uri, DocumentKind>?>(null) }
    var lastFocusedPath by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var marked by remember { mutableStateOf(setOf<String>()) }
    // chronological order of the marks that are currently on - only used for "Between" (range-select)
    var markOrder by remember { mutableStateOf(listOf<String>()) }
    var pendingDelete by remember { mutableStateOf<List<FileItem>?>(null) }
    val internalLabel = stringResource(R.string.storage_internal)
    val roots = remember(volumesVersion) { storageRoots(context, internalLabel) }
    val onSmb = Vfs.isSmb(currentPath)
    // At the server root the "folders" shown ARE shares: destructive actions don't apply there,
    // and a share that auto-enumeration missed can be bookmarked by hand from the menu.
    val isShareRoot = onSmb && Vfs.samePath(currentPath, storageRoot)
    val manualShares = if (onSmb) SmbStore.find(smbHostOf(currentPath))?.shares?.toSet().orEmpty() else emptySet()
    var showAddShare by remember { mutableStateOf(false) }
    var showNetworkDebug by remember { mutableStateOf(false) }
    val longPressEnabled = !isPickMode // long-press always opens the item menu (unless picking a file)
    val canMultiSelect = !isPickMode && !isShareRoot // marking a share itself doesn't make sense
    val selectable = canMultiSelect
    var selecting by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }
    // archives: tap menu (View / Extract / Open with), the contents viewer, and the Compress dialog
    var archivePrompt by remember { mutableStateOf<FileItem?>(null) }
    var archiveView by remember { mutableStateOf<ArchiveViewRequest?>(null) }
    var passwordPrompt by remember { mutableStateOf<PasswordPrompt?>(null) }
    var compressTarget by remember { mutableStateOf<List<FileItem>?>(null) }
    var contextItem by remember { mutableStateOf<FileItem?>(null) }
    var loadError by remember { mutableStateOf<Throwable?>(null) }
    var propertiesFor by remember { mutableStateOf<List<FileItem>?>(null) }
    val selectionActive = selectable && (selecting || marked.isNotEmpty())

    val gridState = rememberLazyGridState()
    val firstFileFocus = remember { FocusRequester() }

    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermission = result.values.any { it } || StoragePermission.hasFullAccess(context)
        permissionChecked = true
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = StoragePermission.hasFullAccess(context)
                permissionChecked = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        hasPermission = StoragePermission.hasFullAccess(context)
        permissionChecked = true
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val folders = files.filter { it.isDirectory }
    val onlyFiles = files.filter { !it.isDirectory }
    val (used, total) = remember(currentPath) { if (Vfs.isSmb(currentPath)) 0L to 0L else storageStats(File(currentPath)) }

    // Create Folder card = slot 0 on desktop/TV (not in pick mode)
    val hasCreateCard = mode == BrowseMode.DIRECTORY && !isPickMode && !isCompact && !searching && !isShareRoot
    val slotOffset = if (hasCreateCard) 1 else 0
    val slotCount = folders.size + slotOffset
    val slotFocus = remember(slotCount) { List(slotCount) { FocusRequester() } }
    val initialFocus by rememberUpdatedState(slotFocus.firstOrNull() ?: firstFileFocus)

    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }

    LaunchedEffect(currentPath, hasPermission, mimeFilter, showHidden, mode, reloadTick, tasks.version, searching, query) {
        if (!hasPermission) {
            files = emptyList()
            return@LaunchedEffect
        }
        if (searching && query.isNotBlank()) {
            // debounce typing; keep the old results on screen meanwhile (no spinner)
            delay(300)
            marked = emptySet()
            files = withContext(Dispatchers.IO) { searchFiles(File(currentPath), query.trim(), showHidden) }
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        marked = emptySet()
        selecting = false
        loadError = null
        files = withContext(Dispatchers.IO) {
            try {
                loadEntries(roots, mode, currentPath, mimeFilter, showHidden)
            } catch (e: Exception) {
                loadError = e // e.g. SMB server unreachable / wrong password
                emptyList()
            }
        }
        isLoading = false
        // Focus first item only when entering a new folder (not when toggling hidden files / typing)
        val focusKey = "$mode:$currentPath"
        if (!searching && lastFocusedPath != focusKey) {
            gridState.scrollToItem(0)
            delay(80)
            runCatching { initialFocus.requestFocus() }
            lastFocusedPath = focusKey
        }
    }

    // ---- Live updates: a download finishing, another app saving a file, a USB copy... shows up by itself.
    // Silent: no spinner, and the selection / scroll position / focus stay as they are.
    suspend fun silentReload() {
        if (!hasPermission || (searching && query.isNotBlank())) return
        if (!onSmb && mode == BrowseMode.DIRECTORY && !File(currentPath).exists()) {
            // this folder was deleted (or its drive pulled out): step up to the nearest one that still exists
            var up = File(currentPath).parentFile
            while (up != null && !up.exists()) up = up.parentFile
            if (up != null && isSameOrChild(up.path, storageRoot)) onPathChange(up.path) else onGoHome()
            return
        }
        val fresh = withContext(Dispatchers.IO) {
            runCatching { loadEntries(roots, mode, currentPath, mimeFilter, showHidden) }.getOrNull()
        } ?: return
        if (fresh != files) {
            files = fresh
            val paths = fresh.mapTo(HashSet()) { it.path }
            marked = marked.filterTo(HashSet()) { it in paths } // keep the selection, minus what no longer exists
            markOrder = markOrder.filter { it in paths }
        }
    }

    val folderTick = rememberDirectoryTick(if (!onSmb && mode == BrowseMode.DIRECTORY) currentPath else null)
    val mediaTick = rememberMediaStoreTick()
    LaunchedEffect(folderTick, mediaTick, volumesVersion) {
        if (folderTick + mediaTick + volumesVersion == 0 || onSmb) return@LaunchedEffect
        // a burst of events (a download writing its file) becomes one reload; whole-storage scans wait longer
        delay(if (mode == BrowseMode.DIRECTORY) 500 else 1500)
        while (isLoading) delay(100) // the normal load is still running: it will read fresh data anyway
        silentReload()
    }

    // A network share can't tell us when something changes: poll the folder, only while it is on screen
    LaunchedEffect(currentPath, mode, onSmb, showHidden, mimeFilter) {
        if (!onSmb || mode != BrowseMode.DIRECTORY) return@LaunchedEffect
        while (true) {
            delay(SMB_POLL_MS)
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) continue
            // don't compete with a transfer, don't hammer a server that is already failing
            if (isLoading || loadError != null || tasks.isBusy || searching) continue
            silentReload()
        }
    }

    fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            StoragePermission.openManageAllFilesSettings(context)
        } else {
            legacyPermissionLauncher.launch(StoragePermission.legacyPermissions())
        }
    }

    /** Move focus to folder slot [target] (scroll first if it is not composed yet). */
    fun jumpToSlot(target: Int) {
        scope.launch {
            val gridIndex = target + 1 // +1 for the FOLDERS header
            val visible = gridState.layoutInfo.visibleItemsInfo.any { it.index == gridIndex }
            if (!visible) {
                gridState.animateScrollToItem(gridIndex)
                delay(60)
            }
            runCatching { slotFocus[target].requestFocus() }
        }
    }

    fun toggleMark(path: String) {
        if (path in marked) {
            marked = marked - path
            markOrder = markOrder - path
        } else {
            marked = marked + path
            markOrder = markOrder + path
        }
    }

    fun deleteItems(items: List<FileItem>, permanent: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                items.forEach { FileOps.deleteOrTrash(context, it.path, permanent) }
            }
            reloadTick++
        }
    }

    fun clearSelection() {
        marked = emptySet()
        markOrder = emptyList()
        selecting = false
    }

    fun restoreItems(items: List<FileItem>) {
        scope.launch {
            withContext(Dispatchers.IO) { items.forEach { FileOps.restore(File(it.path)) } }
            reloadTick++
        }
    }

    // Back leaves select mode first; a second Back goes up a folder
    BackHandler(enabled = selectionActive) { clearSelection() }

    val askNotifications = rememberNotificationPermissionRequest()

    var conflictPrompt by remember { mutableStateOf<ConflictPrompt?>(null) }

    fun startPaste(board: FileClipboard, destDir: String, policy: ConflictPolicy) {
        tasks.paste(board, destDir, policy) // queued behind any running copy / move, with progress + undo
        onClipboardChange(null)
    }

    fun paste(board: FileClipboard) {
        askNotifications() // progress notification (Android 13+)
        val dest = currentPath
        scope.launch {
            // names that already exist in the destination: ask what to do, once for all of them
            val names = withContext(Dispatchers.IO) { FileOps.findConflicts(board.paths, dest) }
            if (names.isEmpty()) startPaste(board, dest, ConflictPolicy.KEEP_BOTH)
            else conflictPrompt = ConflictPrompt(board, dest, names)
        }
    }

    /**
     * Runs [action] with the archive's password: null when it isn't protected, [known] when the caller already has it,
     * otherwise after the user typed (and we verified) it.
     */
    fun withArchivePassword(item: FileItem, known: CharArray?, action: (CharArray?) -> Unit) {
        val format = archiveFormatOf(item.name) ?: return
        if (known != null) {
            action(known.copyOf()) // the task wipes its copy when it ends
            return
        }
        // typed earlier this session for this very archive (e.g. View, now Extract): don't ask again
        ArchivePasswordCache.get(File(item.path))?.let {
            action(it)
            return
        }
        scope.launch {
            val needs = withContext(Dispatchers.IO) { Archives.needsPassword(File(item.path), format) }
            if (needs) passwordPrompt = PasswordPrompt(item, format, action) else action(null)
        }
    }

    /** Unpacks [item] next to itself: straight into this folder, or into a new folder named after the archive. */
    fun extractArchive(item: FileItem, intoNamedFolder: Boolean, knownPassword: CharArray? = null) {
        askNotifications() // progress notification (Android 13+)
        val parent = File(item.path).parent ?: return
        val dest = if (intoNamedFolder) Vfs.uniqueChild(parent, archiveBaseName(item.name)) else parent
        withArchivePassword(item, knownPassword) { password -> tasks.extract(item.path, dest, password) }
    }

    /** Opens the contents viewer (after asking for the password when the archive needs one). */
    fun viewArchive(item: FileItem) {
        withArchivePassword(item, null) { password -> archiveView = ArchiveViewRequest(item, password) }
    }

    // SMB file: download to the cache, then open it like a local file
    fun openSmbFile(path: String) {
        scope.launch {
            Toast.makeText(context, R.string.toast_downloading, Toast.LENGTH_SHORT).show()
            val local = withContext(Dispatchers.IO) { runCatching { SmbClient.downloadToCache(context, path) } }
            local.onSuccess { FileOpener.open(context, it.path) }
                .onFailure { Toast.makeText(context, R.string.toast_smb_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    // photos in this folder: preview in the built-in viewer (swipe between them)
    val imageFiles = files.filter { !it.isDirectory && isImagePath(it.path) && !Vfs.isSmb(it.path) }

    // Long press: open the item's menu, or (already selecting) toggle its mark
    val onItemLongClick: (String) -> Unit = { path ->
        if (selectionActive) toggleMark(path) else contextItem = files.firstOrNull { it.path == path }
    }

    // While selecting, a tap toggles the mark instead of opening.
    val onFolderClick: (String) -> Unit = { path ->
        if (selectionActive) {
            toggleMark(path)
        } else {
            searching = false
            query = ""
            onPathChange(path)
        }
    }
    val onFileClick: (String) -> Unit = { path ->
        when {
            selectionActive -> toggleMark(path)
            isPickMode -> onFilePicked(path)
            Vfs.isSmb(path) -> openSmbFile(path)
            isImagePath(path) -> viewerIndex = imageFiles.indexOfFirst { it.path == path }.coerceAtLeast(0)
            // ZIP / RAR / 7z / TAR / GZ...: ask what to do (view, extract, open with)
            mode == BrowseMode.DIRECTORY && archiveFormatOf(Vfs.name(path)) != null ->
                archivePrompt = files.firstOrNull { it.path == path } ?: FileItem.from(File(path))
            isPdfPath(path) -> documentViewer = Uri.fromFile(File(path)) to DocumentKind.PDF
            isTextPath(path) -> documentViewer = Uri.fromFile(File(path)) to DocumentKind.TEXT
            else -> FileOpener.open(context, path)
        }
    }

    // Same actions drive the floating pill (touch) and the top bar menu (TV / DPAD).
    val markedPaths = marked.toList()
    val cancelAction = PillAction(Icons.Default.Close, stringResource(R.string.action_cancel), onClick = { clearSelection() })
    val infoAction = PillAction(
        Icons.Default.Info,
        stringResource(R.string.action_properties),
        onClick = { propertiesFor = files.filter { it.path in marked } }
    )
    val renameAction: PillAction? = if (marked.size == 1) {
        PillAction(
            Icons.Default.DriveFileRenameOutline,
            stringResource(R.string.action_rename),
            onClick = { renameTarget = files.firstOrNull { it.path in marked } }
        )
    } else null
    // folders can't be shared: only offered when at least one selected item is a file
    val shareAction: PillAction? = if (files.any { it.path in marked && !it.isDirectory && !Vfs.isSmb(it.path) }) {
        PillAction(Icons.Default.Share, stringResource(R.string.action_share), onClick = { FileOpener.share(context, markedPaths) })
    } else null
    // packing needs real files on this device (not a network folder)
    val compressAction: PillAction? = if (!onSmb && mode == BrowseMode.DIRECTORY && marked.isNotEmpty()) {
        PillAction(Icons.Default.Archive, stringResource(R.string.action_compress), onClick = {
            compressTarget = files.filter { it.path in marked }
        })
    } else null
    val deleteMarked = { pendingDelete = files.filter { it.path in marked } }
    // items in on-screen order (folders, then files) - what "Between" selects a range across
    val orderedPaths = remember(folders, onlyFiles) { folders.map { it.path } + onlyFiles.map { it.path } }
    val selectAllAction = PillAction(Icons.Default.SelectAll, stringResource(R.string.action_select_all), onClick = {
        marked = orderedPaths.toSet()
        markOrder = orderedPaths
    })
    // needs two marks to know a range - hidden until then, rather than shown disabled
    val selectBetweenAction: PillAction? = if (markOrder.size >= 2) {
        PillAction(Icons.Default.MultipleStop, stringResource(R.string.action_select_between), onClick = {
            val a = orderedPaths.indexOf(markOrder[markOrder.size - 2])
            val b = orderedPaths.indexOf(markOrder[markOrder.size - 1])
            if (a >= 0 && b >= 0) {
                val range = orderedPaths.subList(minOf(a, b), maxOf(a, b) + 1)
                marked = marked + range
                markOrder = markOrder + range.filterNot { it in markOrder }
            }
        })
    } else null
    val pillActions: List<PillAction> = when {
        mode == BrowseMode.DIRECTORY && clipboard != null -> listOf(
            PillAction(Icons.Default.ContentPaste, stringResource(R.string.action_paste, clipboard.paths.size), onClick = { paste(clipboard) }),
            PillAction(Icons.Default.Close, stringResource(R.string.action_cancel), onClick = { onClipboardChange(null) })
        )
        mode == BrowseMode.DIRECTORY && marked.isNotEmpty() -> listOfNotNull(
            selectAllAction,
            selectBetweenAction,
            PillAction(Icons.Default.ContentCopy, stringResource(R.string.action_copy), onClick = {
                onClipboardChange(FileClipboard(markedPaths, move = false))
                clearSelection()
            }),
            PillAction(Icons.Default.DriveFileMove, stringResource(R.string.action_move), onClick = {
                onClipboardChange(FileClipboard(markedPaths, move = true))
                clearSelection()
            }),
            PillAction(Icons.Default.Delete, stringResource(R.string.action_delete), destructive = true, onClick = { deleteMarked() }),
            shareAction,
            compressAction,
            renameAction,
            infoAction,
            cancelAction
        )
        mode == BrowseMode.APKS && marked.isNotEmpty() -> listOf(
            PillAction(Icons.Default.Delete, stringResource(R.string.action_delete), destructive = true, onClick = { deleteMarked() }),
            infoAction,
            cancelAction
        )
        mode == BrowseMode.TRASH && marked.isNotEmpty() -> listOf(
            PillAction(Icons.Default.Restore, stringResource(R.string.action_restore), onClick = {
                restoreItems(files.filter { it.path in marked })
            }),
            PillAction(Icons.Default.Delete, stringResource(R.string.action_delete), destructive = true, onClick = { deleteMarked() }),
            infoAction,
            cancelAction
        )
        else -> emptyList()
    }
    // In a normal directory, the create-folder button and the selection pill are ONE floating
    // control that morphs between the two (see MorphingActionBar) - so this covers both states.
    val directoryBarVisible = !isTv && mode == BrowseMode.DIRECTORY && !isPickMode && !searching &&
        !isShareRoot && tasks.progress == null && tasks.notice == null
    // Trash / Applications have no create-folder button to morph from, so their selection pill
    // is just its own simple fade in/out.
    val otherPillVisible = !isTv && mode != BrowseMode.DIRECTORY && pillActions.isNotEmpty()

    Box(Modifier.fillMaxSize().background(BgDark)) {
        val hPad = if (isCompact) 12.dp else 24.dp
        val gridCols = when {
            !folderGridMode -> 1
            availableWidth < 500.dp -> 2
            availableWidth < 800.dp -> 3
            else -> 4
        }

        when {
            !permissionChecked -> LoadingBox()
            !hasPermission -> PermissionRequired(onRequest = { requestPermission() })
            else -> {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        TopBar(
                            title = mode.titleRes?.let { stringResource(it) }
                                ?: if (currentPath == storageRoot) rootLabel else Vfs.name(currentPath),
                            showBack = mode != BrowseMode.DIRECTORY || currentPath != storageRoot,
                            used = used,
                            total = total,
                            showViewToggle = !isCompact,
                            showSearch = mode == BrowseMode.DIRECTORY && !onSmb,
                            searching = searching,
                            query = query,
                            onQueryChange = { query = it },
                            onOpenSearch = { searching = true },
                            onCloseSearch = {
                                searching = false
                                query = ""
                            },
                            folderGridMode = folderGridMode,
                            onToggleView = { folderGridMode = !folderGridMode },
                            menu = { dismiss ->
                                when {
                                    mode == BrowseMode.TRASH -> TrashMenu(
                                        items = files,
                                        roots = roots,
                                        marked = marked,
                                        dismiss = dismiss,
                                        onDelete = { pendingDelete = it },
                                        onRestore = { restoreItems(it) }
                                    )
                                    isTv && pillActions.isNotEmpty() -> pillActions.forEach { action ->
                                        MenuItem(
                                            text = action.label,
                                            icon = action.icon,
                                            onClick = {
                                                dismiss()
                                                action.onClick()
                                            }
                                        )
                                    }
                                    else -> BrowseMenu(
                                        isSmb = onSmb,
                                        isShareRoot = isShareRoot,
                                        onRefresh = { reloadTick++ },
                                        onEditSmb = { onEditSmb(currentPath) },
                                        onForgetSmb = { onForgetSmb(currentPath) },
                                        onEject = onEjectVolume,
                                        onAddShare = { showAddShare = true },
                                        showHidden = showHidden,
                                        canSelect = canMultiSelect,
                                        dismiss = dismiss,
                                        onToggleHidden = { AppSettings.setShowHidden(!showHidden) },
                                        onSelectMultiple = { selecting = true }
                                    )
                                }
                            },
                            onBack = {
                                if (mode == BrowseMode.DIRECTORY) onPathChange(Vfs.parent(currentPath) ?: currentPath)
                                else onGoHome()
                            },
                            onGoHome = onGoHome
                        )

                        if (isLoading) {
                            LoadingBox()
                        } else {
                            FileGrid(
                                folders = folders,
                                onlyFiles = onlyFiles,
                                gridState = gridState,
                                gridCols = gridCols,
                                gridMode = folderGridMode,
                                hasCreateCard = hasCreateCard,
                                slotFocus = slotFocus,
                                firstFileFocus = firstFileFocus,
                                showHidden = showHidden,
                                isPickMode = isPickMode,
                                hPad = hPad,
                                bottomPad = if (directoryBarVisible || otherPillVisible) 96.dp else 24.dp,
                                onJumpToSlot = ::jumpToSlot,
                                onOpenFolder = onFolderClick,
                                marked = marked,
                                selectionMode = selectionActive,
                                onLongClick = if (longPressEnabled) onItemLongClick else null,
                                isShareRoot = isShareRoot,
                                onAddShare = { showAddShare = true },
                                showDebugButton = onSmb && loadError != null,
                                onShowDebug = { showNetworkDebug = true },
                                emptyText = loadError?.let { err ->
                                    val msg = stringResource(
                                        if (SmbClient.isAuthError(err)) R.string.smb_error_auth else R.string.smb_error_connect
                                    )
                                    val detail = generateSequence(err) { e -> e.cause }
                                        .lastOrNull { e -> !e.message.isNullOrBlank() }?.message ?: err.message
                                    if (!detail.isNullOrBlank()) "$msg\n($detail)" else msg
                                } ?: stringResource(
                                    when {
                                        isShareRoot -> R.string.smb_no_shares_detected
                                        mode == BrowseMode.DIRECTORY && !searching -> R.string.folder_empty
                                        else -> R.string.list_empty
                                    }
                                ),
                                onFileClick = onFileClick,
                                onCreateFolder = onCreateFolder
                            )
                        }
                    }

                    if (directoryBarVisible) {
                        MorphingActionBar(
                            expanded = pillActions.isNotEmpty(),
                            collapsedIcon = Icons.Default.CreateNewFolder,
                            collapsedDescription = stringResource(R.string.create_folder),
                            onCollapsedClick = onCreateFolder,
                            actions = pillActions,
                            selectionCount = marked.size.takeIf { it > 0 },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(horizontal = 16.dp, vertical = 16.dp)
                        )
                    }

                    AnimatedVisibility(
                        visible = otherPillVisible,
                        enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.9f, animationSpec = tween(200)),
                        exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.9f, animationSpec = tween(150)),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                    ) {
                        ActionPill(actions = pillActions, selectionCount = marked.size.takeIf { it > 0 })
                    }
                }
            }
        }
    }

    contextItem?.let { item ->
        val actions = buildList {
            val selectMultiple = PillAction(Icons.Default.CheckBox, stringResource(R.string.action_select_multiple), onClick = {
                selecting = true
                marked = setOf(item.path)
                markOrder = listOf(item.path)
            })
            val delete = PillAction(Icons.Default.Delete, stringResource(R.string.action_delete), destructive = true, onClick = { pendingDelete = listOf(item) })
            val details = PillAction(
                Icons.Default.Info,
                stringResource(if (mode == BrowseMode.APKS) R.string.action_details else R.string.action_properties),
                onClick = { propertiesFor = listOf(item) }
            )
            when (mode) {
                BrowseMode.DIRECTORY -> if (isShareRoot) {
                    // items here ARE shares: renaming/deleting/moving the share itself makes no sense
                    add(details)
                    if (item.name in manualShares) {
                        add(PillAction(Icons.Default.LinkOff, stringResource(R.string.smb_forget_share), onClick = {
                            SmbStore.removeShare(smbHostOf(item.path), item.name)
                            reloadTick++
                        }))
                    }
                } else {
                    add(selectMultiple)
                    add(PillAction(Icons.Default.DriveFileRenameOutline, stringResource(R.string.action_rename), onClick = { renameTarget = item }))
                    add(PillAction(Icons.Default.ContentCopy, stringResource(R.string.action_copy), onClick = {
                        onClipboardChange(FileClipboard(listOf(item.path), move = false))
                    }))
                    add(PillAction(Icons.Default.DriveFileMove, stringResource(R.string.action_move), onClick = {
                        onClipboardChange(FileClipboard(listOf(item.path), move = true))
                    }))
                    // folders can't be shared
                    if (!item.isDirectory && !Vfs.isSmb(item.path)) {
                        add(PillAction(Icons.Default.Share, stringResource(R.string.action_share), onClick = {
                            FileOpener.share(context, listOf(item.path))
                        }))
                    }
                    if (!onSmb) {
                        if (!item.isDirectory && archiveFormatOf(item.name) != null) {
                            add(PillAction(Icons.Default.Visibility, stringResource(R.string.archive_view), onClick = { viewArchive(item) }))
                            add(PillAction(Icons.Default.Unarchive, stringResource(R.string.archive_extract_here), onClick = { extractArchive(item, false) }))
                        }
                        add(PillAction(Icons.Default.Archive, stringResource(R.string.action_compress), onClick = { compressTarget = listOf(item) }))
                    }
                    add(delete)
                    add(details)
                }
                BrowseMode.TRASH -> {
                    add(selectMultiple)
                    add(PillAction(Icons.Default.Restore, stringResource(R.string.action_restore), onClick = { restoreItems(listOf(item)) }))
                    add(delete)
                    add(details)
                }
                BrowseMode.APKS -> {
                    add(PillAction(Icons.Default.InstallMobile, stringResource(R.string.action_open_install), onClick = {
                        FileOpener.open(context, item.path)
                    }))
                    add(PillAction(Icons.Default.FolderOpen, stringResource(R.string.action_open_location), onClick = {
                        File(item.path).parent?.let { onPathChange(it) }
                    }))
                    add(selectMultiple)
                    add(delete)
                    add(details)
                }
            }
        }
        ContextMenuDialog(title = item.name, actions = actions, onDismiss = { contextItem = null })
    }

    if (showNetworkDebug) {
        val port = SmbStore.find(smbHostOf(currentPath))?.port ?: 445
        NetworkDebugDialog(host = smbHostOf(currentPath), port = port, onDismiss = { showNetworkDebug = false })
    }

    if (showAddShare) {
        AddShareDialog(
            onAdd = { name ->
                SmbStore.addShare(smbHostOf(currentPath), name)
                showAddShare = false
                reloadTick++
            },
            onDismiss = { showAddShare = false }
        )
    }

    renameTarget?.let { target ->
        var text by remember(target) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = CardDark,
            title = { Text(stringResource(R.string.action_rename), color = TextPrimary) },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    tasks.rename(target.path, text) { ok ->
                        if (ok) clearSelection()
                        else Toast.makeText(context, R.string.toast_rename_failed, Toast.LENGTH_SHORT).show()
                    }
                    renameTarget = null
                }) { Text(stringResource(R.string.action_rename), color = CyanAccent) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            }
        )
    }

    archivePrompt?.let { item ->
        val actions = listOf(
            PillAction(Icons.Default.Visibility, stringResource(R.string.archive_view), onClick = { viewArchive(item) }),
            PillAction(Icons.Default.Unarchive, stringResource(R.string.archive_extract_here), onClick = { extractArchive(item, false) }),
            PillAction(
                Icons.Default.FolderOpen,
                stringResource(R.string.archive_extract_to, archiveBaseName(item.name)),
                onClick = { extractArchive(item, true) }
            ),
            PillAction(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.action_open_with), onClick = { FileOpener.open(context, item.path) })
        )
        ContextMenuDialog(title = item.name, actions = actions, onDismiss = { archivePrompt = null })
    }

    passwordPrompt?.let { prompt ->
        ArchivePasswordDialog(
            archiveName = prompt.item.name,
            verify = { password -> Archives.verifyPassword(File(prompt.item.path), prompt.format, password) },
            onUnlocked = { password ->
                ArchivePasswordCache.put(File(prompt.item.path), password) // remembers its own copy
                passwordPrompt = null
                prompt.onUnlocked(password)
            },
            onDismiss = { passwordPrompt = null }
        )
    }

    archiveView?.let { request ->
        ArchiveViewDialog(
            archive = request.item,
            password = request.password,
            onExtract = { named -> extractArchive(request.item, named, request.password) },
            onExtractSelected = { paths ->
                // Selective extract into the archive's parent folder (same as "Extract here")
                val parent = java.io.File(request.item.path).parent ?: return@ArchiveViewDialog
                askNotifications()
                tasks.extractSelected(request.item.path, parent, paths, request.password)
            },
            onDismiss = {
                request.password?.fill('\u0000')
                archiveView = null
            }
        )
    }

    compressTarget?.let { items ->
        CompressDialog(
            defaultName = if (items.size == 1) items.first().name.substringBeforeLast('.', items.first().name) else File(currentPath).name.ifEmpty { "Archive" },
            onConfirm = { name, format, level, password ->
                askNotifications()
                tasks.compress(items.map { it.path }, currentPath, name, format, level, password)
                compressTarget = null
                clearSelection()
            },
            onDismiss = { compressTarget = null }
        )
    }

    propertiesFor?.let { items -> PropertiesDialog(items) { propertiesFor = null } }

    pendingDelete?.let { items ->
        val count = items.size
        val res = context.resources
        DeleteDialog(
            title = stringResource(R.string.delete_confirm_title),
            bodyTrash = res.getQuantityString(R.plurals.delete_trash_body, count, count),
            bodyPermanent = res.getQuantityString(R.plurals.delete_confirm_body, count, count),
            // inside Trash there is nothing left to trash: always permanent
            trashAllowed = mode != BrowseMode.TRASH && !onSmb, // network shares have no Trash
            onDismiss = { pendingDelete = null },
            onConfirm = { permanent ->
                pendingDelete = null
                deleteItems(items, permanent || mode == BrowseMode.TRASH)
            }
        )
    }

    conflictPrompt?.let { prompt ->
        ConflictDialog(
            names = prompt.names,
            trashHint = !Vfs.isSmb(prompt.destDir),
            onChoose = { policy ->
                conflictPrompt = null
                startPaste(prompt.board, prompt.destDir, policy)
            },
            onDismiss = { conflictPrompt = null }
        )
    }

    viewerIndex?.let { start ->
        ImageViewer(
            images = imageFiles.map { ViewerImage(Uri.fromFile(File(it.path)), it.name, it.path) },
            startIndex = start,
            onClose = { viewerIndex = null },
            onChanged = { tasks.notifyChanged() }
        )
    }

    documentViewer?.let { (uri, kind) ->
        val name = uri.lastPathSegment ?: "file"
        when (kind) {
            DocumentKind.PDF -> PdfViewer(uri = uri, title = name, onClose = { documentViewer = null })
            DocumentKind.TEXT -> TextViewer(uri = uri, title = name, onClose = { documentViewer = null })
        }
    }
}

/** An archive to browse, with its (already verified) password if it has one. */
private class ArchiveViewRequest(val item: FileItem, val password: CharArray?)

/** Waiting for the password of [item]; [onUnlocked] continues with it. */
private class PasswordPrompt(val item: FileItem, val format: ArchiveFormat, val onUnlocked: (CharArray?) -> Unit)

/** Paste waiting for the user's choice because some names already exist in [destDir]. */
private class ConflictPrompt(val board: FileClipboard, val destDir: String, val names: List<String>)

/** How often a network folder is re-read while it is on screen. */
private const val SMB_POLL_MS = 5000L

@Composable
private fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = CyanAccent)
    }
}

/**
 * Sections are shown only when they have content:
 * - FOLDERS: has folders, or the Create Folder card exists (desktop/TV)
 * - FILES:   has files
 * - both empty: one empty message
 *
 * Folder grid navigation: DPAD right on the last column wraps to the first
 * column of the next row; on the very last folder it stops.
 */
@Composable
private fun FileGrid(
    folders: List<FileItem>,
    onlyFiles: List<FileItem>,
    gridState: LazyGridState,
    gridCols: Int,
    gridMode: Boolean,
    hasCreateCard: Boolean,
    slotFocus: List<FocusRequester>,
    firstFileFocus: FocusRequester,
    showHidden: Boolean,
    isPickMode: Boolean,
    hPad: Dp,
    bottomPad: Dp,
    onJumpToSlot: (Int) -> Unit,
    onOpenFolder: (String) -> Unit,
    marked: Set<String>,
    selectionMode: Boolean,
    onLongClick: ((String) -> Unit)?,
    isShareRoot: Boolean,
    onAddShare: () -> Unit,
    showDebugButton: Boolean,
    onShowDebug: () -> Unit,
    emptyText: String,
    onFileClick: (String) -> Unit,
    onCreateFolder: () -> Unit
) {
    val slotOffset = if (hasCreateCard) 1 else 0
    val lastSlot = folders.size + slotOffset - 1
    val showFolders = hasCreateCard || folders.isNotEmpty()
    val showFiles = onlyFiles.isNotEmpty()

    fun Modifier.wrapRight(slot: Int): Modifier =
        if (!gridMode || gridCols < 2) this else onPreviewKeyEvent { e ->
            val isRight = e.key == Key.DirectionRight
            val atRowEnd = (slot + 1) % gridCols == 0 || slot == lastSlot
            if (!isRight || !atRowEnd) {
                false
            } else {
                if (e.type == KeyEventType.KeyDown && slot < lastSlot) onJumpToSlot(slot + 1)
                true // consume: wrap to next row, or stop at the last folder
            }
        }

    LazyVerticalGrid(
        columns = GridCells.Fixed(gridCols),
        state = gridState,
        contentPadding = PaddingValues(start = hPad, end = hPad, top = 8.dp, bottom = bottomPad),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
    ) {
        if (showFolders) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(R.string.section_folders, top = 8.dp)
            }
            if (hasCreateCard) {
                item {
                    CreateFolderCard(
                        modifier = Modifier.focusRequester(slotFocus[0]).wrapRight(0),
                        onClick = onCreateFolder
                    )
                }
            }
            items(folders.size, key = { folders[it].path }) { index ->
                val item = folders[index]
                val slot = index + slotOffset
                FolderEntry(
                    item = item,
                    gridMode = gridMode,
                    showHidden = showHidden,
                    marked = item.path in marked,
                    selectionMode = selectionMode,
                    onLongClick = onLongClick?.let { { it(item.path) } },
                    modifier = Modifier.focusRequester(slotFocus[slot]).wrapRight(slot),
                    onClick = { onOpenFolder(item.path) }
                )
            }
        }

        if (showFiles) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(R.string.section_files, top = if (showFolders) 16.dp else 8.dp)
            }
            items(
                onlyFiles.size,
                key = { "file_" + onlyFiles[it].path },
                span = { GridItemSpan(maxLineSpan) }
            ) { index ->
                val item = onlyFiles[index]
                FileRow(
                    item = item,
                    isPickMode = isPickMode,
                    marked = item.path in marked,
                    selectionMode = selectionMode,
                    onLongClick = onLongClick?.let { { it(item.path) } },
                    modifier = if (index == 0 && !showFolders) Modifier.focusRequester(firstFileFocus) else Modifier,
                    onClick = { onFileClick(item.path) }
                )
            }
        }

        if (!showFolders && !showFiles) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(vertical = 16.dp)) {
                    Text(emptyText, color = TextMuted, fontSize = 13.sp)
                    if (isShareRoot) {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onAddShare,
                            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = OnAccent)
                        ) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.smb_add_share))
                        }
                    }
                    if (showDebugButton) {
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = onShowDebug) {
                            Icon(Icons.Default.BugReport, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.network_debug_show), color = CyanAccent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    showBack: Boolean,
    used: Long,
    total: Long,
    showViewToggle: Boolean,
    showSearch: Boolean,
    searching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    folderGridMode: Boolean,
    onToggleView: () -> Unit,
    menu: @Composable (dismiss: () -> Unit) -> Unit,
    onBack: () -> Unit,
    onGoHome: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    if (searching) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(BgDark)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TopBarButton(onClick = onCloseSearch, modifier = Modifier.size(40.dp)) { focused ->
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.cd_close),
                    tint = if (focused) OnAccent else TextSecondary
                )
            }
            Spacer(Modifier.width(8.dp))
            SearchField(query, onQueryChange, Modifier.weight(1f))
        }
        return
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(BgDark)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showBack) {
            TopBarButton(onClick = onBack, modifier = Modifier.size(40.dp)) { focused ->
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = if (focused) OnAccent else TextSecondary
                )
            }
        }

        TopBarButton(onClick = onGoHome, shape = RoundedCornerShape(6.dp)) { focused ->
            Text(
                stringResource(R.string.home_title),
                color = if (focused) OnAccent else CyanAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
        Text("  ›  ", color = TextMuted, fontSize = 12.sp)
        Text(
            title.uppercase(),
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (total > 0) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Text(
                    stringResource(R.string.storage_used, formatBytes(used), formatBytes(total)),
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (used.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .width(100.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = CyanAccent,
                    trackColor = DividerDark
                )
            }
        }

        if (showSearch) {
            TopBarButton(onClick = onOpenSearch, modifier = Modifier.size(40.dp)) { focused ->
                Icon(
                    Icons.Default.Search,
                    contentDescription = stringResource(R.string.cd_search),
                    tint = if (focused) OnAccent else CyanAccent
                )
            }
        }

        if (showViewToggle) {
            TopBarButton(onClick = onToggleView, modifier = Modifier.size(40.dp)) { focused ->
                Icon(
                    if (folderGridMode) Icons.Default.ViewList else Icons.Default.GridView,
                    contentDescription = stringResource(if (folderGridMode) R.string.view_list else R.string.view_grid),
                    tint = if (focused) OnAccent else CyanAccent
                )
            }
        }

        Box {
            TopBarButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) { focused ->
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.cd_more_options),
                    tint = if (focused) OnAccent else TextMuted
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = CardDark
            ) {
                menu { menuOpen = false }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(CardDark)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, null, tint = TextMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(stringResource(R.string.search_hint), color = TextMuted, fontSize = 14.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(CyanAccent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.cd_clear),
                tint = TextMuted,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onQueryChange("") }
            )
        }
    }
}

@Composable
private fun MenuItem(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    checked: Boolean = false
) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, null) },
        trailingIcon = { if (checked) Icon(Icons.Default.Check, null) },
        enabled = enabled,
        colors = MenuDefaults.itemColors(
            textColor = TextPrimary,
            leadingIconColor = TextSecondary,
            trailingIconColor = CyanAccent,
            disabledTextColor = TextMuted,
            disabledLeadingIconColor = TextMuted
        ),
        onClick = onClick
    )
}

@Composable
private fun BrowseMenu(
    isSmb: Boolean,
    isShareRoot: Boolean,
    onRefresh: () -> Unit,
    onEditSmb: () -> Unit,
    onForgetSmb: () -> Unit,
    onEject: (() -> Unit)?,
    onAddShare: () -> Unit,
    showHidden: Boolean,
    canSelect: Boolean,
    dismiss: () -> Unit,
    onToggleHidden: () -> Unit,
    onSelectMultiple: () -> Unit
) {
    MenuItem(
        text = stringResource(R.string.action_refresh),
        icon = Icons.Default.Refresh,
        onClick = {
            dismiss()
            onRefresh()
        }
    )
    if (isShareRoot) {
        MenuItem(stringResource(R.string.smb_add_share), Icons.Default.CreateNewFolder, onClick = { dismiss(); onAddShare() })
    }
    if (isSmb) {
        MenuItem(stringResource(R.string.smb_edit), Icons.Default.Edit, onClick = { dismiss(); onEditSmb() })
        MenuItem(stringResource(R.string.smb_forget), Icons.Default.LinkOff, onClick = { dismiss(); onForgetSmb() })
    }
    if (!isShareRoot) {
        MenuItem(
            text = stringResource(R.string.menu_select_multiple),
            icon = Icons.Default.CheckBox,
            enabled = canSelect,
            onClick = {
                dismiss()
                onSelectMultiple()
            }
        )
    }
    MenuItem(
        text = stringResource(R.string.menu_show_hidden),
        icon = Icons.Default.Visibility,
        checked = showHidden,
        onClick = {
            onToggleHidden()
            dismiss()
        }
    )
    if (onEject != null) {
        MenuItem(stringResource(R.string.action_eject), Icons.Default.Eject, onClick = { dismiss(); onEject() })
    }
    // Not implemented yet
    MenuItem(stringResource(R.string.menu_settings), Icons.Default.Settings, onClick = {}, enabled = false)
}

/** Trash menu: restore / delete for marked items, Restore all, Empty Trash, Delete from <each storage>. */
@Composable
private fun TrashMenu(
    items: List<FileItem>,
    roots: List<StorageRoot>,
    marked: Set<String>,
    dismiss: () -> Unit,
    onDelete: (List<FileItem>) -> Unit,
    onRestore: (List<FileItem>) -> Unit
) {
    if (marked.isNotEmpty()) {
        val selected = items.filter { it.path in marked }
        MenuItem(
            text = stringResource(R.string.menu_restore_marked, marked.size),
            icon = Icons.Default.Restore,
            onClick = {
                dismiss()
                onRestore(selected)
            }
        )
        MenuItem(
            text = stringResource(R.string.menu_delete_marked, marked.size),
            icon = Icons.Default.DeleteForever,
            onClick = {
                dismiss()
                onDelete(selected)
            }
        )
    }
    MenuItem(
        text = stringResource(R.string.menu_restore_all),
        icon = Icons.Default.Restore,
        enabled = items.isNotEmpty(),
        onClick = {
            dismiss()
            onRestore(items)
        }
    )
    MenuItem(
        text = stringResource(R.string.menu_empty_trash),
        icon = Icons.Default.DeleteSweep,
        enabled = items.isNotEmpty(),
        onClick = {
            dismiss()
            onDelete(items)
        }
    )
    roots.forEach { root ->
        val inRoot = items.filter { isSameOrChild(it.path, root.path) }
        MenuItem(
            text = stringResource(R.string.menu_delete_from, root.label),
            icon = Icons.Default.Storage,
            enabled = inRoot.isNotEmpty(),
            onClick = {
                dismiss()
                onDelete(inRoot)
            }
        )
    }
}

@Composable
private fun CreateFolderCard(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(88.dp)
            .focusCard(dashedWhenIdle = true, onClick = onClick)
            .padding(14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(28.dp)
                    .background(CyanAccent)
            )
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.create_folder), color = TextPrimary, fontSize = 13.sp)
        }
    }
}

/** Folder: card (grid) or row (list). */
@Composable
private fun FolderEntry(
    item: FileItem,
    gridMode: Boolean,
    showHidden: Boolean,
    marked: Boolean,
    selectionMode: Boolean,
    onLongClick: (() -> Unit)?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val resources = LocalContext.current.resources
    val countLabel = remember(item.path, showHidden) {
        if (Vfs.isSmb(item.path)) "" else {
            val n = File(item.path).listFiles()?.count { showHidden || !it.isHiddenEntry() } ?: 0
            resources.getQuantityString(R.plurals.item_count, n, n)
        }
    }

    if (gridMode) {
        Row(
            modifier
                .height(88.dp)
                .focusCard(marked = marked, onLongClick = onLongClick, onClick = onClick)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FolderIcon()
            Spacer(Modifier.width(10.dp))
            EntryText(title = item.name, subtitle = countLabel, badge = item.origin, bold = true, modifier = Modifier.weight(1f))
            if (selectionMode) SelectIndicator(marked)
        }
    } else {
        ItemRow(
            title = item.name,
            subtitle = countLabel,
            badge = item.origin,
            marked = marked,
            onLongClick = onLongClick,
            onClick = onClick,
            modifier = modifier,
            leading = { FolderIcon() },
            trailing = { if (selectionMode) SelectIndicator(marked) }
        )
    }
}

@Composable
private fun FileRow(
    item: FileItem,
    isPickMode: Boolean,
    marked: Boolean,
    selectionMode: Boolean,
    onLongClick: (() -> Unit)?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ItemRow(
        title = item.name,
        subtitle = stringResource(R.string.file_modified, formatDate(item.lastModified)),
        badge = item.origin,
        marked = marked,
        onLongClick = onLongClick,
        onClick = onClick,
        modifier = modifier,
        leading = {
            if (Vfs.isSmb(item.path)) {
                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = TextSecondary, modifier = Modifier.size(28.dp))
            } else FileThumbnail(item = item, size = 36.dp)
        },
        trailing = {
            Text(formatBytes(item.size), color = TextSecondary, fontSize = 12.sp)
            if (isPickMode) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.CheckCircle, null, tint = CyanAccent, modifier = Modifier.size(18.dp))
            }
            if (selectionMode) {
                Spacer(Modifier.width(8.dp))
                SelectIndicator(marked)
            }
        }
    )
}

/** Full-width row shared by list-mode folders and files. */
@Composable
private fun ItemRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    marked: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier
            .fillMaxWidth()
            .focusCard(
                corner = dimensionResource(R.dimen.row_corner),
                marked = marked,
                onLongClick = onLongClick,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading()
        Spacer(Modifier.width(12.dp))
        EntryText(title = title, subtitle = subtitle, badge = badge, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun FolderIcon() {
    Icon(Icons.Default.Folder, null, tint = CyanAccent, modifier = Modifier.size(22.dp))
}

/** Radio-style indicator shown on every item while in select mode. */
@Composable
private fun SelectIndicator(checked: Boolean) {
    Icon(
        if (checked) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
        contentDescription = null,
        tint = if (checked) CyanAccent else TextMuted,
        modifier = Modifier.size(20.dp)
    )
}

@Composable
private fun EntryText(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    badge: String? = null
) {
    Column(modifier) {
        Text(
            title,
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = if (bold) FontWeight.Medium else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(subtitle, color = TextMuted, fontSize = 11.sp)
            if (badge != null) {
                Spacer(Modifier.width(6.dp))
                // small "where from" badge (Trash / APK lists span several storages)
                Text(
                    badge,
                    color = TextSecondary,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 140.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(DividerDark)
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
internal fun PermissionRequired(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.FolderOff, null, tint = ErrorRed, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.permission_title), color = TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.permission_body), color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onRequest,
            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = OnAccent)
        ) {
            Text(stringResource(R.string.permission_open_settings))
        }
    }
}

private val TRASH_NAME_PREFIX = Regex("^\\.trashed-\\d+-")

private suspend fun loadEntries(
    roots: List<StorageRoot>,
    mode: BrowseMode,
    path: String,
    mimeFilter: String?,
    showHidden: Boolean
): List<FileItem> = when (mode) {
    BrowseMode.DIRECTORY -> loadFiles(path, mimeFilter, showHidden)
    // Trash / APK lists scan every storage and tag each entry with its storage label
    BrowseMode.TRASH -> scanAll(roots) { it.name.startsWith(TRASH_PREFIX) }
        .map { it.copy(name = it.name.replace(TRASH_NAME_PREFIX, "")) }
        .sortedForList()
    BrowseMode.APKS -> scanAll(roots) { it.isFile && it.extension.equals("apk", ignoreCase = true) }
        .sortedForList()
}

private suspend fun scanAll(roots: List<StorageRoot>, match: (File) -> Boolean): List<FileItem> =
    roots.flatMap { root ->
        scanStorage(File(root.path), match).map { FileItem.from(it).copy(origin = root.label) }
    }

/** Recursive name search under [scope] (max 500 hits). Badge = folder relative to the scope. */
private suspend fun searchFiles(scope: File, query: String, showHidden: Boolean): List<FileItem> =
    scanStorage(
        scope,
        match = { (showHidden || !it.isHiddenEntry()) && it.name.contains(query, ignoreCase = true) },
        limit = 500,
        descend = { showHidden || !it.isHiddenEntry() }
    ).map { file ->
        val where = file.parentFile?.path.orEmpty().removePrefix(scope.path).trimStart('/')
        FileItem.from(file).copy(origin = where.ifEmpty { "/" })
    }.sortedForList()

private fun List<FileItem>.sortedForList(): List<FileItem> =
    sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

private fun loadFiles(path: String, mimeFilter: String?, showHidden: Boolean): List<FileItem> {
    if (Vfs.isSmb(path)) {
        // may throw (unreachable / access denied): the caller shows the error
        return Vfs.list(path)
            .filter { showHidden || !it.name.startsWith(".") }
            .map { FileItem.from(it) }
            .filter { it.isDirectory || matchesMime(it.name, mimeFilter) }
            .sortedForList()
    }
    val dir = File(path)
    if (!dir.exists() || !dir.isDirectory) return emptyList()
    return dir.listFiles()
        ?.filter { showHidden || !it.isHiddenEntry() }
        ?.map { FileItem.from(it) }
        ?.filter { item ->
            if (item.isDirectory) true else matchesMime(item.name, mimeFilter)
        }
        ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        ?: emptyList()
}

private enum class DocumentKind { PDF, TEXT }

private fun isImagePath(path: String): Boolean =
    File(path).extension.lowercase().let { it in IMAGE_EXTS && it != "svg" && it != "ico" && it != "tiff" && it != "tif" }

private fun isPdfPath(path: String): Boolean =
    File(path).extension.equals("pdf", ignoreCase = true)

private fun isTextPath(path: String): Boolean {
    val ext = File(path).extension.lowercase()
    return ext in TEXT_EXTS
}

private val IMAGE_EXTS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "ico", "tiff", "tif"
)
private val VIDEO_EXTS = setOf(
    "mp4", "mkv", "avi", "webm", "3gp", "mov", "wmv", "flv", "m4v", "ts", "m2ts"
)
private val AUDIO_EXTS = setOf(
    "mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus", "amr"
)
private val TEXT_EXTS = setOf(
    "txt", "log", "md", "csv", "json", "xml", "html", "htm", "css", "js", "kt", "java", "py", "sh", "cfg", "ini", "properties"
)

private fun matchesMime(fileName: String, mimeFilter: String?): Boolean {
    if (mimeFilter.isNullOrBlank() || mimeFilter == "*/*") return true
    val ext = fileName.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) return false
    val filter = mimeFilter.lowercase().trim()
    val normalized = when {
        filter.startsWith("images/") || filter == "images/*" -> "image/*"
        filter.startsWith("videos/") || filter == "videos/*" -> "video/*"
        filter.startsWith("audios/") || filter == "audios/*" -> "audio/*"
        else -> filter
    }
    val byExt = when {
        normalized.startsWith("image/") -> ext in IMAGE_EXTS
        normalized.startsWith("video/") -> ext in VIDEO_EXTS
        normalized.startsWith("audio/") -> ext in AUDIO_EXTS
        else -> null
    }
    if (byExt != null) return byExt
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: return false
    return if (normalized.endsWith("/*")) {
        mime.startsWith(normalized.removeSuffix("/*"))
    } else mime.equals(normalized, ignoreCase = true)
}

private fun formatDate(millis: Long): String {
    if (millis == 0L) return "-"
    return SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))
}
