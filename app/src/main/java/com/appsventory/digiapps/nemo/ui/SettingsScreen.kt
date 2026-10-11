package com.appsventory.digiapps.nemo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appsventory.digiapps.nemo.AppNavigator
import com.appsventory.digiapps.nemo.R
import com.appsventory.digiapps.nemo.ui.theme.*
import com.appsventory.digiapps.nemo.smb.SmbConnection
import com.appsventory.digiapps.nemo.smb.SmbStore
import com.appsventory.digiapps.nemo.util.AppSettings
import com.appsventory.digiapps.nemo.util.BrandColor
import com.appsventory.digiapps.nemo.util.DeviceVolume
import com.appsventory.digiapps.nemo.util.ExtraMediaCache
import com.appsventory.digiapps.nemo.util.MediaRepo
import com.appsventory.digiapps.nemo.util.ThemeMode
import com.appsventory.digiapps.nemo.util.VolumeKind
import com.appsventory.digiapps.nemo.util.detectVolumes

/** Standalone settings screen (phone). */
@Composable
fun SettingsScreen(title: String, onBack: () -> Unit) {
    ScreenScaffold(title = title, onBack = onBack) { SettingsContent() }
}

/** Settings as a page inside the shell (large screens / TV): the sidebar stays visible. */
@Composable
fun SettingsPage(onOpenUpdate: () -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.menu_settings),
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        )
        SettingsContent(onOpenUpdate = onOpenUpdate)
    }
}

private enum class SettingsDialog { VIEW, THEME, ACCENT, TRASH, MEDIA_SOURCES }

private class Choice<T>(val label: String, val value: T, val swatch: Color? = null)

@Composable
private fun SettingsContent(onOpenUpdate: (() -> Unit)? = null) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()
    }
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val dark = LocalIsDark.current
    val res = context.resources

    val themeLabel = when (AppSettings.themeMode) {
        ThemeMode.DARK -> stringResource(R.string.theme_dark)
        ThemeMode.LIGHT -> stringResource(R.string.theme_light)
        ThemeMode.AUTO -> stringResource(R.string.theme_auto)
    }
    val brandLabel = brandName(AppSettings.brand)
    val trashDays = AppSettings.trashRetentionDays
    val trashLabel = if (trashDays == 0) stringResource(R.string.trash_keep)
    else res.getQuantityString(R.plurals.trash_after_days, trashDays, trashDays)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader(R.string.settings_general, top = 8.dp)
        SettingsRow(Icons.Default.Language, R.string.settings_language, stringResource(R.string.settings_language_value))
        SettingsRow(
            Icons.Default.GridView, R.string.settings_default_view,
            stringResource(if (AppSettings.defaultGrid) R.string.settings_view_grid_value else R.string.settings_view_list_value),
            onClick = { dialog = SettingsDialog.VIEW }
        )
        SettingsRow(
            Icons.Default.Visibility, R.string.menu_show_hidden, value = "",
            trailing = {
                Switch(
                    checked = AppSettings.showHidden,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = OnAccent,
                        checkedTrackColor = CyanAccent,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = DividerDark
                    )
                )
            },
            onClick = { AppSettings.setShowHidden(!AppSettings.showHidden) }
        )
        SettingsRow(
            Icons.Default.Archive, R.string.settings_delete_archive_after_extract, value = "",
            trailing = {
                Switch(
                    checked = AppSettings.deleteArchiveAfterExtract,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = OnAccent,
                        checkedTrackColor = CyanAccent,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = DividerDark
                    )
                )
            },
            onClick = { AppSettings.setDeleteArchiveAfterExtract(!AppSettings.deleteArchiveAfterExtract) }
        )

        SectionHeader(R.string.settings_appearance, top = 16.dp)
        SettingsRow(Icons.Default.DarkMode, R.string.settings_theme, themeLabel, onClick = { dialog = SettingsDialog.THEME })
        SettingsRow(
            Icons.Default.Palette, R.string.settings_accent, brandLabel,
            trailing = { Swatch(CyanAccent) },
            onClick = { dialog = SettingsDialog.ACCENT }
        )

        SectionHeader(R.string.settings_storage, top = 16.dp)
        SettingsRow(Icons.Default.Delete, R.string.settings_trash, trashLabel, onClick = { dialog = SettingsDialog.TRASH })
        val sourceCount = AppSettings.enabledMediaSources.size
        SettingsRow(
            Icons.Default.PermMedia, R.string.settings_media_sources,
            if (sourceCount == 0) stringResource(R.string.settings_media_sources_none)
            else res.getQuantityString(R.plurals.settings_media_sources_count, sourceCount, sourceCount),
            onClick = { dialog = SettingsDialog.MEDIA_SOURCES }
        )
        SettingsRow(
            Icons.Default.Image, R.string.settings_smb_thumbnails, value = "",
            trailing = {
                Switch(
                    checked = AppSettings.smbThumbnails,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = OnAccent,
                        checkedTrackColor = CyanAccent,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = DividerDark
                    )
                )
            },
            onClick = { AppSettings.setSmbThumbnails(!AppSettings.smbThumbnails) }
        )

        SectionHeader(R.string.settings_about, top = 16.dp)
        SettingsRow(Icons.Default.Info, R.string.settings_version, version, onClick = { onOpenUpdate?.invoke() ?: AppNavigator.openUpdateCheck(context) })
        Spacer(Modifier.height(16.dp))
    }

    when (dialog) {
        SettingsDialog.VIEW -> ChoiceDialog(
            title = stringResource(R.string.settings_default_view),
            options = listOf(
                Choice(stringResource(R.string.settings_view_grid_value), true),
                Choice(stringResource(R.string.settings_view_list_value), false)
            ),
            selected = AppSettings.defaultGrid,
            onSelect = { AppSettings.setDefaultGrid(it) },
            onDismiss = { dialog = null }
        )
        SettingsDialog.THEME -> ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = listOf(
                Choice(stringResource(R.string.theme_dark), ThemeMode.DARK),
                Choice(stringResource(R.string.theme_light), ThemeMode.LIGHT),
                Choice(stringResource(R.string.theme_auto), ThemeMode.AUTO)
            ),
            selected = AppSettings.themeMode,
            onSelect = { AppSettings.setThemeMode(it) },
            onDismiss = { dialog = null }
        )
        SettingsDialog.ACCENT -> ChoiceDialog(
            title = stringResource(R.string.settings_accent),
            options = BrandColor.entries
                .filter { it != BrandColor.SYSTEM || android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S }
                .map { Choice(brandName(it), it, brandColor(it, dark)) },
            selected = AppSettings.brand,
            onSelect = { AppSettings.setBrand(it) },
            onDismiss = { dialog = null }
        )
        SettingsDialog.TRASH -> ChoiceDialog(
            title = stringResource(R.string.settings_trash_title),
            options = listOf(0, 7, 14, 30, 60, 90).map { days ->
                Choice(
                    if (days == 0) stringResource(R.string.trash_keep)
                    else res.getQuantityString(R.plurals.trash_after_days, days, days),
                    days
                )
            },
            selected = AppSettings.trashRetentionDays,
            onSelect = { AppSettings.setTrashRetentionDays(it) },
            onDismiss = { dialog = null }
        )
        SettingsDialog.MEDIA_SOURCES -> MediaSourcesDialog(context = context, onDismiss = { dialog = null })
        null -> Unit
    }
}

@Composable
private fun brandName(brand: BrandColor): String = stringResource(
    when (brand) {
        BrandColor.CYAN -> R.string.brand_cyan
        BrandColor.BLUE -> R.string.brand_blue
        BrandColor.GREEN -> R.string.brand_green
        BrandColor.ORANGE -> R.string.brand_orange
        BrandColor.PINK -> R.string.brand_pink
        BrandColor.PURPLE -> R.string.brand_purple
        BrandColor.RED -> R.string.brand_red
        BrandColor.SYSTEM -> R.string.brand_system
    }
)

@Composable
private fun Swatch(color: Color) {
    Box(Modifier.size(18.dp).clip(CircleShape).background(color))
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    @androidx.annotation.StringRes titleRes: Int,
    value: String,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusCard(corner = dimensionResource(R.dimen.row_corner), onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(stringResource(titleRes), color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(value, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
        }
        if (trailing != null) {
            trailing()
        } else {
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun MediaSourcesDialog(context: android.content.Context, onDismiss: () -> Unit) {
    val volumes = remember { detectVolumes(context, "").filter { it.kind != VolumeKind.INTERNAL } }
    val connections = SmbStore.connections

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = { Text(stringResource(R.string.settings_media_sources_title), color = TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.settings_media_sources_internal_note),
                    color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)
                )
                if (volumes.isEmpty() && connections.isEmpty()) {
                    Text(stringResource(R.string.settings_media_sources_empty), color = TextSecondary, fontSize = 13.sp)
                }
                volumes.forEach { vol ->
                    MediaSourceRow(
                        icon = if (vol.kind == VolumeKind.SD) Icons.Default.SdCard else Icons.Default.Usb,
                        label = vol.label,
                        id = vol.path
                    )
                }
                connections.forEach { conn ->
                    MediaSourceRow(
                        icon = Icons.Default.Storage,
                        label = conn.title,
                        id = MediaRepo.sourceIdFor(conn)
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
private fun MediaSourceRow(icon: ImageVector, label: String, id: String) {
    val checked = id in AppSettings.enabledMediaSources
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                AppSettings.setMediaSourceEnabled(id, !checked)
                ExtraMediaCache.invalidate()
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = CyanAccent, checkmarkColor = OnAccent, uncheckedColor = TextMuted),
            modifier = Modifier.padding(end = 10.dp)
        )
        Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = TextPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = { Text(title, color = TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onSelect(option.value)
                                onDismiss()
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = option.value == selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = CyanAccent, unselectedColor = TextMuted),
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        option.swatch?.let {
                            Swatch(it)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(option.label, color = TextPrimary, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done), color = CyanAccent) }
        }
    )
}
