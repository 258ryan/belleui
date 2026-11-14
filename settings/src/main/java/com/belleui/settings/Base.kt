package com.belleui.settings

import android.graphics.Canvas
import android.graphics.RectF
import com.belleui.kit.Act
import com.belleui.kit.ListState
import com.belleui.kit.Row
import com.belleui.kit.Screen
import com.belleui.kit.Ui

/**
 * A list of rows with optional value bars. Left / right adjust a row that has a bar, A or right opens
 * a row that does not.
 */
abstract class RowsScreen(protected val act: SettingsActivity) : Screen() {
    protected val list = ListState()
    private val rowRect = RectF()

    abstract fun rows(): List<Row>
    open fun bar(i: Int): Float? = null
    open fun open(i: Int) {}
    open fun adjust(i: Int, dir: Int) {}

    private fun area() = RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom)

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        val rows = rows()
        list.draw(c, ui, r, rows.size) { rows[it] }
        for (i in rows.indices) {
            val f = bar(i) ?: continue
            val rr = list.rowRect(ui, r, i, rowRect)
            if (rr.bottom < r.top || rr.top > r.bottom) continue
            val left = rr.left + 12f * ui.sc + (if (rows[i].icon != null || rows[i].iconKind >= 0) 62f * ui.sc else 0f)
            ui.tmp.set(left, rr.bottom - 24f * ui.sc, rr.right - 24f * ui.sc, rr.bottom - 12f * ui.sc)
            ui.progressBar(c, ui.tmp, f)
        }
    }

    override fun onAct(a: Act): Boolean {
        val n = rows().size
        if (n == 0) return false
        val i = list.sel.coerceIn(0, n - 1)
        val hasBar = bar(i) != null
        when (a) {
            Act.OK -> {
                open(i)
                return true
            }
            Act.RIGHT -> {
                if (hasBar) adjust(i, 1) else open(i)
                return true
            }
            Act.LEFT -> if (hasBar) {
                adjust(i, -1)
                return true
            } else return false
            else -> {}
        }
        return list.act(a, n, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val r = area()
        val i = list.hit(shell.ui, r, y, rows().size)
        if (i < 0) return false
        list.sel = i
        if (bar(i) != null) adjust(i, if (x > r.centerX()) 1 else -1) else open(i)
        return true
    }

    override fun onScroll(dy: Float) = list.scrollBy(if (dy > 0) 1 else -1, rows().size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
}
