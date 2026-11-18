package com.belleui.kit

import android.graphics.Canvas
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * One block of a [FlowPage]: a header, a banner, a row. The Nokia Music and Nokia Store clients are single scrolling pages made of such blocks
 * (a title, a promo strip, a button row, then the list), not a title bar over a list, so they share this.
 */
abstract class Cell {
    /** True when the focus can rest on it (a row, a banner); headers are not. */
    open val focusable: Boolean get() = true

    abstract fun height(ui: Ui, w: Float): Float

    /** Draws the cell into [r] (its full width and height); [focused] when the key focus is on it, [pressed] while a finger is down on it. */
    abstract fun draw(c: Canvas, ui: Ui, r: RectF, focused: Boolean, pressed: Boolean)

    open fun onClick() {}

    /** A key the page itself does not use (LEFT / RIGHT mostly); true when the cell took it. */
    open fun onKey(a: Act): Boolean = false

    /** A tap at (x, y) inside [r], before it counts as a click; true when the cell handled it itself (a button inside the cell). */
    open fun onTapAt(x: Float, y: Float, r: RectF): Boolean = false
}

/**
 * A vertically scrolling page of [Cell]s with key focus, pixel-following touch scroll and a coasting fling.
 * The selection / pressed look is up to the cells, so the page can be dressed as Nokia Music or Nokia Store.
 */
class FlowPage : Flingable {
    var cells: List<Cell> = emptyList()
        set(v) {
            field = v
            if (sel !in v.indices || !v[sel].focusable) sel = v.indexOfFirst { it.focusable }
            tops = FloatArray(0)
        }

    /** The focused cell, or -1. */
    var sel = -1
    var scroll = 0f
    private var vel = 0f
    private var lastNs = 0L
    private var tops = FloatArray(0)
    private var total = 0f
    private var viewH = 0f
    private var pressed = -1
    private var layoutW = -1f
    private var keyFocus = true
    private val cellRect = RectF()

    private fun layout(ui: Ui, w: Float) {
        if (tops.size == cells.size + 1 && layoutW == w) return
        tops = FloatArray(cells.size + 1)
        var y = 0f
        for (i in cells.indices) {
            tops[i] = y
            y += cells[i].height(ui, w)
        }
        tops[cells.size] = y
        total = y
        layoutW = w
    }

    /** Forget the measured heights (the cells changed size, e.g. after a rotation). */
    fun invalidateLayout() {
        tops = FloatArray(0)
    }

    private fun clamp(v: Float) = v.coerceIn(0f, max(0f, total - viewH))

    fun draw(c: Canvas, ui: Ui, r: RectF) {
        layoutW = if (layoutW != r.width()) -1f else layoutW
        layout(ui, r.width())
        viewH = r.height()
        // coasting after a fling
        if (abs(vel) > 8f) {
            val now = System.nanoTime()
            val dt = ((now - lastNs) / 1e9f).coerceIn(0f, 0.05f)
            lastNs = now
            val before = scroll
            scroll = clamp(scroll + vel * dt)
            if (scroll == before) vel = 0f
            vel *= exp(-3.2 * dt).toFloat()
            ui.busy = true
        } else vel = 0f
        scroll = clamp(scroll)
        c.save()
        c.clipRect(r)
        for (i in cells.indices) {
            val top = r.top + tops[i] - scroll
            val bot = r.top + tops[i + 1] - scroll
            if (bot < r.top || top > r.bottom) continue
            cellRect.set(r.left, top, r.right, bot)
            cells[i].draw(c, ui, RectF(cellRect), keyFocus && i == sel, i == pressed)
        }
        c.restore()
        if (total > r.height()) ui.scrollbar(c, r.right, r.top, r.bottom, scroll / (total - r.height()), r.height() / total)
    }

    private fun ensureVisible(i: Int) {
        if (i !in cells.indices || tops.size != cells.size + 1) return
        val top = tops[i]
        val bot = tops[i + 1]
        if (top < scroll) scroll = top
        else if (bot > scroll + viewH) scroll = bot - viewH
        scroll = clamp(scroll)
        // the first focusable cell shows everything above it too (the title)
        if (i == cells.indexOfFirst { it.focusable }) scroll = 0f
    }

    /** UP / DOWN move the focus from cell to cell; OK clicks; anything else goes to the focused cell. True when used. */
    fun act(a: Act): Boolean {
        keyFocus = true
        vel = 0f
        when (a) {
            Act.UP, Act.DOWN -> {
                if (cells.isEmpty()) return false
                var i = sel
                do {
                    i += if (a == Act.DOWN) 1 else -1
                } while (i in cells.indices && !cells[i].focusable)
                if (i in cells.indices) {
                    sel = i
                    ensureVisible(i)
                    return true
                }
                // nothing left to focus that way: scroll the page, so text and rows that cannot take the focus (a description, details) can still be read
                val before = scroll
                scroll = clamp(scroll + (if (a == Act.DOWN) 1f else -1f) * viewH * 0.6f)
                return scroll != before
            }
            Act.OK -> {
                cells.getOrNull(sel)?.onClick()
                return true
            }
            else -> return cells.getOrNull(sel)?.onKey(a) ?: false
        }
    }

    /** A finger drag of [dy] pixels (positive = content moves up). */
    fun drag(dy: Float) {
        keyFocus = false
        vel = 0f
        scroll = clamp(scroll + dy)
        Scroll.last = this
    }

    override fun fling(vy: Float) {
        vel = -vy
        lastNs = System.nanoTime()
    }

    /** The cell under (x, y) of the page drawn into [r], or -1. */
    private fun hit(r: RectF, y: Float): Int {
        if (tops.size != cells.size + 1) return -1
        val rel = y - r.top + scroll
        for (i in cells.indices) if (rel >= tops[i] && rel < tops[i + 1]) return i
        return -1
    }

    /** A tap: a cell's own buttons first, then the cell as a whole. True when something was hit. */
    fun tap(r: RectF, x: Float, y: Float): Boolean {
        val i = hit(r, y)
        if (i < 0) return false
        val cr = RectF(r.left, r.top + tops[i] - scroll, r.right, r.top + tops[i + 1] - scroll)
        val cell = cells[i]
        if (cell.onTapAt(x, y, cr)) return true
        if (!cell.focusable) return false
        sel = i
        keyFocus = false
        cell.onClick()
        return true
    }

    /** True when the cell at [y] is a focusable one (so a touch-down can show its pressed look). */
    fun press(r: RectF, y: Float) {
        val i = hit(r, y)
        pressed = if (i >= 0 && cells[i].focusable) i else -1
    }

    fun release() {
        pressed = -1
    }

    fun visibleBounds(): Pair<Float, Float> = scroll to (scroll + viewH)

    fun clampedTop(i: Int): Float = if (i in 0..cells.size && tops.size == cells.size + 1) tops[i] else 0f
}
