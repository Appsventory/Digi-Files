package com.appsventory.digiapps.nemo.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.appsventory.digiapps.nemo.MainActivity
import com.appsventory.digiapps.nemo.R
import java.io.File

/** Opens a tapped file: APK -> installer, everything else -> "open with" app chooser. */
object FileOpener {
    private const val APK_MIME = "application/vnd.android.package-archive"

    fun open(context: Context, path: String) {
        val file = File(path)
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
        if (!file.isFile || uri == null) {
            toast(context, R.string.toast_invalid_file)
            return
        }
        if (file.extension.equals("apk", ignoreCase = true)) install(context, uri) else view(context, file, uri)
    }

    /** True when the app may hand an APK to the package installer (always true below Android 8). */
    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the system "Install unknown apps" screen for this app. */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        start(
            context,
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        )
    }

    /**
     * Hand an already-verified APK to Android's package installer (the system still shows its own
     * confirmation). The caller is expected to have checked [canInstallPackages] first and explained
     * the permission; if it was skipped this falls back to the old toast + settings behaviour.
     */
    fun installApk(context: Context, path: String) {
        val file = File(path)
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
        if (!file.isFile || uri == null) {
            toast(context, R.string.toast_invalid_file)
            return
        }
        install(context, uri)
    }

    /** Share files (folders are skipped) with any app. */
    fun share(context: Context, paths: List<String>) {
        val uris = ArrayList<Uri>()
        val mimes = mutableSetOf<String>()
        for (path in paths) {
            val file = File(path)
            if (!file.isFile) continue
            val uri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }.getOrNull() ?: continue
            uris += uri
            mimes += MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        }
        if (uris.isEmpty()) {
            toast(context, R.string.toast_share_files_only)
            return
        }
        val mime = if (mimes.size == 1) mimes.first() else "*/*"
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uris.first()).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
        start(context, Intent.createChooser(send, null))
    }

    private fun install(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            // User must allow "install unknown apps" for this app once.
            toast(context, R.string.toast_allow_install)
            start(
                context,
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            )
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, intent)
    }

    private fun view(context: Context, file: File, uri: Uri) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        view.clipData = ClipData.newRawUri(null, uri)
        val chooser = Intent.createChooser(view, null)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
        start(context, chooser)
    }

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            toast(context, R.string.toast_no_app_to_open)
        }
    }

    private fun toast(context: Context, res: Int) =
        Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
}
