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
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
    private var statusH = 24f
    private var softH = 46f
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
        if (a == Act.OK || a == Act.OPTIONS) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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
            if (!selectOnly) perform(if (x < width / 4f) Act.BACK else if (x < width / 2f) Act.OK else if (x < width * 3f / 4f) Act.SETTINGS else Act.OPTIONS)
            return true
        }
        if (y < cTop) return false
        if ((page == 1 || page == 2) && y < cTop + TAB * sc) {
            setPage(if (x < width / 2f) 1 else 2)
            invalidate()
            return true
        }
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
                val ch = menuCellH()
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
    private fun settingsLetter() = if (repo.swapPad) "X" else "Y"

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
        statusH = 24f * sc
        softH = 46f * sc
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
        spotShader = RadialGradient(w * 0.5f, h * 0.36f, w * 0.75f, al(theme.accent, 95), al(theme.accent, 0), Shader.TileMode.CLAMP)
        vigShader = RadialGradient(w * 0.5f, h * 0.5f, w * 0.78f, intArrayOf(al(Color.BLACK, 0), al(Color.BLACK, 0), al(Color.BLACK, 185)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    private fun homeTileRect(i: Int, out: RectF): RectF {
        val cw = width / 4f
        out.set(i * cw + 6f * sc, cBot - 116f * sc, (i + 1) * cw - 6f * sc, cBot - 20f * sc)
        return out
    }

    private fun menuAreaTop() = cTop + TAB * sc

    private fun menuAreaBottom() = cBot - 4f * sc

    private fun menuCellH() = (menuAreaBottom() - menuAreaTop()) / 3f

    private fun menuCellRect(idx: Int, top: Float, out: RectF): RectF {
        val cw = width / 4f
        val ch = menuCellH()
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
            val bw = 270f * sc
            out.set(width - 8f * sc - bw, cBot - 6f * sc - bh, width - 8f * sc, cBot - 6f * sc)
        } else {
            val bw = 420f * sc
            val x = (width - bw) / 2f
            val y = cTop + max(0f, (cBot - cTop - bh) / 2f)
            out.set(x, y, x + bw, y + bh)
        }
        return out
    }

    // =====================================================================================
    // drawing  (Symbian Belle / Anna: soft, glossy, dark, vivid squircle icons, icon toolbar)
    // =====================================================================================

    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)
    private var spotShader: Shader? = null
    private var vigShader: Shader? = null

    private fun al(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    private fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.LEFT, maxW: Float = 0f, face: Typeface = sans,
    ) {
        tp.textSize = size
        tp.color = color
        tp.textAlign = align
        tp.typeface = face
        val t = if (maxW > 0f) TextUtils.ellipsize(s, tp, maxW, TextUtils.TruncateAt.END).toString() else s
        c.drawText(t, x, y, tp)
    }

    /** Text with a soft dark drop shadow, as Belle drew labels over wallpaper. */
    private fun shadowText(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.LEFT, maxW: Float = 0f, face: Typeface = sans,
    ) {
        text(c, s, x + 1f * sc, y + 1.5f * sc, size, al(Color.BLACK, 170), align, maxW, face)
        text(c, s, x, y, size, color, align, maxW, face)
    }

    private fun measure(s: String, size: Float, face: Typeface = sans): Float {
        tp.textSize = size
        tp.typeface = face
        return tp.measureText(s)
    }

    private fun drawIcon(c: Canvas, bmp: Bitmap, cx: Float, cy: Float, size: Float, alpha: Int = 255) {
        val full = size * 128f / 96f // bitmap includes shadow margin around the squircle
        rf.set(cx - full / 2, cy - full / 2, cx + full / 2, cy + full / 2)
        iconPaint.alpha = alpha
        c.drawBitmap(bmp, null, rf, iconPaint)
    }

    /** Soft shadow/glow from stacked, growing, faint rounded rects (no blur needed, works on every API). */
    private fun softShadow(c: Canvas, r: RectF, radius: Float, color: Int = Color.BLACK, dy: Float = 4f, alpha: Int = 20, steps: Int = 6) {
        p.style = Paint.Style.FILL
        p.shader = null
        for (i in steps downTo 1) {
            val g = i * 1.6f * sc
            rf.set(r.left - g, r.top - g + dy * sc, r.right + g, r.bottom + g + dy * sc)
            p.color = al(color, alpha)
            c.drawRoundRect(rf, radius + g, radius + g, p)
        }
    }

    /** Belle selection: a translucent, softly glowing glass pad. */
    private fun drawFocus(c: Canvas, r: RectF, radius: Float) {
        softShadow(c, r, radius, theme.accentLight, dy = 0f, alpha = 13, steps = 5)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(r.left, r.top, r.left, r.bottom, al(theme.accentLight, 135), al(theme.accent, 160), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, p)
        p.shader = null
        c.save()
        c.clipRect(r.left, r.top, r.right, r.top + r.height() * 0.5f)
        p.color = al(Color.WHITE, 38)
        c.drawRoundRect(r, radius, radius, p)
        c.restore()
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * sc
        p.color = al(Color.WHITE, 140)
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
        val k = 1f - exp(-dt * 18f)
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
        tmp.inset(6f * sc, 3f * sc)
        menuFocus.target(tmp)
        recentRowRect(recentSel, recentTop.toFloat(), tmp)
        recentFocus.target(tmp)
        if (homeFocus.step(k)) busy = true
        if (menuFocus.step(k)) busy = true
        if (recentFocus.step(k)) busy = true

        // wallpaper: dark base, soft coloured light from above, heavy vignette
        p.style = Paint.Style.FILL
        p.shader = bgShader
        c.drawRect(0f, 0f, w, h, p)
        p.shader = spotShader
        c.drawRect(0f, 0f, w, h, p)
        p.shader = vigShader
        c.drawRect(0f, 0f, w, h, p)
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
        drawToolbar(c, w, h)
        popup?.let { drawPopup(c, it, w) }

        if (System.currentTimeMillis() < toastUntil) {
            drawToast(c, w)
            busy = true
        }
        if (busy) postInvalidateOnAnimation()
    }

    // ---- page 0: home ----------------------------------------------------------------------

    /** Glossy black flip-clock style tile with split line. */
    private fun flipTile(c: Canvas, r: RectF, big: String, corner: String) {
        val rad = 10f * sc
        softShadow(c, r, rad, dy = 5f, alpha = 28)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, 0xFF45474C.toInt(), 0xFF0A0A0C.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, p)
        p.shader = null
        c.save()
        c.clipRect(r.left, r.top, r.right, r.centerY())
        p.color = al(Color.WHITE, 24)
        c.drawRoundRect(r, rad, rad, p)
        c.restore()
        val size = r.height() * 0.62f
        shadowText(c, big, r.centerX(), r.centerY() + size * 0.36f, size, Color.WHITE, Paint.Align.CENTER, face = medium)
        p.color = al(Color.BLACK, 215)
        c.drawRect(r.left, r.centerY() - 1.2f * sc, r.right, r.centerY() + 1.2f * sc, p)
        p.color = al(Color.WHITE, 45)
        c.drawRect(r.left, r.centerY() + 1.2f * sc, r.right, r.centerY() + 2.2f * sc, p)
        if (corner.isNotEmpty()) text(c, corner, r.right - 8f * sc, r.top + 17f * sc, 12f * sc, al(Color.WHITE, 210), Paint.Align.RIGHT, face = medium)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * sc
        p.color = al(Color.WHITE, 60)
        c.drawRoundRect(r, rad, rad, p)
        p.style = Paint.Style.FILL
    }

    private fun drawHome(c: Canvas) {
        val w = width.toFloat()
        val tw = 112f * sc
        val th = 98f * sc
        val top = cTop + 14f * sc
        val x0 = 18f * sc
        val parts = clockStr.split(":")
        rf.set(x0, top, x0 + tw, top + th)
        flipTile(c, rf, parts.getOrElse(0) { "" }, ampmStr)
        rf.set(x0 + tw + 8f * sc, top, x0 + 2 * tw + 8f * sc, top + th)
        flipTile(c, rf, parts.getOrElse(1) { "" }, "")
        rf.set(w - 18f * sc - tw, top, w - 18f * sc, top + th)
        flipTile(c, rf, dayStr, monthStr)
        shadowText(c, dateStr, x0 + 2f * sc, top + th + 26f * sc, 17f * sc, al(Color.WHITE, 225), maxW = 2 * tw)
        shadowText(c, weekdayStr, w - 18f * sc - tw / 2f, top + th + 26f * sc, 15f * sc, al(Color.WHITE, 200), Paint.Align.CENTER, maxW = tw)

        // shortcuts straight on the wallpaper
        drawFocus(c, homeFocus.cur, 12f * sc)
        for (i in 0..3) {
            homeTileRect(i, tmp)
            val app = favs.getOrNull(i)
            if (app != null) {
                drawIcon(c, app.icon, tmp.centerX(), tmp.top + 38f * sc, 62f * sc)
                shadowText(c, app.label, tmp.centerX(), tmp.bottom - 9f * sc, 15f * sc, Color.WHITE, Paint.Align.CENTER, tmp.width() - 8f * sc)
            } else {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.5f * sc
                p.color = al(Color.WHITE, 80)
                rf.set(tmp.centerX() - 26f * sc, tmp.top + 12f * sc, tmp.centerX() + 26f * sc, tmp.top + 64f * sc)
                c.drawRoundRect(rf, 15f * sc, 15f * sc, p)
                p.style = Paint.Style.FILL
                text(c, "+", tmp.centerX(), tmp.top + 50f * sc, 36f * sc, al(Color.WHITE, 90), Paint.Align.CENTER, face = light)
            }
        }
    }

    // ---- page 1: applications (parallel view, tab 1) ------------------------------------------

    private fun drawMenu(c: Canvas) {
        val w = width.toFloat()
        val area = menuAreaTop()
        if (menuApps.isEmpty()) {
            shadowText(c, if (loaded) "No apps" else "Loading...", w / 2, (area + cBot) / 2, 22f * sc, Color.WHITE, Paint.Align.CENTER)
        } else {
            drawFocus(c, menuFocus.cur, 14f * sc)
            val cw = w / 4f
            val ch = menuCellH()
            val isz = 64f * sc
            for (idx in menuApps.indices) {
                menuCellRect(idx, menuTopAnim, tmp)
                if (tmp.bottom < area || tmp.top > cBot) continue
                val app = menuApps[idx]
                val dim = if (repo.isHidden(app.key)) 110 else 255
                val iconTop = tmp.top + (ch - (isz + 24f * sc)) / 2f
                val lift = if (reorder && idx == menuSel) 7f * sc else 0f
                drawIcon(c, app.icon, tmp.centerX(), iconTop + isz / 2f - lift, isz, dim)
                shadowText(c, app.label, tmp.centerX(), iconTop + isz + 19f * sc, 15f * sc, al(Color.WHITE, dim), Paint.Align.CENTER, cw - 10f * sc)
            }
            val rows = (menuApps.size + 3) / 4
            if (rows > 3) {
                val trackT = area + 6f * sc
                val trackH = menuAreaBottom() - area - 12f * sc
                rf.set(w - 6f * sc, trackT, w - 3f * sc, trackT + trackH)
                p.color = al(Color.WHITE, 30)
                c.drawRoundRect(rf, 1.5f * sc, 1.5f * sc, p)
                val thumbH = trackH * 3f / rows
                val thumbT = trackT + (trackH - thumbH) * (menuTopAnim / (rows - 3).toFloat()).coerceIn(0f, 1f)
                rf.set(w - 6f * sc, thumbT, w - 3f * sc, thumbT + thumbH)
                p.color = al(Color.WHITE, 170)
                c.drawRoundRect(rf, 1.5f * sc, 1.5f * sc, p)
            }
        }
        drawTabBar(c, 0)
    }

    /** Belle tab bar: right under the status bar, stretched across the screen, selected tab darker. */
    private fun drawTabBar(c: Canvas, selected: Int) {
        val w = width.toFloat()
        val h = TAB * sc
        val labels = arrayOf("Applications", "Recent")
        val kinds = intArrayOf(ICON_GRID, ICON_CLOCK)
        val tw = w / labels.size
        for (i in labels.indices) {
            val sel = i == selected
            rf.set(i * tw, cTop, (i + 1) * tw, cTop + h)
            p.style = Paint.Style.FILL
            p.shader = if (sel) {
                LinearGradient(0f, cTop, 0f, cTop + h, 0xFF5E6166.toInt(), 0xFF3A3C40.toInt(), Shader.TileMode.CLAMP)
            } else {
                LinearGradient(0f, cTop, 0f, cTop + h, 0xFF26272A.toInt(), 0xFF141517.toInt(), Shader.TileMode.CLAMP)
            }
            c.drawRect(rf, p)
            p.shader = null
            val col = if (sel) Color.WHITE else 0xFF8D9196.toInt()
            val label = labels[i]
            val tsz = 16f * sc
            val textW = measure(label, tsz, if (sel) medium else sans)
            val total = 20f * sc + 8f * sc + textW
            val sx = rf.centerX() - total / 2f
            glyph(c, kinds[i], sx + 10f * sc, cTop + h / 2f, 17f * sc, col)
            text(c, label, sx + 28f * sc, cTop + h / 2f + 5.5f * sc, tsz, col, face = if (sel) medium else sans)
        }
        p.color = al(Color.BLACK, 220)
        c.drawRect(0f, cTop + h - 1.5f * sc, w, cTop + h, p)
        p.color = al(Color.WHITE, 40)
        c.drawRect(tw - 0.5f, cTop, tw + 0.5f, cTop + h, p)
    }

    // ---- page 2: recent (parallel view, tab 2) -- light list with two-line items ----------------

    private fun drawRecent(c: Canvas) {
        val w = width.toFloat()
        val bodyTop = cTop + TAB * sc
        val listTop = cTop + HEADER * sc
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, bodyTop, 0f, cBot, 0xFFF5F6F7.toInt(), 0xFFE2E4E6.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, bodyTop, w, cBot, p)
        p.shader = null
        if (recents.isEmpty()) {
            text(c, "No recent apps", w / 2, (listTop + cBot) / 2, 20f * sc, 0xFF6A6E73.toInt(), Paint.Align.CENTER)
        } else {
            // selected row: dark band with an accent edge
            val f = recentFocus.cur
            p.shader = LinearGradient(0f, f.top, 0f, f.bottom, 0xFF55585D.toInt(), 0xFF2E3033.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(f, p)
            p.shader = null
            p.color = theme.accent
            c.drawRect(f.left, f.top, f.left + 4f * sc, f.bottom, p)
            for (i in recents.indices) {
                recentRowRect(i, recentTopAnim, tmp)
                if (tmp.bottom < listTop || tmp.top > cBot) continue
                val app = recents[i]
                val sel = i == recentSel
                drawIcon(c, app.icon, 38f * sc, tmp.centerY(), 40f * sc)
                text(c, app.label, 70f * sc, tmp.centerY() - 3f * sc, 18f * sc, if (sel) Color.WHITE else 0xFF1B1C1E.toInt(), maxW = w - 110f * sc, face = medium)
                text(c, app.component.packageName, 70f * sc, tmp.centerY() + 15f * sc, 13f * sc, if (sel) 0xFFC8CCD0.toInt() else 0xFF74787D.toInt(), maxW = w - 110f * sc)
                p.color = 0xFFC4C7CA.toInt()
                c.drawRect(0f, tmp.bottom - 1f, w, tmp.bottom, p)
            }
            val vis = recentVisible()
            if (recents.size > vis) {
                val trackH = cBot - listTop - 8f * sc
                val thumbH = max(24f * sc, trackH * vis / recents.size)
                val prog = (recentTopAnim / (recents.size - vis).toFloat()).coerceIn(0f, 1f)
                val ty = listTop + 4f * sc + (trackH - thumbH) * prog
                rf.set(w - 6f * sc, ty, w - 2f * sc, ty + thumbH)
                p.color = al(0xFF4A4D52.toInt(), 200)
                c.drawRoundRect(rf, 2f * sc, 2f * sc, p)
            }
        }
        // list header band, drawn last so rows slide under it
        p.shader = LinearGradient(0f, bodyTop, 0f, listTop, 0xFF6C6F73.toInt(), 0xFF4E5054.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, bodyTop, w, listTop, p)
        p.shader = null
        text(c, "Recently opened", 12f * sc, bodyTop + (listTop - bodyTop) / 2f + 4.5f * sc, 13f * sc, Color.WHITE, face = medium)
        drawTabBar(c, 1)
    }

    // ---- chrome -------------------------------------------------------------------------------------

    /** Slim single-line status bar: wifi, battery, time. */
    private fun drawStatusBar(c: Canvas, w: Float) {
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Color.BLACK
        c.drawRect(0f, 0f, w, statusH, p)

        val base = statusH / 2f + 5f * sc
        val time = if (ampmStr.isEmpty()) clockStr else "$clockStr $ampmStr"
        val csz = 15f * sc
        val tw = measure(time, csz, medium)
        text(c, time, w - 8f * sc, base, csz, Color.WHITE, Paint.Align.RIGHT, face = medium)

        val bRight = w - 8f * sc - tw - 10f * sc
        val bw = 22f * sc
        val bh = 11f * sc
        val bTop = statusH / 2f - bh / 2f
        rf.set(bRight - bw, bTop, bRight, bTop + bh)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f * sc
        p.color = Color.WHITE
        c.drawRect(rf, p)
        p.style = Paint.Style.FILL
        c.drawRect(bRight, bTop + 3f * sc, bRight + 2f * sc, bTop + bh - 3f * sc, p)
        p.color = when {
            charging -> 0xFF63D663.toInt()
            batteryPct <= 15 -> 0xFFE5484D.toInt()
            else -> Color.WHITE
        }
        val fillW = (bw - 4f * sc) * batteryPct / 100f
        c.drawRect(bRight - bw + 2f * sc, bTop + 2f * sc, bRight - bw + 2f * sc + fillW, bTop + bh - 2f * sc, p)
        var x = bRight - bw - 6f * sc
        text(c, "$batteryPct%", x, base, 12f * sc, al(Color.WHITE, 190), Paint.Align.RIGHT)
        x -= measure("$batteryPct%", 12f * sc) + 10f * sc
        if (wifi) drawWifi(c, x - 8f * sc, statusH * 0.74f, 10f * sc)
    }

    private fun drawWifi(c: Canvas, cx: Float, cy: Float, r: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.6f * sc
        p.color = Color.WHITE
        for (i in 1..3) {
            val rr = r * i / 3f
            rf.set(cx - rr, cy - rr, cx + rr, cy + rr)
            c.drawArc(rf, 225f, 90f, false, p)
        }
        p.style = Paint.Style.FILL
        c.drawCircle(cx, cy, 1.5f * sc, p)
    }

    /**
     * Belle toolbar: back arrow (mandatory; on Home the apps button instead), two customisable
     * action buttons (select, settings) and the three-line menu button on the right.
     */
    private fun drawToolbar(c: Canvas, w: Float, h: Float) {
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, cBot, 0f, h, 0xFF303236.toInt(), 0xFF050506.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, cBot, w, h, p)
        p.shader = null
        c.save()
        c.clipRect(0f, cBot, w, cBot + softH * 0.5f)
        p.color = al(Color.WHITE, 14)
        c.drawRect(0f, cBot, w, h, p)
        c.restore()
        p.color = al(Color.WHITE, 70)
        c.drawRect(0f, cBot, w, cBot + 1f, p)

        val cy = cBot + softH / 2f
        val s = 24f * sc
        val inModal = popup != null || reorder
        val leftKind = if (page == 0 && !inModal) ICON_GRID else ICON_BACK
        val x1 = w * 0.10f
        val x2 = w * 0.37f
        val x3 = w * 0.63f
        val x4 = w * 0.90f
        toolIcon(c, leftKind, x1, cy, s)
        hintLetter(c, backLetter(), x1 + 24f * sc, cy)
        toolIcon(c, ICON_OK, x2, cy, s)
        hintLetter(c, okLetter(), x2 + 24f * sc, cy)
        if (!inModal) {
            toolIcon(c, ICON_GEAR, x3, cy, s)
            hintLetter(c, settingsLetter(), x3 + 24f * sc, cy)
            toolIcon(c, ICON_MENU, x4, cy, s)
            hintLetter(c, optLetter(), x4 - 24f * sc, cy)
        }
    }

    private fun hintLetter(c: Canvas, letter: String, x: Float, cy: Float) {
        text(c, letter, x, cy + 5f * sc, 13f * sc, al(Color.WHITE, 120), Paint.Align.CENTER, face = medium)
    }

    private fun toolIcon(c: Canvas, kind: Int, cx: Float, cy: Float, s: Float) {
        glyph(c, kind, cx + 0.5f * sc, cy + 1.5f * sc, s, al(Color.BLACK, 190))
        glyph(c, kind, cx, cy, s, al(Color.WHITE, 235))
    }

    private fun glyph(c: Canvas, kind: Int, cx: Float, cy: Float, s: Float, color: Int) {
        p.color = color
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        when (kind) {
            ICON_BACK -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3f * sc
                c.drawLine(cx + s * 0.45f, cy, cx - s * 0.45f, cy, p)
                c.drawLine(cx - s * 0.45f, cy, cx - s * 0.05f, cy - s * 0.4f, p)
                c.drawLine(cx - s * 0.45f, cy, cx - s * 0.05f, cy + s * 0.4f, p)
            }
            ICON_GRID -> {
                p.style = Paint.Style.FILL
                val q = s * 0.4f
                val g = s * 0.1f
                for (ix in 0..1) for (iy in 0..1) {
                    val x = cx - q - g / 2 + ix * (q + g)
                    val y = cy - q - g / 2 + iy * (q + g)
                    rf.set(x, y, x + q, y + q)
                    c.drawRoundRect(rf, 3f * sc, 3f * sc, p)
                }
            }
            ICON_MENU -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3f * sc
                for (i in -1..1) c.drawLine(cx - s * 0.45f, cy + i * s * 0.34f, cx + s * 0.45f, cy + i * s * 0.34f, p)
            }
            ICON_GEAR -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.5f * sc
                c.drawCircle(cx, cy, s * 0.26f, p)
                p.strokeWidth = 3.2f * sc
                for (i in 0 until 8) {
                    val a = i * (PI / 4).toFloat()
                    c.drawLine(cx + cos(a) * s * 0.36f, cy + sin(a) * s * 0.36f, cx + cos(a) * s * 0.5f, cy + sin(a) * s * 0.5f, p)
                }
            }
            ICON_CLOCK -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2f * sc
                c.drawCircle(cx, cy, s * 0.46f, p)
                c.drawLine(cx, cy, cx, cy - s * 0.3f, p)
                c.drawLine(cx, cy, cx + s * 0.22f, cy + s * 0.08f, p)
            }
            else -> { // ICON_OK: ring with a dot
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.5f * sc
                c.drawCircle(cx, cy, s * 0.46f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy, s * 0.16f, p)
            }
        }
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.BUTT
        p.strokeJoin = Paint.Join.MITER
    }

    /** Belle dialogs and the options menu: centred / corner-anchored, dark title band, light body, dark selection. */
    private fun drawPopup(c: Canvas, pu: Popup, w: Float) {
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = al(Color.BLACK, 150)
        c.drawRect(0f, cTop, w, cBot, p)
        val box = popupRect(pu, RectF())
        val r = 4f * sc
        val titleH = popupTitleH(pu)
        softShadow(c, box, r, dy = 4f, alpha = 34, steps = 4)
        p.shader = LinearGradient(0f, box.top, 0f, box.bottom, 0xFFDDDFE1.toInt(), 0xFFC4C7CA.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(box, r, r, p)
        p.shader = null
        if (titleH > 0f) {
            c.save()
            c.clipRect(box.left, box.top, box.right, box.top + titleH)
            p.shader = LinearGradient(0f, box.top, 0f, box.top + titleH, 0xFF54565A.toInt(), 0xFF2A2B2E.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(box, r, r, p)
            p.shader = null
            c.restore()
            text(c, pu.title, box.left + 12f * sc, box.top + titleH / 2f + 6f * sc, 17f * sc, Color.WHITE, maxW = box.width() - 60f * sc, face = medium)
            // close cross, as on Belle selection dialogs
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.2f * sc
            p.strokeCap = Paint.Cap.ROUND
            p.color = Color.WHITE
            val xc = box.right - 20f * sc
            val yc = box.top + titleH / 2f
            val d = 6f * sc
            c.drawLine(xc - d, yc - d, xc + d, yc + d, p)
            c.drawLine(xc - d, yc + d, xc + d, yc - d, p)
            p.strokeCap = Paint.Cap.BUTT
            p.style = Paint.Style.FILL
        }
        val pad = if (titleH > 0f) 0f else 3f * sc
        for (i in pu.items.indices) {
            val top = box.top + titleH + pad + POPUP_ROW * sc * i
            val sel = i == pu.sel
            if (sel) {
                tmp.set(box.left + 3f * sc, top + 1f * sc, box.right - 3f * sc, top + (POPUP_ROW - 1f) * sc)
                p.shader = LinearGradient(0f, tmp.top, 0f, tmp.bottom, 0xFF4A4C50.toInt(), 0xFF2A2B2E.toInt(), Shader.TileMode.CLAMP)
                c.drawRoundRect(tmp, 3f * sc, 3f * sc, p)
                p.shader = null
                p.color = theme.accent
                c.drawRect(tmp.left, tmp.top + 3f * sc, tmp.left + 3f * sc, tmp.bottom - 3f * sc, p)
            } else if (i > 0) {
                p.color = 0xFFAEB1B4.toInt()
                c.drawRect(box.left + 10f * sc, top, box.right - 10f * sc, top + 1f, p)
            }
            text(c, pu.items[i].label, box.left + 16f * sc, top + POPUP_ROW * sc / 2f + 6.5f * sc, 18f * sc, if (sel) Color.WHITE else 0xFF1B1C1E.toInt(), maxW = box.width() - 32f * sc)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * sc
        p.color = 0xFF7F8387.toInt()
        c.drawRoundRect(box, r, r, p)
        p.style = Paint.Style.FILL
    }

    /** Belle "info banner": auto-dismissing modeless bar across the top, under the status bar. */
    private fun drawToast(c: Canvas, w: Float) {
        val top = statusH
        val hgt = 34f * sc
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, top, 0f, top + hgt, 0xFF2E2F32.toInt(), 0xFF040405.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, top, w, top + hgt, p)
        p.shader = null
        p.color = al(Color.WHITE, 60)
        c.drawRect(0f, top + hgt - 1f, w, top + hgt, p)
        rf.set(12f * sc, top + hgt / 2f - 7f * sc, 26f * sc, top + hgt / 2f + 7f * sc)
        p.color = 0xFFB9BEC3.toInt()
        c.drawRoundRect(rf, 3f * sc, 3f * sc, p)
        text(c, toast, 38f * sc, top + hgt / 2f + 5.5f * sc, 15f * sc, Color.WHITE, maxW = w - 54f * sc)
    }

    private companion object {
        const val TAB = 34f      // tab bar height
        const val HEADER = 56f   // tab bar + list header band
        const val ROW = 56f      // two-line list item
        const val POPUP_TITLE = 38f
        const val POPUP_ROW = 38f
        const val ICON_BACK = 0
        const val ICON_GRID = 1
        const val ICON_MENU = 2
        const val ICON_OK = 3
        const val ICON_GEAR = 4
        const val ICON_CLOCK = 5
    }
}
