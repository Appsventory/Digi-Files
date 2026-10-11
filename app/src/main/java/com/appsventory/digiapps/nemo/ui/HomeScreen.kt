package com.appsventory.digiapps.nemo.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import com.appsventory.digiapps.nemo.ui.theme.LocalIsDark
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appsventory.digiapps.nemo.ui.theme.*
import android.content.Context
import com.appsventory.digiapps.nemo.R
import com.appsventory.digiapps.nemo.util.AppPrefs
import com.appsventory.digiapps.nemo.util.freeLabel
import com.appsventory.digiapps.nemo.util.storageStats
import java.io.File

data class StorageCardData(
    val title: String,
    val freeLabel: String,
    val usedFraction: Float,
    val path: String,
    val icon: ImageVector,
    val extraLine: String? = null
)

enum class ToolAction {
    TRASH, PICTURES, MUSIC, MOVIES, USER_APPS, SYSTEM_SETTINGS, QUICK_SHARE, SETTINGS, EDIT, ADD_STORAGE
}

data class ToolItem(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val action: ToolAction
)

/** Every tool the Home grid can show, in display order. EDIT is always shown. */
private val ALL_TOOLS = listOf(
    ToolItem(R.string.tool_trash, Icons.Default.Delete, ToolAction.TRASH),
    ToolItem(R.string.shortcut_pictures, Icons.Default.Image, ToolAction.PICTURES),
    ToolItem(R.string.shortcut_music, Icons.Default.MusicNote, ToolAction.MUSIC),
    ToolItem(R.string.shortcut_movies, Icons.Default.Movie, ToolAction.MOVIES),
    ToolItem(R.string.tool_user_apps, Icons.Default.Apps, ToolAction.USER_APPS),
    ToolItem(R.string.tool_system_settings, Icons.Default.Settings, ToolAction.SYSTEM_SETTINGS),
    ToolItem(R.string.tool_quick_share, Icons.Default.Share, ToolAction.QUICK_SHARE),
    ToolItem(R.string.menu_settings, Icons.Default.Tune, ToolAction.SETTINGS),
    ToolItem(R.string.tool_edit, Icons.Default.Edit, ToolAction.EDIT)
)

@Composable
fun HomeScreen(
    volumes: List<ExploreVolume>,
    onOpenPath: (String) -> Unit,
    onVolumeLongClick: (ExploreVolume) -> Unit = {},
    onTool: (ToolAction) -> Unit = {},
    isTv: Boolean = false
) {
    var homeMenuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val cards = remember(volumes) { buildStorageCards(context, volumes) }
    var hiddenTools by remember { mutableStateOf(AppPrefs.hiddenTools(context)) }
    var showEditDialog by remember { mutableStateOf(false) }
    val tools = ALL_TOOLS.filter { it.action == ToolAction.EDIT || it.action.name !in hiddenTools }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val isCompact = maxWidth < 600.dp
        val hPad = if (isCompact) 16.dp else 28.dp

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = hPad, vertical = 20.dp)
        ) {
            // Header
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.home_title),
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp
                )
                // Touch devices: the Add-storage card is replaced by this menu (a "+" card the
                // user has to scroll to felt out of place among storage cards on a phone).
                // TV/DPAD keeps the focusable card below instead - simpler to reach with a remote.
                if (!isTv) {
                    Box {
                        TopBarButton(onClick = { homeMenuOpen = true }, modifier = Modifier.size(32.dp)) { focused ->
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.cd_more_options),
                                tint = if (focused) OnAccent else TextSecondary
                            )
                        }
                        DropdownMenu(
                            expanded = homeMenuOpen,
                            onDismissRequest = { homeMenuOpen = false },
                            containerColor = CardDark
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.smb_add_title), color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Default.Add, null, tint = CyanAccent) },
                                onClick = {
                                    homeMenuOpen = false
                                    onTool(ToolAction.ADD_STORAGE)
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Storage cards — long-press SMB cards to edit/forget connection
            if (isCompact) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    cards.forEach { card ->
                        val vol = volumes.find { it.path == card.path }
                        StorageCard(
                            card = card,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onOpenPath(card.path) },
                            onLongClick = if (vol != null && (card.path.startsWith("smb://") || vol.removable)) {
                                { onVolumeLongClick(vol) }
                            } else null
                        )
                    }
                    if (isTv) AddStorageCard(Modifier.fillMaxWidth()) { onTool(ToolAction.ADD_STORAGE) }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    cards.forEach { card ->
                        val vol = volumes.find { it.path == card.path }
                        StorageCard(
                            card = card,
                            modifier = Modifier.weight(1f),
                            onClick = { onOpenPath(card.path) },
                            onLongClick = if (vol != null && (card.path.startsWith("smb://") || vol.removable)) {
                                { onVolumeLongClick(vol) }
                            } else null
                        )
                    }
                    if (isTv) AddStorageCard(Modifier.weight(0.7f)) { onTool(ToolAction.ADD_STORAGE) }
                }
            }

            Spacer(Modifier.height(32.dp))

            // TOOLS
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.home_tools),
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp
                )
            }

            Spacer(Modifier.height(16.dp))

            ToolGrid(tools) { tool ->
                if (tool.action == ToolAction.EDIT) showEditDialog = true else onTool(tool.action)
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showEditDialog) {
        EditToolsDialog(
            hidden = hiddenTools,
            onToggle = { action ->
                hiddenTools = if (action.name in hiddenTools) hiddenTools - action.name else hiddenTools + action.name
                AppPrefs.setHiddenTools(context, hiddenTools)
            },
            onDismiss = { showEditDialog = false }
        )
    }
}

/**
 * Full-width tool grid: as many columns as fit, spread edge to edge
 * (space-between), wraps to new rows, last row padded so columns line up.
 */
@Composable
private fun ToolGrid(tools: List<ToolItem>, onClick: (ToolItem) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = maxOf(1, ((maxWidth + TOOL_MIN_GAP) / (TOOL_CELL_WIDTH + TOOL_MIN_GAP)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tools.chunked(cols).forEach { rowTools ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    rowTools.forEach { tool -> ToolCell(tool) { onClick(tool) } }
                    repeat(cols - rowTools.size) { Spacer(Modifier.width(TOOL_CELL_WIDTH)) }
                }
            }
        }
    }
}

private val TOOL_CELL_WIDTH = 80.dp
private val TOOL_MIN_GAP = 8.dp

/** Icon + label group, centered both ways inside a fixed-size cell. */
@Composable
private fun ToolCell(tool: ToolItem, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .size(width = TOOL_CELL_WIDTH, height = 96.dp)
            .focusCard(
                corner = 12.dp,
                interactionSource = source,
                onClick = onClick
            )
            .padding(4.dp)
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(if (focused) FocusColor else CyanAccent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                tool.icon,
                contentDescription = stringResource(tool.labelRes),
                tint = if (focused) OnAccent else colorResource(if (LocalIsDark.current) R.color.on_accent else R.color.on_accent_light),
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(tool.labelRes),
            color = if (focused) TextPrimary else TextSecondary,
            fontSize = 11.sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Add / remove tools from the Home grid. Saved immediately. */
@Composable
private fun EditToolsDialog(
    hidden: Set<String>,
    onToggle: (ToolAction) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = { Text(stringResource(R.string.tools_edit_title), color = TextPrimary) },
        text = {
            Column {
                ALL_TOOLS.filter { it.action != ToolAction.EDIT }.forEach { tool ->
                    val checked = tool.action.name !in hidden
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onToggle(tool.action) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = CyanAccent,
                                checkmarkColor = OnAccent,
                                uncheckedColor = TextMuted
                            ),
                            modifier = Modifier.padding(12.dp)
                        )
                        Text(stringResource(tool.labelRes), color = TextPrimary, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done), color = CyanAccent)
            }
        }
    )
}

@Composable
private fun StorageCard(
    card: StorageCardData,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()

    Column(
        modifier
            .height(120.dp)
            .focusCard(
                corner = 14.dp,
                interactionSource = source,
                onLongClick = onLongClick,
                onClick = onClick
            )
    ) {
        // top accent strip
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(if (focused) FocusColor else CyanAccent)
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    card.icon,
                    null,
                    tint = if (focused) FocusColor else CyanAccent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        card.title,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        card.freeLabel,
                        color = if (focused) FocusColor else CyanAccent,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                    if (card.extraLine != null) {
                        Text(
                            card.extraLine,
                            color = TextMuted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            LinearProgressIndicator(
                progress = { card.usedFraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (focused) FocusColor else CyanAccent,
                trackColor = DividerDark
            )
        }
    }
}

@Composable
private fun AddStorageCard(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()

    Box(
        modifier
            .height(120.dp)
            .focusCard(
                corner = 14.dp,
                dashedWhenIdle = true,
                interactionSource = source,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Add,
            null,
            tint = if (focused) FocusColor else CyanAccent,
            modifier = Modifier.size(36.dp)
        )
    }
}

private fun buildStorageCards(context: Context, volumes: List<ExploreVolume>): List<StorageCardData> {
    return volumes.map { vol ->
        val isSmb = vol.path.startsWith("smb://")
        val (used, total) = if (isSmb) Pair(0L, 0L) else storageStats(File(vol.path))
        val free = (total - used).coerceAtLeast(0)
        val frac = if (total > 0) used.toFloat() / total else 0f
        StorageCardData(
            title = vol.title,
            freeLabel = if (isSmb) vol.subtitle else context.freeLabel(free),
            usedFraction = frac,
            path = vol.path,
            icon = vol.icon,
            extraLine = vol.fsType
        )
    }
}
