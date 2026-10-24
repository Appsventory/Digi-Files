package com.appsventory.digiapps.nemo.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.appsventory.digiapps.nemo.R
import com.appsventory.digiapps.nemo.ui.theme.BgDark
import com.appsventory.digiapps.nemo.ui.theme.CyanAccent
import com.appsventory.digiapps.nemo.ui.theme.TextPrimary
import com.appsventory.digiapps.nemo.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Full-screen PDF viewer using the platform [PdfRenderer] (no third-party library).
 * Pages are rendered on demand; the file descriptor is released when the dialog closes.
 */
@Composable
fun PdfViewer(
    uri: Uri,
    title: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var pageCount by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pfd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    val listState = rememberLazyListState()

    DisposableEffect(uri) {
        var localPfd: ParcelFileDescriptor? = null
        var localRenderer: PdfRenderer? = null
        try {
            localPfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (localPfd != null) {
                localRenderer = PdfRenderer(localPfd)
                pfd = localPfd
                renderer = localRenderer
                pageCount = localRenderer.pageCount
                loading = false
                if (pageCount == 0) error = context.getString(R.string.viewer_pdf_empty)
            } else {
                loading = false
                error = context.getString(R.string.viewer_open_failed)
            }
        } catch (e: SecurityException) {
            loading = false
            error = context.getString(R.string.viewer_open_failed)
        } catch (e: Exception) {
            loading = false
            error = context.getString(R.string.viewer_pdf_error)
            runCatching { localRenderer?.close() }
            runCatching { localPfd?.close() }
        }
        onDispose {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            renderer = null
            pfd = null
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(BgDark)
                .statusBarsPadding()
        ) {
            DocumentViewerTopBar(title = title, subtitle = if (pageCount > 0) {
                stringResource(R.string.viewer_pdf_pages, pageCount)
            } else null, onClose = onClose)

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CyanAccent)
                }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error!!, color = TextSecondary, modifier = Modifier.padding(24.dp))
                }
                else -> {
                    val r = renderer
                    if (r != null) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed((0 until pageCount).toList()) { index, _ ->
                                PdfPageItem(renderer = r, pageIndex = index)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPageItem(renderer: PdfRenderer, pageIndex: Int) {
    var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }
    val density = LocalDensity.current
    // Target ~ screen width at 2x for crispness; cap to keep memory bounded
    val targetWidthPx = with(density) { 720.dp.roundToPx() }.coerceIn(480, 1440)

    LaunchedEffect(pageIndex, renderer) {
        bitmap = withContext(Dispatchers.IO) {
            synchronized(renderer) {
                runCatching {
                    renderer.openPage(pageIndex).use { page ->
                        val scale = targetWidthPx.toFloat() / page.width.coerceAtLeast(1)
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }.getOrNull()
            }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            var scale by remember { mutableFloatStateOf(1f) }
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = stringResource(R.string.viewer_pdf_page, pageIndex + 1),
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer(scaleX = scale, scaleY = scale)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 4f)
                        }
                    }
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = CyanAccent, modifier = Modifier.size(28.dp))
            }
        }
    }
}

/**
 * Full-screen plain-text viewer. Reads via [ContentResolver.openInputStream],
 * limits size, and prefers UTF-8 with a simple BOM/heuristic fallback.
 */
@Composable
fun TextViewer(
    uri: Uri,
    title: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var truncated by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(uri) {
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) {
            runCatching { readTextLimited(context, uri, MAX_TEXT_BYTES) }
        }
        loading = false
        result.onSuccess { (content, wasTruncated) ->
            text = content
            truncated = wasTruncated
        }.onFailure {
            error = context.getString(R.string.viewer_open_failed)
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(BgDark)
                .statusBarsPadding()
        ) {
            DocumentViewerTopBar(
                title = title,
                subtitle = if (truncated) stringResource(R.string.viewer_text_truncated) else null,
                onClose = onClose
            )
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CyanAccent)
                }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error!!, color = TextSecondary, modifier = Modifier.padding(24.dp))
                }
                else -> {
                    val scroll = rememberScrollState()
                    Text(
                        text = text.orEmpty().ifEmpty { stringResource(R.string.viewer_text_empty) },
                        color = TextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DocumentViewerTopBar(title: String, subtitle: String?, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 16.sp,
                maxLines = 1
            )
            if (subtitle != null) {
                Text(text = subtitle, color = TextSecondary, fontSize = 12.sp)
            }
        }
        TextButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel), tint = TextPrimary)
        }
    }
    Spacer(Modifier.height(4.dp))
}

private const val MAX_TEXT_BYTES = 2 * 1024 * 1024 // 2 MiB

private fun readTextLimited(
    context: android.content.Context,
    uri: Uri,
    maxBytes: Int
): Pair<String, Boolean> {
    val stream = context.contentResolver.openInputStream(uri)
        ?: throw IllegalStateException("Cannot open stream")
    stream.use { raw ->
        val buffered = BufferedInputStream(raw)
        buffered.mark(4)
        val bom = ByteArray(3)
        val n = buffered.read(bom)
        buffered.reset()
        val charset: Charset = when {
            n >= 3 && bom[0] == 0xEF.toByte() && bom[1] == 0xBB.toByte() && bom[2] == 0xBF.toByte() -> {
                buffered.skip(3)
                StandardCharsets.UTF_8
            }
            n >= 2 && bom[0] == 0xFF.toByte() && bom[1] == 0xFE.toByte() -> {
                buffered.skip(2)
                Charset.forName("UTF-16LE")
            }
            n >= 2 && bom[0] == 0xFE.toByte() && bom[1] == 0xFF.toByte() -> {
                buffered.skip(2)
                Charset.forName("UTF-16BE")
            }
            else -> StandardCharsets.UTF_8
        }
        val buffer = ByteArray(maxBytes + 1)
        var total = 0
        while (total < buffer.size) {
            val read = buffered.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        val truncated = total > maxBytes
        val use = if (truncated) maxBytes else total
        val text = String(buffer, 0, use, charset)
        return text to truncated
    }
}
