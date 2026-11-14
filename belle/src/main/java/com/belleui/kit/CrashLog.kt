package com.belleui.kit

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Keeps the last crash of an app, so it can be read on the device the next time the app is opened
 * (no computer or logcat needed) and sent on.
 */
object CrashLog {
    private var installed = false

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "last_crash.txt")

    @Synchronized
    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file(app).writeText("${app.packageName}  $stamp\nthread: ${t.name}\n\n" + Log.getStackTraceString(e))
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun pending(ctx: Context): String? = try {
        file(ctx).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (_: Exception) { }
    }
}

/** Shows a saved crash report: the error and where it happened. */
class CrashScreen(private val report: String) : Screen() {
    override val title get() = "Crash report"
    private var top = 0
    private var lines: List<String> = emptyList()
    private var wrappedFor = 0f

    override fun menu() = listOf(
        MenuItem("Share report") {
            shell.context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "Share crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        MenuItem("Dismiss") {
            CrashLog.clear(shell.context)
            shell.pop()
        },
    )

    private fun wrap(ui: Ui, w: Float, size: Float) {
        if (wrappedFor == w) return
        wrappedFor = w
        val out = ArrayList<String>()
        for (raw in report.lines().take(70)) {
            var line = raw.replace("\t", "    ")
            while (line.isNotEmpty()) {
                var n = line.length
                while (n > 1 && ui.measure(line.substring(0, n), size, ui.light) > w) n--
                out.add(line.substring(0, n))
                line = line.substring(n)
            }
            if (raw.isEmpty()) out.add("")
        }
        lines = out
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        val size = 15f * ui.sc
        val lh = 19f * ui.sc
        wrap(ui, r.width() - 24f * ui.sc, size)
        val vis = max(1, ((r.height() - 8f * ui.sc) / lh).toInt())
        top = top.coerceIn(0, max(0, lines.size - vis))
        for (i in 0 until vis) {
            val l = lines.getOrNull(top + i) ?: break
            ui.text(c, l, r.left + 12f * ui.sc, r.top + 22f * ui.sc + i * lh, size, if (i + top == 0) Belle.BLUE_LIGHT else Color.WHITE, face = ui.light)
        }
    }

    override fun onAct(a: Act): Boolean {
        when (a) {
            Act.UP -> top--
            Act.DOWN -> top++
            Act.PREV -> top -= 10
            Act.NEXT -> top += 10
            else -> return false
        }
        top = max(0, top)
        return true
    }

    override fun onScroll(dy: Float) {
        top = max(0, top + if (dy > 0) 3 else -3)
    }
}
