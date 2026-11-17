package com.belleui.vector

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
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.belleui.kit.Belle
import com.belleui.kit.Profiles
import com.belleui.kit.Row
import com.belleui.kit.Ui
import kotlin.math.max
import kotlin.math.min

/**
 * The Belle Status menu in two columns, laid out for the landscape screen: quick settings (seven tiles, the profile and the
 * brightness) on the left, notifications and activities on the right. Every tile is drawn the same way: a rounded dark key
 * with a white glyph, lit in the accent colour when on.
 */
internal class StatusMenu(val sys: Context, val mod: Context, val close: () -> Unit) : View(sys) {
    private var ui: Ui? = null
    private val am = sys.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val hasCell = sys.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)
    private var notes: List<Hud.Notes.Item> = emptyList()
    private var noteProblem: String? = null
    private var notesAt = 0L
    private val icons = HashMap<String, Bitmap?>()
    private var slide = 0f
    private var scroll = 0f
    private var torch = false
    private val cam = try { sys.getSystemService(Context.CAMERA_SERVICE) as CameraManager } catch (_: Throwable) { null }
    private var torchId: String? = null

    // focus: zone 0 = the switches (0..3), the More row (9) and, when open, extras (4..6 tiles, 7 profile, 8 brightness);
    // zone 1 = the notification list (0 = Clear, 1.. = rows)
    private var zone = 0
    private var leftSel = 1
    private var rightSel = 1
    private val tileRects = Array(8) { RectF() }
    private val profileRect = RectF()
    private val brightRect = RectF()
    private val barRect = RectF()
    private val clearRect = RectF()
    private val rowRects = ArrayList<Pair<Int, RectF>>()
    private val r = RectF()
    private var dragBright = false
    private var dragY = 0f
    private var downY = 0f
    private var downOnHandle = false
    private var dragMoved = false
    private var anim: android.animation.ValueAnimator? = null
    private var closing = false
    private var extras = false            // the "More" panel: rotation, torch, settings, profile, brightness
    private val moreRect = RectF()
    private var listTopPx = 0f

    private val torchCb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id == torchId) { torch = enabled; invalidate() }
        }
    }

    private fun normal() = notes.filter { !it.ongoing }
    private fun acts() = notes.filter { it.ongoing }
    private fun rows(): List<Hud.Notes.Item> = normal() + acts()

    private val tick = object : Runnable {
        override fun run() {
            refreshNotes()
            invalidate()
            handler.postDelayed(this, 900)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tick)
        try {
            torchId = cam?.cameraIdList?.firstOrNull { cam.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            if (torchId != null) cam?.registerTorchCallback(torchCb, handler)
        } catch (_: Throwable) { }
        anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { slide = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Slides the panel back up, then closes it (the panel used to vanish at once). */
    private fun dismiss() {
        if (closing) return
        closing = true
        anim?.cancel()
        val from = slide
        anim = android.animation.ValueAnimator.ofFloat(from, 0f).apply {
            duration = (180 * from).toLong().coerceAtLeast(60L)
            interpolator = android.view.animation.AccelerateInterpolator()
            addUpdateListener { slide = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { close() }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(tick)
        anim?.cancel()
        try { cam?.unregisterTorchCallback(torchCb) } catch (_: Throwable) { }
    }

    private fun refreshNotes() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - notesAt < 700) return
        notesAt = now
        notes = Hud.Notes.active(sys)
        noteProblem = if (notes.isEmpty()) Hud.Notes.problem(sys) else null
        rightSel = rightSel.coerceIn(0, rows().size)
    }

    private fun icon(pkg: String): Bitmap? = icons.getOrPut(pkg) {
        try {
            val d = sys.packageManager.getApplicationIcon(pkg)
            val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 64, 64)
            d.draw(Canvas(b))
            b
        } catch (_: Throwable) {
            null
        }
    }

    // ---- switches ---------------------------------------------------------------------------------

    private fun wifi(): Boolean = try { (sys.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled } catch (_: Throwable) { false }
    private fun bt(): Boolean = try { @Suppress("DEPRECATION") android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.isEnabled == true } catch (_: Throwable) { false }
    private fun airplane(): Boolean = try { Settings.Global.getInt(sys.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0 } catch (_: Throwable) { false }
    private fun dataOn(): Boolean = try {
        val tm = sys.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        tm.javaClass.getMethod("isDataEnabled").invoke(tm) as Boolean
    } catch (_: Throwable) { false }
    private fun soundOn() = am.ringerMode == AudioManager.RINGER_MODE_NORMAL
    private fun autoRotate(): Boolean = try { Settings.System.getInt(sys.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) != 0 } catch (_: Throwable) { false }

    private val names get() = arrayOf(if (hasCell) "Data" else "Airplane", "Wi-Fi", "Bluetooth", "Silence", "Rotation", "Torch", "Settings")
    private fun on(i: Int) = when (i) {
        0 -> if (hasCell) dataOn() else airplane()
        1 -> wifi()
        2 -> bt()
        3 -> !soundOn()
        4 -> autoRotate()
        5 -> torch
        else -> false
    }

    private fun toggle(i: Int) {
        try {
            when (i) {
                0 -> if (hasCell) {
                    val tm = sys.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                    tm.javaClass.getMethod("setDataEnabled", Boolean::class.javaPrimitiveType).invoke(tm, !dataOn())
                } else {
                    val on = !airplane()
                    try {
                        val cm = sys.getSystemService(Context.CONNECTIVITY_SERVICE)
                        cm.javaClass.getMethod("setAirplaneMode", Boolean::class.javaPrimitiveType).invoke(cm, on)
                    } catch (_: Throwable) {
                        Settings.Global.putInt(sys.contentResolver, Settings.Global.AIRPLANE_MODE_ON, if (on) 1 else 0)
                        sys.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", on))
                    }
                }
                1 -> @Suppress("DEPRECATION") (sys.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).setWifiEnabled(!wifi())
                2 -> @Suppress("DEPRECATION") android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.let { if (it.isEnabled) it.disable() else it.enable() }
                3 -> am.ringerMode = if (soundOn()) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
                4 -> Settings.System.putInt(sys.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (autoRotate()) 0 else 1)
                5 -> torchId?.let { cam?.setTorchMode(it, !torch) }
                6 -> openSettings()
            }
        } catch (_: Throwable) {
        }
    }

    /** The Belle Settings app; the Android one if that is missing. */
    private fun openSettings() {
        close()
        val tries = listOf(
            Intent().setClassName("com.belleui.settings", "com.belleui.settings.SettingsActivity"),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in tries) {
            try {
                sys.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                return
            } catch (_: Throwable) {
            }
        }
        Hud.showNote(sys, mod, "Settings", "Could not open the Settings app")
    }

    private fun brightness(): Int = try { Settings.System.getInt(sys.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) } catch (_: Throwable) { 128 }

    private fun setBrightness(v: Int) {
        try {
            Settings.System.putInt(sys.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(sys.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v.coerceIn(8, 255))
        } catch (_: Throwable) {
        }
    }

    private fun batteryText(): String {
        val i = sys.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return ""
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        return "${level * 100 / scale}%"
    }

    // ---- glyphs -----------------------------------------------------------------------------------

    private fun glyph(c: Canvas, u: Ui, kind: Int, cx: Float, cy: Float, s: Float, lit: Boolean) {
        val p = u.p
        p.color = if (lit) Color.WHITE else 0xFFB8B8B8.toInt()
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.8f * u.sc
        p.strokeCap = Paint.Cap.ROUND
        when (kind) {
            0 -> if (hasCell) {
                c.drawLine(cx - s * 0.35f, cy + s * 0.5f, cx - s * 0.35f, cy - s * 0.5f, p)
                c.drawLine(cx - s * 0.35f, cy - s * 0.5f, cx - s * 0.65f, cy - s * 0.15f, p)
                c.drawLine(cx - s * 0.35f, cy - s * 0.5f, cx - s * 0.05f, cy - s * 0.15f, p)
                c.drawLine(cx + s * 0.35f, cy - s * 0.5f, cx + s * 0.35f, cy + s * 0.5f, p)
                c.drawLine(cx + s * 0.35f, cy + s * 0.5f, cx + s * 0.05f, cy + s * 0.15f, p)
                c.drawLine(cx + s * 0.35f, cy + s * 0.5f, cx + s * 0.65f, cy + s * 0.15f, p)
            } else {
                c.drawLine(cx - s * 0.6f, cy, cx + s * 0.6f, cy, p)
                c.drawLine(cx - s * 0.1f, cy, cx - s * 0.45f, cy - s * 0.55f, p)
                c.drawLine(cx - s * 0.1f, cy, cx - s * 0.45f, cy + s * 0.55f, p)
                c.drawLine(cx + s * 0.45f, cy, cx + s * 0.6f, cy - s * 0.3f, p)
                c.drawLine(cx + s * 0.45f, cy, cx + s * 0.6f, cy + s * 0.3f, p)
            }
            1 -> {
                for (k in 1..3) c.drawArc(RectF(cx - k * s * 0.3f, cy + s * 0.35f - k * s * 0.3f, cx + k * s * 0.3f, cy + s * 0.35f + k * s * 0.3f), -135f, 90f, false, p)
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy + s * 0.38f, 2.8f * u.sc, p)
            }
            2 -> {
                c.drawLine(cx, cy - s * 0.7f, cx, cy + s * 0.7f, p)
                c.drawLine(cx, cy - s * 0.7f, cx + s * 0.4f, cy - s * 0.3f, p)
                c.drawLine(cx + s * 0.4f, cy - s * 0.3f, cx - s * 0.4f, cy + s * 0.35f, p)
                c.drawLine(cx, cy + s * 0.7f, cx + s * 0.4f, cy + s * 0.3f, p)
                c.drawLine(cx + s * 0.4f, cy + s * 0.3f, cx - s * 0.4f, cy - s * 0.35f, p)
            }
            3 -> {
                p.style = Paint.Style.FILL
                val path = Path()
                path.moveTo(cx - s * 0.55f, cy - s * 0.2f); path.lineTo(cx - s * 0.2f, cy - s * 0.2f); path.lineTo(cx + s * 0.15f, cy - s * 0.55f)
                path.lineTo(cx + s * 0.15f, cy + s * 0.55f); path.lineTo(cx - s * 0.2f, cy + s * 0.2f); path.lineTo(cx - s * 0.55f, cy + s * 0.2f); path.close()
                c.drawPath(path, p)
                p.style = Paint.Style.STROKE
                if (!lit) c.drawArc(RectF(cx + s * 0.05f, cy - s * 0.4f, cx + s * 0.65f, cy + s * 0.4f), -50f, 100f, false, p)
                else c.drawLine(cx + s * 0.25f, cy - s * 0.45f, cx + s * 0.6f, cy + s * 0.45f, p)
            }
            4 -> { // rotation: a circular arrow
                c.drawArc(RectF(cx - s * 0.55f, cy - s * 0.55f, cx + s * 0.55f, cy + s * 0.55f), -60f, 280f, false, p)
                c.drawLine(cx + s * 0.28f, cy - s * 0.55f, cx + s * 0.62f, cy - s * 0.55f, p)
                c.drawLine(cx + s * 0.28f, cy - s * 0.55f, cx + s * 0.28f, cy - s * 0.2f, p)
            }
            5 -> { // torch: a beam
                p.style = Paint.Style.FILL
                val body = RectF(cx - s * 0.2f, cy + s * 0.05f, cx + s * 0.2f, cy + s * 0.7f)
                c.drawRoundRect(body, 3f * u.sc, 3f * u.sc, p)
                p.style = Paint.Style.STROKE
                c.drawLine(cx - s * 0.2f, cy + s * 0.05f, cx - s * 0.5f, cy - s * 0.4f, p)
                c.drawLine(cx + s * 0.2f, cy + s * 0.05f, cx + s * 0.5f, cy - s * 0.4f, p)
                c.drawLine(cx - s * 0.5f, cy - s * 0.4f, cx + s * 0.5f, cy - s * 0.4f, p)
            }
            else -> u.gfx.glyph(c, "toolbar_settings", cx, cy, s * 1.3f, 255)
        }
        p.style = Paint.Style.FILL
    }

    private fun tile(c: Canvas, u: Ui, i: Int, rc: RectF) {
        val sc = u.sc
        val lit = on(i)
        val focus = zone == 0 && leftSel == i
        val rad = 15f * sc
        u.p.style = Paint.Style.FILL
        u.p.shader = LinearGradient(0f, rc.top, 0f, rc.bottom,
            if (lit) intArrayOf(Belle.BLUE_LIGHT, Belle.BLUE) else intArrayOf(0xFF454545.toInt(), 0xFF1B1B1B.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(rc, rad, rad, u.p)
        u.p.shader = null
        u.p.style = Paint.Style.STROKE
        u.p.strokeWidth = 1.5f * sc
        u.p.color = 0xFF000000.toInt()
        c.drawRoundRect(rc, rad, rad, u.p)
        glyph(c, u, i, rc.centerX(), rc.centerY(), rc.width() * 0.42f, lit)
        if (focus) {
            u.p.strokeWidth = 3f * sc
            u.p.color = Color.WHITE
            c.drawRoundRect(rc.left - 3f * sc, rc.top - 3f * sc, rc.right + 3f * sc, rc.bottom + 3f * sc, rad + 2f * sc, rad + 2f * sc, u.p)
        }
        u.p.style = Paint.Style.FILL
        u.text(c, names[i], rc.centerX(), rc.bottom + 19f * sc, 15f * sc, Color.WHITE, Paint.Align.CENTER, face = u.light, maxW = rc.width() + 18f * sc)
    }

    private val switchNames = arrayOf("signal", "wlan", "bt", "audio")

    /** The Belle panel switch: the theme's own key graphic (qtg_graf_panel_switch_*), blue when on, no label. */
    private fun switchTile(c: Canvas, u: Ui, i: Int, rc: RectF) {
        val name = "qtg_graf_panel_switch_${switchNames[i]}_${if (on(i)) "on" else "off"}"
        if (!u.gfx.draw(c, name, rc)) { tile(c, u, i, rc); return }
        if (zone == 0 && leftSel == i) {
            val sc = u.sc
            u.p.style = Paint.Style.STROKE
            u.p.strokeWidth = 3f * sc
            u.p.color = Color.WHITE
            c.drawRoundRect(rc.left - 3f * sc, rc.top - 3f * sc, rc.right + 3f * sc, rc.bottom + 3f * sc, 16f * sc, 16f * sc, u.p)
            u.p.style = Paint.Style.FILL
        }
    }

    /** A list row: transparent with a 1 px line at 15 % white under it (qtg_fr_list_normal); the focused row is Belle blue (qtg_fr_list_pressed). */
    private fun listBg(c: Canvas, u: Ui, rc: RectF, focus: Boolean) {
        if (focus) {
            u.p.shader = LinearGradient(0f, rc.top, 0f, rc.bottom, Belle.BLUE_LIGHT, Belle.BLUE, Shader.TileMode.CLAMP)
            c.drawRect(rc, u.p)
            u.p.shader = null
        } else {
            u.p.color = 0x26FFFFFF
            c.drawRect(rc.left, rc.bottom - 1f, rc.right, rc.bottom, u.p)
        }
    }

    override fun onDraw(c: Canvas) {
        val u = ui ?: Hud.ui(mod, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).also { ui = it }
        val sc = u.sc
        val w = width.toFloat()
        val panelH = height.toFloat()
        val off = -(1f - slide) * panelH
        rowRects.clear()
        c.save()
        c.translate(0f, off)
        c.clipRect(0f, 0f, w, panelH)
        u.p.style = Paint.Style.FILL
        u.p.shader = null
        u.p.color = 0xFF0C0C0C.toInt()
        c.drawRect(0f, 0f, w, panelH, u.p)

        // the system status bar sits right above this panel (as in the real Belle pull-down), so there is no title bar of its own
        val top = 0f

        val handleH = 26f * sc
        val bottom = panelH - handleH

        // the four switches (the original's Mobile data, Wi-Fi, Bluetooth, Silent), each the 54 x 62 key from the theme
        val kw = 54f * sc
        val kh = 62f * sc
        val rowTop = top + 8f * sc
        val cellW = w / 4f
        for (i in 0 until 4) {
            val x = i * cellW + (cellW - kw) / 2f
            tileRects[i].set(x, rowTop, x + kw, rowTop + kh)
            switchTile(c, u, i, tileRects[i])
        }
        // the labels the real menu puts under the switches (Mobile data, Wi-Fi, Bluetooth, Silent)
        val labels = arrayOf(if (hasCell) "Mobile data" else "Airplane", "Wi-Fi", "Bluetooth", "Silent")
        for (i in 0 until 4) u.text(c, labels[i], tileRects[i].centerX(), u.baselineFor(tileRects[i].bottom + 17f * sc, 21f * sc), 21f * sc, Color.WHITE, Paint.Align.CENTER, face = u.light, maxW = cellW - 8f * sc)
        var y = rowTop + kh + 34f * sc

        // the More row opens the extras (rotation, torch, settings, profile, brightness) in place of the notifications
        val moreH = 34f * sc
        moreRect.set(0f, y, w, y + moreH)
        u.p.color = 0x26FFFFFF
        c.drawRect(0f, y, w, y + 1f, u.p)
        if (zone == 0 && leftSel == 9) listBg(c, u, moreRect, true)
        u.text(c, if (extras) "Less" else "More", w / 2f, u.baselineFor(y + moreH / 2f, 18f * sc, u.light), 18f * sc,
            if (zone == 0 && leftSel == 9) Color.WHITE else Belle.TEXT_MID, Paint.Align.CENTER, face = u.light)
        y += moreH
        val listTop = y
        listTopPx = listTop

        if (extras) {
            for (i in 4..6) tileRects[i].setEmpty()
            clearRect.setEmpty()
            val ts = 56f * sc
            val ey0 = listTop + 8f * sc
            for (i in 4..6) {
                val x = (i - 4) * cellW + (cellW - ts) / 2f
                tileRects[i].set(x, ey0, x + ts, ey0 + ts)
                tile(c, u, i, tileRects[i])
            }
            var yy = ey0 + ts + 32f * sc
            val rowH = 70f * sc
            profileRect.set(0f, yy, w, yy + rowH)
            listBg(c, u, profileRect, zone == 0 && leftSel == 7)
            val pi = Profiles.current(sys)
            r.set(14f * sc, yy + 7f * sc, 14f * sc + 56f * sc, yy + 7f * sc + 56f * sc)
            u.gfx.draw(c, if (pi == 1) "qgn_indi_home_profile_silent" else if (pi == 2) "qgn_indi_home_profile_timed" else "qgn_indi_home_profile_general", r)
            val focusP = zone == 0 && leftSel == 7
            u.text(c, "Profile", 84f * sc, yy + 28f * sc, 24f * sc, Color.WHITE, face = u.medium)
            u.text(c, Profiles.names[pi], 84f * sc, yy + 56f * sc, 20f * sc, if (focusP) Color.WHITE else Belle.TEXT_MID, face = u.light)
            yy += rowH
            brightRect.set(0f, yy, w, yy + rowH)
            listBg(c, u, brightRect, zone == 0 && leftSel == 8)
            u.text(c, "Brightness", 16f * sc, yy + 28f * sc, 24f * sc, Color.WHITE, face = u.medium)
            barRect.set(16f * sc, yy + 40f * sc, w - 20f * sc, yy + 58f * sc)
            u.progressBar(c, barRect, brightness() / 255f)
        } else {
            for (i in 4..6) tileRects[i].setEmpty()
            profileRect.setEmpty()
            brightRect.setEmpty()
            barRect.setEmpty()

            // ---- notifications and activities ----
            c.save()
            c.clipRect(0f, listTop, w, bottom)
            val all = rows()
            val nCount = normal().size
            var yy = listTop - scroll
            var selTop = 0f
            var selBottom = 0f
            fun header(title: String, button: String?, focusIdx: Int) {
                // qtg_fr_list_heading_normal: flat #191919, a 15 % white line on top; ListHeading is graphicSizeSmall (40) high
                val h = 40f * sc
                u.p.color = 0xFF191919.toInt()
                c.drawRect(0f, yy, w, yy + h, u.p)
                u.p.color = 0x26FFFFFF
                c.drawRect(0f, yy, w, yy + 1f, u.p)
                u.text(c, title, 18f * sc, u.baselineFor(yy + h / 2f, 21f * sc, u.medium), 21f * sc, Color.WHITE, face = u.medium)
                if (button != null) {
                    clearRect.set(w - 106f * sc, yy + 4f * sc, w - 10f * sc, yy + h - 4f * sc)
                    u.pushButton(c, clearRect, button, zone == 1 && rightSel == focusIdx)
                    if (zone == 1 && rightSel == focusIdx) { selTop = yy + scroll; selBottom = yy + h + scroll }
                } else clearRect.setEmpty()
                yy += h
            }
            header("Notifications", if (nCount > 0) "Clear" else null, 0)
            if (nCount == 0) {
                u.text(c, noteProblem ?: "No notifications", 20f * sc, u.baselineFor(yy + 30f * sc, 22f * sc), 22f * sc, if (noteProblem != null) 0xFFE8A33D.toInt() else Belle.TEXT_MID, maxW = w - 36f * sc, face = u.light)
                yy += 62f * sc
            }
            var shownActs = false
            for ((idx, n) in all.withIndex()) {
                if (n.ongoing && !shownActs) { shownActs = true; header("Activities", null, -1) }
                val h = 94f * sc
                val focus = zone == 1 && rightSel == idx + 1
                val rc = RectF(0f, yy, w, yy + h)
                if (focus) { selTop = yy + scroll; selBottom = yy + h + scroll }
                if (yy + h > listTop && yy < bottom) {
                    listBg(c, u, rc, focus)
                    val ic = icon(n.pkg)
                    r.set(14f * sc, yy + 19f * sc, 14f * sc + 56f * sc, yy + 19f * sc + 56f * sc)
                    if (ic != null) c.drawBitmap(ic, null, r, null)
                    if (!n.ongoing && System.currentTimeMillis() - n.postTime < 10 * 60_000L) {
                        u.p.color = 0xFFE23B3B.toInt()
                        c.drawCircle(66f * sc, yy + 21f * sc, 6f * sc, u.p)
                    }
                    val tx = 84f * sc
                    val time = android.text.format.DateFormat.format(if (android.text.format.DateFormat.is24HourFormat(sys)) "HH:mm" else "h:mm a", n.postTime).toString()
                    u.text(c, time, w - 14f * sc, yy + 36f * sc, 18f * sc, if (focus) Color.WHITE else Belle.BLUE_LIGHT, Paint.Align.RIGHT, face = u.light)
                    u.text(c, n.title, tx, yy + 38f * sc, 24f * sc, Color.WHITE, maxW = w - tx - 72f * sc, face = u.medium)
                    if (n.progress != null) u.progressBar(c, RectF(tx, yy + 54f * sc, w - 18f * sc, yy + 74f * sc), n.progress)
                    else if (!n.text.isNullOrBlank()) u.text(c, n.text, tx, yy + 70f * sc, 20f * sc, if (focus) Color.WHITE else Belle.TEXT_MID, maxW = w - tx - 16f * sc, face = u.light)
                }
                rowRects.add((idx + 1) to RectF(0f, yy + off, w, yy + h + off))
                yy += h
            }
            c.restore()
            val contentH = yy + scroll - listTop
            // keep the focused row on screen
            if (zone == 1) {
                if (selBottom - scroll > bottom - listTop) scroll = selBottom - (bottom - listTop)
                if (selTop - scroll < 0f) scroll = selTop
            }
            scroll = scroll.coerceIn(0f, max(0f, contentH - (bottom - listTop)))
        }

        // the pull handle
        u.p.color = 0xFF1A1A1A.toInt()
        c.drawRect(0f, bottom, w, panelH, u.p)
        r.set(w / 2f - 39f * sc, bottom + 1f * sc, w / 2f + 39f * sc, panelH - 1f * sc)
        if (!u.gfx.draw(c, "qtg_graf_panel_handle_normal", r)) {
            u.p.color = 0x55FFFFFF
            c.drawRect(w / 2f - 30f * sc, bottom + 11f * sc, w / 2f + 30f * sc, bottom + 14f * sc, u.p)
        }
        c.restore()
    }

    // ---- actions ------------------------------------------------------------------------------------

    private fun activate() {
        if (zone == 0) {
            when (leftSel) {
                in 0..6 -> toggle(leftSel)
                7 -> Profiles.apply(sys, (Profiles.current(sys) + 1) % Profiles.names.size)
                9 -> { extras = !extras; scroll = 0f }
                else -> {}
            }
            return
        }
        val all = rows()
        if (rightSel == 0) {
            for (n in normal()) if (n.clearable) Hud.Notes.cancel(sys, n)
            notesAt = 0
        } else all.getOrNull(rightSel - 1)?.let {
            Hud.Notes.open(sys, it)
            dismiss()
        }
    }

    fun key(code: Int, ev: KeyEvent): Boolean {
        if (ev.action != KeyEvent.ACTION_DOWN) return true
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> if (zone == 0) {
                leftSel = when (leftSel) {
                    9 -> 0
                    in 4..6 -> 9
                    7 -> 4
                    8 -> 7
                    else -> leftSel
                }
            } else if (rightSel > 0) rightSel-- else { zone = 0; leftSel = 9 }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (zone == 0) {
                when (leftSel) {
                    in 0..3 -> leftSel = 9
                    9 -> if (extras) leftSel = 4 else { zone = 1; rightSel = if (normal().isEmpty()) min(1, rows().size) else 0 }
                    in 4..6 -> leftSel = 7
                    7 -> leftSel = 8
                    else -> {}
                }
            } else rightSel = min(rows().size, rightSel + 1)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (zone == 0) {
                when (leftSel) {
                    8 -> setBrightness(brightness() - 16)
                    in 1..3, in 5..6 -> leftSel--
                    else -> {}
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (zone == 0) {
                when (leftSel) {
                    8 -> setBrightness(brightness() + 16)
                    in 0..2, in 4..5 -> leftSel++
                    else -> {}
                }
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> zone = 0
            KeyEvent.KEYCODE_BUTTON_R1 -> zone = 1
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> activate()
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_DEL -> if (zone == 1) {
                val n = rows().getOrNull(rightSel - 1)
                if (n != null && !n.ongoing) Hud.Notes.cancel(sys, n)
                notesAt = 0
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_SELECT -> {
                dismiss()
                return true
            }
            else -> return false
        }
        invalidate()
        return true
    }

    private fun setBrightnessFromX(x: Float) {
        val f = ((x - barRect.left) / barRect.width()).coerceIn(0f, 1f)
        setBrightness((8 + f * 247).toInt())
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val sc = ui?.sc ?: 1f
        val panelH = height.toFloat()
        val off = -(1f - slide) * panelH
        val ex = e.x
        val ey = e.y - off
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragY = e.y
                downY = e.y
                downOnHandle = ey > panelH - 52f * sc
                dragMoved = false
                dragBright = brightRect.contains(ex, ey)
                if (dragBright) { zone = 0; leftSel = 8; setBrightnessFromX(ex) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragBright) { setBrightnessFromX(ex); invalidate(); return true }
                val d = dragY - e.y
                if (Math.abs(d) > 10f * sc) dragMoved = true
                if (dragMoved && !extras && downY > listTopPx + off) {
                    scroll += d
                    dragY = e.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragBright) { dragBright = false; return true }
                if (dragMoved) {
                    // a swipe up that starts on the pull handle closes the menu (dragY is reset on every move, so use the start)
                    if (downOnHandle && downY - e.y > 40f * sc) dismiss()
                    return true
                }
                if (ey > panelH - 26f * sc) { dismiss(); return true }
                if (ey > panelH - 26f * sc) return true
                for (i in 0 until 7) if (!tileRects[i].isEmpty && tileRects[i].contains(ex, ey)) { zone = 0; leftSel = i; toggle(i); invalidate(); return true }
                if (moreRect.contains(ex, ey)) { zone = 0; leftSel = 9; activate(); invalidate(); return true }
                // the list scrolls under the More row and the handle: a tap there must not hit a row that is only partly visible
                if (extras || ey < listTopPx) {
                    if (!profileRect.isEmpty && profileRect.contains(ex, ey)) { zone = 0; leftSel = 7; activate(); invalidate() }
                    return true
                }
                if (profileRect.contains(ex, ey)) { zone = 0; leftSel = 7; activate(); invalidate(); return true }
                if (!clearRect.isEmpty && clearRect.contains(ex, ey + 0f)) { zone = 1; rightSel = 0; activate(); invalidate(); return true }
                for ((i, rc) in rowRects) if (rc.contains(ex, e.y)) { zone = 1; rightSel = i; activate(); invalidate(); return true }
            }
        }
        return true
    }
}
