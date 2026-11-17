package com.belleui.settings

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import com.belleui.kit.Act
import com.belleui.kit.Belle
import com.belleui.kit.ListState
import com.belleui.kit.Scroll
import com.belleui.kit.Row
import com.belleui.kit.Screen
import com.belleui.kit.Ui

/**
 * A list of rows with optional value bars. Left / right adjust a row that has a bar, A or right opens
 * a row that does not.
 *
 * Rows (and bar values) are rebuilt at most once per [cacheMs], not on every frame: building them
 * asks Android for app lists, drive sizes and battery state, which is what made this app laggy.
 */
abstract class RowsScreen(protected val act: SettingsActivity) : Screen() {
    override val lightOk: Boolean get() = true
    protected val list = ListState()
    private val rowRect = RectF()

    private var rowsCache: List<Row> = emptyList()
    private var barsCache: List<Float?> = emptyList()
    private var drawCache: List<Row> = emptyList()
    private var cachedAt = -1_000_000L

    open val cacheMs: Long get() = 1000L

    abstract fun rows(): List<Row>
    open fun bar(i: Int): Float? = null
    open fun open(i: Int) {}
    open fun adjust(i: Int, dir: Int) {}

    /** True for a row whose bar is a slider you can pull (brightness, volume); the other bars only show a value. */
    open fun adjustable(i: Int): Boolean = false

    /** Sets a slider row to [frac] (0..1) when its handle is pulled. */
    open fun setBar(i: Int, frac: Float) {}

    protected fun refresh(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - cachedAt < cacheMs) return
        cachedAt = now
        rowsCache = rows()
        barsCache = rowsCache.indices.map { bar(it) }
        // a bar row is drawn with a blank title; the title, value and a full-size bar are laid over it
        drawCache = rowsCache.mapIndexed { i, r -> if (barsCache[i] != null) Row("", icon = r.icon, iconKind = r.iconKind, iconColor = r.iconColor, dim = r.dim) else r }
    }

    private fun area() = RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom)

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        refresh()
        list.draw(c, ui, r, drawCache.size) { drawCache[it] }
        for (i in rowsCache.indices) {
            val f = barsCache[i] ?: continue
            val row = rowsCache[i]
            val rr = list.rowRect(ui, r, i, rowRect)
            if (rr.bottom < r.top || rr.top > r.bottom) continue
            val sc = ui.sc
            val pad = 20f * sc
            val tx = rr.left + pad + (if (row.icon != null || row.iconKind >= 0) 84f * sc else 0f)
            val white = if (row.dim) ui.al(Color.WHITE, 120) else Color.WHITE
            val value = row.right ?: row.subtitle
            ui.text(c, row.title, tx, rr.top + 40f * sc, 26f * sc, white, maxW = rr.right - tx - pad - 170f * sc)
            if (value != null) ui.text(c, value, rr.right - pad - 8f * sc, rr.top + 39f * sc, 21f * sc, if (i == list.sel) Color.WHITE else Belle.TEXT_MID, Paint.Align.RIGHT, face = ui.light)
            // the Belle progress bar is 20px tall; drawn at that size it keeps its rounded ends
            if (adjustable(i)) {
                ui.tmp.set(tx, rr.top + 56f * sc, rr.right - pad - 8f * sc, rr.top + 88f * sc)
                ui.slider(c, ui.tmp, f, dragBar == i)
            } else {
                ui.tmp.set(tx, rr.top + 58f * sc, rr.right - pad - 8f * sc, rr.top + 86f * sc)
                ui.progressBar(c, ui.tmp, f)
            }
        }
    }

    override fun onAct(a: Act): Boolean {
        refresh()
        val n = rowsCache.size
        if (n == 0) return false
        val i = list.sel.coerceIn(0, n - 1)
        val hasBar = barsCache[i] != null
        when (a) {
            Act.OK -> {
                open(i)
                refresh(true)
                return true
            }
            Act.RIGHT -> {
                if (hasBar) adjust(i, 1) else open(i)
                refresh(true)
                return true
            }
            Act.LEFT -> if (hasBar) {
                adjust(i, -1)
                refresh(true)
                return true
            } else return false
            else -> {}
        }
        return list.act(a, n, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }

    // ---- touch: pulling a slider's handle, scrolling, and tapping --------------------------------------
    private var dragBar = -1
    private var downY = 0f
    private var lastY = 0f
    private var moved = false
    private var scrollAcc = 0f
    private var tapX = 0f

    override val rawTouch: Boolean get() = true

    private fun barSpan(i: Int, r: RectF): Pair<Float, Float> {
        val rr = list.rowRect(shell.ui, r, i, rowRect)
        val sc = shell.ui.sc
        val row = rowsCache[i]
        val tx = rr.left + 20f * sc + (if (row.icon != null || row.iconKind >= 0) 84f * sc else 0f)
        val hd = 42f * sc
        return (tx + hd / 2f) to (rr.right - 28f * sc - hd / 2f)
    }

    private fun pull(i: Int, x: Float) {
        val (a, b) = barSpan(i, area())
        setBar(i, ((x - a) / (b - a)).coerceIn(0f, 1f))
        refresh(true)
        shell.invalidate()
    }

    private var vt: android.view.VelocityTracker? = null

    override fun onTouch(e: android.view.MotionEvent): Boolean {
        vt?.addMovement(e)
        refresh()
        val r = area()
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downY = e.y; lastY = e.y; tapX = e.x; moved = false; scrollAcc = 0f; dragBar = -1
                vt?.recycle(); vt = android.view.VelocityTracker.obtain()
                val i = list.hit(shell.ui, r, e.y, rowsCache.size)
                if (i >= 0 && barsCache.getOrNull(i) != null && adjustable(i)) {
                    val rr = list.rowRect(shell.ui, r, i, rowRect)
                    // the lower half of the row, where the slider is, so the title stays a normal tap target
                    if (e.y > rr.top + 40f * shell.ui.sc) {
                        dragBar = i
                        list.sel = i
                        pull(i, e.x)
                    }
                }
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (dragBar >= 0) {
                    pull(dragBar, e.x)
                } else {
                    if (Math.abs(downY - e.y) > 12f * shell.ui.sc) moved = true
                    if (moved) {
                        // follow the finger in pixels (the shared list keeps the momentum)
                        Scroll.dy = lastY - e.y
                        Scroll.last = null
                        try { list.scrollBy(0, rowsCache.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop)) } finally { Scroll.dy = 0f }
                        shell.invalidate()
                    }
                    lastY = e.y
                }
            }
            android.view.MotionEvent.ACTION_UP -> {
                val was = dragBar
                dragBar = -1
                vt?.computeCurrentVelocity(1000)
                val vy = vt?.yVelocity ?: 0f
                vt?.recycle(); vt = null
                if (was < 0 && !moved) onTap(tapX, e.y) else {
                    if (was < 0 && Math.abs(vy) > 300f * shell.ui.sc) list.fling(vy)
                    shell.invalidate()
                }
            }
            android.view.MotionEvent.ACTION_CANCEL -> { dragBar = -1; shell.invalidate() }
        }
        return true
    }

    override fun onTap(x: Float, y: Float): Boolean {
        refresh()
        val r = area()
        val i = list.hit(shell.ui, r, y, rowsCache.size)
        if (i < 0) return false
        list.sel = i
        if (barsCache[i] != null) adjust(i, if (x > r.centerX()) 1 else -1) else open(i)
        refresh(true)
        return true
    }

    override fun onScroll(dy: Float) {
        list.scrollBy(if (dy > 0) 1 else -1, rowsCache.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }
}
