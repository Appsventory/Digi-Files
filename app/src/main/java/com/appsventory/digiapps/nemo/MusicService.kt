package com.appsventory.digiapps.nemo

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.appsventory.digiapps.nemo.util.MusicPlayer
import com.appsventory.digiapps.nemo.util.Notifications
import com.appsventory.digiapps.nemo.util.Notifications.startFg

/**
 * Foreground service that keeps music playing after the app is closed, and provides the
 * media notification + lockscreen controls (MediaSession). The player itself lives in
 * [MusicPlayer]; this service only mirrors its state and forwards button presses.
 */
class MusicService : Service() {
    private lateinit var session: MediaSession
    private val player get() = MusicPlayer.get(this)

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            player.pause() // headphones unplugged
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this, Notifications.CHANNEL_MUSIC, R.string.notif_channel_music)
        session = MediaSession(this, "DigiFilesMusic").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = player.resume()
                override fun onPause() = player.pause()
                override fun onSkipToNext() = player.next()
                override fun onSkipToPrevious() = player.previous()
                override fun onSeekTo(pos: Long) = player.seekTo(pos.toInt())
                override fun onStop() = player.stopAndClear()
            })
            isActive = true
        }
        player.onStateChanged = { refresh(first = false) }
        ContextCompat.registerReceiver(
            this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> player.toggle()
            ACTION_NEXT -> player.next()
            ACTION_PREV -> player.previous()
            ACTION_STOP -> player.stopAndClear()
        }
        refresh(first = true)
        return START_NOT_STICKY
    }

    private fun refresh(first: Boolean) {
        val current = player.current
        val notification = buildNotification(current)
        // startForeground must follow every startForegroundService call
        if (first) startFg(NOTIFICATION_ID, notification, Notifications.TYPE_MEDIA)
        if (current == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (!first) getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, current.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, current.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, current.group)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, player.durationMs.toLong())
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
                )
                .setState(
                    if (player.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    player.position().toLong(),
                    1f
                )
                .build()
        )
    }

    private fun buildNotification(current: com.appsventory.digiapps.nemo.util.MediaEntry?): Notification {
        val builder = Notifications.builder(this, Notifications.CHANNEL_MUSIC)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(Notifications.openApp(this))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
        if (current == null) return builder.setContentTitle(getString(R.string.app_name)).build()

        val playPause = if (player.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        return builder
            .setContentTitle(current.title)
            .setContentText(current.artist)
            .setOngoing(player.playing)
            .addAction(action(android.R.drawable.ic_media_previous, R.string.cd_previous, ACTION_PREV, 1))
            .addAction(action(playPause, if (player.playing) R.string.cd_pause else R.string.cd_play, ACTION_TOGGLE, 2))
            .addAction(action(android.R.drawable.ic_media_next, R.string.cd_next, ACTION_NEXT, 3))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, R.string.notif_stop, ACTION_STOP, 4))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun action(icon: Int, label: Int, action: String, code: Int): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, icon),
            getString(label),
            Notifications.serviceIntent(this, MusicService::class.java, action, code)
        ).build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        player.onStateChanged = null
        runCatching { unregisterReceiver(noisy) }
        session.release()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_TOGGLE = "digifiles.music.TOGGLE"
        private const val ACTION_NEXT = "digifiles.music.NEXT"
        private const val ACTION_PREV = "digifiles.music.PREV"
        private const val ACTION_STOP = "digifiles.music.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MusicService::class.java))
        }
    }
}
