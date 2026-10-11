package com.appsventory.digiapps.nemo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appsventory.digiapps.nemo.R
import com.appsventory.digiapps.nemo.data.FileItem
import com.appsventory.digiapps.nemo.ui.theme.CardDark
import com.appsventory.digiapps.nemo.ui.theme.CyanAccent
import com.appsventory.digiapps.nemo.ui.theme.TextMuted
import com.appsventory.digiapps.nemo.ui.theme.TextPrimary
import com.appsventory.digiapps.nemo.util.Vfs
import com.appsventory.digiapps.nemo.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private class Stats(val bytes: Long, val files: Int, val folders: Int)

/** Details for one item, or a summary for several. Folder sizes are computed off the main thread. */
@Composable
fun PropertiesDialog(items: List<FileItem>, onDismiss: () -> Unit) {
    val stats by produceState<Stats?>(null, items) {
        value = withContext(Dispatchers.IO) {
            var bytes = 0L
            var files = 0
            var folders = 0
            items.forEach { item ->
                runCatching {
                    bytes += Vfs.sizeOf(item.path)
                    if (item.isDirectory) {
                        val (fi, fo) = Vfs.countContents(item.path)
                        files += fi
                        folders += fo
                    } else files++
                }
            }
            Stats(bytes, files, folders)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = {
            Text(
                if (items.size == 1) stringResource(R.string.action_properties)
                else stringResource(R.string.prop_selected, items.size),
                color = TextPrimary
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val calculating = stringResource(R.string.prop_calculating)
                if (items.size == 1) {
                    val item = items.first()
                    val isFolder = item.isDirectory
                    PropRow(R.string.prop_name, item.name)
                    PropRow(R.string.prop_location, Vfs.parent(item.path).orEmpty())
                    PropRow(
                        R.string.prop_type,
                        if (isFolder) stringResource(R.string.prop_type_folder)
                        else File(item.path).extension.uppercase().ifEmpty { stringResource(R.string.prop_type_file) }
                    )
                    PropRow(R.string.prop_size, stats?.let { "${formatBytes(it.bytes)} (${it.bytes} B)" } ?: calculating)
                    if (isFolder) {
                        PropRow(
                            R.string.prop_contents,
                            stats?.let { stringResource(R.string.prop_contents_value, it.files, it.folders) } ?: calculating
                        )
                    }
                    PropRow(
                        R.string.prop_modified,
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.lastModified))
                    )
                } else {
                    PropRow(R.string.prop_size, stats?.let { formatBytes(it.bytes) } ?: calculating)
                    PropRow(
                        R.string.prop_contents,
                        stats?.let { stringResource(R.string.prop_contents_value, it.files, it.folders) } ?: calculating
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done), color = CyanAccent) }
        }
    )
}

@Composable
private fun PropRow(labelRes: Int, value: String) {
    Text(stringResource(labelRes), color = TextMuted, fontSize = 11.sp)
    Text(value, color = TextPrimary, fontSize = 14.sp)
    Spacer(androidx.compose.ui.Modifier.height(10.dp))
}
