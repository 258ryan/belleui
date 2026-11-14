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
import android.graphics.Path
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
    /** menu = S60 options menu popping up from the bottom-left; otherwise a titled dialog. */
    private class Popup(val title: String, val items: List<PopupItem>, var sel: Int = 0, val menu: Boolean = false)

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
    private var monthStr = ""
    private var dayStr = ""
    private var weekdayStr = ""

    // ---- layout (px) ---------------------------------------------------------------
    private var sc = 1f
    private var statusH = 26f
    private var softH = 36f
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
        monthStr = DateFormat.format("MMM", now).toString().uppercase()
        dayStr = DateFormat.format("d", now).toString()
        weekdayStr = DateFormat.format("EEEE", now).toString()
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
                val row = ((y - tmp.top - popupTitleH(pu) - (if (pu.menu) 3f * sc else 0f)) / (POPUP_ROW * sc)).toInt()
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
                val ch = (cBot - menuAreaTop()) / 3f
                val idx = (((y - menuAreaTop()) / ch + menuTop).toInt()) * 4 + (x / cw).toInt()
                if (y > menuAreaTop() && idx in menuApps.indices) {
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
        popup = Popup(app.label, items, 0, menu = true)
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
        statusH = 26f * sc
        softH = 36f * sc
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
        swoosh.reset()
        swoosh.moveTo(0f, h * 0.80f)
        swoosh.cubicTo(w * 0.30f, h * 0.55f, w * 0.65f, h * 0.95f, w, h * 0.42f)
        swoosh.lineTo(w, h * 0.58f)
        swoosh.cubicTo(w * 0.65f, h * 1.10f, w * 0.30f, h * 0.78f, 0f, h * 0.96f)
        swoosh.close()
    }

    private fun homeTileRect(i: Int, out: RectF): RectF {
        val cw = width / 4f
        val top = cBot - DOCK * sc
        out.set(i * cw + 5f * sc, top + 5f * sc, (i + 1) * cw - 5f * sc, cBot - 5f * sc)
        return out
    }

    private fun menuAreaTop() = cTop + HEADER * sc

    private fun menuCellRect(idx: Int, top: Float, out: RectF): RectF {
        val cw = width / 4f
        val ch = (cBot - menuAreaTop()) / 3f
        val x = (idx % 4) * cw
        val y = menuAreaTop() + ((idx / 4) - top) * ch
        out.set(x, y, x + cw, y + ch)
        return out
    }

    private fun recentRowRect(i: Int, top: Float, out: RectF): RectF {
        val y0 = cTop + HEADER * sc
        out.set(0f, y0 + (i - top) * ROW * sc, width.toFloat(), y0 + (i - top + 1) * ROW * sc)
        return out
    }

    private fun recentVisible(): Int = max(1, ((cBot - cTop - HEADER * sc) / (ROW * sc)).toInt())

    private fun popupTitleH(pu: Popup) = if (pu.menu) 0f else POPUP_TITLE * sc

    private fun popupRect(pu: Popup, out: RectF): RectF {
        val bh = popupTitleH(pu) + POPUP_ROW * sc * pu.items.size + (if (pu.menu) 6f * sc else 0f)
        if (pu.menu) {
            val bw = 250f * sc
            out.set(4f * sc, cBot - 4f * sc - bh, 4f * sc + bw, cBot - 4f * sc)
        } else {
            val bw = 400f * sc
            val x = (width - bw) / 2f
            val y = cTop + max(0f, (cBot - cTop - bh) / 2f)
            out.set(x, y, x + bw, y + bh)
        }
        return out
    }

    // =====================================================================================
    // drawing  (S60 / Symbian^3 / Belle look: glossy bars, dense condensed type, flat blue highlight)
    // =====================================================================================

    private val cond = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val condBold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val swoosh = Path()

    private fun al(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    private fun shade(color: Int, f: Float): Int {
        val r = (Color.red(color) * f).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * f).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * f).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.LEFT, maxW: Float = 0f, face: Typeface = cond,
    ) {
        tp.textSize = size
        tp.color = color
        tp.textAlign = align
        tp.typeface = face
        val t = if (maxW > 0f) TextUtils.ellipsize(s, tp, maxW, TextUtils.TruncateAt.END).toString() else s
        c.drawText(t, x, y, tp)
    }

    /** Text with the 1px dark drop shadow S60 put under labels on wallpaper. */
    private fun shadowText(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.LEFT, maxW: Float = 0f, face: Typeface = cond,
    ) {
        text(c, s, x + 1f, y + 1f, size, al(Color.BLACK, 190), align, maxW, face)
        text(c, s, x, y, size, color, align, maxW, face)
    }

    private fun measure(s: String, size: Float, face: Typeface = cond): Float {
        tp.textSize = size
        tp.typeface = face
        return tp.measureText(s)
    }

    private fun drawIcon(c: Canvas, bmp: Bitmap, cx: Float, cy: Float, size: Float, alpha: Int = 255) {
        val full = size * 128f / 96f // bitmap includes shadow margin around the plate
        rf.set(cx - full / 2, cy - full / 2, cx + full / 2, cy + full / 2)
        iconPaint.alpha = alpha
        c.drawBitmap(bmp, null, rf, iconPaint)
    }

    /** Glossy highlight bar: flat-ish blue, bright top half, light rim. No glow. */
    private fun drawFocus(c: Canvas, r: RectF, radius: Float) {
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(r.left, r.top, r.left, r.bottom, theme.accentLight, theme.accent, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, p)
        p.shader = null
        c.save()
        c.clipRect(r.left, r.top, r.right, r.top + r.height() * 0.5f)
        p.color = al(Color.WHITE, 55)
        c.drawRoundRect(r, radius, radius, p)
        c.restore()
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * sc
        p.color = al(Color.WHITE, 200)
        c.drawRoundRect(r, radius, radius, p)
        p.style = Paint.Style.FILL
    }

    private fun backdrop(c: Canvas, top: Float, bottom: Float) {
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = al(Color.BLACK, 125)
        c.drawRect(0f, top, width.toFloat(), bottom, p)
    }

    private fun drawTitlePane(c: Canvas, title: String) {
        val w = width.toFloat()
        val b = cTop + HEADER * sc
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, cTop, 0f, b, shade(theme.accent, 0.95f), shade(theme.accent, 0.55f), Shader.TileMode.CLAMP)
        c.drawRect(0f, cTop, w, b, p)
        p.shader = null
        p.color = al(Color.WHITE, 40)
        c.drawRect(0f, cTop, w, cTop + HEADER * sc * 0.5f, p)
        p.color = al(Color.BLACK, 150)
        c.drawRect(0f, b - 1f, w, b, p)
        shadowText(c, title, 10f * sc, cTop + HEADER * sc * 0.5f + 6.5f * sc, 18f * sc, Color.WHITE, face = condBold)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = System.nanoTime()
        var dt = (now - lastNs) / 1e9f
        lastNs = now
        if (dt > 0.05f || dt <= 0f) dt = 0.016f
        val k = 1f - exp(-dt * 22f) // snappy, S60-style
        var busy = false

        pageAnim += (page - pageAnim) * k
        if (abs(page - pageAnim) < 0.004f) pageAnim = page.toFloat() else busy = true
        menuTopAnim += (menuTop - menuTopAnim) * k
        if (abs(menuTop - menuTopAnim) < 0.004f) menuTopAnim = menuTop.toFloat() else busy = true
        recentTopAnim += (recentTop - recentTopAnim) * k
        if (abs(recentTop - recentTopAnim) < 0.004f) recentTopAnim = recentTop.toFloat() else busy = true

        homeTileRect(homeSel, tmp)
        homeFocus.target(tmp)
        menuCellRect(menuSel, menuTop.toFloat(), tmp)
        tmp.inset(4f * sc, 3f * sc)
        menuFocus.target(tmp)
        recentRowRect(recentSel, recentTop.toFloat(), tmp)
        recentFocus.target(tmp)
        if (homeFocus.step(k)) busy = true
        if (menuFocus.step(k)) busy = true
        if (recentFocus.step(k)) busy = true

        // wallpaper: deep gradient + the soft light swirl Symbian^3 shipped with
        p.style = Paint.Style.FILL
        p.shader = bgShader
        c.drawRect(0f, 0f, w, h, p)
        p.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(al(theme.accentLight, 0), al(theme.accentLight, 60), al(theme.accentLight, 0)), null, Shader.TileMode.CLAMP)
        c.drawPath(swoosh, p)
        p.shader = null

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

    // ---- page 0: home --------------------------------------------------------------------

    private fun panel(c: Canvas, r: RectF) {
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = al(Color.BLACK, 150)
        c.drawRoundRect(r, 5f * sc, 5f * sc, p)
        c.save()
        c.clipRect(r.left, r.top, r.right, r.top + r.height() * 0.5f)
        p.color = al(Color.WHITE, 22)
        c.drawRoundRect(r, 5f * sc, 5f * sc, p)
        c.restore()
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * sc
        p.color = al(Color.WHITE, 60)
        c.drawRoundRect(r, 5f * sc, 5f * sc, p)
        p.style = Paint.Style.FILL
    }

    private fun drawHome(c: Canvas) {
        val w = width.toFloat()
        val m = 12f * sc
        val top = cTop + 12f * sc
        val split = w * 0.60f

        // clock widget
        rf.set(m, top, split, top + 104f * sc)
        panel(c, rf)
        val tsz = 58f * sc
        shadowText(c, clockStr, rf.left + 14f * sc, rf.top + 62f * sc, tsz, Color.WHITE, face = cond)
        if (ampmStr.isNotEmpty()) {
            val tw = measure(clockStr, tsz)
            shadowText(c, ampmStr, rf.left + 20f * sc + tw, rf.top + 62f * sc, 20f * sc, theme.accentLight)
        }
        shadowText(c, dateStr, rf.left + 16f * sc, rf.top + 90f * sc, 17f * sc, 0xFFD0D8E0.toInt(), maxW = rf.width() - 28f * sc)

        // calendar widget
        rf.set(split + m, top, w - m, top + 104f * sc)
        panel(c, rf)
        val bandB = rf.top + 28f * sc
        c.save()
        c.clipRect(rf.left, rf.top, rf.right, bandB)
        p.shader = LinearGradient(0f, rf.top, 0f, bandB, theme.accentLight, shade(theme.accent, 0.7f), Shader.TileMode.CLAMP)
        c.drawRoundRect(rf, 5f * sc, 5f * sc, p)
        p.shader = null
        c.restore()
        shadowText(c, monthStr, rf.centerX(), rf.top + 20f * sc, 17f * sc, Color.WHITE, Paint.Align.CENTER, face = condBold)
        shadowText(c, dayStr, rf.centerX(), rf.top + 74f * sc, 40f * sc, Color.WHITE, Paint.Align.CENTER, face = condBold)
        shadowText(c, weekdayStr, rf.centerX(), rf.top + 96f * sc, 15f * sc, 0xFFD0D8E0.toInt(), Paint.Align.CENTER)

        // shortcut bar
        val dockTop = cBot - DOCK * sc
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, dockTop, 0f, cBot, al(Color.BLACK, 150), al(Color.BLACK, 215), Shader.TileMode.CLAMP)
        c.drawRect(0f, dockTop, w, cBot, p)
        p.shader = null
        p.color = al(Color.WHITE, 60)
        c.drawRect(0f, dockTop, w, dockTop + 1f, p)
        drawFocus(c, homeFocus.cur, 6f * sc)
        for (i in 0..3) {
            homeTileRect(i, tmp)
            val app = favs.getOrNull(i)
            if (app != null) {
                drawIcon(c, app.icon, tmp.centerX(), tmp.top + 34f * sc, 52f * sc)
                shadowText(c, app.label, tmp.centerX(), tmp.bottom - 8f * sc, 15f * sc, Color.WHITE, Paint.Align.CENTER, tmp.width() - 8f * sc)
            } else {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.5f * sc
                p.color = al(Color.WHITE, 90)
                rf.set(tmp.centerX() - 24f * sc, tmp.top + 10f * sc, tmp.centerX() + 24f * sc, tmp.top + 58f * sc)
                c.drawRoundRect(rf, 8f * sc, 8f * sc, p)
                p.style = Paint.Style.FILL
                shadowText(c, "Empty", tmp.centerX(), tmp.bottom - 8f * sc, 15f * sc, al(Color.WHITE, 120), Paint.Align.CENTER)
            }
        }
    }

    // ---- page 1: main menu --------------------------------------------------------------

    private fun drawMenu(c: Canvas) {
        val w = width.toFloat()
        val area = menuAreaTop()
        backdrop(c, area, cBot)
        if (menuApps.isEmpty()) {
            shadowText(c, if (loaded) "No apps" else "Loading...", w / 2, (area + cBot) / 2, 20f * sc, Color.WHITE, Paint.Align.CENTER)
        } else {
            drawFocus(c, menuFocus.cur, 6f * sc)
            val cw = w / 4f
            val ch = (cBot - area) / 3f
            val isz = 56f * sc
            for (idx in menuApps.indices) {
                menuCellRect(idx, menuTopAnim, tmp)
                if (tmp.bottom < area || tmp.top > cBot) continue
                val app = menuApps[idx]
                val dim = if (repo.isHidden(app.key)) 110 else 255
                val iconTop = tmp.top + (ch - (isz + 24f * sc)) / 2f
                val lift = if (reorder && idx == menuSel) 6f * sc else 0f
                drawIcon(c, app.icon, tmp.centerX(), iconTop + isz / 2f - lift, isz, dim)
                shadowText(c, app.label, tmp.centerX(), iconTop + isz + 18f * sc, 15f * sc, al(Color.WHITE, dim), Paint.Align.CENTER, cw - 10f * sc)
            }
            val rows = (menuApps.size + 3) / 4
            if (rows > 3) {
                val trackT = area + 4f * sc
                val trackH = cBot - area - 8f * sc
                rf.set(w - 6f * sc, trackT, w - 2f * sc, trackT + trackH)
                p.color = al(Color.WHITE, 35)
                c.drawRect(rf, p)
                val thumbH = trackH * 3f / rows
                val thumbT = trackT + (trackH - thumbH) * (menuTopAnim / (rows - 3).toFloat()).coerceIn(0f, 1f)
                rf.set(w - 6f * sc, thumbT, w - 2f * sc, thumbT + thumbH)
                p.color = theme.accentLight
                c.drawRect(rf, p)
            }
        }
        drawTitlePane(c, "Menu")
    }

    // ---- page 2: recent (list view) ---------------------------------------------------------

    private fun drawRecent(c: Canvas) {
        val w = width.toFloat()
        val y0 = cTop + HEADER * sc
        backdrop(c, y0, cBot)
        if (recents.isEmpty()) {
            shadowText(c, "No recent apps", w / 2, (y0 + cBot) / 2, 20f * sc, Color.WHITE, Paint.Align.CENTER)
        } else {
            drawFocus(c, recentFocus.cur, 2f * sc)
            for (i in recents.indices) {
                recentRowRect(i, recentTopAnim, tmp)
                if (tmp.bottom < y0 || tmp.top > cBot) continue
                val app = recents[i]
                drawIcon(c, app.icon, 30f * sc, tmp.centerY(), 30f * sc)
                shadowText(c, app.label, 58f * sc, tmp.centerY() + 6f * sc, 18f * sc, Color.WHITE, maxW = w - 80f * sc)
                p.color = al(Color.WHITE, 28)
                c.drawRect(0f, tmp.bottom - 1f, w, tmp.bottom, p)
            }
        }
        drawTitlePane(c, "Recent")
    }

    // ---- chrome ---------------------------------------------------------------------------------

    private fun glossBar(c: Canvas, top: Float, bottom: Float, w: Float) {
        val mid = (top + bottom) / 2f
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, top, 0f, mid, 0xFF3A4046.toInt(), 0xFF0E1012.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, top, w, mid, p)
        p.shader = LinearGradient(0f, mid, 0f, bottom, 0xFF000000.toInt(), 0xFF1A1D20.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, mid, w, bottom, p)
        p.shader = null
    }

    private fun drawStatusBar(c: Canvas, w: Float) {
        glossBar(c, 0f, statusH, w)
        p.color = 0xFF5A6068.toInt()
        c.drawRect(0f, statusH - 1f, w, statusH, p)

        val base = statusH / 2f + 5f * sc
        var x = 8f * sc
        if (wifi) {
            drawWifi(c, x + 8f * sc, statusH * 0.74f, 10f * sc)
            x += 24f * sc
        }
        text(c, "General", x, base, 15f * sc, 0xFFC4CCD4.toInt())

        // page indicator: plain dots
        val gap = 7f * sc
        var dx = (w - (2 * gap + 3 * 5f * sc)) / 2f
        for (i in 0..2) {
            p.color = if (i == page) Color.WHITE else al(Color.WHITE, 90)
            c.drawCircle(dx + 2.5f * sc, statusH / 2f, 2.5f * sc, p)
            dx += 5f * sc + gap
        }

        val time = if (ampmStr.isEmpty()) clockStr else "$clockStr $ampmStr"
        val csz = 16f * sc
        val tw = measure(time, csz, condBold)
        text(c, time, w - 8f * sc, base, csz, Color.WHITE, Paint.Align.RIGHT, face = condBold)

        // S60-style segmented battery indicator
        val bRight = w - 8f * sc - tw - 12f * sc
        val bw = 26f * sc
        val bh = 12f * sc
        val bTop = statusH / 2f - bh / 2f
        rf.set(bRight - bw, bTop, bRight, bTop + bh)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * sc
        p.color = al(Color.WHITE, 210)
        c.drawRect(rf, p)
        p.style = Paint.Style.FILL
        c.drawRect(bRight, bTop + 3f * sc, bRight + 2f * sc, bTop + bh - 3f * sc, p)
        val segs = ((batteryPct + 12) / 25).coerceIn(0, 4)
        val segW = (bw - 5f * sc) / 4f
        p.color = when {
            charging -> 0xFF63D663.toInt()
            batteryPct <= 15 -> 0xFFE5484D.toInt()
            else -> 0xFF8ED8FF.toInt()
        }
        for (i in 0 until segs) {
            val sx = bRight - bw + 2f * sc + i * (segW + 0.5f * sc)
            c.drawRect(sx, bTop + 2f * sc, sx + segW - 0.5f * sc, bTop + bh - 2f * sc, p)
        }
        text(c, "$batteryPct%", bRight - bw - 5f * sc, base, 14f * sc, 0xFFC4CCD4.toInt(), Paint.Align.RIGHT)
    }

    private fun drawWifi(c: Canvas, cx: Float, cy: Float, r: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.6f * sc
        p.color = 0xFFC4CCD4.toInt()
        for (i in 1..3) {
            val rr = r * i / 3f
            rf.set(cx - rr, cy - rr, cx + rr, cy + rr)
            c.drawArc(rf, 225f, 90f, false, p)
        }
        p.style = Paint.Style.FILL
        c.drawCircle(cx, cy, 1.5f * sc, p)
    }

    private fun drawSoftKeys(c: Canvas, w: Float, h: Float) {
        glossBar(c, cBot, h, w)
        p.color = 0xFF5A6068.toInt()
        c.drawRect(0f, cBot, w, cBot + 1f, p)

        val left: String
        val center: String
        val right: String
        val leftKey = optLetter()
        val rightKey = backLetter()
        when {
            popup != null -> { left = ""; center = "Select"; right = "Cancel" }
            reorder -> { left = ""; center = "Drop"; right = "Done" }
            page == 0 -> { left = "Options"; center = "Open"; right = "Menu" }
            else -> { left = "Options"; center = "Open"; right = "Home" }
        }
        val y = cBot + softH / 2f
        if (left.isNotEmpty()) drawHint(c, leftKey, left, 8f * sc, y, 0)
        drawHint(c, okLetter(), center, w / 2f, y, 1)
        drawHint(c, rightKey, right, w - 8f * sc, y, 2)
    }

    /** Hardware-style keycap + label. mode 0 = left anchored, 1 = centered, 2 = right anchored. */
    private fun drawHint(c: Canvas, letter: String, label: String, ax: Float, cy: Float, mode: Int) {
        val cap = 18f * sc
        val tsz = 17f * sc
        val tw = measure(label, tsz)
        val total = cap + 6f * sc + tw
        val start = when (mode) {
            0 -> ax
            1 -> ax - total / 2f
            else -> ax - total
        }
        rf.set(start, cy - cap / 2f, start + cap, cy + cap / 2f)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, rf.top, 0f, rf.bottom, 0xFF7A838C.toInt(), 0xFF2B3036.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(rf, 4f * sc, 4f * sc, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * sc
        p.color = 0xFFA9B2BB.toInt()
        c.drawRoundRect(rf, 4f * sc, 4f * sc, p)
        p.style = Paint.Style.FILL
        text(c, letter, rf.centerX(), cy + 5f * sc, 13f * sc, Color.WHITE, Paint.Align.CENTER, face = condBold)
        text(c, label, start + cap + 6f * sc, cy + 6f * sc, tsz, Color.WHITE)
    }

    private fun drawPopup(c: Canvas, pu: Popup, w: Float) {
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = al(Color.BLACK, 90)
        c.drawRect(0f, cTop, w, cBot, p)
        val box = popupRect(pu, RectF())
        val r = 4f * sc
        val titleH = popupTitleH(pu)
        p.shader = LinearGradient(0f, box.top, 0f, box.bottom, 0xFF2C343C.toInt(), 0xFF0A0D10.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(box, r, r, p)
        p.shader = null
        if (titleH > 0f) {
            c.save()
            c.clipRect(box.left, box.top, box.right, box.top + titleH)
            p.shader = LinearGradient(0f, box.top, 0f, box.top + titleH, shade(theme.accent, 0.95f), shade(theme.accent, 0.55f), Shader.TileMode.CLAMP)
            c.drawRoundRect(box, r, r, p)
            p.shader = null
            p.color = al(Color.WHITE, 40)
            c.drawRect(box.left, box.top, box.right, box.top + titleH / 2f, p)
            c.restore()
            shadowText(c, pu.title, box.left + 10f * sc, box.top + titleH / 2f + 6.5f * sc, 18f * sc, Color.WHITE, maxW = box.width() - 20f * sc, face = condBold)
        }
        val pad = if (titleH > 0f) 0f else 3f * sc
        for (i in pu.items.indices) {
            val top = box.top + titleH + pad + POPUP_ROW * sc * i
            val sel = i == pu.sel
            if (sel) {
                tmp.set(box.left + 3f * sc, top + 1f * sc, box.right - 3f * sc, top + (POPUP_ROW - 1f) * sc)
                drawFocus(c, tmp, 3f * sc)
            } else if (i > 0) {
                p.color = al(Color.WHITE, 24)
                c.drawRect(box.left + 8f * sc, top, box.right - 8f * sc, top + 1f, p)
            }
            text(c, pu.items[i].label, box.left + 12f * sc, top + POPUP_ROW * sc / 2f + 6.5f * sc, 18f * sc, Color.WHITE, maxW = box.width() - 24f * sc)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * sc
        p.color = 0xFF8794A0.toInt()
        c.drawRoundRect(box, r, r, p)
        p.style = Paint.Style.FILL
    }

    /** S60 "information note": small dark box in the middle of the screen. */
    private fun drawToast(c: Canvas, w: Float) {
        val tsz = 17f * sc
        val tw = measure(toast, tsz)
        val bw = min(tw + 36f * sc, w - 24f * sc)
        val cy = (cTop + cBot) / 2f
        rf.set((w - bw) / 2f, cy - 22f * sc, (w + bw) / 2f, cy + 22f * sc)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, rf.top, 0f, rf.bottom, 0xFF2C343C.toInt(), 0xFF0A0D10.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(rf, 5f * sc, 5f * sc, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * sc
        p.color = theme.accentLight
        c.drawRoundRect(rf, 5f * sc, 5f * sc, p)
        p.style = Paint.Style.FILL
        text(c, toast, w / 2f, cy + 6f * sc, tsz, Color.WHITE, Paint.Align.CENTER, bw - 24f * sc)
    }

    private companion object {
        const val HEADER = 28f   // title pane
        const val ROW = 42f      // list row
        const val DOCK = 100f    // home shortcut bar
        const val POPUP_TITLE = 30f
        const val POPUP_ROW = 36f
    }
}
