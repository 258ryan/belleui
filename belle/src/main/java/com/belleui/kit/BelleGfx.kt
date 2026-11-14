package com.belleui.kit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.min

/**
 * Belle theme graphics (Qt Quick Components 1.1 for Symbian default theme, BSD) rendered to PNG
 * at 2x and shipped in assets/belle. Frames are drawn as 9-slice border images.
 */
class BelleGfx(private val ctx: Context) {
    private val cache = HashMap<String, Bitmap>()
    private val missing = HashSet<String>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()

    /** The graphics that are blue in the stock theme (selection, progress, buttons, switches); they follow the accent. */
    private val themed = setOf(
        "qtg_fr_list_pressed", "qtg_fr_progressbar_fill", "qtg_fr_pushbutton_pressed", "qtg_graf_checkbox_normal_selected",
        "qtg_graf_switchbutton_fill", "qtg_graf_switchbutton_handle_pressed", "qgn_indi_mup4_forward_focused",
        "qgn_indi_mup4_pause_focused", "qgn_indi_mup4_play_focused", "qgn_indi_mup4_rewind_focused",
    )
    private var seenAccent = 0

    private fun hsv(c: Int) = FloatArray(3).also { Color.colorToHSV(c, it) }

    /** Rotates the hue of a blue graphic to the accent, keeping its shading. */
    private fun tint(src: Bitmap): Bitmap {
        val base = hsv(Belle.DEFAULT_BLUE)
        val to = hsv(Belle.BLUE)
        val r = Math.toRadians((to[0] - base[0]).toDouble())
        val cs = Math.cos(r).toFloat()
        val sn = Math.sin(r).toFloat()
        val hue = ColorMatrix(floatArrayOf(
            .213f + cs * .787f - sn * .213f, .715f - cs * .715f - sn * .715f, .072f - cs * .072f + sn * .928f, 0f, 0f,
            .213f - cs * .213f + sn * .143f, .715f + cs * .285f + sn * .140f, .072f - cs * .072f - sn * .283f, 0f, 0f,
            .213f - cs * .213f - sn * .787f, .715f - cs * .715f + sn * .715f, .072f + cs * .928f + sn * .072f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
        val sat = ColorMatrix()
        sat.setSaturation((to[1] / base[1]).coerceIn(0.2f, 1.4f))
        hue.postConcat(sat)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val p = Paint()
        p.colorFilter = ColorMatrixColorFilter(hue)
        Canvas(out).drawBitmap(src, 0f, 0f, p)
        return out
    }

    fun get(name: String): Bitmap? {
        if (seenAccent != Belle.accentVersion) {
            seenAccent = Belle.accentVersion
            for (n in themed) cache.remove(n)
        }
        cache[name]?.let { return it }
        if (name in missing) return null
        var bmp = try {
            ctx.assets.open("belle/$name.png").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
        if (bmp != null && name in themed && Belle.BLUE != Belle.DEFAULT_BLUE) bmp = tint(bmp)
        if (bmp == null) missing.add(name) else cache[name] = bmp
        return bmp
    }

    /** Draws [name] stretched into [to] as a 9-slice; slice size in source px, border size on screen. */
    fun nine(c: Canvas, name: String, to: RectF, srcBorder: Int, dstBorder: Float, alpha: Int = 255): Boolean {
        val bmp = get(name) ?: return false
        val sb = min(srcBorder, min(bmp.width, bmp.height) / 2)
        val db = min(dstBorder, min(to.width(), to.height()) / 2f)
        val sx = intArrayOf(0, sb, bmp.width - sb, bmp.width)
        val sy = intArrayOf(0, sb, bmp.height - sb, bmp.height)
        val dx = floatArrayOf(to.left, to.left + db, to.right - db, to.right)
        val dy = floatArrayOf(to.top, to.top + db, to.bottom - db, to.bottom)
        paint.alpha = alpha
        for (j in 0..2) for (i in 0..2) {
            if (sx[i + 1] <= sx[i] || sy[j + 1] <= sy[j] || dx[i + 1] <= dx[i] || dy[j + 1] <= dy[j]) continue
            src.set(sx[i], sy[j], sx[i + 1], sy[j + 1])
            dst.set(dx[i], dy[j], dx[i + 1], dy[j + 1])
            c.drawBitmap(bmp, src, dst, paint)
        }
        paint.alpha = 255
        return true
    }

    /** Draws a flat glyph centred on (cx, cy), scaled to [size] px. */
    fun glyph(c: Canvas, name: String, cx: Float, cy: Float, size: Float, alpha: Int = 255): Boolean {
        val bmp = get(name) ?: return false
        dst.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        paint.alpha = alpha
        c.drawBitmap(bmp, null, dst, paint)
        paint.alpha = 255
        return true
    }

    /** Draws a graphic at its natural 1x size scaled by [scale], top-left at (x, y). */
    fun draw(c: Canvas, name: String, to: RectF, alpha: Int = 255): Boolean {
        val bmp = get(name) ?: return false
        paint.alpha = alpha
        c.drawBitmap(bmp, null, to, paint)
        paint.alpha = 255
        return true
    }
}
