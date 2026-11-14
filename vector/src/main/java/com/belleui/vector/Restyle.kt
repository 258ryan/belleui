package com.belleui.vector

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.AbsListView
import android.widget.ImageView
import android.widget.TextView

/**
 * The Belle look, applied to whatever views the chooser has: light surfaces become the Belle dark panel,
 * dark text becomes white, and the item that has focus (D-pad, gamepad) or is pressed gets the Belle blue
 * highlight. Works on the view tree rather than on Android's private layout names, so it holds up across versions.
 */
object Restyle {
    private const val PANEL = 0xFF1E1E1E.toInt()
    private const val BLUE = 0xFF1080DD.toInt()
    private const val BLUE_LIGHT = 0xFF5CB4F8.toInt()
    private const val SEPARATOR = 0xFF444444.toInt()
    private const val MID = 0xFF999999.toInt()
    private val TAG_KEY = "belle_chooser".hashCode()

    fun apply(a: Activity) {
        val decor = a.window.decorView
        paint(decor, a.resources.displayMetrics.density, a)
        if (decor.getTag(TAG_KEY) == null) {
            decor.setTag(TAG_KEY, true)
            // list items are made and recycled as you scroll, so paint again when the layout changes
            decor.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (!decor.isAttachedToWindow) {
                        decor.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        return
                    }
                    try {
                        paint(decor, a.resources.displayMetrics.density, a)
                    } catch (_: Throwable) {
                    }
                }
            })
        }
    }

    private fun luminance(c: Int) = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0

    private fun highlight(density: Float): StateListDrawable {
        fun shape(): GradientDrawable {
            val g = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BLUE_LIGHT, BLUE))
            g.cornerRadius = 10f * density
            return g
        }
        val s = StateListDrawable()
        s.addState(intArrayOf(android.R.attr.state_pressed), shape())
        s.addState(intArrayOf(android.R.attr.state_focused), shape())
        s.addState(intArrayOf(android.R.attr.state_selected), shape())
        s.addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        return s
    }

    private fun paint(v: View, density: Float, a: Activity) {
        // surfaces: anything painted in a light flat colour becomes the dark panel
        val bg = v.background
        if (bg is ColorDrawable && Color.alpha(bg.color) > 200 && luminance(bg.color) > 0.6) {
            v.setBackgroundColor(PANEL)
        } else if (bg is RippleDrawable || (v.isClickable && v !is TextView && v is ViewGroup)) {
            // item rows: a Belle highlight when focused or pressed
            if (v.getTag(TAG_KEY) == null) {
                v.setTag(TAG_KEY, true)
                v.background = highlight(density)
                v.isFocusable = true
            }
        }
        if (v is AbsListView) {
            v.selector = highlight(density)
            v.setBackgroundColor(PANEL)
            v.cacheColorHint = PANEL
        }
        if (v is TextView && v !is android.widget.Button) {
            val c = v.currentTextColor
            if (luminance(c) < 0.55) v.setTextColor(if (Color.alpha(c) < 200 || v.textSize < 14f * density) MID else Color.WHITE)
            val idName = try { a.resources.getResourceEntryName(v.id) } catch (_: Throwable) { "" }
            if (idName == "title" || idName == "profile_button") v.setTextColor(BLUE_LIGHT)
        }
        if (v is android.widget.Button) {
            v.setTextColor(BLUE_LIGHT)
        }
        if (v !is ImageView && v !is TextView && v !is ViewGroup && v.layoutParams?.height != null && v.layoutParams.height in 1..(3 * density).toInt()) {
            // thin divider lines
            v.setBackgroundColor(SEPARATOR)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) paint(v.getChildAt(i), density, a)
    }
}
