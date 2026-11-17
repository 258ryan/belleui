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

    /** Draws [name] stretched into [to] as three slices: [srcBorder] px caps at the ends, the middle stretched, the full height scaled. */
    fun threeH(c: Canvas, name: String, to: RectF, srcBorder: Int, dstBorder: Float, alpha: Int = 255): Boolean {
        val bmp = get(name) ?: return false
        val sb = min(srcBorder, bmp.width / 2)
        val db = min(dstBorder, to.width() / 2f)
        val sx = intArrayOf(0, sb, bmp.width - sb, bmp.width)
        val dx = floatArrayOf(to.left, to.left + db, to.right - db, to.right)
        paint.alpha = alpha
        for (i in 0..2) {
            if (sx[i + 1] <= sx[i] || dx[i + 1] <= dx[i]) continue
            src.set(sx[i], 0, sx[i + 1], bmp.height)
            dst.set(dx[i], to.top, dx[i + 1], to.bottom)
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

    private val digitNames = mapOf(
        '0' to "zero", '1' to "one", '2' to "two", '3' to "three", '4' to "four", '5' to "five", '6' to "six", '7' to "seven", '8' to "eight", '9' to "nine",
        ':' to "colon", '.' to "stop",
    )

    /** Width of [text] set in the Belle digital-clock digits at height [h]. */
    fun digitsWidth(text: String, h: Float): Float {
        val s = h / 200f
        var w = 0f
        for (ch in text) {
            val b = digitNames[ch]?.let { get("qsn_cp_clock_digital_new_$it") } ?: continue
            w += b.width * s + 2f * s
        }
        return w
    }

    /** Draws [text] (digits, colon, point) in the Belle clock digits, [h] tall, with its vertical middle at [cy]. Returns the width used. */
    fun digits(c: Canvas, text: String, x: Float, cy: Float, h: Float, align: Paint.Align = Paint.Align.LEFT, alpha: Int = 255): Float {
        val s = h / 200f
        val total = digitsWidth(text, h)
        var px = when (align) { Paint.Align.CENTER -> x - total / 2f; Paint.Align.RIGHT -> x - total; else -> x }
        paint.alpha = alpha
        for (ch in text) {
            val b = digitNames[ch]?.let { get("qsn_cp_clock_digital_new_$it") } ?: continue
            dst.set(px, cy - b.height * s / 2f, px + b.width * s, cy + b.height * s / 2f)
            c.drawBitmap(b, null, dst, paint)
            px += b.width * s + 2f * s
        }
        paint.alpha = 255
        return total
    }

    /** Draws the part [part] of a graphic (in its own pixels) into [to]. */
    fun drawPart(c: Canvas, name: String, part: Rect, to: RectF): Boolean {
        val bmp = get(name) ?: return false
        c.drawBitmap(bmp, part, to, paint)
        return true
    }

    private val tintPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Like [glyph], but recoloured: the dark input glyphs of the theme become white on a dark key. */
    fun glyphTinted(c: Canvas, name: String, cx: Float, cy: Float, size: Float, color: Int): Boolean {
        val bmp = get(name) ?: return false
        dst.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        tintPaint.colorFilter = android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
        c.drawBitmap(bmp, null, dst, tintPaint)
        tintPaint.colorFilter = null
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
