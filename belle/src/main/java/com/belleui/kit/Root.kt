package com.belleui.kit

import android.os.SystemClock

/**
 * Running a command as root, for the things Android keeps from normal apps (switching Wi-Fi, Bluetooth and
 * airplane mode, CPU speed, force-stopping apps). Magisk asks the user the first time an app does this; if the
 * answer is no, or there is no root, every call just returns null and the caller falls back to a normal way.
 */
object Root {
    /** Runs [cmd] as root and returns what it printed, or null if root is missing or the command failed or timed out. */
    fun run(cmd: String, timeoutMs: Long = 8000): String? {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val sb = StringBuilder()
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { sb.append(it).append('\n') }
                } catch (_: Exception) {
                }
            }
            reader.start()
            val end = SystemClock.uptimeMillis() + timeoutMs
            while (SystemClock.uptimeMillis() < end) {
                try {
                    val code = p.exitValue()
                    reader.join(500)
                    return if (code == 0) sb.toString().trim() else null
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(40)
                }
            }
            p.destroy()
            null
        } catch (_: Exception) {
            null
        }
    }

    /** True if root answers. Call off the main thread. */
    fun available(): Boolean = run("id -u")?.trim() == "0"
}
