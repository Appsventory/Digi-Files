package com.appsventory.digiapps.nemo.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.appsventory.digiapps.nemo.MusicService

/**
 * App-wide music player (singleton, so the UI, the notification and the lockscreen
 * controls all drive the same instance). Playback is kept alive by [MusicService].
 */
class MusicPlayer private constructor(private val context: Context) {
    companion object {
        @Volatile
        private var instance: MusicPlayer? = null

        fun get(context: Context): MusicPlayer = instance ?: synchronized(this) {
            instance ?: MusicPlayer(context.applicationContext).also { instance = it }
        }
    }

    var queue by mutableStateOf<List<MediaEntry>>(emptyList())
        private set
    var index by mutableIntStateOf(-1)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set

    /** Set by the service so it can refresh the notification / media session. */
    var onStateChanged: (() -> Unit)? = null

    val current: MediaEntry? get() = queue.getOrNull(index)

    private var player: MediaPlayer? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var resumeAfterFocusLoss = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeAfterFocusLoss = playing
                pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeAfterFocusLoss) {
                resumeAfterFocusLoss = false
                resume()
            }
        }
    }

    fun play(list: List<MediaEntry>, startIndex: Int) {
        queue = list
        start(startIndex)
        MusicService.start(context)
    }

    private fun start(i: Int) {
        val entry = queue.getOrNull(i) ?: return
        release()
        @Suppress("DEPRECATION")
        audioManager?.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        index = i
        positionMs = 0
        durationMs = entry.durationMs.toInt()
        player = MediaPlayer().apply {
            setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setOnPreparedListener {
                durationMs = it.duration
                it.start()
                playing = true
                onStateChanged?.invoke()
            }
            setOnCompletionListener { next() }
            setOnErrorListener { _, _, _ ->
                playing = false
                next()
                true
            }
            runCatching {
                setDataSource(context, entry.uri)
                prepareAsync()
            }.onFailure { playing = false }
        }
        onStateChanged?.invoke()
    }

    fun toggle() {
        if (playing) pause() else resume()
    }

    fun pause() {
        val p = player ?: return
        if (playing) {
            p.pause()
            positionMs = p.currentPosition
            playing = false
            onStateChanged?.invoke()
        }
    }

    fun resume() {
        val p = player ?: return
        if (!playing) {
            p.start()
            playing = true
            onStateChanged?.invoke()
        }
    }

    fun next() {
        if (queue.isEmpty()) return
        if (index + 1 < queue.size) start(index + 1) else stopAndClear()
    }

    fun previous() {
        // restart the song if we're past the first seconds, else go to the previous one
        if (position() > 3000 || index <= 0) seekTo(0) else start(index - 1)
    }

    fun seekTo(ms: Int) {
        player?.seekTo(ms)
        positionMs = ms
        onStateChanged?.invoke()
    }

    /** Live position (the state value only updates while the UI polls). */
    fun position(): Int = runCatching { player?.currentPosition }.getOrNull() ?: positionMs

    /** Called by the UI while playing to update the progress bar. */
    fun refresh() {
        if (playing) positionMs = position()
    }

    fun stopAndClear() {
        release()
        queue = emptyList()
        index = -1
        onStateChanged?.invoke() // service sees "nothing playing" and shuts down
    }

    private fun release() {
        player?.runCatching { release() }
        player = null
        playing = false
        @Suppress("DEPRECATION")
        audioManager?.abandonAudioFocus(focusListener)
    }
}
