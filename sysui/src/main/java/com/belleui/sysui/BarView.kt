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

/** The Belle status bar, as on the E6: the screen's title on the left; indicators, battery and clock on the right. */
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
        val size = h * 0.86f   // the title: about 30 px on the 35 px bar (measured on a real Belle screen)
        val pad = h * 0.4f

        // left: the title of what is on screen, as on every Belle screen
        ui.text(c, Bar.title, pad, ui.baselineFor(h / 2f, size), size, Color.WHITE, maxW = w * 0.45f)

        // right side: Qt Components StatusBarDefault.qml from the firmware, every size a fraction of the bar height
        // (statusBarHeight): content 18/26, battery 24/26 wide, clock 44/26 wide, padding-small 6 at 36 px
        val cy = h / 2f
        val ch = Math.round(h * 18f / 26f).toFloat()                 // priv.contentHeight
        val padS = 6f * h / 36f                                      // platformStyle.paddingSmall
        val pad14 = Math.round(padS / 4f).toFloat()                  // paddingSmallOneQuarter
        val pad34 = Math.round(padS * 3f / 4f).toFloat()             // paddingSmallThreeQuarters

        // clock: Light, content-height pixels, right aligned in a 44/26 box, paddingSmall from the edge
        val clock = DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", System.currentTimeMillis()).toString()
        val clockRight = w - padS
        val clockW = Math.round(h * 44f / 26f).toFloat()
        val csz = h * 0.74f   // the clock: about 26 px
        ui.text(c, clock, clockRight, ui.baselineFor(cy, csz, ui.light), csz, Color.WHITE, Paint.Align.RIGHT, face = ui.light)
        val clockLeft = clockRight - maxOf(clockW, ui.measure(clock, csz, ui.light))

        // battery: 24/26 wide box, fill width (level + 2) / 13 of the bar height, level 0..7; charging runs the level round in 3.5 s
        val bw = Math.round(h * 24f / 26f).toFloat()
        val bRight = clockLeft - pad34
        r.set(bRight - bw, cy - ch / 2f, bRight, cy + ch / 2f)
        val psm = if (Bar.powerSave) "_psm" else ""
        val bg = ui.gfx.get("qtg_graf_battery_level${psm}_bg")
        val full = ui.gfx.get("qtg_graf_battery_level${psm}_full")
        val level = if (Bar.charging) ((System.currentTimeMillis() % 3500L) * 800L / 3500L).toInt() / 100 else ((Bar.batteryPct * 7 + 50) / 100).coerceIn(0, 7)
        if (bg != null) c.drawBitmap(bg, null, fit(bg, r), null)
        if (full != null) {
            c.save()
            c.clipRect(r.left, r.top, r.left + Math.round(h * (level + 2) / 13f), r.bottom)
            c.drawBitmap(full, null, fit(full, r), null)
            c.restore()
        }
        var x = r.left - pad34

        // signal strength: the Qt Components signal pictures (19/18 of the content height wide), the full bars clipped to the level (0..7)
        if (Bar.signal >= 0) {
            val sw = ch * 19f / 18f
            r.set(x - sw, cy - ch / 2f, x, cy + ch / 2f)
            val sbg = ui.gfx.get("qtg_graf_signal_level_bg")
            val sfull = ui.gfx.get("qtg_graf_signal_level_full")
            if (sbg != null) c.drawBitmap(sbg, null, fit(sbg, r), null)
            if (sfull != null) {
                c.save()
                c.clipRect(r.left, r.top, r.left + r.width() * (Bar.signal + 1) / 8f, r.bottom)
                c.drawBitmap(sfull, null, fit(sfull, r), null)
                c.restore()
            }
            x = r.left - pad34
        }

        // universal indicators: at most three (maxIndicatorCount), content-height squares, a quarter padding apart
        var shown = 0
        fun glyph(name: String) {
            if (shown >= 3) return
            r.set(x - ch, cy - ch / 2f, x, cy + ch / 2f)
            ui.gfx.draw(c, name, r)
            x -= ch + pad14
            shown++
        }
        if (Bar.notifications > 0 && shown < 3) {
            // an envelope, with the count beside it
            val label = if (Bar.notifications > 99) "99+" else Bar.notifications.toString()
            val ts = ch * 0.85f
            val tw = ui.measure(label, ts)
            ui.text(c, label, x, ui.baselineFor(cy, ts), ts, Color.WHITE, Paint.Align.RIGHT)
            x -= tw + pad14
            val ew = ch
            val eh = ch * 0.7f
            r.set(x - ew, cy - eh / 2f, x, cy + eh / 2f)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.6f
            p.color = Color.WHITE
            c.drawRect(r, p)
            c.drawLine(r.left, r.top, r.centerX(), r.centerY(), p)
            c.drawLine(r.right, r.top, r.centerX(), r.centerY(), p)
            p.style = Paint.Style.FILL
            x -= ew + pad14
            shown++
        }
        if (Bar.usb && shown < 3) { usbGlyph(c, x - ch / 2f, cy, ch); x -= ch + pad14; shown++ }
        if (Bar.wifi) glyph("qtg_toolbar_wlan_on")
        if (Bar.bluetooth) glyph("qtg_toolbar_bluetooth_on")
        if (Bar.silent) glyph("qtg_toolbar_audio_off")
        if (Bar.alarm && shown < 3) { ui.glyph(c, com.belleui.kit.Ic.BELL, x - ch / 2f, cy, ch); x -= ch + pad14; shown++ }
        if (Bar.airplane) glyph("qtg_toolbar_network_off")

        if (Bar.charging) postInvalidateDelayed(220)
    }

    /** The USB trident, as a small white glyph: stem, arrow head, a circle on the left branch and a square on the right one. */
    private fun usbGlyph(c: Canvas, cx: Float, cy: Float, s: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = maxOf(1.4f, s * 0.09f)
        p.strokeCap = Paint.Cap.ROUND
        p.color = Color.WHITE
        val top = cy - s * 0.42f
        val bot = cy + s * 0.34f
        c.drawLine(cx, top, cx, bot, p)
        c.drawLine(cx, cy + s * 0.08f, cx - s * 0.26f, cy - s * 0.06f, p)
        c.drawLine(cx, cy - s * 0.02f, cx + s * 0.26f, cy - s * 0.16f, p)
        p.style = Paint.Style.FILL
        c.drawCircle(cx, bot + s * 0.05f, s * 0.1f, p)
        c.drawCircle(cx - s * 0.28f, cy - s * 0.08f, s * 0.07f, p)
        c.drawRect(cx + s * 0.25f, cy - s * 0.25f, cx + s * 0.35f, cy - s * 0.15f, p)
        val path = android.graphics.Path()
        path.moveTo(cx, top - s * 0.1f)
        path.lineTo(cx - s * 0.1f, top + s * 0.08f)
        path.lineTo(cx + s * 0.1f, top + s * 0.08f)
        path.close()
        c.drawPath(path, p)
    }

    /** [bmp] fitted inside [box], aspect kept and centred (Image.PreserveAspectFit). */
    private val fitRect = RectF()
    private fun fit(bmp: android.graphics.Bitmap, box: RectF): RectF {
        val s = minOf(box.width() / bmp.width, box.height() / bmp.height)
        val dw = bmp.width * s
        val dh = bmp.height * s
        fitRect.set(box.centerX() - dw / 2f, box.centerY() - dh / 2f, box.centerX() + dw / 2f, box.centerY() + dh / 2f)
        return fitRect
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
