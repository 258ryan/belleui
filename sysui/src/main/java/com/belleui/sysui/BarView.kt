package com.belleui.sysui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import com.belleui.kit.Ui
import com.belleui.vector.Hud

/** The Belle status bar: wifi and the notification count on the left; clock and battery on the right. */
class BarView(context: Context, private val barH: Int) : View(context) {
    private val ui = Ui(context).also { it.sc = barH / 28f }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var downY = 0f
    private var moved = false

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), barH)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = barH.toFloat()
        r.set(0f, 0f, w, h)
        if (!ui.gfx.draw(c, "qtg_fr_statusbar", r)) {
            p.shader = LinearGradient(0f, 0f, 0f, h, 0xFF2A2A2A.toInt(), 0xFF050505.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(r, p)
            p.shader = null
        }
        val size = h * 0.56f
        val pad = h * 0.45f

        // right: battery meter, then the clock
        var x = w - pad
        val bw = h * 1.15f
        val bh = h * 0.62f
        val bt = (h - bh) / 2f
        r.set(x - bw, bt, x, bt + bh)
        val bg = ui.gfx.get("qtg_graf_battery_level_bg")
        val full = ui.gfx.get("qtg_graf_battery_level_full")
        if (bg != null) c.drawBitmap(bg, null, r, null)
        if (full != null) {
            c.save()
            c.clipRect(r.left, r.top, r.left + bw * (0.12f + 0.76f * Bar.batteryPct / 100f), r.bottom)
            c.drawBitmap(full, null, r, null)
            c.restore()
        }
        x -= bw + h * 0.25f
        val pct = Bar.batteryPct.toString() + "%" + if (Bar.charging) "+" else ""
        ui.text(c, pct, x, ui.baselineFor(h / 2f, size * 0.85f), size * 0.85f, 0xFFD8D8D8.toInt(), Paint.Align.RIGHT)
        x -= ui.measure(pct, size * 0.85f) + pad
        val clock = DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", System.currentTimeMillis()).toString()
        ui.text(c, clock, x, ui.baselineFor(h / 2f, size), size, Color.WHITE, Paint.Align.RIGHT)

        // left: wifi, notifications
        var lx = pad
        if (Bar.wifi) {
            val s = h * 0.78f
            r.set(lx, (h - s) / 2f, lx + s, (h + s) / 2f)
            ui.gfx.draw(c, "qtg_toolbar_wlan_on", r)
            lx += s + pad * 0.6f
        }
        val n = Bar.notifications
        if (n > 0) {
            val label = if (n > 99) "99+" else n.toString()
            val tw = ui.measure(label, size * 0.8f)
            val d = h * 0.62f
            r.set(lx, (h - d) / 2f, lx + maxOf(d, tw + h * 0.34f), (h + d) / 2f)
            p.style = Paint.Style.FILL
            p.color = 0xFF2F7BD8.toInt()
            c.drawRoundRect(r, d / 2f, d / 2f, p)
            ui.text(c, label, r.centerX(), ui.baselineFor(r.centerY(), size * 0.8f), size * 0.8f, Color.WHITE, Paint.Align.CENTER)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y
                moved = false
            }
            MotionEvent.ACTION_MOVE -> if (!moved && e.y - downY > barH * 0.6f) {
                moved = true
                if (!Hud.statusOpen) Hud.toggleStatus(context, context)
            }
            MotionEvent.ACTION_UP -> if (!moved) Hud.toggleStatus(context, context)
        }
        return true
    }
}
