package com.belleui.clock

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import java.util.Calendar

/** days is a bitmask, bit 0 = Monday ... bit 6 = Sunday; 0 means "once". */
class Alarm(var id: Int, var hour: Int, var minute: Int, var days: Int, var enabled: Boolean, var label: String)

class AlarmStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("alarms", Context.MODE_PRIVATE)

    fun all(): MutableList<Alarm> =
        (prefs.getString("list", "") ?: "").split("\n").filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split("|")
            try {
                Alarm(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4] == "1", p.getOrElse(5) { "" })
            } catch (_: Exception) {
                null
            }
        }.sortedWith(compareBy({ it.hour }, { it.minute })).toMutableList()

    fun save(list: List<Alarm>) {
        prefs.edit().putString("list", list.joinToString("\n") { "${it.id}|${it.hour}|${it.minute}|${it.days}|${if (it.enabled) 1 else 0}|${it.label.replace("|", " ")}" }).apply()
    }

    fun nextId(): Int = (all().maxOfOrNull { it.id } ?: 0) + 1
}

object AlarmScheduler {
    const val SNOOZE_CODE = 8000
    const val TIMER_CODE = 9000

    fun nextTrigger(a: Alarm, now: Long = System.currentTimeMillis()): Long {
        for (d in 0..7) {
            val cal = Calendar.getInstance()
            cal.timeInMillis = now
            cal.add(Calendar.DAY_OF_YEAR, d)
            cal.set(Calendar.HOUR_OF_DAY, a.hour)
            cal.set(Calendar.MINUTE, a.minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Mon = 0 ... Sun = 6
            if (cal.timeInMillis > now && (a.days == 0 || (a.days shr dow) and 1 == 1)) return cal.timeInMillis
        }
        return now + 24 * 3600 * 1000L
    }

    private fun pending(ctx: Context, code: Int, kind: String, id: Int, label: String): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .putExtra("kind", kind).putExtra("id", id).putExtra("label", label)
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun scheduleAt(ctx: Context, code: Int, at: Long, kind: String, id: Int, label: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(ctx, 0, Intent(ctx, ClockActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pending(ctx, code, kind, id, label))
    }

    fun cancelAt(ctx: Context, code: Int) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, code, "alarm", 0, ""))
    }

    fun schedule(ctx: Context, a: Alarm): Long {
        val at = nextTrigger(a)
        scheduleAt(ctx, a.id, at, "alarm", a.id, a.label)
        return at
    }

    fun cancel(ctx: Context, a: Alarm) = cancelAt(ctx, a.id)

    fun rescheduleAll(ctx: Context) {
        for (a in AlarmStore(ctx).all()) if (a.enabled) schedule(ctx, a)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind") ?: "alarm"
        val id = intent.getIntExtra("id", -1)
        var label = intent.getStringExtra("label") ?: ""
        if (kind == "alarm") {
            val store = AlarmStore(ctx)
            val list = store.all()
            val a = list.firstOrNull { it.id == id }
            if (a != null) {
                if (a.days == 0) a.enabled = false else AlarmScheduler.schedule(ctx, a)
                store.save(list)
            }
        }
        if (label.isEmpty()) label = if (kind == "timer") "Timer" else "Alarm"
        val svc = Intent(ctx, AlarmService::class.java).putExtra("label", label).putExtra("kind", kind)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc) else ctx.startService(svc)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(ctx)
    }
}

/** Rings the alarm (looping sound + vibration) with a full-screen notification. */
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var label = "Alarm"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRinging()
                return START_NOT_STICKY
            }
            ACTION_SNOOZE -> {
                AlarmScheduler.scheduleAt(this, AlarmScheduler.SNOOZE_CODE, System.currentTimeMillis() + ClockSettings.snoozeMinutes(this) * 60_000L, "snooze", 0, label)
                stopRinging()
                return START_NOT_STICKY
            }
        }
        label = intent?.getStringExtra("label") ?: "Alarm"
        startRinging()
        return START_NOT_STICKY
    }

    private fun startRinging() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Alarms", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(null, null)
            nm.createNotificationChannel(ch)
        }
        val full = PendingIntent.getActivity(
            this, 1, Intent(this, RingActivity::class.java).putExtra("label", label).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(code: Int, act: String) = PendingIntent.getService(this, code, Intent(this, AlarmService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(label)
            .setContentText("Alarm")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(Notification.Action.Builder(null, "Snooze", action(2, ACTION_SNOOZE)).build())
            .addAction(Notification.Action.Builder(null, "Stop", action(3, ACTION_STOP)).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(ID, n)

        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val m = MediaPlayer()
            m.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            m.setDataSource(this, uri)
            m.isLooping = true
            m.prepare()
            m.start()
            player = m
        } catch (_: Exception) {
        }
        try {
            val v = getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0))
            else @Suppress("DEPRECATION") v.vibrate(longArrayOf(0, 700, 500), 0)
        } catch (_: Exception) {
        }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopRinging() }, 120_000L)
    }

    private fun stopRinging() {
        handler.removeCallbacksAndMessages(null)
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        try {
            getSystemService(Vibrator::class.java).cancel()
        } catch (_: Exception) {
        }
        sendBroadcast(Intent(RingActivity.ACTION_CLOSE).setPackage(packageName))
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        player?.release()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "alarm"
        const val ID = 11
        const val ACTION_STOP = "com.belleui.clock.STOP"
        const val ACTION_SNOOZE = "com.belleui.clock.SNOOZE"
    }
}

/** The Clock settings (clock.r01: "Alarm snooze time", 1 to 60 minutes). */
object ClockSettings {
    val SNOOZE_CHOICES = listOf(1, 5, 10, 15, 20, 30, 60)

    fun snoozeMinutes(ctx: Context): Int = ctx.getSharedPreferences("clock", Context.MODE_PRIVATE).getInt("snooze", 10).coerceIn(1, 60)

    fun setSnoozeMinutes(ctx: Context, m: Int) {
        ctx.getSharedPreferences("clock", Context.MODE_PRIVATE).edit().putInt("snooze", m.coerceIn(1, 60)).apply()
    }
}
