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
    override val appAccent: Int? get() = 0xFFE0952D.toInt()

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
        if (area.width() >= area.height()) drawLandscape(cv, ui, area) else drawPortrait(cv, ui, area)
    }

    /**
     * The E6 landscape calculator, from the firmware's Apps layout pack (variety 3 of the calc lines, 640x480):
     * the paper tape (calc_paper_pane l=9 t=14 r=322 b=92) and the display (calc_display_pane l=9 r=322 b=13 H=70)
     * on the left, the keypad (grid_calc_pane r=9 t=14, 304x350 = 4x5 cells of 76x70, cell_calc_pane) on the right.
     * Paper rows are 39 px (list_calc_item_pane); the key sits in its cell 12 px in and 9 px down, 52 px tall (cell_calc_pane_g2).
     */
    private fun drawLandscape(cv: Canvas, ui: Ui, area: RectF) {
        val sc = ui.sc
        val paper = RectF(area.left + 9f * sc, area.top + 14f * sc, area.right - 322f * sc, area.bottom - 92f * sc)
        val disp = RectF(area.left + 9f * sc, area.bottom - 83f * sc, area.right - 322f * sc, area.bottom - 13f * sc)
        if (!ui.gfx.nine(cv, "qgn_graf_calc_paper", paper, 12, 12f * sc)) {
            ui.p.style = Paint.Style.FILL
            ui.p.shader = null
            ui.p.color = 0xFFE9E9E5.toInt()
            cv.drawRoundRect(paper, 12f * sc, 12f * sc, ui.p)
        }
        if (!ui.gfx.nine(cv, "qgn_graf_calc_paper", disp, 12, 12f * sc)) {
            ui.p.style = Paint.Style.FILL
            ui.p.shader = null
            ui.p.color = 0xFFE9E9E5.toInt()
            cv.drawRoundRect(disp, 12f * sc, 12f * sc, ui.p)
        }
        val ink = 0xFF1B1B1B.toInt()
        // the tape: the latest finished sums, newest at the bottom, one 39 px row each
        val rowH = 39f * sc
        val rows = maxOf(1, ((paper.height() - 12f * sc) / rowH).toInt())
        val live = if (result == null && expr.isNotEmpty()) Eval.value(autoClose(expr))?.takeIf { !it.isNaN() && !it.isInfinite() }?.let { Eval.format(it) } else null
        val lines = ArrayList<Pair<String, Int>>()
        for (h in act.history.take(rows).reversed()) lines.add("${h.first} = ${h.second}" to ink)
        if (live != null) lines.add("= $live" to 0xFF777777.toInt())
        val shown = lines.takeLast(rows)
        var y = paper.bottom - 6f * sc - shown.size * rowH
        for ((s, col) in shown) {
            ui.text(cv, s, paper.right - 17f * sc, ui.baselineFor(y + rowH / 2f, 22f * sc, ui.light), 22f * sc, col, Paint.Align.RIGHT, maxW = paper.width() - 34f * sc, face = ui.light)
            y += rowH
        }
        // the display: the entry being typed (calc_display_pane_t1, digital font, 51 px pane, right aligned, 18 px in)
        val big = result ?: if (expr.isEmpty()) "0" else expr
        ui.text(cv, big, disp.right - 18f * sc, ui.baselineFor(disp.centerY(), 40f * sc), 40f * sc, ink, Paint.Align.RIGHT, maxW = disp.width() - 36f * sc)

        // the keypad
        val gridRight = area.right - 9f * sc
        val gridTop = area.top + 14f * sc
        val cw = 76f * sc
        val ch = 70f * sc
        for (ri in keys.indices) for (ci in keys[ri].indices) {
            val cx = gridRight - 4 * cw + ci * cw
            val cy = gridTop + ri * ch
            val rect = rects[ri][ci]
            rect.set(cx + 12f * sc, cy + 9f * sc, cx + cw - 12f * sc, cy + 9f * sc + 52f * sc)
            drawKey(cv, ui, rect, keys[ri][ci], ri == r && ci == c)
        }
    }

    private fun drawPortrait(cv: Canvas, ui: Ui, area: RectF) {
        val sc = ui.sc
        // display
        val disp = RectF(area.left + 12f * sc, area.top + 8f * sc, area.right - 12f * sc, area.top + 100f * sc)
        // the Belle calculator shows its sums on a sheet of paper
        ui.p.style = Paint.Style.FILL
        ui.p.shader = null
        ui.p.color = 0xFFE9E9E5.toInt()
        cv.drawRoundRect(disp, 8f * sc, 8f * sc, ui.p)
        ui.gfx.draw(cv, "qgn_graf_calc_results_line", RectF(disp.left + 10f * sc, disp.top + 44f * sc, disp.right - 10f * sc, disp.top + 46f * sc))
        val live = if (result == null && expr.isNotEmpty()) Eval.value(autoClose(expr))?.takeIf { !it.isNaN() && !it.isInfinite() }?.let { Eval.format(it) } else null
        val big = result ?: live ?: if (expr.isEmpty()) "0" else ""
        ui.text(cv, expr.ifEmpty { " " }, disp.right - 14f * sc, disp.top + 32f * sc, 22f * sc, if (result != null) 0xFF777777.toInt() else 0xFF1B1B1B.toInt(), Paint.Align.RIGHT, maxW = disp.width() - 28f * sc, face = ui.light)
        if (big.isNotEmpty() && big != expr) ui.text(cv, big, disp.right - 14f * sc, disp.top + 80f * sc, 40f * sc, 0xFF1B1B1B.toInt(), Paint.Align.RIGHT, maxW = disp.width() - 28f * sc)
        else if (result != null) ui.text(cv, big, disp.right - 14f * sc, disp.top + 80f * sc, 40f * sc, 0xFF1B1B1B.toInt(), Paint.Align.RIGHT, maxW = disp.width() - 28f * sc)

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
            drawKey(cv, ui, rect, k, ri == r && ci == c)
        }
    }

    private val glyphs = mapOf(
        "0" to "zero", "1" to "one", "2" to "two", "3" to "three", "4" to "four", "5" to "five", "6" to "six", "7" to "seven", "8" to "eight", "9" to "nine",
        "." to "separator", "%" to "percent", "C" to "clear", "÷" to "divide", "×" to "multiply", "−" to "subtract", "+" to "add", "=" to "equals",
    )

    /** A key in the Belle keypad style: the theme's key frame with its calculator glyph on it. */
    private fun drawKey(cv: Canvas, ui: Ui, rect: RectF, k: String, selected: Boolean) {
        val sc = ui.sc
        val framed = ui.gfx.nine(cv, if (selected) "qsn_fr_keypad_button_pressed" else "qsn_fr_keypad_button_normal", rect, 24, 10f * sc)
        if (!framed) {
            ui.pushButton(cv, rect, k, selected)
            return
        }
        // operators and clear wear the app's amber / red, so the keypad reads at a glance
        val tint = when (k) { "÷", "×", "−", "+", "=" -> Belle.BLUE; "C" -> 0xFFD84545.toInt(); else -> 0 }
        if (tint != 0) {
            ui.p.style = Paint.Style.FILL
            ui.p.shader = android.graphics.LinearGradient(0f, rect.top, 0f, rect.bottom, ui.al(Belle.BLUE_LIGHT.takeIf { tint == Belle.BLUE } ?: tint, 190), ui.al(tint, 190), android.graphics.Shader.TileMode.CLAMP)
            cv.drawRoundRect(rect.left + 2f * sc, rect.top + 2f * sc, rect.right - 2f * sc, rect.bottom - 2f * sc, 8f * sc, 8f * sc, ui.p)
            ui.p.shader = null
        }
        val g = glyphs[k]
        if (g == null || !ui.gfx.glyph(cv, "qgn_indi_calc_$g", rect.centerX(), rect.centerY(), minOf(rect.height() * 0.62f, 40f * sc))) {
            ui.text(cv, k, rect.centerX(), ui.baselineFor(rect.centerY(), 26f * sc), 26f * sc, Color.WHITE, Paint.Align.CENTER)
        }
        if (selected) {
            ui.p.style = Paint.Style.STROKE
            ui.p.strokeWidth = 3f * sc
            ui.p.color = Belle.BLUE_LIGHT
            cv.drawRoundRect(rect.left - 1f * sc, rect.top - 1f * sc, rect.right + 1f * sc, rect.bottom + 1f * sc, 11f * sc, 11f * sc, ui.p)
            ui.p.style = Paint.Style.FILL
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
