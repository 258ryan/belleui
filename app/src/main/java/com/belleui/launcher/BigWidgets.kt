package com.belleui.launcher

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import java.util.concurrent.Executors

/** What the Music widget shows and controls: whichever app is playing, found through its media session. */
class MediaWidget(private val ctx: Context, private val onChange: () -> Unit) {
    var title: String? = null
        private set
    var artist: String? = null
        private set
    var art: Bitmap? = null
        private set
    var playing = false
        private set
    var available = false
        private set
    private var controller: MediaController? = null
    private val main = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private val component = ComponentName(ctx, HubListenerService::class.java)

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = read()
        override fun onPlaybackStateChanged(state: PlaybackState?) = read()
        override fun onSessionDestroyed() = pick()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick() }

    /** Needs notification access, the same permission the Hub uses. */
    fun start() {
        try {
            val m = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            manager = m
            m.addOnActiveSessionsChangedListener(sessionsListener, component)
            pick()
        } catch (_: SecurityException) {
            available = false
        } catch (_: Exception) {
        }
    }

    fun stop() {
        try {
            manager?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        controller?.unregisterCallback(callback)
        controller = null
    }

    private fun pick() {
        val list = try { manager?.getActiveSessions(component) } catch (_: Exception) { null } ?: emptyList()
        // a session that is playing wins; otherwise the first one
        val c = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (c?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = c
            c?.registerCallback(callback, main)
        }
        read()
    }

    private fun read() {
        val c = controller
        available = c != null
        val md = c?.metadata
        title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        main.post(onChange)
    }

    fun prev() = controller?.transportControls?.skipToPrevious()
    fun next() = controller?.transportControls?.skipToNext()
    fun toggle() {
        val t = controller?.transportControls ?: return
        if (playing) t.pause() else t.play()
    }
}

/** The next week of calendar events, for the Today widget. Reading needs the calendar permission. */
class CalendarWidget(private val ctx: Context, private val onChange: () -> Unit) {
    class Event(val begin: Long, val allDay: Boolean, val title: String)

    @Volatile
    var events: List<Event> = emptyList()
        private set
    var loadedAt = 0L
        private set
    private val io = Executors.newSingleThreadExecutor()

    fun hasPermission() = ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun refresh(force: Boolean = false) {
        if (!hasPermission()) return
        val now = System.currentTimeMillis()
        if (!force && now - loadedAt < 5 * 60_000) return
        loadedAt = now
        io.execute {
            val out = ArrayList<Event>()
            try {
                val start = System.currentTimeMillis()
                val end = start + 7L * 24 * 3600 * 1000
                val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
                android.content.ContentUris.appendId(b, start)
                android.content.ContentUris.appendId(b, end)
                ctx.contentResolver.query(
                    b.build(), arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.TITLE),
                    null, null, "${CalendarContract.Instances.BEGIN} ASC",
                )?.use { c ->
                    while (c.moveToNext() && out.size < 20) out.add(Event(c.getLong(0), c.getInt(1) != 0, c.getString(2) ?: "(no title)"))
                }
            } catch (_: Exception) {
            }
            events = out
            Handler(Looper.getMainLooper()).post(onChange)
        }
    }
}
