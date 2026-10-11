package com.appsventory.digiapps.nemo.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appsventory.digiapps.nemo.R
import com.appsventory.digiapps.nemo.smb.NetworkDiagnostics
import com.appsventory.digiapps.nemo.ui.theme.CardDark
import com.appsventory.digiapps.nemo.ui.theme.CyanAccent
import com.appsventory.digiapps.nemo.ui.theme.TextPrimary
import com.appsventory.digiapps.nemo.ui.theme.TextSecondary

/**
 * Live network snapshot + a raw TCP probe to [host]:[port], for diagnosing a connection
 * failure on the actual device. Copy button puts the text on the clipboard to paste elsewhere.
 */
@Composable
fun NetworkDebugDialog(host: String, port: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(host, port) { text = NetworkDiagnostics.report(context, host, port) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = { Text(stringResource(R.string.network_debug_title), color = TextPrimary) },
        text = {
            Box(Modifier.heightIn(max = 420.dp)) {
                val value = text
                if (value == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = CyanAccent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.network_debug_running), color = TextSecondary, fontSize = 13.sp)
                    }
                } else {
                    SelectionContainer {
                        Text(
                            value,
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text != null,
                onClick = { text?.let { clipboard.setText(AnnotatedString(it)) } }
            ) { Text(stringResource(R.string.action_copy), color = CyanAccent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close), color = TextSecondary) }
        }
    )
}
