package com.appsventory.digiapps.nemo.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.appsventory.digiapps.nemo.R
import com.appsventory.digiapps.nemo.ui.theme.*
import com.appsventory.digiapps.nemo.update.AssetDownloadState
import com.appsventory.digiapps.nemo.update.DownloadFailure
import com.appsventory.digiapps.nemo.update.GitHubAsset
import com.appsventory.digiapps.nemo.update.GitHubRelease
import com.appsventory.digiapps.nemo.update.GitHubReleaseApi
import com.appsventory.digiapps.nemo.update.MdBlock
import com.appsventory.digiapps.nemo.update.ReleaseConfig
import com.appsventory.digiapps.nemo.update.ReleaseMarkdown
import com.appsventory.digiapps.nemo.update.UpdateDownloader
import com.appsventory.digiapps.nemo.update.VersionRelation
import com.appsventory.digiapps.nemo.update.assetKey
import com.appsventory.digiapps.nemo.update.compareInstalledToRemote
import com.appsventory.digiapps.nemo.update.isInstallable
import com.appsventory.digiapps.nemo.update.suitableDownloadAssets
import com.appsventory.digiapps.nemo.util.FileOpener
import com.appsventory.digiapps.nemo.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Phone / standalone activity: full-screen with back. */
@Composable
fun UpdateScreen(title: String, onBack: () -> Unit) {
    UpdateShell(
        title = title,
        onBack = onBack,
        isWide = false,
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .navigationBarsPadding()
    )
}

/**
 * Large screens / TV: content panel beside the persistent sidebar.
 * Matches [SettingsPage] — does not replace or hide the rail.
 */
@Composable
fun UpdatePage(onBack: () -> Unit) {
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    UpdateShell(
        title = stringResource(R.string.update_title),
        onBack = onBack,
        isWide = wide,
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    )
}

private sealed interface CheckState {
    data object Loading : CheckState
    data class Ready(
        val release: GitHubRelease,
        val relation: VersionRelation,
        val suitable: List<GitHubAsset>
    ) : CheckState
    data object NoReleases : CheckState
    data class Error(val message: String) : CheckState
}

@Composable
private fun UpdateShell(
    title: String,
    onBack: () -> Unit,
    isWide: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appName = stringResource(R.string.app_name)
    val installedVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    var state by remember { mutableStateOf<CheckState>(CheckState.Loading) }
    var showAssetPicker by remember { mutableStateOf(false) }
    var showInstallPermission by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var requestSeq by remember { mutableIntStateOf(0) }
    var fetchJob by remember { mutableStateOf<Job?>(null) }
    // The asset the primary action currently refers to (survives rotation).
    var chosenAssetName by rememberSaveable { mutableStateOf<String?>(null) }

    val downloadStates by UpdateDownloader.states.collectAsState()

    val isLoading = state is CheckState.Loading
    val releaseUrl = (state as? CheckState.Ready)?.release?.htmlUrl

    fun check() {
        if (fetchJob?.isActive == true && isLoading) return
        fetchJob?.cancel()
        val seq = ++requestSeq
        state = CheckState.Loading
        menuOpen = false
        fetchJob = scope.launch {
            val result = withContext(Dispatchers.IO) { GitHubReleaseApi.fetchLatest() }
            if (seq != requestSeq) return@launch
            state = when (result) {
                is GitHubReleaseApi.Result.Success -> {
                    val relation = compareInstalledToRemote(installedVersion, result.release.tagName)
                    val suitable = suitableDownloadAssets(result.release.assets)
                    CheckState.Ready(result.release, relation, suitable)
                }
                GitHubReleaseApi.Result.NoReleases -> CheckState.NoReleases
                is GitHubReleaseApi.Result.HttpError ->
                    CheckState.Error(
                        if (result.code == 429) context.getString(R.string.update_error_rate_limit)
                        else context.getString(R.string.update_error_http, result.code)
                    )
                is GitHubReleaseApi.Result.NetworkError ->
                    CheckState.Error(context.getString(R.string.update_error_network))
                is GitHubReleaseApi.Result.ParseError ->
                    CheckState.Error(context.getString(R.string.update_error_parse))
            }
        }
    }

    LaunchedEffect(Unit) { check() }

    val ready = state as? CheckState.Ready

    // Work out the real local state of every candidate asset (file on disk + DownloadManager),
    // never trusting an in-memory flag: on open …
    LaunchedEffect(ready?.release?.tagName, ready?.suitable) {
        val r = ready ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            r.suitable.forEach { UpdateDownloader.refresh(context, r.release.tagName, it) }
        }
    }
    // … and whenever the screen comes back (e.g. file deleted meanwhile, or returning from Settings/installer).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val r = ready ?: return@LifecycleEventEffect
        scope.launch(Dispatchers.IO) {
            r.suitable.forEach { UpdateDownloader.refresh(context, r.release.tagName, it) }
        }
    }

    fun stateOf(tag: String, asset: GitHubAsset): AssetDownloadState =
        downloadStates[assetKey(tag, asset)] ?: AssetDownloadState.NotDownloaded

    // Which asset does the primary action refer to?
    val activeAsset: GitHubAsset? = ready?.let { r ->
        val tag = r.release.tagName
        r.suitable.firstOrNull { it.name == chosenAssetName }
            ?: r.suitable.firstOrNull { stateOf(tag, it) is AssetDownloadState.Downloading }
            ?: r.suitable.firstOrNull { stateOf(tag, it) is AssetDownloadState.Downloaded }
            ?: r.suitable.firstOrNull { stateOf(tag, it) is AssetDownloadState.Failed }
            ?: r.suitable.singleOrNull()
    }
    val activeState: AssetDownloadState =
        if (ready != null && activeAsset != null) stateOf(ready.release.tagName, activeAsset)
        else AssetDownloadState.NotDownloaded
    val actionModel = ActionModel(
        state = activeState,
        asset = activeAsset,
        noAssets = ready?.suitable?.isEmpty() == true,
        canChange = (ready?.suitable?.size ?: 0) > 1 && activeState !is AssetDownloadState.Downloading
    )

    fun startDownload(asset: GitHubAsset) {
        val r = ready ?: return
        chosenAssetName = asset.name
        // UpdateDownloader refuses a second concurrent download, so repeated taps are harmless.
        if (!UpdateDownloader.start(context, r.release.tagName, asset) && !UpdateDownloader.isAnyDownloading()) {
            Toast.makeText(context, R.string.update_download_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    fun installOrOpen(asset: GitHubAsset) {
        val r = ready ?: return
        scope.launch {
            // Re-verify at the moment of use: the file may have been deleted since it was downloaded.
            val fresh = withContext(Dispatchers.IO) {
                UpdateDownloader.refresh(context, r.release.tagName, asset)
                UpdateDownloader.states.value[assetKey(r.release.tagName, asset)]
            }
            if (fresh !is AssetDownloadState.Downloaded) {
                Toast.makeText(context, R.string.update_file_missing, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (asset.isInstallable) {
                if (FileOpener.canInstallPackages(context)) FileOpener.installApk(context, fresh.path)
                else showInstallPermission = true
            } else {
                // never run a non-APK through the package installer
                FileOpener.open(context, fresh.path)
            }
        }
    }

    fun onPrimary() {
        val r = ready ?: return
        when {
            r.suitable.isEmpty() -> {
                Toast.makeText(context, R.string.update_no_assets, Toast.LENGTH_SHORT).show()
                r.release.htmlUrl?.let { openUrl(context, it) }
            }
            activeAsset == null -> showAssetPicker = true // several files: never pick one silently
            else -> when (activeState) {
                is AssetDownloadState.Downloading -> Unit // duplicate taps ignored
                is AssetDownloadState.Downloaded -> installOrOpen(activeAsset)
                AssetDownloadState.NotDownloaded,
                is AssetDownloadState.Failed -> startDownload(activeAsset)
            }
        }
    }

    Column(modifier) {
        // Top bar (fixed): back · title · overflow menu
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isWide) 16.dp else 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TopBarButton(onClick = onBack, modifier = Modifier.size(40.dp)) { focused ->
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = if (focused) OnAccent else TextSecondary
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
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
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.update_view_on_github)) },
                        leadingIcon = { Icon(Icons.Default.OpenInNew, null) },
                        enabled = !releaseUrl.isNullOrBlank(),
                        colors = MenuDefaults.itemColors(
                            textColor = TextPrimary,
                            leadingIconColor = TextSecondary,
                            disabledTextColor = TextMuted,
                            disabledLeadingIconColor = TextMuted
                        ),
                        onClick = {
                            menuOpen = false
                            releaseUrl?.let { openUrl(context, it) }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_retry)) },
                        leadingIcon = { Icon(Icons.Default.Refresh, null) },
                        enabled = !isLoading,
                        colors = MenuDefaults.itemColors(
                            textColor = TextPrimary,
                            leadingIconColor = TextSecondary,
                            disabledTextColor = TextMuted,
                            disabledLeadingIconColor = TextMuted
                        ),
                        onClick = {
                            menuOpen = false
                            check()
                        }
                    )
                }
            }
        }

        when (val s = state) {
            CheckState.Loading -> LoadingBlock(Modifier.weight(1f).fillMaxWidth())
            is CheckState.Ready -> ReadyLayout(
                isWide = isWide,
                appName = appName,
                installedVersion = installedVersion,
                release = s.release,
                relation = s.relation,
                suitableCount = s.suitable.size,
                action = actionModel,
                onPrimary = { onPrimary() },
                onCancel = { activeAsset?.let { UpdateDownloader.cancel(context, s.release.tagName, it) } },
                onChangeFile = { showAssetPicker = true },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
            CheckState.NoReleases -> MessageBlock(
                icon = Icons.Default.Info,
                title = stringResource(R.string.update_no_releases_title),
                body = stringResource(R.string.update_no_releases_body),
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
            is CheckState.Error -> MessageBlock(
                icon = Icons.Default.ErrorOutline,
                title = stringResource(R.string.update_error_title),
                body = s.message,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }
    }

    if (showAssetPicker && ready != null) {
        AssetPickerDialog(
            assets = ready.suitable,
            onConfirm = { asset ->
                showAssetPicker = false
                chosenAssetName = asset.name
                // already have a verified copy of this file → just switch to Install Now, no re-download
                val existing = stateOf(ready.release.tagName, asset)
                if (existing is AssetDownloadState.NotDownloaded || existing is AssetDownloadState.Failed) {
                    startDownload(asset)
                }
            },
            onDismiss = { showAssetPicker = false }
        )
    }

    if (showInstallPermission) {
        AlertDialog(
            onDismissRequest = { showInstallPermission = false },
            containerColor = CardDark,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text(stringResource(R.string.update_install_permission_title)) },
            text = { Text(stringResource(R.string.update_install_permission_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showInstallPermission = false
                    FileOpener.openInstallPermissionSettings(context)
                }) { Text(stringResource(R.string.update_install_permission_open), color = CyanAccent) }
            },
            dismissButton = {
                TextButton(onClick = { showInstallPermission = false }) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            }
        )
    }
}

// ───────────────────────────── primary action model ─────────────────────────────

private data class ActionModel(
    val state: AssetDownloadState,
    /** The asset the action currently refers to; null until the user has chosen among several. */
    val asset: GitHubAsset?,
    /** Release has no installable asset: the action opens the release page instead. */
    val noAssets: Boolean,
    /** More than one file exists and nothing is downloading, so "Change file" is offered. */
    val canChange: Boolean
)

private fun small(): Dp = 3.dp // focus-ring inset of [FocusRingButton]

// ───────────────────────────── ready layout ─────────────────────────────

/**
 * Layout + focus structure of the loaded screen.
 *
 * Root cause of "Down from Download lands in the sidebar": nothing below the Download button was
 * focusable (release notes are plain text in a scroll container), so Compose's directional search fell
 * through to the sidebar items that sit lower on screen. Fix: the notes card is itself a focus target,
 * Download's `down` is an explicit [FocusRequester] to it, and the notes card owns Up/Down so scrolling,
 * the hand-back to Download at the top, and "stay put at the bottom" are all explicit.
 */
@Composable
private fun ReadyLayout(
    isWide: Boolean,
    appName: String,
    installedVersion: String,
    release: GitHubRelease,
    relation: VersionRelation,
    suitableCount: Int,
    action: ActionModel,
    onPrimary: () -> Unit,
    onCancel: () -> Unit,
    onChangeFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showDownload = when (relation) {
        VersionRelation.NEWER_AVAILABLE -> true
        VersionRelation.UNKNOWN -> suitableCount > 0
        else -> false
    }
    val blocks = remember(release.body) { ReleaseMarkdown.parse(release.body) }
    val hasNotes = blocks.isNotEmpty()

    val downloadFocus = remember { FocusRequester() }
    val notesFocus = remember { FocusRequester() }

    if (isWide) {
        // Tablet / TV: [ header (weighted) | action ] · release info card · release notes card
        Column(
            modifier
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // header: takes everything the action does not need
                HeaderInfo(
                    release = release,
                    appName = appName,
                    installedVersion = installedVersion,
                    wide = true,
                    modifier = Modifier.weight(1f).padding(top = 4.dp)
                )
                if (showDownload) {
                    WideActionArea(
                        action = action,
                        downloadFocus = downloadFocus,
                        // Down: notes if there are any, otherwise stay (never leak into the sidebar)
                        downTarget = if (hasNotes) notesFocus else FocusRequester.Cancel,
                        onPrimary = onPrimary,
                        onCancel = onCancel,
                        onChangeFile = onChangeFile
                    )
                }
            }
            ReleaseInfoCard(release, relation)
            if (hasNotes) {
                Text(
                    stringResource(R.string.update_release_notes),
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                ReleaseNotesCard(
                    blocks = blocks,
                    notesFocus = notesFocus,
                    upTarget = if (showDownload) downloadFocus else null,
                    bottomTarget = null, // bottom of the notes: stay, do not jump to the sidebar
                    bottomPadding = 4.dp,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    } else {
        // Phone: fixed header · scrollable notes · fixed bottom action
        Column(modifier.focusGroup()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HeaderInfo(
                    release = release,
                    appName = appName,
                    installedVersion = installedVersion,
                    wide = false,
                    modifier = Modifier.fillMaxWidth()
                )
                ReleaseInfoCard(release, relation)
                if (hasNotes) {
                    Text(
                        stringResource(R.string.update_release_notes),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (hasNotes) {
                ReleaseNotesCard(
                    blocks = blocks,
                    notesFocus = notesFocus,
                    upTarget = null,
                    // external keyboard on a phone: Down at the very bottom continues to the action
                    bottomTarget = if (showDownload) downloadFocus else null,
                    // the action bar sits below this region, but keep the last lines clear anyway
                    bottomPadding = if (showDownload) 16.dp else 8.dp,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (showDownload) {
                PhoneActionBar(
                    action = action,
                    downloadFocus = downloadFocus,
                    upTarget = if (hasNotes) notesFocus else null,
                    onPrimary = onPrimary,
                    onCancel = onCancel,
                    onChangeFile = onChangeFile
                )
            }
        }
    }
}

@Composable
private fun HeaderInfo(
    release: GitHubRelease,
    appName: String,
    installedVersion: String,
    wide: Boolean,
    modifier: Modifier = Modifier
) {
    val latest = release.tagName.trimStart('v', 'V')
    val title = release.name?.takeIf { it.isNotBlank() } ?: "$appName $latest"
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            color = TextPrimary,
            fontSize = if (wide) 22.sp else 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            stringResource(R.string.update_installed_version, installedVersion.ifBlank { "—" }) +
                "  ·  " + stringResource(R.string.update_latest_version, latest),
            color = TextSecondary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            stringResource(R.string.update_source, "${ReleaseConfig.owner}/${ReleaseConfig.repo}"),
            color = TextMuted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Status of this release vs the installed app. Deliberately does not repeat the title/version/source. */
@Composable
private fun ReleaseInfoCard(release: GitHubRelease, relation: VersionRelation) {
    val statusText: String
    val statusColor: Color
    when (relation) {
        VersionRelation.NEWER_AVAILABLE -> {
            statusText = stringResource(R.string.update_available, release.tagName.trimStart('v', 'V'))
            statusColor = CyanAccent
        }
        VersionRelation.UP_TO_DATE -> {
            statusText = stringResource(R.string.update_up_to_date)
            statusColor = TextSecondary
        }
        VersionRelation.OLDER_OR_EQUAL -> {
            statusText = stringResource(R.string.update_newer_installed)
            statusColor = TextSecondary
        }
        VersionRelation.UNKNOWN -> {
            statusText = stringResource(R.string.update_version_unknown)
            statusColor = TextMuted
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimensionResource(R.dimen.row_corner)))
            .background(CardDark)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            when (relation) {
                VersionRelation.NEWER_AVAILABLE -> Icons.Default.SystemUpdate
                VersionRelation.UP_TO_DATE -> Icons.Default.CheckCircle
                else -> Icons.Default.Info
            },
            contentDescription = null,
            tint = statusColor,
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(statusText, color = statusColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            formatPublished(release.publishedAt)?.let {
                Text(
                    stringResource(R.string.update_published, it),
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ───────────────────────────── release notes card (focusable + scrolling) ─────────────────────────────

/**
 * The only scrolling region. It is a real focus target so D-pad can reach it, and it handles
 * Up/Down itself:
 *  - Down: scroll while more content exists; at the bottom either continue to [bottomTarget]
 *    (phone) or consume the key so focus can never leak to the sidebar.
 *  - Up: scroll while not at the top; at the very top hand focus to [upTarget] (the Download button).
 *    With no [upTarget] the key is left unhandled so the top bar can take it.
 *  - Left/Right are untouched, so moving toward the sidebar stays a deliberate Left press.
 * Focusing the card never changes the scroll offset.
 */
@Composable
private fun ReleaseNotesCard(
    blocks: List<MdBlock>,
    notesFocus: FocusRequester,
    upTarget: FocusRequester?,
    bottomTarget: FocusRequester?,
    bottomPadding: Dp,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val stepPx = with(LocalDensity.current) { 96.dp.toPx() }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val corner = dimensionResource(R.dimen.row_corner)
    val label = stringResource(R.string.cd_release_notes)

    Column(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .then(if (focused) Modifier.focusGlow(corner) else Modifier)
            .clip(RoundedCornerShape(corner))
            .background(CardDark)
            // outer → inner: key handling first, then the focus target itself
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> {
                        if (scroll.canScrollForward) {
                            scope.launch { scroll.animateScrollBy(stepPx) }
                        } else if (bottomTarget != null) {
                            runCatching { bottomTarget.requestFocus() }
                        }
                        true // always consumed: never falls through to a neighbouring panel
                    }
                    Key.DirectionUp -> {
                        if (scroll.value > 0) {
                            scope.launch { scroll.animateScrollBy(-stepPx) }
                            true
                        } else if (upTarget != null) {
                            runCatching { upTarget.requestFocus() }
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
            .focusRequester(notesFocus)
            .focusable(interactionSource = source)
            .semantics { contentDescription = label }
            .verticalScroll(scroll)
            .padding(14.dp)
    ) {
        ReleaseNotesContent(blocks)
        Spacer(Modifier.height(bottomPadding))
    }
}

// ───────────────────────────── action area ─────────────────────────────

/** Tablet / TV: [Cancel | Change file] [primary] with a caption below. Primary keeps a fixed width. */
@Composable
private fun WideActionArea(
    action: ActionModel,
    downloadFocus: FocusRequester,
    downTarget: FocusRequester,
    onPrimary: () -> Unit,
    onCancel: () -> Unit,
    onChangeFile: () -> Unit
) {
    val downloading = action.state is AssetDownloadState.Downloading
    val sideProps = Modifier.focusProperties { down = downTarget }
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Left of the primary button so the primary never moves or resizes.
            if (downloading) {
                SecondaryChip(stringResource(R.string.update_cancel_download), onCancel, sideProps, 44.dp)
            } else if (action.canChange) {
                SecondaryChip(stringResource(R.string.update_change_file), onChangeFile, sideProps, 44.dp)
            }
            PrimaryActionButton(
                model = action,
                onClick = onPrimary,
                height = 52.dp,
                modifier = Modifier
                    .width(216.dp)
                    .focusRequester(downloadFocus)
                    .focusProperties { down = downTarget }
            )
        }
        ActionCaption(action, Modifier.widthIn(max = 320.dp), TextAlign.End, maxLines = 2)
    }
}

/** Phone: fixed at the bottom, full width, inside the safe area. */
@Composable
private fun PhoneActionBar(
    action: ActionModel,
    downloadFocus: FocusRequester,
    upTarget: FocusRequester?,
    onPrimary: () -> Unit,
    onCancel: () -> Unit,
    onChangeFile: () -> Unit
) {
    val downloading = action.state is AssetDownloadState.Downloading
    val upProps = Modifier.focusProperties { upTarget?.let { up = it } }
    Column(
        Modifier
            .fillMaxWidth()
            .background(SurfaceDark)
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Reserved caption row → the bar never changes height between states.
        Row(
            Modifier.fillMaxWidth().heightIn(min = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionCaption(action, Modifier.weight(1f).padding(horizontal = 4.dp), TextAlign.Start, maxLines = 1)
            if (downloading) {
                SecondaryChip(stringResource(R.string.update_cancel_download), onCancel, upProps, 36.dp)
            } else if (action.canChange) {
                SecondaryChip(stringResource(R.string.update_change_file), onChangeFile, upProps, 36.dp)
            }
        }
        PrimaryActionButton(
            model = action,
            onClick = onPrimary,
            height = 56.dp,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(downloadFocus)
                .then(upProps)
        )
    }
}

@Composable
private fun ActionCaption(
    action: ActionModel,
    modifier: Modifier,
    align: TextAlign,
    maxLines: Int
) {
    val st = action.state
    var isError = false
    val text: String = when (st) {
        is AssetDownloadState.Downloading -> when {
            st.waiting -> stringResource(R.string.update_waiting_network)
            st.totalBytes > 0 -> stringResource(R.string.update_bytes_progress, formatBytes(st.downloadedBytes), formatBytes(st.totalBytes))
            else -> stringResource(R.string.update_bytes_so_far, formatBytes(st.downloadedBytes))
        }
        is AssetDownloadState.Downloaded -> stringResource(R.string.update_ready_caption, formatBytes(st.sizeBytes))
        is AssetDownloadState.Failed -> {
            isError = true
            stringResource(
                when (st.reason) {
                    DownloadFailure.NETWORK -> R.string.update_failed_network
                    DownloadFailure.STORAGE -> R.string.update_failed_storage
                    DownloadFailure.HTTP -> R.string.update_failed_http
                    DownloadFailure.INCOMPLETE -> R.string.update_failed_incomplete
                    DownloadFailure.UNKNOWN -> R.string.update_failed_unknown
                }
            )
        }
        AssetDownloadState.NotDownloaded -> action.asset?.let { a ->
            if (a.size > 0) "${a.name}  ·  ${formatBytes(a.size)}" else a.name
        }.orEmpty()
    }
    // Always composed (even when empty) so the area keeps its height.
    Text(
        text,
        color = if (isError) ErrorRed else TextMuted,
        fontSize = 12.sp,
        textAlign = align,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** Download / Downloading n% / Install Now / Open / Retry — one stable-size button for every device. */
@Composable
private fun PrimaryActionButton(
    model: ActionModel,
    onClick: () -> Unit,
    height: Dp,
    modifier: Modifier = Modifier
) {
    val st = model.state
    val installable = model.asset?.isInstallable != false

    val label: String
    val icon: ImageVector
    val container: Color
    val contentColor: Color
    when {
        model.noAssets -> {
            label = stringResource(R.string.update_open_release_page)
            icon = Icons.Default.OpenInNew
            container = CyanAccent.copy(alpha = 0.18f)
            contentColor = CyanAccent
        }
        st is AssetDownloadState.Downloading -> {
            label = when {
                st.waiting -> stringResource(R.string.update_waiting_network)
                st.percent != null -> stringResource(R.string.update_downloading_pct, st.percent!!)
                else -> stringResource(R.string.update_downloading_plain)
            }
            icon = Icons.Default.CloudDownload // replaced by the spinner below
            container = CyanAccent.copy(alpha = 0.18f)
            contentColor = TextPrimary
        }
        st is AssetDownloadState.Downloaded -> {
            label = stringResource(if (installable) R.string.update_install_now else R.string.update_open_file)
            icon = if (installable) Icons.Default.SystemUpdate else Icons.Default.OpenInNew
            container = CyanAccent
            contentColor = OnAccent
        }
        st is AssetDownloadState.Failed -> {
            label = stringResource(R.string.update_retry_download)
            icon = Icons.Default.Refresh
            container = ErrorRed.copy(alpha = 0.18f)
            contentColor = ErrorRed
        }
        else -> {
            label = stringResource(R.string.update_download)
            icon = Icons.Default.CloudDownload
            container = CyanAccent
            contentColor = OnAccent
        }
    }

    FocusRingButton(
        modifier = modifier.height(height),
        corner = 12.dp,
        container = container,
        // stays focusable while downloading so D-pad focus is not lost; clicks are ignored in that state
        onClick = onClick
    ) {
        // determinate fill behind the label, only when a reliable fraction exists
        if (st is AssetDownloadState.Downloading) {
            st.fraction?.let { f ->
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(f)
                        .background(CyanAccent.copy(alpha = 0.45f))
                )
            }
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                if (st is AssetDownloadState.Downloading && !model.noAssets) {
                    val f = st.fraction
                    if (f != null) {
                        CircularProgressIndicator(
                            progress = { f },
                            color = CyanAccent,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        CircularProgressIndicator(
                            color = CyanAccent,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                color = contentColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SecondaryChip(label: String, onClick: () -> Unit, modifier: Modifier, height: Dp) {
    FocusRingButton(
        modifier = modifier.height(height),
        corner = 10.dp,
        container = CardDark,
        onClick = onClick
    ) {
        Text(
            label,
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 14.dp)
        )
    }
}

/**
 * Shared focusable button. Focus = the app's focus stroke + glow drawn on an outer ring, with the
 * filled container inset by [small] so the ring stays clearly visible even on a solid accent fill.
 * Put focusRequester / focusProperties on [modifier] (they must come before the focus target).
 */
@Composable
private fun FocusRingButton(
    modifier: Modifier,
    corner: Dp,
    container: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val outer = corner + small()
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .then(if (focused) Modifier.focusGlow(outer) else Modifier)
            .clip(RoundedCornerShape(outer))
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(small())
            .clip(RoundedCornerShape(corner))
            .background(container),
        content = content
    )
}

// ───────────────────────────── misc blocks ─────────────────────────────

@Composable
private fun LoadingBlock(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = CyanAccent, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.update_checking), color = TextSecondary, fontSize = 14.sp)
    }
}

@Composable
private fun MessageBlock(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

// ───────────────────────────── asset chooser dialog ─────────────────────────────

/**
 * Shown only when a release has several installable files. Nothing is pre-selected: the user must pick
 * a row (selection ≠ focus) before Download enables. Focus starts on the first row, rows are large
 * focus targets with the app's standard focus glow, and long names wrap instead of overflowing.
 */
@Composable
private fun AssetPickerDialog(
    assets: List<GitHubAsset>,
    onConfirm: (GitHubAsset) -> Unit,
    onDismiss: () -> Unit
) {
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    var selected by remember { mutableStateOf<GitHubAsset?>(null) }
    val firstRow = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstRow.requestFocus() } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceDark)
                .padding(if (wide) 24.dp else 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CyanAccent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.update_select_asset),
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.update_choose_hint),
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp, horizontal = 2.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                assets.forEachIndexed { index, asset ->
                    AssetRow(
                        asset = asset,
                        isSelected = selected == asset,
                        modifier = if (index == 0) Modifier.focusRequester(firstRow) else Modifier,
                        onClick = { selected = asset }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FocusRingButton(
                    modifier = Modifier.height(48.dp).widthIn(min = 104.dp),
                    corner = 12.dp,
                    container = CardDark,
                    onClick = onDismiss
                ) {
                    Text(
                        stringResource(R.string.action_cancel),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                val choice = selected
                FocusRingButton(
                    modifier = Modifier.height(48.dp).widthIn(min = 128.dp),
                    corner = 12.dp,
                    container = if (choice != null) CyanAccent else DividerDark,
                    enabled = choice != null, // nothing is chosen for the user
                    onClick = { choice?.let(onConfirm) }
                ) {
                    Row(
                        Modifier.align(Alignment.Center).padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = if (choice != null) OnAccent else TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.update_choose_confirm),
                            color = if (choice != null) OnAccent else TextMuted,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssetRow(
    asset: GitHubAsset,
    isSelected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val typeDescription = stringResource(if (asset.isApk) R.string.cd_file_type_apk else R.string.cd_file_type_other)
    val selectedLabel = stringResource(R.string.cd_selected)
    Row(
        modifier
            .fillMaxWidth()
            // focus = glow, selected (unfocused) = accent stroke — both from the app's standard card effect
            .focusCard(corner = 14.dp, marked = isSelected, onClick = onClick)
            .background(if (isSelected) CyanAccent.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .heightIn(min = 52.dp)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                selected = isSelected
                contentDescription = if (isSelected) "${asset.name}, $typeDescription, $selectedLabel" else "${asset.name}, $typeDescription"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileTypeBadge(asset)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                asset.name,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis // long names wrap, then ellipsize — never overflow sideways
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val ext = asset.extension.uppercase().ifBlank { "FILE" }
                val size = if (asset.size > 0) formatBytes(asset.size) else stringResource(R.string.update_asset_unknown_size)
                Text(
                    "$ext  ·  $size",
                    color = TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (asset.isApk) {
                    BuildTag(
                        stringResource(if (asset.isDebugBuild) R.string.update_asset_debug else R.string.update_asset_release),
                        highlight = !asset.isDebugBuild
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        SelectionIndicator(isSelected)
    }
}

@Composable
private fun FileTypeBadge(asset: GitHubAsset) {
    val ext = asset.extension
    val isArchive = ext in setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz")
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (asset.isApk) CyanAccent.copy(alpha = 0.15f) else DividerDark),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            when {
                asset.isApk -> Icons.Default.Android
                isArchive -> Icons.Default.FolderZip
                else -> Icons.Default.Description
            },
            contentDescription = null,
            tint = if (asset.isApk) CyanAccent else TextSecondary,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun BuildTag(text: String, highlight: Boolean) {
    Text(
        text,
        color = if (highlight) CyanAccent else TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (highlight) CyanAccent.copy(alpha = 0.15f) else DividerDark)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

@Composable
private fun SelectionIndicator(isSelected: Boolean) {
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (isSelected) CyanAccent else Color.Transparent)
            .then(if (isSelected) Modifier else Modifier.border(2.dp, TextMuted, CircleShape)),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = OnAccent, modifier = Modifier.size(16.dp))
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun formatPublished(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return runCatching {
        val core = iso.substringBefore(".").removeSuffix("Z") + "Z"
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val date = parser.parse(core) ?: return null
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)
    }.getOrNull()
}
