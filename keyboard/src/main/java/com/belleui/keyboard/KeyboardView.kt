package com.belleui.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/** Two rows of keys in the Belle style. The selected key is blue; the game pad moves it, A presses it. */
class KeyboardView(ctx: Context) : View(ctx) {
    var rows: List<List<Key>> = Layouts.rows(0, false)
        private set
    var selRow = 0
    var selCol = 0
    var shift = 0 // 0 off, 1 next letter, 2 caps lock
    var page = 0
    var listener: ((Key) -> Unit)? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL); textAlign = Paint.Align.CENTER }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); textAlign = Paint.Align.CENTER; color = 0xFF8A8A8A.toInt() }
    private val rect = RectF()
    private val path = Path()
    private var sc = 1f
    private var pressedUntil = 0L
    private val keyRects = ArrayList<Pair<RectF, Pair<Int, Int>>>()

    fun setLayout(page: Int, qwerty: Boolean) {
        this.page = page
        rows = Layouts.rows(page, qwerty)
        selRow = selRow.coerceIn(0, 1)
        selCol = selCol.coerceIn(0, rows[selRow].size - 1)
        invalidate()
    }

    fun selected(): Key = rows[selRow][selCol]

    /** Moves the highlight; up and down keep the same spot along the row. */
    fun move(dx: Int, dy: Int) {
        if (dy != 0) {
            val here = centreOf(selRow, selCol)
            selRow = (selRow + dy).coerceIn(0, 1)
            selCol = nearest(selRow, here)
        } else if (dx != 0) {
            val n = rows[selRow].size
            selCol = (selCol + dx + n) % n
        }
        invalidate()
    }

    private fun centreOf(row: Int, col: Int): Float {
        var x = 0f
        for (i in 0 until col) x += rows[row][i].w
        return x + rows[row][col].w / 2f
    }

    private fun nearest(row: Int, x: Float): Int {
        var best = 0
        var bd = Float.MAX_VALUE
        for (i in rows[row].indices) {
            val d = kotlin.math.abs(centreOf(row, i) - x)
            if (d < bd) {
                bd = d
                best = i
            }
        }
        return best
    }

    fun flash() {
        pressedUntil = System.currentTimeMillis() + 90
        invalidate()
        postInvalidateDelayed(110)
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        sc = width / 640f
        setMeasuredDimension(width, (160f * sc).toInt())
    }

    private fun label(k: Key): String {
        if (k.kind != K_CHAR) return k.label
        return if (page == 0 && shift > 0) k.label.uppercase() else k.label
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawColor(0xFF1B1B1B.toInt())
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = 0xFF3A3A3A.toInt()
        c.drawRect(0f, 0f, w, 1.5f * sc, p)
        // legend
        hint.textSize = 13f * sc
        c.drawText("A press   B delete   X space   Y shift   L1 R1 cursor   R2 enter   L2 123", w / 2f, 19f * sc, hint)

        keyRects.clear()
        val top = 28f * sc
        val rowH = (h - top - 6f * sc) / 2f
        val unit = (w - 12f * sc) / 18f
        for ((ri, row) in rows.withIndex()) {
            var x = 6f * sc
            for ((ci, k) in row.withIndex()) {
                rect.set(x + 1.5f * sc, top + ri * rowH + 1.5f * sc, x + k.w * unit - 1.5f * sc, top + (ri + 1) * rowH - 1.5f * sc)
                keyRects.add(RectF(rect) to (ri to ci))
                val sel = ri == selRow && ci == selCol
                val down = sel && System.currentTimeMillis() < pressedUntil
                drawKey(c, rect, k, sel, down)
                x += k.w * unit
            }
        }
    }

    private fun drawKey(c: Canvas, r: RectF, k: Key, sel: Boolean, down: Boolean) {
        val special = k.kind != K_CHAR
        val topCol: Int
        val botCol: Int
        when {
            down -> { topCol = 0xFF1080DD.toInt(); botCol = 0xFF0A5BA3.toInt() }
            sel -> { topCol = 0xFF5CB4F8.toInt(); botCol = 0xFF1080DD.toInt() }
            special -> { topCol = 0xFF4A5560.toInt(); botCol = 0xFF2E363E.toInt() }
            else -> { topCol = 0xFF4A4A4A.toInt(); botCol = 0xFF2C2C2C.toInt() }
        }
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, topCol, botCol, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, 7f * sc, 7f * sc, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * sc
        p.color = if (sel) 0xFFB5DEFF.toInt() else 0xFF5A5A5A.toInt()
        c.drawRoundRect(r, 7f * sc, 7f * sc, p)
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        val cx = r.centerX()
        val cy = r.centerY()
        val s = r.height() * 0.3f
        when (k.kind) {
            K_CHAR, K_PAGE -> {
                text.color = Color.WHITE
                text.textSize = (if (k.kind == K_PAGE) 17f else 25f) * sc
                c.drawText(label(k), cx, cy - (text.ascent() + text.descent()) / 2f, text)
            }
            K_SHIFT -> {
                path.reset()
                path.moveTo(cx, cy - s)
                path.lineTo(cx + s, cy)
                path.lineTo(cx + s * 0.4f, cy)
                path.lineTo(cx + s * 0.4f, cy + s)
                path.lineTo(cx - s * 0.4f, cy + s)
                path.lineTo(cx - s * 0.4f, cy)
                path.lineTo(cx - s, cy)
                path.close()
                if (shift == 0) {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 2f * sc
                }
                c.drawPath(path, p)
                if (shift == 2) c.drawRect(cx - s, cy + s + 3f * sc, cx + s, cy + s + 6f * sc, p)
            }
            K_BACKSPACE -> {
                path.reset()
                path.moveTo(cx - s * 1.5f, cy)
                path.lineTo(cx - s * 0.5f, cy - s)
                path.lineTo(cx + s * 1.5f, cy - s)
                path.lineTo(cx + s * 1.5f, cy + s)
                path.lineTo(cx - s * 0.5f, cy + s)
                path.close()
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2f * sc
                c.drawPath(path, p)
                c.drawLine(cx, cy - s * 0.4f, cx + s * 0.8f, cy + s * 0.4f, p)
                c.drawLine(cx + s * 0.8f, cy - s * 0.4f, cx, cy + s * 0.4f, p)
            }
            K_ENTER -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.4f * sc
                path.reset()
                path.moveTo(cx + s, cy - s)
                path.lineTo(cx + s, cy + s * 0.2f)
                path.lineTo(cx - s, cy + s * 0.2f)
                c.drawPath(path, p)
                path.reset()
                path.moveTo(cx - s * 0.2f, cy - s * 0.5f)
                path.lineTo(cx - s, cy + s * 0.2f)
                path.lineTo(cx - s * 0.2f, cy + s * 0.9f)
                c.drawPath(path, p)
            }
            K_SPACE -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.4f * sc
                path.reset()
                path.moveTo(cx - s * 1.6f, cy - s * 0.2f)
                path.lineTo(cx - s * 1.6f, cy + s * 0.5f)
                path.lineTo(cx + s * 1.6f, cy + s * 0.5f)
                path.lineTo(cx + s * 1.6f, cy - s * 0.2f)
                c.drawPath(path, p)
            }
        }
        p.style = Paint.Style.FILL
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
        for ((r, rc) in keyRects) if (r.contains(e.x, e.y)) {
            selRow = rc.first
            selCol = rc.second
            flash()
            listener?.invoke(rows[rc.first][rc.second])
            return true
        }
        return true
    }
}
