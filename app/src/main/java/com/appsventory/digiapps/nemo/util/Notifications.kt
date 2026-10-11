package com.appsventory.digiapps.nemo.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.appsventory.digiapps.nemo.MainActivity
import com.appsventory.digiapps.nemo.R

/** Small helpers shared by the two foreground services. */
object Notifications {
    const val CHANNEL_MUSIC = "music"
    const val CHANNEL_TASKS = "tasks"

    fun ensureChannel(context: Context, id: String, @StringRes name: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(id, context.getString(name), NotificationManager.IMPORTANCE_LOW))
    }

    @Suppress("DEPRECATION")
    fun builder(context: Context, channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, channel)
        else Notification.Builder(context)

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun serviceIntent(context: Context, service: Class<*>, action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            context, code, Intent(context, service).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** startForeground with the service type (required on Android 14+, accepted from 10). */
    fun Service.startFg(id: Int, notification: Notification, type: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(id, notification, type)
        else startForeground(id, notification)
    }

    /** Id of the "finished / cancelled / failed" notification that replaces the progress one. */
    const val NOTIFICATION_TASK_RESULT = 1003

    /**
     * Result of a copy / move, left in the shade after the progress notification is gone
     * (so it is also seen when the app was closed meanwhile). Silently ignored when notifications are blocked.
     */
    fun postTaskResult(context: Context, message: String, @DrawableRes icon: Int) {
        ensureChannel(context, CHANNEL_TASKS, R.string.notif_channel_tasks)
        val builder = builder(context, CHANNEL_TASKS)
            .setSmallIcon(icon)
            .setColor(context.getColor(R.color.cyan_accent))
            .setContentTitle(message)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setShowWhen(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setTimeoutAfter(20_000)
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_TASK_RESULT, builder.build())
        }
    }

    fun cancelTaskResult(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_TASK_RESULT)
    }

    const val TYPE_MEDIA = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    const val TYPE_DATA_SYNC = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
}
