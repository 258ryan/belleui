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
            // the Avkon mail icon (avkon2.mif of the E6), with the count beside it
            val label = if (Bar.notifications > 99) "99+" else Bar.notifications.toString()
            val ts = ch * 0.85f
            val tw = ui.measure(label, ts)
            ui.text(c, label, x, ui.baselineFor(cy, ts), ts, Color.WHITE, Paint.Align.RIGHT)
            x -= tw + pad14
            glyph("av_mail")
        }
        // the universal indicators are the E6's own Avkon icons (avkon2.mif, decoded from NVG)
        if (Bar.usb) glyph("av_usb")
        if (Bar.wifi) glyph("av_wlan")
        if (Bar.bluetooth) glyph("av_bluetooth")
        if (Bar.headset) glyph("av_headset")
        if (Bar.silent) glyph("av_mute")
        if (Bar.alarm) glyph("av_alarm")
        if (Bar.airplane) glyph("av_offline")

        if (Bar.charging) postInvalidateDelayed(220)
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
