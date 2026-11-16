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
        // the default blue means the accent was never picked: an app may keep its own colour then
        if (p.contains("accent") && p.getInt("accent", Belle.DEFAULT_BLUE) != Belle.DEFAULT_BLUE) {
            shared = p.getInt("accent", Belle.DEFAULT_BLUE)
            Belle.setAccent(shared!!)
        }
        Belle.soundsOn = p.getBoolean("sounds", true)
        Belle.lightLists = p.getBoolean("light", false)
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
                        val lightCol = c.getColumnIndex("light")
                        val light = if (lightCol >= 0) c.getInt(lightCol) != 0 else Belle.lightLists
                        var changed = Belle.soundsOn != sounds || Belle.lightLists != light
                        Belle.lightLists = light
                        val e = prefs(app).edit().putBoolean("sounds", sounds).putBoolean("light", light)
                        Belle.soundsOn = sounds
                        if (accent != 0 && accent != Belle.DEFAULT_BLUE) {
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
    fun apply(ctx: Context, accent: Int, sounds: Boolean, light: Boolean = Belle.lightLists) {
        prefs(ctx).edit().putInt("accent", accent).putBoolean("sounds", sounds).putBoolean("light", light).apply()
        Belle.lightLists = light
        shared = accent
        Belle.soundsOn = sounds
        Belle.setAccent(accent)
    }
}
