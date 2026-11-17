package com.belleui.kit

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader

/**
 * Nokia Belle transition effects, taken from the E6 ROM (resource/effects/*.fxml).
 *
 *  - popup / note / context menu appear: scale 0.9 -> 1 out-quad, opacity 0 -> 1 LINEAR, 0.25 s
 *  - popup / note disappear: scale 1 -> 0.9 in-quad, opacity 1 -> 0 LINEAR, 0.25 s
 *  - options menu (optionsmenu_appear/disappear.fxml): not a slide. A masked wipe in place using smoothfade.mbm, a
 *    vertical soft-edged gradient (mask values 151..255 of 255, low at the top, full at the bottom). The mask
 *    `time` runs 0.125 -> 1 over 0.3 s linear on appear and 1 -> 0 on disappear, so the menu is revealed from its
 *    bottom edge upwards.
 */
object Fx {
    const val POPUP_MS = 250f
    const val MENU_MS = 300f

    /** fxml "outquad" */
    fun outQuad(t: Float): Float = 1f - (1f - t) * (1f - t)

    /** fxml "inquad" */
    fun inQuad(t: Float): Float = t * t

    /** Scale of a popup, from its linear progress (0 = hidden, 1 = shown) and whether it is appearing. */
    fun popupScale(progress: Float, appearing: Boolean): Float =
        if (appearing) 0.9f + 0.1f * outQuad(progress) else 1f - 0.1f * inQuad(1f - progress)

    /** Mask time of the options-menu wipe from its linear progress. */
    fun menuTime(progress: Float, appearing: Boolean): Float =
        if (appearing) 0.125f + 0.875f * progress else progress

    // smoothfade.mbm: mask value m = MASK_MIN + (1 - MASK_MIN) * y (y = 0 at the top of the bitmap, 1 at the bottom).
    // A pixel shows once the mask value passes (1 - time); SOFT is the width of the soft edge (an approximation).
    private const val MASK_MIN = 0.59f
    private const val SOFT = 0.1f

    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    /**
     * Finish a wipe. The caller opened an offscreen layer with `c.saveLayer(...)` and drew the content; this masks it
     * against [box] (the menu's rectangle) for the given mask [time] and pops the layer.
     */
    fun endWipe(c: Canvas, box: RectF, time: Float) {
        val threshold = 1f - time
        val y0 = (threshold - MASK_MIN) / (1f - MASK_MIN) // fraction of the box where the wipe edge starts
        val y1 = (threshold + SOFT - MASK_MIN) / (1f - MASK_MIN) // and where it is fully shown
        if (y1 > 0f && box.height() > 0f) {
            val bounds = c.clipBounds
            if (y0 >= 1f) {
                maskPaint.shader = null
                maskPaint.color = 0
                c.drawRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), maskPaint)
            } else {
                val a = y0.coerceIn(0f, 1f)
                val b = y1.coerceIn(a, 1f)
                maskPaint.shader = LinearGradient(
                    0f, box.top, 0f, box.bottom,
                    intArrayOf(0, 0, -1, -1), floatArrayOf(0f, a, b, 1f), Shader.TileMode.CLAMP,
                )
                c.drawRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), maskPaint)
                maskPaint.shader = null
            }
        }
        c.restore()
    }
}
