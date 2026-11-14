package com.belleui.music

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import kotlin.random.Random

/** Process-wide playback state shared by the UI and [PlaybackService]. */
object Player {
    var queue: List<Track> = emptyList()
        private set
    var index = -1
        private set
    var playing = false
        private set
    var shuffle = false
    var repeat = 0 // 0 off, 1 all, 2 one

    @Volatile
    private var mp: MediaPlayer? = null
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile
    private var gen = 0
    private var prepared = false
    private var session: MediaSession? = null
    private lateinit var app: Context
    private var audio: AudioManager? = null
    private val listeners = LinkedHashMap<Any, () -> Unit>()

    /** One listener per owner, so reopening a screen never stacks up callbacks. */
    fun listen(owner: Any, fn: () -> Unit) {
        synchronized(listeners) { listeners[owner] = fn }
    }

    fun unlisten(owner: Any) {
        synchronized(listeners) { listeners.remove(owner) }
    }

    val current: Track? get() = queue.getOrNull(index)
    val position: Long get() = try { if (prepared) mp?.currentPosition?.toLong() ?: 0L else 0L } catch (_: Exception) { 0L }
    val duration: Long get() = current?.durationMs ?: 0L

    private fun changed() {
        updateSession()
        val ls = synchronized(listeners) { listeners.values.toList() }
        for (l in ls) l()
    }

    fun playQueue(ctx: Context, tracks: List<Track>, start: Int) {
        app = ctx.applicationContext
        audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        queue = tracks
        index = start.coerceIn(0, tracks.size - 1)
        ensureSession()
        startService()
        playCurrent()
    }

    private fun startService() {
        val i = Intent(app, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
    }

    /** Opening the file and preparing the player can block, so it happens off the UI thread; the newest request wins. */
    private fun playCurrent() {
        val t = current ?: return
        val g = ++gen
        prepared = false
        changed()
        io.execute {
            if (g != gen) return@execute
            try {
                mp?.release()
                @Suppress("DEPRECATION")
                audio?.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                val m = MediaPlayer()
                m.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                m.setWakeMode(app, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                m.setDataSource(app, t.uri)
                m.setOnPreparedListener {
                    if (g != gen) {
                        it.release()
                        return@setOnPreparedListener
                    }
                    prepared = true
                    it.start()
                    playing = true
                    changed()
                }
                m.setOnCompletionListener { if (g == gen) onCompleted() }
                m.setOnErrorListener { _, _, _ ->
                    playing = false
                    changed()
                    true
                }
                mp = m
                m.prepareAsync()
            } catch (_: Exception) {
                playing = false
                changed()
            }
        }
    }

    private fun onCompleted() {
        if (repeat == 2) {
            playCurrent()
        } else {
            next(auto = true)
        }
    }

    fun toggle() {
        val m = mp ?: return
        if (!prepared) return
        if (m.isPlaying) {
            m.pause()
            playing = false
        } else {
            m.start()
            playing = true
        }
        changed()
    }

    fun next(auto: Boolean = false) {
        if (queue.isEmpty()) return
        index = when {
            shuffle && queue.size > 1 -> {
                var n = Random.nextInt(queue.size)
                while (n == index) n = Random.nextInt(queue.size)
                n
            }
            index < queue.size - 1 -> index + 1
            repeat == 1 || !auto -> 0
            else -> {
                // end of queue
                mp?.stop()
                playing = false
                changed()
                return
            }
        }
        playCurrent()
    }

    fun prev() {
        if (queue.isEmpty()) return
        if (position > 3000) {
            seek(0)
            return
        }
        index = if (index > 0) index - 1 else queue.size - 1
        playCurrent()
    }

    fun seek(ms: Long) {
        if (prepared) mp?.seekTo(ms.coerceIn(0, duration).toInt())
        changed()
    }

    fun seekBy(deltaMs: Long) = seek(position + deltaMs)

    private fun ensureSession() {
        if (session != null) return
        val s = MediaSession(app, "BelleMusic")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = toggleTo(true)
            override fun onPause() = toggleTo(false)
            override fun onSkipToNext() = next()
            override fun onSkipToPrevious() = prev()
            override fun onSeekTo(pos: Long) = seek(pos)
        })
        s.isActive = true
        session = s
    }

    private fun toggleTo(play: Boolean) {
        if (play != playing) toggle()
    }

    private fun updateSession() {
        val s = session ?: return
        val st = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO)
                .setState(st, position, 1f)
                .build(),
        )
        current?.let {
            s.setMetadata(
                android.media.MediaMetadata.Builder()
                    .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, it.title)
                    .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, it.artist)
                    .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, it.album)
                    .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, it.durationMs)
                    .build(),
            )
        }
    }

    val sessionToken: MediaSession.Token? get() = session?.sessionToken
}
