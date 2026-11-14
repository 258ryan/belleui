package com.belleui.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The whole launcher UI, drawn on one canvas and driven by gamepad keys.
 *
 * Pages (L1/R1 or D-pad past the edge to switch):
 *   0  Home    – Belle-style clock + battery widget, 4 favourite tiles
 *   1  Menu    – Symbian-style 4x3 app grid
 *   2  Recent  – Asha-style list of recently opened apps
 */
class LauncherView(private val act: Activity) : View(act) {

    private enum class Act { UP, DOWN, LEFT, RIGHT, OK, BACK, OPTIONS, SETTINGS, MENU, PREV, NEXT }

    private class PopupItem(val label: String, val action: () -> Unit)
    private class Popup(val title: String, val items: List<PopupItem>, var sel: Int = 0)

    private class AnimRect {
        val cur = RectF()
        val tgt = RectF()
        private var init = false
        fun target(r: RectF) {
            tgt.set(r)
            if (!init) {
                cur.set(r)
                init = true
            }
        }

        /** @return true while still moving */
        fun step(k: Float): Boolean {
            cur.left += (tgt.left - cur.left) * k
            cur.top += (tgt.top - cur.top) * k
            cur.right += (tgt.right - cur.right) * k
            cur.bottom += (tgt.bottom - cur.bottom) * k
            val moving = abs(tgt.left - cur.left) + abs(tgt.top - cur.top) +
                    abs(tgt.right - cur.right) + abs(tgt.bottom - cur.bottom) > 1f
            if (!moving) cur.set(tgt)
            return moving
        }
    }

    // ---- data ------------------------------------------------------------------
    private val repo = AppRepo(act)
    private var theme = Themes.all[repo.themeIndex]
    private var menuApps: List<AppEntry> = emptyList()
    private var favs: List<AppEntry> = emptyList()
    private var recents: List<AppEntry> = emptyList()
    private var loaded = false

    // ---- ui state ----------------------------------------------------------------
    private var page = 0
    private var pageAnim = 0f
    private var homeSel = 0
    private var menuSel = 0
    private var menuTop = 0
    private var menuTopAnim = 0f
    private var recentSel = 0
    private var recentTop = 0
    private var recentTopAnim = 0f
    private var reorder = false
    private var popup: Popup? = null
    private var toast = ""
    private var toastUntil = 0L
    private val homeFocus = AnimRect()
    private val menuFocus = AnimRect()
    private val recentFocus = AnimRect()

    // ---- status ------------------------------------------------------------------
    private var batteryPct = 100
    private var charging = false
    private var wifi = false
    private var clockStr = ""
    private var ampmStr = ""
    private var dateStr = ""

    // ---- layout (px) ---------------------------------------------------------------
    private var sc = 1f
    private var statusH = 30f
    private var softH = 44f
    private var cTop = 30f
    private var cBot = 436f

    // ---- paints ----------------------------------------------------------------------
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rf = RectF()
    private val tmp = RectF()
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private var bgShader: Shader? = null
    private var glow1: Shader? = null
    private var glow2: Shader? = null
    private var lastNs = System.nanoTime()
    private var scrollAccum = 0f

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) readBattery(intent)
            readWifi()
            refreshClock()
            invalidate()
        }
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reloadApps()
    }

    private val gestures = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scrollAccum = 0f
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (popup == null && onTap(e.x, e.y, selectOnly = true)) perform(Act.OPTIONS)
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (popup == null && abs(vx) > abs(vy) && abs(vx) > 600f) {
                setPage(page + if (vx < 0) 1 else -1)
                return true
            }
            return false
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (popup != null) return false
            scrollAccum += dy
            val step = 60f * sc
            while (abs(scrollAccum) > step) {
                val d = if (scrollAccum > 0) 1 else -1
                scrollAccum -= d * step
                if (page == 1) {
                    menuTop = (menuTop + d).coerceIn(0, max(0, (menuApps.size + 3) / 4 - 3))
                } else if (page == 2) {
                    recentTop = (recentTop + d).coerceIn(0, max(0, recents.size - recentVisible()))
                }
            }
            invalidate()
            return true
        }
    })

    init {
        isFocusable = true
        refreshClock()
        readWifi()
    }

    // =====================================================================================
    // lifecycle / data
    // =====================================================================================

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            @Suppress("DEPRECATION")
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        val sticky = act.registerReceiver(statusReceiver, f)
        if (sticky != null) readBattery(sticky)
        val pf = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        act.registerReceiver(packageReceiver, pf)
        reloadApps()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try {
            act.unregisterReceiver(statusReceiver)
            act.unregisterReceiver(packageReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    fun onResumed() {
        refreshClock()
        readWifi()
        if (loaded) refreshLists()
        invalidate()
    }

    fun goHome() {
        popup = null
        reorder = false
        page = 0
        homeSel = 0
        refreshClock()
        invalidate()
    }

    private fun reloadApps() {
        Thread {
            repo.reload()
            post {
                loaded = true
                refreshLists()
                invalidate()
            }
        }.start()
    }

    private fun refreshLists() {
        menuApps = repo.menuApps(repo.showHidden)
        favs = repo.favoriteEntries()
        recents = repo.recentEntries()
        menuSel = menuSel.coerceIn(0, max(0, menuApps.size - 1))
        recentSel = recentSel.coerceIn(0, max(0, recents.size - 1))
        ensureVisible()
    }

    private fun readBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) batteryPct = level * 100 / scale
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun readWifi() {
        wifi = try {
            val cm = act.getSystemService(ConnectivityManager::class.java)
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (_: Exception) {
            false
        }
    }

    private fun refreshClock() {
        val now = System.currentTimeMillis()
        if (DateFormat.is24HourFormat(act)) {
            clockStr = DateFormat.format("HH:mm", now).toString()
            ampmStr = ""
        } else {
            clockStr = DateFormat.format("h:mm", now).toString()
            ampmStr = DateFormat.format("a", now).toString()
        }
        dateStr = DateFormat.format("EEEE, d MMMM", now).toString()
    }

    // =====================================================================================
    // input
    // =====================================================================================

    private fun actionFor(code: Int): Act? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> Act.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Act.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Act.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Act.RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Act.OK
        KeyEvent.KEYCODE_BUTTON_A -> if (repo.swapPad) Act.BACK else Act.OK
        KeyEvent.KEYCODE_BUTTON_B -> if (repo.swapPad) Act.OK else Act.BACK
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> Act.BACK
        KeyEvent.KEYCODE_BUTTON_X -> if (repo.swapPad) Act.SETTINGS else Act.OPTIONS
        KeyEvent.KEYCODE_BUTTON_Y -> if (repo.swapPad) Act.OPTIONS else Act.SETTINGS
        KeyEvent.KEYCODE_MENU -> Act.OPTIONS
        KeyEvent.KEYCODE_BUTTON_SELECT -> Act.SETTINGS
        KeyEvent.KEYCODE_BUTTON_START -> Act.MENU
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_PAGE_UP -> Act.PREV
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_PAGE_DOWN -> Act.NEXT
        else -> null
    }

    fun handleKeyEvent(e: KeyEvent): Boolean {
        val a = actionFor(e.keyCode) ?: return false
        if (e.action == KeyEvent.ACTION_DOWN) {
            val directional = a == Act.UP || a == Act.DOWN || a == Act.LEFT || a == Act.RIGHT
            if (e.repeatCount == 0 || directional) perform(a)
        }
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        gestures.onTouchEvent(event) || super.onTouchEvent(event)

    private fun perform(a: Act) {
        val pu = popup
        if (pu != null) {
            val n = pu.items.size
            when (a) {
                Act.UP -> pu.sel = (pu.sel - 1 + n) % n
                Act.DOWN -> pu.sel = (pu.sel + 1) % n
                Act.OK -> {
                    val item = pu.items[pu.sel]
                    popup = null
                    item.action()
                }
                Act.BACK, Act.OPTIONS, Act.SETTINGS -> popup = null
                else -> {}
            }
            invalidate()
            return
        }
        if (reorder) {
            when (a) {
                Act.LEFT -> moveBy(-1)
                Act.RIGHT -> moveBy(1)
                Act.UP -> moveBy(-4)
                Act.DOWN -> moveBy(4)
                Act.OK, Act.BACK, Act.OPTIONS -> {
                    reorder = false
                    showToast("Order saved")
                }
                else -> {}
            }
            invalidate()
            return
        }
        when (a) {
            Act.PREV -> setPage(page - 1)
            Act.NEXT -> setPage(page + 1)
            Act.MENU -> setPage(if (page == 1) 0 else 1)
            Act.SETTINGS -> openSettings(0)
            Act.OPTIONS -> openOptions()
            Act.BACK -> setPage(if (page == 0) 1 else 0)
            else -> navigate(a)
        }
        invalidate()
    }

    private fun setPage(p: Int) {
        page = p.coerceIn(0, 2)
    }

    private fun navigate(a: Act) {
        when (page) {
            0 -> when (a) {
                Act.LEFT -> if (homeSel > 0) homeSel--
                Act.RIGHT -> if (homeSel < 3) homeSel++ else setPage(1)
                Act.OK -> {
                    val app = favs.getOrNull(homeSel)
                    if (app != null) {
                        launch(app)
                    } else {
                        setPage(1)
                        showToast("Pick an app, then Options > Add to home")
                    }
                }
                else -> {}
            }
            1 -> {
                val n = menuApps.size
                if (n == 0) {
                    if (a == Act.LEFT) setPage(0) else if (a == Act.RIGHT) setPage(2)
                    return
                }
                when (a) {
                    Act.LEFT -> if (menuSel % 4 == 0) setPage(0) else menuSel--
                    Act.RIGHT -> if (menuSel % 4 == 3 || menuSel == n - 1) setPage(2) else menuSel++
                    Act.UP -> if (menuSel >= 4) menuSel -= 4
                    Act.DOWN -> if (menuSel + 4 < n) menuSel += 4 else if (menuSel / 4 < (n - 1) / 4) menuSel = n - 1
                    Act.OK -> launch(menuApps[menuSel])
                    else -> {}
                }
            }
            2 -> when (a) {
                Act.UP -> if (recentSel > 0) recentSel--
                Act.DOWN -> if (recentSel < recents.size - 1) recentSel++
                Act.LEFT -> setPage(1)
                Act.OK -> recents.getOrNull(recentSel)?.let { launch(it) }
                else -> {}
            }
        }
        ensureVisible()
    }

    private fun moveBy(d: Int) {
        if (menuApps.isEmpty()) return
        val to = (menuSel + d).coerceIn(0, menuApps.size - 1)
        if (to == menuSel) return
        repo.moveInOrder(menuApps.map { it.key }, menuSel, to)
        menuApps = repo.menuApps(repo.showHidden)
        menuSel = to
        ensureVisible()
    }

    private fun ensureVisible() {
        val row = menuSel / 4
        if (row < menuTop) menuTop = row else if (row > menuTop + 2) menuTop = row - 2
        menuTop = menuTop.coerceIn(0, max(0, (menuApps.size + 3) / 4 - 3))
        val vis = recentVisible()
        if (recentSel < recentTop) recentTop = recentSel else if (recentSel >= recentTop + vis) recentTop = recentSel - vis + 1
        recentTop = recentTop.coerceIn(0, max(0, recents.size - vis))
    }

    /** Touch: returns true if something was hit (selection is moved there). */
    private fun onTap(x: Float, y: Float, selectOnly: Boolean = false): Boolean {
        val pu = popup
        if (pu != null) {
            popupRect(pu, tmp)
            if (!tmp.contains(x, y)) {
                popup = null
            } else {
                val row = ((y - tmp.top - POPUP_TITLE * sc) / (POPUP_ROW * sc)).toInt()
                if (row in pu.items.indices) {
                    pu.sel = row
                    perform(Act.OK)
                }
            }
            invalidate()
            return true
        }
        if (abs(pageAnim - page) > 0.05f) return false
        if (y > cBot) { // soft-key bar
            if (!selectOnly) perform(if (x < width / 3f) Act.OPTIONS else if (x < width * 2f / 3f) Act.OK else Act.BACK)
            return true
        }
        if (y < cTop) return false
        var hit = false
        when (page) {
            0 -> for (i in 0..3) if (homeTileRect(i, tmp).contains(x, y)) {
                homeSel = i
                hit = favs.getOrNull(i) != null
                if (!selectOnly) perform(Act.OK)
                break
            }
            1 -> {
                val cw = width / 4f
                val ch = (cBot - cTop) / 3f
                val idx = (((y - cTop) / ch + menuTop).toInt()) * 4 + (x / cw).toInt()
                if (idx in menuApps.indices) {
                    menuSel = idx
                    hit = true
                    if (!selectOnly) perform(Act.OK)
                }
            }
            2 -> {
                val idx = ((y - (cTop + HEADER * sc)) / (ROW * sc)).toInt() + recentTop
                if (idx in recents.indices && y > cTop + HEADER * sc) {
                    recentSel = idx
                    hit = true
                    if (!selectOnly) perform(Act.OK)
                }
            }
        }
        invalidate()
        return hit
    }

    // =====================================================================================
    // actions
    // =====================================================================================

    private fun launch(app: AppEntry) {
        val i = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            act.startActivity(i)
            repo.pushRecent(app.key)
            recents = repo.recentEntries()
        } catch (_: Exception) {
            showToast("Can't open ${app.label}")
        }
    }

    private fun tryStart(i: Intent) {
        try {
            act.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            showToast("Not available")
        }
    }

    private fun selectedApp(): AppEntry? = when (page) {
        0 -> favs.getOrNull(homeSel)
        1 -> menuApps.getOrNull(menuSel)
        else -> recents.getOrNull(recentSel)
    }

    private fun okLetter() = if (repo.swapPad) "B" else "A"
    private fun backLetter() = if (repo.swapPad) "A" else "B"
    private fun optLetter() = if (repo.swapPad) "Y" else "X"

    private fun openOptions() {
        val app = selectedApp()
        if (app == null) {
            openSettings(0)
            return
        }
        val items = ArrayList<PopupItem>()
        items += PopupItem("Open") { launch(app) }
        if (page == 1) items += PopupItem("Move") {
            reorder = true
            showToast("D-pad moves, ${okLetter()} drops")
        }
        val fav = repo.isFavorite(app.key)
        items += PopupItem(if (fav) "Remove from home" else "Add to home") {
            if (fav) repo.removeFavorite(app.key)
            else if (!repo.addFavorite(app.key)) showToast("Home is full (${AppRepo.MAX_FAVS} tiles)")
            refreshLists()
        }
        if (page == 1) {
            val hidden = repo.isHidden(app.key)
            items += PopupItem(if (hidden) "Unhide" else "Hide") {
                repo.setHidden(app.key, !hidden)
                refreshLists()
            }
        }
        items += PopupItem("App info") {
            tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.component.packageName}")))
        }
        items += PopupItem("Uninstall") {
            tryStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.component.packageName}")))
        }
        popup = Popup(app.label, items)
    }

    private fun openSettings(sel: Int) {
        val items = listOf(
            PopupItem("Theme: ${theme.name}") {
                val next = (repo.themeIndex + 1) % Themes.all.size
                repo.themeIndex = next
                theme = Themes.all[next]
                buildBackground()
                openSettings(0)
            },
            PopupItem("Buttons: " + if (repo.swapPad) "Nintendo (B = OK)" else "Xbox (A = OK)") {
                repo.swapPad = !repo.swapPad
                openSettings(1)
            },
            PopupItem("Hidden apps: " + if (repo.showHidden) "shown" else "hidden") {
                repo.showHidden = !repo.showHidden
                refreshLists()
                openSettings(2)
            },
            PopupItem("Android settings") { tryStart(Intent(Settings.ACTION_SETTINGS)) },
            PopupItem("About BelleUI") { showToast("BelleUI 0.1 - Symbian x Belle x Asha") },
        )
        popup = Popup("Settings", items, sel)
    }

    private fun showToast(s: String) {
        toast = s
        toastUntil = System.currentTimeMillis() + 2200
    }

    // =====================================================================================
    // layout helpers
    // =====================================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sc = min(w / 640f, h / 480f)
        statusH = 30f * sc
        softH = 44f * sc
        cTop = statusH
        cBot = h - softH
        buildBackground()
        ensureVisible()
    }

    private fun buildBackground() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        bgShader = LinearGradient(0f, 0f, 0f, h, theme.bgTop, theme.bgBottom, Shader.TileMode.CLAMP)
        glow1 = RadialGradient(w * 0.9f, h * 0.15f, w * 0.7f, al(theme.accent, 70), al(theme.accent, 0), Shader.TileMode.CLAMP)
        glow2 = RadialGradient(w * 0.05f, h * 0.95f, w * 0.6f, al(theme.accent, 45), al(theme.accent, 0), Shader.TileMode.CLAMP)
    }

    private fun homeTileRect(i: Int, out: RectF): RectF {
        val m = 24f * sc
        val gap = 14f * sc
        val tw = (width - 2 * m - 3 * gap) / 4f
        val th = 146f * sc
        val top = cBot - th - 16f * sc
        out.set(m + i * (tw + gap), top, m + i * (tw + gap) + tw, top + th)
        return out
    }

    private fun menuCellRect(idx: Int, top: Float, out: RectF): RectF {
        val cw = width / 4f
        val ch = (cBot - cTop) / 3f
        val x = (idx % 4) * cw
        val y = cTop + ((idx / 4) - top) * ch
        out.set(x, y, x + cw, y + ch)
        return out
    }

    private fun recentRowRect(i: Int, top: Float, out: RectF): RectF {
        val y0 = cTop + HEADER * sc
        out.set(0f, y0 + (i - top) * ROW * sc, width.toFloat(), y0 + (i - top + 1) * ROW * sc)
        return out
    }

    private fun recentVisible(): Int = max(1, ((cBot - cTop - HEADER * sc) / (ROW * sc)).toInt())

    private fun popupRect(pu: Popup, out: RectF): RectF {
        val bw = 380f * sc
        val bh = (POPUP_TITLE + POPUP_ROW * pu.items.size + 8f) * sc
        val x = (width - bw) / 2f
        val y = cTop + max(0f, (cBot - cTop - bh) / 2f)
        out.set(x, y, x + bw, y + bh)
        return out
    }

    // =====================================================================================
    // drawing
    // =====================================================================================

    private fun al(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    private fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.LEFT, maxW: Float = 0f, face: Typeface = Typeface.DEFAULT,
    ) {
        tp.textSize = size
        tp.color = color
        tp.textAlign = align
        tp.typeface = face
        val t = if (maxW > 0f) TextUtils.ellipsize(s, tp, maxW, TextUtils.TruncateAt.END).toString() else s
        c.drawText(t, x, y, tp)
    }

    private fun measure(s: String, size: Float, face: Typeface): Float {
        tp.textSize = size
        tp.typeface = face
        return tp.measureText(s)
    }

    private fun drawIcon(c: Canvas, bmp: Bitmap, cx: Float, cy: Float, size: Float, alpha: Int = 255) {
        rf.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        iconPaint.alpha = alpha
        c.drawBitmap(bmp, null, rf, iconPaint)
    }

    private fun glass(c: Canvas, r: RectF, radius: Float) {
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(r.left, r.top, r.left, r.bottom, al(Color.WHITE, 46), al(Color.WHITE, 12), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * sc
        p.color = al(Color.WHITE, 55)
        c.drawRoundRect(r, radius, radius, p)
        p.style = Paint.Style.FILL
    }

    private fun drawFocus(c: Canvas, r: RectF, radius: Float) {
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(r.left, r.top, r.left, r.bottom, al(theme.accentLight, 235), al(theme.accent, 215), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, p)
        p.shader = null
        c.save()
        c.clipRect(r.left, r.top, r.right, r.top + r.height() * 0.5f)
        p.color = al(Color.WHITE, 60)
        c.drawRoundRect(r, radius, radius, p)
        c.restore()
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f * sc
        p.color = al(Color.WHITE, 170)
        c.drawRoundRect(r, radius, radius, p)
        p.style = Paint.Style.FILL
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = System.nanoTime()
        var dt = (now - lastNs) / 1e9f
        lastNs = now
        if (dt > 0.05f || dt <= 0f) dt = 0.016f
        val k = 1f - exp(-dt * 15f)
        var busy = false

        // ---- animation steps ----
        pageAnim += (page - pageAnim) * k
        if (abs(page - pageAnim) < 0.004f) pageAnim = page.toFloat() else busy = true
        menuTopAnim += (menuTop - menuTopAnim) * k
        if (abs(menuTop - menuTopAnim) < 0.004f) menuTopAnim = menuTop.toFloat() else busy = true
        recentTopAnim += (recentTop - recentTopAnim) * k
        if (abs(recentTop - recentTopAnim) < 0.004f) recentTopAnim = recentTop.toFloat() else busy = true

        homeTileRect(homeSel, tmp)
        tmp.inset(-5f * sc, -5f * sc)
        homeFocus.target(tmp)
        menuCellRect(menuSel, menuTop.toFloat(), tmp)
        tmp.inset(8f * sc, 5f * sc)
        menuFocus.target(tmp)
        recentRowRect(recentSel, recentTop.toFloat(), tmp)
        tmp.inset(6f * sc, 2f * sc)
        recentFocus.target(tmp)
        if (homeFocus.step(k)) busy = true
        if (menuFocus.step(k)) busy = true
        if (recentFocus.step(k)) busy = true

        // ---- background ----
        p.style = Paint.Style.FILL
        p.shader = bgShader
        c.drawRect(0f, 0f, w, h, p)
        p.shader = glow1
        c.drawRect(0f, 0f, w, h, p)
        p.shader = glow2
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null

        // ---- pages ----
        for (i in 0..2) {
            val d = i - pageAnim
            if (abs(d) >= 1f) continue
            c.save()
            c.clipRect(0f, cTop, w, cBot)
            c.translate(d * w, 0f)
            when (i) {
                0 -> drawHome(c)
                1 -> drawMenu(c)
                else -> drawRecent(c)
            }
            c.restore()
        }

        drawStatusBar(c, w)
        drawSoftKeys(c, w, h)
        popup?.let { drawPopup(c, it, w) }

        if (System.currentTimeMillis() < toastUntil) {
            drawToast(c, w)
            busy = true
        }
        if (busy) postInvalidateOnAnimation()
    }

    // ---- page 0: home ----------------------------------------------------------------

    private fun drawHome(c: Canvas) {
        val w = width.toFloat()
        // clock
        val tsz = 92f * sc
        val base = cTop + 104f * sc
        text(c, clockStr, 30f * sc, base, tsz, Color.WHITE, face = light)
        if (ampmStr.isNotEmpty()) {
            val tw = measure(clockStr, tsz, light)
            text(c, ampmStr, 30f * sc + tw + 8f * sc, base, 26f * sc, theme.accentLight)
        }
        text(c, dateStr, 34f * sc, cTop + 142f * sc, 24f * sc, 0xFFB8C4D4.toInt())

        // battery widget
        rf.set(w - 218f * sc, cTop + 22f * sc, w - 24f * sc, cTop + 148f * sc)
        glass(c, rf, 14f * sc)
        text(c, "Battery", rf.left + 16f * sc, rf.top + 30f * sc, 17f * sc, 0xFFB8C4D4.toInt())
        text(c, "$batteryPct%", rf.left + 16f * sc, rf.top + 78f * sc, 46f * sc, Color.WHITE, face = light)
        val barL = rf.left + 16f * sc
        val barR = rf.right - 16f * sc
        val barT = rf.bottom - 26f * sc
        tmp.set(barL, barT, barR, barT + 8f * sc)
        p.color = al(Color.WHITE, 40)
        c.drawRoundRect(tmp, 4f * sc, 4f * sc, p)
        tmp.right = barL + (barR - barL) * batteryPct / 100f
        p.color = if (batteryPct <= 15 && !charging) 0xFFE5484D.toInt() else theme.accent
        c.drawRoundRect(tmp, 4f * sc, 4f * sc, p)
        if (charging) text(c, "charging", rf.right - 16f * sc, rf.top + 30f * sc, 15f * sc, theme.accentLight, Paint.Align.RIGHT)

        // favourites
        text(c, "FAVOURITES", 28f * sc, homeTileRect(0, tmp).top - 8f * sc, 14f * sc, al(theme.accentLight, 200), face = medium)
        for (i in 0..3) glass(c, homeTileRect(i, tmp), 14f * sc)
        drawFocus(c, homeFocus.cur, 16f * sc)
        for (i in 0..3) {
            homeTileRect(i, tmp)
            val cx = tmp.centerX()
            val app = favs.getOrNull(i)
            val sel = i == homeSel
            if (app != null) {
                drawIcon(c, app.icon, cx, tmp.top + 52f * sc, 64f * sc)
                text(c, app.label, cx, tmp.bottom - 20f * sc, 17f * sc, Color.WHITE, Paint.Align.CENTER, tmp.width() - 12f * sc)
            } else {
                text(c, "+", cx, tmp.top + 70f * sc, 48f * sc, al(Color.WHITE, if (sel) 200 else 70), Paint.Align.CENTER, face = light)
                text(c, "Empty", cx, tmp.bottom - 20f * sc, 16f * sc, al(Color.WHITE, if (sel) 220 else 100), Paint.Align.CENTER)
            }
        }
    }

    // ---- page 1: symbian menu grid -----------------------------------------------------

    private fun drawMenu(c: Canvas) {
        val w = width.toFloat()
        if (menuApps.isEmpty()) {
            text(c, if (loaded) "No apps" else "Loading...", w / 2, (cTop + cBot) / 2, 26f * sc, al(Color.WHITE, 160), Paint.Align.CENTER, face = light)
            return
        }
        drawFocus(c, menuFocus.cur, 16f * sc)
        val cw = w / 4f
        val ch = (cBot - cTop) / 3f
        val isz = 64f * sc
        for (idx in menuApps.indices) {
            menuCellRect(idx, menuTopAnim, tmp)
            if (tmp.bottom < cTop || tmp.top > cBot) continue
            val app = menuApps[idx]
            val moving = reorder && idx == menuSel
            val dim = if (repo.isHidden(app.key)) 110 else 255
            val iconTop = tmp.top + (ch - 92f * sc) / 2f
            val size = if (moving) isz * 1.2f else isz
            drawIcon(c, app.icon, tmp.centerX(), iconTop + isz / 2f - (if (moving) 6f * sc else 0f), size, dim)
            text(c, app.label, tmp.centerX(), iconTop + isz + 24f * sc, 17f * sc, al(Color.WHITE, dim), Paint.Align.CENTER, cw - 14f * sc)
        }
        val rows = (menuApps.size + 3) / 4
        if (rows > 3) {
            val trackT = cTop + 6f * sc
            val trackH = cBot - cTop - 12f * sc
            rf.set(w - 7f * sc, trackT, w - 4f * sc, trackT + trackH)
            p.color = al(Color.WHITE, 30)
            c.drawRoundRect(rf, 2f * sc, 2f * sc, p)
            val thumbH = trackH * 3f / rows
            val thumbT = trackT + (trackH - thumbH) * (menuTopAnim / (rows - 3).toFloat()).coerceIn(0f, 1f)
            rf.set(w - 7f * sc, thumbT, w - 4f * sc, thumbT + thumbH)
            p.color = al(theme.accentLight, 200)
            c.drawRoundRect(rf, 2f * sc, 2f * sc, p)
        }
    }

    // ---- page 2: recent (asha-style list) ------------------------------------------------

    private fun drawRecent(c: Canvas) {
        val w = width.toFloat()
        val y0 = cTop + HEADER * sc
        if (recents.isEmpty()) {
            text(c, "No recent apps", w / 2, (cTop + cBot) / 2 + 20f * sc, 26f * sc, al(Color.WHITE, 160), Paint.Align.CENTER, face = light)
        } else {
            drawFocus(c, recentFocus.cur, 10f * sc)
            for (i in recents.indices) {
                recentRowRect(i, recentTopAnim, tmp)
                if (tmp.bottom < y0 || tmp.top > cBot) continue
                val app = recents[i]
                drawIcon(c, app.icon, 46f * sc, tmp.centerY(), 44f * sc)
                text(c, app.label, 84f * sc, tmp.centerY() + 8f * sc, 23f * sc, Color.WHITE, maxW = w - 120f * sc)
                p.color = al(Color.WHITE, 22)
                c.drawRect(14f * sc, tmp.bottom - 1f, w - 14f * sc, tmp.bottom, p)
            }
        }
        // header drawn last so rows scroll underneath it
        p.shader = LinearGradient(0f, cTop, 0f, y0, al(theme.accent, 255), al(theme.accent, 170), Shader.TileMode.CLAMP)
        c.drawRect(0f, cTop, w, y0, p)
        p.shader = null
        text(c, "Recent", 18f * sc, cTop + 27f * sc, 22f * sc, Color.WHITE, face = medium)
    }

    // ---- chrome -------------------------------------------------------------------------------

    private fun drawStatusBar(c: Canvas, w: Float) {
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, 0f, statusH, al(Color.BLACK, 235), al(0xFF0A0F18.toInt(), 215), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, statusH, p)
        p.shader = null
        p.color = al(theme.accent, 150)
        c.drawRect(0f, statusH - 1f, w, statusH, p)

        val base = statusH / 2f + 6f * sc
        var x = 10f * sc
        if (wifi) {
            drawWifi(c, x + 9f * sc, statusH * 0.72f, 11f * sc)
            x += 28f * sc
        }
        text(c, "BelleUI", x, base, 15f * sc, 0xFFC9D6E8.toInt(), face = medium)

        // page dots
        val gap = 6f * sc
        var total = -gap
        for (i in 0..2) total += 6f * sc + 10f * sc * closeness(i) + gap
        var dx = (w - total) / 2f
        for (i in 0..2) {
            val cl = closeness(i)
            val dw = 6f * sc + 10f * sc * cl
            rf.set(dx, statusH / 2f - 3f * sc, dx + dw, statusH / 2f + 3f * sc)
            p.color = al(theme.accentLight, (90 + 165 * cl).toInt())
            c.drawRoundRect(rf, 3f * sc, 3f * sc, p)
            dx += dw + gap
        }

        // clock + battery (right)
        val time = if (ampmStr.isEmpty()) clockStr else "$clockStr $ampmStr"
        val csz = 17f * sc
        val tw = measure(time, csz, medium)
        text(c, time, w - 10f * sc, base, csz, Color.WHITE, Paint.Align.RIGHT, face = medium)
        val bRight = w - 10f * sc - tw - 14f * sc
        val bw = 24f * sc
        val bh = 12f * sc
        val bTop = statusH / 2f - bh / 2f
        rf.set(bRight - bw, bTop, bRight, bTop + bh)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * sc
        p.color = al(Color.WHITE, 200)
        c.drawRoundRect(rf, 2f * sc, 2f * sc, p)
        p.style = Paint.Style.FILL
        c.drawRect(bRight, bTop + 3f * sc, bRight + 2f * sc, bTop + bh - 3f * sc, p)
        val fillW = (bw - 4f * sc) * batteryPct / 100f
        p.color = when {
            charging -> 0xFF5BD75B.toInt()
            batteryPct <= 15 -> 0xFFE5484D.toInt()
            else -> Color.WHITE
        }
        c.drawRect(bRight - bw + 2f * sc, bTop + 2f * sc, bRight - bw + 2f * sc + fillW, bTop + bh - 2f * sc, p)
        text(c, "$batteryPct%", bRight - bw - 6f * sc, base, 14f * sc, 0xFFC9D6E8.toInt(), Paint.Align.RIGHT)
    }

    private fun closeness(i: Int) = (1f - abs(i - pageAnim)).coerceIn(0f, 1f)

    private fun drawWifi(c: Canvas, cx: Float, cy: Float, r: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.8f * sc
        p.color = 0xFFC9D6E8.toInt()
        for (i in 1..3) {
            val rr = r * i / 3f
            rf.set(cx - rr, cy - rr, cx + rr, cy + rr)
            c.drawArc(rf, 225f, 90f, false, p)
        }
        p.style = Paint.Style.FILL
        c.drawCircle(cx, cy, 1.6f * sc, p)
    }

    private fun drawSoftKeys(c: Canvas, w: Float, h: Float) {
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, cBot, 0f, h, 0xFF1D232C.toInt(), 0xFF000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, cBot, w, h, p)
        p.shader = null
        p.color = al(theme.accent, 170)
        c.drawRect(0f, cBot, w, cBot + 1f, p)

        val left: String
        val center: String
        val right: String
        val rightKey: String
        val leftKey: String
        when {
            popup != null -> { left = ""; center = "Select"; right = "Cancel"; leftKey = ""; rightKey = backLetter() }
            reorder -> { left = ""; center = "Drop"; right = "Done"; leftKey = ""; rightKey = backLetter() }
            page == 0 -> { left = "Options"; center = "Open"; right = "Menu"; leftKey = optLetter(); rightKey = backLetter() }
            else -> { left = "Options"; center = "Open"; right = "Home"; leftKey = optLetter(); rightKey = backLetter() }
        }
        val y = cBot + softH / 2f
        if (left.isNotEmpty()) drawHint(c, leftKey, left, 14f * sc, y, 0)
        drawHint(c, okLetter(), center, w / 2f, y, 1)
        drawHint(c, rightKey, right, w - 14f * sc, y, 2)
    }

    /** mode 0 = left anchored, 1 = centered, 2 = right anchored. */
    private fun drawHint(c: Canvas, letter: String, label: String, ax: Float, cy: Float, mode: Int) {
        val d = 24f * sc
        val tsz = 19f * sc
        val tw = measure(label, tsz, Typeface.DEFAULT)
        val total = d + 8f * sc + tw
        val start = when (mode) {
            0 -> ax
            1 -> ax - total / 2f
            else -> ax - total
        }
        p.style = Paint.Style.FILL
        p.color = theme.accent
        c.drawCircle(start + d / 2f, cy, d / 2f, p)
        text(c, letter, start + d / 2f, cy + 6.5f * sc, 16f * sc, Color.BLACK, Paint.Align.CENTER, face = medium)
        text(c, label, start + d + 8f * sc, cy + 6.5f * sc, tsz, Color.WHITE)
    }

    private fun drawPopup(c: Canvas, pu: Popup, w: Float) {
        p.style = Paint.Style.FILL
        p.color = al(Color.BLACK, 165)
        c.drawRect(0f, cTop, w, cBot, p)
        val box = popupRect(pu, RectF())
        val r = 12f * sc
        p.shader = LinearGradient(box.left, box.top, box.left, box.bottom, 0xFF1B2330.toInt(), 0xFF0A0E14.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(box, r, r, p)
        p.shader = null
        // title bar
        c.save()
        c.clipRect(box.left, box.top, box.right, box.top + POPUP_TITLE * sc)
        p.shader = LinearGradient(0f, box.top, 0f, box.top + POPUP_TITLE * sc, theme.accent, al(theme.accent, 170), Shader.TileMode.CLAMP)
        c.drawRoundRect(box, r, r, p)
        p.shader = null
        c.restore()
        text(c, pu.title, box.left + 18f * sc, box.top + 28f * sc, 21f * sc, Color.WHITE, maxW = box.width() - 36f * sc, face = medium)
        for (i in pu.items.indices) {
            val top = box.top + (POPUP_TITLE + POPUP_ROW * i) * sc
            val sel = i == pu.sel
            if (sel) {
                tmp.set(box.left + 6f * sc, top + 2f * sc, box.right - 6f * sc, top + (POPUP_ROW - 2f) * sc)
                drawFocus(c, tmp, 8f * sc)
            }
            text(c, pu.items[i].label, box.left + 22f * sc, top + 30f * sc, 21f * sc, if (sel) Color.BLACK else Color.WHITE, maxW = box.width() - 44f * sc)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * sc
        p.color = al(theme.accentLight, 140)
        c.drawRoundRect(box, r, r, p)
        p.style = Paint.Style.FILL
    }

    private fun drawToast(c: Canvas, w: Float) {
        val tsz = 18f * sc
        val tw = measure(toast, tsz, Typeface.DEFAULT)
        val bw = min(tw + 40f * sc, w - 20f * sc)
        rf.set((w - bw) / 2f, cBot - 52f * sc, (w + bw) / 2f, cBot - 14f * sc)
        p.style = Paint.Style.FILL
        p.color = al(0xFF0A0E14.toInt(), 235)
        c.drawRoundRect(rf, 19f * sc, 19f * sc, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * sc
        p.color = al(theme.accentLight, 160)
        c.drawRoundRect(rf, 19f * sc, 19f * sc, p)
        p.style = Paint.Style.FILL
        text(c, toast, w / 2f, rf.centerY() + 6.5f * sc, tsz, Color.WHITE, Paint.Align.CENTER, bw - 24f * sc)
    }

    private companion object {
        const val HEADER = 40f
        const val ROW = 62f
        const val POPUP_TITLE = 42f
        const val POPUP_ROW = 46f
    }
}
