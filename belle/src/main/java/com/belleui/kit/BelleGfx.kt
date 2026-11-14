package com.belleui.kit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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

    fun get(name: String): Bitmap? {
        cache[name]?.let { return it }
        if (name in missing) return null
        val bmp = try {
            ctx.assets.open("belle/$name.png").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
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
