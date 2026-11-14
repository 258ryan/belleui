package com.belleui.clock

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.format.DateFormat
import com.belleui.kit.Act
import com.belleui.kit.Belle
import com.belleui.kit.Ic
import com.belleui.kit.ListDialog
import com.belleui.kit.ListState
import com.belleui.kit.MenuItem
import com.belleui.kit.QueryDialog
import com.belleui.kit.Row
import com.belleui.kit.Screen
import com.belleui.kit.TabDef
import com.belleui.kit.Tool
import com.belleui.kit.Tumbler
import com.belleui.kit.TumblerDialog
import com.belleui.kit.Ui
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Stopwatch and timer state; lives for the process so tabs keep running. */
object Chrono {
    var swRunning = false
    var swStart = 0L
    var swAccum = 0L
    val laps = ArrayList<Long>()

    fun swElapsed(): Long = swAccum + if (swRunning) SystemClock.elapsedRealtime() - swStart else 0L

    var timerTotal = 5 * 60_000L
    var timerRunning = false
    var timerEnd = 0L          // epoch ms when running
    var timerLeftPaused = 0L   // ms left when paused (0 = idle)

    fun timerLeft(): Long = when {
        timerRunning -> (timerEnd - System.currentTimeMillis()).coerceAtLeast(0)
        timerLeftPaused > 0 -> timerLeftPaused
        else -> timerTotal
    }
}

private val CITIES = listOf(
    "London" to "Europe/London", "Paris" to "Europe/Paris", "Berlin" to "Europe/Berlin", "Moscow" to "Europe/Moscow",
    "Istanbul" to "Europe/Istanbul", "Cairo" to "Africa/Cairo", "Nairobi" to "Africa/Nairobi", "Dubai" to "Asia/Dubai",
    "Mumbai" to "Asia/Kolkata", "Bangkok" to "Asia/Bangkok", "Beijing" to "Asia/Shanghai", "Tokyo" to "Asia/Tokyo",
    "Seoul" to "Asia/Seoul", "Sydney" to "Australia/Sydney", "Auckland" to "Pacific/Auckland", "Honolulu" to "Pacific/Honolulu",
    "Los Angeles" to "America/Los_Angeles", "Denver" to "America/Denver", "Chicago" to "America/Chicago",
    "New York" to "America/New_York", "Sao Paulo" to "America/Sao_Paulo",
)

private val DAY_NAMES = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private fun repeatText(days: Int) = when (days) {
    0 -> "Once"
    127 -> "Every day"
    31 -> "Weekdays"
    96 -> "Weekends"
    else -> DAY_NAMES.filterIndexed { i, _ -> (days shr i) and 1 == 1 }.joinToString(" ")
}

private fun chrono(ms: Long, centis: Boolean): String {
    val total = ms / 10
    val cs = total % 100
    val s = (total / 100) % 60
    val m = (total / 6000) % 60
    val h = total / 360000
    val base = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    return if (centis) "$base.%02d".format(cs) else base
}

private fun until(ms: Long): String {
    val min = (ms / 60_000).toInt() + 1
    val h = min / 60
    val m = min % 60
    return when {
        h > 0 && m > 0 -> "Alarm in $h h $m min"
        h > 0 -> "Alarm in $h h"
        else -> "Alarm in $m min"
    }
}

class ClockScreen(private val act: ClockActivity) : Screen() {
    override val title get() = "Clock"
    override val tabs = listOf(
        TabDef("Clock", Ic.CLOCK), TabDef("Alarms", Ic.BELL), TabDef("Stopwatch", Ic.STOPWATCH), TabDef("Timer", Ic.HOURGLASS),
    )

    private val prefs = act.getSharedPreferences("clock", 0)
    private val alarmList = ListState()
    private val lapList = ListState(50f)
    private val is24 get() = DateFormat.is24HourFormat(act)

    private var zones: MutableList<String> = (prefs.getString("zones", "America/New_York,Asia/Tokyo") ?: "").split(",").filter { it.isNotEmpty() }.toMutableList()
    private var alarms: MutableList<Alarm> = act.store.all()

    override val tickMs: Long
        get() = when (tab) {
            0 -> 1000L
            2 -> if (Chrono.swRunning) 40L else 0L
            3 -> if (Chrono.timerRunning) 250L else 0L
            else -> 0L
        }

    override fun onShow() {
        alarms = act.store.all()
    }

    // ---- toolbar and menu --------------------------------------------------------------------

    override fun tools(): List<Tool> = when (tab) {
        0 -> listOf(Tool(Ic.ADD, { addCity() }))
        1 -> listOf(Tool(Ic.ADD, { newAlarm() }))
        2 -> listOf(
            Tool(if (Chrono.swRunning) Ic.PAUSE else Ic.PLAY, { swToggle() }),
            Tool(if (Chrono.swRunning) Ic.FLAG else Ic.REFRESH, { swSecondary() }, Chrono.swRunning || Chrono.swElapsed() > 0),
        )
        else -> listOf(
            Tool(if (Chrono.timerRunning) Ic.PAUSE else Ic.PLAY, { timerToggle() }),
            Tool(Ic.REFRESH, { timerReset() }, Chrono.timerRunning || Chrono.timerLeftPaused > 0),
        )
    }

    override fun menu(): List<MenuItem> = when (tab) {
        0 -> listOf(MenuItem("Add city") { addCity() }) + if (zones.isNotEmpty()) listOf(MenuItem("Remove city") { removeCity() }) else emptyList()
        1 -> {
            val items = arrayListOf(MenuItem("New alarm") { newAlarm() })
            alarms.getOrNull(alarmList.sel)?.let { a ->
                items.add(MenuItem("Edit time") { editTime(a) })
                items.add(MenuItem("Repeat: ${repeatText(a.days)}") { editRepeat(a) })
                items.add(MenuItem("Delete") { deleteAlarm(a) })
            }
            items
        }
        3 -> listOf(MenuItem("Set time") { timerSet() })
        else -> emptyList()
    }

    // ---- clock tab -----------------------------------------------------------------------------

    private fun addCity() {
        val free = CITIES.filter { it.second !in zones }
        if (free.isEmpty()) return
        shell.showOverlay(ListDialog("Add city", free.map { it.first }) { i ->
            zones.add(free[i].second)
            prefs.edit().putString("zones", zones.joinToString(",")).apply()
        })
    }

    private fun removeCity() {
        val names = zones.map { z -> CITIES.firstOrNull { it.second == z }?.first ?: z }
        shell.showOverlay(ListDialog("Remove city", names) { i ->
            zones.removeAt(i)
            prefs.edit().putString("zones", zones.joinToString(",")).apply()
        })
    }

    private fun drawAnalog(c: Canvas, ui: Ui, cx: Float, cy: Float, rad: Float, cal: Calendar) {
        val p = ui.p
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = 0xFF111111.toInt()
        c.drawCircle(cx, cy, rad, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f * ui.sc
        p.color = 0xFF5A5A5A.toInt()
        c.drawCircle(cx, cy, rad, p)
        p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 60) {
            val a = i * PI.toFloat() / 30f
            val major = i % 5 == 0
            val r1 = rad - (if (major) 18f else 10f) * ui.sc
            val r2 = rad - 6f * ui.sc
            p.strokeWidth = (if (major) 3f else 1.2f) * ui.sc
            p.color = if (major) Color.WHITE else 0xFF777777.toInt()
            c.drawLine(cx + sin(a) * r1, cy - cos(a) * r1, cx + sin(a) * r2, cy - cos(a) * r2, p)
        }
        for ((n, i) in listOf(12 to 0, 3 to 3, 6 to 6, 9 to 9)) {
            val a = i * PI.toFloat() / 6f
            val rr = rad - 38f * ui.sc
            ui.text(c, n.toString(), cx + sin(a) * rr, ui.baselineFor(cy - cos(a) * rr, 22f * ui.sc), 22f * ui.sc, align = Paint.Align.CENTER)
        }
        val sec = cal.get(Calendar.SECOND)
        val min = cal.get(Calendar.MINUTE) + sec / 60f
        val hour = (cal.get(Calendar.HOUR) % 12) + min / 60f
        fun hand(angle: Float, len: Float, w: Float, color: Int) {
            p.strokeWidth = w * ui.sc
            p.color = color
            c.drawLine(cx - sin(angle) * len * 0.12f, cy + cos(angle) * len * 0.12f, cx + sin(angle) * len, cy - cos(angle) * len, p)
        }
        hand(hour * PI.toFloat() / 6f, rad * 0.5f, 7f, Color.WHITE)
        hand(min * PI.toFloat() / 30f, rad * 0.75f, 5f, Color.WHITE)
        hand(sec * PI.toFloat() / 30f, rad * 0.85f, 2.2f, Belle.BLUE_LIGHT)
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.BUTT
        p.color = Belle.BLUE_LIGHT
        c.drawCircle(cx, cy, 6f * ui.sc, p)
    }

    private fun drawClockTab(c: Canvas, ui: Ui, r: RectF) {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val rad = min(r.height() / 2f - 22f * ui.sc, 150f * ui.sc)
        val cx = r.left + rad + 30f * ui.sc
        val cy = r.centerY()
        drawAnalog(c, ui, cx, cy, rad, cal)

        val x = cx + rad + 36f * ui.sc
        val w = r.right - x - 16f * ui.sc
        val time = DateFormat.format(if (is24) "HH:mm" else "h:mm", now).toString()
        val topY = r.top + 24f * ui.sc
        ui.text(c, time, x, ui.baselineFor(topY + 36f * ui.sc, 72f * ui.sc, ui.light), 72f * ui.sc, face = ui.light)
        if (!is24) ui.text(c, DateFormat.format("a", now).toString(), x + ui.measure(time, 72f * ui.sc, ui.light) + 8f * ui.sc, ui.baselineFor(topY + 56f * ui.sc, 22f * ui.sc), 22f * ui.sc, Belle.BLUE_LIGHT)
        ui.text(c, DateFormat.format("EEEE, d MMMM", now).toString(), x, ui.baselineFor(topY + 92f * ui.sc, 20f * ui.sc, ui.light), 20f * ui.sc, Belle.TEXT_MID, maxW = w, face = ui.light)

        var y = topY + 116f * ui.sc
        if (zones.isEmpty()) {
            ui.text(c, "Press Y to add a city", x, ui.baselineFor(y + 30f * ui.sc, 18f * ui.sc, ui.light), 18f * ui.sc, Belle.TEXT_MID, face = ui.light)
        }
        for (z in zones.take(4)) {
            val tz = TimeZone.getTimeZone(z)
            val name = CITIES.firstOrNull { it.second == z }?.first ?: z
            val local = Calendar.getInstance(tz)
            val dayDiff = local.get(Calendar.DAY_OF_YEAR) - cal.get(Calendar.DAY_OF_YEAR)
            val tag = when { dayDiff == 0 -> ""; dayDiff == 1 || dayDiff < -300 -> " +1"; else -> " -1" }
            ui.rf.set(x, y, x + w, y + 46f * ui.sc)
            ui.p.style = Paint.Style.FILL
            ui.p.shader = null
            ui.p.color = Belle.SEPARATOR
            c.drawRect(ui.rf.left, ui.rf.bottom - 1f, ui.rf.right, ui.rf.bottom, ui.p)
            ui.text(c, name, x, ui.baselineFor(ui.rf.centerY(), 20f * ui.sc), 20f * ui.sc, maxW = w * 0.55f)
            val f = java.text.SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", Locale.getDefault())
            f.timeZone = tz
            ui.text(c, f.format(Date(now)) + tag, x + w, ui.baselineFor(ui.rf.centerY(), 20f * ui.sc, ui.light), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.RIGHT, face = ui.light)
            y += 46f * ui.sc
        }
    }

    // ---- alarms tab ----------------------------------------------------------------------------

    private fun timeText(h: Int, m: Int): String =
        if (is24) "%02d:%02d".format(h, m) else "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h >= 12) "PM" else "AM")

    private fun pickTime(h: Int, m: Int, title: String, done: (Int, Int) -> Unit) {
        val cols = ArrayList<Tumbler>()
        val mins = Tumbler((0..59).map { "%02d".format(it) }, m)
        if (is24) {
            cols.add(Tumbler((0..23).map { "%02d".format(it) }, h))
            cols.add(mins)
            shell.showOverlay(TumblerDialog(title, cols) { r -> done(r[0], r[1]) })
        } else {
            cols.add(Tumbler((1..12).map { it.toString() }, (if (h % 12 == 0) 12 else h % 12) - 1))
            cols.add(mins)
            cols.add(Tumbler(listOf("AM", "PM"), if (h >= 12) 1 else 0))
            shell.showOverlay(TumblerDialog(title, cols) { r -> done((r[0] + 1) % 12 + if (r[2] == 1) 12 else 0, r[1]) })
        }
    }

    private fun save() {
        act.store.save(alarms)
        alarms = act.store.all()
    }

    private fun newAlarm() {
        val now = Calendar.getInstance()
        pickTime(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), "New alarm") { h, m ->
            val a = Alarm(act.store.nextId(), h, m, 0, true, "")
            alarms.add(a)
            save()
            shell.toast(until(AlarmScheduler.schedule(act, a) - System.currentTimeMillis()))
        }
    }

    private fun editTime(a: Alarm) {
        pickTime(a.hour, a.minute, "Alarm time") { h, m ->
            a.hour = h
            a.minute = m
            if (a.enabled) shell.toast(until(AlarmScheduler.schedule(act, a) - System.currentTimeMillis()))
            save()
        }
    }

    private fun editRepeat(a: Alarm) {
        val masks = intArrayOf(0, 127, 31, 96)
        shell.showOverlay(ListDialog("Repeat", listOf("Once", "Every day", "Weekdays", "Weekends"), masks.indexOf(a.days)) { i ->
            a.days = masks[i]
            if (a.enabled) AlarmScheduler.schedule(act, a)
            save()
        })
    }

    private fun deleteAlarm(a: Alarm) {
        shell.showOverlay(QueryDialog("Delete alarm ${timeText(a.hour, a.minute)}?", "Delete", "Cancel") {
            AlarmScheduler.cancel(act, a)
            alarms.removeAll { it.id == a.id }
            save()
        })
    }

    private fun toggleAlarm(a: Alarm) {
        a.enabled = !a.enabled
        if (a.enabled) shell.toast(until(AlarmScheduler.schedule(act, a) - System.currentTimeMillis())) else AlarmScheduler.cancel(act, a)
        save()
    }

    private fun drawAlarms(c: Canvas, ui: Ui, r: RectF) {
        if (alarms.isEmpty()) {
            ui.text(c, "No alarms", r.centerX(), ui.baselineFor(r.centerY() - 14f * ui.sc, 22f * ui.sc), 22f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
            ui.text(c, "Press Y to add one", r.centerX(), ui.baselineFor(r.centerY() + 16f * ui.sc, 18f * ui.sc, ui.light), 18f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER, face = ui.light)
            return
        }
        alarmList.draw(c, ui, r, alarms.size) { i ->
            val a = alarms[i]
            Row(timeText(a.hour, a.minute), repeatText(a.days) + if (a.label.isNotEmpty()) " - ${a.label}" else "", iconKind = Ic.BELL, iconColor = 0xFFE8761A.toInt(), dim = !a.enabled, rightReserve = 84f)
        }
        val vis = alarmList.visible(ui, r.height())
        for (i in alarms.indices) {
            val rr = alarmList.rowRect(ui, r, i, ui.tmp)
            if (rr.bottom < r.top || rr.top > r.bottom) continue
            ui.switchButton(c, r.right - 12f * ui.sc - 33f * ui.sc, rr.centerY(), alarms[i].enabled)
        }
        if (vis < 0) return
    }

    // ---- stopwatch tab ----------------------------------------------------------------------------

    private fun swToggle() {
        if (Chrono.swRunning) {
            Chrono.swAccum += SystemClock.elapsedRealtime() - Chrono.swStart
            Chrono.swRunning = false
        } else {
            Chrono.swStart = SystemClock.elapsedRealtime()
            Chrono.swRunning = true
        }
    }

    private fun swSecondary() {
        if (Chrono.swRunning) {
            Chrono.laps.add(Chrono.swElapsed())
        } else {
            Chrono.swAccum = 0
            Chrono.laps.clear()
        }
    }

    private fun drawStopwatch(c: Canvas, ui: Ui, r: RectF) {
        val big = 76f * ui.sc
        val top = r.top + 36f * ui.sc
        ui.text(c, chrono(Chrono.swElapsed(), true), r.centerX(), ui.baselineFor(top + 40f * ui.sc, big, ui.light), big, align = Paint.Align.CENTER, face = ui.light)
        val list = RectF(r.left, top + 100f * ui.sc, r.right, r.bottom)
        if (Chrono.laps.isEmpty()) {
            ui.text(c, if (Chrono.swRunning) "Y pause  -  St lap" else "Y start", r.centerX(), ui.baselineFor(list.top + 30f * ui.sc, 18f * ui.sc, ui.light), 18f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER, face = ui.light)
            return
        }
        lapList.draw(c, ui, list, Chrono.laps.size, showSel = false) { i ->
            val idx = Chrono.laps.size - 1 - i
            val split = Chrono.laps[idx] - (if (idx > 0) Chrono.laps[idx - 1] else 0L)
            Row("Lap ${idx + 1}", right = chrono(split, true))
        }
    }

    // ---- timer tab ---------------------------------------------------------------------------------

    private fun timerToggle() {
        if (Chrono.timerRunning) {
            Chrono.timerLeftPaused = Chrono.timerLeft()
            Chrono.timerRunning = false
            AlarmScheduler.cancelAt(act, AlarmScheduler.TIMER_CODE)
        } else {
            val left = if (Chrono.timerLeftPaused > 0) Chrono.timerLeftPaused else Chrono.timerTotal
            if (left <= 0) return
            Chrono.timerEnd = System.currentTimeMillis() + left
            Chrono.timerRunning = true
            Chrono.timerLeftPaused = 0
            AlarmScheduler.scheduleAt(act, AlarmScheduler.TIMER_CODE, Chrono.timerEnd, "timer", 0, "Timer")
        }
    }

    private fun timerReset() {
        Chrono.timerRunning = false
        Chrono.timerLeftPaused = 0
        AlarmScheduler.cancelAt(act, AlarmScheduler.TIMER_CODE)
    }

    private fun timerSet() {
        if (Chrono.timerRunning) return
        val t = Chrono.timerTotal / 1000
        val cols = listOf(
            Tumbler((0..23).map { "%02d h".format(it) }, (t / 3600).toInt()),
            Tumbler((0..59).map { "%02d m".format(it) }, ((t / 60) % 60).toInt()),
            Tumbler((0..59).map { "%02d s".format(it) }, (t % 60).toInt()),
        )
        shell.showOverlay(TumblerDialog("Timer", cols) { r ->
            Chrono.timerTotal = (r[0] * 3600L + r[1] * 60L + r[2]) * 1000L
            Chrono.timerLeftPaused = 0
        })
    }

    private fun drawTimer(c: Canvas, ui: Ui, r: RectF) {
        if (Chrono.timerRunning && Chrono.timerEnd <= System.currentTimeMillis()) {
            Chrono.timerRunning = false
            Chrono.timerLeftPaused = 0
        }
        val left = Chrono.timerLeft()
        val big = 84f * ui.sc
        ui.text(c, chrono(left, false), r.centerX(), ui.baselineFor(r.centerY() - 20f * ui.sc, big, ui.light), big, align = Paint.Align.CENTER, face = ui.light)
        val bar = RectF(r.left + 60f * ui.sc, r.centerY() + 44f * ui.sc, r.right - 60f * ui.sc, r.centerY() + 64f * ui.sc)
        ui.progressBar(c, bar, if (Chrono.timerTotal > 0) left.toFloat() / Chrono.timerTotal else 0f)
        val hint = when {
            Chrono.timerRunning -> "Y pause"
            Chrono.timerLeftPaused > 0 -> "Y resume  -  St reset"
            else -> "A set time  -  Y start"
        }
        ui.text(c, hint, r.centerX(), ui.baselineFor(r.centerY() + 100f * ui.sc, 18f * ui.sc, ui.light), 18f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER, face = ui.light)
    }

    // ---- screen plumbing -----------------------------------------------------------------------------

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        when (tab) {
            0 -> drawClockTab(c, ui, r)
            1 -> drawAlarms(c, ui, r)
            2 -> drawStopwatch(c, ui, r)
            else -> drawTimer(c, ui, r)
        }
    }

    override fun onAct(a: Act): Boolean {
        when (tab) {
            1 -> {
                if (alarms.isEmpty()) return false
                val vis = alarmList.visible(shell.ui, shell.contentBottom - shell.contentTop)
                if (a == Act.OK) {
                    toggleAlarm(alarms[alarmList.sel])
                    return true
                }
                return alarmList.act(a, alarms.size, vis)
            }
            2 -> if (a == Act.OK) {
                swToggle()
                return true
            }
            3 -> if (a == Act.OK) {
                if (Chrono.timerRunning) timerToggle() else timerSet()
                return true
            }
        }
        return false
    }

    override fun onTap(x: Float, y: Float): Boolean {
        when (tab) {
            1 -> {
                val r = RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom)
                val i = alarmList.hit(shell.ui, r, y, alarms.size)
                if (i < 0) return false
                alarmList.sel = i
                if (x > r.right - 110f * shell.ui.sc) toggleAlarm(alarms[i]) else editTime(alarms[i])
                return true
            }
            2 -> swToggle()
            3 -> if (Chrono.timerRunning) timerToggle() else timerSet()
            else -> return false
        }
        return true
    }

    override fun onLongPress(x: Float, y: Float) {
        if (tab == 1) {
            val r = RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom)
            val i = alarmList.hit(shell.ui, r, y, alarms.size)
            if (i >= 0) {
                alarmList.sel = i
                shell.perform(Act.OPTIONS)
            }
        }
    }

    override fun onScroll(dy: Float) {
        if (tab == 1) alarmList.scrollBy(if (dy > 0) 1 else -1, alarms.size, alarmList.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }
}
