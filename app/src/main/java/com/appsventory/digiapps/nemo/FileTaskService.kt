package com.appsventory.digiapps.nemo

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.appsventory.digiapps.nemo.util.FileTasks
import com.appsventory.digiapps.nemo.util.Notifications
import com.appsventory.digiapps.nemo.util.Notifications.startFg
import com.appsventory.digiapps.nemo.util.TaskProgress
import com.appsventory.digiapps.nemo.util.formatBytes

/**
 * Keeps a running copy / move alive (and shows its progress in a notification) even when
 * the app is closed. The work itself runs in [FileTasks]; this service only mirrors it.
 */
class FileTaskService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tasks get() = FileTasks.get(this)

    private val ticker = object : Runnable {
        override fun run() {
            if (!tasks.isBusy) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            getSystemService(android.app.NotificationManager::class.java)
                .notify(NOTIFICATION_ID, build(tasks.progress))
            handler.postDelayed(this, 700)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this, Notifications.CHANNEL_TASKS, R.string.notif_channel_tasks)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) tasks.cancel()
        startFg(NOTIFICATION_ID, build(tasks.progress), Notifications.TYPE_DATA_SYNC)
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 700)
        return START_NOT_STICKY
    }

    private fun build(progress: TaskProgress?): Notification {
        val builder = Notifications.builder(this, Notifications.CHANNEL_TASKS)
            .setSmallIcon(
                when {
                    progress?.archive == true -> R.drawable.ic_notif_archive
                    progress?.move == true -> R.drawable.ic_notif_move
                    else -> R.drawable.ic_notif_copy
                }
            )
            .setColor(getColor(R.color.cyan_accent))
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentTitle(
                (progress?.label ?: getString(R.string.task_copying)) +
                    if (progress != null && progress.queued > 0) "  ·  " + getString(R.string.task_queued, progress.queued) else ""
            )
            .setContentIntent(Notifications.openApp(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    getString(R.string.action_cancel),
                    Notifications.serviceIntent(this, FileTaskService::class.java, ACTION_CANCEL, 10)
                ).build()
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ would otherwise delay showing a foreground-service notification by ~10 s
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        if (progress == null || progress.total <= 0) {
            // sizes are still being counted
            builder.setContentText(getString(R.string.task_preparing)).setProgress(0, 0, true)
            return builder.build()
        }

        val percent = ((progress.done * 100) / progress.total).toInt().coerceIn(0, 100)
        val bytes = getString(R.string.task_progress, formatBytes(progress.done), formatBytes(progress.total))
        val lines = mutableListOf<String>()
        if (progress.itemCount > 1 && progress.itemIndex > 0) {
            lines += getString(R.string.task_item_of, progress.itemIndex, progress.itemCount)
        }
        if (progress.current.isNotEmpty()) lines += progress.current
        lines += bytes
        val eta = etaText(progress)
        builder
            .setContentText(if (progress.current.isNotEmpty()) "$bytes  ·  ${progress.current}" else bytes)
            .setSubText(if (eta != null) "$percent%  ·  $eta" else "$percent%")
            .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
            .setProgress(100, percent, false)
        return builder.build()
    }

    /** "About" time left, from the average speed so far. Null until there is enough data. */
    private fun etaText(progress: TaskProgress): String? {
        val elapsed = System.currentTimeMillis() - progress.startedAt
        if (progress.startedAt == 0L || elapsed < 3_000 || progress.done <= 0) return null
        val remainingSec = ((progress.total - progress.done) * elapsed / progress.done / 1000).coerceAtLeast(0)
        return when {
            remainingSec < 60 -> getString(R.string.task_eta_seconds, remainingSec.toInt().coerceAtLeast(1))
            remainingSec < 3600 -> getString(R.string.task_eta_minutes, (remainingSec / 60).toInt().coerceAtLeast(1))
            else -> getString(R.string.task_eta_hours, (remainingSec / 3600).toInt(), ((remainingSec % 3600) / 60).toInt())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_CANCEL = "digifiles.tasks.CANCEL"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FileTaskService::class.java))
        }
    }
}
