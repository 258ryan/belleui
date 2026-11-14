package com.belleui.calc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import com.belleui.kit.Act
import com.belleui.kit.Belle
import com.belleui.kit.BelleActivity
import com.belleui.kit.BelleShell
import com.belleui.kit.Ic
import com.belleui.kit.ListState
import com.belleui.kit.MenuItem
import com.belleui.kit.Row
import com.belleui.kit.Screen
import com.belleui.kit.Sounds
import com.belleui.kit.Tool
import com.belleui.kit.Ui

class CalcActivity : BelleActivity() {
    val calc = CalcScreen(this)
    val history = ArrayList<Pair<String, String>>()

    override fun createRoot(shell: BelleShell): Screen = calc

    /** Digits and operators from a keyboard work too. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && shell.screen === calc) {
            val k = when (event.keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (event.keyCode - KeyEvent.KEYCODE_0)).toString()
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> ('0' + (event.keyCode - KeyEvent.KEYCODE_NUMPAD_0)).toString()
                KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_NUMPAD_DOT -> "."
                KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> "+"
                KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> "−"
                KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> "×"
                KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_NUMPAD_DIVIDE -> "÷"
                KeyEvent.KEYCODE_EQUALS -> "="
                KeyEvent.KEYCODE_DEL -> "⌫"
                else -> null
            }
            if (k != null) {
                calc.press(k)
                shell.invalidate()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

/** The calculator: a display and a 4 x 5 keypad. The D-pad moves the highlight, A presses it. */
class CalcScreen(private val act: CalcActivity) : Screen() {
    override val title get() = "Calculator"
    private val keys = listOf(
        listOf("C", "(", ")", "÷"),
        listOf("7", "8", "9", "×"),
        listOf("4", "5", "6", "−"),
        listOf("1", "2", "3", "+"),
        listOf("0", ".", "%", "="),
    )
    private var r = 1
    private var c = 0
    private var expr = ""
    private var result: String? = null // set after =, until the next key
    private val rects = Array(5) { Array(4) { RectF() } }

    private val ops = "+−×÷"

    fun press(k: String) {
        if (result != null) {
            // typing a number starts afresh; an operator carries the result on
            if (k[0].isDigit() || k == "." || k == "(") expr = "" else if (k in listOf("⌫", "C")) {
                expr = if (k == "C") "" else expr
            } else expr = result!!
            result = null
            if (k == "⌫") return
        }
        when (k) {
            "C" -> expr = ""
            "⌫" -> expr = expr.dropLast(1)
            "=" -> {
                val v = Eval.value(autoClose(expr))
                if (v == null || v.isNaN() || v.isInfinite()) {
                    result = null
                    Sounds.play(Sounds.ERROR)
                } else {
                    val shown = Eval.format(v)
                    act.history.add(0, expr to shown)
                    if (act.history.size > 50) act.history.removeAt(50)
                    expr = shown
                    result = shown
                }
            }
            else -> {
                if (k in ops.map { it.toString() }) {
                    if (expr.isEmpty() && k != "−") return
                    // a second operator replaces the first
                    if (expr.isNotEmpty() && expr.last() in ops) expr = expr.dropLast(1)
                } else if (k == ".") {
                    val tail = expr.takeLastWhile { it.isDigit() || it == '.' }
                    if ('.' in tail) return
                }
                if (expr.length < 60) expr += k
            }
        }
    }

    private fun autoClose(e: String): String {
        var s = e
        while (s.isNotEmpty() && s.last() in ops) s = s.dropLast(1)
        val open = s.count { it == '(' } - s.count { it == ')' }
        return s + ")".repeat(maxOf(0, open))
    }

    override fun tools() = listOf(
        Tool(Ic.DELETE, { press("⌫"); shell.invalidate() }),
        Tool(Ic.REFRESH, { press("C"); shell.invalidate() }),
    )

    override fun menu() = listOf(
        MenuItem("History") { shell.push(HistoryScreen(act, this)) },
        MenuItem("Copy result") {
            val text = result ?: Eval.value(autoClose(expr))?.let { Eval.format(it) } ?: expr
            (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("result", text))
            shell.toast("Copied")
        },
    )

    /** Brings back an earlier calculation. */
    fun recall(e: String) {
        expr = e
        result = null
    }

    override fun draw(cv: Canvas, ui: Ui, area: RectF) {
        val sc = ui.sc
        // display
        val disp = RectF(area.left + 12f * sc, area.top + 8f * sc, area.right - 12f * sc, area.top + 100f * sc)
        ui.p.style = Paint.Style.FILL
        ui.p.shader = null
        ui.p.color = Belle.PANEL
        cv.drawRoundRect(disp, 8f * sc, 8f * sc, ui.p)
        val live = if (result == null && expr.isNotEmpty()) Eval.value(autoClose(expr))?.takeIf { !it.isNaN() && !it.isInfinite() }?.let { Eval.format(it) } else null
        val big = result ?: live ?: if (expr.isEmpty()) "0" else ""
        ui.text(cv, expr.ifEmpty { " " }, disp.right - 14f * sc, disp.top + 32f * sc, 22f * sc, if (result != null) Belle.TEXT_MID else Color.WHITE, Paint.Align.RIGHT, maxW = disp.width() - 28f * sc, face = ui.light)
        if (big.isNotEmpty() && big != expr) ui.text(cv, big, disp.right - 14f * sc, disp.top + 80f * sc, 40f * sc, Color.WHITE, Paint.Align.RIGHT, maxW = disp.width() - 28f * sc)
        else if (result != null) ui.text(cv, big, disp.right - 14f * sc, disp.top + 80f * sc, 40f * sc, Color.WHITE, Paint.Align.RIGHT, maxW = disp.width() - 28f * sc)

        // keypad
        val top = disp.bottom + 10f * sc
        val gap = 8f * sc
        val kw = (area.width() - 24f * sc - 3 * gap) / 4f
        val kh = (area.bottom - top - 8f * sc - 4 * gap) / 5f
        for (ri in keys.indices) for (ci in keys[ri].indices) {
            val x = area.left + 12f * sc + ci * (kw + gap)
            val y = top + ri * (kh + gap)
            val rect = rects[ri][ci]
            rect.set(x, y, x + kw, y + kh)
            val k = keys[ri][ci]
            ui.pushButton(cv, rect, k, ri == r && ci == c)
        }
    }

    override fun onAct(a: Act): Boolean {
        when (a) {
            Act.UP -> if (r > 0) r-- else return false
            Act.DOWN -> if (r < keys.size - 1) r++ else return false
            Act.LEFT -> c = (c + 3) % 4
            Act.RIGHT -> c = (c + 1) % 4
            Act.OK -> press(keys[r][c])
            Act.PREV -> press("⌫")
            Act.NEXT -> press("=")
            else -> return false
        }
        return true
    }

    override fun onTap(x: Float, y: Float): Boolean {
        for (ri in rects.indices) for (ci in rects[ri].indices) if (rects[ri][ci].contains(x, y)) {
            r = ri
            c = ci
            press(keys[ri][ci])
            return true
        }
        return false
    }
}

class HistoryScreen(private val act: CalcActivity, private val calc: CalcScreen) : Screen() {
    override val title get() = "History"
    private val list = ListState()

    override fun menu() = listOf(MenuItem("Clear history") { act.history.clear(); shell.invalidate() })

    override fun draw(cv: Canvas, ui: Ui, r: RectF) {
        if (act.history.isEmpty()) {
            ui.text(cv, "No calculations yet", r.centerX(), ui.baselineFor(r.centerY(), 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
            return
        }
        list.draw(cv, ui, r, act.history.size) { i -> Row(act.history[i].first, "= " + act.history[i].second) }
    }

    override fun onAct(a: Act): Boolean {
        if (act.history.isEmpty()) return false
        if (a == Act.OK) {
            calc.recall(act.history[list.sel.coerceIn(0, act.history.size - 1)].second)
            shell.pop()
            return true
        }
        return list.act(a, act.history.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val i = list.hit(shell.ui, RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom), y, act.history.size)
        if (i < 0) return false
        calc.recall(act.history[i].second)
        shell.pop()
        return true
    }
}
