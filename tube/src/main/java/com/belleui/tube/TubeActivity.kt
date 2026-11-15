package com.belleui.tube

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import com.belleui.kit.BelleActivity
import com.belleui.kit.BelleShell
import com.belleui.kit.Screen

/** A YouTube client in the Belle look: Trending, search, and playback, with NewPipe's extractor doing the talking. */
class TubeActivity : BelleActivity() {
    private lateinit var field: EditText
    private var textCb: ((String) -> Unit)? = null
    private lateinit var homeUi: BelleShell
    private val ui = Handler(Looper.getMainLooper())

    fun onMain(r: () -> Unit) { ui.post(r) }

    /** Runs [work] off the UI thread, then [done] on it. */
    fun <T> bg(work: () -> T, done: (Result<T>) -> Unit) {
        Thread {
            val r = runCatching(work)
            ui.post { done(r) }
        }.start()
    }

    override fun buildContent(shell: BelleShell): View {
        homeUi = shell
        val root = FrameLayout(this)
        root.addView(shell, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        field = EditText(this)
        val gfx = shell.ui.gfx
        field.background = object : Drawable() {
            override fun draw(c: Canvas) {
                gfx.nine(c, "qtg_fr_textfield_highlighted", RectF(bounds), 40, 20f * shell.ui.sc)
            }

            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: ColorFilter?) {}

            @Suppress("OVERRIDE_DEPRECATION")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
        field.setTextColor(0xFF1B1B1B.toInt())
        field.setHintTextColor(0xFF8A8A8A.toInt())
        field.setSingleLine()
        field.imeOptions = EditorInfo.IME_ACTION_SEARCH
        field.setPadding(24, 0, 24, 0)
        field.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE || (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) {
                val t = field.text.toString().trim()
                hideText()
                if (t.isNotEmpty()) textCb?.invoke(t)
                true
            } else false
        }
        root.addView(field, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100))
        field.visibility = View.GONE
        return root
    }

    override fun createRoot(shell: BelleShell): Screen {
        // a YouTube link opened from another app goes straight to the video
        val link = intent?.data?.toString()
        return if (link != null) VideoScreen(this, link, "Video") else TrendingScreen(this)
    }

    fun askText(hint: String, initial: String, cb: (String) -> Unit) {
        val sc = homeUi.ui.sc
        val lp = field.layoutParams as FrameLayout.LayoutParams
        lp.topMargin = (homeUi.contentTop + 6f * sc).toInt()
        lp.height = (46f * sc).toInt()
        lp.leftMargin = (8f * sc).toInt()
        lp.rightMargin = (8f * sc).toInt()
        field.layoutParams = lp
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f * sc)
        field.hint = hint
        field.setText(initial)
        textCb = cb
        field.visibility = View.VISIBLE
        field.requestFocus()
        field.selectAll()
        getSystemService(InputMethodManager::class.java).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideText() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
        field.visibility = View.GONE
        homeUi.requestFocus()
        homeUi.invalidate()
    }

    override fun shellHandlesKeys(): Boolean = field.visibility != View.VISIBLE

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (field.visibility == View.VISIBLE && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_ESCAPE)
        ) {
            hideText()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun play(url: String, title: String, audioOnly: Boolean) {
        startActivity(
            android.content.Intent(this, PlayerActivity::class.java)
                .putExtra("url", url)
                .putExtra("title", title)
                .putExtra("audio", audioOnly),
        )
    }

    fun prefs(): android.content.SharedPreferences = getSharedPreferences("tube", Context.MODE_PRIVATE)
}
