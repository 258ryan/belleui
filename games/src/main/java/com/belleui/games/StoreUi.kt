package com.belleui.games

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.belleui.kit.Act
import com.belleui.kit.BelleShell
import com.belleui.kit.Cell
import com.belleui.kit.Ui
import kotlin.math.max
import kotlin.math.min

/*
 * The Nokia Store client's widgets (Qt Quick, from the Nokia Store package; sizes from its style/E6.js): the 43 px group header, the 100 px store item
 * with stars, the item header of the details page, an action item with its blue button, the search bar and the sort button bar.
 */

internal const val STORE_INK = 0xFF282828.toInt()
internal const val STORE_GREY = 0xFF666666.toInt()
internal const val STORE_BG = 0xFFF1F1F1.toInt()

/** The blue "down" look of a selected item (citem.gbackground.down: #1080DD fading to #53A4E7); its text turns white. */
internal fun storeSelBg(c: Canvas, ui: Ui, r: RectF) {
    ui.p.style = Paint.Style.FILL
    ui.p.shader = LinearGradient(0f, r.top, 0f, r.bottom, 0xFF1080DD.toInt(), 0xFF53A4E7.toInt(), Shader.TileMode.CLAMP)
    c.drawRect(r, ui.p)
    ui.p.shader = null
}

internal fun storeStars(c: Canvas, ui: Ui, x: Float, y: Float, rating: Float, scale: Float = 1.25f) {
    val sc = ui.sc
    val w = 16f * sc * scale
    val h = 13f * sc * scale
    val n = Math.round(rating.coerceIn(0f, 1f) * 5f)
    for (i in 0 until 5) {
        val l = x + i * (w + 3f * sc)
        ui.gfx.draw(c, if (i < n) "ns_star" else "ns_star_bg", RectF(l, y, l + w, y + h))
    }
}

/** Text that stays readable on the blue selection: white, whatever the page theme does to colours. */
private inline fun Ui.sel(on: Boolean, block: () -> Unit) {
    if (on) onDark { block() } else block()
}

/** StoreItem: 100 px, a 76 px icon, a 30 px title, a 24 px light grey line, the stars along the bottom and a note on the right. */
internal fun drawStoreItem(c: Canvas, ui: Ui, r: RectF, g: Game, focused: Boolean, act: GamesActivity, shell: BelleShell, info: String, right: String) {
    val sc = ui.sc
    if (focused) storeSelBg(c, ui, r)
    val box = RectF(r.left + 8f * sc, r.centerY() - 38f * sc, r.left + 84f * sc, r.centerY() + 38f * sc)
    drawArt(c, ui, act, shell, g, box)
    val x = r.left + 100f * sc
    val maxW = r.right - x - 14f * sc
    val white = 0xFFFFFFFF.toInt()
    val rw = if (right.isEmpty()) 0f else ui.measure(right, 24f * sc, ui.light) + 10f * sc
    ui.sel(focused) {
        ui.text(c, g.title, x, r.top + 31f * sc, 30f * sc, if (focused) white else STORE_INK, maxW = maxW)
        ui.text(c, info, x, r.top + 59f * sc, 24f * sc, if (focused) white else STORE_GREY, maxW = maxW - rw, face = ui.light)
        if (right.isNotEmpty()) ui.text(c, right, r.right - 14f * sc, r.top + 59f * sc, 24f * sc, if (focused) white else STORE_GREY, Paint.Align.RIGHT, face = ui.light)
    }
    val rating = g.rep.meta?.rating ?: 0f
    if (rating > 0f) storeStars(c, ui, x, r.bottom - 12f * sc - 16f * sc, rating)
    if (!focused) ui.fill(c, RectF(r.left, r.bottom - 1f, r.right, r.bottom), 0xFFD9D9D9.toInt())
}

/** SearchBar: 82 px, the header background, a white input with the magnifier and the query (or "Search"), and the blue go button. */
internal fun drawStoreSearchBar(c: Canvas, ui: Ui, r: RectF, query: String) {
    val sc = ui.sc
    ui.nine(c, "ns_header_bg", r, 10, 10f)
    val input = RectF(r.left + 10f * sc, r.centerY() - 29f * sc, r.right - 10f * sc - 58f * sc, r.centerY() + 29f * sc)
    ui.p.style = Paint.Style.FILL
    ui.p.color = 0xFFFFFFFF.toInt()
    c.drawRoundRect(input, 12f * sc, 12f * sc, ui.p)
    ui.p.style = Paint.Style.STROKE
    ui.p.strokeWidth = 1f * sc
    ui.p.color = 0xFFC4C4C4.toInt()
    c.drawRoundRect(input, 12f * sc, 12f * sc, ui.p)
    ui.p.style = Paint.Style.FILL
    ui.gfx.draw(c, "ns_search", RectF(input.left + 10f * sc, input.centerY() - 15f * sc, input.left + 40f * sc, input.centerY() + 15f * sc))
    val shown = query.ifBlank { "Search games" }
    ui.text(c, shown, input.left + 50f * sc, ui.baselineFor(input.centerY(), 24f * sc, ui.light), 24f * sc, if (query.isBlank()) 0xFF999999.toInt() else STORE_INK, maxW = input.width() - 60f * sc, face = ui.light)
    val btn = RectF(r.right - 10f * sc - 52f * sc, r.centerY() - 26f * sc, r.right - 10f * sc, r.centerY() + 26f * sc)
    ui.nine(c, "ns_search_btn", btn, 20, 20f)
    ui.gfx.draw(c, "ns_search_go", RectF(btn.centerX() - 10f * sc, btn.centerY() - 10f * sc, btn.centerX() + 10f * sc, btn.centerY() + 10f * sc))
}

/** ButtonBar: the Store's row of pill buttons (left / middle / right pieces), the active one pressed in, 24 px labels. */
internal fun drawStoreButtonBar(c: Canvas, ui: Ui, r: RectF, labels: List<String>, active: Int) {
    val sc = ui.sc
    val n = labels.size
    val w = r.width() / n
    for (i in 0 until n) {
        val seg = RectF(r.left + i * w, r.top, r.left + (i + 1) * w, r.bottom)
        val piece = if (n == 1) "left" else if (i == 0) "left" else if (i == n - 1) "right" else "mid"
        val state = if (i == active) "down" else "up"
        if (!ui.nine(c, "ns_tab_${piece}_$state", seg, 25, 20f * sc)) ui.fill(c, seg, if (i == active) 0xFFD0D0D0.toInt() else 0xFFF8F8F8.toInt())
        ui.text(c, labels[i], seg.centerX(), ui.baselineFor(seg.centerY(), 22f * sc), 22f * sc, STORE_INK, Paint.Align.CENTER, maxW = seg.width() - 12f * sc)
    }
}

/** GroupHeader: 43 px of grey gradient with the heading in 24 px. */
internal class StoreHeaderCell(private val text: String) : Cell() {
    override val focusable get() = false
    override fun height(ui: Ui, w: Float) = 43f * ui.sc
    override fun draw(c: Canvas, ui: Ui, r: RectF, focused: Boolean, pressed: Boolean) {
        ui.p.style = Paint.Style.FILL
        ui.p.shader = LinearGradient(0f, r.top, 0f, r.bottom, 0xFFDFDFDF.toInt(), 0xFFCFCFCF.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(r, ui.p)
        ui.p.shader = null
        ui.text(c, text, r.left + 12f * ui.sc, ui.baselineFor(r.centerY(), 24f * ui.sc), 24f * ui.sc, STORE_INK, maxW = r.width() - 24f * ui.sc)
    }
}

/** ItemHeader of the details page: a 96 px icon on the left, the large stars, "By ...", "System > Genre" and a note, 130 px tall. */
internal class StoreItemHeaderCell(private val act: GamesActivity, private val shell: BelleShell, private val game: Game, private val lines: List<String>) : Cell() {
    override val focusable get() = false
    override fun height(ui: Ui, w: Float) = 130f * ui.sc
    override fun draw(c: Canvas, ui: Ui, r: RectF, focused: Boolean, pressed: Boolean) {
        val sc = ui.sc
        val box = RectF(r.left + 8f * sc + 8f * sc, r.top + 8f * sc, r.left + 8f * sc + 8f * sc + 96f * sc, r.top + 8f * sc + 96f * sc)
        drawArt(c, ui, act, shell, game, box)
        val x = r.left + 8f * sc + 112f * sc + 4f * sc
        val maxW = r.right - x - 14f * sc
        var y = r.top + 8f * sc
        val rating = game.rep.meta?.rating ?: 0f
        storeStars(c, ui, x, y, rating, 1.6f)
        y += 13f * sc * 1.6f + 3f * sc + 24f * sc
        for (l in lines.take(3)) {
            ui.text(c, l, x, y, 24f * sc, STORE_GREY, maxW = maxW, face = ui.light)
            y += 28f * sc
        }
        ui.fill(c, RectF(r.left, r.bottom - 1f, r.right, r.bottom), 0xFFD9D9D9.toInt())
    }
}

/** ActionItem: 82 px, a 30 px label with a 20 px note under it and the Store's blue (or white secondary) button on the right. */
internal class StoreActionCell(
    private val label: String,
    private val note: String,
    private val button: String,
    private val secondary: Boolean,
    private val action: () -> Unit,
) : Cell() {
    private val btn = RectF()
    override fun height(ui: Ui, w: Float) = 82f * ui.sc
    override fun draw(c: Canvas, ui: Ui, r: RectF, focused: Boolean, pressed: Boolean) {
        val sc = ui.sc
        if (focused) storeSelBg(c, ui, r)
        val bw = 221f * sc
        btn.set(r.right - 14f * sc - bw, r.centerY() - 22f * sc, r.right - 14f * sc, r.centerY() + 22f * sc)
        val maxW = btn.left - r.left - 24f * sc
        val white = 0xFFFFFFFF.toInt()
        ui.sel(focused) {
            ui.text(c, label, r.left + 14f * sc, ui.baselineFor(r.centerY() - (if (note.isEmpty()) 0f else 10f) * sc, 30f * sc), 30f * sc, if (focused) white else STORE_INK, maxW = maxW)
            if (note.isNotEmpty()) ui.text(c, note, r.left + 14f * sc, ui.baselineFor(r.centerY() + 18f * sc, 20f * sc, ui.light), 20f * sc, if (focused) white else STORE_GREY, maxW = maxW, face = ui.light)
        }
        ui.nine(c, if (secondary) "ns_btn_secondary" else "ns_btn_primary", btn, 20, 20f * sc)
        ui.onDark {
            if (secondary) {
                ui.p.color = STORE_INK
                ui.text(c, button, btn.centerX(), ui.baselineFor(btn.centerY(), 24f * sc, ui.medium), 24f * sc, STORE_INK, Paint.Align.CENTER, maxW = btn.width() - 12f * sc, face = ui.medium)
            } else {
                ui.text(c, button, btn.centerX(), ui.baselineFor(btn.centerY(), 24f * sc, ui.medium), 24f * sc, 0xFFF1F0F1.toInt(), Paint.Align.CENTER, maxW = btn.width() - 12f * sc, face = ui.medium)
            }
        }
        if (!focused) ui.fill(c, RectF(r.left, r.bottom - 1f, r.right, r.bottom), 0xFFD9D9D9.toInt())
    }
    override fun onClick() = action()
    override fun onTapAt(x: Float, y: Float, r: RectF): Boolean = false
}

/** A paragraph of body text (the description) in 24 px light grey-black, wrapped to the page. */
internal class StoreBodyCell(private val text: String) : Cell() {
    override val focusable get() = false
    private var lines: List<String> = emptyList()
    private var forW = -1f

    private fun wrap(ui: Ui, w: Float) {
        if (forW == w && lines.isNotEmpty()) return
        forW = w
        val out = ArrayList<String>()
        for (para in text.split("\n")) {
            var line = ""
            for (word in para.trim().split(Regex("\\s+"))) {
                val t = if (line.isEmpty()) word else "$line $word"
                if (line.isEmpty() || ui.measure(t, 24f * ui.sc, ui.light) <= w) line = t else { out.add(line); line = word }
            }
            out.add(line)
        }
        lines = out
    }

    override fun height(ui: Ui, w: Float): Float {
        wrap(ui, w - 28f * ui.sc)
        return lines.size * 30f * ui.sc + 24f * ui.sc
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF, focused: Boolean, pressed: Boolean) {
        val sc = ui.sc
        wrap(ui, r.width() - 28f * sc)
        var y = r.top + 12f * sc + 24f * sc
        for (l in lines) {
            ui.text(c, l, r.left + 14f * sc, y, 24f * sc, STORE_INK, face = ui.light)
            y += 30f * sc
        }
    }
}

/** A "label ........ value" row of the details list. */
internal class StoreInfoCell(private val label: String, private val value: String) : Cell() {
    override val focusable get() = false
    override fun height(ui: Ui, w: Float) = 56f * ui.sc
    override fun draw(c: Canvas, ui: Ui, r: RectF, focused: Boolean, pressed: Boolean) {
        val sc = ui.sc
        ui.text(c, label, r.left + 14f * sc, ui.baselineFor(r.centerY(), 24f * sc, ui.light), 24f * sc, STORE_GREY, face = ui.light)
        val lw = ui.measure(label, 24f * sc, ui.light)
        ui.text(c, value, r.right - 14f * sc, ui.baselineFor(r.centerY(), 24f * sc), 24f * sc, STORE_INK, Paint.Align.RIGHT, maxW = r.width() - lw - 50f * sc)
        ui.fill(c, RectF(r.left + 4f * sc, r.bottom - 1f, r.right - 4f * sc, r.bottom), 0xFFE0E0E0.toInt())
    }
}

/** PivotBar as a plain list row (Library, Emulators): an icon frame, a 30 px label, a 24 px light note and the arrow; the blue selection when focused. */
internal fun drawStorePivotRow(c: Canvas, ui: Ui, r: RectF, title: String, note: String?, color: Int, kind: Int, focused: Boolean, dim: Boolean, arrow: Boolean) {
    val sc = ui.sc
    if (focused) storeSelBg(c, ui, r)
    val box = RectF(r.left + 8f * sc, r.centerY() - 32f * sc, r.left + 72f * sc, r.centerY() + 32f * sc)
    ui.iconFrame(c, box, color, kind)
    val x = r.left + 86f * sc
    val maxW = r.right - x - (if (arrow) 44f else 14f) * sc
    val white = 0xFFFFFFFF.toInt()
    val ink = if (dim) 0xFF9A9A9A.toInt() else STORE_INK
    ui.sel(focused) {
        if (note.isNullOrEmpty()) {
            ui.text(c, title, x, ui.baselineFor(r.centerY(), 30f * sc), 30f * sc, if (focused) white else ink, maxW = maxW)
        } else {
            ui.text(c, title, x, ui.baselineFor(r.centerY() - 14f * sc, 30f * sc), 30f * sc, if (focused) white else ink, maxW = maxW)
            ui.text(c, note, x, ui.baselineFor(r.centerY() + 20f * sc, 24f * sc, ui.light), 24f * sc, if (focused) white else STORE_GREY, maxW = maxW, face = ui.light)
        }
    }
    if (arrow) ui.gfx.draw(c, "ns_pivot_arrow", RectF(r.right - 14f * sc - 10f * sc, r.centerY() - 9f * sc, r.right - 14f * sc, r.centerY() + 9f * sc))
    if (!focused) ui.fill(c, RectF(r.left, r.bottom - 1f, r.right, r.bottom), 0xFFD9D9D9.toInt())
}
