package com.appsventory.digiapps.nemo.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.appsventory.digiapps.nemo.R
import com.appsventory.digiapps.nemo.data.FileItem
import com.appsventory.digiapps.nemo.ui.theme.CardDark
import com.appsventory.digiapps.nemo.ui.theme.CyanAccent
import com.appsventory.digiapps.nemo.ui.theme.TextMuted
import com.appsventory.digiapps.nemo.ui.theme.TextPrimary
import com.appsventory.digiapps.nemo.ui.theme.TextSecondary
import com.appsventory.digiapps.nemo.util.ArchiveFormat
import com.appsventory.digiapps.nemo.util.ArchivePasswordCache
import com.appsventory.digiapps.nemo.util.Archives
import com.appsventory.digiapps.nemo.util.FileOpener
import com.appsventory.digiapps.nemo.util.archiveBaseName
import com.appsventory.digiapps.nemo.util.archiveFormatOf
import com.appsventory.digiapps.nemo.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** One row of the archive viewer: a file, or a folder (also folders that only exist as part of file paths). */
private class Node(val name: String, val path: String, val isDirectory: Boolean, val size: Long, val modified: Long)

private fun norm(path: String) = path.replace('\\', '/').trim('/')

/** What is directly inside [dir] ("" = top level), folders first. */
private fun childrenOf(entries: List<Archives.Entry>, dir: String): List<Node> {
    val prefix = if (dir.isEmpty()) "" else "$dir/"
    val out = LinkedHashMap<String, Node>()
    for (e in entries) {
        val p = norm(e.path)
        if (!p.startsWith(prefix)) continue
        val rest = p.removePrefix(prefix)
        if (rest.isEmpty()) continue
        val name = rest.substringBefore('/')
        val isDir = rest.contains('/') || e.isDirectory
        val old = out[name]
        out[name] = if (old != null && old.isDirectory) old else Node(name, prefix + name, isDir, if (isDir) 0 else e.size, e.modified)
    }
    return out.values.sortedWith(compareByDescending<Node> { it.isDirectory }.thenBy { it.name.lowercase() })
}

/**
 * "View" for an archive: browse inside it without extracting everything. Tap a folder to enter it
 * (Back / the first row goes up), tap a file to unpack just that file and open it.
 */
@Composable
fun ArchiveViewDialog(
    archive: FileItem,
    /** Already checked by the caller (null: the archive isn't protected). */
    password: CharArray?,
    onExtract: (named: Boolean) -> Unit,
    /** Selective extract: only these entry paths (files and/or folders). Null destination uses parent of archive. */
    onExtractSelected: (entryPaths: List<String>) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val format: ArchiveFormat? = remember(archive.path) { archiveFormatOf(archive.name) }
    var dir by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf<String?>(null) }
    // Multi-select for selective extraction (Feature B). Paths are full archive entry paths.
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    fun toggleSelect(path: String) {
        selected = if (path in selected) selected - path else selected + path
    }

    // (readable, entries)
    val loaded by produceState<Pair<Boolean, List<Archives.Entry>>?>(null, archive.path) {
        value = withContext(Dispatchers.IO) {
            if (format == null) {
                false to emptyList()
            } else {
                runCatching { Archives.list(File(archive.path), format, password) }.fold({ true to it }, { false to emptyList() })
            }
        }
    }
    LaunchedEffect(loaded) {
        if (loaded?.first == false) {
            ArchivePasswordCache.forget(File(archive.path)) // a remembered password that doesn't open it is no good
            Toast.makeText(context, R.string.toast_archive_open_failed, Toast.LENGTH_LONG).show()
            onDismiss()
        }
    }

    val nodes = remember(loaded, dir) { loaded?.second?.let { childrenOf(it, dir) } }
    val first = remember { FocusRequester() }
    LaunchedEffect(dir, nodes != null) { if (nodes != null) runCatching { first.requestFocus() } }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    fun up() {
        dir = if (dir.contains('/')) dir.substringBeforeLast('/') else ""
    }

    Dialog(
        onDismissRequest = { if (dir.isNotEmpty()) up() else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.86f)
                .clip(RoundedCornerShape(20.dp))
                .background(CardDark)
                .padding(12.dp)
        ) {
            Text(archive.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("/$dir", color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected.isEmpty()) {
                    TextButton(onClick = { onDismiss(); onExtract(false) }) {
                        Text(stringResource(R.string.archive_extract_here), color = CyanAccent, fontSize = 12.sp)
                    }
                    TextButton(onClick = { onDismiss(); onExtract(true) }) {
                        Text(stringResource(R.string.archive_extract_to, archiveBaseName(archive.name)), color = CyanAccent, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    TextButton(onClick = {
                        val paths = selected.toList()
                        onDismiss()
                        onExtractSelected(paths)
                    }) {
                        Text(
                            stringResource(R.string.archive_extract_selected, selected.size),
                            color = CyanAccent,
                            fontSize = 12.sp
                        )
                    }
                    TextButton(onClick = { selected = emptySet() }) {
                        Text(stringResource(R.string.action_clear_selection), color = TextSecondary, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close), color = TextSecondary, fontSize = 12.sp) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    nodes == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = CyanAccent)
                    }
                    nodes.isEmpty() && dir.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.archive_empty), color = TextMuted, fontSize = 14.sp)
                    }
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
                        if (dir.isNotEmpty()) {
                            item(key = "..") {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .focusRequester(first)
                                        .focusCard(corner = 12.dp, onClick = { up() })
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.ArrowUpward, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text(stringResource(R.string.archive_up), color = TextPrimary, fontSize = 13.sp)
                                }
                            }
                        }
                        items(nodes, key = { it.path }) { node ->
                            val isFirst = dir.isEmpty() && node === nodes.first()
                            val marked = node.path in selected
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .then(if (isFirst) Modifier.focusRequester(first) else Modifier)
                                    .focusCard(
                                        corner = 12.dp,
                                        marked = marked,
                                        onLongClick = { toggleSelect(node.path) },
                                        onClick = {
                                            if (selected.isNotEmpty()) {
                                                // In selection mode, tap toggles membership
                                                toggleSelect(node.path)
                                            } else if (node.isDirectory) {
                                                dir = node.path
                                            } else if (opening == null && format != null) {
                                                scope.launch {
                                                    opening = node.path
                                                    val folder = File(context.cacheDir, "archive_view").apply { mkdirs() }
                                                    val file = File(folder, node.name)
                                                    val ok = withContext(Dispatchers.IO) {
                                                        runCatching { Archives.extractEntry(File(archive.path), format, node.path, file, password) }
                                                            .getOrDefault(false)
                                                    }
                                                    opening = null
                                                    if (ok) FileOpener.open(context, file.path)
                                                    else Toast.makeText(context, R.string.toast_archive_open_failed, Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (node.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                    null,
                                    tint = if (node.isDirectory) CyanAccent else TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(node.name, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val info = buildList {
                                        if (!node.isDirectory && node.size > 0) add(formatBytes(node.size))
                                        if (node.modified > 0) add(dateFormat.format(Date(node.modified)))
                                    }.joinToString("  ·  ")
                                    if (info.isNotEmpty()) Text(info, color = TextMuted, fontSize = 11.sp, maxLines = 1)
                                }
                                if (opening == node.path) CircularProgressIndicator(Modifier.size(16.dp), color = CyanAccent, strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}
