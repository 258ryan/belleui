package com.belleui.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.belleui.kit.BelleGfx
import java.lang.ref.WeakReference

/** The Belle battery meter (the horizontal Belle graphic, filled to the level) for the stock status bar. */
object BelleBattery {
    @Volatile
    var pct = 100
    @Volatile
    var charging = false
    private var registered = false
    private val users = ArrayList<WeakReference<Drawable>>()

    private fun read(i: Intent?) {
        if (i == null) return
        val level = i.getIntExtra("level", 0)
        val scale = i.getIntExtra("scale", 100).coerceAtLeast(1)
        pct = level * 100 / scale
        charging = i.getIntExtra("plugged", 0) != 0
    }

    @Synchronized
    private fun ensure(ctx: Context) {
        if (registered) return
        registered = true
        val app = ctx.applicationContext ?: ctx
        read(app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                read(i)
                synchronized(users) {
                    val it = users.iterator()
                    while (it.hasNext()) {
                        val d = it.next().get()
                        if (d == null) it.remove() else d.invalidateSelf()
                    }
                }
            }
        }, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
    }

    @JvmStatic
    fun drawable(ctx: Context): Drawable? = try {
        ensure(ctx)
        val d = BatteryDrawable(ctx)
        synchronized(users) { users.add(WeakReference(d)) }
        d
    } catch (_: Throwable) {
        null
    }

    private class BatteryDrawable(ctx: Context) : Drawable() {
        private val gfx = BelleGfx(ctx)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val bolt = Path()

        override fun draw(c: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            r.set(b)
            val bg = gfx.get("qtg_graf_battery_level_bg")
            val full = gfx.get("qtg_graf_battery_level_full")
            if (bg != null) c.drawBitmap(bg, null, r, null)
            if (full != null) {
                c.save()
                c.clipRect(r.left, r.top, r.left + r.width() * (0.12f + 0.76f * pct / 100f), r.bottom)
                c.drawBitmap(full, null, r, null)
                c.restore()
            }
            if (charging) {
                val cx = r.centerX()
                val cy = r.centerY()
                val s = r.height() * 0.32f
                bolt.reset()
                bolt.moveTo(cx + s * 0.2f, cy - s)
                bolt.lineTo(cx - s * 0.55f, cy + s * 0.15f)
                bolt.lineTo(cx - s * 0.05f, cy + s * 0.15f)
                bolt.lineTo(cx - s * 0.2f, cy + s)
                bolt.lineTo(cx + s * 0.55f, cy - s * 0.15f)
                bolt.lineTo(cx + s * 0.05f, cy - s * 0.15f)
                bolt.close()
                p.style = Paint.Style.FILL
                p.color = 0xFFFFFFFF.toInt()
                c.drawPath(bolt, p)
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth() = 48
        override fun getIntrinsicHeight() = 30
    }
}
