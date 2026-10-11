package com.appsventory.digiapps.nemo.ui

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.appsventory.digiapps.nemo.data.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val IMAGE_EXTS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "ico", "tiff", "tif"
)
private val VIDEO_EXTS = setOf(
    "mp4", "mkv", "avi", "webm", "3gp", "mov", "wmv", "flv", "m4v", "ts", "m2ts"
)
private val AUDIO_EXTS = setOf(
    "mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus", "amr"
)
private val ARCHIVE_EXTS = setOf("zip", "rar", "7z", "tar", "gz", "bz2")
private val DOC_EXTS = setOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt"
)

enum class FileKind {
    FOLDER, IMAGE, VIDEO, AUDIO, APK, ARCHIVE, DOCUMENT, OTHER
}

fun fileKind(name: String, isDirectory: Boolean): FileKind {
    if (isDirectory) return FileKind.FOLDER
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext in IMAGE_EXTS -> FileKind.IMAGE
        ext in VIDEO_EXTS -> FileKind.VIDEO
        ext in AUDIO_EXTS -> FileKind.AUDIO
        ext == "apk" -> FileKind.APK
        ext in ARCHIVE_EXTS -> FileKind.ARCHIVE
        ext in DOC_EXTS -> FileKind.DOCUMENT
        else -> FileKind.OTHER
    }
}

@Composable
fun FileThumbnail(
    item: FileItem,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    val kind = remember(item.path, item.isDirectory) {
        fileKind(item.name, item.isDirectory)
    }
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when (kind) {
            FileKind.FOLDER -> {
                TypeIcon(Icons.Default.Folder, MaterialTheme.colorScheme.primary)
            }
            FileKind.IMAGE -> {
                CoilThumb(path = item.path, isVideo = false)
            }
            FileKind.VIDEO -> {
                CoilThumb(path = item.path, isVideo = true)
            }
            FileKind.APK -> {
                ApkThumb(path = item.path)
            }
            FileKind.AUDIO -> {
                TypeIcon(Icons.Default.AudioFile, MaterialTheme.colorScheme.tertiary)
            }
            FileKind.ARCHIVE -> {
                TypeIcon(Icons.Default.FolderZip, MaterialTheme.colorScheme.secondary)
            }
            FileKind.DOCUMENT -> {
                TypeIcon(Icons.Default.Description, MaterialTheme.colorScheme.secondary)
            }
            FileKind.OTHER -> {
                TypeIcon(
                    Icons.AutoMirrored.Filled.InsertDriveFile,
                    MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TypeIcon(icon: ImageVector, tint: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(28.dp)
    )
}

@Composable
private fun CoilThumb(path: String, isVideo: Boolean) {
    val context = LocalContext.current
    val fallbackIcon = if (isVideo) Icons.Default.Movie else Icons.Default.Image
    val fallbackTint = MaterialTheme.colorScheme.onSurfaceVariant

    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }

    val request = remember(path, isVideo) {
        val builder = ImageRequest.Builder(context)
            .data(File(path))
            .crossfade(true)
            .size(128)
        if (isVideo) {
            builder.videoFrameMillis(1000)
        }
        builder.build()
    }

    SubcomposeAsyncImage(
        model = request,
        imageLoader = imageLoader,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
        loading = {
            TypeIcon(fallbackIcon, fallbackTint)
        },
        error = {
            TypeIcon(fallbackIcon, fallbackTint)
        }
    )
}

@Composable
private fun ApkThumb(path: String) {
    val context = LocalContext.current
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }

    LaunchedEffect(path) {
        failed = false
        bitmap = withContext(Dispatchers.IO) {
            try {
                extractApkIcon(context.packageManager, path)
            } catch (_: Exception) {
                null
            }
        }
        if (bitmap == null) {
            failed = true
        }
    }

    when {
        bitmap != null -> {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        else -> {
            TypeIcon(Icons.Default.Android, MaterialTheme.colorScheme.primary)
        }
    }
}

private fun extractApkIcon(pm: PackageManager, path: String): Bitmap? {
    val info = pm.getPackageArchiveInfo(path, 0) ?: return null
    val appInfo = info.applicationInfo ?: return null
    appInfo.sourceDir = path
    appInfo.publicSourceDir = path
    val drawable = appInfo.loadIcon(pm) ?: return null
    return when (drawable) {
        is BitmapDrawable -> drawable.bitmap
        else -> {
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp
        }
    }
}
