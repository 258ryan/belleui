package com.belleui.keyboard

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import com.belleui.kit.Act
import com.belleui.kit.BelleActivity
import com.belleui.kit.BelleShell
import com.belleui.kit.Ic
import com.belleui.kit.ListDialog
import com.belleui.kit.ListState
import com.belleui.kit.QueryDialog
import com.belleui.kit.Row
import com.belleui.kit.Screen
import com.belleui.kit.Ui

/** Set-up and options for the Belle keyboard, with a text box to try it in. */
class KeyboardActivity : BelleActivity() {
    private lateinit var field: EditText
    val prefs by lazy { getSharedPreferences("keyboard", Context.MODE_PRIVATE) }
    val imm get() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    override fun createRoot(shell: BelleShell): Screen = SetupScreen(this)

    override fun buildContent(shell: BelleShell): View {
        val root = FrameLayout(this)
        root.addView(shell, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        field = EditText(this)
        field.setBackgroundColor(0xFFF2F2F2.toInt())
        field.setTextColor(0xFF1B1B1B.toInt())
        field.setHint("Type here to try the keyboard")
        field.setHintTextColor(0xFF8A8A8A.toInt())
        field.imeOptions = EditorInfo.IME_ACTION_DONE
        field.setPadding(24, 0, 24, 0)
        root.addView(field, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 110))
        field.visibility = View.GONE
        return root
    }

    override fun shellHandlesKeys() = field.visibility != View.VISIBLE

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (field.visibility == View.VISIBLE && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) hideTry()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun showTry() {
        field.visibility = View.VISIBLE
        field.requestFocus()
        field.post { imm.showSoftInput(field, 0) }
    }

    private fun hideTry() {
        imm.hideSoftInputFromWindow(field.windowToken, 0)
        field.visibility = View.GONE
        field.clearFocus()
        shell.requestFocus()
        shell.invalidate()
    }

    var onResumeHook: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        onResumeHook?.invoke()
    }

    fun learnedCount(): Int = getSharedPreferences("keyboard_words", Context.MODE_PRIVATE).getString("learned", "")!!.split("\n").count { it.isNotBlank() }

    fun forgetWords() {
        getSharedPreferences("keyboard_words", Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun enabled(): Boolean = imm.enabledInputMethodList.any { it.packageName == packageName }

    fun inUse(): Boolean = (Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "").startsWith("$packageName/")
}

class SetupScreen(private val act: KeyboardActivity) : Screen() {
    override val title get() = "Keyboard"
    private val list = ListState()

    override fun onShow() {
        act.onResumeHook = { shell.postInvalidate() }
    }

    private fun flag(key: String, def: Boolean) = act.prefs.getBoolean(key, def)

    private fun rows(): List<Row> {
        val g = shell.ui.gfx
        val layout = if (act.prefs.getInt("layout", 0) == 1) "QWERTY" else "A to Z"
        return listOf(
            Row("Turn on", if (act.enabled()) "Belle keyboard is switched on" else "Press A, then switch it on", iconKind = Ic.CHECK, check = act.enabled()),
            Row("Use it", if (act.inUse()) "It is the keyboard in use" else "Press A to choose the keyboard", iconKind = Ic.GRID, check = act.inUse()),
            Row("Letter order", layout, iconKind = Ic.LIST, drill = true),
            Row("Capital after a full stop", "Starts sentences with a capital", iconKind = Ic.NOTE, check = flag("autocap", true)),
            Row("Word suggestions", "Suggests words as you type; up from the keys picks one", iconKind = Ic.SEARCH, check = flag("predict", true)),
            Row("Forget typed words", "${act.learnedCount()} words learned from you", iconKind = Ic.DELETE, drill = true),
            Row("Key click", "A short sound for each key", iconKind = Ic.AUDIO_ON, check = flag("click", true)),
            Row("Vibrate", "A short buzz for each key", iconKind = Ic.BELL, check = flag("buzz", false)),
            Row("Try it", "Open a text box to type in", iconKind = Ic.FIND, drill = true),
        )
    }

    private fun area() = RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom)

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        val rows = rows()
        list.draw(c, ui, r, rows.size) { rows[it] }
    }

    private fun open(i: Int) {
        when (i) {
            0 -> try { act.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) } catch (_: Exception) { shell.toast("Not available") }
            1 -> if (act.enabled()) act.imm.showInputMethodPicker() else shell.toast("Turn it on first")
            2 -> shell.showOverlay(ListDialog("Letter order", listOf("A to Z", "QWERTY"), act.prefs.getInt("layout", 0)) { k ->
                act.prefs.edit().putInt("layout", k).apply()
            })
            3 -> act.prefs.edit().putBoolean("autocap", !flag("autocap", true)).apply()
            4 -> act.prefs.edit().putBoolean("predict", !flag("predict", true)).apply()
            5 -> shell.showOverlay(QueryDialog("Forget the words the keyboard learned from you?", "Forget", "Cancel") {
                act.forgetWords()
                shell.toast("Forgotten")
            })
            6 -> act.prefs.edit().putBoolean("click", !flag("click", true)).apply()
            7 -> act.prefs.edit().putBoolean("buzz", !flag("buzz", false)).apply()
            8 -> if (act.inUse()) act.showTry() else shell.toast("Choose Belle keyboard first (Use it)")
        }
    }

    override fun onAct(a: Act): Boolean {
        if (a == Act.OK) {
            open(list.sel)
            return true
        }
        return list.act(a, 9, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val i = list.hit(shell.ui, area(), y, 9)
        if (i < 0) return false
        list.sel = i
        open(i)
        return true
    }
}
