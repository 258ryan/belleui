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
        val size = h * 0.62f
        val pad = h * 0.4f

        // left: the title of what is on screen, as on every Belle screen
        ui.text(c, Bar.title, pad, ui.baselineFor(h / 2f, size), size, Color.WHITE, maxW = w * 0.45f)

        // right, from the edge in: clock, battery, notification mark, wifi
        var x = w - pad
        val clock = DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", System.currentTimeMillis()).toString()
        ui.text(c, clock, x, ui.baselineFor(h / 2f, size), size, Color.WHITE, Paint.Align.RIGHT)
        x -= ui.measure(clock, size) + pad * 0.6f
        val bw = h * 1.15f
        val bh = h * 0.6f
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
        if (Bar.charging) {
            // a bolt over the battery while it charges
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            val bx = r.centerX()
            val by = r.centerY()
            val bolt = android.graphics.Path()
            bolt.moveTo(bx + bw * 0.06f, by - bh * 0.42f)
            bolt.lineTo(bx - bw * 0.14f, by + bh * 0.08f)
            bolt.lineTo(bx - bw * 0.01f, by + bh * 0.08f)
            bolt.lineTo(bx - bw * 0.06f, by + bh * 0.42f)
            bolt.lineTo(bx + bw * 0.16f, by - bh * 0.1f)
            bolt.lineTo(bx + bw * 0.03f, by - bh * 0.1f)
            bolt.close()
            c.drawPath(bolt, p)
        }
        x -= bw + pad * 0.6f
        // the small indicators, from the battery outwards: wifi, bluetooth, silent, alarm, airplane
        fun glyph(name: String) {
            val s = h * 0.8f
            r.set(x - s, (h - s) / 2f, x, (h + s) / 2f)
            ui.gfx.draw(c, name, r)
            x -= s + pad * 0.3f
        }
        if (Bar.wifi) glyph("qtg_toolbar_wlan_on")
        if (Bar.bluetooth) glyph("qtg_toolbar_bluetooth_on")
        if (Bar.silent) glyph("qtg_toolbar_audio_off")
        if (Bar.alarm) { ui.glyph(c, com.belleui.kit.Ic.BELL, x - h * 0.4f, h / 2f, h * 0.62f); x -= h * 0.8f + pad * 0.3f }
        if (Bar.airplane) glyph("qtg_toolbar_network_off")
        if (Bar.notifications > 0) {
            // an envelope, with the count beside it
            val label = if (Bar.notifications > 99) "99+" else Bar.notifications.toString()
            val tw = ui.measure(label, size * 0.8f)
            ui.text(c, label, x, ui.baselineFor(h / 2f, size * 0.8f), size * 0.8f, Color.WHITE, Paint.Align.RIGHT)
            x -= tw + 4f
            val ew = h * 0.62f
            val eh = h * 0.42f
            r.set(x - ew, (h - eh) / 2f, x, (h + eh) / 2f)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.6f
            p.color = Color.WHITE
            c.drawRect(r, p)
            c.drawLine(r.left, r.top, r.centerX(), r.centerY(), p)
            c.drawLine(r.right, r.top, r.centerX(), r.centerY(), p)
            p.style = Paint.Style.FILL
            x -= ew + pad * 0.6f
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
