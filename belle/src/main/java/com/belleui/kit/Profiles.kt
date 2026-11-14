package com.belleui.kit

import android.content.Context
import android.media.AudioManager
import android.provider.Settings

/**
 * Sound profiles in the Belle manner: one choice sets the ringer, the volumes and the vibration together.
 * The chosen profile is kept in a system setting so every app (and the system-process pieces) agree on it.
 */
object Profiles {
    const val KEY = "belle_profile"
    val names = listOf("General", "Silent", "Meeting", "Outdoor")

    fun current(ctx: Context): Int = try {
        Settings.System.getInt(ctx.contentResolver, KEY, 0).coerceIn(0, names.size - 1)
    } catch (_: Exception) {
        0
    }

    /** Applies profile [id]; returns false if some part was refused (for example Silent without do-not-disturb access). */
    fun apply(ctx: Context, id: Int): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var ok = true
        fun vol(stream: Int, pct: Int) {
            try {
                am.setStreamVolume(stream, (am.getStreamMaxVolume(stream) * pct / 100f).toInt(), 0)
            } catch (_: SecurityException) {
                ok = false
            }
        }
        fun ringer(mode: Int) {
            try {
                am.ringerMode = mode
            } catch (_: SecurityException) {
                ok = false
                // without do-not-disturb access, vibrate is the quietest allowed
                try { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE } catch (_: SecurityException) { }
            }
        }
        when (id) {
            1 -> ringer(AudioManager.RINGER_MODE_SILENT)
            2 -> ringer(AudioManager.RINGER_MODE_VIBRATE)
            3 -> {
                ringer(AudioManager.RINGER_MODE_NORMAL)
                vol(AudioManager.STREAM_RING, 100)
                vol(AudioManager.STREAM_NOTIFICATION, 100)
                vol(AudioManager.STREAM_ALARM, 100)
            }
            else -> {
                ringer(AudioManager.RINGER_MODE_NORMAL)
                vol(AudioManager.STREAM_RING, 60)
                vol(AudioManager.STREAM_NOTIFICATION, 60)
            }
        }
        try {
            Settings.System.putInt(ctx.contentResolver, KEY, id)
            Settings.System.putInt(ctx.contentResolver, Settings.System.VIBRATE_WHEN_RINGING, if (id == 0 || id == 3) 0 else 1)
        } catch (_: Exception) {
            ok = false
        }
        return ok
    }
}
