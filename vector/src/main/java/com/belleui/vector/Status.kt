package com.belleui.vector

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Tells the module's own page what the hooks did. Hooks run inside other processes (the system process, the
 * chooser's process, the system UI), so each one leaves a short note in a system setting that the Belle Chooser
 * app can read. That lets you see whether the hooks attached without a computer.
 */
object Status {
    private val lines = ArrayList<String>()
    private var process = "?"
    private var tries = 0
    private var scheduled = false

    fun key(process: String) = "belle_chooser_" + process.replace(':', '_').replace('.', '_')

    private fun context(): Context? = try {
        val at = Class.forName("android.app.ActivityThread")
        (at.getMethod("currentApplication").invoke(null) as? Context)
            ?: at.getMethod("currentActivityThread").invoke(null)?.let { at.getMethod("getSystemContext").invoke(it) as? Context }
    } catch (_: Throwable) {
        null
    }

    @Synchronized
    fun note(processName: String, line: String) {
        process = processName
        lines.add(line)
        if (!scheduled) {
            scheduled = true
            // the settings provider is not ready while the process is starting: try again every few seconds
            Handler(Looper.getMainLooper()).postDelayed(object : Runnable {
                override fun run() {
                    if (!flush() && tries++ < 12) Handler(Looper.getMainLooper()).postDelayed(this, 8000)
                }
            }, 6000)
        } else {
            flush()
        }
    }

    @Synchronized
    private fun flush(): Boolean {
        val ctx = context() ?: return false
        return try {
            val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            Settings.Global.putString(ctx.contentResolver, key(process), "$now\n" + lines.joinToString("\n"))
            true
        } catch (_: Throwable) {
            false
        }
    }
}
