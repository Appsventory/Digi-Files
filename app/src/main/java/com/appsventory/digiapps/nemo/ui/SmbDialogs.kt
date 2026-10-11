package com.appsventory.digiapps.nemo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import com.appsventory.digiapps.nemo.util.SmbDiscovery
import com.appsventory.digiapps.nemo.util.SmbHost
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appsventory.digiapps.nemo.R
import com.appsventory.digiapps.nemo.smb.SmbClient
import com.appsventory.digiapps.nemo.smb.SmbConnection
import com.appsventory.digiapps.nemo.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Add / edit / connect an SMB server. One server = one connection (smb://host/);
 * its shares show up as folders once inside. The connection is tested before it is saved.
 *
 * @param initial existing connection to edit, or null for a new one
 * @param prefillHost / [prefillName] used when connecting to a server found on the network
 */
@Composable
fun SmbConnectionDialog(
    initial: SmbConnection?,
    prefillHost: String,
    prefillName: String,
    onConnected: (SmbConnection) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name ?: prefillName) }
    var host by remember { mutableStateOf(initial?.host ?: prefillHost) }
    var anonymous by remember { mutableStateOf(initial?.anonymous ?: true) }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var domain by remember { mutableStateOf(initial?.domain.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorDetail by remember { mutableStateOf<String?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }

    // "Scan" is on demand only (tapping it), never automatic - an always-on background scan
    // is what was triggering Android's own "connect to a device on your local network?"
    // system prompt every time the app opened.
    val appContext = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    var scanResults by remember { mutableStateOf<List<SmbHost>>(emptyList()) }
    val discovery = remember { SmbDiscovery(appContext) }
    DisposableEffect(Unit) { onDispose { discovery.stop() } }
    val nearbyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            scanResults = emptyList()
            scanning = true
            discovery.start { scanResults = it }
        }
    }
    fun scan() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.NEARBY_WIFI_DEVICES
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            nearbyPermission.launch(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            return
        }
        scanResults = emptyList()
        scanning = true
        discovery.start { scanResults = it }
    }

    val hostRequired = stringResource(R.string.smb_host_required)
    val authError = stringResource(R.string.smb_error_auth)
    val connectError = stringResource(R.string.smb_error_connect)

    fun connect() {
        // accept "192.168.1.5", "smb://192.168.1.5/" or "nas:445"
        val target = host.trim().removePrefix("smb://").substringBefore('/')
        val hostName = target.substringBefore(':')
        val port = target.substringAfter(':', "").toIntOrNull() ?: 445
        if (hostName.isEmpty()) {
            error = hostRequired
            return
        }
        val connection = SmbConnection(
            host = hostName, name = name.trim(), port = port, anonymous = anonymous,
            username = username.trim(), password = password, domain = domain.trim(),
            shares = initial?.shares.orEmpty()
        )
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { SmbClient.connect(connection) } }
            busy = false
            result
                .onSuccess { onConnected(connection) }
                .onFailure {
                    error = if (SmbClient.isAuthError(it)) authError else connectError
                    errorDetail = generateSequence(it as Throwable) { e -> e.cause }
                        .lastOrNull { e -> !e.message.isNullOrBlank() }?.message
                        ?: it.message
                }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = CardDark,
        title = {
            Text(
                stringResource(
                    when {
                        initial != null -> R.string.smb_edit_title
                        prefillHost.isNotEmpty() -> R.string.smb_connect_title
                        else -> R.string.smb_add_title
                    }
                ),
                color = TextPrimary
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host, onValueChange = { host = it }, singleLine = true,
                    enabled = initial == null && !busy,
                    label = { Text(stringResource(R.string.smb_host)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingIcon = if (initial == null) {
                        {
                            IconButton(onClick = { scan() }, enabled = !busy) {
                                if (scanning) CircularProgressIndicator(Modifier.size(18.dp), color = CyanAccent, strokeWidth = 2.dp)
                                else Icon(Icons.Default.Wifi, contentDescription = stringResource(R.string.smb_scan), tint = TextMuted)
                            }
                        }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (initial == null && (scanning || scanResults.isNotEmpty())) {
                    Text(
                        stringResource(if (scanning && scanResults.isEmpty()) R.string.smb_scanning else R.string.smb_scan_results),
                        color = TextMuted, fontSize = 11.sp
                    )
                    LazyColumn(Modifier.heightIn(max = 120.dp)) {
                        items(scanResults, key = { it.address }) { found ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        host = found.address
                                        if (name.isBlank()) name = found.name
                                        discovery.stop()
                                        scanning = false
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp)
                            ) {
                                Text(found.name, color = TextPrimary, fontSize = 13.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(found.address, color = TextMuted, fontSize = 12.sp)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true, enabled = !busy,
                    label = { Text(stringResource(R.string.smb_name)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !busy) { anonymous = !anonymous },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = anonymous, onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = CyanAccent, checkmarkColor = OnAccent, uncheckedColor = TextMuted),
                        modifier = Modifier.padding(end = 10.dp, top = 6.dp, bottom = 6.dp)
                    )
                    Text(stringResource(R.string.smb_anonymous), color = TextPrimary, fontSize = 14.sp)
                }
                if (!anonymous) {
                    OutlinedTextField(
                        value = username, onValueChange = { username = it }, singleLine = true, enabled = !busy,
                        label = { Text(stringResource(R.string.smb_username)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, singleLine = true, enabled = !busy,
                        label = { Text(stringResource(R.string.smb_password)) },
                        visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = stringResource(
                                        if (passwordVisible) R.string.cd_hide_password else R.string.cd_show_password
                                    ),
                                    tint = TextMuted
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = domain, onValueChange = { domain = it }, singleLine = true, enabled = !busy,
                        label = { Text(stringResource(R.string.smb_domain)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (busy) Text(stringResource(R.string.smb_connecting), color = TextSecondary, fontSize = 13.sp)
                error?.let { msg ->
                    Text(msg, color = ErrorRed, fontSize = 13.sp)
                    errorDetail?.let {
                        Text(it, color = TextMuted, fontSize = 11.sp)
                    }
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showDebug = true }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.BugReport, null, tint = CyanAccent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.network_debug_show), color = CyanAccent, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { connect() }, enabled = !busy) {
                Text(stringResource(R.string.smb_connect), color = CyanAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.action_cancel), color = TextSecondary)
            }
        }
    )

    if (showDebug) {
        val probeHost = host.trim().removePrefix("smb://").substringBefore('/').substringBefore(':')
        NetworkDebugDialog(host = probeHost.ifBlank { "192.168.1.1" }, port = 445, onDismiss = { showDebug = false })
    }
}

@Composable
fun ForgetSmbDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = { Text(stringResource(R.string.smb_forget_title), color = TextPrimary) },
        text = { Text(stringResource(R.string.smb_forget_body), color = TextSecondary, fontSize = 14.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.smb_forget_action), color = ErrorRed) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = TextSecondary) }
        }
    )
}

/** "Menu > Add shared folder" — bookmark a share name by hand under the current server. */
@Composable
fun AddShareDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = { Text(stringResource(R.string.smb_add_share_title), color = TextPrimary) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.smb_share)) },
                placeholder = { Text(stringResource(R.string.smb_share_hint)) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onAdd(name) }) {
                Text(stringResource(R.string.action_create), color = CyanAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = TextSecondary) }
        }
    )
}
