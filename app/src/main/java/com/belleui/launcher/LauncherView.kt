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
 *  -1  Hub     – the notification history
 *   0  Home    – the Belle home screen: widgets and shortcuts on a 4x3 grid you arrange yourself
 *   1  Menu    – the E6 application grid, four icons across
 *   2  Recent  – the open / recently used apps
 */
class LauncherView(private val act: Activity) : View(act) {
    /** The E6 application grid is four icons across, three rows to a screen (ApplicationGrid.xml of the E6 Belle theme). */
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
    private var pageTarget = 0f
    private var pageFrom = 0f
    private var pageStartMs = 0L
    private var tab = 1
    private var tabFocus = false
    private var homeSel = 0
    private var homePage = 0 // unused since the home views below; kept so old saved state still loads
    private var homeView = 0 // which of the home views (up to six, the firmware's root configuration) is showing
    private var viewShot: Bitmap? = null // the view that is sliding out
    private var viewDir = 0
    private var viewStartMs = 0L
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
    private var statusH = 35f
    private var softH = 68f
    private var cTop = 30f
    private var cBot = 436f

    // ---- paints ----------------------------------------------------------------------
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rf = RectF()
    private val tmp = RectF()
    private val light = com.belleui.kit.NokiaFonts.regular(context)
    private val medium = com.belleui.kit.NokiaFonts.semibold(context)
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
    private val contacts = ContactsWidget(act) { postInvalidate() }
    private var notesList: List<Pair<String, String>> = emptyList()
    private var noteIdx = 0
    private var notesLoadedAt = 0L
    private var dateShort = ""
    private var dateShortFor = ""
    private var storageFree = 0L
    private var storageTotal = 1L
    /** The kit's drawing helpers: popups, buttons and the highlight look exactly as in the Belle apps. */
    private val kit = com.belleui.kit.Ui(act)
    private val gfx = kit.gfx

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
            // only on the page itself: holding a toolbar button must not pick anything up
            if (popup != null || e.y < cTop || e.y > cBot) return
            if (!onTap(e.x, e.y, selectOnly = true)) return
            if (page == 0 && !homeEdit && homeItems.isNotEmpty()) {
                // hold on an icon or widget: pick it up, then drag
                homeEdit = true
                carrying = true
                dragging = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            } else perform(Act.OPTIONS)
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (popup == null && !reorder && !homeEdit && abs(vx) > abs(vy) && abs(vx) > 600f) {
                if (page == 0) {
                    // between home views first, then on to the Menu (right) or the Hub (left)
                    if (vx < 0) { if (homeView < repo.homeViewCount - 1) switchHomeView(1) else setPage(1) }
                    else { if (homeView > 0) switchHomeView(-1) else setPage(-1) }
                } else setPage(page + if (vx < 0) 1 else -1)
                ensureVisible()
                invalidate()
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
        for (r in arrayOf(statusReceiver, packageReceiver)) {
            try {
                act.unregisterReceiver(r)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    fun onResumed() {
        // pick up the theme colour, sounds and button hints chosen in Styles
        com.belleui.kit.ThemeStore.refresh(act) {
            com.belleui.kit.ThemeStore.shared?.let { theme = Themes.fromAccent(it) }
            invalidate()
        }
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
        menuApps = repo.menuApps(repo.showHidden, flat = searchQuery.isNotEmpty()).let { l -> if (searchQuery.isEmpty()) l else l.filter { it.label.contains(searchQuery, true) } }
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
        if (e.action == KeyEvent.ACTION_DOWN && !keyNav) { keyNav = true; invalidate() }
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
        if (a == Act.OK && page == 0 && popup == null && !homeEdit && homeItems.isNotEmpty()) {
            // press A = open; hold A = pick the item up to move it
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (e.repeatCount == 0) okLong = false
                else if (!okLong) {
                    okLong = true
                    homeEdit = true
                    carrying = true
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    showToast("D-pad moves, ${okLetter()} drops")
                    invalidate()
                }
            } else if (e.action == KeyEvent.ACTION_UP) {
                if (!okLong) perform(Act.OK)
                okLong = false
            }
            return true
        }
        if (e.action == KeyEvent.ACTION_DOWN) {
            val directional = a == Act.UP || a == Act.DOWN || a == Act.LEFT || a == Act.RIGHT
            if (e.repeatCount == 0 || directional) perform(a)
        }
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) keyNav = false
        if (page == 0 && homeEdit && carrying && dragging) {
            val cur = homeItems.getOrNull(homeIdx)
            if (cur != null && (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_UP)) {
                val cw = width / HC.toFloat()
                val ch = (cBot - cTop - 12f * sc) / HR
                val nc = (event.x / cw).toInt().coerceIn(0, HC - cur.w)
                val nr = ((event.y - cTop - 6f * sc) / ch).toInt().coerceIn(0, HR - cur.h)
                if ((nc != cur.col || nr != cur.row) && !occupied(nc, nr, cur.w, cur.h, cur)) {
                    cur.col = nc
                    cur.row = nr
                }
                if (event.action == MotionEvent.ACTION_UP) {
                    dragging = false
                    carrying = false
                    saveHome()
                    showToast("Dropped - press ${backLetter()} when done")
                }
                invalidate()
                return true
            }
        }
        return gestures.onTouchEvent(event) || super.onTouchEvent(event)
    }

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
            Act.SETTINGS -> if (page == 1) openSearch() else if (page == 2) {
                recents.getOrNull(recentSel)?.let {
                    closeApp(listOf(it))
                    refreshLists()
                    showToast("${it.label} closed")
                }
            } else openQuick(0)
            Act.OPTIONS -> openOptions()
            Act.BACK -> if (page == 1 && repo.openFolder != null && searchQuery.isEmpty()) closeFolder() else setPage(if (page == 0) 1 else 0)
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
                    if (a == Act.LEFT) setPage(0) else if (a == Act.RIGHT) setPage(2) 
                    return
                }
                when (a) {
                    Act.LEFT -> if (menuSel % COLS == 0) setPage(0) else menuSel--
                    Act.RIGHT -> if (menuSel % COLS == COLS - 1 || menuSel == n - 1) setPage(2) else menuSel++
                    Act.UP -> if (menuSel >= COLS) menuSel -= COLS
                    Act.DOWN -> if (menuSel + COLS < n) menuSel += COLS else if (menuSel / COLS < (n - 1) / COLS) menuSel = n - 1
                    Act.OK -> menuApps[menuSel].let { if (repo.isFolder(it)) openFolder(it.label) else launch(it) }
                    else -> {}
                }
            }
            2 -> when (a) {
                Act.UP -> if (recentSel > 0) recentSel--
                Act.DOWN -> if (recentSel < recents.size - 1) recentSel++
                Act.LEFT -> setPage(1)
                Act.OK -> recents.getOrNull(recentSel)?.let { launch(it) }
                Act.RIGHT -> {}
                else -> {}
            }
        }
        ensureVisible()
    }

    private fun moveBy(d: Int) {
        if (menuApps.isEmpty()) return
        val to = (menuSel + d).coerceIn(0, menuApps.size - 1)
        if (to == menuSel) return
        // folders always come first and are not moved
        val folderCount = menuApps.count { repo.isFolder(it) }
        if (repo.isFolder(menuApps[menuSel]) || to < folderCount) return
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
                val ih = popupItemH(pu)
                val vis = popupVis(pu)
                val listTop = tmp.top + popupTitleH(pu) + (if (pu.menu) 4f * sc else 0f)
                // floor, not toInt(): a tap on the title must not count as the first row
                val row = Math.floor(((y - listTop) / ih).toDouble()).toInt()
                if (row in 0 until vis && pu.top + row < pu.items.size) {
                    pu.sel = pu.top + row
                    perform(Act.OK)
                } else if (y >= listTop + ih * vis) {
                    // the Cancel / Back button under the list
                    popup = null
                    com.belleui.kit.Sounds.play(com.belleui.kit.Sounds.BACK)
                }
            }
            invalidate()
            return true
        }
        if (abs(pageAnim - pagePos()) > 0.05f) return false
        if (y > cBot) { // soft-key bar
            if (!selectOnly) {
                if (page == 0 && popup == null && !reorder) {
                    val f = x / width
                    when {
                        f < 0.25f -> perform(Act.BACK)
                        f > 0.75f -> perform(Act.OPTIONS)
                    }
                } else if (page == 1 || page == 0 || page == 2) perform(if (x < width / 3f) Act.BACK else if (x < width * 2f / 3f) (if (page == 0) Act.OK else Act.SETTINGS) else Act.OPTIONS)
                else perform(if (x < width / 4f) Act.BACK else if (x < width / 2f) Act.OK else if (x < width * 3f / 4f) Act.SETTINGS else Act.OPTIONS)
            }
            return true
        }
        if (y < cTop) return false
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
                val cw = menuCellW()
                val ch = menuCellH()
                val idx = (((y - menuGridTop()) / ch + menuTop).toInt()) * COLS + ((x - menuGridLeft()) / cw).toInt().coerceIn(0, COLS - 1)
                if (y > menuGridTop() && idx in menuApps.indices) {
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
                    if (!selectOnly) {
                        // the cross at the right closes the app, anywhere else opens it
                        if (x > width - 64f * sc) {
                            closeApp(listOf(recents[idx]))
                            refreshLists()
                        } else perform(Act.OK)
                    }
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
        app.extras?.let { i.putExtras(it).setAction("emu.launch").removeCategory(Intent.CATEGORY_LAUNCHER) }
        try {
            act.startActivity(i)
            repo.pushRecent(app.key)
            recents = repo.recentEntries().filter { repo.showHidden || !repo.isHidden(it.key) }
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
        if (page == 1 && repo.isFolder(app)) {
            openFolderOptions(app)
            return
        }
        val items = ArrayList<PopupItem>()
        items += PopupItem("Open") { launch(app) }
        if (page == 2) {
            items += PopupItem("Close app") { closeApp(listOf(app)) }
            if (app.extras == null) items += PopupItem("Force stop") { forceStop(app) }
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
        if (page == 1 && searchQuery.isEmpty()) {
            items += PopupItem("Move to folder") { moveToFolderPicker(app) }
            if (repo.openFolder != null) items += PopupItem("Remove from folder") {
                repo.setFolder(app.key, null)
                refreshLists()
                showToast("${app.label} is back in the Menu")
            }
            if (repo.openFolder == null) items += PopupItem("New folder") { newFolder() }
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
        // a Symbian app lives inside the emulator: its package is the emulator's, which these would act on
        if (app.extras == null) {
            items += PopupItem("App info") {
                tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.component.packageName}")))
            }
            items += PopupItem("Uninstall") {
                tryStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.component.packageName}")))
            }
        }
        popup = Popup(app.label, items, 0, menu = true)
    }

    // ---- Menu folders ------------------------------------------------------------------------------

    private var folderWipeStart = 0L

    private fun openFolder(name: String) {
        repo.openFolder = name
        menuTop = 0
        menuSel = 0
        folderWipeStart = System.currentTimeMillis()
        refreshLists()
        invalidate()
    }

    private fun closeFolder() {
        val was = repo.openFolder
        repo.openFolder = null
        menuTop = 0
        folderWipeStart = System.currentTimeMillis()
        refreshLists()
        // land on the folder just left
        menuSel = menuApps.indexOfFirst { repo.isFolder(it) && it.label == was }.coerceAtLeast(0)
        ensureVisible()
        invalidate()
    }

    private fun askName(title: String, initial: String, ok: (String) -> Unit) {
        val et = android.widget.EditText(act)
        et.setText(initial)
        et.setSingleLine(true)
        et.setSelectAllOnFocus(true)
        android.app.AlertDialog.Builder(act).setTitle(title).setView(et)
            .setPositiveButton("OK") { _, _ -> ok(et.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun nextFolderName(): String {
        val have = repo.folders().map { it.lowercase() }
        var n = 1
        while ("folder $n" in have) n++
        return "Folder $n"
    }

    private fun newFolder(then: ((String) -> Unit)? = null) {
        askName("New folder", nextFolderName()) { n ->
            if (repo.addFolder(n)) {
                val name = repo.folders().first { it.equals(n.trim().replace("|", "").take(24), true) }
                refreshLists()
                showToast("Folder created")
                then?.invoke(name)
            } else showToast("That name can't be used")
        }
    }

    private fun moveToFolderPicker(app: AppEntry) {
        val items = ArrayList<PopupItem>()
        for (f in repo.folders().sortedBy { it.lowercase() }) {
            items += PopupItem(f) {
                repo.setFolder(app.key, f)
                refreshLists()
                showToast("${app.label} moved to $f")
            }
        }
        items += PopupItem("New folder...") {
            newFolder { f ->
                repo.setFolder(app.key, f)
                refreshLists()
                showToast("${app.label} moved to $f")
            }
        }
        popup = Popup("Move to folder", items, 0)
    }

    private fun openFolderOptions(entry: AppEntry) {
        val name = entry.label
        val items = ArrayList<PopupItem>()
        items += PopupItem("Open") { openFolder(name) }
        items += PopupItem("Rename") {
            askName("Rename folder", name) { n ->
                if (repo.renameFolder(name, n)) refreshLists() else showToast("That name can't be used")
            }
        }
        items += PopupItem("Delete folder") {
            repo.removeFolder(name)
            refreshLists()
            showToast("Folder deleted - its apps are back in the Menu")
        }
        items += PopupItem("New folder") { newFolder() }
        popup = Popup(name, items, 0, menu = true)
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
        kit.sc = sc
        com.belleui.kit.Belle.sc = sc
        // the strip of the status bar: the launcher's own, or the room kept free under the real one
        statusH = com.belleui.kit.Belle.statusH() * sc
        softH = com.belleui.kit.Belle.TOOL_H * sc
        cTop = statusH
        cBot = h - softH
        ensureVisible()
    }

    private fun menuAreaTop() = cTop + (if (searching()) SEARCH_H * sc else 0f)

    private fun menuAreaBottom() = cBot

    // the E6 menu grid, measured at 640x480: 151 px across, 122 px down, 72 px icons, the first row 16 px under the title bar
    private fun menuCellW() = 151f * sc
    // grid_app_pane (640x480, 604x363 = 4x3 cells): l=11, t=7 (the 25 px on the right are the scroll bar's)
    private fun menuGridLeft() = max(0f, (width - COLS * menuCellW()) / 2f) // centred: equal air left and right
    private fun menuGridTop() = menuAreaTop()
    private fun menuCellH() = 121f * sc // cell_app_pane variety 2: 151x121

    private fun menuCellRect(idx: Int, top: Float, out: RectF): RectF {
        val cw = menuCellW()
        val ch = menuCellH()
        val x = menuGridLeft() + (idx % COLS) * cw
        val y = menuGridTop() + ((idx / COLS) - top) * ch
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

    /** Under the rows: 6 px of air, the full-width Cancel (menu) or Back (popup) button, 8 px to the edge. */
    private fun popupFootH() = (6f + POPUP_BTN + 8f) * sc

    /** Room for the rows. The options menu may cover the page up to the status bar; a popup stays between the bars. */
    private fun popupAvail(pu: Popup) = if (pu.menu) height - cTop - 90f * sc else (cBot - cTop) - popupTitleH(pu) - popupFootH()

    /** E6 rows are 80 px. A long options menu is squeezed the way the apps' menu is (to 48 px at most), then scrolls. */
    private fun popupItemH(pu: Popup): Float {
        val full = POPUP_ROW * sc
        if (!pu.menu) return full
        return (popupAvail(pu) / max(1, pu.items.size)).coerceIn(48f * sc, full)
    }

    /** How many rows fit: a long list scrolls instead of running off the screen. */
    private fun popupVis(pu: Popup): Int = max(1, min(pu.items.size, (popupAvail(pu) / popupItemH(pu) + 0.01f).toInt()))

    private fun popupRect(pu: Popup, out: RectF): RectF {
        val ih = popupItemH(pu)
        val vis = popupVis(pu)
        if (pu.menu) {
            val bh = ih * vis + 4f * sc + popupFootH()
            out.set(0f, height - bh, width.toFloat(), height.toFloat())
        } else {
            val bw = min(504f * sc, width - 16f * sc) // sizeDialogMax
            val bh = popupTitleH(pu) + ih * vis + popupFootH()
            val x = (width - bw) / 2f
            val y = cTop + max(0f, (cBot - cTop - bh) / 2f)
            out.set(x, y, x + bw, y + bh)
        }
        return out
    }

    // =====================================================================================
    // drawing  (Symbian Belle / Anna: soft, glossy, dark, vivid squircle icons, icon toolbar)
    // =====================================================================================

    private val sans = com.belleui.kit.NokiaFonts.regular(context)

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

    /** Belle highlight: the theme's blue pressed frame (qtg_fr_list_pressed). */
    private var keyNav = false // the focus box shows once a key has been pressed, as the touch reference shows none until you navigate

    private fun drawFocus(c: Canvas, r: RectF, radius: Float) {
        if (!keyNav) return
        if (nine(c, "qtg_fr_list_pressed", r, 40, radius / sc)) return
        p.style = Paint.Style.FILL
        p.color = theme.accent
        c.drawRoundRect(r, radius, radius, p)
    }

    private var drawFaults = 0
    private var titleSent = ""

    override fun onDraw(c: Canvas) {
        val mark = c.save()
        try {
            drawAll(c)
            drawFaults = 0
        } catch (t: Throwable) {
            // one bad widget or page must never crash the home screen: log it and keep the wallpaper and chrome
            c.restoreToCount(mark)
            android.util.Log.e("BelleLauncher", "draw failed", t)
            drawFaults++
            c.drawColor(Color.BLACK)
            text(c, "Home screen problem", width / 2f, height / 2f, 24f * sc, Color.WHITE, Paint.Align.CENTER)
            if (drawFaults < 30) postInvalidateDelayed(500)
        }
        c.restoreToCount(mark)
    }

    private fun drawAll(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = System.nanoTime()
        var dt = (now - lastNs) / 1e9f
        lastNs = now
        if (dt > 0.05f || dt <= 0f) dt = 0.016f
        val k = 1f - exp(-dt * 18f)
        frameK = k
        var busy = false

        // switching home views: a 0.225 s out-quad slide (activeidle_viewswitch_*.fxml on the E6)
        val pageGoal = pagePos().toFloat()
        val nowMs = System.currentTimeMillis()
        if (pageGoal != pageTarget) {
            pageTarget = pageGoal
            pageFrom = pageAnim
            pageStartMs = nowMs
        }
        val pt = ((nowMs - pageStartMs) / 225f).coerceIn(0f, 1f)
        pageAnim = pageFrom + (pageGoal - pageFrom) * (1f - (1f - pt) * (1f - pt))
        if (pt < 1f) busy = true else pageAnim = pageGoal
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
                0 -> drawHomeView(c)
                else -> if (tab == 2) drawRecent(c) else drawMenu(c)
            }
            c.restore()
        }

        if (com.belleui.kit.Belle.sysBar) {
            val t = pageTitle()
            if (t != titleSent) {
                titleSent = t
                com.belleui.kit.Belle.publishTitle(act, t)
            }
        } else drawStatusBar(c, w)
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

    /** The Belle battery meter, filled to the charge level. */
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
    private var okLong = false
    private var dragging = false
    private var homeLoaded = false
    private var curWidget = -1
    private val HC = 4
    private val HR = 3
    private var appMap: Map<String, AppEntry> = emptyMap()

    private fun appFor(it: HomeItem): AppEntry? = if (it.widget) null else (appMap[it.id] ?: repo.apps.firstOrNull { a -> a.key == it.id }?.also { a -> appMap = appMap + (a.key to a) })

    private fun widgetSizeOf(t: Int) = when (t) { 3 -> 4 to 3; 2 -> 2 to 3; 1, 5, 6 -> 4 to 2; 8 -> 2 to 2; else -> 4 to 1 }

    /** Widgets with up to three buttons the focus steps through: music, quick switches, notes, favourite contacts. */
    private fun hasSubs(t: Int) = t == 2 || t == 4 || t == 5 || t == 6

    /** The heights (in rows) each widget looks right at. */
    private fun widgetHeights(t: Int) = when (t) { 0 -> intArrayOf(1, 2); 1 -> intArrayOf(1, 2); 2 -> intArrayOf(3, 2); 3 -> intArrayOf(2, 3); 5 -> intArrayOf(2, 3); 6 -> intArrayOf(2, 3); 8 -> intArrayOf(2, 3); else -> intArrayOf(1) }

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
        repo.setHomeLayoutOf(homeView, homeItems.joinToString(";") { "${if (it.widget) "w" else "a"},${it.id},${it.col},${it.row},${it.w},${it.h}" })
    }

    /** The ROM's default content of home views 2..6 (the views in rootconfiguration.xml), with Android's equivalents. */
    private fun defaultView(v: Int) {
        when (v) {
            1 -> homeItems.add(HomeItem(true, "6", 0, 1, 4, 2))              // contactfavorites
            2 -> {
                homeItems.add(HomeItem(true, "3", 0, 0, 4, 2))                // emailwidget
                homeItems.add(HomeItem(true, "7", 0, 2, 4, 1))                // profiles
            }
            3 -> homeItems.add(HomeItem(true, "1", 0, 0, 4, 2))              // organizer
            4 -> {
                homeItems.add(HomeItem(true, "2", 0, 0, 2, 3))                // musicwidget
                var i = 0
                for (app in repo.favoriteEntries().take(2)) { homeItems.add(HomeItem(false, app.key, 2 + i, 2)); i++ }
            }
            else -> {}                                                          // view 6 is empty
        }
    }

    private fun loadHome() {
        homeItems.clear()
        val raw = repo.homeLayoutOf(homeView)
        if (raw == null) {
            if (homeView == 0) {
                // first run: a flip clock on top and the old four shortcuts underneath
                homeItems.add(HomeItem(true, "0", 0, 0, 4, 1))
                var i = 0
                for (app in repo.favoriteEntries()) {
                    homeItems.add(HomeItem(false, app.key, i % HC, 2))
                    i++
                    if (i >= HC) break
                }
            } else defaultView(homeView)
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
        if (hasWidget(6)) contacts.refresh()
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
        widgetSub = if (hasSubs(type)) 1 else 0
        saveHome()
        homeStart()
        if (type == 1 && !calendar.hasPermission()) act.requestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR), 31)
        if (type == 6 && !contacts.hasPermission()) act.requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 32)
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
        val hs = widgetHeights(it.type)
        for (step in 1..hs.size) {
            val nh = hs[(hs.indexOf(it.h).let { k -> if (k < 0) 0 else k } + step) % hs.size]
            if (nh != it.h && it.row + nh <= HR && !occupied(it.col, it.row, it.w, nh, it)) {
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
        val sub = if (it.widget && hasSubs(it.type) && widgetSub in 0..2 && !it.subs[widgetSub].isEmpty && !homeEdit) it.subs[widgetSub] else null
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

    /** Nothing further that way on this view: the next home view, then the Hub to the left or the Menu to the right. */
    private fun homeEdge(a: Act) {
        if (homeEdit) return
        if (a == Act.LEFT) { if (homeView > 0) switchHomeView(-1) else setPage(-1) }
        else if (a == Act.RIGHT) { if (homeView < repo.homeViewCount - 1) switchHomeView(1) else setPage(1) }
    }

    /** Shows home view [homeView] + [d]: the old one slides out and the new one in (activeidle_viewswitch_*.fxml: 0.225 s out-quad). */
    private fun switchHomeView(d: Int) {
        val n = homeView + d
        if (n < 0 || n >= repo.homeViewCount) return
        viewShot = try {
            val bw = width
            val bh = (cBot - cTop).toInt()
            if (bw > 0 && bh > 0) {
                val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                val cv = Canvas(bmp)
                cv.translate(0f, -cTop)
                drawHome(cv)
                bmp
            } else null
        } catch (_: Throwable) {
            null
        }
        saveHome()
        homeView = n
        homeIdx = 0
        carrying = false
        widgetSub = 0
        viewDir = d
        viewStartMs = System.currentTimeMillis()
        loadHome()
        invalidate()
    }

    private fun drawHomeView(c: Canvas) {
        val shot = viewShot
        val t = ((System.currentTimeMillis() - viewStartMs) / 225f).coerceIn(0f, 1f)
        if (shot == null || t >= 1f) {
            viewShot = null
            drawHome(c)
            return
        }
        val e = 1f - (1f - t) * (1f - t)
        val w = width.toFloat()
        c.save()
        c.translate(viewDir * w * (1f - e), 0f)
        drawHome(c)
        c.restore()
        c.drawBitmap(shot, -viewDir * w * e, cTop, null)
        postInvalidateOnAnimation()
    }

    private fun addHomeView() {
        val n = repo.homeViewCount
        if (n >= 6) return
        saveHome()
        repo.setHomeLayoutOf(n, "")      // an added view starts empty
        repo.homeViewCount = n + 1
        homeView = n
        homeIdx = 0
        loadHome()
        showToast("Home screen ${n + 1} added")
        invalidate()
    }

    private fun removeHomeView() {
        val n = repo.homeViewCount
        if (n <= 1) return
        for (i in homeView until n - 1) repo.setHomeLayoutOf(i, repo.homeLayoutOf(i + 1) ?: "")
        repo.setHomeLayoutOf(n - 1, "")
        repo.homeViewCount = n - 1
        homeView = homeView.coerceIn(0, n - 2)
        homeIdx = 0
        loadHome()
        showToast("Home screen removed")
        invalidate()
    }

    private fun homeMove(a: Act) {
        val cur = homeItems.getOrNull(homeIdx)
        if (cur == null) {
            homeEdge(a)
            return
        }
        if (cur.widget && (a == Act.LEFT || a == Act.RIGHT) && hasSubs(cur.type) && !homeEdit) {
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
            widgetSub = if (n.widget && hasSubs(n.type)) (if (a == Act.LEFT) 2 else 0).let { if (a == Act.UP || a == Act.DOWN) 1 else it } else 0        } else homeEdge(a)
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
            if (widgetSub < 0) widgetSub = if (hasSubs(it.type)) 1 else 0
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
        val subFocus = !homeEdit && cur != null && cur.widget && hasSubs(cur.type) && widgetSub in 0..2 && !cur.subs[widgetSub].isEmpty
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
                    // icon and label plate form one stack, centred in the cell (the reference's 88 + 34 px, shrunk only if the cell is shorter)
                    val fit = min(1f, (r.height() - 6f * sc) / (126f * sc))
                    val isz = 88f * sc * fit
                    val plateH = 34f * sc * fit
                    val stackTop = r.centerY() - (isz + 4f * sc * fit + plateH) / 2f
                    val iconCy = stackTop + isz / 2f
                    drawIcon(c, app.icon, r.centerX(), iconCy, isz)
                    // the home screen label: a dark translucent plate, 107 x 34
                    val tsz = 22f * sc * fit
                    val tw = min(107f * sc, r.width() - 2f * sc)
                    rf.set(r.centerX() - tw / 2f, stackTop + isz + 4f * sc * fit, r.centerX() + tw / 2f, stackTop + isz + 4f * sc * fit + plateH)
                    p.style = Paint.Style.FILL
                    p.shader = null
                    p.color = 0x99000000.toInt()
                    c.drawRoundRect(rf, 6f * sc, 6f * sc, p)
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
            text(c, "Press ${optLetter()} for Options to add widgets and shortcuts", w / 2f, cTop + (cBot - cTop) / 2f, 18f * sc, al(Color.WHITE, 200), Paint.Align.CENTER, w - 40f * sc, face = light)
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
            val names = arrayOf("Flip clock", "Today", "Music player", "Notifications", "Quick switches", "Notes", "Favourite contacts", "Profile", "Analogue clock")
            val order = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8)
            popup = Popup("Add widget", order.map { t -> PopupItem(names[t]) { addWidget(t) } }, 0)
        }
        if (repo.homeViewCount < 6) items += PopupItem("Add home screen") { addHomeView() }
        if (repo.homeViewCount > 1) items += PopupItem("Remove this home screen") { removeHomeView() }
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
            6 -> {
                if (!contacts.hasPermission()) act.requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 32)
                else contacts.people.getOrNull(widgetSub.coerceAtLeast(0))?.let { pe ->
                    tryStart(Intent(Intent.ACTION_VIEW, contacts.uri(pe)))
                } ?: tryStart(Intent(Intent.ACTION_VIEW, android.provider.ContactsContract.Contacts.CONTENT_URI))
            }
            7 -> try {
                val n = (com.belleui.kit.Profiles.current(act) + 1) % com.belleui.kit.Profiles.names.size
                com.belleui.kit.Profiles.apply(act, n)
                showToast("Profile: ${com.belleui.kit.Profiles.names[n]}")
            } catch (_: Throwable) {
                tryStart(Intent(Settings.ACTION_SOUND_SETTINGS))
            }
            8 -> openOther("com.belleui.clock")
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
            6 -> drawContacts(c, r)
            7 -> drawProfile(c, r)
            8 -> drawAnalog(c, r)
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

    /** The Favourite contacts widget: up to three starred contacts, their photo (or initials) and name; each is a button. */
    private fun drawContacts(c: Canvas, r: RectF) {
        contacts.refresh()
        darkCard(c, r, 14f * sc)
        val list = contacts.people.take(3)
        if (!contacts.hasPermission() || list.isEmpty()) {
            val msg = if (!contacts.hasPermission()) "Press ${okLetter()} to show your favourite contacts" else "No favourite contacts"
            val sub = if (!contacts.hasPermission()) "Contacts access is needed" else "Star a contact in Contacts to see it here"
            text(c, msg, r.centerX(), r.centerY() - 6f * sc, 20f * sc, Color.WHITE, Paint.Align.CENTER, r.width() - 40f * sc, face = medium)
            text(c, sub, r.centerX(), r.centerY() + 22f * sc, 16f * sc, al(Color.WHITE, 170), Paint.Align.CENTER, r.width() - 40f * sc, face = light)
            subRects[1].set(r)
            return
        }
        val cw = r.width() / 3f
        val ps = min(r.height() * 0.5f, 84f * sc)
        for ((i, pe) in list.withIndex()) {
            val cell = RectF(r.left + i * cw, r.top, r.left + (i + 1) * cw, r.bottom)
            val cx = cell.centerX()
            val cy = r.top + r.height() * 0.42f
            val circle = RectF(cx - ps / 2f, cy - ps / 2f, cx + ps / 2f, cy + ps / 2f)
            val photo = pe.photo
            p.style = Paint.Style.FILL
            if (photo != null) {
                val clip = android.graphics.Path()
                clip.addCircle(cx, cy, ps / 2f, android.graphics.Path.Direction.CW)
                c.save()
                c.clipPath(clip)
                c.drawBitmap(photo, null, circle, iconPaint)
                c.restore()
            } else {
                p.shader = LinearGradient(0f, circle.top, 0f, circle.bottom, theme.accentLight, theme.accent, Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, ps / 2f, p)
                p.shader = null
                val initials = pe.name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.substring(0, 1).uppercase() }
                text(c, initials, cx, baselineFor(cy, ps * 0.42f, medium), ps * 0.42f, Color.WHITE, Paint.Align.CENTER, face = medium)
            }
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f * sc
            p.color = al(Color.WHITE, 90)
            c.drawCircle(cx, cy, ps / 2f, p)
            p.style = Paint.Style.FILL
            text(c, pe.name, cx, cell.bottom - 22f * sc, 18f * sc, Color.WHITE, Paint.Align.CENTER, cw - 12f * sc, face = light)
            subRects[i].set(cell)
        }
    }

    /** The Profile widget: the current profile and its icon; select cycles to the next. */
    private fun drawProfile(c: Canvas, r: RectF) {
        darkCard(c, r, 14f * sc)
        val pi = try { com.belleui.kit.Profiles.current(act) } catch (_: Throwable) { 0 }
        val icon = RectF(r.left + 14f * sc, r.centerY() - 28f * sc, r.left + 14f * sc + 56f * sc, r.centerY() + 28f * sc)
        gfx.draw(c, if (pi == 1) "qgn_indi_home_profile_silent" else if (pi == 2) "qgn_indi_home_profile_timed" else "qgn_indi_home_profile_general", icon)
        val tx = icon.right + 16f * sc
        text(c, "Profile", tx, r.centerY() - 8f * sc, 17f * sc, al(Color.WHITE, 170), face = light)
        text(c, try { com.belleui.kit.Profiles.names[pi] } catch (_: Throwable) { "General" }, tx, r.centerY() + 24f * sc, 26f * sc, Color.WHITE, maxW = r.right - tx - 14f * sc, face = medium)
    }

    /** The analogue clock widget, with the theme's face and pointers (the same graphics the Clock app uses). */
    private fun drawAnalog(c: Canvas, r: RectF) {
        val rad = min(r.width(), r.height()) / 2f - 4f * sc
        val cx = r.centerX()
        val cy = r.centerY()
        if (!gfx.drawPart(c, "qsn_cp_clock_analogue_face_1", android.graphics.Rect(0, 5, 306, 309), RectF(cx - rad, cy - rad, cx + rad, cy + rad))) {
            p.style = Paint.Style.FILL
            p.color = al(Color.BLACK, 190)
            c.drawCircle(cx, cy, rad, p)
        }
        val cal = Calendar.getInstance()
        val mins = cal.get(Calendar.MINUTE).toFloat()
        val hour = (cal.get(Calendar.HOUR) % 12) + mins / 60f
        val hand1 = gfx.get("qsn_cp_clock_analogue_pointer_hour")
        val hand2 = gfx.get("qsn_cp_clock_analogue_pointer_minute")
        val half = 96f * (2f * rad) / 306f
        val box = RectF(cx - half, cy - half, cx + half, cy + half)
        if (hand1 != null && hand2 != null) {
            c.save(); c.rotate(hour * 30f, cx, cy); c.drawBitmap(hand1, null, box, iconPaint); c.restore()
            c.save(); c.rotate(mins * 6f, cx, cy); c.drawBitmap(hand2, null, box, iconPaint); c.restore()
        } else {
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.color = Color.WHITE
            p.strokeWidth = 5f * sc
            val ah = hour * 30f * PI.toFloat() / 180f
            c.drawLine(cx, cy, cx + sin(ah) * rad * 0.5f, cy - cos(ah) * rad * 0.5f, p)
            p.strokeWidth = 3f * sc
            val am = mins * 6f * PI.toFloat() / 180f
            c.drawLine(cx, cy, cx + sin(am) * rad * 0.78f, cy - cos(am) * rad * 0.78f, p)
            p.style = Paint.Style.FILL
        }
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
            text(c, "Press ${okLetter()} to write one", tx, box.top + 78f * sc, 16f * sc, al(ink, 170), maxW = tw, face = light)
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
        // the Belle widget plate (widget_belle00); the drawn glass is only the fallback
        if (nine(c, "widget_plate", r, 20, 14f)) return
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

    /** Two flip cards: the Belle digital clock widget background and clock digits, with the AM/PM mark and the date. */
    private fun drawFlip(c: Canvas, r: RectF) {
        val hh = clockStr.substringBefore(':')
        val mm = clockStr.substringAfter(':', "00")
        if (dateShortFor != clockStr) {
            dateShortFor = clockStr
            dateShort = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date())
        }
        // the widget picture keeps its own proportions, centred
        // the reference: two tiles about 392 x 152 px, centred, the top 17 px below the widget's top edge (they may reach past it)
        val bgAspect = 624f / 254f
        val bw = min(400f * sc, width - 8f * sc)
        val box = RectF(r.centerX() - bw / 2f, r.top + 14f * sc, r.centerX() + bw / 2f, r.top + 14f * sc + bw / bgAspect)
        if (!gfx.draw(c, "qgn_graf_hs_digital_bg_8x3", box)) {
            darkCard(c, RectF(box.left, box.top, box.centerX() - 4f * sc, box.bottom), 14f * sc)
            darkCard(c, RectF(box.centerX() + 4f * sc, box.top, box.right, box.bottom), 14f * sc)
        }
        val cy = box.centerY() - 4f * sc
        val lx = box.left + box.width() * 0.25f
        val rx = box.left + box.width() * 0.75f
        val size = box.height() * 0.66f
        text(c, hh, lx, baselineFor(cy, size), size, Color.WHITE, Paint.Align.CENTER, face = light)
        text(c, mm, rx, baselineFor(cy, size), size, Color.WHITE, Paint.Align.CENTER, face = light)
        if (ampmStr.isNotEmpty()) text(c, ampmStr, box.right - 22f * sc, box.top + 30f * sc, 16f * sc, al(Color.WHITE, 220), Paint.Align.RIGHT, face = light)
        text(c, dateShort, box.right - 30f * sc, box.bottom - 14f * sc, 21f * sc, al(Color.WHITE, 190), Paint.Align.RIGHT, face = light)
    }

    /** A paper calendar: the Belle calendar widget paper, "Today" and what is coming in the next week. */
    /** The E6 paper widget: white card, rounded at the bottom, two binder rings on top (from the real home screen). */
    private fun paperWidget(c: Canvas, r: RectF, rings: Boolean) {
        val rad = 14f * sc
        // soft shadow
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = al(Color.BLACK, 70)
        rf.set(r.left - 1f * sc, r.top + 2f * sc, r.right + 1f * sc, r.bottom + 5f * sc)
        c.drawRoundRect(rf, rad, rad, p)
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, 0xFFFFFFFF.toInt(), 0xFFE4E4E4.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * sc
        p.color = al(Color.BLACK, 120)
        c.drawRoundRect(r, rad, rad, p)
        p.style = Paint.Style.FILL
        if (rings) {
            for (fx in floatArrayOf(0.1f, 0.9f)) {
                val cx = r.left + r.width() * fx
                p.color = 0xFF9A9A9A.toInt()
                rf.set(cx - 5f * sc, r.top - 6f * sc, cx + 5f * sc, r.top + 14f * sc)
                c.drawRoundRect(rf, 5f * sc, 5f * sc, p)
                p.color = 0xFFF4F4F4.toInt()
                rf.set(cx - 2.5f * sc, r.top - 3f * sc, cx + 2.5f * sc, r.top + 10f * sc)
                c.drawRoundRect(rf, 2.5f * sc, 2.5f * sc, p)
            }
        }
    }

    private fun drawToday(c: Canvas, r: RectF) {
        paperWidget(c, r, true)
        val ink = 0xFF111111.toInt()
        val headH = min(46f * sc, r.height() * 0.3f)
        text(c, "Today", r.centerX(), baselineFor(r.top + headH / 2f + 2f * sc, 26f * sc), 26f * sc, ink, Paint.Align.CENTER)
        p.color = al(Color.BLACK, 70)
        c.drawRect(r.left + 1f, r.top + headH, r.right - 1f, r.top + headH + 1f * sc, p)
        val ev = calendar.events
        val grey = 0xFF707070.toInt()
        when {
            !calendar.hasPermission() -> text(c, "Press ${okLetter()} to show your calendar", r.centerX(), baselineFor(r.top + headH + (r.height() - headH) / 2f, 19f * sc), 19f * sc, grey, Paint.Align.CENTER, maxW = r.width() - 30f * sc)
            ev.isEmpty() -> text(c, "No events for 7 days", r.centerX(), baselineFor(r.top + headH + (r.height() - headH) / 2f, 20f * sc), 20f * sc, ink, Paint.Align.CENTER)
            else -> {
                val rowH = 42f * sc
                val n = max(1, min(3, ((r.height() - headH - 6f * sc) / rowH).toInt()))
                var y = r.top + headH + 6f * sc
                val fmt = DateFormat.getTimeFormat(act)
                val day = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
                for (e in ev.take(n)) {
                    val whenText = if (e.allDay) "all day" else fmt.format(java.util.Date(e.begin))
                    val dayText = if (isToday(e.begin) && e.begin < System.currentTimeMillis() + 24 * 3600_000L) null else day.format(java.util.Date(e.begin))
                    text(c, whenText, r.left + 14f * sc, baselineFor(y + rowH / 2f - (if (dayText != null) 8f * sc else 0f), 22f * sc), 22f * sc, ink)
                    if (dayText != null) text(c, dayText, r.left + 14f * sc, baselineFor(y + rowH / 2f + 12f * sc, 16f * sc, light), 16f * sc, grey, face = light)
                    // the yellow event bar and the title, as in the E6 calendar widget
                    p.color = 0xFFF2C500.toInt()
                    c.drawRect(r.left + 84f * sc, y + 4f * sc, r.left + 89f * sc, y + rowH - 4f * sc, p)
                    text(c, e.title, r.left + 100f * sc, baselineFor(y + rowH / 2f, 21f * sc), 21f * sc, ink, maxW = r.right - r.left - 116f * sc)
                    y += rowH
                }
            }
        }
    }

    /** A round dark media button of the E6 music widget: kind 0 previous, 1 play/pause, 2 next. */
    private fun mediaDisc(c: Canvas, kind: Int, cx: Float, cy: Float, d: Float) {
        val rad = d / 2f
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, cy - rad, 0f, cy + rad, 0xFF4A4A4A.toInt(), 0xFF101010.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * sc
        p.color = 0xFF000000.toInt()
        c.drawCircle(cx, cy, rad, p)
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        val s = d * 0.2f
        when (kind) {
            1 -> if (media.playing) {
                c.drawRect(cx - s * 0.9f, cy - s, cx - s * 0.2f, cy + s, p)
                c.drawRect(cx + s * 0.2f, cy - s, cx + s * 0.9f, cy + s, p)
            } else {
                val path = Path()
                path.moveTo(cx - s * 0.6f, cy - s * 1.1f)
                path.lineTo(cx + s * 1.1f, cy)
                path.lineTo(cx - s * 0.6f, cy + s * 1.1f)
                path.close()
                c.drawPath(path, p)
            }
            else -> {
                val dir = if (kind == 0) -1f else 1f
                val path = Path()
                path.moveTo(cx - dir * s * 0.1f, cy - s)
                path.lineTo(cx + dir * s * 1.0f, cy)
                path.lineTo(cx - dir * s * 0.1f, cy + s)
                path.close()
                c.drawPath(path, p)
                c.drawRect(cx - dir * s * 0.9f - s * 0.12f, cy - s, cx - dir * s * 0.9f + s * 0.12f, cy + s, p)
            }
        }
    }

    /** The E6 music widget: the cover on top of a dark glass panel, the title on a dark strip, three round buttons. */
    private fun drawMusic(c: Canvas, r: RectF) {
        if (r.height() < 170f * sc) {
            // a short, wide widget: the cover on the left, title and the buttons on the right
            val glass = RectF(r)
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, glass.top, 0f, glass.bottom, 0xF0303030.toInt(), 0xF0101010.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(glass, 16f * sc, 16f * sc, p)
            p.shader = null
            val side0 = r.height() - 12f * sc
            val art0 = RectF(r.left + 6f * sc, r.top + 6f * sc, r.left + 6f * sc + side0, r.top + 6f * sc + side0)
            val bmp0 = media.art
            c.save()
            c.clipRect(art0)
            if (bmp0 != null) {
                val k = max(art0.width() / bmp0.width, art0.height() / bmp0.height)
                val sw = art0.width() / k
                val sh = art0.height() / k
                c.drawBitmap(bmp0, android.graphics.Rect(((bmp0.width - sw) / 2).toInt(), ((bmp0.height - sh) / 2).toInt(), ((bmp0.width + sw) / 2).toInt(), ((bmp0.height + sh) / 2).toInt()), art0, iconPaint)
            } else {
                p.color = 0xFF2A2A2A.toInt()
                c.drawRect(art0, p)
            }
            c.restore()
            val tx = art0.right + 14f * sc
            val t0 = if (media.available) (media.title ?: "Playing") else "Nothing is playing"
            text(c, t0, tx, baselineFor(r.top + r.height() * 0.28f, 21f * sc), 21f * sc, Color.WHITE, maxW = r.right - tx - 12f * sc)
            val cy0 = r.top + r.height() * 0.68f
            val span = (r.right - tx - 12f * sc)
            val xs0 = floatArrayOf(tx + span * 0.18f, tx + span * 0.5f, tx + span * 0.82f)
            val ds0 = floatArrayOf(40f * sc, 52f * sc, 40f * sc)
            for (i in 0..2) {
                mediaDisc(c, i, xs0[i], cy0, ds0[i])
                val hh = ds0[i] / 2f + 3f * sc
                subRects[i].set(xs0[i] - hh, cy0 - hh, xs0[i] + hh, cy0 + hh)
            }
            return
        }
        val side = min(r.width() - 28f * sc, r.height() - 92f * sc).coerceAtLeast(60f * sc)
        val art = RectF(r.centerX() - side / 2f, r.top + 4f * sc, r.centerX() + side / 2f, r.top + 4f * sc + side)
        // the glass panel starts about halfway down the cover and holds the buttons
        val panel = RectF(r.left, art.top + side * 0.42f, r.right, r.bottom)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, panel.top, 0f, panel.bottom, 0xF0303030.toInt(), 0xF0101010.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(panel, 16f * sc, 16f * sc, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * sc
        p.color = al(Color.WHITE, 40)
        c.drawRoundRect(panel, 16f * sc, 16f * sc, p)
        p.style = Paint.Style.FILL
        val bmp = media.art
        c.save()
        c.clipRect(art)
        if (bmp != null) {
            val k = max(art.width() / bmp.width, art.height() / bmp.height)
            val sw = art.width() / k
            val sh = art.height() / k
            val src = android.graphics.Rect(((bmp.width - sw) / 2).toInt(), ((bmp.height - sh) / 2).toInt(), ((bmp.width + sw) / 2).toInt(), ((bmp.height + sh) / 2).toInt())
            c.drawBitmap(bmp, src, art, iconPaint)
        } else if (!nine(c, "qgn_graf_mup_npv_defalbumart", art, 0, 0f)) {
            p.color = 0xFF2A2A2A.toInt()
            c.drawRect(art, p)
        }
        // the title strip along the bottom of the cover
        val strip = RectF(art.left, art.bottom - 40f * sc, art.right, art.bottom)
        p.color = al(Color.BLACK, 190)
        c.drawRect(strip, p)
        val title = if (media.available) (media.title ?: "Playing") + (media.artist?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: "") else if (!HubStore.hasAccess(act)) "Allow notification access" else "Nothing is playing"
        text(c, title, strip.left + 10f * sc, baselineFor(strip.centerY(), 21f * sc), 21f * sc, Color.WHITE, maxW = strip.width() - 20f * sc)
        c.restore()
        val by = r.bottom - 46f * sc
        val dx = min(86f * sc, r.width() * 0.28f)
        val xs = floatArrayOf(r.centerX() - dx, r.centerX(), r.centerX() + dx)
        val ds = floatArrayOf(54f * sc, 82f * sc, 54f * sc)
        for (i in 0..2) {
            mediaDisc(c, i, xs[i], by, ds[i])
            val hh = ds[i] / 2f + 3f * sc
            subRects[i].set(xs[i] - hh, by - hh, xs[i] + hh, by + hh)
        }
    }

    private fun drawInbox(c: Canvas, r: RectF) {
        paperWidget(c, r, false)
        HubStore.load(act)
        val ink = 0xFF111111.toInt()
        val grey = 0xFF707070.toInt()
        val total = HubStore.notes.count { it.title.isNotBlank() || it.text.isNotBlank() }
        val fit = max(1, (r.height() / (66f * sc)).toInt())
        val list = HubStore.notes.filter { it.title.isNotBlank() || it.text.isNotBlank() }.take(min(3, fit))
        if (!HubStore.hasAccess(act)) {
            text(c, "Press ${okLetter()} and allow notification access", r.centerX(), baselineFor(r.centerY(), 18f * sc), 18f * sc, grey, Paint.Align.CENTER, maxW = r.width() - 30f * sc)
            return
        }
        if (list.isEmpty()) {
            text(c, "No notifications yet", r.centerX(), baselineFor(r.centerY(), 20f * sc), 20f * sc, grey, Paint.Align.CENTER)
            return
        }
        val fmt = DateFormat.getTimeFormat(act)
        val rowH = r.height() / list.size.toFloat()
        var y = r.top
        for ((i, n) in list.withIndex()) {
            if (i > 0) {
                p.color = al(Color.BLACK, 60)
                c.drawRect(r.left + 16f * sc, y, r.right - 16f * sc, y + 1f * sc, p)
            }
            val t = fmt.format(java.util.Date(n.time))
            text(c, t, r.right - 26f * sc, baselineFor(y + rowH * 0.32f, 19f * sc, light), 19f * sc, 0xFF3E7FBF.toInt(), Paint.Align.RIGHT, face = light)
            text(c, n.title.ifBlank { n.pkg }, r.left + 18f * sc, baselineFor(y + rowH * 0.32f, 21f * sc, medium), 21f * sc, ink, maxW = r.width() - 110f * sc, face = medium)
            text(c, n.text, r.left + 18f * sc, baselineFor(y + rowH * 0.70f, 20f * sc, light), 20f * sc, grey, maxW = r.width() - 48f * sc, face = light)
            y += rowH
        }
        // the blue scroll bar down the right edge of the list
        if (total > list.size) {
            p.color = 0xFF3E7FBF.toInt()
            rf.set(r.right - 9f * sc, r.top + 8f * sc, r.right - 5f * sc, r.top + 8f * sc + (r.height() - 16f) * list.size / total)
            c.drawRoundRect(rf, 2f * sc, 2f * sc, p)
        }
    }

    private fun drawSwitches(c: Canvas, r: RectF) {
        darkCard(c, r, 14f * sc)
        val ringer = try { (act.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).ringerMode } catch (_: Exception) { 2 }
        val labels = arrayOf("Wi-Fi", if (ringer == android.media.AudioManager.RINGER_MODE_NORMAL) "Sound on" else "Quiet", "Settings")
        val on = booleanArrayOf(wifi || wifiOn(), ringer == android.media.AudioManager.RINGER_MODE_NORMAL, false)
        val h = min(r.height() - 30f * sc, 68f * sc)
        val w = h * 58f / 66f
        for (i in 0..2) {
            val cx = r.left + r.width() * (0.2f + 0.3f * i)
            rf.set(cx - w / 2f, r.top + 6f * sc, cx + w / 2f, r.top + 6f * sc + h)
            subRects[i].set(rf)
            // the real Belle panel switch pictures, as on the Status menu
            val art = when (i) { 0 -> "wlan"; 1 -> "audio"; else -> null }
            if (art != null) {
                gfx.draw(c, "qtg_graf_panel_switch_${art}_${if (on[i]) "on" else "off"}", rf)
            } else {
                p.style = Paint.Style.FILL
                p.shader = LinearGradient(0f, rf.top + 4f * sc, 0f, rf.bottom - 8f * sc, 0xFF3A3A3A.toInt(), 0xFF1C1C1C.toInt(), Shader.TileMode.CLAMP)
                c.drawRoundRect(rf.left + 3f * sc, rf.top + 5f * sc, rf.right - 3f * sc, rf.bottom - 9f * sc, 14f * sc, 14f * sc, p)
                p.shader = null
                gfx.glyph(c, "toolbar_settings", rf.centerX(), rf.centerY() - 2f * sc, 30f * sc, 255)
            }
            text(c, labels[i], cx, r.bottom - 7f * sc, 14f * sc, Color.WHITE, Paint.Align.CENTER, face = light)
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
        // the same dimmed wallpaper as the Menu and Recent
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = al(Color.BLACK, 150)
        c.drawRect(0f, cTop, w, cBot, p)

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

        val rowH = 94f * sc
        val headH = 30f * sc
        var total = 0f
        for (it in hubItems) total += if (it.header != null) headH else rowH
        val panelH = min(avail, total + 12f * sc)
        val panel = RectF(0f, panelTop, w, panelTop + panelH)

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
                    // a grey section bar, as on the Status menu
                    p.shader = LinearGradient(0f, yy, 0f, yy + h, 0xFF4A4A4A.toInt(), 0xFF383838.toInt(), Shader.TileMode.CLAMP)
                    c.drawRect(panel.left, yy, panel.right, yy + h, p)
                    p.shader = null
                    text(c, it.header, panel.left + 14f * sc, baselineFor(yy + h / 2f, 16f * sc, medium), 16f * sc, Color.WHITE, face = medium)
                } else {
                    val n = it.note!!
                    val rr = RectF(panel.left + 6f * sc, yy + 1f * sc, panel.right - 6f * sc, yy + h - 1f * sc)
                    if (i == hubSel) nine(c, "qtg_fr_list_pressed", rr, 40, 14f) else {
                        p.color = al(Color.WHITE, 30)
                        c.drawRect(rr.left + 8f * sc, rr.bottom, rr.right - 8f * sc, rr.bottom + 1f, p)
                    }
                    val isz = 56f * sc
                    noteIcon(n.pkg)?.let { b ->
                        rf.set(rr.left + 10f * sc, rr.centerY() - isz / 2f, rr.left + 10f * sc + isz, rr.centerY() + isz / 2f)
                        c.drawBitmap(b, null, rf, iconPaint)
                    }
                    val tx = rr.left + 10f * sc + isz + 12f * sc
                    val meta = appLabel(n.pkg) + "  " + DateFormat.format(if (DateFormat.is24HourFormat(act)) "HH:mm" else "h:mm a", n.time)
                    val mw = min(measure(meta, 17f * sc, light), rr.width() * 0.4f)
                    text(c, meta, rr.right - 12f * sc, baselineFor(rr.top + 30f * sc, 17f * sc, light), 17f * sc, if (i == hubSel) Color.WHITE else grey, Paint.Align.RIGHT, maxW = rr.width() * 0.4f, face = light)
                    text(c, n.title.ifBlank { appLabel(n.pkg) }, tx, baselineFor(rr.top + 30f * sc, 24f * sc, medium), 24f * sc, Color.WHITE, maxW = rr.right - tx - mw - 24f * sc, face = medium)
                    if (n.text.isNotBlank()) text(c, n.text.replace('\n', ' '), tx, baselineFor(rr.top + 66f * sc, 20f * sc, light), 20f * sc, if (i == hubSel) Color.WHITE else 0xFFBBBBBB.toInt(), maxW = rr.right - tx - 14f * sc, face = light)
                    hubHits.add(RectF(rr) to i)
                }
            }
            yy += h
        }
        c.restore()
    }

    // ---- page 1: the Menu -- 151x122 cells, 72 px icons, 19 px labels over the dimmed wallpaper -----

    private fun drawMenu(c: Canvas) {
        // entering or leaving a folder: the firmware's folder_open / folder_close, a 0.3 s linear reveal
        val t = ((System.currentTimeMillis() - folderWipeStart) / 300f).coerceIn(0f, 1f)
        if (t >= 1f) {
            drawMenuGrid(c)
            return
        }
        c.save()
        c.clipRect(0f, cTop, width * t, cBot)
        drawMenuGrid(c)
        c.restore()
        postInvalidateOnAnimation()
    }

    private fun drawMenuGrid(c: Canvas) {
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
            val cw = menuCellW()
            val ch = menuCellH()
            val isz = 74f * sc // smaller than the measured 84 so the cells aren't cramped on this taller screen
            for (idx in menuApps.indices) {
                menuCellRect(idx, menuTopAnim, tmp)
                if (tmp.bottom < area || tmp.top > cBot) continue
                val app = menuApps[idx]
                val dim = if (repo.isHidden(app.key)) 110 else 255
                // cell_app_pane_t1: a 30 px label pane, 5 px above the cell's bottom edge (centre 101 px down); the icon sits 12 px down
                val iconTop = tmp.top + 9f * sc
                val lift = if (reorder && idx == menuSel) 7f * sc else 0f
                drawIcon(c, app.icon, tmp.centerX(), iconTop + isz / 2f - lift, isz, dim)
                text(c, app.label, tmp.centerX(), baselineFor(tmp.top + 101f * sc, 21f * sc), 21f * sc, al(Color.WHITE, dim), Paint.Align.CENTER, cw - 10f * sc)
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

    // ---- page 2: recent apps -- E6 list rows: 94 px, 64 px icon, 26 px title, 21 px subtitle ---------

    private fun drawRecent(c: Canvas) {
        val w = width.toFloat()
        val listTop = cTop + HEADER * sc
        p.style = Paint.Style.FILL
        p.shader = null
        // the same dimmed wallpaper as the Menu
        p.color = al(Color.BLACK, 170)
        c.drawRect(0f, cTop, w, cBot, p)
        if (recents.isEmpty()) {
            text(c, "No recent apps", w / 2, (listTop + cBot) / 2, 22f * sc, 0xFFBBBBBB.toInt(), Paint.Align.CENTER)
            return
        }
        nine(c, "qtg_fr_list_pressed", recentFocus.cur, 40, 20f)
        val used = repo.lastUsed
        val now = System.currentTimeMillis()
        for (i in recents.indices) {
            recentRowRect(i, recentTopAnim, tmp)
            if (tmp.bottom < listTop || tmp.top > cBot) continue
            val app = recents[i]
            val sel = i == recentSel
            if (i != recentSel) {
                p.color = al(Color.WHITE, 30)
                c.drawRect(14f * sc, tmp.bottom - 1f, w - 14f * sc, tmp.bottom, p)
            }
            val pad = 14f * sc
            drawIcon(c, app.icon, pad + 32f * sc, tmp.centerY(), 64f * sc)
            val tx = pad + 64f * sc + pad
            val t = used[app.component.packageName]
            val sub = if (t != null) android.text.format.DateUtils.getRelativeTimeSpanString(t, now, android.text.format.DateUtils.MINUTE_IN_MILLIS).toString() else "Opened from here"
            text(c, app.label, tx, tmp.centerY() - 6f * sc, 26f * sc, Color.WHITE, maxW = w - tx - 70f * sc, face = medium)
            text(c, sub, tx, tmp.centerY() + 27f * sc, 21f * sc, if (sel) Color.WHITE else 0xFFB5B5B5.toInt(), maxW = w - tx - 70f * sc, face = light)
            // the cross on the right closes the app
            glyph(c, ICON_CLOSE, w - 34f * sc, tmp.centerY(), 26f * sc, if (sel) Color.WHITE else al(Color.WHITE, 150))
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

    // ---- chrome -------------------------------------------------------------------------------------

    /** The Home status title as on a real Belle phone: the network's name, or "Offline"; the profile when there is neither (no SIM). */
    private fun homeTitle(): String {
        try {
            if (Settings.Global.getInt(act.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0) return "Offline"
            val tm = act.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            val n = tm.networkOperatorName
            if (!n.isNullOrBlank()) return n
        } catch (_: Throwable) {
        }
        return try { com.belleui.kit.Profiles.names[com.belleui.kit.Profiles.current(act)] } catch (_: Throwable) { "Home" }
    }

    private fun pageTitle() = when (page) {
        -1 -> "Hub"
        0 -> homeTitle()
        1 -> if (searchQuery.isNotEmpty()) "Search" else (repo.openFolder ?: "Menu")
        else -> "Recent"
    }

    /** 36 px status bar (only when the Belle System UI is not drawing the real one): title on the left, indicators and clock on the right. */
    private fun drawStatusBar(c: Canvas, w: Float) {
        rf.set(0f, 0f, w, statusH)
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Color.BLACK
        c.drawRect(rf, p)
        nine(c, "qtg_fr_statusbar", rf, 20, 10f)

        val cy = statusH / 2f
        text(c, pageTitle(), 6f * sc, baselineFor(cy, 30f * sc), 30f * sc, Color.WHITE)
        val time = if (ampmStr.isEmpty()) clockStr else "$clockStr $ampmStr"
        val csz = 26f * sc
        val tw = measure(time, csz)
        text(c, time, w - 8f * sc, baselineFor(cy, csz), csz, Color.WHITE, Paint.Align.RIGHT)

        val bw = 34f * sc
        val bh = 20f * sc
        val bLeft = w - 8f * sc - tw - 12f * sc - bw
        drawBattery(c, bLeft, cy - bh / 2f, bw, bh)
        if (wifi) drawWifi(c, bLeft - 26f * sc, statusH * 0.78f, 13f * sc)
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
     * 67 px toolbar with 30 px white glyphs. Home: Menu, page dots, Options. Menu: Back, Search, Options (both as in
     * the E6 Belle theme's HomeScreen.xml / ApplicationGrid.xml). Recent: Back, Close, Options.
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
        if ((page == 1 || page == 2) && !inModal) {
            // the Belle Menu toolbar: back, search (or close, on Recent), menu
            val mm = 56f * sc
            toolIcon(c, ICON_BACK, mm, cy, s)
            hintLetter(c, backLetter(), mm + 26f * sc, cy)
            if (page == 1) gfx.glyph(c, "toolbar_search", w / 2f, cy, s, 255) else glyph(c, ICON_CLOSE, w / 2f, cy, s, Color.WHITE)
            hintLetter(c, settingsLetter(), w / 2f + 26f * sc, cy)
            toolIcon(c, ICON_MENU, w - mm, cy, s)
            hintLetter(c, optLetter(), w - mm - 26f * sc, cy)
            return
        }
        if (page == 0 && !inModal) {
            // the E6 home toolbar: Menu, page dots, options (positions measured on the device)
            val k = w / 640f
            toolIcon(c, ICON_GRID, 60f * k, cy, s)
            hintLetter(c, backLetter(), 60f * k + 26f * sc, cy)
            toolIcon(c, ICON_MENU, w - 57f * k, cy, s)
            hintLetter(c, optLetter(), w - 57f * k - 26f * sc, cy)
            // page dots: the Hub, then one per home view; the current one lit
            val dots = 1 + repo.homeViewCount
            val lit = if (page == -1) 0 else 1 + homeView
            for (i in 0 until dots) {
                p.style = Paint.Style.FILL
                p.color = if (i == lit) Color.WHITE else al(Color.WHITE, 90)
                c.drawCircle(w / 2f - (dots - 1) * 10f * sc + i * 20f * sc, cy, 4.5f * sc, p)
            }
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
        if (!com.belleui.kit.Belle.keyHints) return
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

    // ---- overlays: options menu, selection popup, info banner - the same E6 look as the kit's -----

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
            // the E6 selection popup, as in the Belle apps: dark plate, the title in blue over a thin blue rule
            kit.popupPlate(c, box)
            text(c, pu.title, box.left + 14f * sc, baselineFor(box.top + titleH / 2f + 2f * sc, 23f * sc), 23f * sc, POPUP_BLUE, maxW = box.width() - 28f * sc)
            p.style = Paint.Style.FILL
            p.shader = null
            p.color = POPUP_BLUE
            c.drawRect(box.left + 8f * sc, box.top + titleH, box.right - 8f * sc, box.top + titleH + 2f * sc, p)
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
                kit.highlight(c, tmp, 14f)
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
        // the full-width button under the list: Cancel on the options menu, Back on a popup (67 px, as on the E6)
        val cy0 = top0 + itemH * vis + 6f * sc
        tmp.set(box.left + 8f * sc, cy0, box.right - 8f * sc, cy0 + POPUP_BTN * sc)
        kit.pushButton(c, tmp, if (pu.menu) "Cancel" else "Back", pu.menu && pu.sel == pu.items.size)
        c.restore()
    }

    /** Info banner: as tall as the toolbar, full width under the status bar, slides in and dismisses itself. */
    private fun drawToast(c: Canvas, w: Float) {
        val now = System.currentTimeMillis()
        val tin = ((now - toastStart) / 220f).coerceIn(0f, 1f)
        val tout = ((toastUntil - now) / 220f).coerceIn(0f, 1f)
        kit.banner(c, w, toast, min(tin, tout))
    }

    private companion object {
        const val SEARCH_H = 44f
        const val TAB = 68f      // tab bar, as tall as the toolbar
        const val HEADER = 6f    // a little air above the first row
        const val ROW = 94f      // list item height on the E6 (graphicSizeLarge)
        const val POPUP_ROW = 67f // menu and popup rows on the E6 (the kit's MENU_ITEM_H)
        const val POPUP_BTN = 68f // the full-width Cancel / Back button
        const val POPUP_BLUE = 0xFF4A90D9.toInt() // popup titles and their rule
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
