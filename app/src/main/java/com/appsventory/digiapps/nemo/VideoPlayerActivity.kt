package com.appsventory.digiapps.nemo

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.appsventory.digiapps.nemo.ui.theme.ActiveColor
import com.appsventory.digiapps.nemo.ui.theme.DigiFilesTheme
import com.appsventory.digiapps.nemo.ui.theme.TextPrimary
import com.appsventory.digiapps.nemo.ui.theme.TextSecondary
import com.appsventory.digiapps.nemo.util.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Media3 video player with TikTok-style vertical paging, unified bottom settings,
 * bottom-left controls, and Android TV D-pad support.
 *
 * Playlist direction (touch + remote):
 * - Swipe **up** / D-pad **Down** → next video (higher index)
 * - Swipe **down** / D-pad **Up** → previous video (lower index)
 */
class VideoPlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val uris = intent.getStringArrayListExtra(EXTRA_URIS)?.mapNotNull {
            runCatching { Uri.parse(it) }.getOrNull()
        } ?: listOfNotNull(intent.data)
        if (uris.isEmpty()) {
            finish()
            return
        }
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, uris.lastIndex)

        AppSettings.ensureLoaded(this)
        setContent {
            DigiFilesTheme {
                VideoPlayerScreen(
                    uris = uris,
                    titles = titles,
                    startIndex = startIndex,
                    onClose = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_INDEX = "index"

        fun start(context: Context, uri: Uri, title: String) {
            start(context, listOf(uri), listOf(title), 0)
        }

        fun start(context: Context, uris: List<Uri>, titles: List<String>, startIndex: Int) {
            if (uris.isEmpty()) return
            val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_URIS, ArrayList(uris.map { it.toString() }))
                putStringArrayListExtra(EXTRA_TITLES, ArrayList(titles))
                putExtra(EXTRA_INDEX, startIndex.coerceIn(0, uris.lastIndex))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }
    }
}

private const val SEEK_MS = 10_000L
private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
private fun VideoPlayerScreen(
    uris: List<Uri>,
    titles: List<String>,
    startIndex: Int,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val navBottomDp = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }

    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, uris.lastIndex)) { uris.size }
    val player = remember { ExoPlayer.Builder(context).build() }

    var controlsVisible by remember { mutableStateOf(true) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsSubMenu by remember { mutableStateOf<SettingsSub?>(null) }
    var autoNext by remember { mutableStateOf(AppSettings.videoAutoNext) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var seekHint by remember { mutableStateOf<SeekHint?>(null) }
    var accumulatedSeekMs by remember { mutableLongStateOf(0L) }
    var autoNextConsumedForPage by remember { mutableIntStateOf(-1) }
    var audioTracks by remember { mutableStateOf<List<AudioTrackOption>>(emptyList()) }

    val surfaceFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    val settledPage by remember { derivedStateOf { pagerState.settledPage } }
    val currentTitle = titles.getOrElse(settledPage) { "" }

    fun seekBy(deltaMs: Long) {
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0L, duration)
        player.seekTo(target)
        positionMs = target
        accumulatedSeekMs += deltaMs
        seekHint = if (accumulatedSeekMs >= 0) SeekHint.Forward(accumulatedSeekMs)
        else SeekHint.Rewind(-accumulatedSeekMs)
        controlsVisible = true
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
        controlsVisible = true
    }

    fun goToPage(page: Int) {
        if (page in uris.indices) {
            scope.launch { pagerState.animateScrollToPage(page) }
        }
    }

    // Load media when the settled page changes (single ExoPlayer).
    LaunchedEffect(settledPage) {
        val uri = uris.getOrNull(settledPage) ?: return@LaunchedEffect
        errorMessage = null
        loading = true
        autoNextConsumedForPage = -1
        player.setMediaItem(MediaItem.fromUri(uri))
        player.setPlaybackSpeed(playbackSpeed)
        player.prepare()
        player.playWhenReady = true
    }

    val autoNextRef = rememberUpdatedState(autoNext)
    val settledRef = rememberUpdatedState(settledPage)
    val pageCountRef = rememberUpdatedState(uris.size)

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> loading = true
                    Player.STATE_READY -> {
                        loading = false
                        durationMs = player.duration.coerceAtLeast(0L)
                        audioTracks = collectAudioTracks(player)
                    }
                    Player.STATE_ENDED -> {
                        loading = false
                        isPlaying = false
                        if (autoNextRef.value) {
                            val page = settledRef.value
                            if (autoNextConsumedForPage != page && page < pageCountRef.value - 1) {
                                autoNextConsumedForPage = page
                                scope.launch { pagerState.animateScrollToPage(page + 1) }
                            }
                        }
                    }
                    Player.STATE_IDLE -> Unit
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) loading = false
            }

            override fun onPlayerError(error: PlaybackException) {
                loading = false
                errorMessage = error.localizedMessage
                    ?: context.getString(R.string.video_player_error)
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                audioTracks = collectAudioTracks(player)
            }
        }
        player.addListener(listener)
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            player.removeListener(listener)
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }

    // Progress ticker while playing
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.coerceAtLeast(0L)
            delay(250)
        }
    }

    LaunchedEffect(isLandscape) {
        val act = context as ComponentActivity
        val c = WindowInsetsControllerCompat(act.window, act.window.decorView)
        if (isLandscape) {
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(seekHint, accumulatedSeekMs) {
        if (seekHint != null) {
            delay(800)
            seekHint = null
            accumulatedSeekMs = 0L
        }
    }

    // Auto-hide controls
    LaunchedEffect(controlsVisible, isPlaying, settingsOpen) {
        if (controlsVisible && isPlaying && !settingsOpen) {
            delay(3500)
            controlsVisible = false
        }
    }

    LaunchedEffect(Unit) {
        runCatching { surfaceFocus.requestFocus() }
    }

    BackHandler {
        when {
            settingsSubMenu != null -> settingsSubMenu = null
            settingsOpen -> settingsOpen = false
            else -> onClose()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(surfaceFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // When settings are open, let focus system handle keys.
                if (settingsOpen) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        togglePlay()
                        true
                    }
                    Key.DirectionLeft -> {
                        seekBy(-SEEK_MS)
                        true
                    }
                    Key.DirectionRight -> {
                        seekBy(SEEK_MS)
                        true
                    }
                    Key.DirectionUp -> {
                        // Up → previous video (matches swipe down)
                        if (settledPage > 0) {
                            goToPage(settledPage - 1)
                            true
                        } else false
                    }
                    Key.DirectionDown -> {
                        // Down → next video (matches swipe up)
                        if (settledPage < uris.lastIndex) {
                            goToPage(settledPage + 1)
                            true
                        } else false
                    }
                    Key.Back, Key.Escape -> {
                        onClose()
                        true
                    }
                    else -> false
                }
            }
    ) {
        // Entire page (surface + controls) moves with the pager — TikTok-style.
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1
        ) { page ->
            val active = page == settledPage
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false // custom bottom-left controls only
                            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        }
                    },
                    update = { view ->
                        view.player = if (active) player else null
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Per-page gesture surface (paging is handled by VerticalPager; double-tap seek only)
                if (active) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(bottom = 96.dp) // leave bottom controls free
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { controlsVisible = !controlsVisible },
                                    onDoubleTap = { offset ->
                                        val mid = size.width / 2f
                                        if (offset.x >= mid) seekBy(SEEK_MS) else seekBy(-SEEK_MS)
                                    }
                                )
                            }
                    )
                }

                // Title (moves with page)
                AnimatedVisibility(
                    visible = controlsVisible && active,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(4.dp)
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_close),
                                tint = TextPrimary
                            )
                        }
                        Text(
                            text = currentTitle,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                }

                // Bottom-left controls + single settings (moves with page)
                AnimatedVisibility(
                    visible = controlsVisible && active,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                ) {
                    Column {
                        // Seek bar
                        val dur = durationMs.coerceAtLeast(1L).toFloat()
                        Slider(
                            value = positionMs.coerceIn(0L, durationMs).toFloat().coerceAtMost(dur),
                            onValueChange = {
                                positionMs = it.toLong()
                                player.seekTo(positionMs)
                            },
                            valueRange = 0f..dur,
                            colors = SliderDefaults.colors(
                                thumbColor = ActiveColor,
                                activeTrackColor = ActiveColor,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Play / pause — bottom-left primary control
                            FocusableIconButton(
                                onClick = { togglePlay() },
                                focusRequester = playFocus,
                                contentDescription = if (isPlaying) "Pause" else "Play"
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = TextPrimary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${formatPlayerTime(positionMs)} / ${formatPlayerTime(durationMs)}",
                                color = TextPrimary,
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.weight(1f))
                            // Single settings button (bottom control area)
                            Box {
                                FocusableIconButton(
                                    onClick = {
                                        settingsOpen = true
                                        settingsSubMenu = null
                                    },
                                    contentDescription = stringResource(R.string.video_player_settings)
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = null, tint = TextPrimary)
                                }
                                DropdownMenu(
                                    expanded = settingsOpen,
                                    onDismissRequest = {
                                        settingsOpen = false
                                        settingsSubMenu = null
                                    }
                                ) {
                                    when (settingsSubMenu) {
                                        null -> {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        stringResource(R.string.video_player_auto_next) +
                                                            if (autoNext) "  ✓" else ""
                                                    )
                                                },
                                                onClick = {
                                                    autoNext = !autoNext
                                                    AppSettings.setVideoAutoNext(autoNext)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        stringResource(
                                                            R.string.video_player_speed,
                                                            speedLabel(playbackSpeed)
                                                        )
                                                    )
                                                },
                                                onClick = { settingsSubMenu = SettingsSub.Speed }
                                            )
                                            if (audioTracks.size > 1) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.video_player_audio)) },
                                                    onClick = { settingsSubMenu = SettingsSub.Audio }
                                                )
                                            }
                                        }
                                        SettingsSub.Speed -> {
                                            SPEED_OPTIONS.forEach { speed ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            speedLabel(speed) +
                                                                if (speed == playbackSpeed) "  ✓" else ""
                                                        )
                                                    },
                                                    onClick = {
                                                        playbackSpeed = speed
                                                        player.setPlaybackSpeed(speed)
                                                        settingsSubMenu = null
                                                        settingsOpen = false
                                                    }
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.action_close)) },
                                                onClick = { settingsSubMenu = null }
                                            )
                                        }
                                        SettingsSub.Audio -> {
                                            audioTracks.forEach { track ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(track.label + if (track.selected) "  ✓" else "")
                                                    },
                                                    onClick = {
                                                        selectAudioTrack(player, track)
                                                        settingsSubMenu = null
                                                        settingsOpen = false
                                                    }
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.action_close)) },
                                                onClick = { settingsSubMenu = null }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (active && loading && errorMessage == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = ActiveColor)
                }
                if (active) {
                    errorMessage?.let { msg ->
                        Text(
                            text = msg,
                            color = Color(0xFFFF8A80),
                            fontSize = 14.sp,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp)
                        )
                    }
                }

                if (active) {
                    AnimatedVisibility(
                        visible = seekHint != null,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(
                                when (seekHint) {
                                    is SeekHint.Forward -> Alignment.CenterEnd
                                    is SeekHint.Rewind -> Alignment.CenterStart
                                    null -> Alignment.Center
                                }
                            )
                            .padding(horizontal = 32.dp)
                    ) {
                        val hint = seekHint
                        if (hint != null) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Icon(
                                    if (hint is SeekHint.Forward) Icons.Default.FastForward
                                    else Icons.Default.FastRewind,
                                    contentDescription = null,
                                    tint = TextPrimary,
                                    modifier = Modifier.size(28.dp)
                                )
                                Text(
                                    text = when (hint) {
                                        is SeekHint.Forward -> "+${hint.ms / 1000}s"
                                        is SeekHint.Rewind -> "−${hint.ms / 1000}s"
                                    },
                                    color = TextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusableIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    focusRequester: FocusRequester? = null,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    IconButton(
        onClick = onClick,
        interactionSource = interaction,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(interactionSource = interaction)
            .then(
                if (focused) Modifier
                    .border(2.dp, ActiveColor, CircleShape)
                    .padding(2.dp)
                else Modifier
            )
    ) {
        content()
    }
}

private enum class SettingsSub { Speed, Audio }

private sealed class SeekHint {
    data class Forward(val ms: Long) : SeekHint()
    data class Rewind(val ms: Long) : SeekHint()
}

private data class AudioTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val selected: Boolean
)

private fun collectAudioTracks(player: ExoPlayer): List<AudioTrackOption> {
    val out = ArrayList<AudioTrackOption>()
    val tracks = player.currentTracks
    for (gi in 0 until tracks.groups.size) {
        val group = tracks.groups[gi]
        if (group.type != C.TRACK_TYPE_AUDIO) continue
        for (ti in 0 until group.length) {
            if (!group.isTrackSupported(ti)) continue
            val format = group.getTrackFormat(ti)
            val label = format.label
                ?: format.language
                ?: "Track ${out.size + 1}"
            out += AudioTrackOption(gi, ti, label, group.isTrackSelected(ti))
        }
    }
    return out
}

private fun selectAudioTrack(player: ExoPlayer, track: AudioTrackOption) {
    val tracks = player.currentTracks
    if (track.groupIndex !in 0 until tracks.groups.size) return
    val group = tracks.groups[track.groupIndex]
    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setOverrideForType(
            TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex)
        )
        .build()
}

private fun speedLabel(speed: Float): String =
    if (speed == 1f) "1x" else "${speed}x"

private fun formatPlayerTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
