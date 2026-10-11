package com.appsventory.digiapps.nemo.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.appsventory.digiapps.nemo.R
import com.appsventory.digiapps.nemo.ui.theme.BgDark
import com.appsventory.digiapps.nemo.ui.theme.OnAccent
import com.appsventory.digiapps.nemo.ui.theme.TextMuted
import com.appsventory.digiapps.nemo.ui.theme.TextPrimary
import com.appsventory.digiapps.nemo.ui.theme.TextSecondary
import com.appsventory.digiapps.nemo.util.FileOpener
import com.appsventory.digiapps.nemo.util.FileOps
import com.appsventory.digiapps.nemo.ui.theme.ErrorRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One photo in the viewer. [path] (when known) enables delete and file-based share. */
data class ViewerImage(val uri: Uri, val title: String, val path: String? = null)

/**
 * Full-screen photo viewer: swipe (or DPAD left/right) between photos, pinch or double-tap
 * to zoom, tap to hide the bars, share, and delete (to Trash, or permanently).
 * [onChanged] is called after a photo was deleted so the caller can refresh its list.
 */
@Composable
fun ImageViewer(
    images: List<ViewerImage>,
    startIndex: Int,
    onClose: () -> Unit,
    onChanged: () -> Unit = {}
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var list by remember { mutableStateOf(images) }
        val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, images.lastIndex)) { list.size }
        var chrome by remember { mutableStateOf(true) }
        var zoomed by remember { mutableStateOf(false) }
        var confirmDelete by remember { mutableStateOf<ViewerImage?>(null) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        LaunchedEffect(pager.currentPage) { zoomed = false }

        Box(
            Modifier
                .fillMaxSize()
                .background(BgDark)
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val step = when (e.key) {
                        Key.DirectionRight -> 1
                        Key.DirectionLeft -> -1
                        else -> return@onKeyEvent false
                    }
                    scope.launch { pager.animateScrollToPage((pager.currentPage + step).coerceIn(0, list.lastIndex)) }
                    true
                }
        ) {
            HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
                list.getOrNull(page)?.let { image ->
                    ZoomableImage(
                        uri = image.uri,
                        onTap = { chrome = !chrome },
                        onZoomChange = { if (pager.currentPage == page) zoomed = it }
                    )
                }
            }

            val current = list.getOrNull(pager.currentPage)
            if (chrome && current != null) {
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(BgDark.copy(alpha = 0.6f))
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TopBarButton(onClick = onClose, modifier = Modifier.size(44.dp)) { focused ->
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_close),
                            tint = if (focused) OnAccent else TextPrimary
                        )
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(current.title, color = TextPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${pager.currentPage + 1} / ${list.size}", color = TextMuted, fontSize = 11.sp)
                    }
                    TopBarButton(
                        onClick = {
                            if (current.path != null) FileOpener.share(context, listOf(current.path))
                            else shareUri(context, current.uri)
                        },
                        modifier = Modifier.size(44.dp)
                    ) { focused ->
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.action_share),
                            tint = if (focused) OnAccent else TextSecondary
                        )
                    }
                    if (current.path != null) {
                        TopBarButton(onClick = { confirmDelete = current }, modifier = Modifier.size(44.dp)) { focused ->
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.cd_delete),
                                tint = if (focused) OnAccent else ErrorRed
                            )
                        }
                    }
                }
            }
        }

        confirmDelete?.let { image ->
            DeleteDialog(
                title = stringResource(R.string.viewer_delete_title),
                bodyTrash = stringResource(R.string.viewer_delete_trash_body),
                bodyPermanent = stringResource(R.string.viewer_delete_permanent_body),
                trashAllowed = true,
                onDismiss = { confirmDelete = null },
                onConfirm = { permanent ->
                    confirmDelete = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            FileOps.deleteOrTrash(context, image.path.orEmpty(), permanent)
                        }
                        if (ok) {
                            list = list - image
                            onChanged()
                            if (list.isEmpty()) onClose()
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun ZoomableImage(uri: Uri, onTap: () -> Unit, onZoomChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(scale) { onZoomChange(scale > 1.01f) }

    val request = remember(uri) {
        ImageRequest.Builder(context).data(uri).size(2048).crossfade(true).build()
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else scale = 2.5f
                    }
                )
            }
            .pointerInput(Unit) {
                // Pinch always; one-finger pan only while zoomed, so the pager can still swipe at 1x.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multiTouch = event.changes.count { it.pressed } > 1
                        if (multiTouch || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            val pan = event.calculatePan()
                            val maxX = box.width * (scale - 1f) / 2f
                            val maxY = box.height * (scale - 1f) / 2f
                            offset = if (scale <= 1f) Offset.Zero else Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY)
                            )
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun shareUri(context: android.content.Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/*")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // nothing can handle it
    }
}
