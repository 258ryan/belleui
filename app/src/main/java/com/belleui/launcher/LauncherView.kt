package com.belleui.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.graphics.Path
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
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.LruCache
import java.util.Calendar
import android.provider.Settings
import android.media.AudioManager
import android.net.wifi.WifiManager
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
import kotlin.math.roundToInt
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
    /** The Belle application grid is six icons across in landscape (ApplicationGrid.xml of the Belle FP2 theme). */
    private val COLS = 4


    private enum class Act { UP, DOWN, LEFT, RIGHT, OK, BACK, OPTIONS, SETTINGS, MENU, PREV, NEXT }

    private class PopupItem(val label: String, val action: () -> Unit)
    /** menu = S60 options menu popping up from the bottom-left; otherwise a titled dialog. */
    private class Popup(val title: String, val items: List<PopupItem>, var sel: Int = 0, val menu: Boolean = false) {
        var top = 0 // first visible row when the list is taller than the screen
    }

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
    private var theme: Theme = run {
        com.belleui.kit.ThemeStore.load(context)
        com.belleui.kit.Sounds.init(context)
        com.belleui.kit.ThemeStore.shared?.let { Themes.fromAccent(it) } ?: Themes.all[repo.themeIndex]
    }
    private var menuApps: List<AppEntry> = emptyList()
    private var favs: List<AppEntry> = emptyList()
    private var recents: List<AppEntry> = emptyList()
    private var loaded = false

    // ---- ui state ----------------------------------------------------------------
    private var page = 0
    private var pageAnim = 0f
    private var tab = 1
    private var tabFocus = false
    private var homeSel = 0
    private var homePage = 0 // which of the three home screens is showing
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
    private var toastStart = 0L
    private var popupT = 0f
    private var popupShown: Popup? = null
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
    private var dayMonthStr = ""

    // ---- layout (px) ---------------------------------------------------------------
    private var sc = 1f
    private var statusH = 26f
    private var softH = 60f
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

    // ---- Hub page (notification history, left of Home) ----
    private class HubItem(val header: String?, val note: Note?)
    private var hubItems: List<HubItem> = emptyList()
    private var hubBuiltVersion = -1
    private var hubBuiltDay = -1
    private var hubSel = 0
    private var hubScroll = 0f
    private var hubScrollTarget = 0f
    private val hubHits = ArrayList<Pair<RectF, Int>>()
    private val noteIcons = LruCache<String, Bitmap>(40)
    private val appLabels = HashMap<String, String>()
    private var frameK = 0.3f
    private var hubTick = false

    // ---- home widgets ----
    private val homeWidgetRects = arrayOf(RectF(), RectF())
    private var widgetSub = 0 // which button of a large widget, or which small widget, has the focus
    private val subRects = Array(3) { RectF() }
    private val media = MediaWidget(act) { postInvalidate() }
    private val calendar = CalendarWidget(act) { postInvalidate() }
    private var notesList: List<Pair<String, String>> = emptyList()
    private var noteIdx = 0
    private var notesLoadedAt = 0L
    private var dateShort = ""
    private var dateShortFor = ""
    private var storageFree = 0L
    private var storageTotal = 1L
    private val gfx = BelleGfx(act)

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
                    menuTop = (menuTop + d).coerceIn(0, max(0, (menuApps.size + COLS - 1) / COLS - 3))
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
        // The launcher draws its own focus; stop Android outlining the whole view in the accent colour.
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        foreground = null
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
        homeStart()
        readStorage()
        refreshClock()
        readWifi()
        if (loaded) refreshLists()
        invalidate()
    }

    /** The Status menu's Notifications button: the Hub page. */
    fun openHub() {
        popup = null
        reorder = false
        setPage(-1)
        invalidate()
    }

    /** The recent-apps key (through the Belle Chooser module): the Recent tab, ready to pick from. */
    fun openRecents() {
        popup = null
        reorder = false
        setPage(2)
        tabFocus = false
        recentSel = 0
        if (loaded) refreshLists()
        invalidate()
    }

    fun goHome() {
        if (homePage != 0) {
            homePage = 0
            repo.homePage = 0
            refreshLists()
        }
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

    /** Moves to another home screen; its own shortcuts and widgets load with it. */
    private fun switchHome(to: Int, tile: Int) {
        homePage = to.coerceIn(0, AppRepo.HOME_PAGES - 1)
        repo.homePage = homePage
        homeSel = tile
        widgetSub = 0
        refreshLists()
        homeStart()
        invalidate()
    }

    /** Past the last tile or widget: the next home screen, then the apps (or the Hub on the left). */
    private fun homeEdge(dir: Int) {
        val to = homePage + dir
        if (to in 0 until AppRepo.HOME_PAGES) switchHome(to, if (dir < 0) 3 else 0)
        else setPage(if (dir < 0) -1 else 1)
    }

    // ---- search (the Menu's toolbar magnifier) ----------------------------------------------------
    private var searchQuery = ""
    private var searchBox: android.widget.EditText? = null
    private var searchEditing = false

    private fun searching() = searchBox?.visibility == View.VISIBLE

    private fun openSearch() {
        popup = null
        val box = searchBox ?: android.widget.EditText(act).also { b ->
            b.setBackgroundColor(0xE6101010.toInt())
            b.setTextColor(Color.WHITE)
            b.setHintTextColor(0xFF8A8A8A.toInt())
            b.hint = "Search"
            b.setSingleLine()
            b.textSize = 18f
            b.setPadding((16f * sc).toInt(), 0, (16f * sc).toInt(), 0)
            b.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            b.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    searchQuery = s?.toString()?.trim() ?: ""
                    menuSel = 0
                    menuTop = 0
                    refreshLists()
                    invalidate()
                }
            })
            b.setOnEditorActionListener { _, _, _ ->
                // keep the results, hand the keys back to the grid
                searchEditing = false
                (act.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(b.windowToken, 0)
                b.clearFocus()
                invalidate()
                true
            }
            val lp = android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, (SEARCH_H * sc).toInt())
            lp.topMargin = statusH.toInt()
            act.addContentView(b, lp)
            searchBox = b
        }
        box.setText("")
        box.visibility = View.VISIBLE
        searchEditing = true
        box.requestFocus()
        box.post { (act.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(box, 0) }
        invalidate()
    }

    private fun closeSearch() {
        val b = searchBox ?: return
        (act.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(b.windowToken, 0)
        b.visibility = View.GONE
        b.clearFocus()
        searchEditing = false
        searchQuery = ""
        refreshLists()
        invalidate()
    }

    private fun refreshLists() {
        menuApps = repo.menuApps(repo.showHidden).let { l -> if (searchQuery.isEmpty()) l else l.filter { it.label.contains(searchQuery, true) } }
        // hidden apps disappear everywhere (menu, home shortcuts, recents) unless "show hidden" is on
        favs = repo.favoriteEntries().filter { repo.showHidden || !repo.isHidden(it.key) }
        recents = repo.recentEntries().filter { repo.showHidden || !repo.isHidden(it.key) }
        if (loaded && !homeLoaded) {
            homeLoaded = true
            loadHome()
        }
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
        dayMonthStr = DateFormat.format("d MMMM", now).toString()
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
        if (e.keyCode == KeyEvent.KEYCODE_APP_SWITCH && !searching()) {
            // the recents key, when the system lets it through to the launcher
            if (e.action == KeyEvent.ACTION_UP) openRecents()
            return true
        }
        if (searching()) {
            if (e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_ESCAPE || (e.keyCode == KeyEvent.KEYCODE_BUTTON_B && !searchEditing)) {
                if (e.action == KeyEvent.ACTION_UP) closeSearch()
                return true
            }
            if (searchEditing) return false // the text box gets the keys
        }
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
        com.belleui.kit.Sounds.play(when (a) {
            Act.OK -> com.belleui.kit.Sounds.SELECT
            Act.BACK -> com.belleui.kit.Sounds.BACK
            Act.OPTIONS, Act.SETTINGS, Act.MENU -> com.belleui.kit.Sounds.OPEN
            else -> com.belleui.kit.Sounds.TICK
        })
        if (a == Act.OK || a == Act.OPTIONS) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val pu = popup
        if (pu != null) {
            val n = pu.items.size + (if (pu.menu) 1 else 0)
            when (a) {
                Act.UP -> pu.sel = (pu.sel - 1 + n) % n
                Act.DOWN -> pu.sel = (pu.sel + 1) % n
                Act.OK -> {
                    val item = pu.items.getOrNull(pu.sel)
                    popup = null
                    item?.action?.invoke()
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
                Act.UP -> moveBy(-COLS)
                Act.DOWN -> moveBy(COLS)
                Act.OK, Act.BACK, Act.OPTIONS -> {
                    reorder = false
                    showToast("Order saved")
                }
                else -> {}
            }
            invalidate()
            return
        }
        if (page == 0 && homeEdit && (a == Act.BACK || a == Act.SETTINGS)) {
            homeKey(a)
            invalidate()
            return
        }
        if (tabFocus && page >= 1 && (a == Act.LEFT || a == Act.RIGHT || a == Act.UP || a == Act.DOWN || a == Act.OK || a == Act.BACK)) {
            when (a) {
                Act.LEFT -> setPage(1)
                Act.RIGHT -> setPage(2)
                Act.DOWN, Act.OK, Act.BACK -> tabFocus = false
                else -> {}
            }
            invalidate()
            return
        }
        when (a) {
            Act.PREV -> setPage(page - 1) // L1 on Home opens the Hub
            Act.NEXT -> setPage(page + 1)
            Act.MENU -> setPage(if (page != 0) 0 else 1)
            Act.SETTINGS -> if (page == 1) openSearch() else openQuick(0)
            Act.OPTIONS -> openOptions()
            Act.BACK -> setPage(if (page == 0) 1 else 0)
            else -> navigate(a)
        }
        invalidate()
    }

    /** -1 = Hub (left of Home); 0 = Home; 1 / 2 = the Applications / Recent tabs of the apps view (switched in place, no slide). */
    private fun setPage(p: Int) {
        page = p.coerceIn(-1, 2)
        if (page >= 1) tab = page else tabFocus = false
    }

    /** Horizontal slot the page occupies: both tabs share the apps slot. */
    private fun pagePos() = min(page, 1)

    private fun navigate(a: Act) {
        when (page) {
            -1 -> when (a) {
                Act.UP -> hubStep(-1)
                Act.DOWN -> hubStep(1)
                Act.RIGHT -> setPage(0)
                Act.OK -> {
                    if (!HubStore.hasAccess(act)) tryStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    else hubItems.getOrNull(hubSel)?.note?.let { openNote(it) }
                }
                else -> {}
            }
            0 -> homeKey(a)
            1 -> {
                val n = menuApps.size
                if (n == 0) {
                    if (a == Act.LEFT) setPage(0) else if (a == Act.RIGHT) setPage(2) else if (a == Act.UP && page == 2) tabFocus = true
                    return
                }
                when (a) {
                    Act.LEFT -> if (menuSel % COLS == 0) setPage(0) else menuSel--
                    Act.RIGHT -> if (menuSel % COLS == COLS - 1 || menuSel == n - 1) setPage(2) else menuSel++
                    Act.UP -> if (menuSel >= COLS) menuSel -= COLS
                    Act.DOWN -> if (menuSel + COLS < n) menuSel += COLS else if (menuSel / COLS < (n - 1) / COLS) menuSel = n - 1
                    Act.OK -> launch(menuApps[menuSel])
                    else -> {}
                }
            }
            2 -> when (a) {
                Act.UP -> if (recentSel > 0) recentSel-- else tabFocus = true
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
        val row = menuSel / COLS
        if (row < menuTop) menuTop = row else if (row > menuTop + 2) menuTop = row - 2
        menuTop = menuTop.coerceIn(0, max(0, (menuApps.size + COLS - 1) / COLS - 3))
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
                val row = ((y - tmp.top - popupTitleH(pu) - (if (pu.menu) 4f * sc else 0f)) / popupItemH(pu)).toInt() + pu.top
                if (row in pu.items.indices || (pu.menu && row >= pu.items.size)) {
                    pu.sel = min(row, pu.items.size)
                    perform(Act.OK)
                }
            }
            invalidate()
            return true
        }
        if (abs(pageAnim - pagePos()) > 0.05f) return false
        if (y > cBot) { // soft-key bar
            if (!selectOnly) {
                if (page == 1 || page == 0) perform(if (x < width / 3f) Act.BACK else if (x < width * 2f / 3f) (if (page == 1) Act.SETTINGS else Act.OK) else Act.OPTIONS)
                else perform(if (x < width / 4f) Act.BACK else if (x < width / 2f) Act.OK else if (x < width * 3f / 4f) Act.SETTINGS else Act.OPTIONS)
            }
            return true
        }
        if (y < cTop) return false
        if (page == 2 && y < cTop + TAB * sc) {
            setPage(if (x < width / 2f) 1 else 2)
            invalidate()
            return true
        }
        var hit = false
        when (page) {
            -1 -> for ((rect, idx) in hubHits) if (rect.contains(x, y)) {
                hubSel = idx
                hit = true
                if (!selectOnly) perform(Act.OK)
                break
            }
            0 -> hit = homeTap(x, y, selectOnly)
            1 -> {
                val cw = width / COLS.toFloat()
                val ch = menuCellH()
                val idx = (((y - menuAreaTop()) / ch + menuTop).toInt()) * COLS + (x / cw).toInt()
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
        -1 -> null
        0 -> homeItems.getOrNull(homeIdx)?.let { appFor(it) }
        1 -> menuApps.getOrNull(menuSel)
        else -> recents.getOrNull(recentSel)
    }

    private fun okLetter() = if (repo.swapPad) "B" else "A"
    private fun backLetter() = if (repo.swapPad) "A" else "B"
    private fun optLetter() = if (repo.swapPad) "Y" else "X"
    private fun settingsLetter() = if (repo.swapPad) "X" else "Y"

    private fun openOptions() {
        if (page == -1) {
            openHubOptions()
            return
        }
        if (page == 0) {
            openHomeMenu()
            return
        }
        val app = selectedApp()
        if (app == null) {
            openQuick(0)
            return
        }
        val items = ArrayList<PopupItem>()
        items += PopupItem("Open") { launch(app) }
        if (page == 2) {
            items += PopupItem("Close app") { closeApp(listOf(app)) }
            items += PopupItem("Force stop") { forceStop(app) }
            items += PopupItem("Remove from list") {
                repo.removeRecent(app.key)
                refreshLists()
                showToast("${app.label} removed from Recent")
            }
            items += PopupItem("Close all and clear") { closeApp(recents.toList(), clear = true) }
            items += PopupItem("Clear list") {
                repo.clearRecents()
                refreshLists()
                showToast("Recent list cleared")
            }
        }
        if (page == 1 && searchQuery.isEmpty()) items += PopupItem("Move") {
            reorder = true
            showToast("D-pad moves, ${okLetter()} drops")
        }
        val fav = homeHas(app.key)
        items += PopupItem(if (fav) "Remove from home" else "Add to home") {
            if (fav) removeHomeApp(app.key)
            else if (!addHomeApp(app.key)) showToast("No room on the home screen")
            else showToast("${app.label} added to home")
        }
        val hidden = repo.isHidden(app.key)
        items += PopupItem(if (hidden) "Unhide" else "Hide from launcher") {
            repo.setHidden(app.key, !hidden)
            refreshLists()
            showToast(if (hidden) "${app.label} is visible again" else "${app.label} hidden - restore it in Settings")
        }
        items += PopupItem("App info") {
            tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.component.packageName}")))
        }
        items += PopupItem("Uninstall") {
            tryStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.component.packageName}")))
        }
        popup = Popup(app.label, items, 0, menu = true)
    }

    /** Ends the background processes of these apps (a normal permission, so no root needed); the launcher itself is never touched. */
    private fun closeApp(apps: List<AppEntry>, clear: Boolean = false) {
        val am = act.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        var n = 0
        for (a in apps) {
            val pkg = a.component.packageName
            if (pkg == act.packageName) continue
            try {
                am.killBackgroundProcesses(pkg)
                n++
            } catch (_: Exception) {
            }
        }
        if (clear) repo.clearRecents()
        refreshLists()
        showToast(if (clear) "Closed $n app${if (n == 1) "" else "s"} and cleared the list" else if (n == 1) "${apps[0].label} closed" else "Closed $n apps")
    }

    /**
     * Stops the app completely. Android only lets the system or root do that, so this asks root (Magisk shows
     * its question the first time) and falls back to the app's info page, where the Force stop button is.
     */
    private fun forceStop(app: AppEntry) {
        val pkg = app.component.packageName
        if (pkg == act.packageName) return
        showToast("Stopping ${app.label}...")
        Thread {
            var ok = false
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $pkg"))
                ok = p.waitFor() == 0
            } catch (_: Exception) {
            }
            post {
                if (ok) {
                    showToast("${app.label} stopped")
                } else {
                    showToast("No root - tap Force stop on the next page")
                    tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
                }
            }
        }.start()
    }

    private fun audio() = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun volumePct(): Int {
        val a = audio()
        return a.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max(1, a.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    }

    private fun brightnessPct(): Int = try {
        Settings.System.getInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / 255
    } catch (_: Exception) {
        -1
    }

    private fun wifiOn(): Boolean = try {
        (act.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
    } catch (_: Exception) {
        false
    }

    private fun btOn(): Boolean = try {
        @Suppress("DEPRECATION")
        android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
    } catch (_: Exception) {
        false
    }

    private fun airplaneOn(): Boolean = try {
        Settings.Global.getInt(act.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    } catch (_: Exception) {
        false
    }

    /** Switches a radio with root when it is there; otherwise (or if root says no) the normal panel opens. */
    private fun radio(kind: String, nowOn: Boolean, sel: Int, fallback: () -> Unit) {
        showToast("Switching...")
        Thread {
            val cmd = if (kind == "airplane") "cmd connectivity airplane-mode ${if (nowOn) "disable" else "enable"}" else "svc $kind ${if (nowOn) "disable" else "enable"}"
            val ok = com.belleui.kit.Root.run(cmd) != null
            post {
                if (ok) {
                    showToast("Done")
                    postDelayed({ if (popup != null) openQuick(sel); invalidate() }, 900)
                } else {
                    popup = null
                    fallback()
                }
            }
        }.start()
    }

    /** Y: the Quick settings panel. */
    private fun openQuick(sel: Int) {
        val items = listOf(
            PopupItem("Wi-Fi: " + if (wifiOn()) "on" else "off") {
                radio("wifi", wifiOn(), 0) { tryStart(Intent(if (Build.VERSION.SDK_INT >= 29) "android.settings.panel.action.WIFI" else Settings.ACTION_WIFI_SETTINGS)) }
            },
            PopupItem("Bluetooth: " + if (btOn()) "on" else "off") {
                radio("bluetooth", btOn(), 1) { tryStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            },
            PopupItem("Airplane mode: " + if (airplaneOn()) "on" else "off") {
                radio("airplane", airplaneOn(), 2) { tryStart(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) }
            },
            PopupItem("Profile: " + com.belleui.kit.Profiles.names[com.belleui.kit.Profiles.current(act)]) {
                val next = (com.belleui.kit.Profiles.current(act) + 1) % com.belleui.kit.Profiles.names.size
                if (!Settings.System.canWrite(act)) {
                    tryStart(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${act.packageName}")))
                } else {
                    val ok = com.belleui.kit.Profiles.apply(act, next)
                    showToast(com.belleui.kit.Profiles.names[next] + if (ok) "" else " (partly - allow do-not-disturb access for Silent)")
                    openQuick(3)
                }
            },
            PopupItem("Volume: ${volumePct()}%") {
                val next = if (volumePct() >= 100) 0 else (volumePct() / 20 + 1) * 20
                val a = audio()
                try {
                    a.setStreamVolume(AudioManager.STREAM_MUSIC, (a.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * next / 100.0).roundToInt(), 0)
                } catch (_: SecurityException) {
                }
                openQuick(4)
            },
            PopupItem("Brightness: " + (brightnessPct().takeIf { it >= 0 }?.let { "$it%" } ?: "-")) {
                if (Settings.System.canWrite(act)) {
                    val b = brightnessPct()
                    val next = if (b < 25) 25 else if (b < 50) 50 else if (b < 75) 75 else if (b < 100) 100 else 25
                    Settings.System.putInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255 * next / 100)
                    openQuick(5)
                } else {
                    tryStart(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${act.packageName}")))
                }
            },
            PopupItem("System settings") {
                val own = act.packageManager.getLaunchIntentForPackage("com.belleui.settings")
                tryStart(own ?: Intent(Settings.ACTION_SETTINGS))
            },
            PopupItem("Launcher settings") { openSettings(0) },
        )
        popup = Popup("Quick settings", items, sel)
    }

    private fun openSettings(sel: Int) {
        val items = listOf(
            PopupItem(if (com.belleui.kit.ThemeStore.shared != null) "Theme colour: set in Styles" else "Theme: ${theme.name}") {
                if (com.belleui.kit.ThemeStore.shared != null) {
                    val i = context.packageManager.getLaunchIntentForPackage("com.belleui.styles")
                    if (i != null) context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    popup = null
                } else {
                    val next = (repo.themeIndex + 1) % Themes.all.size
                    repo.themeIndex = next
                    theme = Themes.all[next]
                    buildBackground()
                    openSettings(0)
                }
            },
            PopupItem("Buttons: " + if (repo.swapPad) "Nintendo (B = OK)" else "Xbox (A = OK)") {
                repo.swapPad = !repo.swapPad
                openSettings(1)
            },
            PopupItem("Show hidden apps: " + if (repo.showHidden) "on" else "off") {
                repo.showHidden = !repo.showHidden
                refreshLists()
                openSettings(2)
            },
            PopupItem("Hidden apps (${repo.apps.count { repo.isHidden(it.key) }})") { openHiddenApps() },
            PopupItem("Apps shown: " + arrayOf("all", "games", "media", "other")[repo.catFilter]) {
                repo.catFilter = (repo.catFilter + 1) % 4
                refreshLists()
                openSettings(4)
            },
        )
        popup = Popup("Settings", items, sel)
    }

    /** Lists the hidden apps; choosing one makes it visible again. */
    private fun openHiddenApps() {
        val hiddenApps = repo.apps.filter { repo.isHidden(it.key) }.sortedBy { it.label.lowercase() }
        if (hiddenApps.isEmpty()) {
            showToast("No hidden apps")
            return
        }
        val shown = hiddenApps.take(4)
        val items = ArrayList<PopupItem>()
        for (a in shown) {
            items += PopupItem("Unhide ${a.label}") {
                repo.setHidden(a.key, false)
                refreshLists()
                showToast("${a.label} is visible again")
                if (hiddenApps.size > 1) openHiddenApps()
            }
        }
        if (hiddenApps.size > shown.size) items += PopupItem("${hiddenApps.size - shown.size} more - unhide these first") { openHiddenApps() }
        popup = Popup("Hidden apps", items)
    }

    private fun showToast(s: String) {
        toast = s
        toastStart = System.currentTimeMillis()
        toastUntil = toastStart + 2800
    }

    // =====================================================================================
    // layout helpers
    // =====================================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sc = min(w / 640f, h / 480f)
        statusH = 26f * sc
        softH = 60f * sc
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
        // same footprint as an Applications grid cell, so the highlight matches
        val h = menuCellH() - 6f * sc
        out.set(i * cw + 6f * sc, cBot - 12f * sc - h, (i + 1) * cw - 6f * sc, cBot - 12f * sc)
        return out
    }

    private fun menuAreaTop() = cTop + (if (searching()) SEARCH_H * sc else 0f)

    private fun menuAreaBottom() = cBot

    private fun menuCellH() = (menuAreaBottom() - menuAreaTop()) / 3.15f

    private fun menuCellRect(idx: Int, top: Float, out: RectF): RectF {
        val cw = width / COLS.toFloat()
        val ch = menuCellH()
        val x = (idx % COLS) * cw
        val y = menuAreaTop() + ((idx / COLS) - top) * ch
        out.set(x, y, x + cw, y + ch)
        return out
    }

    private fun recentRowRect(i: Int, top: Float, out: RectF): RectF {
        val y0 = cTop + HEADER * sc
        out.set(0f, y0 + (i - top) * ROW * sc, width.toFloat(), y0 + (i - top + 1) * ROW * sc)
        return out
    }

    private fun recentVisible(): Int = max(1, ((cBot - cTop - HEADER * sc) / (ROW * sc)).toInt())

    private fun popupTitleH(pu: Popup) = if (pu.menu) 0f else 50f * sc

    private fun popupItemH(pu: Popup): Float {
        val full = POPUP_ROW * sc
        return full
    }

    /** How many rows fit: a long menu scrolls instead of running off a small screen. */
    private fun popupVis(pu: Popup): Int {
        val ih = popupItemH(pu)
        val avail = if (pu.menu) height - statusH - (4f + 6f + 50f + 8f) * sc else (cBot - cTop) - popupTitleH(pu) - 10f * sc
        return max(1, min(pu.items.size, (avail / ih).toInt()))
    }

    private fun popupRect(pu: Popup, out: RectF): RectF {
        val ih = popupItemH(pu)
        val vis = popupVis(pu)
        if (pu.menu) {
            val bh = ih * vis + 4f * sc + 6f * sc + 50f * sc + 8f * sc
            out.set(0f, height - bh, width.toFloat(), height.toFloat())
        } else {
            val bw = min(504f * sc, width - 16f * sc) // sizeDialogMax
            val bh = popupTitleH(pu) + ih * vis + 6f * sc
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

    private fun nine(c: Canvas, name: String, to: RectF, srcBorder: Int = 40, dstBorder: Float = 20f, alpha: Int = 255) =
        gfx.nine(c, name, to, srcBorder, dstBorder * sc, alpha)

    /** Baseline that vertically centres text of [size] on [cy]. */
    private fun baselineFor(cy: Float, size: Float, face: Typeface = sans): Float {
        tp.textSize = size
        tp.typeface = face
        val fm = tp.fontMetrics
        return cy - (fm.ascent + fm.descent) / 2f
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

    /** Belle highlight: the theme's blue pressed frame (qtg_fr_list_pressed). */
    private fun drawFocus(c: Canvas, r: RectF, radius: Float) {
        if (nine(c, "qtg_fr_list_pressed", r, 40, radius / sc)) return
        p.style = Paint.Style.FILL
        p.color = theme.accent
        c.drawRoundRect(r, radius, radius, p)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = System.nanoTime()
        var dt = (now - lastNs) / 1e9f
        lastNs = now
        if (dt > 0.05f || dt <= 0f) dt = 0.016f
        val k = 1f - exp(-dt * 18f)
        frameK = k
        var busy = false

        pageAnim += (pagePos() - pageAnim) * k
        if (abs(pagePos() - pageAnim) < 0.004f) pageAnim = pagePos().toFloat() else busy = true
        menuTopAnim += (menuTop - menuTopAnim) * k
        if (abs(menuTop - menuTopAnim) < 0.004f) menuTopAnim = menuTop.toFloat() else busy = true
        recentTopAnim += (recentTop - recentTopAnim) * k
        if (abs(recentTop - recentTopAnim) < 0.004f) recentTopAnim = recentTop.toFloat() else busy = true

        homeFocusRect(tmp)
        homeFocus.target(tmp)
        menuCellRect(menuSel, menuTop.toFloat(), tmp)
        tmp.inset(6f * sc, 3f * sc)
        menuFocus.target(tmp)
        recentRowRect(recentSel, recentTop.toFloat(), tmp)
        recentFocus.target(tmp)
        if (homeFocus.step(k)) busy = true
        if (menuFocus.step(k)) busy = true
        if (recentFocus.step(k)) busy = true

        // no background is painted: the window shows the system wallpaper
        p.style = Paint.Style.FILL
        p.shader = null

        for (i in -1..1) {
            val d = i - pageAnim
            if (abs(d) >= 1f) continue
            c.save()
            c.clipRect(0f, cTop, w, cBot)
            c.translate(d * w, 0f)
            when (i) {
                -1 -> drawHub(c)
                0 -> drawHome(c)
                else -> if (tab == 2) drawRecent(c) else drawMenu(c)
            }
            c.restore()
        }

        drawStatusBar(c, w)
        drawToolbar(c, w, h)
        if (popup != null) popupShown = popup
        val popupTarget = if (popup != null) 1f else 0f
        popupT += (popupTarget - popupT) * k
        if (abs(popupTarget - popupT) < 0.01f) popupT = popupTarget else busy = true
        if (popupT <= 0f && popup == null) popupShown = null
        popupShown?.let { drawPopup(c, it, w) }

        if (System.currentTimeMillis() < toastUntil) {
            drawToast(c, w)
            busy = true
        }
        if (busy) postInvalidateOnAnimation() else if (hubTick) {
            hubTick = false
            postInvalidateDelayed(2000)
        }
    }

    // ---- page 0: home ----------------------------------------------------------------------

    /** One-row Belle home screen widget plate (widget_belle00 from the Symbian HomeScreen widget template). */
    private fun widgetPlate(c: Canvas, r: RectF) {
        if (!nine(c, "widget_plate", r, 20, 14f)) {
            p.style = Paint.Style.FILL
            p.color = 0xFF1A1A1A.toInt()
            c.drawRoundRect(r, 12f * sc, 12f * sc, p)
        }
    }

    /** 24x18 Belle battery meter, filled to [pct]. */
    private fun drawBattery(c: Canvas, left: Float, top: Float, bw: Float, bh: Float) {
        rf.set(left, top, left + bw, top + bh)
        val bg = gfx.get("qtg_graf_battery_level_bg")
        val full = gfx.get("qtg_graf_battery_level_full")
        if (bg != null) c.drawBitmap(bg, null, rf, iconPaint)
        if (full != null) {
            c.save()
            c.clipRect(left, top, left + bw * (0.12f + 0.76f * batteryPct / 100f), top + bh)
            c.drawBitmap(full, null, rf, iconPaint)
            c.restore()
        }
    }

    // =====================================================================================
    // the Belle home screen: a grid of shortcuts and widgets you arrange yourself
    // =====================================================================================

    private class HomeItem(val widget: Boolean, val id: String, var col: Int, var row: Int, var w: Int = 1, var h: Int = 1) {
        val rect = RectF()
        val subs = Array(3) { RectF() }
        val type get() = id.toIntOrNull() ?: -1
    }

    private val homeItems = ArrayList<HomeItem>()
    private var homeIdx = 0
    private var homeEdit = false
    private var carrying = false
    private var homeLoaded = false
    private var curWidget = -1
    private val HC = 4
    private val HR = 3
    private var appMap: Map<String, AppEntry> = emptyMap()

    private fun appFor(it: HomeItem): AppEntry? = if (it.widget) null else (appMap[it.id] ?: repo.apps.firstOrNull { a -> a.key == it.id }?.also { a -> appMap = appMap + (a.key to a) })

    private fun widgetSizeOf(t: Int) = when (t) { 3 -> 4 to 3; 2, 5 -> 4 to 2; else -> 4 to 1 }

    private fun homeCell(col: Int, row: Int, w: Int, h: Int, out: RectF): RectF {
        val top = cTop + 6f * sc
        val bot = cBot - 6f * sc
        val cw = width / HC.toFloat()
        val ch = (bot - top) / HR
        out.set(col * cw + 5f * sc, top + row * ch + 3f * sc, (col + w) * cw - 5f * sc, top + (row + h) * ch - 3f * sc)
        return out
    }

    private fun layoutHome() {
        for (it in homeItems) homeCell(it.col, it.row, it.w, it.h, it.rect)
    }

    private fun occupied(col: Int, row: Int, w: Int, h: Int, ignore: HomeItem?): Boolean =
        homeItems.any { it !== ignore && col < it.col + it.w && col + w > it.col && row < it.row + it.h && row + h > it.row }

    private fun findFree(w: Int, h: Int): Pair<Int, Int>? {
        for (r in 0..HR - h) for (c in 0..HC - w) if (!occupied(c, r, w, h, null)) return c to r
        return null
    }

    private fun saveHome() {
        repo.homeLayout = homeItems.joinToString(";") { "${if (it.widget) "w" else "a"},${it.id},${it.col},${it.row},${it.w},${it.h}" }
    }

    private fun loadHome() {
        homeItems.clear()
        val raw = repo.homeLayout
        if (raw == null) {
            // first run: a flip clock on top and the old four shortcuts underneath
            homeItems.add(HomeItem(true, "0", 0, 0, 4, 1))
            var i = 0
            for (app in repo.favoriteEntries()) {
                homeItems.add(HomeItem(false, app.key, i % HC, 1 + i / HC))
                i++
                if (i >= HC * 2) break
            }
            saveHome()
        } else {
            for (rec in raw.split(';')) {
                val f = rec.split(',')
                if (f.size != 6) continue
                try {
                    homeItems.add(HomeItem(f[0] == "w", f[1], f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt()))
                } catch (_: NumberFormatException) {
                }
            }
            // shortcuts whose app is gone disappear
            homeItems.removeAll { !it.widget && repo.apps.none { a -> a.key == it.id } }
            // a layout saved for a taller grid is packed again into this one
            val keep = ArrayList<HomeItem>()
            val again = ArrayList<HomeItem>()
            for (it in homeItems) {
                if (it.col + it.w <= HC && it.row + it.h <= HR && keep.none { o -> it.col < o.col + o.w && it.col + it.w > o.col && it.row < o.row + o.h && it.row + it.h > o.row }) keep.add(it) else again.add(it)
            }
            homeItems.clear()
            homeItems.addAll(keep)
            for (it in again) {
                val at = findFree(it.w, min(it.h, HR)) ?: continue
                it.col = at.first
                it.row = at.second
                it.h = min(it.h, HR)
                homeItems.add(it)
            }
        }
        homeIdx = homeIdx.coerceIn(0, max(0, homeItems.size - 1))
        homeStart()
    }

    private fun hasWidget(t: Int) = homeItems.any { it.widget && it.type == t }

    /** Starts what the widgets on screen need: the music listener, the calendar, the notes. */
    private fun homeStart() {
        media.stop()
        if (hasWidget(2)) media.start()
        if (hasWidget(1)) calendar.refresh()
        if (hasWidget(5)) loadNotes()
    }

    private fun homeHas(key: String) = homeItems.any { !it.widget && it.id == key }

    private fun addHomeApp(key: String): Boolean {
        if (homeHas(key)) return true
        val at = findFree(1, 1) ?: return false
        homeItems.add(HomeItem(false, key, at.first, at.second))
        saveHome()
        return true
    }

    private fun removeHomeApp(key: String) {
        homeItems.removeAll { !it.widget && it.id == key }
        homeIdx = homeIdx.coerceIn(0, max(0, homeItems.size - 1))
        saveHome()
    }

    private fun addWidget(type: Int) {
        val (w, h) = widgetSizeOf(type)
        val at = findFree(w, h)
        if (at == null) {
            showToast("No room - remove or shrink something first")
            return
        }
        homeItems.add(HomeItem(true, type.toString(), at.first, at.second, w, h))
        homeIdx = homeItems.size - 1
        widgetSub = if (type == 2 || type == 4 || type == 5) 1 else 0
        saveHome()
        homeStart()
        if (type == 1 && !calendar.hasPermission()) act.requestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR), 31)
    }

    private fun removeCurrent() {
        if (homeItems.isEmpty()) return
        homeItems.removeAt(homeIdx.coerceIn(0, homeItems.size - 1))
        homeIdx = homeIdx.coerceIn(0, max(0, homeItems.size - 1))
        carrying = false
        saveHome()
        homeStart()
    }

    private fun resizeCurrent() {
        val it = homeItems.getOrNull(homeIdx) ?: return
        if (!it.widget) return
        for (step in 1..3) {
            val nh = (it.h - 1 + step) % 3 + 1
            if (it.row + nh <= HR && !occupied(it.col, it.row, it.w, nh, it)) {
                it.h = nh
                saveHome()
                return
            }
        }
        showToast("No room to resize")
    }

    private fun homeFocusRect(out: RectF): RectF {
        layoutHome()
        val it = homeItems.getOrNull(homeIdx)
        if (it == null) {
            out.set(0f, 0f, 0f, 0f)
            return out
        }
        val sub = if (it.widget && (it.type == 2 || it.type == 4 || it.type == 5) && widgetSub in 0..2 && !it.subs[widgetSub].isEmpty && !homeEdit) it.subs[widgetSub] else null
        if (sub != null) {
            out.set(sub)
            out.inset(-5f * sc, -5f * sc)
        } else {
            out.set(it.rect)
            out.inset(-4f * sc, -3f * sc)
        }
        return out
    }

    private fun homeKey(a: Act) {
        layoutHome()
        val cur = homeItems.getOrNull(homeIdx)
        if (homeEdit) {
            when (a) {
                Act.OK -> {
                    carrying = !carrying
                    if (!carrying) saveHome()
                }
                Act.BACK, Act.SETTINGS -> {
                    homeEdit = false
                    carrying = false
                    saveHome()
                    showToast("Home saved")
                }
                Act.OPTIONS -> openHomeMenu()
                Act.LEFT, Act.RIGHT, Act.UP, Act.DOWN -> if (carrying && cur != null) {
                    val nc = cur.col + if (a == Act.LEFT) -1 else if (a == Act.RIGHT) 1 else 0
                    val nr = cur.row + if (a == Act.UP) -1 else if (a == Act.DOWN) 1 else 0
                    if (nc in 0..HC - cur.w && nr in 0..HR - cur.h && !occupied(nc, nr, cur.w, cur.h, cur)) {
                        cur.col = nc
                        cur.row = nr
                    }
                } else homeMove(a)
                else -> {}
            }
            return
        }
        when (a) {
            Act.OK -> if (cur != null) {
                if (cur.widget) {
                    curWidget = cur.type
                    widgetActivate()
                } else appFor(cur)?.let { launch(it) }
            } else openHomeMenu()
            Act.LEFT, Act.RIGHT, Act.UP, Act.DOWN -> homeMove(a)
            else -> {}
        }
    }

    private fun homeMove(a: Act) {
        val cur = homeItems.getOrNull(homeIdx) ?: return
        if (cur.widget && (a == Act.LEFT || a == Act.RIGHT) && (cur.type == 2 || cur.type == 4 || cur.type == 5) && !homeEdit) {
            val n = widgetSub + if (a == Act.LEFT) -1 else 1
            if (n in 0..2) {
                widgetSub = n
                return
            }
        }
        val cx = cur.rect.centerX()
        val cy = cur.rect.centerY()
        var best = -1
        var bestScore = Float.MAX_VALUE
        for ((i, o) in homeItems.withIndex()) {
            if (o === cur) continue
            val dx = o.rect.centerX() - cx
            val dy = o.rect.centerY() - cy
            val ok = when (a) { Act.LEFT -> dx < -1f; Act.RIGHT -> dx > 1f; Act.UP -> dy < -1f; else -> dy > 1f }
            if (!ok) continue
            val horiz = a == Act.LEFT || a == Act.RIGHT
            val score = (if (horiz) abs(dx) else abs(dy)) + 2.5f * (if (horiz) abs(dy) else abs(dx))
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        if (best >= 0) {
            homeIdx = best
            val n = homeItems[best]
            widgetSub = if (n.widget && (n.type == 2 || n.type == 4 || n.type == 5)) (if (a == Act.LEFT) 2 else 0).let { if (a == Act.UP || a == Act.DOWN) 1 else it } else 0        } else if (!homeEdit) {
            // nothing further that way: the Hub is to the left, the Menu to the right
            if (a == Act.LEFT) setPage(-1) else if (a == Act.RIGHT) setPage(1)
        }
    }

    private fun homeTap(x: Float, y: Float, selectOnly: Boolean): Boolean {
        layoutHome()
        val idx = homeItems.indexOfLast { it.rect.contains(x, y) }
        if (homeEdit) {
            val cur = homeItems.getOrNull(homeIdx)
            if (idx >= 0) {
                val it = homeItems[idx]
                // the corner cross removes
                if (!selectOnly && x > it.rect.right - 30f * sc && y < it.rect.top + 30f * sc) {
                    homeIdx = idx
                    removeCurrent()
                    return true
                }
                homeIdx = idx
                if (!selectOnly) carrying = !carrying
                if (!carrying) saveHome()
                return true
            }
            if (carrying && cur != null && !selectOnly) {
                val cw = width / HC.toFloat()
                val ch = (cBot - cTop - 12f * sc) / HR
                val nc = (x / cw).toInt().coerceIn(0, HC - cur.w)
                val nr = ((y - cTop - 6f * sc) / ch).toInt().coerceIn(0, HR - cur.h)
                if (!occupied(nc, nr, cur.w, cur.h, cur)) {
                    cur.col = nc
                    cur.row = nr
                }
                return true
            }
            return false
        }
        if (idx < 0) return false
        homeIdx = idx
        val it = homeItems[idx]
        if (it.widget) {
            widgetSub = -1
            for (i in 0..2) if (it.subs[i].contains(x, y)) widgetSub = i
            if (widgetSub < 0) widgetSub = if (it.type == 2 || it.type == 4 || it.type == 5) 1 else 0
            if (!selectOnly) {
                curWidget = it.type
                widgetActivate()
            }
        } else if (!selectOnly) appFor(it)?.let { a -> launch(a) }
        return true
    }

    private fun drawHome(c: Canvas) {
        val w = width.toFloat()
        layoutHome()
        p.style = Paint.Style.FILL
        p.shader = null
        if (homeEdit) {
            // the arranging grid, as in the Belle guidelines
            p.color = al(Color.BLACK, 110)
            c.drawRect(0f, cTop, w, cBot, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1f * sc
            p.color = al(Color.WHITE, 60)
            for (cc in 0 until HC) for (rr in 0 until HR) {
                homeCell(cc, rr, 1, 1, rf)
                c.drawRect(rf, p)
            }
            p.style = Paint.Style.FILL
        }
        val cur = homeItems.getOrNull(homeIdx)
        val subFocus = !homeEdit && cur != null && cur.widget && (cur.type == 2 || cur.type == 4 || cur.type == 5) && widgetSub in 0..2 && !cur.subs[widgetSub].isEmpty
        if (!subFocus) drawFocus(c, homeFocus.cur, 12f * sc)
        for (it in homeItems) {
            val lift = if (homeEdit && carrying && it === cur) 5f * sc else 0f
            c.save()
            c.translate(0f, -lift)
            if (it.widget) {
                for (k in subRects) k.setEmpty()
                drawLarge(c, it.type, it.rect)
                for (k in 0..2) it.subs[k].set(subRects[k])
            } else {
                val app = appFor(it)
                if (app != null) {
                    val r = it.rect
                    val isz = min(r.height() * 0.62f, 76f * sc) // the same icon size as the Menu
                    val top = r.top + 3f * sc
                    drawIcon(c, app.icon, r.centerX(), top + isz / 2f, isz)
                    val tsz = 15f * sc
                    val tw = min(measure(app.label, tsz) + 14f * sc, r.width() - 4f * sc)
                    rf.set(r.centerX() - tw / 2f, top + isz + 3f * sc, r.centerX() + tw / 2f, top + isz + 23f * sc)
                    p.color = al(Color.BLACK, 150)
                    c.drawRoundRect(rf, 5f * sc, 5f * sc, p)
                    text(c, app.label, r.centerX(), baselineFor(rf.centerY(), tsz), tsz, Color.WHITE, Paint.Align.CENTER, tw - 8f * sc)
                }
            }
            if (homeEdit) {
                // the white cross on the corner of everything you can remove
                val cx = it.rect.right - 12f * sc
                val cy = it.rect.top + 12f * sc
                p.color = Color.WHITE
                c.drawCircle(cx, cy, 10f * sc, p)
                p.color = Color.BLACK
                p.strokeWidth = 2.2f * sc
                p.style = Paint.Style.STROKE
                c.drawLine(cx - 4.5f * sc, cy - 4.5f * sc, cx + 4.5f * sc, cy + 4.5f * sc, p)
                c.drawLine(cx - 4.5f * sc, cy + 4.5f * sc, cx + 4.5f * sc, cy - 4.5f * sc, p)
                p.style = Paint.Style.FILL
            }
            c.restore()
        }
        if (subFocus && cur != null) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f * sc
            p.color = theme.accentLight
            val ring = homeFocus.cur
            c.drawRoundRect(ring, ring.height() / 2f, ring.height() / 2f, p)
            p.style = Paint.Style.FILL
        }
        if (homeItems.isEmpty()) {
            text(c, "Press X for Options to add widgets and shortcuts", w / 2f, cTop + (cBot - cTop) / 2f, 18f * sc, al(Color.WHITE, 200), Paint.Align.CENTER, w - 40f * sc, face = light)
        }
    }

    private fun readStorage() {
        try {
            val st = StatFs(Environment.getDataDirectory().path)
            storageFree = st.availableBytes
            storageTotal = max(1L, st.totalBytes)
        } catch (_: Exception) {
        }
    }

    private fun gb(b: Long) = if (b >= 1_000_000_000L) "%.1f GB".format(b / 1e9) else "%.0f MB".format(b / 1e6)

    private fun isToday(t: Long): Boolean {
        val d0 = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return t >= d0
    }

    private fun drawWidget(c: Canvas, type: Int, r: RectF) {
        widgetPlate(c, r)
        val cy = r.centerY()
        val grey = 0xFF999999.toInt()
        val left = r.left + 18f * sc
        when (type) {
            WIDGET_DATE -> {
                val dsz = 56f * sc
                text(c, dayStr, left, baselineFor(cy, dsz), dsz, Color.WHITE)
                val dx = left + measure(dayStr, dsz) + 16f * sc
                text(c, weekdayStr, dx, baselineFor(cy - 12f * sc, 20f * sc), 20f * sc, Color.WHITE, maxW = r.right - dx - 10f * sc)
                text(c, dayMonthStr.substringAfter(' ', dayMonthStr), dx, baselineFor(cy + 13f * sc, 18f * sc, light), 18f * sc, grey, maxW = r.right - dx - 10f * sc, face = light)
            }
            WIDGET_BATTERY -> {
                val bw = 60f * sc
                val bh = bw * 18f / 24f
                drawBattery(c, left, cy - bh / 2f, bw, bh)
                val px = left + bw + 16f * sc
                text(c, "$batteryPct%", px, baselineFor(cy - 8f * sc, 40f * sc), 40f * sc, Color.WHITE)
                text(c, if (charging) "Charging" else "Battery", px, baselineFor(cy + 24f * sc, 18f * sc, light), 18f * sc, grey, face = light)
            }
            WIDGET_STORAGE -> {
                text(c, "Storage", left, baselineFor(r.top + 22f * sc, 17f * sc, light), 17f * sc, grey, face = light)
                text(c, "${gb(storageFree)} free", left, baselineFor(r.top + 48f * sc, 26f * sc), 26f * sc, Color.WHITE, maxW = r.width() - 36f * sc)
                tmp.set(left, r.bottom - 30f * sc, r.right - 18f * sc, r.bottom - 10f * sc)
                drawBar(c, tmp, (storageTotal - storageFree).toFloat() / storageTotal)
            }
            WIDGET_NOTES -> {
                HubStore.load(act)
                val today = HubStore.notes.count { isToday(it.time) }
                val num = if (HubStore.hasAccess(act)) "$today" else "!"
                val nsz = 50f * sc
                text(c, num, left, baselineFor(cy, nsz), nsz, Color.WHITE)
                val nx = left + measure(num, nsz) + 14f * sc
                val head = when {
                    !HubStore.hasAccess(act) -> "Allow access"
                    today == 1 -> "notification today"
                    else -> "notifications today"
                }
                text(c, head, nx, baselineFor(cy - 12f * sc, 18f * sc), 18f * sc, Color.WHITE, maxW = r.right - nx - 10f * sc)
                text(c, HubStore.notes.firstOrNull()?.title?.ifBlank { null } ?: "Tap to open the Hub", nx, baselineFor(cy + 14f * sc, 16f * sc, light), 16f * sc, grey, maxW = r.right - nx - 10f * sc, face = light)
            }
            else -> { // clock
                val tsz = 46f * sc
                text(c, clockStr, left, baselineFor(cy, tsz), tsz, Color.WHITE)
                var colX = left + measure(clockStr, tsz)
                if (ampmStr.isNotEmpty()) {
                    text(c, ampmStr, colX + 6f * sc, baselineFor(cy, tsz), 16f * sc, theme.accentLight)
                    colX += 6f * sc + measure(ampmStr, 16f * sc)
                }
                val dx = max(colX + 16f * sc, r.left + 150f * sc)
                text(c, weekdayStr, dx, baselineFor(cy - 12f * sc, 20f * sc), 20f * sc, Color.WHITE, maxW = r.right - dx - 10f * sc)
                text(c, dayMonthStr, dx, baselineFor(cy + 13f * sc, 18f * sc, light), 18f * sc, grey, maxW = r.right - dx - 10f * sc, face = light)
            }
        }
    }

    /** Belle progress bar at its natural 20px height. */
    private fun drawBar(c: Canvas, r: RectF, frac: Float) {
        nine(c, "qtg_fr_progressbar_track", r, 20, 10f)
        if (frac > 0.01f) {
            val f = RectF(r.left, r.top, r.left + max(r.height(), r.width() * frac.coerceIn(0f, 1f)), r.bottom)
            nine(c, "qtg_fr_progressbar_fill", f, 20, 10f)
        }
    }

    private fun openOther(pkg: String) {
        val i = act.packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) tryStart(i) else showToast("That app isn't installed")
    }

    private fun widgetTap(type: Int) {
        when (type) {
            WIDGET_NOTES -> setPage(-1)
            WIDGET_STORAGE -> openOther("com.belleui.files")
            WIDGET_BATTERY -> openOther("com.belleui.settings")
            else -> openOther("com.belleui.clock")
        }
    }

    private val largeNames = arrayOf("Two small widgets", "Flip clock", "Today", "Music player", "Notifications", "Quick switches", "Notes")

    /** Options on the Home: arrange, add and remove (the Belle "arranging items" flow). */
    private fun openHomeMenu() {
        val cur = homeItems.getOrNull(homeIdx)
        val items = ArrayList<PopupItem>()
        items += PopupItem("Hub") { setPage(-1) }
        if (homeEdit) {
            items += PopupItem("Done") {
                homeEdit = false
                carrying = false
                saveHome()
            }
        } else items += PopupItem("Arrange items") {
            homeEdit = true
            carrying = false
            showToast("${okLetter()} picks up and drops, ${backLetter()} is done")
        }
        if (cur != null) {
            if (cur.widget) items += PopupItem("Resize widget") { resizeCurrent() }
            items += PopupItem(if (cur.widget) "Remove widget" else "Remove shortcut") { removeCurrent() }
        }
        items += PopupItem("Add widget") {
            val names = arrayOf("Flip clock", "Today", "Music player", "Notifications", "Quick switches", "Notes")
            val order = intArrayOf(0, 1, 2, 3, 4, 5)
            popup = Popup("Add widget", order.map { t -> PopupItem(names[t]) { addWidget(t) } }, 0)
        }
        items += PopupItem("Add shortcut") {
            setPage(1)
            showToast("Pick an app, then Options > Add to home")
        }
        items += PopupItem("Quick settings") { openQuick(0) }
        items += PopupItem("Wallpaper") { tryStart(Intent(Intent.ACTION_SET_WALLPAPER)) }
        popup = Popup("Home", items, 0, menu = true)
    }

    // ---- large Belle widgets (after the Nokia Belle home screen) ---------------------------------------

    private fun widgetActivate() {
        when (curWidget) {
            -1 -> widgetTap(if (widgetSub == 0) repo.widgetLeft else repo.widgetRight)
            0 -> openOther("com.belleui.clock")
            1 -> {
                if (!calendar.hasPermission()) act.requestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR), 31)
                else tryStart(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("content://com.android.calendar/time")))
            }
            2 -> when {
                !media.available -> if (!HubStore.hasAccess(act)) tryStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) else openOther("com.belleui.music")
                widgetSub == 0 -> media.prev()
                widgetSub == 2 -> media.next()
                else -> media.toggle()
            }
            3 -> setPage(-1)
            5 -> when (widgetSub) {
                0 -> if (noteIdx > 0) noteIdx--
                2 -> if (noteIdx < notesList.size - 1) noteIdx++
                else -> openOther("com.belleui.notes")
            }
            4 -> when (widgetSub) {
                0 -> tryStart(Intent(if (Build.VERSION.SDK_INT >= 29) "android.settings.panel.action.WIFI" else Settings.ACTION_WIFI_SETTINGS))
                1 -> try {
                    val am = act.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    am.ringerMode = if (am.ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL) android.media.AudioManager.RINGER_MODE_VIBRATE else android.media.AudioManager.RINGER_MODE_NORMAL
                } catch (_: Exception) {
                    tryStart(Intent(Settings.ACTION_SOUND_SETTINGS))
                }
                else -> openQuick(0)
            }
        }
    }

    private fun drawLarge(c: Canvas, type: Int, r: RectF) {
        for (k in subRects) k.setEmpty()
        when (type) {
            0 -> drawFlip(c, r)
            1 -> drawToday(c, r)
            2 -> drawMusic(c, r)
            3 -> drawInbox(c, r)
            5 -> drawNotes(c, r)
            else -> drawSwitches(c, r)
        }
    }

    /** The latest notes, from the Notes app's read-only provider, off the main thread. */
    private fun loadNotes() {
        val now = System.currentTimeMillis()
        if (now - notesLoadedAt < 5000) return
        notesLoadedAt = now
        Thread {
            val out = ArrayList<Pair<String, String>>()
            try {
                act.contentResolver.query(android.net.Uri.parse("content://com.belleui.notes.provider/latest"), null, null, null, null)?.use { cur ->
                    while (cur.moveToNext() && out.size < 6) out.add(cur.getString(0) to (cur.getString(1) ?: ""))
                }
            } catch (_: Exception) {
            }
            notesList = out
            noteIdx = noteIdx.coerceIn(0, max(0, out.size - 1))
            postInvalidate()
        }.start()
    }

    /** The Belle notes widget: a yellow note with the latest note on it, a pen, and arrows to leaf through. */
    private fun drawNotes(c: Canvas, r: RectF) {
        loadNotes()
        val aspect = 624f / 524f
        val nh = r.height()
        val nw = min(nh * aspect, r.width() - 90f * sc)
        val box = RectF(r.centerX() - nw / 2f, r.top, r.centerX() + nw / 2f, r.top + nh)
        if (!gfx.draw(c, "qgn_graf_hs_notes_bg_8x6", box)) {
            p.style = Paint.Style.FILL
            p.color = 0xFFFFF4B8.toInt()
            c.drawRoundRect(box, 8f * sc, 8f * sc, p)
        }
        val ink = 0xFF3A3520.toInt()
        val n = notesList.getOrNull(noteIdx)
        val tx = box.left + 18f * sc
        val tw = box.width() - 36f * sc
        if (n == null) {
            text(c, "No notes yet", tx, box.top + 50f * sc, 20f * sc, ink, maxW = tw, face = medium)
            text(c, "Press A to write one", tx, box.top + 78f * sc, 16f * sc, al(ink, 170), maxW = tw, face = light)
        } else {
            text(c, n.first, tx, box.top + 46f * sc, 21f * sc, ink, maxW = tw, face = medium)
            var y = box.top + 76f * sc
            for (line in wrapText(n.second, 17f * sc, tw, 4)) {
                text(c, line, tx, y, 17f * sc, ink, maxW = tw, face = light)
                y += 24f * sc
            }
        }
        // pen
        gfx.draw(c, "qgn_graf_hs_notes_pen", RectF(box.right - 52f * sc, box.bottom - 78f * sc, box.right - 14f * sc, box.bottom - 40f * sc))
        // arrows and the note itself are the three buttons
        val cy = box.centerY()
        val prevOn = noteIdx > 0
        val nextOn = noteIdx < notesList.size - 1
        val pr = RectF(box.left - 44f * sc, cy - 22f * sc, box.left - 4f * sc, cy + 22f * sc)
        val nx = RectF(box.right + 4f * sc, cy - 22f * sc, box.right + 44f * sc, cy + 22f * sc)
        gfx.draw(c, if (prevOn) "qgn_indi_hs_notes_prev" else "qgn_indi_hs_notes_prev_disabled", pr)
        gfx.draw(c, if (nextOn) "qgn_indi_hs_notes_next" else "qgn_indi_hs_notes_next_disabled", nx)
        subRects[0].set(pr)
        subRects[1].set(box)
        subRects[2].set(nx)
    }

    private fun wrapText(text: String, size: Float, maxW: Float, maxLines: Int): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            var line = ""
            for (w in para.split(' ')) {
                val t = if (line.isEmpty()) w else "$line $w"
                if (measure(t, size, light) <= maxW) line = t else {
                    if (line.isNotEmpty()) out.add(line)
                    line = w
                    if (out.size >= maxLines) return out
                }
            }
            if (line.isNotEmpty()) out.add(line)
            if (out.size >= maxLines) return out.take(maxLines)
        }
        return out.take(maxLines)
    }

    private fun darkCard(c: Canvas, r: RectF, radius: Float) {
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, 0xFF3A3D42.toInt(), 0xFF0C0D0F.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f * sc
        p.color = al(Color.WHITE, 40)
        c.drawRoundRect(r, radius, radius, p)
        p.style = Paint.Style.FILL
    }

    private fun paperCard(c: Canvas, r: RectF) {
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, 0xFFF8F8F6.toInt(), 0xFFDADAD6.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, 12f * sc, 12f * sc, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f * sc
        p.color = al(Color.BLACK, 90)
        c.drawRoundRect(r, 12f * sc, 12f * sc, p)
        p.style = Paint.Style.FILL
    }

    /** Two flip cards: the Belle digital clock widget background and clock digits, with the AM/PM mark and the date. */
    private fun drawFlip(c: Canvas, r: RectF) {
        val hh = clockStr.substringBefore(':')
        val mm = clockStr.substringAfter(':', "00")
        if (dateShortFor != clockStr) {
            dateShortFor = clockStr
            dateShort = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date())
        }
        // the widget picture keeps its own proportions, centred
        val bgAspect = 624f / 254f
        val bw = min(r.width(), r.height() * bgAspect)
        val box = RectF(r.centerX() - bw / 2f, r.top, r.centerX() + bw / 2f, r.top + bw / bgAspect)
        if (!gfx.draw(c, "qgn_graf_hs_digital_bg_8x3", box)) {
            darkCard(c, RectF(box.left, box.top, box.centerX() - 4f * sc, box.bottom), 14f * sc)
            darkCard(c, RectF(box.centerX() + 4f * sc, box.top, box.right, box.bottom), 14f * sc)
        }
        val dh = box.height() * 0.5f
        val cy = box.centerY() - 3f * sc
        val lx = box.left + box.width() * 0.25f
        val rx = box.left + box.width() * 0.75f
        if (gfx.digitsWidth(hh, dh) > 0f) {
            gfx.digits(c, hh, lx, cy, dh, Paint.Align.CENTER)
            gfx.digits(c, mm, rx, cy, dh, Paint.Align.CENTER)
        } else {
            val size = box.height() * 0.62f
            text(c, hh, lx, baselineFor(cy, size), size, Color.WHITE, Paint.Align.CENTER, face = light)
            text(c, mm, rx, baselineFor(cy, size), size, Color.WHITE, Paint.Align.CENTER, face = light)
        }
        if (ampmStr.isNotEmpty()) text(c, ampmStr, box.right - 22f * sc, box.top + 30f * sc, 16f * sc, al(Color.WHITE, 220), Paint.Align.RIGHT, face = light)
        text(c, dateShort, box.right - 22f * sc, box.bottom - 16f * sc, 15f * sc, al(Color.WHITE, 190), Paint.Align.RIGHT, face = light)
    }

    /** A paper calendar: the Belle calendar widget paper, "Today" and what is coming in the next week. */
    private fun drawToday(c: Canvas, r: RectF) {
        if (!gfx.draw(c, "qgn_graf_hs_cal_bg_8x2", r)) paperCard(c, r)
        val ink = 0xFF2B2B2B.toInt()
        text(c, "Today", r.centerX(), r.top + 40f * sc, 22f * sc, ink, Paint.Align.CENTER, face = medium)
        val ev = calendar.events
        val grey = 0xFF6A6A6A.toInt()
        when {
            !calendar.hasPermission() -> text(c, "Press A to show your calendar", r.centerX(), baselineFor(r.top + 92f * sc, 19f * sc), 19f * sc, grey, Paint.Align.CENTER, maxW = r.width() - 30f * sc)
            ev.isEmpty() -> text(c, "No events for 7 days", r.centerX(), baselineFor(r.top + 92f * sc, 20f * sc), 20f * sc, ink, Paint.Align.CENTER)
            else -> {
                var y = r.top + 72f * sc
                val fmt = DateFormat.getTimeFormat(act)
                val day = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
                for (e in ev.take(3)) {
                    if (y + 20f * sc > r.bottom) break
                    val whenText = (if (isToday(e.begin) && e.begin < System.currentTimeMillis() + 24 * 3600_000L) "" else day.format(java.util.Date(e.begin)) + " ") + if (e.allDay) "all day" else fmt.format(java.util.Date(e.begin))
                    text(c, whenText, r.left + 18f * sc, y, 16f * sc, theme.accent, face = medium)
                    text(c, e.title, r.left + 18f * sc + 108f * sc, y, 18f * sc, ink, maxW = r.right - r.left - 144f * sc)
                    y += 28f * sc
                }
            }
        }
    }

    /** A round media button. */
    private fun mediaButton(c: Canvas, kind: Int, cx: Float, cy: Float, size: Float, focus: Boolean) {
        when (kind) {
            1 -> {
                val name = if (media.playing) "qgn_indi_mup2_button_pause" else "qgn_indi_mup2_button_play"
                val g = gfx.get(name)
                if (g != null) {
                    rf.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
                    c.drawBitmap(g, null, rf, iconPaint)
                }
            }
            else -> {
                // previous / next: two triangles, drawn so they are always crisp
                val s = size * 0.28f
                val dir = if (kind == 0) -1f else 1f
                p.style = Paint.Style.FILL
                p.color = Color.WHITE
                for (o in floatArrayOf(-s * 0.55f, s * 0.55f)) {
                    val path = Path()
                    path.moveTo(cx + o - dir * s * 0.7f, cy - s)
                    path.lineTo(cx + o + dir * s * 0.7f, cy)
                    path.lineTo(cx + o - dir * s * 0.7f, cy + s)
                    path.close()
                    c.drawPath(path, p)
                }
            }
        }
    }

    private fun drawMusic(c: Canvas, r: RectF) {
        darkCard(c, r, 16f * sc)
        val side = (r.height() - 28f * sc).coerceIn(70f * sc, 108f * sc)
        val art = RectF(r.left + 14f * sc, r.centerY() - side / 2f, r.left + 14f * sc + side, r.centerY() + side / 2f)
        val bmp = media.art
        c.save()
        c.clipPath(Path().apply { addRoundRect(art, 12f * sc, 12f * sc, Path.Direction.CW) })
        if (bmp != null) {
            val s = max(art.width() / bmp.width, art.height() / bmp.height)
            val sw = art.width() / s
            val sh = art.height() / s
            val src = android.graphics.Rect(((bmp.width - sw) / 2).toInt(), ((bmp.height - sh) / 2).toInt(), ((bmp.width + sw) / 2).toInt(), ((bmp.height + sh) / 2).toInt())
            c.drawBitmap(bmp, src, art, iconPaint)
        } else if (!nine(c, "qgn_graf_mup_npv_defalbumart", art, 0, 0f)) {
            p.color = 0xFF2A2A2A.toInt()
            c.drawRect(art, p)
        }
        c.restore()
        val x = art.right + 16f * sc
        val maxW = r.right - x - 14f * sc
        // title and artist on the upper half, the three keys centred on the lower half of the right side
        val top = art.top
        if (media.available) {
            text(c, media.title ?: "Playing", x, top + 26f * sc, 20f * sc, Color.WHITE, maxW = maxW)
            text(c, media.artist ?: "", x, top + 50f * sc, 16f * sc, 0xFFB5B5B5.toInt(), maxW = maxW, face = light)
        } else {
            text(c, "Music", x, top + 26f * sc, 20f * sc, Color.WHITE, maxW = maxW)
            text(c, if (!HubStore.hasAccess(act)) "Allow notification access to control playback" else "Nothing is playing - press A to open Music", x, top + 50f * sc, 15f * sc, 0xFFB5B5B5.toInt(), maxW = maxW, face = light)
        }
        val by = art.bottom - 24f * sc
        val xs = floatArrayOf(x + maxW * 0.18f, x + maxW * 0.5f, x + maxW * 0.82f)
        val sizes = floatArrayOf(38f * sc, 46f * sc, 38f * sc)
        for (i in 0..2) {
            mediaButton(c, i, xs[i], by, sizes[i], false)
            val h = sizes[i] / 2f + 3f * sc
            subRects[i].set(xs[i] - h, by - h, xs[i] + h, by + h)
        }
    }

    private fun drawInbox(c: Canvas, r: RectF) {
        paperCard(c, r)
        HubStore.load(act)
        val ink = 0xFF2B2B2B.toInt()
        val grey = 0xFF6A6A6A.toInt()
        // header strip
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, r.top, 0f, r.top + 38f * sc, al(theme.accentLight, 255), al(theme.accent, 255), Shader.TileMode.CLAMP)
        rf.set(r.left + 1f, r.top + 1f, r.right - 1f, r.top + 38f * sc)
        c.drawRoundRect(rf, 11f * sc, 11f * sc, p)
        p.shader = null
        text(c, "Notifications", r.left + 16f * sc, baselineFor(r.top + 19f * sc, 19f * sc, medium), 19f * sc, Color.WHITE, face = medium)
        val list = HubStore.notes.filter { it.title.isNotBlank() || it.text.isNotBlank() }.take(3)
        if (!HubStore.hasAccess(act)) {
            text(c, "Press A and allow notification access", r.centerX(), baselineFor(r.top + 100f * sc, 18f * sc), 18f * sc, grey, Paint.Align.CENTER, maxW = r.width() - 30f * sc)
            return
        }
        if (list.isEmpty()) {
            text(c, "No notifications yet", r.centerX(), baselineFor(r.top + 100f * sc, 19f * sc), 19f * sc, grey, Paint.Align.CENTER)
            return
        }
        val fmt = DateFormat.getTimeFormat(act)
        var y = r.top + 40f * sc
        val rowH = ((r.bottom - y - 6f * sc) / 3f).coerceAtMost(58f * sc)
        for ((i, n) in list.withIndex()) {
            if (i > 0) {
                p.color = al(Color.BLACK, 40)
                c.drawRect(r.left + 12f * sc, y, r.right - 12f * sc, y + 1f, p)
            }
            p.color = theme.accent
            c.drawCircle(r.left + 20f * sc, y + 19f * sc, 5f * sc, p)
            val t = fmt.format(java.util.Date(n.time))
            val tw = measure(t, 15f * sc, light)
            text(c, t, r.right - 14f * sc, y + 25f * sc, 15f * sc, theme.accent, Paint.Align.RIGHT, face = light)
            text(c, n.title.ifBlank { n.pkg }, r.left + 34f * sc, y + 25f * sc, 18f * sc, ink, maxW = r.width() - 34f * sc - tw - 30f * sc, face = medium)
            text(c, n.text, r.left + 34f * sc, y + 47f * sc, 16f * sc, grey, maxW = r.width() - 50f * sc, face = light)
            y += rowH
        }
    }

    private fun drawSwitches(c: Canvas, r: RectF) {
        darkCard(c, r, r.height() / 2f)
        val ringer = try { (act.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).ringerMode } catch (_: Exception) { 2 }
        val labels = arrayOf("Wi-Fi", if (ringer == android.media.AudioManager.RINGER_MODE_NORMAL) "Sound on" else "Quiet", "Quick settings")
        val on = booleanArrayOf(wifi || wifiOn(), ringer == android.media.AudioManager.RINGER_MODE_NORMAL, false)
        for (i in 0..2) {
            val cx = r.left + r.width() * (0.2f + 0.3f * i)
            val d = r.height() * 0.62f
            rf.set(cx - d / 2f, r.top + 8f * sc, cx + d / 2f, r.top + 8f * sc + d)
            subRects[i].set(rf)
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, rf.top, 0f, rf.bottom, if (on[i]) theme.accentLight else 0xFF6B7078.toInt(), if (on[i]) theme.accent else 0xFF3A3E44.toInt(), Shader.TileMode.CLAMP)
            c.drawOval(rf, p)
            p.shader = null
            p.color = Color.WHITE
            when (i) {
                0 -> { // wi-fi arcs
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 2.6f * sc
                    for (k in 1..3) c.drawArc(RectF(rf.centerX() - k * 6f * sc, rf.centerY() + 6f * sc - k * 6f * sc, rf.centerX() + k * 6f * sc, rf.centerY() + 6f * sc + k * 6f * sc), -135f, 90f, false, p)
                    p.style = Paint.Style.FILL
                    c.drawCircle(rf.centerX(), rf.centerY() + 7f * sc, 2.4f * sc, p)
                }
                1 -> { // speaker
                    val cx2 = rf.centerX() - 3f * sc
                    val path = Path()
                    path.moveTo(cx2 - 7f * sc, rf.centerY() - 4f * sc)
                    path.lineTo(cx2 - 2f * sc, rf.centerY() - 4f * sc)
                    path.lineTo(cx2 + 4f * sc, rf.centerY() - 9f * sc)
                    path.lineTo(cx2 + 4f * sc, rf.centerY() + 9f * sc)
                    path.lineTo(cx2 - 2f * sc, rf.centerY() + 4f * sc)
                    path.lineTo(cx2 - 7f * sc, rf.centerY() + 4f * sc)
                    path.close()
                    c.drawPath(path, p)
                    if (on[i]) {
                        p.style = Paint.Style.STROKE
                        p.strokeWidth = 2.2f * sc
                        c.drawArc(RectF(cx2 + 2f * sc, rf.centerY() - 8f * sc, cx2 + 14f * sc, rf.centerY() + 8f * sc), -50f, 100f, false, p)
                        p.style = Paint.Style.FILL
                    }
                }
                else -> { // three sliders
                    for (k in -1..1) c.drawRoundRect(rf.centerX() - 9f * sc, rf.centerY() + k * 7f * sc - 1.5f * sc, rf.centerX() + 9f * sc, rf.centerY() + k * 7f * sc + 1.5f * sc, 1.5f * sc, 1.5f * sc, p)
                    c.drawCircle(rf.centerX() - 3f * sc, rf.centerY() - 7f * sc, 3.2f * sc, p)
                    c.drawCircle(rf.centerX() + 4f * sc, rf.centerY(), 3.2f * sc, p)
                    c.drawCircle(rf.centerX() - 2f * sc, rf.centerY() + 7f * sc, 3.2f * sc, p)
                }
            }
            text(c, labels[i], cx, r.bottom - 8f * sc, 14f * sc, al(Color.WHITE, 210), Paint.Align.CENTER, face = light)
        }
    }

    // ---- Hub: everything that notified you, over the wallpaper ----------------------------------------

    private fun dayLabel(t: Long): String {
        val d0 = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return when {
            t >= d0 -> "Today"
            t >= d0 - 86_400_000L -> "Yesterday"
            else -> DateFormat.format("EEE d MMM", t).toString()
        }
    }

    private fun buildHub() {
        HubStore.load(act)
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (hubBuiltVersion == HubStore.version && hubBuiltDay == day) return
        hubBuiltVersion = HubStore.version
        hubBuiltDay = day
        val out = ArrayList<HubItem>()
        var last = ""
        for (n in HubStore.notes.toList()) {
            val l = dayLabel(n.time)
            if (l != last) {
                out.add(HubItem(l, null))
                last = l
            }
            out.add(HubItem(null, n))
        }
        hubItems = out
        if (hubItems.getOrNull(hubSel)?.note == null) hubSel = hubItems.indexOfFirst { it.note != null }.coerceAtLeast(0)
    }

    private fun hubStep(dir: Int) {
        var i = hubSel + dir
        while (i in hubItems.indices) {
            if (hubItems[i].note != null) {
                hubSel = i
                return
            }
            i += dir
        }
    }

    private fun openNote(n: Note) {
        try {
            val pi = HubStore.intents[n.key]
            if (pi != null) {
                pi.send()
                return
            }
        } catch (_: Exception) {
        }
        val i = act.packageManager.getLaunchIntentForPackage(n.pkg)
        if (i != null) tryStart(i) else showToast("Can't open that app")
    }

    private fun openHubOptions() {
        val note = hubItems.getOrNull(hubSel)?.note
        val items = ArrayList<PopupItem>()
        if (note != null) {
            items += PopupItem("Open") { openNote(note) }
            items += PopupItem("Remove") {
                HubStore.remove(act, note)
                showToast("Removed")
            }
            items += PopupItem("Clear all") {
                HubStore.clear(act)
                showToast("Hub cleared")
            }
        }
        items += PopupItem(if (HubStore.hasAccess(act)) "Notification access" else "Allow notification access") {
            tryStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        items += PopupItem("Quick settings") { openQuick(0) }
        popup = Popup("Hub", items, 0, menu = true)
    }

    private fun appLabel(pkg: String): String = appLabels.getOrPut(pkg) {
        try { act.packageManager.getApplicationLabel(act.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
    }

    private fun noteIcon(pkg: String): Bitmap? {
        noteIcons.get(pkg)?.let { return it }
        return try {
            val d = act.packageManager.getApplicationIcon(pkg)
            val b = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 72, 72)
            d.draw(Canvas(b))
            noteIcons.put(pkg, b)
            b
        } catch (_: Exception) {
            null
        }
    }

    private fun card(c: Canvas, r: RectF) {
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = al(Color.BLACK, 165)
        c.drawRoundRect(r, 10f * sc, 10f * sc, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * sc
        p.color = al(Color.WHITE, 45)
        c.drawRoundRect(r, 10f * sc, 10f * sc, p)
        p.style = Paint.Style.FILL
    }

    /** The Hub / Today page: not a black page like Apps and Recent, but cards over your wallpaper. */
    private fun drawHub(c: Canvas) {
        val w = width.toFloat()
        val m = 12f * sc
        val grey = 0xFF999999.toInt()
        hubHits.clear()
        buildHub()
        hubTick = true

        val hr = RectF(m, cTop + 10f * sc, w - m, cTop + 88f * sc)
        card(c, hr)
        val now = System.currentTimeMillis()
        shadowText(c, DateFormat.format("EEEE", now).toString(), hr.left + 16f * sc, baselineFor(hr.top + 28f * sc, 26f * sc), 26f * sc, Color.WHITE)
        text(c, DateFormat.format("d MMMM", now).toString(), hr.left + 16f * sc, baselineFor(hr.top + 58f * sc, 18f * sc, light), 18f * sc, grey, face = light)
        text(c, clockStr + (if (ampmStr.isEmpty()) "" else " $ampmStr"), hr.right - 16f * sc, baselineFor(hr.top + 34f * sc, 36f * sc), 36f * sc, Color.WHITE, Paint.Align.RIGHT)
        val count = hubItems.count { it.note != null }
        text(c, if (count == 0) "No notifications" else "$count notification${if (count == 1) "" else "s"}", hr.right - 16f * sc, baselineFor(hr.top + 60f * sc, 16f * sc, light), 16f * sc, grey, Paint.Align.RIGHT, face = light)

        val panelTop = hr.bottom + 8f * sc
        val avail = cBot - 10f * sc - panelTop
        val access = HubStore.hasAccess(act)
        if (!access || count == 0) {
            val cardR = RectF(m, panelTop, w - m, panelTop + min(avail, 120f * sc))
            card(c, cardR)
            text(c, if (access) "Nothing yet" else "See your notifications here", cardR.centerX(), baselineFor(cardR.centerY() - 16f * sc, 22f * sc), 22f * sc, Color.WHITE, Paint.Align.CENTER)
            text(c, if (access) "New notifications will collect on this page" else "Press ${okLetter()} to allow notification access", cardR.centerX(), baselineFor(cardR.centerY() + 18f * sc, 17f * sc, light), 17f * sc, grey, Paint.Align.CENTER, maxW = cardR.width() - 30f * sc, face = light)
            return
        }

        val rowH = 62f * sc
        val headH = 28f * sc
        var total = 0f
        for (it in hubItems) total += if (it.header != null) headH else rowH
        val panelH = min(avail, total + 12f * sc)
        val panel = RectF(m, panelTop, w - m, panelTop + panelH)
        card(c, panel)

        // keep the selected notification in view
        var y = 6f * sc
        var selTop = 0f
        var selBottom = 0f
        for ((i, it) in hubItems.withIndex()) {
            val h = if (it.header != null) headH else rowH
            if (i == hubSel) {
                selTop = y
                selBottom = y + h
            }
            y += h
        }
        if (selBottom - hubScrollTarget > panelH - 6f * sc) hubScrollTarget = selBottom - panelH + 6f * sc
        if (selTop - hubScrollTarget < 6f * sc) hubScrollTarget = selTop - 6f * sc
        hubScrollTarget = hubScrollTarget.coerceIn(0f, max(0f, total + 12f * sc - panelH))
        hubScroll += (hubScrollTarget - hubScroll) * frameK
        if (abs(hubScrollTarget - hubScroll) < 0.5f) hubScroll = hubScrollTarget else postInvalidateOnAnimation()

        c.save()
        c.clipRect(panel)
        var yy = panel.top + 6f * sc - hubScroll
        for ((i, it) in hubItems.withIndex()) {
            val h = if (it.header != null) headH else rowH
            if (yy + h >= panel.top && yy <= panel.bottom) {
                if (it.header != null) {
                    text(c, it.header, panel.left + 16f * sc, baselineFor(yy + h / 2f, 15f * sc, medium), 15f * sc, theme.accentLight, face = medium)
                    p.color = al(Color.WHITE, 40)
                    c.drawRect(panel.left + 12f * sc, yy + h - 2f * sc, panel.right - 12f * sc, yy + h - 1f * sc, p)
                } else {
                    val n = it.note!!
                    val rr = RectF(panel.left + 6f * sc, yy + 1f * sc, panel.right - 6f * sc, yy + h - 1f * sc)
                    if (i == hubSel) drawFocus(c, rr, 10f * sc)
                    val isz = 38f * sc
                    noteIcon(n.pkg)?.let { b ->
                        rf.set(rr.left + 10f * sc, rr.centerY() - isz / 2f, rr.left + 10f * sc + isz, rr.centerY() + isz / 2f)
                        c.drawBitmap(b, null, rf, iconPaint)
                    }
                    val tx = rr.left + 10f * sc + isz + 12f * sc
                    val meta = appLabel(n.pkg) + "  " + DateFormat.format(if (DateFormat.is24HourFormat(act)) "HH:mm" else "h:mm a", n.time)
                    val mw = min(measure(meta, 14f * sc, light), rr.width() * 0.4f)
                    text(c, meta, rr.right - 12f * sc, baselineFor(rr.top + 22f * sc, 14f * sc, light), 14f * sc, if (i == hubSel) Color.WHITE else grey, Paint.Align.RIGHT, maxW = rr.width() * 0.4f, face = light)
                    text(c, n.title.ifBlank { appLabel(n.pkg) }, tx, baselineFor(rr.top + 22f * sc, 18f * sc, medium), 18f * sc, Color.WHITE, maxW = rr.right - tx - mw - 24f * sc, face = medium)
                    if (n.text.isNotBlank()) text(c, n.text.replace('\n', ' '), tx, baselineFor(rr.top + 46f * sc, 15f * sc, light), 15f * sc, if (i == hubSel) Color.WHITE else 0xFFBBBBBB.toInt(), maxW = rr.right - tx - 14f * sc, face = light)
                    hubHits.add(RectF(rr) to i)
                }
            }
            yy += h
        }
        c.restore()
    }

    // ---- page 1: applications (tab 1) -- black page, 70px icon frames, 18px labels -----------------

    private fun drawMenu(c: Canvas) {
        val w = width.toFloat()
        val area = menuAreaTop()
        p.style = Paint.Style.FILL
        p.shader = null
        // the wallpaper shows through, darkened, like the Belle Menu
        p.color = al(Color.BLACK, 170)
        c.drawRect(0f, cTop, w, cBot, p)
        if (menuApps.isEmpty()) {
            val msg = if (!loaded) "Loading..." else if (searchQuery.isNotEmpty()) "No match" else "No apps"
            text(c, msg, w / 2, (area + cBot) / 2, 22f * sc, Color.WHITE, Paint.Align.CENTER)
        } else {
            drawFocus(c, menuFocus.cur, 14f * sc)
            val cw = w / COLS
            val ch = menuCellH()
            val isz = 76f * sc
            for (idx in menuApps.indices) {
                menuCellRect(idx, menuTopAnim, tmp)
                if (tmp.bottom < area || tmp.top > cBot) continue
                val app = menuApps[idx]
                val dim = if (repo.isHidden(app.key)) 110 else 255
                val iconTop = tmp.top + 8f * sc
                val lift = if (reorder && idx == menuSel) 7f * sc else 0f
                drawIcon(c, app.icon, tmp.centerX(), iconTop + isz / 2f - lift, isz, dim)
                text(c, app.label, tmp.centerX(), iconTop + isz + 26f * sc, 21f * sc, al(Color.WHITE, dim), Paint.Align.CENTER, cw - 10f * sc)
            }
            val rows = (menuApps.size + COLS - 1) / COLS
            if (rows > 3) {
                val trackT = area + 6f * sc
                val trackH = menuAreaBottom() - area - 12f * sc
                val thumbH = trackH * 3f / rows
                val thumbT = trackT + (trackH - thumbH) * (menuTopAnim / (rows - 3).toFloat()).coerceIn(0f, 1f)
                rf.set(w - 6f * sc, thumbT, w - 3f * sc, thumbT + thumbH)
                p.color = al(Color.WHITE, 150)
                c.drawRoundRect(rf, 1.5f * sc, 1.5f * sc, p)
            }
        }
    }

    /** Belle tab bar: right under the status bar, full width, theme tab frames. */
    private fun drawTabBar(c: Canvas, selected: Int) {
        val w = width.toFloat()
        val h = TAB * sc
        val labels = arrayOf("Applications", "Recent")
        val kinds = intArrayOf(ICON_GRID, ICON_CLOCK)
        val tw = w / labels.size
        rf.set(0f, cTop, w, cTop + h)
        p.style = Paint.Style.FILL
        p.color = Color.BLACK
        c.drawRect(rf, p)
        nine(c, "qtg_fr_tab_bar", rf, 40, 20f)
        for (i in labels.indices) {
            val sel = i == selected
            rf.set(i * tw, cTop, (i + 1) * tw, cTop + h)
            if (sel) {
                p.style = Paint.Style.FILL
                p.color = Color.BLACK
                c.drawRect(rf, p)
                nine(c, "qtg_fr_tab_active", rf, 40, 20f, 190)
            } else {
                nine(c, "qtg_fr_tab_passive_normal", rf, 40, 20f)
            }
            if (sel && tabFocus) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3f * sc
                p.color = theme.accentLight
                c.drawRect(rf.left + 2f * sc, rf.top + 2f * sc, rf.right - 2f * sc, rf.bottom - 2f * sc, p)
                p.style = Paint.Style.FILL
            }
            val col = if (sel) Color.WHITE else 0xFF999999.toInt()
            val tsz = 18f * sc
            val face = if (sel) sans else light
            val textW = measure(labels[i], tsz, face)
            val total = 30f * sc + 8f * sc + textW
            val sx = rf.centerX() - total / 2f
            glyph(c, kinds[i], sx + 15f * sc, cTop + h / 2f, 26f * sc, col)
            text(c, labels[i], sx + 38f * sc, baselineFor(cTop + h / 2f, tsz, face), tsz, col, face = face)
        }
    }

    // ---- page 2: recent (tab 2) -- ListItem: 70px, 12px inset, 50px icon, 22px title, 18px subtitle -

    private fun drawRecent(c: Canvas) {
        val w = width.toFloat()
        val listTop = cTop + HEADER * sc
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Color.BLACK
        c.drawRect(0f, cTop, w, cBot, p)
        if (recents.isEmpty()) {
            text(c, "No recent apps", w / 2, (listTop + cBot) / 2, 22f * sc, 0xFF999999.toInt(), Paint.Align.CENTER)
        } else {
            for (i in recents.indices) {
                recentRowRect(i, recentTopAnim, tmp)
                if (tmp.bottom < listTop || tmp.top > cBot) continue
                p.color = 0xFF444444.toInt() // colorDisabledMid separator
                c.drawRect(0f, tmp.bottom - 1f, w, tmp.bottom, p)
            }
            // pressed / highlighted frame (qtg_fr_list_pressed): Belle blue with a white fade from the top
            nine(c, "qtg_fr_list_pressed", recentFocus.cur, 40, 20f)
            for (i in recents.indices) {
                recentRowRect(i, recentTopAnim, tmp)
                if (tmp.bottom < listTop || tmp.top > cBot) continue
                val app = recents[i]
                val sel = i == recentSel
                val pad = 12f * sc // paddingLarge
                drawIcon(c, app.icon, pad + 25f * sc, tmp.centerY(), 50f * sc)
                val tx = pad + 50f * sc + pad
                text(c, app.label, tx, tmp.centerY() - 4f * sc, 22f * sc, Color.WHITE, maxW = w - tx - 24f * sc)
                text(c, app.component.packageName, tx, tmp.centerY() + 20f * sc, 18f * sc, if (sel) Color.WHITE else 0xFF999999.toInt(), maxW = w - tx - 24f * sc, face = light)
            }
            val vis = recentVisible()
            if (recents.size > vis) {
                val trackH = cBot - listTop - 8f * sc
                val thumbH = max(24f * sc, trackH * vis / recents.size)
                val prog = (recentTopAnim / (recents.size - vis).toFloat()).coerceIn(0f, 1f)
                val ty = listTop + 4f * sc + (trackH - thumbH) * prog
                rf.set(w - 6f * sc, ty, w - 3f * sc, ty + thumbH)
                p.color = al(Color.WHITE, 150)
                c.drawRoundRect(rf, 1.5f * sc, 1.5f * sc, p)
            }
        }
        drawTabBar(c, 1)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) * (1 - t) + Color.red(b) * t).toInt()
        val g = (Color.green(a) * (1 - t) + Color.green(b) * t).toInt()
        val bl = (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt()
        return Color.rgb(r, g, bl)
    }

    // ---- chrome -------------------------------------------------------------------------------------

    private fun pageTitle() = when (page) {
        -1 -> "Hub"
        0 -> try { com.belleui.kit.Profiles.names[com.belleui.kit.Profiles.current(act)] } catch (_: Throwable) { "Home" }
        1 -> if (searchQuery.isNotEmpty()) "Search" else "Menu"
        else -> "Recent"
    }

    /** 26px status bar: application title on the left, indicators and clock on the right. */
    private fun drawStatusBar(c: Canvas, w: Float) {
        rf.set(0f, 0f, w, statusH)
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Color.BLACK
        c.drawRect(rf, p)
        nine(c, "qtg_fr_statusbar", rf, 20, 10f)

        val cy = statusH / 2f
        text(c, pageTitle(), 8f * sc, baselineFor(cy, 18f * sc), 18f * sc, Color.WHITE)
        val time = if (ampmStr.isEmpty()) clockStr else "$clockStr $ampmStr"
        val csz = 18f * sc
        val tw = measure(time, csz)
        text(c, time, w - 8f * sc, baselineFor(cy, csz), csz, Color.WHITE, Paint.Align.RIGHT)

        val bw = 24f * sc
        val bh = 18f * sc
        val bLeft = w - 8f * sc - tw - 10f * sc - bw
        drawBattery(c, bLeft, cy - bh / 2f, bw, bh)
        if (wifi) drawWifi(c, bLeft - 18f * sc, statusH * 0.76f, 10f * sc)
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
     * 60px toolbar (heightToolBar), 30px white glyphs spread evenly: first button at the left edge, last at
     * the right edge. Back is always first (apps button on Home); the options menu is always last.
     */
    private fun drawToolbar(c: Canvas, w: Float, h: Float) {
        rf.set(0f, cBot, w, h)
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Color.BLACK
        c.drawRect(rf, p)
        nine(c, "qtg_fr_toolbar", rf, 40, 20f)

        val cy = cBot + softH / 2f
        val s = 30f * sc
        val inModal = popup != null || reorder
        if (page == 1 && !inModal) {
            // the Belle Menu toolbar: back, search, menu
            val mm = 56f * sc
            toolIcon(c, ICON_BACK, mm, cy, s)
            hintLetter(c, backLetter(), mm + 26f * sc, cy)
            gfx.glyph(c, "toolbar_search", w / 2f, cy, s, 255)
            hintLetter(c, settingsLetter(), w / 2f + 26f * sc, cy)
            toolIcon(c, ICON_MENU, w - mm, cy, s)
            hintLetter(c, optLetter(), w - mm - 26f * sc, cy)
            return
        }
        if (page == 0 && !inModal) {
            // the Belle home toolbar: the Menu on the left, options on the right
            val mm = 56f * sc
            toolIcon(c, ICON_GRID, mm, cy, s)
            hintLetter(c, backLetter(), mm + 26f * sc, cy)
            toolIcon(c, ICON_MENU, w - mm, cy, s)
            hintLetter(c, optLetter(), w - mm - 26f * sc, cy)
            return
        }
        val leftKind = if (page == 0 && !inModal) ICON_GRID else ICON_BACK
        val m = 32f * sc
        val step = (w - 2 * m) / 3f
        val x1 = m
        val x2 = m + step
        val x3 = m + 2 * step
        val x4 = m + 3 * step
        toolIcon(c, leftKind, x1, cy, s)
        hintLetter(c, backLetter(), x1 + 26f * sc, cy)
        toolIcon(c, ICON_OK, x2, cy, s)
        hintLetter(c, okLetter(), x2 + 26f * sc, cy)
        if (!inModal) {
            toolIcon(c, ICON_GEAR, x3, cy, s)
            hintLetter(c, settingsLetter(), x3 + 26f * sc, cy)
            toolIcon(c, ICON_MENU, x4, cy, s)
            hintLetter(c, optLetter(), x4 - 26f * sc, cy)
        }
    }

    private fun hintLetter(c: Canvas, letter: String, x: Float, cy: Float) {
        text(c, letter, x, cy + 5f * sc, 13f * sc, al(Color.WHITE, 110), Paint.Align.CENTER, face = medium)
    }

    private fun toolIcon(c: Canvas, kind: Int, cx: Float, cy: Float, s: Float) {
        glyph(c, kind, cx, cy, s, Color.WHITE)
    }

    private fun glyph(c: Canvas, kind: Int, cx: Float, cy: Float, s: Float, color: Int) {
        val stock = when (kind) {
            ICON_BACK -> "toolbar_back"
            ICON_MENU -> "toolbar_menu"
            ICON_GEAR -> "toolbar_settings"
            ICON_CLOSE -> "qtg_graf_popup_close_normal"
            else -> null
        }
        if (stock != null && gfx.glyph(c, stock, cx, cy, s, Color.alpha(color))) return
        p.color = color
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        when (kind) {
            ICON_BACK -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3.4f * sc
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
                p.strokeWidth = 3.6f * sc
                for (i in -1..1) c.drawLine(cx - s * 0.45f, cy + i * s * 0.32f, cx + s * 0.45f, cy + i * s * 0.32f, p)
            }
            ICON_GEAR -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.6f * sc
                c.drawCircle(cx, cy, s * 0.26f, p)
                p.strokeWidth = 3.4f * sc
                for (i in 0 until 8) {
                    val a = i * (PI / 4).toFloat()
                    c.drawLine(cx + cos(a) * s * 0.36f, cy + sin(a) * s * 0.36f, cx + cos(a) * s * 0.5f, cy + sin(a) * s * 0.5f, p)
                }
            }
            ICON_CLOCK -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.2f * sc
                c.drawCircle(cx, cy, s * 0.46f, p)
                c.drawLine(cx, cy, cx, cy - s * 0.3f, p)
                c.drawLine(cx, cy, cx + s * 0.22f, cy + s * 0.08f, p)
            }
            ICON_CLOSE -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3f * sc
                val d = s * 0.32f
                c.drawLine(cx - d, cy - d, cx + d, cy + d, p)
                c.drawLine(cx - d, cy + d, cx + d, cy - d, p)
            }
            else -> { // ICON_OK: ring with a dot
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.8f * sc
                c.drawCircle(cx, cy, s * 0.46f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy, s * 0.16f, p)
            }
        }
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.BUTT
        p.strokeJoin = Paint.Join.MITER
    }

    // ---- overlays: options Menu, SelectionDialog, InfoBanner. Nothing dims the screen behind them. -----

    private fun pushButton(c: Canvas, r: RectF, label: String, selected: Boolean) {
        if (selected) {
            nine(c, "qtg_fr_pushbutton_pressed", r, 40, 20f)
        } else {
            // colours sampled from the real Belle Cancel / Yes / No buttons
            val rad = 6f * sc
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, r.top, 0f, r.bottom, 0xFF585953.toInt(), 0xFF393A34.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, rad, rad, p)
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1f * sc
            p.color = al(Color.BLACK, 170)
            c.drawRoundRect(r, rad, rad, p)
            p.style = Paint.Style.FILL
        }
        text(c, label, r.centerX(), baselineFor(r.centerY(), 22f * sc), 22f * sc, Color.WHITE, Paint.Align.CENTER)
    }

    private fun drawPopup(c: Canvas, pu: Popup, w: Float) {
        val box = popupRect(pu, RectF())
        val titleH = popupTitleH(pu)
        val itemH = popupItemH(pu)
        val slide = if (pu.menu) (1f - popupT) * box.height() else (1f - popupT) * 28f * sc
        c.save()
        c.translate(0f, slide)

        if (pu.menu) {
            // options Menu: slides up from the bottom edge over the toolbar
            rf.set(box.left, box.top, box.right, box.bottom + 20f * sc)
            p.style = Paint.Style.FILL
            p.shader = null
            p.color = 0xFF1B1B1B.toInt() // measured: the real panel is #1B1B1B with a faint top sheen
            c.drawRoundRect(rf, 12f * sc, 12f * sc, p)
            p.shader = LinearGradient(0f, rf.top, 0f, rf.top + 46f * sc, al(Color.WHITE, 26), al(Color.WHITE, 0), Shader.TileMode.CLAMP)
            c.drawRoundRect(rf, 12f * sc, 12f * sc, p)
            p.shader = null
        } else {
            // SelectionDialog: dark panel with the theme's heading frame
            nine(c, "qtg_fr_popup", box, 40, 20f)
            rf.set(box.left, box.top, box.right, box.top + titleH)
            nine(c, "qtg_fr_popup_heading", rf, 40, 20f)
            text(c, pu.title, box.left + 12f * sc, baselineFor(box.top + titleH / 2f, 22f * sc), 22f * sc, Color.WHITE, maxW = box.width() - 80f * sc)
            glyph(c, ICON_CLOSE, box.right - 28f * sc, box.top + titleH / 2f, 30f * sc, Color.WHITE)
        }

        val top0 = box.top + titleH + (if (pu.menu) 4f * sc else 0f)
        val vis = popupVis(pu)
        val focus = min(pu.sel, pu.items.size - 1)
        if (focus < pu.top) pu.top = focus else if (focus >= pu.top + vis) pu.top = focus - vis + 1
        pu.top = pu.top.coerceIn(0, max(0, pu.items.size - vis))
        for (i in pu.top until min(pu.items.size, pu.top + vis)) {
            val top = top0 + itemH * (i - pu.top)
            val sel = i == pu.sel
            if (sel) {
                tmp.set(box.left + 4f * sc, top + 1f * sc, box.right - 4f * sc, top + itemH - 1f * sc)
                nine(c, "qtg_fr_list_pressed", tmp, 40, 14f)
            } else if (i > pu.top) {
                p.style = Paint.Style.FILL
                p.shader = null
                p.color = al(Color.WHITE, 30)
                c.drawRect(box.left + 12f * sc, top, box.right - 12f * sc, top + 1f, p)
            }
            text(c, pu.items[i].label, box.left + 16f * sc, baselineFor(top + itemH / 2f, 22f * sc), 22f * sc, Color.WHITE, maxW = box.width() - 40f * sc)
        }
        if (pu.items.size > vis) {
            // a thin scroll bar on the right, since the list is longer than what fits
            val trackT = top0 + 2f * sc
            val trackH = itemH * vis - 4f * sc
            val thumbH = trackH * vis / pu.items.size
            val thumbT = trackT + (trackH - thumbH) * (pu.top.toFloat() / (pu.items.size - vis))
            p.style = Paint.Style.FILL
            p.shader = null
            p.color = al(Color.WHITE, 120)
            rf.set(box.right - 6f * sc, thumbT, box.right - 3f * sc, thumbT + thumbH)
            c.drawRoundRect(rf, 1.5f * sc, 1.5f * sc, p)
        }
        if (pu.menu) {
            val cy0 = top0 + itemH * vis + 6f * sc
            tmp.set(box.left + 8f * sc, cy0, box.right - 8f * sc, cy0 + 50f * sc)
            pushButton(c, tmp, "Cancel", pu.sel == pu.items.size)
        }
        c.restore()
    }

    /** InfoBanner: 60px, full width under the status bar, slides in and dismisses itself. */
    private fun drawToast(c: Canvas, w: Float) {
        val now = System.currentTimeMillis()
        val tin = ((now - toastStart) / 220f).coerceIn(0f, 1f)
        val tout = ((toastUntil - now) / 220f).coerceIn(0f, 1f)
        val t = min(tin, tout)
        val hgt = 60f * sc
        val top = statusH - (1f - t) * hgt
        c.save()
        c.clipRect(0f, statusH, w, statusH + hgt)
        rf.set(0f, top, w, top + hgt)
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Color.BLACK
        c.drawRect(rf, p)
        nine(c, "qtg_fr_popup_infobanner", rf, 40, 20f)
        text(c, toast, 16f * sc, baselineFor(top + hgt / 2f, 20f * sc), 20f * sc, Color.WHITE, maxW = w - 32f * sc)
        c.restore()
    }

    private companion object {
        const val SEARCH_H = 44f
        const val TAB = 50f      // tab bar (landscape; 60 in portrait)
        const val HEADER = 50f   // list starts under the tab bar
        const val ROW = 70f      // heightListItem
        const val POPUP_ROW = 64f // heightMenuItem
        const val WIDGET_CLOCK = 0
        const val WIDGET_DATE = 1
        const val WIDGET_BATTERY = 2
        const val WIDGET_STORAGE = 3
        const val WIDGET_NOTES = 4
        const val ICON_BACK = 0
        const val ICON_GRID = 1
        const val ICON_MENU = 2
        const val ICON_OK = 3
        const val ICON_GEAR = 4
        const val ICON_CLOCK = 5
        const val ICON_CLOSE = 6
    }
}
