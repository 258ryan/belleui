package com.belleui.files

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StrictMode
import android.provider.Settings
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
import java.io.File

class FilesActivity : BelleActivity() {
    private lateinit var field: EditText
    private var textCb: ((String) -> Unit)? = null
    private lateinit var homeUi: BelleShell
    val prefs by lazy { getSharedPreferences("files", Context.MODE_PRIVATE) }

    var showHidden: Boolean
        get() = prefs.getBoolean("hidden", false)
        set(v) = prefs.edit().putBoolean("hidden", v).apply()

    var sort: Int
        get() = prefs.getInt("sort", Fs.SORT_NAME)
        set(v) = prefs.edit().putInt("sort", v).apply()

    var onResumeHook: (() -> Unit)? = null

    override fun buildContent(shell: BelleShell): View {
        homeUi = shell
        // opening files hands other apps a plain file:// URI
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
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
        field.imeOptions = EditorInfo.IME_ACTION_DONE
        field.setPadding(24, 0, 24, 0)
        field.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_DONE || (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) {
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

    override fun createRoot(shell: BelleShell): Screen = RootScreen(this)

    /** Shows a Belle text field at the top (real keyboard) and returns the text through [cb]. */
    fun askText(hint: String, initial: String, cb: (String) -> Unit) {
        val sc = homeUi.ui.sc
        val lp = field.layoutParams as FrameLayout.LayoutParams
        lp.topMargin = (homeUi.contentTop + 6f * sc).toInt()
        lp.height = (46f * sc).toInt()
        lp.leftMargin = (8f * sc).toInt()
        lp.rightMargin = (8f * sc).toInt()
        field.layoutParams = lp
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f * sc)
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

    fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun askAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPerms(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)) { onResumeHook?.invoke() }
        }
    }

    fun openFile(f: File): String? {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(f), Fs.mime(f)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(i)
            null
        } catch (_: Exception) {
            "No app can open this file"
        }
    }

    override fun onResume() {
        super.onResume()
        onResumeHook?.invoke()
    }
}
