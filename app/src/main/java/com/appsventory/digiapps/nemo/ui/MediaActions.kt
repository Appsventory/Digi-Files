package com.appsventory.digiapps.nemo.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MultipleStop
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.appsventory.digiapps.nemo.R
import com.appsventory.digiapps.nemo.data.FileItem
import com.appsventory.digiapps.nemo.ui.theme.CardDark
import com.appsventory.digiapps.nemo.ui.theme.CyanAccent
import com.appsventory.digiapps.nemo.ui.theme.TextPrimary
import com.appsventory.digiapps.nemo.ui.theme.TextSecondary
import com.appsventory.digiapps.nemo.util.FileOpener
import com.appsventory.digiapps.nemo.util.FileOps
import com.appsventory.digiapps.nemo.util.MediaEntry
import com.appsventory.digiapps.nemo.util.Vfs
import com.appsventory.digiapps.nemo.util.isSmb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/*
 * Long-press menu + "select multiple" shared by Gallery (photos), Videos and Music.
 *
 * Long press (touch) or hold OK (D-pad, fires on key up like everywhere else) opens the item menu:
 *   View / Play · Select multiple · Share · Details · Delete
 * While selecting: tap toggles an item, long press opens the bulk menu, and a pill at the bottom
 * offers the same actions. Back leaves selection first.
 *
 * Every label lives in strings.xml (media_* / action_*), so adding a language is just a new values-xx folder.
 */

/** Which items are ticked while selecting. Keys are [MediaEntry.id]. */
@Stable
class MediaSelection {
    var active by mutableStateOf(false)
        private set
    var marked by mutableStateOf<Set<Long>>(emptySet())
        private set

    /** Ticked items in the order they were ticked: "Select between" works from the last two. */
    private var order by mutableStateOf<List<Long>>(emptyList())

    /** Needs two ticked items to know which range is meant. */
    val canSelectBetween: Boolean get() = order.size >= 2

    fun isMarked(id: Long) = id in marked
    fun start(id: Long) {
        active = true
        marked = setOf(id)
        order = listOf(id)
    }
    fun toggle(id: Long) {
        if (id in marked) {
            marked = marked - id
            order = order - id
        } else {
            marked = marked + id
            order = order + id
        }
    }
    fun selectAll(ids: List<Long>) {
        marked = ids.toSet()
        order = ids
        active = ids.isNotEmpty()
    }

    /**
     * Toggle selection for a time group (or any id set):
     * - none selected in group → select all group ids
     * - all selected → deselect only those ids
     * - some selected → select the rest
     * Never deselects ids outside [groupIds].
     */
    fun applyGroupToggle(groupIds: List<Long>) {
        if (groupIds.isEmpty()) return
        val set = groupIds.toSet()
        val allSelected = set.all { it in marked }
        if (allSelected) {
            marked = marked - set
            order = order.filter { it !in set }
            if (marked.isEmpty()) active = false
        } else {
            active = true
            val added = groupIds.filterNot { it in marked }
            marked = marked + set
            order = order + added
        }
    }
    /** Ticks everything between the last two ticked items, in the order the list shows them ([visibleOrder]). */
    fun selectBetween(visibleOrder: List<Long>) {
        if (order.size < 2) return
        val a = visibleOrder.indexOf(order[order.size - 2])
        val b = visibleOrder.indexOf(order[order.size - 1])
        if (a < 0 || b < 0) return
        val range = visibleOrder.subList(minOf(a, b), maxOf(a, b) + 1)
        marked = marked + range
        order = order + range.filterNot { it in order }
    }
    fun clear() {
        active = false
        marked = emptySet()
        order = emptyList()
    }
    /** Drops marks of items that no longer exist (deleted elsewhere, list reloaded). */
    fun prune(valid: Set<Long>) {
        if (marked.any { it !in valid }) {
            marked = marked.filterTo(HashSet()) { it in valid }
            order = order.filter { it in valid }
        }
    }
}

/** State of the menus / dialogs of one media list. */
@Stable
class MediaActions {
    val selection = MediaSelection()
    var menuFor by mutableStateOf<MediaEntry?>(null)
    var bulkMenu by mutableStateOf(false)
    var deleting by mutableStateOf<List<MediaEntry>?>(null)
    var details by mutableStateOf<List<MediaEntry>?>(null)
    var renaming by mutableStateOf<MediaEntry?>(null)

    /** Long press on an item: its menu, or the bulk menu while selecting. */
    fun onLongPress(entry: MediaEntry) {
        if (selection.active) bulkMenu = true else menuFor = entry
    }

    /** Tap: toggles the mark while selecting, otherwise [open]. */
    fun onClick(entry: MediaEntry, open: () -> Unit) {
        if (selection.active) selection.toggle(entry.id) else open()
    }

    /** Only items with a real file path have details to show. */
    fun showDetails(list: List<MediaEntry>) {
        details = list.filter { it.path != null }.takeIf { it.isNotEmpty() }
    }
}

@Composable
fun rememberMediaActions(): MediaActions = remember { MediaActions() }

/** Round check mark drawn on a selected thumbnail / row. */
@Composable
fun CheckBadge(modifier: Modifier = Modifier) {
    Icon(
        Icons.Default.CheckCircle,
        contentDescription = null,
        tint = CyanAccent,
        modifier = modifier.padding(6.dp).size(20.dp).background(CardDark, CircleShape)
    )
}

/**
 * Menus, dialogs and the selection pill. Call it inside the Box that holds the list, after the list,
 * so the pill floats over its bottom edge.
 *
 * @param visible everything the list shows right now ("Select all" and bulk actions work on this)
 * @param onOpen what View / Play does for an item
 * @param onBeforeDelete e.g. stop the player when its song is about to be deleted
 * @param onShowInFolder jumps to the file browser at a folder (null = no "Show in folder" entry)
 * @param onChanged the files changed on disk: reload the list
 */
@Composable
fun BoxScope.MediaActionsUi(
    actions: MediaActions,
    visible: List<MediaEntry>,
    @StringRes openLabel: Int,
    openIcon: ImageVector,
    onOpen: (MediaEntry) -> Unit,
    onBeforeDelete: (List<MediaEntry>) -> Unit = {},
    onShowInFolder: ((String) -> Unit)? = null,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selection = actions.selection
    val byId = remember(visible) { visible.associateBy { it.id } }

    // items that vanished from the list (deleted, reloaded) can't stay selected
    androidx.compose.runtime.LaunchedEffect(visible) { selection.prune(byId.keys) }

    fun marked(): List<MediaEntry> = selection.marked.mapNotNull { byId[it] }
    fun share(list: List<MediaEntry>) {
        // network items have no local file to hand over
        FileOpener.share(context, list.filterNot { it.isSmb }.mapNotNull { it.path })
    }

    val labelSelectMultiple = stringResource(R.string.action_select_multiple)
    val labelSelectAll = stringResource(R.string.action_select_all)
    val labelShare = stringResource(R.string.action_share)
    val labelDetails = stringResource(R.string.action_details)
    val labelDelete = stringResource(R.string.action_delete)
    val labelDone = stringResource(R.string.action_done)
    val labelOpenWith = stringResource(R.string.action_open_with)
    val labelShowInFolder = stringResource(R.string.action_show_in_folder)
    val labelRename = stringResource(R.string.action_rename)
    val labelBetween = stringResource(R.string.action_select_between)

    // --- one item (long press) ---
    actions.menuFor?.let { entry ->
        val list = buildList {
            add(PillAction(openIcon, stringResource(openLabel), onClick = { onOpen(entry) }))
            add(PillAction(Icons.Default.CheckBox, labelSelectMultiple, onClick = { selection.start(entry.id) }))
            if (!entry.isSmb) add(PillAction(Icons.Default.Share, labelShare, onClick = { share(listOf(entry)) }))
            val path = entry.path
            // "Open with" and "Rename" need a real local file
            if (path != null && !entry.isSmb) {
                add(PillAction(Icons.AutoMirrored.Filled.OpenInNew, labelOpenWith, onClick = { FileOpener.open(context, path) }))
            }
            if (path != null && onShowInFolder != null) {
                add(PillAction(Icons.Default.FolderOpen, labelShowInFolder, onClick = {
                    Vfs.parent(path)?.let(onShowInFolder)
                }))
            }
            if (path != null && !entry.isSmb) {
                add(PillAction(Icons.Default.DriveFileRenameOutline, labelRename, onClick = { actions.renaming = entry }))
            }
            add(PillAction(Icons.Default.Info, labelDetails, onClick = { actions.showDetails(listOf(entry)) }))
            add(PillAction(Icons.Default.Delete, labelDelete, destructive = true, onClick = { actions.deleting = listOf(entry) }))
        }
        ContextMenuDialog(title = entry.title, actions = list, onDismiss = { actions.menuFor = null })
    }

    // --- the ticked items: same actions in the bulk menu (long press) and in the pill ---
    val bulkActions = listOfNotNull(
        PillAction(Icons.Default.Share, labelShare, onClick = { share(marked()) }),
        PillAction(Icons.Default.SelectAll, labelSelectAll, onClick = { selection.selectAll(visible.map { it.id }) }),
        // needs two ticked items to know the range: hidden until then
        if (selection.canSelectBetween) {
            PillAction(Icons.Default.MultipleStop, labelBetween, onClick = { selection.selectBetween(visible.map { it.id }) })
        } else null,
        PillAction(Icons.Default.Info, labelDetails, onClick = { actions.showDetails(marked()) }),
        PillAction(Icons.Default.Delete, labelDelete, destructive = true, onClick = {
            marked().takeIf { it.isNotEmpty() }?.let { actions.deleting = it }
        }),
        PillAction(Icons.Default.Done, labelDone, onClick = { selection.clear() })
    )
    if (selection.active && actions.bulkMenu) {
        ContextMenuDialog(
            title = stringResource(R.string.media_selected_count, selection.marked.size),
            actions = bulkActions,
            onDismiss = { actions.bulkMenu = false }
        )
    }
    if (selection.active) {
        ActionPill(
            actions = bulkActions,
            selectionCount = selection.marked.size,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
        )
        // Back leaves selection first
        BackHandler { selection.clear() }
    }

    actions.deleting?.let { items ->
        val count = items.size
        val res = context.resources
        DeleteDialog(
            title = stringResource(R.string.delete_confirm_title),
            bodyTrash = res.getQuantityString(R.plurals.delete_trash_body, count, count),
            bodyPermanent = res.getQuantityString(R.plurals.delete_confirm_body, count, count),
            trashAllowed = items.none { it.isSmb }, // network shares have no Trash
            onDismiss = { actions.deleting = null },
            onConfirm = { permanent ->
                actions.deleting = null
                onBeforeDelete(items)
                scope.launch {
                    withContext(Dispatchers.IO) {
                        items.forEach { e -> e.path?.let { FileOps.deleteOrTrash(context, it, permanent) } }
                    }
                    selection.clear()
                    delay(600) // MediaStore needs a moment to drop the rows
                    onChanged()
                }
            }
        )
    }

    actions.renaming?.let { entry ->
        val path = entry.path
        var text by remember(entry) { mutableStateOf(path?.let { Vfs.name(it) } ?: entry.title) }
        AlertDialog(
            onDismissRequest = { actions.renaming = null },
            containerColor = CardDark,
            title = { Text(stringResource(R.string.action_rename), color = TextPrimary) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    actions.renaming = null
                    if (path != null) {
                        scope.launch {
                            val renamed = withContext(Dispatchers.IO) { Vfs.rename(path, text) }
                            if (renamed == null) {
                                Toast.makeText(context, R.string.toast_rename_failed, Toast.LENGTH_SHORT).show()
                            } else {
                                // old name out of MediaStore, new one in
                                FileOps.scanMedia(context, listOf(path, renamed))
                                selection.clear()
                                delay(600.milliseconds)
                                onChanged()
                            }
                        }
                    }
                }) { Text(stringResource(R.string.action_rename), color = CyanAccent) }
            },
            dismissButton = {
                TextButton(onClick = { actions.renaming = null }) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            }
        )
    }

    actions.details?.let { list ->
        val items = list.mapNotNull { e ->
            e.path?.let { FileItem(Vfs.name(it), it, false, e.size, e.dateMillis) }
        }
        PropertiesDialog(items = items, onDismiss = { actions.details = null })
    }
}
