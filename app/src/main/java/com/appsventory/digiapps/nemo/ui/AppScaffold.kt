package com.appsventory.digiapps.nemo.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appsventory.digiapps.nemo.R
import com.appsventory.digiapps.nemo.ui.theme.*

enum class SideShortcut(@StringRes val labelRes: Int, val icon: ImageVector) {
    DOWNLOAD(R.string.shortcut_download, Icons.Default.Download),
    PICTURES(R.string.shortcut_pictures, Icons.Default.Image),
    MUSIC(R.string.shortcut_music, Icons.Default.MusicNote),
    MOVIES(R.string.shortcut_movies, Icons.Default.Movie),
    DOCUMENTS(R.string.shortcut_documents, Icons.Default.Description),
    APPLICATIONS(R.string.shortcut_applications, Icons.Default.Apps)
}

/** Phone bottom bar tabs, in display order. */
enum class ShellTab(@StringRes val labelRes: Int, val icon: ImageVector, val shortcut: SideShortcut?) {
    HOME(R.string.nav_home, Icons.Default.Home, null),
    DOWNLOAD(R.string.shortcut_download, Icons.Default.Download, SideShortcut.DOWNLOAD),
    PICTURES(R.string.shortcut_pictures, Icons.Default.Image, SideShortcut.PICTURES),
    MUSIC(R.string.shortcut_music, Icons.Default.MusicNote, SideShortcut.MUSIC),
    MOVIES(R.string.shortcut_movies, Icons.Default.Movie, SideShortcut.MOVIES);

    companion object {
        fun of(shortcut: SideShortcut?): ShellTab = entries.firstOrNull { it.shortcut == shortcut } ?: HOME
    }
}

data class ExploreVolume(
    val id: String,
    val title: String,
    val subtitle: String,
    val path: String,
    val icon: ImageVector,
    /** Network servers found by scanning but not saved yet: tapping asks for the login. */
    val saved: Boolean = true,
    /** SD card / USB drive: can be ejected. */
    val removable: Boolean = false,
    /** Filesystem name (ext4, F2FS, exFAT...) when known, shown on the storage card. */
    val fsType: String? = null
)

/** Kiri siku nempel frame, kanan full round */
private val HalfPillShape = RoundedCornerShape(
    topStart = 0.dp,
    bottomStart = 0.dp,
    topEnd = 100.dp,
    bottomEnd = 100.dp
)

@Composable
fun DigiSidebar(
    selectedShortcut: SideShortcut?,
    selectedVolumeId: String?,
    volumes: List<ExploreVolume>,
    onShortcut: (SideShortcut) -> Unit,
    onVolume: (ExploreVolume) -> Unit,
    onVolumeLongClick: (ExploreVolume) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var exploreExpanded by remember { mutableStateOf(true) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (exploreExpanded) 180f else 0f,
        animationSpec = tween(250),
        label = "exploreChevron"
    )

    Column(
        modifier
            .width(260.dp)
            .fillMaxHeight()
            .background(SurfaceDark)
            .padding(top = 20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = CyanAccent,
                modifier = Modifier.size(30.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.brand_digi), color = CyanAccent, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.width(5.dp))
            Text(stringResource(R.string.brand_files), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }

        Spacer(Modifier.height(20.dp))

        // Only this part scrolls; the brand header above stays fixed.
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp)
        ) {
        SideShortcut.entries.forEach { shortcut ->
            SidebarItem(
                icon = shortcut.icon,
                title = stringResource(shortcut.labelRes),
                selected = selectedShortcut == shortcut,
                onClick = { onShortcut(shortcut) }
            )
        }

        Spacer(Modifier.height(28.dp))

        // EXPLORE header — bukan item active/focus storage
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { exploreExpanded = !exploreExpanded }
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.section_explore),
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp
            )
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = stringResource(if (exploreExpanded) R.string.cd_collapse else R.string.cd_expand),
                tint = TextMuted,
                modifier = Modifier.size(20.dp).rotate(chevronRotation)
            )
        }

        AnimatedVisibility(
            visible = exploreExpanded,
            enter = expandVertically(animationSpec = tween(250)) + fadeIn(tween(200)),
            exit = shrinkVertically(animationSpec = tween(250)) + fadeOut(tween(150))
        ) {
            Column(Modifier.padding(top = 4.dp)) {
                volumes.forEach { vol ->
                    SidebarItem(
                        icon = vol.icon,
                        title = vol.title,
                        subtitle = vol.subtitle,
                        selected = vol.id == selectedVolumeId,
                        onClick = { onVolume(vol) },
                        onLongClick = { onVolumeLongClick(vol) }
                    )
                }
            }
        }
        }
    }
}

/**
 * Item sidebar tunggal (Quick access + daftar storage).
 * Prioritas warna: focus (FocusColor) > active (ActiveColor) > idle.
 * Tanpa outline — hanya warna bg.
 *
 * Focus dibaca dari interactionSource milik clickable (1 focus target).
 * Jangan pakai .focusable() + .clickable() bersamaan: dua target,
 * onFocusChanged jadi salah baca (isFocused false saat anak yang fokus).
 */
@Composable
private fun SidebarItem(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    onLongClick: (() -> Unit)? = null
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val hasSubtitle = !subtitle.isNullOrBlank()

    val bg = when {
        focused -> FocusColor
        selected -> ActiveColor
        else -> Color.Transparent
    }
    val iconTint = when {
        focused -> OnAccent
        selected -> CyanAccent
        else -> TextSecondary
    }
    val titleColor = when {
        focused -> OnAccent
        selected -> TextPrimary
        else -> if (hasSubtitle) TextPrimary else TextSecondary
    }
    val subtitleColor = if (focused) OnAccentVariant else TextMuted
    val emphasized = selected || focused

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 12.dp, top = 3.dp, bottom = 3.dp)
            .clip(HalfPillShape)
            .background(bg)
            .openOrMenuClickable(
                interactionSource = source,
                onOpen = onClick,
                onMenu = onLongClick
            )
            .padding(
                start = 20.dp,
                end = 16.dp,
                top = if (hasSubtitle) 11.dp else 12.dp,
                bottom = if (hasSubtitle) 11.dp else 12.dp
            )
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(if (hasSubtitle) 12.dp else 14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = titleColor,
                fontSize = if (hasSubtitle) 13.sp else 14.sp,
                fontWeight = when {
                    hasSubtitle && emphasized -> FontWeight.SemiBold
                    emphasized -> FontWeight.Medium
                    else -> FontWeight.Normal
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (hasSubtitle) {
                Text(
                    subtitle.orEmpty(),
                    color = subtitleColor,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Phone bottom bar. A pill slides behind the other tabs to the selected one and
 * settles with a small bounce; the selected icon pops the same way.
 */
@Composable
fun AnimatedBottomBar(selected: ShellTab, onSelect: (ShellTab) -> Unit) {
    val tabs = ShellTab.entries
    val pillWidth = 64.dp
    val pillHeight = 32.dp
    val topPad = 12.dp

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(SurfaceDark)
            .navigationBarsPadding()
    ) {
        val itemWidth = maxWidth / tabs.size
        val pillX by animateDpAsState(
            targetValue = itemWidth * selected.ordinal + (itemWidth - pillWidth) / 2,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
            label = "tabPill"
        )
        // drawn first = behind the tab items
        Box(
            Modifier
                .offset(x = pillX, y = topPad)
                .size(pillWidth, pillHeight)
                .clip(RoundedCornerShape(pillHeight / 2))
                .background(ActiveColor)
        )
        Row(Modifier.fillMaxWidth()) {
            tabs.forEach { tab ->
                BottomTab(
                    tab = tab,
                    selected = tab == selected,
                    iconBoxSize = DpSize(pillWidth, pillHeight),
                    topPad = topPad,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(tab) }
                )
            }
        }
    }
}

@Composable
private fun BottomTab(
    tab: ShellTab,
    selected: Boolean,
    iconBoxSize: DpSize,
    topPad: Dp,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.15f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "tabIconScale"
    )
    val tint by animateColorAsState(
        targetValue = if (selected) CyanAccent else TextMuted,
        animationSpec = tween(200),
        label = "tabTint"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(top = topPad, bottom = 12.dp)
    ) {
        Box(Modifier.size(iconBoxSize), contentAlignment = Alignment.Center) {
            Icon(
                tab.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(tab.labelRes), fontSize = 10.sp, color = tint, maxLines = 1)
    }
}

@Composable
fun ResponsiveShell(
    wide: Boolean,
    selectedShortcut: SideShortcut?,
    selectedVolumeId: String?,
    volumes: List<ExploreVolume>,
    selectedTab: ShellTab,
    onTab: (ShellTab) -> Unit,
    onShortcut: (SideShortcut) -> Unit,
    onVolume: (ExploreVolume) -> Unit,
    onVolumeLongClick: (ExploreVolume) -> Unit = {},
    content: @Composable () -> Unit
) {
    Box(Modifier.fillMaxSize().background(BgDark)) {
        if (wide) {
            Row(Modifier.fillMaxSize().navigationBarsPadding()) {
                DigiSidebar(
                    selectedShortcut = selectedShortcut,
                    selectedVolumeId = selectedVolumeId,
                    volumes = volumes,
                    onShortcut = onShortcut,
                    onVolume = onVolume,
                    onVolumeLongClick = onVolumeLongClick
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(BgDark)
                ) { content() }
            }
        } else {
            // The bar is part of the shell: it never moves, only the page inside slides.
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                AnimatedBottomBar(selected = selectedTab, onSelect = onTab)
            }
        }
    }
}
