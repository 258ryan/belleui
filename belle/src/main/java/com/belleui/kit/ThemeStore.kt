package com.belleui.kit

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * The look and feel settings shared by every Belle app. They are chosen in the Styles app, which
 * offers them through a small read-only content provider; each app keeps the last values it saw, so
 * start-up never waits for Styles.
 */
object ThemeStore {
    const val AUTHORITY = "com.belleui.styles.theme"
    private val main = Handler(Looper.getMainLooper())

    /** The accent picked in Styles; null until it has been picked. */
    @Volatile
    var shared: Int? = null
        private set

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("belle_theme", Context.MODE_PRIVATE)

    /** Reads the saved values; cheap, call before building any UI. */
    fun load(ctx: Context) {
        val p = prefs(ctx)
        if (p.contains("accent")) {
            shared = p.getInt("accent", Belle.DEFAULT_BLUE)
            Belle.setAccent(shared!!)
        }
        Belle.soundsOn = p.getBoolean("sounds", true)
    }

    /** Asks Styles for the current values on a worker thread; [onChange] runs on the main thread if they differ. */
    fun refresh(ctx: Context, onChange: () -> Unit) {
        val app = ctx.applicationContext
        Thread {
            try {
                app.contentResolver.query(Uri.parse("content://$AUTHORITY/theme"), null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val accent = c.getInt(0)
                        val sounds = c.getInt(1) != 0
                        var changed = Belle.soundsOn != sounds
                        val e = prefs(app).edit().putBoolean("sounds", sounds)
                        Belle.soundsOn = sounds
                        if (accent != 0) {
                            e.putInt("accent", accent)
                            if (Belle.BLUE != accent) {
                                shared = accent
                                Belle.setAccent(accent)
                                changed = true
                            }
                        }
                        e.apply()
                        if (changed) main.post(onChange)
                    }
                }
            } catch (_: Exception) {
                // Styles is not installed: keep the saved values
            }
        }.start()
    }

    /** Used by Styles itself, so its own screens change at once. */
    fun apply(ctx: Context, accent: Int, sounds: Boolean) {
        prefs(ctx).edit().putInt("accent", accent).putBoolean("sounds", sounds).apply()
        shared = accent
        Belle.soundsOn = sounds
        Belle.setAccent(accent)
    }
}
