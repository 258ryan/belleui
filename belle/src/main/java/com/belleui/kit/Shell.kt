package com.belleui.kit

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Bundle
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Gamepad / keyboard actions. TOOL1 and TOOL2 are the two customisable toolbar buttons. */
enum class Act { UP, DOWN, LEFT, RIGHT, OK, BACK, OPTIONS, TOOL1, TOOL2, PREV, NEXT }

object Keys {
    /** How many times the key being handled has repeated (0 for the first press). */
    @Volatile @JvmField var repeat = 0

    fun actionFor(code: Int, swap: Boolean): Act? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> Act.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Act.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Act.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Act.RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Act.OK
        KeyEvent.KEYCODE_BUTTON_A -> if (swap) Act.BACK else Act.OK
        KeyEvent.KEYCODE_BUTTON_B -> if (swap) Act.OK else Act.BACK
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> Act.BACK
        KeyEvent.KEYCODE_BUTTON_X -> if (swap) Act.TOOL1 else Act.OPTIONS
        KeyEvent.KEYCODE_BUTTON_Y -> if (swap) Act.OPTIONS else Act.TOOL1
        KeyEvent.KEYCODE_MENU -> Act.OPTIONS
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT -> Act.TOOL2
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_PAGE_UP -> Act.PREV
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_PAGE_DOWN -> Act.NEXT
        else -> null
    }
}

class Tool(val kind: Int, val onClick: () -> Unit, val enabled: Boolean = true)
class MenuItem(val label: String, val action: () -> Unit)
class TabDef(val label: String, val kind: Int)

/** Battery, wifi and clock for the status bar. */
class StatusInfo(private val ctx: Context, private val onChange: () -> Unit) {
    var batteryPct = 100
    var charging = false
    var wifi = false
    var clock = ""

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) readBattery(intent)
            refresh()
            onChange()
        }
    }

    fun start() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            @Suppress("DEPRECATION")
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        val sticky = ctx.registerReceiver(receiver, f)
        if (sticky != null) readBattery(sticky)
        refresh()
    }

    fun stop() {
        try {
            ctx.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun readBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) batteryPct = level * 100 / scale
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
    }

    fun refresh() {
        val now = System.currentTimeMillis()
        clock = DateFormat.format(if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a", now).toString()
        wifi = try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (_: Exception) {
            false
        }
    }
}

// ================================================================================================
// screens and list helpers
// ================================================================================================

abstract class Screen {
    lateinit var shell: BelleShell
    open val title: String get() = ""
    open val tabs: List<TabDef> get() = emptyList()
    var tab = 0

    /** Up to two customisable toolbar buttons (Y / Start on a gamepad). */
    open fun tools(): List<Tool> = emptyList()
    open fun menu(): List<MenuItem> = emptyList()
    open fun onShow() {}
    open fun onTabChanged() {}

    /** Draw into [r], the area between the status/tab bar and the toolbar. */
    abstract fun draw(c: Canvas, ui: Ui, r: RectF)
    open fun drawOver(c: Canvas, ui: Ui, r: RectF) {}

    /** Return true if the action was used. BACK unhandled pops the screen. */
    open fun onAct(a: Act): Boolean = false
    open fun onTap(x: Float, y: Float): Boolean = false
    open fun onLongPress(x: Float, y: Float) {}
    open fun onScroll(dy: Float) {}

    /** Redraw period in ms for live content such as clocks; 0 = only on change. */
    open val tickMs: Long get() = 0L

    /** True when a view underneath (e.g. a WebView) shows through and gets unused touches and keys. */
    open val transparentContent: Boolean get() = false
}

/** Scrolling list of [Row]s. */
class ListState(val rowH: Float = Belle.ROW_H) {
    var sel = 0
    var top = 0
    private var topAnim = 0f

    fun visible(ui: Ui, h: Float) = max(1, (h / (rowH * ui.sc)).toInt())

    fun ensure(count: Int, vis: Int) {
        sel = sel.coerceIn(0, max(0, count - 1))
        if (sel < top) top = sel else if (sel >= top + vis) top = sel - vis + 1
        top = top.coerceIn(0, max(0, count - vis))
    }

    /** Handles UP/DOWN; returns true if the selection moved. */
    fun act(a: Act, count: Int, vis: Int, repeat: Int = Keys.repeat): Boolean {
        val before = sel
        // holding UP or DOWN speeds up: 1 row at first, then 2, then 4
        val step = if (repeat > 24) 4 else if (repeat > 10) 2 else 1
        when (a) {
            Act.UP -> sel = max(0, sel - step)
            Act.DOWN -> sel = min(count - 1, sel + step)
            Act.PREV -> sel = max(0, sel - vis)
            Act.NEXT -> sel = min(count - 1, sel + vis)
            else -> return false
        }
        ensure(count, vis)
        return sel != before
    }

    fun scrollBy(rows: Int, count: Int, vis: Int) {
        top = (top + rows).coerceIn(0, max(0, count - vis))
    }

    fun rowRect(ui: Ui, r: RectF, i: Int, out: RectF): RectF {
        val h = rowH * ui.sc
        out.set(r.left, r.top + (i - topAnim) * h, r.right, r.top + (i - topAnim + 1) * h)
        return out
    }

    /** Row index under [y], or -1. */
    fun hit(ui: Ui, r: RectF, y: Float, count: Int): Int {
        val i = ((y - r.top) / (rowH * ui.sc) + topAnim).toInt()
        return if (y >= r.top && i in 0 until count) i else -1
    }

    fun draw(c: Canvas, ui: Ui, r: RectF, count: Int, showSel: Boolean = true, rowAt: (Int) -> Row) {
        val vis = visible(ui, r.height())
        ensure(count, vis)
        topAnim += (top - topAnim) * ui.k
        if (abs(top - topAnim) < 0.004f) topAnim = top.toFloat() else ui.busy = true
        c.save()
        c.clipRect(r)
        val h = rowH * ui.sc
        for (i in 0 until count) {
            val y = r.top + (i - topAnim) * h
            if (y + h < r.top || y > r.bottom) continue
            ui.rf.set(r.left, y, r.right, y + h)
            ui.listRow(c, ui.rf, showSel && i == sel, rowAt(i))
        }
        c.restore()
        if (count > vis) ui.scrollbar(c, r.right, r.top, r.bottom, topAnim / (count - vis).toFloat(), vis.toFloat() / count)
    }
}

/** Scrolling grid of equally sized cells. */
class GridState(val cols: Int, private val cellAspect: Float = 1f) {
    var sel = 0
    var top = 0
    private var topAnim = 0f

    fun cellW(w: Float) = w / cols
    fun cellH(w: Float) = cellW(w) * cellAspect
    fun visibleRows(r: RectF) = max(1, (r.height() / cellH(r.width())).toInt())

    fun ensure(count: Int, r: RectF) {
        val vis = visibleRows(r)
        sel = sel.coerceIn(0, max(0, count - 1))
        val row = sel / cols
        if (row < top) top = row else if (row >= top + vis) top = row - vis + 1
        top = top.coerceIn(0, max(0, (count + cols - 1) / cols - vis))
    }

    fun act(a: Act, count: Int, r: RectF): Boolean {
        val before = sel
        when (a) {
            Act.LEFT -> if (sel % cols > 0) sel--
            Act.RIGHT -> if (sel % cols < cols - 1 && sel < count - 1) sel++
            Act.UP -> if (sel >= cols) sel -= cols
            Act.DOWN -> if (sel + cols < count) sel += cols else if (sel / cols < (count - 1) / cols) sel = count - 1
            Act.PREV -> sel = max(0, sel - cols * visibleRows(r))
            Act.NEXT -> sel = min(count - 1, sel + cols * visibleRows(r))
            else -> return false
        }
        ensure(count, r)
        return sel != before
    }

    fun scrollBy(rows: Int, count: Int, r: RectF) {
        top = (top + rows).coerceIn(0, max(0, (count + cols - 1) / cols - visibleRows(r)))
    }

    fun cellRect(r: RectF, i: Int, out: RectF): RectF {
        val cw = cellW(r.width())
        val ch = cellH(r.width())
        out.set(r.left + (i % cols) * cw, r.top + ((i / cols) - topAnim) * ch, r.left + (i % cols + 1) * cw, r.top + ((i / cols) - topAnim + 1) * ch)
        return out
    }

    fun hit(r: RectF, x: Float, y: Float, count: Int): Int {
        if (!r.contains(x, y)) return -1
        val i = (((y - r.top) / cellH(r.width()) + topAnim).toInt()) * cols + ((x - r.left) / cellW(r.width())).toInt()
        return if (i in 0 until count) i else -1
    }

    fun draw(c: Canvas, ui: Ui, r: RectF, count: Int, showSel: Boolean = true, cell: (Canvas, RectF, Int, Boolean) -> Unit) {
        ensure(count, r)
        topAnim += (top - topAnim) * ui.k
        if (abs(top - topAnim) < 0.004f) topAnim = top.toFloat() else ui.busy = true
        c.save()
        c.clipRect(r)
        val vis = visibleRows(r)
        for (i in 0 until count) {
            cellRect(r, i, ui.tmp)
            if (ui.tmp.bottom < r.top || ui.tmp.top > r.bottom) continue
            if (showSel && i == sel) ui.highlight(c, ui.tmp.let { RectF(it).apply { inset(2f * ui.sc, 2f * ui.sc) } }, 8f)
            cell(c, ui.tmp, i, showSel && i == sel)
        }
        c.restore()
        val rows = (count + cols - 1) / cols
        if (rows > vis) ui.scrollbar(c, r.right, r.top, r.bottom, topAnim / (rows - vis).toFloat(), vis.toFloat() / rows)
    }
}

// ================================================================================================
// overlays: options menu, dialogs
// ================================================================================================

abstract class Overlay {
    /** Slides up from the bottom (options menu) instead of a short fade-in slide. */
    open val slideUp: Boolean get() = false
    abstract fun draw(c: Canvas, ui: Ui, w: Float, h: Float, cTop: Float, cBot: Float)
    abstract fun onAct(a: Act, shell: BelleShell)
    open fun onTap(x: Float, y: Float, shell: BelleShell): Boolean = false

    protected fun dialogFrame(c: Canvas, ui: Ui, box: RectF, title: String?) {
        ui.nine(c, "qtg_fr_popup", box, 40, 20f)
        if (title != null) {
            ui.rf.set(box.left, box.top, box.right, box.top + 50f * ui.sc)
            ui.nine(c, "qtg_fr_popup_heading", ui.rf, 40, 20f)
            ui.text(c, title, box.left + 12f * ui.sc, ui.baselineFor(box.top + 25f * ui.sc, 22f * ui.sc), 22f * ui.sc, maxW = box.width() - 80f * ui.sc)
            ui.glyph(c, Ic.CLOSE, box.right - 28f * ui.sc, box.top + 25f * ui.sc, 30f * ui.sc)
        }
    }
}

/** Belle options Menu: slides up over the toolbar, 64px items, full-width Cancel button. */
class MenuOverlay(private val items: List<MenuItem>) : Overlay() {
    override val slideUp get() = true
    private var sel = 0
    private val box = RectF()
    private var itemH = 0f
    private var sc = 1f

    override fun draw(c: Canvas, ui: Ui, w: Float, h: Float, cTop: Float, cBot: Float) {
        sc = ui.sc
        itemH = min(Belle.MENU_ITEM_H * ui.sc, (h - cTop - 90f * ui.sc) / max(1, items.size))
        val bh = itemH * items.size + 4f * ui.sc + 6f * ui.sc + 50f * ui.sc + 8f * ui.sc
        box.set(0f, h - bh, w, h)
        ui.rf.set(box.left, box.top, box.right, box.bottom + 20f * ui.sc)
        ui.p.style = Paint.Style.FILL
        ui.p.shader = null
        ui.p.color = Belle.PANEL
        c.drawRoundRect(ui.rf, 12f * ui.sc, 12f * ui.sc, ui.p)
        ui.p.shader = android.graphics.LinearGradient(0f, ui.rf.top, 0f, ui.rf.top + 46f * ui.sc, ui.al(Color.WHITE, 26), ui.al(Color.WHITE, 0), android.graphics.Shader.TileMode.CLAMP)
        c.drawRoundRect(ui.rf, 12f * ui.sc, 12f * ui.sc, ui.p)
        ui.p.shader = null
        val top0 = box.top + 4f * ui.sc
        for (i in items.indices) {
            val top = top0 + itemH * i
            if (i == sel) {
                ui.tmp.set(box.left + 4f * ui.sc, top + 1f * ui.sc, box.right - 4f * ui.sc, top + itemH - 1f * ui.sc)
                ui.highlight(c, ui.tmp, 14f)
            } else if (i > 0) {
                ui.p.color = ui.al(Color.WHITE, 30)
                c.drawRect(box.left + 12f * ui.sc, top, box.right - 12f * ui.sc, top + 1f, ui.p)
            }
            ui.text(c, items[i].label, box.left + 16f * ui.sc, ui.baselineFor(top + itemH / 2f, 22f * ui.sc), 22f * ui.sc, maxW = w - 40f * ui.sc)
        }
        val cy0 = top0 + itemH * items.size + 6f * ui.sc
        ui.tmp.set(box.left + 8f * ui.sc, cy0, box.right - 8f * ui.sc, cy0 + 50f * ui.sc)
        ui.pushButton(c, ui.tmp, "Cancel", sel == items.size)
    }

    override fun onAct(a: Act, shell: BelleShell) {
        val n = items.size + 1
        when (a) {
            Act.UP -> sel = (sel - 1 + n) % n
            Act.DOWN -> sel = (sel + 1) % n
            Act.OK -> {
                val item = items.getOrNull(sel)
                shell.closeOverlay()
                item?.action?.invoke()
            }
            Act.BACK, Act.OPTIONS -> shell.closeOverlay()
            else -> {}
        }
    }

    override fun onTap(x: Float, y: Float, shell: BelleShell): Boolean {
        if (!box.contains(x, y)) {
            shell.closeOverlay()
            return true
        }
        val row = ((y - box.top - 4f * sc) / itemH).toInt()
        sel = row.coerceIn(0, items.size)
        onAct(Act.OK, shell)
        return true
    }
}

/** SelectionDialog: blue heading with close icon, 64px items, the chosen one carries a tick. */
class ListDialog(
    private val title: String,
    private val items: List<String>,
    private var chosen: Int = -1,
    private val onPick: (Int) -> Unit,
) : Overlay() {
    private var sel = max(0, chosen)
    private var top = 0
    private val box = RectF()
    private var sc = 1f
    private var vis = 1

    override fun draw(c: Canvas, ui: Ui, w: Float, h: Float, cTop: Float, cBot: Float) {
        sc = ui.sc
        val rowH = Belle.MENU_ITEM_H * ui.sc
        vis = max(1, min(items.size, ((cBot - cTop - 70f * ui.sc) / rowH).toInt()))
        if (sel < top) top = sel else if (sel >= top + vis) top = sel - vis + 1
        val bw = min(504f * ui.sc, w - 16f * ui.sc)
        val bh = 50f * ui.sc + rowH * vis + 6f * ui.sc
        val x = (w - bw) / 2f
        val y = cTop + max(0f, (cBot - cTop - bh) / 2f)
        box.set(x, y, x + bw, y + bh)
        dialogFrame(c, ui, box, title)
        for (j in 0 until vis) {
            val i = top + j
            val rt = box.top + 50f * ui.sc + rowH * j
            if (i == sel) {
                ui.tmp.set(box.left + 4f * ui.sc, rt + 1f * ui.sc, box.right - 4f * ui.sc, rt + rowH - 1f * ui.sc)
                ui.highlight(c, ui.tmp, 14f)
            } else if (j > 0) {
                ui.p.style = Paint.Style.FILL
                ui.p.color = ui.al(Color.WHITE, 30)
                c.drawRect(box.left + 12f * ui.sc, rt, box.right - 12f * ui.sc, rt + 1f, ui.p)
            }
            ui.text(c, items[i], box.left + 16f * ui.sc, ui.baselineFor(rt + rowH / 2f, 22f * ui.sc), 22f * ui.sc, maxW = bw - 90f * ui.sc)
            if (i == chosen) ui.glyph(c, Ic.CHECK, box.right - 34f * ui.sc, rt + rowH / 2f, 30f * ui.sc)
        }
    }

    override fun onAct(a: Act, shell: BelleShell) {
        when (a) {
            Act.UP -> if (sel > 0) sel--
            Act.DOWN -> if (sel < items.size - 1) sel++
            Act.OK -> {
                shell.closeOverlay()
                onPick(sel)
            }
            Act.BACK, Act.OPTIONS -> shell.closeOverlay()
            else -> {}
        }
    }

    override fun onTap(x: Float, y: Float, shell: BelleShell): Boolean {
        if (!box.contains(x, y)) {
            shell.closeOverlay()
            return true
        }
        val row = ((y - box.top - 50f * sc) / (Belle.MENU_ITEM_H * sc)).toInt()
        if (row in 0 until vis) {
            sel = top + row
            onAct(Act.OK, shell)
        }
        return true
    }
}

/** QueryDialog: dark panel, message, equal-width buttons inside (one button if [no] is null). */
class QueryDialog(
    private val message: String,
    private val yes: String = "OK",
    private val no: String? = "Cancel",
    private val onYes: () -> Unit,
) : Overlay() {
    private var sel = 0
    private val box = RectF()
    private val yesR = RectF()
    private val noR = RectF()

    override fun draw(c: Canvas, ui: Ui, w: Float, h: Float, cTop: Float, cBot: Float) {
        val lines = message.split("\n")
        val bw = min(504f * ui.sc, w - 16f * ui.sc)
        val bh = 24f * ui.sc + lines.size * 30f * ui.sc + 20f * ui.sc + 50f * ui.sc + 8f * ui.sc
        val x = (w - bw) / 2f
        val y = cTop + 40f * ui.sc
        box.set(x, y, x + bw, y + bh)
        dialogFrame(c, ui, box, null)
        for ((i, l) in lines.withIndex()) {
            ui.text(c, l, box.left + 16f * ui.sc, ui.baselineFor(box.top + 30f * ui.sc + i * 30f * ui.sc, 20f * ui.sc), 20f * ui.sc, maxW = bw - 32f * ui.sc)
        }
        val by = box.bottom - 8f * ui.sc - 50f * ui.sc
        if (no == null) {
            yesR.set(box.left + 8f * ui.sc, by, box.right - 8f * ui.sc, by + 50f * ui.sc)
        } else {
            val half = (bw - 24f * ui.sc) / 2f
            yesR.set(box.left + 8f * ui.sc, by, box.left + 8f * ui.sc + half, by + 50f * ui.sc)
            noR.set(box.right - 8f * ui.sc - half, by, box.right - 8f * ui.sc, by + 50f * ui.sc)
            ui.pushButton(c, noR, no, sel == 1)
        }
        ui.pushButton(c, yesR, yes, sel == 0)
    }

    override fun onAct(a: Act, shell: BelleShell) {
        when (a) {
            Act.LEFT -> sel = 0
            Act.RIGHT -> if (no != null) sel = 1
            Act.OK -> {
                shell.closeOverlay()
                if (sel == 0) onYes()
            }
            Act.BACK, Act.OPTIONS -> shell.closeOverlay()
            else -> {}
        }
    }

    override fun onTap(x: Float, y: Float, shell: BelleShell): Boolean {
        if (yesR.contains(x, y)) {
            sel = 0
            onAct(Act.OK, shell)
        } else if (no != null && noR.contains(x, y)) {
            sel = 1
            onAct(Act.OK, shell)
        } else if (!box.contains(x, y)) {
            shell.closeOverlay()
        }
        return true
    }
}

class Tumbler(val values: List<String>, var index: Int = 0)

/** Belle tumblers: rotating drums with a full scroll-around, used to set times and dates. */
class TumblerDialog(
    private val title: String,
    private val cols: List<Tumbler>,
    private val onDone: (IntArray) -> Unit,
) : Overlay() {
    private var active = 0
    private val box = RectF()
    private val doneR = RectF()
    private val cancelR = RectF()
    private var sc = 1f
    private var drumTop = 0f
    private var rowH = 0f

    override fun draw(c: Canvas, ui: Ui, w: Float, h: Float, cTop: Float, cBot: Float) {
        sc = ui.sc
        rowH = 46f * ui.sc
        val bw = min(420f * ui.sc, w - 16f * ui.sc)
        val bh = 50f * ui.sc + rowH * 5 + 12f * ui.sc + 50f * ui.sc + 8f * ui.sc
        val x = (w - bw) / 2f
        val y = cTop + max(0f, (cBot - cTop - bh) / 2f)
        box.set(x, y, x + bw, y + bh)
        dialogFrame(c, ui, box, title)
        drumTop = box.top + 50f * ui.sc
        // centre band
        ui.tmp.set(box.left + 6f * ui.sc, drumTop + rowH * 2, box.right - 6f * ui.sc, drumTop + rowH * 3)
        ui.highlight(c, ui.tmp, 8f)
        val cw = (bw - 12f * ui.sc) / cols.size
        c.save()
        c.clipRect(box.left, drumTop, box.right, drumTop + rowH * 5)
        for ((ci, col) in cols.withIndex()) {
            val cx = box.left + 6f * ui.sc + cw * ci + cw / 2f
            for (d in -2..2) {
                val n = col.values.size
                val idx = ((col.index + d) % n + n) % n
                val alpha = when (abs(d)) {
                    0 -> 255
                    1 -> 170
                    else -> 90
                }
                val cy = drumTop + rowH * (d + 2) + rowH / 2f
                ui.text(c, col.values[idx], cx, ui.baselineFor(cy, 22f * ui.sc), 22f * ui.sc, ui.al(Color.WHITE, alpha), Paint.Align.CENTER)
            }
            if (ci > 0) {
                ui.p.color = ui.al(Color.WHITE, 40)
                ui.p.style = Paint.Style.FILL
                c.drawRect(cx - cw / 2f - 0.5f, drumTop, cx - cw / 2f + 0.5f, drumTop + rowH * 5, ui.p)
            }
        }
        c.restore()
        // active column marker
        ui.p.style = Paint.Style.STROKE
        ui.p.strokeWidth = 2.5f * ui.sc
        ui.p.color = Belle.BLUE_LIGHT
        val ax = box.left + 6f * ui.sc + cw * active
        c.drawRect(ax + 2f * ui.sc, drumTop + rowH * 2, ax + cw - 2f * ui.sc, drumTop + rowH * 3, ui.p)
        ui.p.style = Paint.Style.FILL
        val by = box.bottom - 8f * ui.sc - 50f * ui.sc
        val half = (bw - 24f * ui.sc) / 2f
        doneR.set(box.left + 8f * ui.sc, by, box.left + 8f * ui.sc + half, by + 50f * ui.sc)
        cancelR.set(box.right - 8f * ui.sc - half, by, box.right - 8f * ui.sc, by + 50f * ui.sc)
        ui.pushButton(c, doneR, "Done", false)
        ui.pushButton(c, cancelR, "Cancel", false)
    }

    private fun step(col: Tumbler, d: Int) {
        val n = col.values.size
        col.index = ((col.index + d) % n + n) % n
    }

    override fun onAct(a: Act, shell: BelleShell) {
        when (a) {
            Act.UP -> step(cols[active], -1)
            Act.DOWN -> step(cols[active], 1)
            Act.PREV -> step(cols[active], -5)
            Act.NEXT -> step(cols[active], 5)
            Act.LEFT -> if (active > 0) active--
            Act.RIGHT -> if (active < cols.size - 1) active++
            Act.OK -> {
                shell.closeOverlay()
                onDone(IntArray(cols.size) { cols[it].index })
            }
            Act.BACK, Act.OPTIONS -> shell.closeOverlay()
            else -> {}
        }
    }

    override fun onTap(x: Float, y: Float, shell: BelleShell): Boolean {
        if (doneR.contains(x, y)) {
            onAct(Act.OK, shell)
        } else if (cancelR.contains(x, y)) {
            shell.closeOverlay()
        } else if (box.contains(x, y) && y >= drumTop && y < drumTop + rowH * 5) {
            val cw = (box.width() - 12f * sc) / cols.size
            val ci = ((x - box.left - 6f * sc) / cw).toInt().coerceIn(0, cols.size - 1)
            active = ci
            step(cols[ci], ((y - drumTop) / rowH).toInt() - 2)
        } else if (!box.contains(x, y)) {
            shell.closeOverlay()
        }
        return true
    }
}

// ================================================================================================
// the shell view
// ================================================================================================

class BelleShell(val activity: BelleActivity) : View(activity) {
    val ui = Ui(activity)
    val status = StatusInfo(activity) { invalidate() }
    private val prefs = activity.getSharedPreferences("belle_shell", Context.MODE_PRIVATE)
    var swapPad: Boolean
        get() = prefs.getBoolean("swapPad", false)
        set(v) = prefs.edit().putBoolean("swapPad", v).apply()

    private val stack = ArrayList<Screen>()
    private var overlay: Overlay? = null
    private var shownOverlay: Overlay? = null
    private var overlayT = 0f
    private var tabFocus = false
    private var banner = ""
    private var bannerStart = 0L
    private var bannerUntil = 0L
    private var lastNs = System.nanoTime()
    private var passThrough = false

    /** Repeat count of the key currently being handled (0 for the first press), for accelerating cursors. */
    var keyRepeat = 0
    private var contentChanged: (() -> Unit)? = null

    val screen: Screen get() = stack.last()

    // layout (px)
    var contentLeft = 0f; private set
    var contentTop = 0f; private set
    var contentRight = 0f; private set
    var contentBottom = 0f; private set
    private val content = RectF()

    private val gestures = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            tap(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (overlay == null && e.y >= contentTop && e.y <= contentBottom) screen.onLongPress(e.x, e.y)
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (overlay == null && screen.tabs.size > 1 && abs(vx) > abs(vy) && abs(vx) > 700f) {
                switchTab(screen.tab + if (vx < 0) 1 else -1)
                return true
            }
            return false
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (overlay != null) return false
            screen.onScroll(dy)
            invalidate()
            return true
        }
    })

    init {
        isFocusable = true
        if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
    }

    fun onContentChanged(l: () -> Unit) {
        contentChanged = l
    }

    // ---- navigation ---------------------------------------------------------------------------

    private val uiPrefs by lazy { context.getSharedPreferences("belle_ui", Context.MODE_PRIVATE) }

    fun push(s: Screen) {
        s.shell = this
        stack.add(s)
        tabFocus = false
        s.onShow()
        // reopen on the tab used last time
        if (s.tabs.size > 1) {
            val saved = uiPrefs.getInt("tab_" + s.javaClass.name, 0).coerceIn(0, s.tabs.size - 1)
            if (saved != 0) {
                s.tab = saved
                s.onTabChanged()
            }
        }
        invalidate()
    }

    fun pop() {
        if (stack.size <= 1) {
            activity.onRootBack()
            return
        }
        stack.removeAt(stack.size - 1)
        tabFocus = false
        screen.onShow()
        invalidate()
    }

    fun showOverlay(o: Overlay) {
        overlay = o
        shownOverlay = o
        invalidate()
    }

    fun closeOverlay() {
        overlay = null
        invalidate()
    }

    fun toast(text: String) {
        banner = text
        bannerStart = System.currentTimeMillis()
        bannerUntil = bannerStart + 2800
        invalidate()
    }

    private fun switchTab(t: Int) {
        val n = screen.tabs.size
        if (n == 0) return
        val nt = t.coerceIn(0, n - 1)
        if (nt != screen.tab) {
            screen.tab = nt
            screen.onTabChanged()
            if (screen.tabs.size > 1) uiPrefs.edit().putInt("tab_" + screen.javaClass.name, nt).apply()
        }
        invalidate()
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        status.stop()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ui.sc = min(w / 640f, h / 480f)
        computeContent()
    }

    private fun computeContent() {
        val tabs = if (stack.isNotEmpty() && screen.tabs.isNotEmpty()) Belle.TAB_H * ui.sc else 0f
        contentLeft = 0f
        contentTop = Belle.STATUS_H * ui.sc + tabs
        contentRight = width.toFloat()
        contentBottom = height - Belle.TOOL_H * ui.sc
        val changed = content.left != contentLeft || content.top != contentTop ||
            content.right != contentRight || content.bottom != contentBottom
        content.set(contentLeft, contentTop, contentRight, contentBottom)
        if (changed) contentChanged?.invoke()
    }

    // ---- input --------------------------------------------------------------------------------

    private fun toolKinds(): IntArray {
        val tools = screen.tools().take(2)
        val k = IntArray(2 + tools.size)
        k[0] = Ic.BACK
        for ((i, t) in tools.withIndex()) k[1 + i] = t.kind
        k[k.size - 1] = Ic.MENU
        return k
    }

    private fun tap(x: Float, y: Float) {
        overlay?.let {
            it.onTap(x, y, this)
            invalidate()
            return
        }
        val h = height.toFloat()
        if (y >= h - Belle.TOOL_H * ui.sc) {
            val kinds = toolKinds()
            val xs = ui.toolCenters(width.toFloat(), kinds.size)
            var best = 0
            for (i in xs.indices) if (abs(xs[i] - x) < abs(xs[best] - x)) best = i
            when (best) {
                0 -> perform(Act.BACK)
                kinds.size - 1 -> perform(Act.OPTIONS)
                else -> perform(if (best == 1) Act.TOOL1 else Act.TOOL2)
            }
            return
        }
        val tabs = screen.tabs
        val tabTop = Belle.STATUS_H * ui.sc
        if (tabs.isNotEmpty() && y >= tabTop && y < tabTop + Belle.TAB_H * ui.sc) {
            Sounds.play(Sounds.TICK)
            switchTab((x / (width.toFloat() / tabs.size)).toInt())
            return
        }
        if (y >= contentTop && screen.onTap(x, y)) {
            Sounds.play(Sounds.SELECT)
            invalidate()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            passThrough = overlay == null && stack.isNotEmpty() && screen.transparentContent &&
                e.y >= contentTop && e.y <= contentBottom
        }
        if (passThrough) return false
        return gestures.onTouchEvent(e) || super.onTouchEvent(e)
    }

    /** @return true if the event was consumed; false lets it reach views underneath (e.g. a WebView). */
    fun handleKey(e: KeyEvent): Boolean {
        val a = Keys.actionFor(e.keyCode, swapPad) ?: return false
        keyRepeat = e.repeatCount
        Keys.repeat = e.repeatCount
        if (e.action != KeyEvent.ACTION_DOWN) {
            // consume the matching key-up of anything we handle, except when passing through
            return !(overlay == null && screen.transparentContent && isNavKey(a))
        }
        val directional = a == Act.UP || a == Act.DOWN || a == Act.LEFT || a == Act.RIGHT
        if (e.repeatCount > 0 && !directional && a != Act.PREV && a != Act.NEXT) return true
        return perform(a)
    }

    private fun isNavKey(a: Act) = a == Act.UP || a == Act.DOWN || a == Act.LEFT || a == Act.RIGHT || a == Act.OK

    private fun soundFor(a: Act): Int = when (a) {
        Act.OK, Act.TOOL1, Act.TOOL2 -> Sounds.SELECT
        Act.BACK -> Sounds.BACK
        Act.OPTIONS -> Sounds.OPEN
        else -> Sounds.TICK
    }

    fun perform(a: Act): Boolean {
        val r = performInner(a)
        if (r) Sounds.play(soundFor(a))
        return r
    }

    private fun performInner(a: Act): Boolean {
        if (a == Act.OK || a == Act.OPTIONS) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        overlay?.let {
            it.onAct(a, this)
            invalidate()
            return true
        }
        val s = screen
        val handled: Boolean = when (a) {
            Act.OPTIONS -> {
                val items = ArrayList<MenuItem>(s.menu())
                items.add(MenuItem(if (swapPad) "Buttons: Nintendo - switch to Xbox" else "Buttons: Xbox - switch to Nintendo") { swapPad = !swapPad })
                showOverlay(MenuOverlay(items))
                true
            }
            Act.TOOL1 -> {
                s.tools().getOrNull(0)?.let { if (it.enabled) it.onClick() }
                true
            }
            Act.TOOL2 -> {
                s.tools().getOrNull(1)?.let { if (it.enabled) it.onClick() }
                true
            }
            Act.BACK -> {
                if (tabFocus) tabFocus = false else if (!s.onAct(Act.BACK)) pop()
                true
            }
            else -> {
                if (tabFocus && s.tabs.isNotEmpty() && a != Act.PREV && a != Act.NEXT) {
                    when (a) {
                        Act.LEFT -> switchTab(s.tab - 1)
                        Act.RIGHT -> switchTab(s.tab + 1)
                        Act.DOWN, Act.OK -> tabFocus = false
                        else -> {}
                    }
                    true
                } else if ((a == Act.PREV || a == Act.NEXT) && s.tabs.size > 1) {
                    switchTab(s.tab + if (a == Act.NEXT) 1 else -1)
                    true
                } else if (s.onAct(a)) {
                    true
                } else if (a == Act.UP && s.tabs.isNotEmpty()) {
                    tabFocus = true
                    true
                } else {
                    false
                }
            }
        }
        invalidate()
        return handled || !s.transparentContent
    }

    // ---- drawing ------------------------------------------------------------------------------

    override fun onDraw(c: Canvas) {
        if (stack.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val now = System.nanoTime()
        var dt = (now - lastNs) / 1e9f
        lastNs = now
        if (dt > 0.05f || dt <= 0f) dt = 0.016f
        ui.k = 1f - exp(-dt * 18f)
        ui.busy = false
        computeContent()
        val s = screen
        val tabsH = if (s.tabs.isNotEmpty()) Belle.TAB_H * ui.sc else 0f
        val statusH = Belle.STATUS_H * ui.sc

        if (!s.transparentContent) {
            ui.rf.set(0f, statusH, w, contentBottom)
            ui.fill(c, ui.rf, Color.BLACK)
        }
        c.save()
        c.clipRect(content)
        s.draw(c, ui, content)
        c.restore()
        c.save()
        c.clipRect(content)
        s.drawOver(c, ui, content)
        c.restore()
        if (s.tabs.isNotEmpty()) {
            ui.tabBar(c, w, statusH, s.tabs.map { it.label }, IntArray(s.tabs.size) { s.tabs[it].kind }, s.tab, tabFocus)
        }
        ui.statusBar(c, w, s.title, status)

        val tools = s.tools().take(2)
        val kinds = toolKinds()
        val enabled = BooleanArray(kinds.size) { i -> if (i == 0 || i == kinds.size - 1) true else tools[i - 1].enabled }
        val letters = arrayOfNulls<String>(kinds.size)
        letters[0] = if (swapPad) "A" else "B"
        for (i in tools.indices) letters[1 + i] = if (i == 0) (if (swapPad) "X" else "Y") else "St"
        letters[kinds.size - 1] = if (swapPad) "Y" else "X"
        ui.toolbar(c, w, h - Belle.TOOL_H * ui.sc, kinds, enabled, letters)

        // overlay animation
        val target = if (overlay != null) 1f else 0f
        overlayT += (target - overlayT) * ui.k
        if (abs(target - overlayT) < 0.01f) overlayT = target else ui.busy = true
        if (overlayT <= 0f && overlay == null) shownOverlay = null
        shownOverlay?.let { o ->
            val box = h * 0.9f
            c.save()
            c.translate(0f, (1f - overlayT) * (if (o.slideUp) box else 28f * ui.sc))
            o.draw(c, ui, w, h, statusH, h - Belle.TOOL_H * ui.sc)
            c.restore()
        }

        val nowMs = System.currentTimeMillis()
        if (nowMs < bannerUntil) {
            val tin = ((nowMs - bannerStart) / 220f).coerceIn(0f, 1f)
            val tout = ((bannerUntil - nowMs) / 220f).coerceIn(0f, 1f)
            ui.banner(c, w, banner, min(tin, tout))
            ui.busy = true
        }
        if (ui.busy) postInvalidateOnAnimation() else if (s.tickMs > 0) postInvalidateDelayed(s.tickMs)
    }
}

// ================================================================================================
// activity base
// ================================================================================================

abstract class BelleActivity : Activity() {
    lateinit var shell: BelleShell
    private var permCb: ((Boolean) -> Unit)? = null

    /** First screen of the app. */
    abstract fun createRoot(shell: BelleShell): Screen

    /** Content view; override to add views underneath the shell (the shell is drawn on top). */
    open fun buildContent(shell: BelleShell): View = shell

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        ThemeStore.load(this)
        try { Sounds.init(this) } catch (_: Throwable) { }
        shell = BelleShell(this)
        setContentView(buildContent(shell), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        hideSystemBars()
        shell.push(createRoot(shell))
        // the last time this app crashed, show what happened
        CrashLog.pending(this)?.let { shell.push(CrashScreen(it)) }
    }

    override fun onResume() {
        super.onResume()
        // pick up a theme colour or sound setting changed in Styles
        ThemeStore.refresh(this) { if (::shell.isInitialized) shell.invalidate() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Return false while a real text field has focus so keys reach it instead of the gamepad shell. */
    protected open fun shellHandlesKeys(): Boolean = true

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (shellHandlesKeys() && shell.handleKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("handled through the shell")
    override fun onBackPressed() {
        shell.perform(Act.BACK)
    }

    open fun onRootBack() {
        finish()
    }

    fun requestPerms(perms: Array<String>, cb: (Boolean) -> Unit) {
        if (perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            cb(true)
        } else {
            permCb = cb
            requestPermissions(perms, 42)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42) permCb?.invoke(grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }
}
