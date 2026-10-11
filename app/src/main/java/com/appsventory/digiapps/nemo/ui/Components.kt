package com.appsventory.digiapps.nemo.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import com.appsventory.digiapps.nemo.util.ConflictPolicy
import com.appsventory.digiapps.nemo.util.FileTasks
import com.appsventory.digiapps.nemo.util.formatBytes
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
//import androidx.compose.animation.core.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
//import androidx.compose.foundation.animateContentSize
import androidx.compose.material3.IconButton
import com.appsventory.digiapps.nemo.R
import com.appsventory.digiapps.nemo.ui.theme.*

/** Focusable top bar button: focus = FocusColor bg (same rule as sidebar). */
@Composable
fun TopBarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    content: @Composable (focused: Boolean) -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(
        modifier
            .clip(shape)
            .background(if (focused) FocusColor else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content(focused)
    }
}

@Composable
fun SectionHeader(@StringRes textRes: Int, top: Dp) {
    Text(
        stringResource(textRes),
        color = TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = top, bottom = 4.dp)
    )
}

/** Screen frame for secondary activities: back button + title + content. */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize().background(BgDark)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TopBarButton(onClick = onBack, modifier = Modifier.size(40.dp)) { focused ->
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = if (focused) OnAccent else TextSecondary
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        // content fills the middle; a bottom bar (phone) pads the nav bar itself,
        // otherwise the content keeps clear of the system navigation bar
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (bottomBar == null) Modifier.navigationBarsPadding() else Modifier)
        ) { content() }
        bottomBar?.invoke()
    }
}

data class PillAction(
    val icon: ImageVector,
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

/** Shared visual content for the pill: an optional selection-count badge, then the actions. */
@Composable
private fun PillContentRow(actions: List<PillAction>, selectionCount: Int?) {
    Row(
        Modifier.padding(vertical = 6.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionCount != null && selectionCount > 0) {
            Box(
                Modifier
                    .padding(start = 2.dp, end = 8.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(CyanAccent),
                contentAlignment = Alignment.Center
            ) {
                Text(selectionCount.toString(), color = OnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            actions.forEach { action ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .widthIn(min = 52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceDark)
                        .clickable(onClick = action.onClick)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        action.icon,
                        contentDescription = null,
                        tint = if (action.destructive) ErrorRed else CyanAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        action.label,
                        color = if (action.destructive) ErrorRed else TextSecondary,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** Floating pill with icon+label actions (touch devices; TV shows the same actions in the menu).
 *  When [selectionCount] is set, a small round counter badge leads the pill. */
@Composable
fun ActionPill(actions: List<PillAction>, modifier: Modifier = Modifier, selectionCount: Int? = null) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier
            .shadow(10.dp, shape)
            .clip(shape)
            .background(CardDark)
    ) {
        PillContentRow(actions, selectionCount)
    }
}

/**
 * The Create-folder FAB and the selection pill are the same floating control at different
 * moments, not two separate ones - this morphs one into the other: the circular button expands
 * sideways into the pill (and its brand-color background fades to the pill's card color) when
 * [expanded] turns true, and reverses the same way when it turns false.
 */
@Composable
fun MorphingActionBar(
    expanded: Boolean,
    collapsedIcon: ImageVector,
    collapsedDescription: String,
    onCollapsedClick: () -> Unit,
    actions: List<PillAction>,
    selectionCount: Int?,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(28.dp)
    val background by animateColorAsState(
        targetValue = if (expanded) CardDark else CyanAccent,
        animationSpec = tween(280),
        label = "morphBarBg"
    )
    Box(
        modifier
            .shadow(10.dp, shape)
            .clip(shape)
            .background(background)
            .animateContentSize(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = expanded,
            transitionSpec = {
                (fadeIn(tween(220, delayMillis = 90)) + scaleIn(initialScale = 0.85f, animationSpec = tween(220, delayMillis = 90))) togetherWith
                    (fadeOut(tween(120)) + scaleOut(targetScale = 0.85f, animationSpec = tween(120)))
            },
            label = "morphBarContent"
        ) { isExpanded ->
            if (!isExpanded) {
                IconButton(onClick = onCollapsedClick, modifier = Modifier.size(56.dp)) {
                    Icon(collapsedIcon, contentDescription = collapsedDescription, tint = OnAccent)
                }
            } else {
                PillContentRow(actions, selectionCount)
            }
        }
    }
}

/**
 * Segmented chips (focus = cyan, active = #212121 — same rule as the sidebar).
 * [wrap]: chips flow onto the next line when they don't fit (dialogs are narrow), instead of being squeezed
 * into one row where long labels break letter by letter.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChipTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** TV / DPAD: moving focus onto a tab opens it (no click needed). */
    autoSelectOnFocus: Boolean = false,
    wrap: Boolean = false
) {
    // moving focus back up into the tabs lands on the selected one (or the last focused one)
    val selectedChip = remember { FocusRequester() }
    val chips: @Composable () -> Unit = {
        labels.forEachIndexed { index, label ->
            val source = remember { MutableInteractionSource() }
            val focused by source.collectIsFocusedAsState()
            val isSelected = index == selected
            LaunchedEffect(focused) {
                if (focused && autoSelectOnFocus && !isSelected) onSelect(index)
            }
            Text(
                label,
                fontSize = 13.sp,
                maxLines = 1,
                softWrap = false, // a chip label is one word: never break it
                fontWeight = if (isSelected || focused) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    focused -> OnAccent
                    isSelected -> TextPrimary
                    else -> TextSecondary
                },
                modifier = Modifier
                    .then(if (isSelected) Modifier.focusRequester(selectedChip) else Modifier)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        when {
                            focused -> FocusColor
                            isSelected -> ActiveColor
                            else -> Color.Transparent
                        }
                    )
                    .clickable(interactionSource = source, indication = null) { onSelect(index) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
    if (wrap) {
        androidx.compose.foundation.layout.FlowRow(
            modifier.focusRestorer(selectedChip).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) { chips() }
    } else {
        Row(
            modifier
                .focusRestorer(selectedChip)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) { chips() }
    }
}

/** Progress of a running copy/move, or the result (with Undo) of a finished operation. */
@Composable
fun TaskBanner(tasks: FileTasks, modifier: Modifier = Modifier) {
    val progress = tasks.progress
    val notice = tasks.notice

    // auto-dismiss finished notices
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(8000)
            tasks.dismissNotice()
        }
    }
    if (progress == null && notice == null) return

    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .fillMaxWidth()
            .shadow(8.dp, shape)
            .clip(shape)
            .background(CardDark)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (progress != null) {
            val preparing = progress.total <= 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(progress.label, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (preparing) stringResource(R.string.task_preparing) else listOfNotNull(
                            if (progress.queued > 0) stringResource(R.string.task_queued, progress.queued) else null,
                            stringResource(R.string.task_progress, formatBytes(progress.done), formatBytes(progress.total)),
                            if (progress.itemCount > 1 && progress.itemIndex > 0)
                                stringResource(R.string.task_item_of, progress.itemIndex, progress.itemCount) else null,
                            progress.current.takeIf { it.isNotEmpty() }
                        ).joinToString("  ·  "),
                        color = TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (progress.queued > 0) {
                    TextButton(onClick = { tasks.cancelAll() }) {
                        Text(stringResource(R.string.task_cancel_all), color = TextSecondary)
                    }
                }
                TextButton(onClick = { tasks.cancel() }) {
                    Text(stringResource(R.string.action_cancel), color = CyanAccent)
                }
            }
            Spacer(Modifier.height(8.dp))
            val barModifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
            if (preparing) {
                LinearProgressIndicator(modifier = barModifier, color = CyanAccent, trackColor = DividerDark)
            } else {
                LinearProgressIndicator(
                    progress = { (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) },
                    modifier = barModifier,
                    color = CyanAccent,
                    trackColor = DividerDark
                )
            }
        } else if (notice != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(notice.message, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                notice.undo?.let { undo ->
                    TextButton(onClick = undo) {
                        Text(stringResource(R.string.action_undo), color = CyanAccent)
                    }
                }
                TopBarButton(onClick = { tasks.dismissNotice() }, modifier = Modifier.size(36.dp)) { focused ->
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_close),
                        tint = if (focused) OnAccent else TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Delete confirmation with a "Delete permanently" checkbox.
 * Unchecked = move to Trash (rename with the .trashed prefix). [trashAllowed] false = no checkbox, always permanent.
 */
@Composable
fun DeleteDialog(
    title: String,
    bodyTrash: String,
    bodyPermanent: String,
    trashAllowed: Boolean,
    onConfirm: (permanent: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var permanent by remember { mutableStateOf(!trashAllowed) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = { Text(title, color = TextPrimary) },
        text = {
            Column {
                Text(if (permanent) bodyPermanent else bodyTrash, color = TextSecondary, fontSize = 14.sp)
                if (trashAllowed) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { permanent = !permanent },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = permanent,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = ErrorRed,
                                checkmarkColor = OnError,
                                uncheckedColor = TextMuted
                            ),
                            modifier = Modifier.padding(end = 10.dp, top = 8.dp, bottom = 8.dp)
                        )
                        Text(stringResource(R.string.delete_permanently), color = TextPrimary, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(permanent) }) {
                Text(stringResource(R.string.action_delete), color = ErrorRed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = TextSecondary) }
        }
    )
}

/** "Already exists": Keep both / Replace / Skip for every clashing name at once. */
@Composable
fun ConflictDialog(
    names: List<String>,
    trashHint: Boolean,
    onChoose: (ConflictPolicy) -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = { Text(stringResource(R.string.conflict_title), color = TextPrimary) },
        text = {
            Column {
                Text(
                    if (names.size == 1) stringResource(R.string.conflict_one, names[0])
                    else stringResource(R.string.conflict_many, names.size),
                    color = TextSecondary,
                    fontSize = 14.sp
                )
                if (trashHint) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.conflict_trash_hint), color = TextMuted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                // safest choice first (and focused): nothing is lost or overwritten
                TextButton(
                    onClick = { onChoose(ConflictPolicy.KEEP_BOTH) },
                    modifier = Modifier.fillMaxWidth().focusRequester(first)
                ) { Text(stringResource(R.string.conflict_keep_both), color = CyanAccent) }
                TextButton(
                    onClick = { onChoose(ConflictPolicy.REPLACE) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.conflict_replace), color = ErrorRed) }
                TextButton(
                    onClick = { onChoose(ConflictPolicy.SKIP) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.conflict_skip), color = TextPrimary) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = TextSecondary) }
        }
    )
}

/** Long-press menu for one item: a focusable list of actions (works with touch and DPAD). */
@Composable
fun ContextMenuDialog(title: String, actions: List<PillAction>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(CardDark)
                .padding(vertical = 12.dp, horizontal = 8.dp)
        ) {
            Text(
                title,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
            // long menus (media items have 8 entries) scroll instead of running off a short screen
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            actions.forEachIndexed { index, action ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                        .focusCard(
                            corner = 12.dp,
                            onClick = {
                                onDismiss()
                                action.onClick()
                            }
                        )
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        action.icon,
                        contentDescription = null,
                        tint = if (action.destructive) ErrorRed else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        action.label,
                        color = if (action.destructive) ErrorRed else TextPrimary,
                        fontSize = 14.sp
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            }
        }
    }
}
