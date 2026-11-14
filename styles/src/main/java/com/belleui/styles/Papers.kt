package com.belleui.styles

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader

/** The wallpapers that come with Styles, drawn at whatever size is asked for. */
object Papers {
    val names = listOf("Belle Blue", "Teal Bloom", "Magenta Dusk", "Amber", "Steel", "Carbon", "Aurora", "Paper", "Black")

    private fun radial(c: Canvas, w: Int, h: Int, cx: Float, cy: Float, r: Float, vararg stops: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val pos = FloatArray(stops.size) { it / (stops.size - 1f) }
        p.shader = RadialGradient(cx * w, cy * h, r * w, stops, pos, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

    private fun linear(c: Canvas, w: Int, h: Int, x0: Float, y0: Float, x1: Float, y1: Float, vararg stops: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val pos = FloatArray(stops.size) { it / (stops.size - 1f) }
        p.shader = LinearGradient(x0 * w, y0 * h, x1 * w, y1 * h, stops, pos, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

    private fun vignette(c: Canvas, w: Int, h: Int, alpha: Int) {
        radial(c, w, h, 0.5f, 0.5f, 0.85f, Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(alpha, 0, 0, 0))
    }

    fun render(i: Int, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        when (i) {
            0 -> {
                c.drawColor(0xFF031226.toInt())
                radial(c, w, h, 0.3f, 0.2f, 1.0f, 0xFF3AA0F0.toInt(), 0xFF0A3A78.toInt(), 0xFF031226.toInt())
                vignette(c, w, h, 90)
            }
            1 -> {
                c.drawColor(0xFF021412.toInt())
                radial(c, w, h, 0.7f, 0.25f, 1.0f, 0xFF3FD4BE.toInt(), 0xFF0C4A44.toInt(), 0xFF021412.toInt())
                vignette(c, w, h, 90)
            }
            2 -> {
                linear(c, w, h, 0f, 0f, 0f, 1f, 0xFF1E0A1A.toInt(), 0xFF5A1745.toInt(), 0xFFD0338A.toInt(), 0xFFFFA3D3.toInt())
                radial(c, w, h, 0.5f, 1.05f, 0.7f, 0x66FFFFFF, Color.TRANSPARENT)
            }
            3 -> {
                c.drawColor(0xFF2A1608.toInt())
                radial(c, w, h, 0.25f, 0.9f, 1.1f, 0xFFFFC27A.toInt(), 0xFFE8761A.toInt(), 0xFF2A1608.toInt())
                vignette(c, w, h, 80)
            }
            4 -> {
                linear(c, w, h, 0f, 0f, 1f, 1f, 0xFF9DB3C6.toInt(), 0xFF566574.toInt(), 0xFF222A33.toInt())
                radial(c, w, h, 0.25f, 0.1f, 0.6f, 0x55FFFFFF, Color.TRANSPARENT)
            }
            5 -> {
                c.drawColor(0xFF161616.toInt())
                val p = Paint()
                p.color = 0x16FFFFFF
                p.strokeWidth = w / 320f
                val step = w / 90f
                var x = -h.toFloat()
                while (x < w) {
                    c.drawLine(x, h.toFloat(), x + h, 0f, p)
                    x += step
                }
                vignette(c, w, h, 150)
            }
            6 -> {
                c.drawColor(0xFF050A18.toInt())
                radial(c, w, h, 0.2f, 0.3f, 0.7f, 0xCC1080DD.toInt(), Color.TRANSPARENT)
                radial(c, w, h, 0.75f, 0.35f, 0.65f, 0xAA27D3A0.toInt(), Color.TRANSPARENT)
                radial(c, w, h, 0.5f, 0.9f, 0.7f, 0x99A03CD0.toInt(), Color.TRANSPARENT)
                vignette(c, w, h, 100)
            }
            7 -> {
                linear(c, w, h, 0f, 0f, 0f, 1f, 0xFFE9EBED.toInt(), 0xFFC8CDD2.toInt())
                vignette(c, w, h, 50)
            }
            else -> c.drawColor(Color.BLACK)
        }
        return b
    }
}
