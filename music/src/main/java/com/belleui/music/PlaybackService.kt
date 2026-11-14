package com.belleui.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps playback alive in the background with a media notification. */
class PlaybackService : Service() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pending = false

    // seeking or skipping fires many changes; the notification is refreshed at most every 400 ms
    private val listener: () -> Unit = {
        if (!pending) {
            pending = true
            handler.postDelayed({
                pending = false
                update()
            }, 400)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW))
        }
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(ID, n)
        }
        Player.listen(this, listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        update()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Player.unlisten(this)
        super.onDestroy()
    }

    private fun update() {
        getSystemService(NotificationManager::class.java).notify(ID, build())
        if (!Player.playing) stopForeground(false)
    }

    private fun build(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MusicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val t = Player.current
        b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(t?.title ?: "Music")
            .setContentText(t?.artist ?: "")
            .setContentIntent(open)
            .setOngoing(Player.playing)
        Player.sessionToken?.let { b.setStyle(Notification.MediaStyle().setMediaSession(it)) }
        return b.build()
    }

    private companion object {
        const val CHANNEL = "playback"
        const val ID = 7
    }
}
