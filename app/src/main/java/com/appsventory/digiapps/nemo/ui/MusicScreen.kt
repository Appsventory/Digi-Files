package com.appsventory.digiapps.nemo.ui

import android.content.ContentUris
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appsventory.digiapps.nemo.R
import com.appsventory.digiapps.nemo.ui.theme.*
import com.appsventory.digiapps.nemo.util.MediaEntry
import com.appsventory.digiapps.nemo.util.MediaKind
import com.appsventory.digiapps.nemo.util.MediaRepo
import com.appsventory.digiapps.nemo.smb.SmbClient
import com.appsventory.digiapps.nemo.util.AppSettings
import com.appsventory.digiapps.nemo.util.MusicPlayer
import com.appsventory.digiapps.nemo.util.isSmb
import com.appsventory.digiapps.nemo.util.isTelevision
import android.widget.Toast
import androidx.compose.material.icons.filled.Refresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext

/** Music: Songs / Albums / Artists lists + a mini player. */
@Composable
fun MusicScreen(player: MusicPlayer, onShowInFolder: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val askNotifications = rememberNotificationPermissionRequest()
    var songs by remember { mutableStateOf<List<MediaEntry>?>(null) }
    var refreshingSmb by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Local (MediaStore) + a cached SMB scan - see SmbMediaCache for why this doesn't re-scan
    // the network every time this screen opens.
    // also reloads by itself when MediaStore changes (new download / drive scanned)
    val mediaTick = rememberMediaStoreTick()
    var reloadTick by remember { mutableIntStateOf(0) } // bumped after a delete from the long-press menu
    LaunchedEffect(mediaTick, reloadTick) {
        if (mediaTick > 0) delay(800)
        songs = MediaRepo.loadCombined(context, MediaKind.AUDIO)
    }
    val hasExtraSources = AppSettings.enabledMediaSources.isNotEmpty()

    val isTv = remember { context.isTelevision() }
    // saved, so a refresh keeps you in the same tab / album / artist
    var tab by rememberSaveable { mutableStateOf(0) } // 0 songs, 1 albums, 2 artists
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var lastGroup by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(group) {
        if (isTv && group != null) {
            delay(120)
            runCatching { firstFocus.requestFocus() }
        }
    }
    BackHandler(enabled = group != null) { group = null }
    val all = songs

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChipTabs(
                modifier = Modifier.weight(1f),
                autoSelectOnFocus = remember { context.isTelevision() },
                labels = listOf(
                    stringResource(R.string.media_tab_songs),
                    stringResource(R.string.media_tab_albums),
                    stringResource(R.string.media_tab_artists)
                ),
                selected = tab,
                onSelect = {
                    tab = it
                    group = null
                }
            )
            // Only shown once a server is saved - scanning is on demand, never automatic.
            if (hasExtraSources) {
                TopBarButton(
                    onClick = {
                        scope.launch {
                            refreshingSmb = true
                            songs = MediaRepo.loadCombined(context, MediaKind.AUDIO, forceRefresh = true)
                            refreshingSmb = false
                        }
                    },
                    modifier = Modifier.size(40.dp).padding(end = 8.dp)
                ) { focused ->
                    if (refreshingSmb) CircularProgressIndicator(Modifier.size(18.dp), color = CyanAccent, strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh), tint = if (focused) OnAccent else TextSecondary)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                all == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CyanAccent)
                }
                all.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.list_empty), color = TextMuted, fontSize = 14.sp)
                }
                group != null -> SongList(
                    onPlay = askNotifications,
                    firstFocus = firstFocus,
                    list = all.filter { (if (tab == 1) it.group else it.artist) == group },
                    player = player,
                    header = group,
                    onShowInFolder = onShowInFolder,
                    onChanged = { reloadTick++ }
                )
                tab == 0 -> SongList(all, player, header = null, onPlay = askNotifications, firstFocus = null, onShowInFolder = onShowInFolder, onChanged = { reloadTick++ })
                tab == 1 -> GroupList(all.groupBy { it.group }.toList(), if (isTv) lastGroup else null, { lastGroup = null }) {
                    lastGroup = it
                    group = it
                }
                else -> GroupList(all.groupBy { it.artist }.toList(), if (isTv) lastGroup else null, { lastGroup = null }) {
                    lastGroup = it
                    group = it
                }
            }
        }
        MiniPlayer(player)
    }
}

@Composable
private fun SongList(
    list: List<MediaEntry>,
    player: MusicPlayer,
    header: String?,
    onPlay: () -> Unit,
    firstFocus: FocusRequester?,
    onShowInFolder: ((String) -> Unit)?,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloadingId by remember { mutableStateOf<Long?>(null) }
    // long-press menu + select multiple (see MediaActions.kt)
    val actions = rememberMediaActions()
    val selection = actions.selection

    fun play(entry: MediaEntry, index: Int) {
        onPlay()
        if (entry.isSmb) {
            // played on its own (not mixed into the queue) - the rest of the
            // queue may not be downloaded yet, so Next/Previous stay predictable
            scope.launch {
                downloadingId = entry.id
                val local = downloadSmbSong(context, entry)
                downloadingId = null
                if (local != null) player.play(listOf(local), 0)
                else Toast.makeText(context, R.string.toast_smb_failed, Toast.LENGTH_SHORT).show()
            }
        } else {
            player.play(list, index)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().focusGroup()
        ) {
            header?.let {
                item {
                    Text(it, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            itemsIndexed(list, key = { _, e -> e.id }) { index, entry ->
                val isCurrent = player.current?.id == entry.id
                val marked = selection.isMarked(entry.id)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                        .focusCard(
                            corner = dimensionResource(R.dimen.row_corner),
                            marked = marked,
                            onLongClick = { actions.onLongPress(entry) },
                            onClick = { actions.onClick(entry) { play(entry, index) } }
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AudioArt(entry, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.title,
                            color = if (isCurrent) CyanAccent else TextPrimary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${entry.artist}  ·  ${formatDuration(entry.durationMs)}",
                            color = TextMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (marked) {
                        Icon(Icons.Default.CheckCircle, null, tint = CyanAccent, modifier = Modifier.size(20.dp))
                    } else if (downloadingId == entry.id) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = CyanAccent, strokeWidth = 2.dp)
                    } else if (isCurrent && player.playing) {
                        Icon(Icons.Default.GraphicEq, null, tint = CyanAccent, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        MediaActionsUi(
            actions = actions,
            visible = list,
            openLabel = R.string.media_action_play,
            openIcon = Icons.Default.PlayArrow,
            onOpen = { e -> play(e, list.indexOfFirst { it.id == e.id }.coerceAtLeast(0)) },
            // don't leave a deleted song playing
            onBeforeDelete = { gone -> if (gone.any { it.id == player.current?.id }) player.stopAndClear() },
            onShowInFolder = onShowInFolder,
            onChanged = onChanged
        )
    }
}

@Composable
private fun GroupList(
    groups: List<Pair<String, List<MediaEntry>>>,
    focusGroup: String?,
    onFocusHandled: () -> Unit,
    onOpen: (String) -> Unit
) {
    val resources = LocalContext.current.resources
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    // back from an album / artist: focus returns to it
    LaunchedEffect(focusGroup) {
        if (focusGroup != null) {
            val index = groups.indexOfFirst { it.first == focusGroup }
            if (index >= 0) {
                listState.scrollToItem(index)
                delay(120)
                runCatching { requesters[focusGroup]?.requestFocus() }
            }
            onFocusHandled()
        }
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().focusGroup()
    ) {
        items(groups, key = { it.first }) { (name, songs) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .focusRequester(requesters.getOrPut(name) { FocusRequester() })
                    .focusCard(corner = dimensionResource(R.dimen.row_corner), onClick = { onOpen(name) })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AudioArt(songs.first(), 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, color = TextPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        resources.getQuantityString(R.plurals.item_count, songs.size, songs.size),
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(player: MusicPlayer) {
    val current = player.current ?: return
    LaunchedEffect(player.playing) {
        while (player.playing) {
            player.refresh()
            delay(500)
        }
    }
    Column(Modifier.fillMaxWidth().background(CardDark)) {
        LinearProgressIndicator(
            progress = { if (player.durationMs > 0) (player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = CyanAccent,
            trackColor = DividerDark
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AudioArt(current, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(current.title, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(current.artist, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PlayerButton(Icons.Default.SkipPrevious, R.string.cd_previous) { player.previous() }
            PlayerButton(
                if (player.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (player.playing) R.string.cd_pause else R.string.cd_play
            ) { player.toggle() }
            PlayerButton(Icons.Default.SkipNext, R.string.cd_next) { player.next() }
        }
    }
}

@Composable
private fun PlayerButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    @androidx.annotation.StringRes description: Int,
    onClick: () -> Unit
) {
    TopBarButton(onClick = onClick, modifier = Modifier.size(44.dp)) { focused ->
        Icon(
            icon,
            contentDescription = stringResource(description),
            tint = if (focused) OnAccent else TextPrimary
        )
    }
}

private val artCache = LruCache<Long, ImageBitmap>(300)

/** Album art (cached per album), or a note icon when there is none. */
@Composable
fun AudioArt(entry: MediaEntry, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState(artCache.get(entry.albumId), entry.albumId) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { loadArt(context, entry, px) }
                ?.also { artCache.put(entry.albumId, it) }
        }
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(DividerDark),
        contentAlignment = Alignment.Center
    ) {
        val art = bitmap
        if (art != null) {
            Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Default.MusicNote, null, tint = TextMuted, modifier = Modifier.size(size / 2))
        }
    }
}

private fun loadArt(context: android.content.Context, entry: MediaEntry, px: Int): ImageBitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.contentResolver.loadThumbnail(entry.uri, Size(px * 2, px * 2), null).asImageBitmap()
    } else {
        val art = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), entry.albumId)
        context.contentResolver.openInputStream(art)?.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
    }
}.getOrNull()

/** Downloads one SMB song to the app cache so MediaPlayer (no network support) can play it. */
private suspend fun downloadSmbSong(context: android.content.Context, entry: MediaEntry): MediaEntry? {
    val path = entry.path ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val file = SmbClient.downloadToCache(context, path)
            entry.copy(uri = Uri.fromFile(file))
        }.getOrNull()
    }
}
