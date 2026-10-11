package com.appsventory.digiapps.nemo.ui

import android.content.Context
import kotlinx.coroutines.delay
import androidx.core.content.ContextCompat
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.appsventory.digiapps.nemo.R
import com.appsventory.digiapps.nemo.ui.theme.CyanAccent
import com.appsventory.digiapps.nemo.ui.theme.TextMuted
import com.appsventory.digiapps.nemo.ui.theme.TextPrimary
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class InstalledApp(val label: String, val packageName: String, val isSystem: Boolean)

/** Standalone screen (phone). */
@Composable
fun AppsScreen(title: String, onBack: () -> Unit) {
    ScreenScaffold(title = title, onBack = onBack) { AppsContent() }
}

/** Installed apps as a page inside the shell (large screens / TV): the sidebar stays visible. */
@Composable
fun AppsPage() {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.tool_user_apps),
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        )
        AppsContent()
    }
}

@Composable
private fun AppsContent() {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    // installs / uninstalls / updates while this page is open
    var packageTick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                packageTick++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(packageTick) {
        if (packageTick > 0) delay(500)
        apps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
    }

    val list = apps
    if (list == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = CyanAccent)
        }
    } else {
        val (system, user) = list.partition { it.isSystem }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (user.isNotEmpty()) {
                item { SectionHeader(R.string.apps_user, top = 8.dp) }
                items(user, key = { it.packageName }) { AppRow(it) }
            }
            if (system.isNotEmpty()) {
                item { SectionHeader(R.string.apps_system, top = 16.dp) }
                items(system, key = { it.packageName }) { AppRow(it) }
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .focusCard(corner = dimensionResource(R.dimen.row_corner), onClick = { launchApp(context, app.packageName) })
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(36.dp)) {
            icon?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun loadInstalledApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getInstalledApplications(0)
    }
    return infos
        .map {
            InstalledApp(
                label = pm.getApplicationLabel(it).toString(),
                packageName = it.packageName,
                isSystem = it.flags and ApplicationInfo.FLAG_SYSTEM != 0
            )
        }
        .sortedBy { it.label.lowercase() }
}

private fun launchApp(context: Context, packageName: String) {
    val pm = context.packageManager
    val intent = pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName)
    if (intent == null) {
        Toast.makeText(context, R.string.toast_cannot_open_app, Toast.LENGTH_SHORT).show()
    } else {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
