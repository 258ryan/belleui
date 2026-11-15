package com.belleui.browser

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.belleui.kit.Act
import com.belleui.kit.Belle
import com.belleui.kit.Ic
import com.belleui.kit.ListState
import com.belleui.kit.MenuItem
import com.belleui.kit.QueryDialog
import com.belleui.kit.Row
import com.belleui.kit.Screen
import com.belleui.kit.Tool
import com.belleui.kit.Ui
import kotlin.math.max
import kotlin.math.min

class Bookmarks(private val act: BrowserActivity) {
    fun all(): MutableList<Pair<String, String>> =
        (act.prefs.getString("bookmarks", "") ?: "").split("\n").filter { it.contains("|") }.map {
            val i = it.indexOf('|')
            it.substring(0, i) to it.substring(i + 1)
        }.toMutableList()

    fun save(list: List<Pair<String, String>>) {
        act.prefs.edit().putString("bookmarks", list.joinToString("\n") { it.first.replace("|", " ").replace("\n", " ") + "|" + it.second }).apply()
    }

    fun add(title: String, url: String): Boolean {
        val l = all()
        if (l.any { it.second == url }) return false
        l.add(0, (title.ifEmpty { url }) to url)
        save(l)
        return true
    }
}

/**
 * The browsing screen. The WebView sits underneath; this screen is transparent so touches and keys the
 * shell does not use reach it. With the cursor on, the D-pad / stick moves a pointer and A clicks.
 */
class BrowserScreen(private val act: BrowserActivity) : Screen() {
    override val transparentContent get() = true
    override val title: String
        get() = act.pageTitle.ifEmpty { Uri.parse(act.pageUrl).host ?: "Web" }
    override val tickMs get() = if (act.loading) 200L else 0L

    /** 0 = jump between links (default), 1 = movable pointer, 2 = keys go straight to the page. */
    private var navMode = 0
    private var navPending = false
    private var cx = -1f
    private var cy = -1f
    private val arrow = Path()
    private val handler = Handler(Looper.getMainLooper())

    override fun tools() = listOf(
        Tool(Ic.GOTO, { act.showAddress() }),
        Tool(if (act.loading) Ic.CLOSE else Ic.REFRESH, { if (act.loading) act.web.stopLoading() else act.web.reload() }),
    )

    override fun menu(): List<MenuItem> {
        val items = ArrayList<MenuItem>()
        if (act.web.canGoForward()) items.add(MenuItem("Forward") { act.web.goForward() })
        items.add(MenuItem("Home page") { act.web.loadUrl(act.homePage) })
        items.add(MenuItem("Bookmarks") { shell.push(BookmarksScreen(act)) })
        items.add(MenuItem("Add bookmark") {
            shell.toast(if (Bookmarks(act).add(act.pageTitle, act.pageUrl)) "Bookmark added" else "Already bookmarked")
        })
        items.add(MenuItem("D-pad: " + arrayOf("links", "pointer", "page scroll")[navMode]) { navMode = (navMode + 1) % 3 })
        items.add(MenuItem("Images: " + if (act.imagesOn) "on" else "off (faster)") {
            act.imagesOn = !act.imagesOn
            act.web.reload()
        })
        items.add(MenuItem("Desktop site: " + if (act.desktop) "on" else "off") { act.applyDesktop(!act.desktop) })
        items.add(MenuItem("Share page") {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, act.pageUrl)
            act.startActivity(Intent.createChooser(i, "Share"))
        })
        items.add(MenuItem("Use this page as home") {
            act.homePage = act.pageUrl
            shell.toast("Home page set")
        })
        items.add(MenuItem("Clear browsing data") {
            shell.showOverlay(QueryDialog("Clear history, cache and cookies?", "Clear", "Cancel") { act.clearData() })
        })
        return items
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        // nothing: the WebView underneath is the content
    }

    override fun drawOver(c: Canvas, ui: Ui, r: RectF) {
        if (act.loading) {
            ui.p.style = Paint.Style.FILL
            ui.p.shader = null
            ui.p.color = Belle.BLUE_LIGHT
            c.drawRect(r.left, r.top, r.left + r.width() * act.loadProgress / 100f, r.top + 4f * ui.sc, ui.p)
        }
        if (navMode != 1 || act.addressOpen || act.fullscreenVideo) return
        if (cx < 0) {
            cx = r.centerX()
            cy = r.centerY()
        }
        cx = cx.coerceIn(r.left, r.right)
        cy = cy.coerceIn(r.top, r.bottom)
        val s = ui.sc
        arrow.reset()
        arrow.moveTo(cx, cy)
        arrow.lineTo(cx, cy + 22f * s)
        arrow.lineTo(cx + 6f * s, cy + 17f * s)
        arrow.lineTo(cx + 11f * s, cy + 28f * s)
        arrow.lineTo(cx + 15f * s, cy + 26f * s)
        arrow.lineTo(cx + 10f * s, cy + 16f * s)
        arrow.lineTo(cx + 17f * s, cy + 16f * s)
        arrow.close()
        ui.p.style = Paint.Style.FILL
        ui.p.color = Color.WHITE
        c.drawPath(arrow, ui.p)
        ui.p.style = Paint.Style.STROKE
        ui.p.strokeWidth = 1.6f * s
        ui.p.color = Color.BLACK
        c.drawPath(arrow, ui.p)
        ui.p.style = Paint.Style.FILL
    }

    private fun click() {
        val x = cx - act.web.left
        val y = cy - act.web.top
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        act.web.dispatchTouchEvent(down)
        down.recycle()
        handler.postDelayed({
            val t2 = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(t2 - 40, t2, MotionEvent.ACTION_UP, x, y, 0)
            act.web.dispatchTouchEvent(up)
            up.recycle()
        }, 40)
    }

    /** D-pad moves the focus ring between links, buttons and fields; A clicks; nothing that way = scroll. */
    private fun linkNav(a: Act): Boolean {
        val dir = when (a) {
            Act.UP -> "up"
            Act.DOWN -> "down"
            Act.LEFT -> "left"
            Act.RIGHT -> "right"
            else -> null
        }
        if (dir != null) {
            if (navPending) return true
            navPending = true
            val w = act.web
            val step = (min(shell.keyRepeat, 12) * 0.05f + 0.4f)
            act.web.evaluateJavascript("window.__belleNav ? window.__belleNav('$dir') : false") { r ->
                navPending = false
                if (r != "true") {
                    when (dir) {
                        "up" -> w.scrollBy(0, -(w.height * step).toInt())
                        "down" -> w.scrollBy(0, (w.height * step).toInt())
                        "left" -> w.scrollBy(-(w.width * step * 0.6f).toInt(), 0)
                        else -> w.scrollBy((w.width * step * 0.6f).toInt(), 0)
                    }
                }
            }
            return true
        }
        if (a == Act.OK) {
            act.web.evaluateJavascript("window.__belleNav ? window.__belleNav('click') : false") { r ->
                if (r != null && r.contains("input")) act.showWebKeyboard()
            }
            return true
        }
        return false
    }

    override fun onAct(a: Act): Boolean {
        if (act.fullscreenVideo) {
            if (a == Act.BACK) act.hideCustomView()
            return true
        }
        when (a) {
            Act.BACK -> {
                if (act.web.canGoBack()) {
                    act.web.goBack()
                    return true
                }
                return false
            }
            Act.PREV -> {
                if (act.web.canGoBack()) act.web.goBack()
                return true
            }
            Act.NEXT -> {
                if (act.web.canGoForward()) act.web.goForward()
                return true
            }
            else -> {}
        }
        if (navMode == 2) return false // D-pad and A go straight to the page
        if (navMode == 0) return linkNav(a)
        val sc = shell.ui.sc
        val step = (7f + min(shell.keyRepeat, 20) * 2f) * sc
        val top = shell.contentTop
        val bottom = shell.contentBottom
        val edge = 36f * sc
        when (a) {
            Act.LEFT -> cx -= step
            Act.RIGHT -> cx += step
            Act.UP -> {
                if (cy - step < top + edge) act.web.scrollBy(0, (-step * 2.5f).toInt())
                cy = max(top, cy - step)
            }
            Act.DOWN -> {
                if (cy + step > bottom - edge) act.web.scrollBy(0, (step * 2.5f).toInt())
                cy = min(bottom, cy + step)
            }
            Act.OK -> click()
            else -> return false
        }
        cx = cx.coerceIn(0f, shell.width.toFloat())
        return true
    }
}

class BookmarksScreen(private val act: BrowserActivity) : Screen() {
    override val lightOk: Boolean get() = true
    override val title get() = "Bookmarks"
    private val list = ListState()
    private var items = Bookmarks(act).all()

    override fun menu() = listOf(
        MenuItem("Delete bookmark") {
            val b = items.getOrNull(list.sel)
            if (b != null) {
                items.remove(b)
                Bookmarks(act).save(items)
            }
        },
    )

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        if (items.isEmpty()) {
            ui.text(c, "No bookmarks", r.centerX(), ui.baselineFor(r.centerY(), 22f * ui.sc), 22f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
            return
        }
        list.draw(c, ui, r, items.size) { i -> Row(items[i].first, items[i].second, icon = ui.gfx.get("qgn_menu_browser"), iconKind = Ic.GLOBE, iconColor = 0xFF32B6C8.toInt()) }
    }

    private fun open(i: Int) {
        act.web.loadUrl(items[i].second)
        shell.pop()
    }

    override fun onAct(a: Act): Boolean {
        if (items.isEmpty()) return false
        if (a == Act.OK) {
            open(list.sel)
            return true
        }
        return list.act(a, items.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val i = list.hit(shell.ui, RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom), y, items.size)
        if (i < 0) return false
        list.sel = i
        open(i)
        return true
    }

    override fun onScroll(dy: Float) = list.scrollBy(if (dy > 0) 1 else -1, items.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
}
