package com.appsventory.digiapps.nemo.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.appsventory.digiapps.nemo.R
import com.appsventory.digiapps.nemo.ui.theme.*
import com.appsventory.digiapps.nemo.util.MediaEntry
import com.appsventory.digiapps.nemo.util.MediaKind
import com.appsventory.digiapps.nemo.smb.SmbClient
import com.appsventory.digiapps.nemo.util.AppSettings
import com.appsventory.digiapps.nemo.util.FileOpener
import com.appsventory.digiapps.nemo.util.MediaRepo
import com.appsventory.digiapps.nemo.util.isSmb
import com.appsventory.digiapps.nemo.util.isTelevision
import android.net.Uri
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.ui.res.dimensionResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date

/**
 * Gallery for photos (IMAGE) or videos (VIDEO), like a gallery app:
 * all items (photos grouped by day) and albums (folders). Photos open in a full-screen viewer,
 * videos open in the player you pick.
 */
@Composable
fun GalleryScreen(kind: MediaKind, onShowInFolder: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    var items by remember(kind) { mutableStateOf<List<MediaEntry>?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var refreshingSmb by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Local (MediaStore) + a cached SMB scan - see SmbMediaCache for why this doesn't re-scan
    // the network every time this screen opens.
    // also reloads by itself when MediaStore changes (new download / photo / drive scanned)
    val mediaTick = rememberMediaStoreTick()
    LaunchedEffect(kind, reloadTick, mediaTick) {
        if (mediaTick > 0) delay(800)
        items = MediaRepo.loadCombined(context, kind)
    }
    val hasExtraSources = AppSettings.enabledMediaSources.isNotEmpty()
    val isTv = remember { context.isTelevision() }

    // saved, so a refresh / rotation keeps you in the same tab and album
    var tab by rememberSaveable { mutableStateOf(0) } // 0 = all, 1 = albums
    var album by rememberSaveable { mutableStateOf<String?>(null) }
    var lastAlbum by remember { mutableStateOf<String?>(null) } // album to put focus back on (TV)
    val gridFocus = remember { FocusRequester() }
    // opening an album: focus its first item (otherwise focus falls back to the top of the screen)
    LaunchedEffect(album) {
        if (isTv && album != null) {
            delay(120)
            runCatching { gridFocus.requestFocus() }
        }
    }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var smbViewerImage by remember { mutableStateOf<ViewerImage?>(null) }
    BackHandler(enabled = album != null) { album = null }

    val videoLoader = remember {
        ImageLoader.Builder(context).components { add(VideoFrameDecoder.Factory()) }.build()
    }
    val all = items

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChipTabs(
                modifier = Modifier.weight(1f),
                autoSelectOnFocus = isTv,
                labels = listOf(
                    stringResource(if (kind == MediaKind.VIDEO) R.string.media_tab_videos else R.string.media_tab_photos),
                    stringResource(R.string.media_tab_albums)
                ),
                selected = tab,
                onSelect = {
                    tab = it
                    album = null
                }
            )
            // Only shown once a server is saved - scanning is on demand, never automatic.
            if (hasExtraSources) {
                TopBarButton(
                    onClick = {
                        scope.launch {
                            refreshingSmb = true
                            items = MediaRepo.loadCombined(context, kind, forceRefresh = true)
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
        when {
            all == null -> Centered { CircularProgressIndicator(color = CyanAccent) }
            all.isEmpty() -> Centered { Text(stringResource(R.string.list_empty), color = TextMuted, fontSize = 14.sp) }
            tab == 1 && album == null -> AlbumGrid(
                entries = all,
                kind = kind,
                videoLoader = videoLoader,
                focusAlbum = if (isTv) lastAlbum else null,
                onFocusHandled = { lastAlbum = null }
            ) {
                lastAlbum = it
                album = it
            }
            else -> {
                val shown = if (album != null) all.filter { it.group == album } else all
                album?.let {
                    Text(
                        it,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                // network photos aren't swipeable inline with local ones (no live Uri to page
                // through) - they open on their own, downloaded on tap; local photos keep the
                // full swipeable viewer exactly as before.
                val localOnly = remember(shown) { shown.filterNot { it.isSmb } }
                // key(album): a different album starts with a clean selection
                key(album) {
                MediaGrid(shown, kind, videoLoader, gridFocus, onShowInFolder = onShowInFolder, onChanged = { reloadTick++ }) { entry ->
                    when {
                        kind == MediaKind.VIDEO -> playVideo(context, scope, entry, playlist = shown)
                        entry.isSmb -> scope.launch {
                            val image = downloadSmbPhoto(context, entry)
                            if (image != null) smbViewerImage = image
                            else Toast.makeText(context, R.string.toast_smb_failed, Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            val localIndex = localOnly.indexOfFirst { it.id == entry.id }
                            if (localIndex >= 0) viewerIndex = localIndex
                        }
                    }
                }
                }
                viewerIndex?.let { start ->
                    ImageViewer(
                        images = localOnly.map { ViewerImage(it.uri, it.title, it.path) },
                        startIndex = start,
                        onClose = { viewerIndex = null },
                        onChanged = {
                            // MediaStore needs a moment to drop the deleted row
                            scope.launch {
                                delay(600)
                                reloadTick++
                            }
                        }
                    )
                }
                smbViewerImage?.let { image ->
                    ImageViewer(images = listOf(image), startIndex = 0, onClose = { smbViewerImage = null })
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun MediaGrid(
    entries: List<MediaEntry>,
    kind: MediaKind,
    videoLoader: ImageLoader,
    firstFocus: FocusRequester,
    onShowInFolder: ((String) -> Unit)?,
    onChanged: () -> Unit,
    onOpen: (MediaEntry) -> Unit
) {
    val firstId = entries.firstOrNull()?.id
    val isVideo = kind == MediaKind.VIDEO
    // long-press menu + select multiple (see MediaActions.kt)
    val actions = rememberMediaActions()
    val selection = actions.selection

    Box(Modifier.fillMaxSize()) {
        if (isVideo) {
            // Videos keep a uniform 16:9 grid (stable layout, duration badge).
            LazyVerticalGrid(
                columns = GridCells.Adaptive(170.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize().focusGroup()
            ) {
                gridItems(entries, key = { it.id }) { e ->
                    MediaCell(
                        e, true, videoLoader,
                        if (e.id == firstId) Modifier.focusRequester(firstFocus) else Modifier,
                        marked = selection.isMarked(e.id),
                        onLongClick = { actions.onLongPress(e) }
                    ) { actions.onClick(e) { onOpen(e) } }
                }
            }
        } else {
            // Photos: continuous alternating Pattern A / Pattern B grid (no date section headers).
            val context = LocalContext.current
            val blocks = remember(entries) { PhotoGridBlocks.build(entries) }
            // Group membership from the full dataset (selection source of truth).
            val timeIndex = remember(entries) { GalleryTimeIndex.build(entries) }
            val listState = rememberLazyListState()
            // Viewport width needed to map scroll offset → tile geometry inside a multi-span block.
            var viewportWidthPx by remember { mutableIntStateOf(0) }
            // Active group = time group of the photo tile intersecting the top of the viewport
            // (not merely the first photo of the first visible LazyColumn block).
            val activeGroup by remember(blocks, timeIndex, viewportWidthPx) {
                derivedStateOf {
                    val topId = PhotoGridBlocks.topVisiblePhotoId(
                        blocks = blocks,
                        firstVisibleBlockIndex = listState.firstVisibleItemIndex,
                        scrollOffsetPx = listState.firstVisibleItemScrollOffset,
                        widthPx = viewportWidthPx
                    )
                    if (topId != null) timeIndex.groupOf(topId) else null
                }
            }
            // Fresh values for the indicator click (avoid stale group / index closures).
            val activeGroupRef = rememberUpdatedState(activeGroup)
            val timeIndexRef = rememberUpdatedState(timeIndex)
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(0.dp),
                    // 2.dp between consecutive pattern blocks only; in-block tile gaps stay GAP_PX.
                    verticalArrangement = Arrangement.spacedBy(PhotoGridBlocks.BLOCK_GAP),
                    modifier = Modifier
                        .fillMaxSize()
                        .focusGroup()
                        .onSizeChanged { viewportWidthPx = it.width }
                ) {
                    lazyItems(blocks, key = { it.key }) { block ->
                        PhotoGridBlock(
                            block = block,
                            videoLoader = videoLoader,
                            firstId = firstId,
                            firstFocus = firstFocus,
                            selection = selection,
                            onLongClick = { actions.onLongPress(it) },
                            onClick = { e -> actions.onClick(e) { onOpen(e) } },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                val group = activeGroup
                if (entries.isNotEmpty() && group != null && group != TimeGroupId.Unknown) {
                    val navPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    val label = timeIndex.label(context, group)
                    FloatingTimeIndicator(
                        label = label,
                        onClick = {
                            // Resolve membership from the full index + current stable group id.
                            val g = activeGroupRef.value ?: return@FloatingTimeIndicator
                            if (g == TimeGroupId.Unknown) return@FloatingTimeIndicator
                            selection.applyGroupToggle(timeIndexRef.value.idsIn(g))
                        },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 12.dp, bottom = navPad + 12.dp)
                    )
                }
            }
        }
        MediaActionsUi(
            actions = actions,
            visible = entries,
            openLabel = if (isVideo) R.string.media_action_play else R.string.media_action_view,
            openIcon = if (isVideo) Icons.Default.PlayArrow else Icons.Default.Image,
            onOpen = onOpen,
            onShowInFolder = onShowInFolder,
            onChanged = onChanged
        )
    }
}

@Composable
private fun MediaCell(
    entry: MediaEntry,
    isVideo: Boolean,
    videoLoader: ImageLoader,
    modifier: Modifier,
    marked: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit
) {
    if (isVideo) {
        Column(modifier.focusCard(corner = 10.dp, marked = marked, onLongClick = onLongClick, onClick = onClick).padding(bottom = 8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                Thumb(entry, video = true, loader = videoLoader)
                if (marked) CheckBadge(Modifier.align(Alignment.TopStart))
                Text(
                    formatDuration(entry.durationMs),
                    color = TextPrimary,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(ActiveColor)
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
            Text(
                entry.title,
                color = TextPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    } else {
        // Photo cell fills its CSS-grid slot; square corners; image crops to the slot.
        Box(
            modifier
                .fillMaxSize()
                .focusCard(corner = 0.dp, marked = marked, onLongClick = onLongClick, onClick = onClick)
        ) {
            Thumb(entry, video = false, loader = videoLoader)
            if (marked) CheckBadge(Modifier.align(Alignment.TopStart))
        }
    }
}

/**
 * Gallery photo layout: alternating Pattern A (large left) and Pattern B (large right).
 *
 * Pattern A (up to 6):
 *   ┌───────────┬─────┐
 *   │     1     │  2  │
 *   │   (2×2)   ├─────┤
 *   │           │  3  │
 *   ├─────┬─────┼─────┤
 *   │  4  │  5  │  6  │
 *   └─────┴─────┴─────┘
 *
 * Pattern B (up to 6): large tile on the right, small tiles 2/3 on the left, then row 4–6.
 *
 * 1–2 photos total → single equal-width row (no large tile).
 * Incomplete blocks only place existing items; unused rows are not created when the
 * block has ≤3 items (top section only) or when trailing 1–2 items use a single row.
 */
private enum class PhotoBlockKind { SINGLE_ROW, PATTERN_A, PATTERN_B }

private data class PhotoGridBlockModel(
    val key: String,
    val kind: PhotoBlockKind,
    val entries: List<MediaEntry>
)

private object PhotoGridBlocks {
    const val COLS = 3
    /** Gap between tiles in px (≈1–2 dp on most densities; applied in px inside Layout). */
    const val GAP_PX = 4
    /** Vertical gap between consecutive pattern blocks (A↔B). Not applied inside a block. */
    val BLOCK_GAP = (GAP_PX/2).dp

    fun build(entries: List<MediaEntry>): List<PhotoGridBlockModel> {
        if (entries.isEmpty()) return emptyList()
        if (entries.size <= 2) {
            return listOf(
                PhotoGridBlockModel(
                    key = "sr_${entries.first().id}",
                    kind = PhotoBlockKind.SINGLE_ROW,
                    entries = entries
                )
            )
        }
        val out = ArrayList<PhotoGridBlockModel>()
        var i = 0
        var pattern = 0 // even = A, odd = B
        while (i < entries.size) {
            val remaining = entries.size - i
            if (remaining <= 2) {
                // Trailing 1–2 after complete patterns: clean single row
                out += PhotoGridBlockModel(
                    key = "sr_${entries[i].id}",
                    kind = PhotoBlockKind.SINGLE_ROW,
                    entries = entries.subList(i, entries.size)
                )
                break
            }
            val take = minOf(6, remaining)
            val kind = if (pattern % 2 == 0) PhotoBlockKind.PATTERN_A else PhotoBlockKind.PATTERN_B
            val slice = entries.subList(i, i + take)
            out += PhotoGridBlockModel(
                key = "p${pattern}_${slice.first().id}",
                kind = kind,
                entries = slice
            )
            i += take
            pattern++
        }
        return out
    }

    /**
     * Slots for a pattern block: list of (row, col, rowSpan, colSpan) in photo order.
     * Only the first [count] slots are used.
     */
    fun slots(kind: PhotoBlockKind, count: Int): List<IntArray> {
        if (kind == PhotoBlockKind.SINGLE_ROW) {
            return (0 until count).map { intArrayOf(0, it, 1, 1) }
        }
        val largeLeft = kind == PhotoBlockKind.PATTERN_A
        // Photo order: 1=large, 2=top small, 3=bottom small, 4,5,6 = bottom row
        val all = if (largeLeft) {
            listOf(
                intArrayOf(0, 0, 2, 2), // 1 large left
                intArrayOf(0, 2, 1, 1), // 2
                intArrayOf(1, 2, 1, 1), // 3
                intArrayOf(2, 0, 1, 1), // 4
                intArrayOf(2, 1, 1, 1), // 5
                intArrayOf(2, 2, 1, 1), // 6
            )
        } else {
            listOf(
                intArrayOf(0, 1, 2, 2), // 1 large right
                intArrayOf(0, 0, 1, 1), // 2 top-left
                intArrayOf(1, 0, 1, 1), // 3 bottom-left of top section
                intArrayOf(2, 0, 1, 1), // 4
                intArrayOf(2, 1, 1, 1), // 5
                intArrayOf(2, 2, 1, 1), // 6
            )
        }
        return all.take(count)
    }

    /** How many unit rows this block needs given item count. */
    fun rowCount(kind: PhotoBlockKind, count: Int): Int {
        if (kind == PhotoBlockKind.SINGLE_ROW) return 1
        return when {
            count <= 0 -> 0
            count <= 3 -> 2 // only the large + two small section
            else -> 3
        }
    }

    /**
     * Media id of the photo under the **top-left** of the Gallery content viewport.
     *
     * LazyColumn items are pattern blocks, not individual photos. Hit-testing a single point
     * (top-left of the visible content) avoids a spanning large tile "reclaiming" the active
     * group after a smaller side tile scrolls away — which caused sequences like
     * Yesterday → Today while scrolling down (Pattern B: left small then large-right).
     *
     * Geometry matches [PhotoGridBlock] (cell size, gaps, row/col spans).
     */
    fun topVisiblePhotoId(
        blocks: List<PhotoGridBlockModel>,
        firstVisibleBlockIndex: Int,
        scrollOffsetPx: Int,
        widthPx: Int
    ): Long? {
        if (blocks.isEmpty() || widthPx <= 0) return null
        val idx = firstVisibleBlockIndex.coerceIn(0, blocks.lastIndex)
        val block = blocks[idx]
        val count = block.entries.size
        if (count == 0) return null
        val slotList = slots(block.kind, count)
        val gap = GAP_PX
        val cell = ((widthPx - gap * (COLS - 1)) / COLS).coerceAtLeast(1)
        // Sample slightly inside the top-left so gap lines don't miss every tile.
        val sampleX = 0
        val sampleY = scrollOffsetPx.coerceAtLeast(0)
        for (i in slotList.indices) {
            val s = slotList[i]
            val x0 = s[1] * (cell + gap)
            val y0 = s[0] * (cell + gap)
            val w = s[3] * cell + (s[3] - 1).coerceAtLeast(0) * gap
            val h = s[2] * cell + (s[2] - 1).coerceAtLeast(0) * gap
            if (sampleX >= x0 && sampleX < x0 + w && sampleY >= y0 && sampleY < y0 + h) {
                return block.entries[i].id
            }
        }
        // Gap or past last row: nearest tile whose top is at or above the sample (prefer lower
        // tiles when between rows so scrolling down advances the group, not upward).
        var best = 0
        var bestTop = Int.MIN_VALUE
        for (i in slotList.indices) {
            val s = slotList[i]
            val y0 = s[0] * (cell + gap)
            if (y0 <= sampleY && y0 >= bestTop) {
                // Among equal tops, prefer left-most (col) for determinism.
                if (y0 > bestTop || s[1] <= slotList[best][1]) {
                    best = i
                    bestTop = y0
                }
            }
        }
        return block.entries[best].id
    }
}

@Composable
private fun PhotoGridBlock(
    block: PhotoGridBlockModel,
    videoLoader: ImageLoader,
    firstId: Long?,
    firstFocus: FocusRequester,
    selection: MediaSelection,
    onLongClick: (MediaEntry) -> Unit,
    onClick: (MediaEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val count = block.entries.size
    val slots = remember(block.kind, count) { PhotoGridBlocks.slots(block.kind, count) }
    val rows = PhotoGridBlocks.rowCount(block.kind, count)
    Layout(
        content = {
            for (i in 0 until count) {
                val e = block.entries[i]
                key(e.id) {
                    MediaCell(
                        e, false, videoLoader,
                        if (e.id == firstId) Modifier.focusRequester(firstFocus) else Modifier,
                        marked = selection.isMarked(e.id),
                        onLongClick = { onLongClick(e) },
                        onClick = { onClick(e) }
                    )
                }
            }
        },
        modifier = modifier
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = PhotoGridBlocks.GAP_PX
        val cols = PhotoGridBlocks.COLS
        // unit cell so that 3 cells + 2 gaps fill [width]
        val cell = ((width - gap * (cols - 1)) / cols).coerceAtLeast(1)
        val height = rows * cell + (rows - 1).coerceAtLeast(0) * gap
        val placeables = ArrayList<Placeable>(measurables.size)
        for (i in measurables.indices) {
            val s = slots[i]
            val w = s[3] * cell + (s[3] - 1).coerceAtLeast(0) * gap
            val h = s[2] * cell + (s[2] - 1).coerceAtLeast(0) * gap
            placeables += measurables[i].measure(Constraints.fixed(w, h))
        }
        layout(width, height.coerceAtLeast(0)) {
            for (i in placeables.indices) {
                val s = slots[i]
                val x = s[1] * (cell + gap)
                val y = s[0] * (cell + gap)
                placeables[i].placeRelative(x, y)
            }
        }
    }
}

@Composable
private fun FloatingTimeIndicator(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState = label,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "galleryTime",
        modifier = modifier
    ) { text ->
        Text(
            text = text,
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(ActiveColor.copy(alpha = 0.85f))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/**
 * Stable identity for a hierarchical time group. Selection uses this, not the formatted label.
 */
private sealed class TimeGroupId {
    data object Today : TimeGroupId()
    data object Yesterday : TimeGroupId()
    data object EarlierThisWeek : TimeGroupId()
    data object EarlierThisMonth : TimeGroupId()
    /** Calendar month (month is 0–11, same as [Calendar.MONTH]). */
    data class MonthYear(val year: Int, val month: Int) : TimeGroupId()
    data object Unknown : TimeGroupId()
}

/**
 * Precomputed membership of each media id in a [TimeGroupId], built once when the list changes.
 * Scroll only looks up the representative item's group; selection reads the full id list for that group.
 */
private class GalleryTimeIndex private constructor(
    private val byId: Map<Long, TimeGroupId>,
    private val members: Map<TimeGroupId, List<Long>>
) {
    fun groupOf(id: Long): TimeGroupId = byId[id] ?: TimeGroupId.Unknown
    fun idsIn(group: TimeGroupId): List<Long> = members[group].orEmpty()

    fun label(context: android.content.Context, group: TimeGroupId): String = when (group) {
        TimeGroupId.Today -> context.getString(R.string.gallery_time_today)
        TimeGroupId.Yesterday -> context.getString(R.string.gallery_time_yesterday)
        TimeGroupId.EarlierThisWeek -> context.getString(R.string.gallery_time_earlier_week)
        TimeGroupId.EarlierThisMonth -> context.getString(R.string.gallery_time_earlier_month)
        is TimeGroupId.MonthYear -> {
            val cal = Calendar.getInstance().apply {
                set(Calendar.YEAR, group.year)
                set(Calendar.MONTH, group.month)
                set(Calendar.DAY_OF_MONTH, 1)
            }
            DateFormat.format("MMMM yyyy", cal).toString()
        }
        TimeGroupId.Unknown -> ""
    }

    companion object {
        fun build(entries: List<MediaEntry>, nowMillis: Long = System.currentTimeMillis()): GalleryTimeIndex {
            val bounds = TimeBounds.compute(nowMillis)
            val byId = HashMap<Long, TimeGroupId>(entries.size)
            val buckets = LinkedHashMap<TimeGroupId, MutableList<Long>>()
            for (e in entries) {
                val g = bounds.groupOf(e.dateMillis)
                byId[e.id] = g
                if (g != TimeGroupId.Unknown) {
                    buckets.getOrPut(g) { ArrayList() }.add(e.id)
                }
            }
            return GalleryTimeIndex(byId, buckets)
        }
    }
}

/** Local-calendar day/week/month boundaries for hierarchical grouping. */
private class TimeBounds(
    val todayStart: Long,
    val yesterdayStart: Long,
    val weekStart: Long,
    val monthStart: Long
) {
    /**
     * Exactly one group per timestamp. Branches are mutually exclusive and ordered:
     * Today → Yesterday → Earlier this week → Earlier this month → Month/year.
     * Boundaries are local-calendar midnights ([todayStart], [yesterdayStart], [weekStart], [monthStart]).
     */
    fun groupOf(millis: Long): TimeGroupId {
        if (millis <= 0L) return TimeGroupId.Unknown
        // [todayStart, ∞)
        if (millis >= todayStart) return TimeGroupId.Today
        // [yesterdayStart, todayStart)
        if (millis >= yesterdayStart) return TimeGroupId.Yesterday
        // [weekStart, yesterdayStart) — excludes today/yesterday by prior returns
        if (millis >= weekStart) return TimeGroupId.EarlierThisWeek
        // [monthStart, weekStart) — may be empty when the week starts before this month
        if (millis >= monthStart) return TimeGroupId.EarlierThisMonth
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        return TimeGroupId.MonthYear(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
    }

    companion object {
        fun compute(nowMillis: Long): TimeBounds {
            val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
            // Start of today (local)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val todayStart = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, -1)
            val yesterdayStart = cal.timeInMillis
            // Start of current calendar week (respects locale first day of week)
            cal.timeInMillis = todayStart
            val first = cal.firstDayOfWeek
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            val diff = (dow - first + 7) % 7
            cal.add(Calendar.DAY_OF_YEAR, -diff)
            val weekStart = cal.timeInMillis
            // Start of current calendar month
            cal.timeInMillis = todayStart
            cal.set(Calendar.DAY_OF_MONTH, 1)
            val monthStart = cal.timeInMillis
            return TimeBounds(todayStart, yesterdayStart, weekStart, monthStart)
        }
    }
}

@Composable
private fun AlbumGrid(
    entries: List<MediaEntry>,
    kind: MediaKind,
    videoLoader: ImageLoader,
    focusAlbum: String?,
    onFocusHandled: () -> Unit,
    onOpen: (String) -> Unit
) {
    val resources = LocalContext.current.resources
    val albums = remember(entries) { entries.groupBy { it.group }.toList() }
    val gridState = rememberLazyGridState()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    // back from an album: focus returns to the album that was open
    LaunchedEffect(focusAlbum) {
        if (focusAlbum != null) {
            val index = albums.indexOfFirst { it.first == focusAlbum }
            if (index >= 0) {
                gridState.scrollToItem(index)
                delay(120)
                runCatching { requesters[focusAlbum]?.requestFocus() }
            }
            onFocusHandled()
        }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().focusGroup()
    ) {
        gridItems(albums, key = { it.first }) { (name, group) ->
            Column(
                Modifier
                    .focusRequester(requesters.getOrPut(name) { FocusRequester() })
                    .focusCard(corner = 12.dp, onClick = { onOpen(name) })
                    .padding(bottom = 10.dp)
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    Thumb(group.first(), video = kind == MediaKind.VIDEO, loader = videoLoader)
                }
                Text(
                    name,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
                Text(
                    resources.getQuantityString(R.plurals.item_count, group.size, group.size),
                    color = TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
            }
        }
    }
}

/** In-memory only (per app session): avoids re-downloading the same network photo's preview
 *  while scrolling. Cleared when the process restarts - that's fine, it's just a preview. */
private val smbThumbCache = mutableMapOf<String, Uri>()

@Composable
private fun Thumb(entry: MediaEntry, video: Boolean, loader: ImageLoader) {
    // Network media isn't pre-downloaded just to render a thumbnail by default - a generic icon
    // stands in until the item is actually opened (see the tap handlers below), unless the user
    // opted in to network thumbnails (Settings > Download thumbnails for network photos), and
    // only for photos: grabbing a video frame would still mean downloading the whole file.
    if (entry.isSmb) {
        if (video || !AppSettings.smbThumbnails) {
            Box(Modifier.fillMaxSize().background(DividerDark), contentAlignment = Alignment.Center) {
                Icon(
                    if (video) Icons.Default.Movie else Icons.Default.Image,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(28.dp)
                )
            }
            return
        }
        val context = LocalContext.current
        val path = entry.path!!
        val cachedUri by produceState<Uri?>(smbThumbCache[path], path) {
            if (value == null) {
                value = withContext(Dispatchers.IO) {
                    runCatching { Uri.fromFile(SmbClient.downloadToCache(context, path)) }.getOrNull()
                }?.also { smbThumbCache[path] = it }
            }
        }
        val uri = cachedUri
        if (uri == null) {
            Box(Modifier.fillMaxSize().background(DividerDark), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp), color = CyanAccent, strokeWidth = 2.dp)
            }
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context).data(uri).crossfade(true).build(),
                imageLoader = loader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().background(DividerDark)
            )
        }
        return
    }
    val context = LocalContext.current
    val request = remember(entry.uri) {
        ImageRequest.Builder(context)
            .data(entry.uri)
            .apply { if (video) videoFrameMillis(1000) }
            .crossfade(true)
            .build()
    }
    AsyncImage(
        model = request,
        imageLoader = loader,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().background(DividerDark)
    )
}

/** Local video: in-app Media3 player with the current list as playlist (swipe / Auto Next).
 *  Network video: external player if available, else download then open. */
private fun playVideo(
    context: Context,
    scope: CoroutineScope,
    entry: MediaEntry,
    playlist: List<MediaEntry> = listOf(entry)
) {
    if (entry.isSmb) {
        val path = entry.path ?: return
        val direct = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(path), "video/*")
        val opened = runCatching {
            context.startActivity(Intent.createChooser(direct, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!opened) {
            scope.launch {
                Toast.makeText(context, R.string.toast_downloading, Toast.LENGTH_SHORT).show()
                val local = withContext(Dispatchers.IO) { runCatching { SmbClient.downloadToCache(context, path) } }
                local.onSuccess { FileOpener.open(context, it.path) }
                    .onFailure { Toast.makeText(context, R.string.toast_smb_failed, Toast.LENGTH_SHORT).show() }
            }
        }
        return
    }
    // Local / MediaStore only — stable order for swipe & Auto Next
    val local = playlist.filterNot { it.isSmb }
    val index = local.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
    val uris = local.map { it.uri }
    val titles = local.map { it.title }
    if (uris.isEmpty()) {
        com.appsventory.digiapps.nemo.VideoPlayerActivity.start(context, entry.uri, entry.title)
    } else {
        com.appsventory.digiapps.nemo.VideoPlayerActivity.start(context, uris, titles, index)
    }
}

/** Downloads one SMB photo to the app cache so it can be opened in the built-in viewer. */
private suspend fun downloadSmbPhoto(context: Context, entry: MediaEntry): ViewerImage? {
    val path = entry.path ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val file = SmbClient.downloadToCache(context, path)
            ViewerImage(Uri.fromFile(file), entry.title, file.path)
        }.getOrNull()
    }
}

private fun dayKey(millis: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
}

@Composable
private fun dayLabel(millis: Long): String {
    val key = dayKey(millis)
    val today = dayKey(System.currentTimeMillis())
    val yesterday = dayKey(System.currentTimeMillis() - 86_400_000L)
    return when (key) {
        today -> stringResource(R.string.media_today)
        yesterday -> stringResource(R.string.media_yesterday)
        else -> DateFormat.getMediumDateFormat(LocalContext.current).format(Date(millis))
    }
}

/** 3:07 or 1:02:09 */
fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
