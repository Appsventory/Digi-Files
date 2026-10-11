package com.appsventory.digiapps.nemo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appsventory.digiapps.nemo.R
import com.appsventory.digiapps.nemo.ui.theme.CardDark
import com.appsventory.digiapps.nemo.ui.theme.CyanAccent
import com.appsventory.digiapps.nemo.ui.theme.DividerDark
import com.appsventory.digiapps.nemo.ui.theme.ErrorRed
import com.appsventory.digiapps.nemo.ui.theme.TextMuted
import com.appsventory.digiapps.nemo.ui.theme.TextPrimary
import com.appsventory.digiapps.nemo.ui.theme.TextSecondary
import com.appsventory.digiapps.nemo.util.ArchiveFormat
import com.appsventory.digiapps.nemo.util.ArchiveLevel
import com.appsventory.digiapps.nemo.util.Archives
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Password input with a show / hide eye. */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        isError = isError,
        label = { Text(label) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(if (visible) R.string.password_hide else R.string.password_show),
                    tint = TextSecondary
                )
            }
        },
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * Asks for the password of [archiveName] and only lets go once [verify] says it is the right one
 * (so a typo is caught here, not after the extraction started).
 */
@Composable
fun ArchivePasswordDialog(
    archiveName: String,
    verify: suspend (CharArray) -> Boolean,
    onUnlocked: (CharArray) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }

    fun submit() {
        if (text.isEmpty() || checking) return
        val password = text.toCharArray()
        scope.launch {
            checking = true
            val ok = withContext(Dispatchers.IO) { verify(password) }
            checking = false
            if (ok) {
                onUnlocked(password)
            } else {
                password.fill('\u0000')
                wrong = true
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        containerColor = CardDark,
        title = { Text(stringResource(R.string.archive_password_title), color = TextPrimary) },
        text = {
            Column {
                Text(stringResource(R.string.archive_password_message, archiveName), color = TextSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                PasswordField(
                    value = text,
                    onValueChange = {
                        text = it
                        wrong = false
                    },
                    label = stringResource(R.string.archive_password_hint),
                    isError = wrong
                )
                if (wrong) Text(stringResource(R.string.archive_password_wrong), color = ErrorRed, fontSize = 12.sp)
                if (checking) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = CyanAccent, trackColor = DividerDark)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotEmpty() && !checking, onClick = { submit() }) {
                Text(stringResource(R.string.archive_password_unlock), color = CyanAccent)
            }
        },
        dismissButton = {
            TextButton(enabled = !checking, onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = TextSecondary)
            }
        }
    )
}

/**
 * "Compress": name, format, how hard to compress, and (ZIP / 7z) an optional password asked twice,
 * because a typo would make the archive impossible to open.
 */
@Composable
fun CompressDialog(
    defaultName: String,
    onConfirm: (name: String, format: ArchiveFormat, level: ArchiveLevel, password: CharArray?) -> Unit,
    onDismiss: () -> Unit
) {
    val formats = remember { ArchiveFormat.entries.filter { it.canCreate } }
    val levels = remember { ArchiveLevel.entries.toList() }
    val levelLabels = listOf(
        stringResource(R.string.compress_level_store),
        stringResource(R.string.compress_level_fast),
        stringResource(R.string.compress_level_normal),
        stringResource(R.string.compress_level_maximum)
    )
    var name by remember { mutableStateOf(defaultName) }
    var formatIndex by remember { mutableIntStateOf(0) }
    var levelIndex by remember { mutableIntStateOf(levels.indexOf(ArchiveLevel.NORMAL)) }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val format = formats[formatIndex]
    val encryptable = Archives.canEncrypt(format)
    val mismatch = encryptable && password != repeat

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = { Text(stringResource(R.string.compress_title), color = TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.compress_name_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                ChipTabs(labels = formats.map { it.label }, selected = formatIndex, onSelect = { formatIndex = it }, wrap = true)
                if (Archives.hasLevels(format)) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.compress_level_title), color = TextMuted, fontSize = 12.sp)
                    ChipTabs(labels = levelLabels, selected = levelIndex, onSelect = { levelIndex = it }, wrap = true)
                }
                if (encryptable) {
                    Spacer(Modifier.height(8.dp))
                    PasswordField(password, { password = it }, stringResource(R.string.compress_password_hint))
                    if (password.isNotEmpty()) {
                        PasswordField(repeat, { repeat = it }, stringResource(R.string.compress_password_repeat), isError = mismatch)
                        if (mismatch) {
                            Text(stringResource(R.string.compress_password_mismatch), color = ErrorRed, fontSize = 12.sp)
                        } else {
                            Text(stringResource(R.string.compress_password_note), color = TextMuted, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.compress_rar_note), color = TextMuted, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !(mismatch && password.isNotEmpty()),
                onClick = {
                    onConfirm(
                        name,
                        format,
                        if (Archives.hasLevels(format)) levels[levelIndex] else ArchiveLevel.NORMAL,
                        if (encryptable && password.isNotEmpty()) password.toCharArray() else null
                    )
                }
            ) { Text(stringResource(R.string.compress_action), color = CyanAccent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = TextSecondary) }
        }
    )
}
