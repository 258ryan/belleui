package com.belleui.vector

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import com.belleui.kit.Belle
import com.belleui.kit.Ic
import com.belleui.kit.Profiles
import com.belleui.kit.Row
import com.belleui.kit.Ui
import kotlin.math.min

/** Everything here runs inside the system UI process, drawn with the Belle kit from this module's own context. */
object Hud {
    private val main = Handler(Looper.getMainLooper())

    private fun ui(mod: Context, w: Int, h: Int): Ui {
        val ui = Ui(mod)
        ui.sc = min(w / 640f, h / 480f)
        return ui
    }

    // ------------------------------------------------------------------------------------------
    // volume
    // ------------------------------------------------------------------------------------------

    private var volumeView: VolumeView? = null
    private val hideVolume = Runnable { removeVolume() }

    private class VolumeView(val sys: Context, val mod: Context) : View(sys) {
        var stream = AudioManager.STREAM_MUSIC
        private val am = sys.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private var ui: Ui? = null
        private val box = RectF()

        override fun onDraw(c: Canvas) {
            val u = ui ?: ui(mod, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).also { ui = it }
            val sc = u.sc
            val w = width.toFloat()
            val h = height.toFloat()
            box.set(0f, 0f, w, h)
            u.nine(c, "qtg_fr_popup", box, 40, 20f)
            val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
            val frac = am.getStreamVolume(stream).toFloat() / max
            val label = when (stream) {
                AudioManager.STREAM_RING -> "Ringtone"
                AudioManager.STREAM_ALARM -> "Alarm"
                AudioManager.STREAM_NOTIFICATION -> "Notifications"
                AudioManager.STREAM_VOICE_CALL -> "Call"
                AudioManager.STREAM_SYSTEM -> "System"
                else -> "Media"
            }
            u.text(c, label, 20f * sc, 28f * sc, 18f * sc, Color.WHITE, face = u.light)
            u.glyph(c, if (frac <= 0f) Ic.AUDIO_OFF else Ic.AUDIO_ON, 40f * sc, h - 26f * sc, 34f * sc)
            u.progressBar(c, RectF(68f * sc, h - 36f * sc, w - 22f * sc, h - 16f * sc), frac)
        }
    }

    fun showVolume(sys: Context, mod: Context, stream: Int) {
        val wm = sys.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = volumeView ?: VolumeView(sys, mod).also {
            val sc = min(sys.resources.displayMetrics.widthPixels / 640f, sys.resources.displayMetrics.heightPixels / 480f)
            val lp = WindowManager.LayoutParams(
                (330 * sc).toInt(), (84 * sc).toInt(), 2020 /* TYPE_VOLUME_OVERLAY */,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = (14 * sc).toInt()
            lp.title = "BelleVolume"
            wm.addView(it, lp)
            volumeView = it
        }
        v.stream = stream
        v.invalidate()
        main.removeCallbacks(hideVolume)
        main.postDelayed(hideVolume, 1600)
        // follow the level while it is on screen
        main.postDelayed(object : Runnable {
            override fun run() {
                if (volumeView != null) {
                    volumeView?.invalidate()
                    main.postDelayed(this, 90)
                }
            }
        }, 90)
    }

    private fun removeVolume() {
        val v = volumeView ?: return
        try {
            (v.sys.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Throwable) {
        }
        volumeView = null
    }

    // ------------------------------------------------------------------------------------------
    // a Belle list dialog (the power menu)
    // ------------------------------------------------------------------------------------------

    class Entry(val title: String, val subtitle: String? = null, val action: () -> Unit)

    private class ListView(val sys: Context, val mod: Context, val title: String, val entries: List<Entry>, val close: () -> Unit) : View(sys) {
        var sel = 0
        private var ui: Ui? = null
        private val box = RectF()
        private val row = RectF()

        override fun onDraw(c: Canvas) {
            val u = ui ?: ui(mod, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).also { ui = it }
            val sc = u.sc
            val rows = entries.size
            val rowH = Belle.MENU_ITEM_H * sc
            val w = min(width * 0.8f, 420f * sc)
            val h = 54f * sc + rows * rowH + 10f * sc
            box.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
            u.nine(c, "qtg_fr_popup", box, 40, 20f)
            u.text(c, title, box.left + 20f * sc, box.top + 36f * sc, 22f * sc, Belle.BLUE_LIGHT, face = u.medium)
            for ((i, e) in entries.withIndex()) {
                row.set(box.left + 8f * sc, box.top + 48f * sc + i * rowH, box.right - 8f * sc, box.top + 48f * sc + (i + 1) * rowH)
                u.listRow(c, row, i == sel, Row(e.title, e.subtitle))
            }
        }

        fun key(code: Int, ev: KeyEvent): Boolean {
            if (ev.action != KeyEvent.ACTION_DOWN) return true
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> sel = (sel - 1 + entries.size) % entries.size
                KeyEvent.KEYCODE_DPAD_DOWN -> sel = (sel + 1) % entries.size
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                    close()
                    entries[sel].action()
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_POWER -> {
                    close()
                    return true
                }
                else -> return false
            }
            invalidate()
            return true
        }

        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            if (e.action != android.view.MotionEvent.ACTION_DOWN) return true
            if (!box.contains(e.x, e.y)) {
                close()
                return true
            }
            val i = ((e.y - box.top - 48f * (ui?.sc ?: 1f)) / (Belle.MENU_ITEM_H * (ui?.sc ?: 1f))).toInt()
            if (i in entries.indices) {
                close()
                entries[i].action()
            }
            return true
        }
    }

    private var dialog: Dialog? = null

    private fun fullDialog(sys: Context, view: View, keys: (Int, KeyEvent) -> Boolean): Dialog {
        val d = Dialog(sys)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(view)
        d.window?.apply {
            setType(2009 /* TYPE_KEYGUARD_DIALOG: above everything, as the stock power menu is */)
            setBackgroundDrawable(ColorDrawable(0xB0000000.toInt()))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        d.setOnKeyListener { _, code, ev -> keys(code, ev) }
        d.setOnDismissListener { if (dialog === d) dialog = null }
        return d
    }

    fun showList(sys: Context, mod: Context, title: String, entries: List<Entry>) {
        dialog?.dismiss()
        lateinit var d: Dialog
        val v = ListView(sys, mod, title, entries) { d.dismiss() }
        d = fullDialog(sys, v) { code, ev -> v.key(code, ev) }
        dialog = d
        d.show()
        v.requestFocus()
    }

    /** The Belle power menu. */
    fun showPower(sys: Context, mod: Context) {
        val pm = sys.getSystemService(Context.POWER_SERVICE) as PowerManager
        fun sleepNow() {
            try {
                PowerManager::class.java.getMethod("goToSleep", Long::class.javaPrimitiveType).invoke(pm, android.os.SystemClock.uptimeMillis())
            } catch (_: Throwable) {
            }
        }
        val entries = listOf(
            Entry("Power off") {
                sys.startActivity(Intent("com.android.internal.intent.action.REQUEST_SHUTDOWN").putExtra("android.intent.extra.KEY_CONFIRM", false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            Entry("Restart") { pm.reboot(null) },
            Entry("Restart to recovery") { pm.reboot("recovery") },
            Entry("Screen off") { sleepNow() },
            Entry("Profile: " + Profiles.names[Profiles.current(sys)], "Tap to switch") {
                Profiles.apply(sys, (Profiles.current(sys) + 1) % Profiles.names.size)
            },
            Entry("Cancel") { },
        )
        showList(sys, mod, "Phone", entries)
    }

    // ------------------------------------------------------------------------------------------
    // the Status menu: the Belle pull-down (quick switches, notifications, activities)
    // ------------------------------------------------------------------------------------------

    /** What is currently notifying, read from the system's notification service (the system UI is allowed to). */
    private object Notes {
        class Item(val sbn: android.service.notification.StatusBarNotification, val title: String, val text: String?, val ongoing: Boolean, val progress: Float?)

        private fun service(): Any? = try {
            android.app.NotificationManager::class.java.getMethod("getService").invoke(null)
        } catch (_: Throwable) {
            null
        }

        @Suppress("UNCHECKED_CAST")
        fun active(sys: Context): List<Item> {
            val nm = service() ?: return emptyList()
            val arr = try {
                nm.javaClass.getMethod("getActiveNotifications", String::class.java).invoke(nm, sys.packageName) as? Array<android.service.notification.StatusBarNotification>
            } catch (_: Throwable) {
                null
            } ?: return emptyList()
            val out = ArrayList<Item>()
            for (n in arr) {
                try {
                    val no = n.notification
                    if (no.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) continue
                    val ex = no.extras
                    val title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
                    val text = ex.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
                    if (title.isBlank() && text.isNullOrBlank()) continue
                    val max = ex.getInt(android.app.Notification.EXTRA_PROGRESS_MAX, 0)
                    val prog = if (max > 0) ex.getInt(android.app.Notification.EXTRA_PROGRESS, 0).toFloat() / max else null
                    val ongoing = no.flags and (android.app.Notification.FLAG_ONGOING_EVENT or android.app.Notification.FLAG_FOREGROUND_SERVICE) != 0
                    out.add(Item(n, title.ifBlank { appName(sys, n.packageName) }, text, ongoing, prog))
                } catch (_: Throwable) {
                }
            }
            return out.sortedByDescending { it.sbn.postTime }
        }

        fun appName(sys: Context, pkg: String): String = try {
            sys.packageManager.getApplicationLabel(sys.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) {
            pkg
        }

        fun cancel(sys: Context, n: android.service.notification.StatusBarNotification) {
            try {
                val nm = service() ?: return
                nm.javaClass.getMethod("cancelNotificationWithTag", String::class.java, String::class.java, String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(nm, n.packageName, (try { n.javaClass.getMethod("getOpPkg").invoke(n) as String } catch (_: Throwable) { n.packageName }), n.tag, n.id, n.userId)
            } catch (_: Throwable) {
            }
        }
    }

    private class PullDown(val sys: Context, val mod: Context, val close: () -> Unit) : View(sys) {
        private var ui: Ui? = null
        private val am = sys.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private val handler = Handler(Looper.getMainLooper())
        private val hasCell = sys.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)
        private var notes: List<Notes.Item> = emptyList()
        private var notesAt = 0L
        private val icons = HashMap<String, android.graphics.Bitmap?>()
        private var slide = 0f
        private var scroll = 0f
        private var sel = 0
        private var sw = 1 // which quick switch has the focus (starts on Wi-Fi)
        private val hits = ArrayList<Pair<Int, RectF>>() // focus index -> rect, for touch and scrolling
        private val r = RectF()

        // focus order: 0 switches, 1 profile, 2 media, 3 ringtone, 4 brightness, 5 clear, 6.. notifications, then activities, then footer
        private fun normal() = notes.filter { !it.ongoing }
        private fun acts() = notes.filter { it.ongoing }
        private fun focusCount() = 6 + normal().size + acts().size + 2

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
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180
                addUpdateListener {
                    slide = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            handler.removeCallbacks(tick)
        }

        private fun refreshNotes() {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - notesAt < 700) return
            notesAt = now
            notes = Notes.active(sys)
            sel = sel.coerceIn(0, focusCount() - 1)
        }

        private fun icon(pkg: String): android.graphics.Bitmap? = icons.getOrPut(pkg) {
            try {
                val d = sys.packageManager.getApplicationIcon(pkg)
                val b = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, 64, 64)
                d.draw(Canvas(b))
                b
            } catch (_: Throwable) {
                null
            }
        }

        private fun wifi(): Boolean = try { (sys.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled } catch (_: Throwable) { false }

        private fun bt(): Boolean = try {
            @Suppress("DEPRECATION")
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        } catch (_: Throwable) {
            false
        }

        private fun airplane(): Boolean = try { Settings.Global.getInt(sys.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0 } catch (_: Throwable) { false }

        private fun dataOn(): Boolean = try {
            val tm = sys.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            tm.javaClass.getMethod("isDataEnabled").invoke(tm) as Boolean
        } catch (_: Throwable) {
            false
        }

        private fun soundOn(): Boolean = am.ringerMode == AudioManager.RINGER_MODE_NORMAL

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
                }
            } catch (_: Throwable) {
            }
        }

        private fun volume(stream: Int, d: Int) {
            try {
                am.setStreamVolume(stream, (am.getStreamVolume(stream) + d).coerceIn(0, am.getStreamMaxVolume(stream)), 0)
            } catch (_: Throwable) {
            }
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
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val chg = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            return "${level * 100 / scale}%" + if (chg) " charging" else ""
        }

        // ---- drawing ----

        private fun glyph(c: Canvas, u: Ui, kind: Int, cx: Float, cy: Float, s: Float, on: Boolean) {
            val p = u.p
            p.color = if (on) Color.WHITE else 0xFFB0B0B0.toInt()
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.6f * u.sc
            when (kind) {
                0 -> if (hasCell) { // data: two arrows
                    c.drawLine(cx - s * 0.35f, cy + s * 0.5f, cx - s * 0.35f, cy - s * 0.5f, p)
                    c.drawLine(cx - s * 0.35f, cy - s * 0.5f, cx - s * 0.65f, cy - s * 0.15f, p)
                    c.drawLine(cx - s * 0.35f, cy - s * 0.5f, cx - s * 0.05f, cy - s * 0.15f, p)
                    c.drawLine(cx + s * 0.35f, cy - s * 0.5f, cx + s * 0.35f, cy + s * 0.5f, p)
                    c.drawLine(cx + s * 0.35f, cy + s * 0.5f, cx + s * 0.05f, cy + s * 0.15f, p)
                    c.drawLine(cx + s * 0.35f, cy + s * 0.5f, cx + s * 0.65f, cy + s * 0.15f, p)
                } else { // airplane
                    c.drawLine(cx - s * 0.6f, cy, cx + s * 0.6f, cy, p)
                    c.drawLine(cx - s * 0.1f, cy, cx - s * 0.45f, cy - s * 0.55f, p)
                    c.drawLine(cx - s * 0.1f, cy, cx - s * 0.45f, cy + s * 0.55f, p)
                    c.drawLine(cx + s * 0.45f, cy, cx + s * 0.6f, cy - s * 0.3f, p)
                    c.drawLine(cx + s * 0.45f, cy, cx + s * 0.6f, cy + s * 0.3f, p)
                }
                1 -> { // wi-fi
                    for (k in 1..3) c.drawArc(RectF(cx - k * s * 0.3f, cy + s * 0.35f - k * s * 0.3f, cx + k * s * 0.3f, cy + s * 0.35f + k * s * 0.3f), -135f, 90f, false, p)
                    p.style = Paint.Style.FILL
                    c.drawCircle(cx, cy + s * 0.38f, 2.6f * u.sc, p)
                }
                2 -> { // bluetooth rune
                    c.drawLine(cx, cy - s * 0.7f, cx, cy + s * 0.7f, p)
                    c.drawLine(cx, cy - s * 0.7f, cx + s * 0.4f, cy - s * 0.3f, p)
                    c.drawLine(cx + s * 0.4f, cy - s * 0.3f, cx - s * 0.4f, cy + s * 0.35f, p)
                    c.drawLine(cx, cy + s * 0.7f, cx + s * 0.4f, cy + s * 0.3f, p)
                    c.drawLine(cx + s * 0.4f, cy + s * 0.3f, cx - s * 0.4f, cy - s * 0.35f, p)
                }
                else -> { // sound / silence
                    p.style = Paint.Style.FILL
                    val path = android.graphics.Path()
                    path.moveTo(cx - s * 0.55f, cy - s * 0.2f); path.lineTo(cx - s * 0.2f, cy - s * 0.2f); path.lineTo(cx + s * 0.15f, cy - s * 0.55f)
                    path.lineTo(cx + s * 0.15f, cy + s * 0.55f); path.lineTo(cx - s * 0.2f, cy + s * 0.2f); path.lineTo(cx - s * 0.55f, cy + s * 0.2f); path.close()
                    c.drawPath(path, p)
                    p.style = Paint.Style.STROKE
                    if (on) c.drawArc(RectF(cx + s * 0.05f, cy - s * 0.4f, cx + s * 0.65f, cy + s * 0.4f), -50f, 100f, false, p)
                    else c.drawLine(cx + s * 0.25f, cy - s * 0.45f, cx + s * 0.6f, cy + s * 0.45f, p)
                }
            }
            p.style = Paint.Style.FILL
        }

        override fun onDraw(c: Canvas) {
            val u = ui ?: ui(mod, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).also { ui = it }
            val sc = u.sc
            val w = width.toFloat()
            val panelH = min(height * 0.94f, 456f * sc)
            val off = -(1f - slide) * panelH
            hits.clear()
            c.save()
            c.translate(0f, off)
            c.clipRect(0f, 0f, w, panelH)
            // panel
            u.p.style = Paint.Style.FILL
            u.p.shader = null
            u.p.color = 0xFF262626.toInt()
            c.drawRect(0f, 0f, w, panelH, u.p)

            // quick switches (fixed at the top)
            val barH = 92f * sc
            u.p.color = 0xFF111111.toInt()
            c.drawRect(0f, 0f, w, barH, u.p)
            val on = booleanArrayOf(if (hasCell) dataOn() else airplane(), wifi(), bt(), soundOn())
            val names = arrayOf(if (hasCell) "Data" else "Airplane", "Wi-Fi", "Bluetooth", "Silence")
            val cell = w / 4f
            for (i in 0..3) {
                val cx = cell * i + cell / 2f
                val cy = 36f * sc
                val rad = 27f * sc
                val focused = sel == 0 && sw == i
                u.p.shader = android.graphics.LinearGradient(0f, cy - rad, 0f, cy + rad, if (on[i]) Belle.BLUE_LIGHT else 0xFF5A5A5A.toInt(), if (on[i]) Belle.BLUE else 0xFF2E2E2E.toInt(), android.graphics.Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, rad, u.p)
                u.p.shader = null
                if (focused) {
                    u.p.style = Paint.Style.STROKE
                    u.p.strokeWidth = 3f * sc
                    u.p.color = Color.WHITE
                    c.drawCircle(cx, cy, rad + 4f * sc, u.p)
                    u.p.style = Paint.Style.FILL
                }
                glyph(c, u, if (i == 3) 3 else i, cx, cy, 17f * sc, if (i == 3) on[3] else true)
                u.text(c, names[i], cx, 80f * sc, 14f * sc, if (focused) Color.WHITE else Belle.TEXT_MID, Paint.Align.CENTER, face = u.light)
            }
            hits.add(0 to RectF(0f, 0f, w, barH))

            // everything below scrolls
            c.save()
            c.clipRect(0f, barH, w, panelH - 20f * sc)
            var y = barH - scroll
            var selTop = barH
            var selBottom = barH + 40f * sc
            fun mark(index: Int, top: Float, bottom: Float) {
                hits.add(index to RectF(0f, top, w, bottom))
                if (sel == index) {
                    selTop = top + scroll
                    selBottom = bottom + scroll
                }
            }
            fun header(title: String, button: String?, index: Int) {
                val h = 38f * sc
                u.p.shader = android.graphics.LinearGradient(0f, y, 0f, y + h, 0xFF454545.toInt(), 0xFF333333.toInt(), android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(0f, y, w, y + h, u.p)
                u.p.shader = null
                u.text(c, title, 16f * sc, y + 26f * sc, 19f * sc, Color.WHITE, face = u.medium)
                if (button != null) {
                    r.set(w - 100f * sc, y + 5f * sc, w - 12f * sc, y + h - 5f * sc)
                    u.pushButton(c, r, button, sel == index)
                    mark(index, y, y + h)
                }
                y += h
            }
            fun slider(label: String, frac: Float, index: Int) {
                val h = 40f * sc
                val focus = sel == index
                if (focus) {
                    u.p.color = 0x33FFFFFF
                    c.drawRect(0f, y, w, y + h, u.p)
                }
                u.text(c, label, 16f * sc, y + 26f * sc, 17f * sc, if (focus) Color.WHITE else Belle.TEXT_MID, face = u.light)
                u.progressBar(c, RectF(130f * sc, y + 10f * sc, w - 20f * sc, y + 30f * sc), frac)
                mark(index, y, y + h)
                y += h
            }
            // profile row
            run {
                val h = 44f * sc
                val focus = sel == 1
                if (focus) {
                    u.p.color = 0x33FFFFFF
                    c.drawRect(0f, y, w, y + h, u.p)
                }
                u.text(c, "Profile", 16f * sc, y + 28f * sc, 17f * sc, if (focus) Color.WHITE else Belle.TEXT_MID, face = u.light)
                u.text(c, Profiles.names[Profiles.current(sys)], 130f * sc, y + 29f * sc, 21f * sc, Color.WHITE)
                u.text(c, "A: change", w - 16f * sc, y + 28f * sc, 15f * sc, Belle.TEXT_MID, Paint.Align.RIGHT, face = u.light)
                mark(1, y, y + h)
                y += h
            }
            slider("Media", am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1), 2)
            slider("Ringtone", am.getStreamVolume(AudioManager.STREAM_RING).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_RING).coerceAtLeast(1), 3)
            slider("Brightness", brightness() / 255f, 4)

            val normal = normal()
            header("Notifications", if (normal.isNotEmpty()) "Clear" else null, 5)
            if (normal.isEmpty()) {
                u.text(c, "No notifications", 16f * sc, y + 28f * sc, 17f * sc, Belle.TEXT_MID, face = u.light)
                y += 44f * sc
            }
            var idx = 6
            fun noteRow(n: Notes.Item, index: Int) {
                val h = 56f * sc
                val focus = sel == index
                if (focus) {
                    u.p.shader = android.graphics.LinearGradient(0f, y, 0f, y + h, Belle.BLUE_LIGHT, Belle.BLUE, android.graphics.Shader.TileMode.CLAMP)
                    c.drawRect(0f, y, w, y + h, u.p)
                    u.p.shader = null
                } else {
                    u.p.color = 0xFF444444.toInt()
                    c.drawRect(0f, y + h - 1f, w, y + h, u.p)
                }
                val ic = icon(n.sbn.packageName)
                rf.set(34f * sc, y + 9f * sc, 34f * sc + 38f * sc, y + 9f * sc + 38f * sc)
                if (ic != null) c.drawBitmap(ic, null, rf, null)
                if (!n.ongoing && android.os.SystemClock.uptimeMillis() > 0 && System.currentTimeMillis() - n.sbn.postTime < 10 * 60_000L) {
                    u.p.color = if (focus) Color.WHITE else Color.WHITE
                    c.drawCircle(20f * sc, y + 28f * sc, 5f * sc, u.p)
                }
                val tx = 84f * sc
                u.text(c, n.title, tx, y + 24f * sc, 18f * sc, Color.WHITE, maxW = w - tx - 14f * sc, face = u.medium)
                if (n.progress != null) {
                    u.progressBar(c, RectF(tx, y + 32f * sc, w - 16f * sc, y + 48f * sc), n.progress)
                } else if (!n.text.isNullOrBlank()) {
                    u.text(c, n.text, tx, y + 46f * sc, 15f * sc, if (focus) Color.WHITE else Belle.TEXT_MID, maxW = w - tx - 14f * sc, face = u.light)
                }
                mark(index, y, y + h)
                y += h
            }
            for (n in normal) noteRow(n, idx++)
            val acts = acts()
            if (acts.isNotEmpty()) {
                header("Activities", null, -1)
                for (n in acts) noteRow(n, idx++)
            }
            // footer buttons
            run {
                val h = 54f * sc
                val bw = (w - 36f * sc) / 2f
                r.set(12f * sc, y + 8f * sc, 12f * sc + bw, y + h - 6f * sc)
                u.pushButton(c, r, "Hub", sel == idx)
                mark(idx, y, y + h)
                val hubIdx = idx
                idx++
                r.set(24f * sc + bw, y + 8f * sc, 24f * sc + 2 * bw, y + h - 6f * sc)
                u.pushButton(c, r, "Settings", sel == idx)
                hits.add(idx to RectF(r))
                if (sel == idx) {
                    selTop = y + scroll
                    selBottom = y + h + scroll
                }
                if (sel == hubIdx) hits.removeAll { false }
                y += h
            }
            c.restore()
            // keep the focused row on screen
            val view = panelH - barH - 20f * sc
            if (selBottom - scroll > barH + view) scroll = selBottom - barH - view
            if (selTop - scroll < barH) scroll = selTop - barH
            scroll = scroll.coerceAtLeast(0f)

            // the pull handle: a hatched strip, like the guideline
            u.p.color = 0xFF1A1A1A.toInt()
            c.drawRect(0f, panelH - 20f * sc, w, panelH, u.p)
            u.p.color = 0x55FFFFFF
            u.p.strokeWidth = 1f * sc
            var x = -20f * sc
            while (x < w) {
                c.drawLine(x, panelH, x + 18f * sc, panelH - 20f * sc, u.p)
                x += 8f * sc
            }
            c.restore()
        }

        private val rf = RectF()

        // ---- input ----

        private fun activate() {
            val normal = normal()
            val acts = acts()
            when {
                sel == 0 -> toggle(sw)
                sel == 1 -> Profiles.apply(sys, (Profiles.current(sys) + 1) % Profiles.names.size)
                sel == 5 -> {
                    for (n in normal) if (n.sbn.notification.flags and android.app.Notification.FLAG_NO_CLEAR == 0) Notes.cancel(sys, n.sbn)
                    notesAt = 0
                }
                sel in 6 until 6 + normal.size + acts.size -> {
                    val n = (normal + acts)[sel - 6]
                    try {
                        n.sbn.notification.contentIntent?.send()
                    } catch (_: Throwable) {
                    }
                    if (n.sbn.notification.flags and android.app.Notification.FLAG_AUTO_CANCEL != 0) Notes.cancel(sys, n.sbn)
                    close()
                }
                sel == 6 + normal.size + acts.size -> {
                    close()
                    launch("com.belleui.launcher", "belle_hub")
                }
                else -> {
                    close()
                    launch("com.belleui.settings")
                }
            }
        }

        private fun launch(pkg: String, extra: String? = null) {
            try {
                val i = sys.packageManager.getLaunchIntentForPackage(pkg) ?: return
                if (extra != null) i.putExtra(extra, true)
                sys.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
            }
        }

        fun key(code: Int, ev: KeyEvent): Boolean {
            if (ev.action != KeyEvent.ACTION_DOWN) return true
            val last = focusCount() - 1
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> sel = (sel - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_DPAD_DOWN -> sel = (sel + 1).coerceAtMost(last)
                KeyEvent.KEYCODE_DPAD_LEFT -> when (sel) {
                    0 -> sw = (sw + 3) % 4
                    2 -> volume(AudioManager.STREAM_MUSIC, -1)
                    3 -> volume(AudioManager.STREAM_RING, -1)
                    4 -> setBrightness(brightness() - 16)
                    else -> if (sel == last) sel = last - 1
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> when (sel) {
                    0 -> sw = (sw + 1) % 4
                    2 -> volume(AudioManager.STREAM_MUSIC, 1)
                    3 -> volume(AudioManager.STREAM_RING, 1)
                    4 -> setBrightness(brightness() + 16)
                    else -> if (sel == last - 1) sel = last
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> activate()
                KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_DEL -> {
                    // dismiss the highlighted notification
                    val all = normal() + acts()
                    if (sel in 6 until 6 + all.size) {
                        val n = all[sel - 6]
                        if (!n.ongoing) Notes.cancel(sys, n.sbn)
                        notesAt = 0
                    }
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_SELECT -> {
                    close()
                    return true
                }
                else -> return false
            }
            invalidate()
            return true
        }

        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            if (e.action != android.view.MotionEvent.ACTION_DOWN) return true
            val panelH = min(height * 0.94f, 456f * (ui?.sc ?: 1f))
            if (e.y > panelH) {
                close()
                return true
            }
            for ((i, rect) in hits.reversed()) if (rect.contains(e.x, e.y)) {
                if (i == 0) {
                    sw = (e.x / (width / 4f)).toInt().coerceIn(0, 3)
                }
                sel = i
                activate()
                invalidate()
                return true
            }
            return true
        }
    }

    /** Opens the Status menu, or closes it if it is already open. */
    fun toggleStatus(sys: Context, mod: Context) {
        if (dialog != null) {
            dialog?.dismiss()
            return
        }
        lateinit var d: Dialog
        val v = PullDown(sys, mod) { d.dismiss() }
        d = fullDialog(sys, v) { code, ev -> v.key(code, ev) }
        dialog = d
        d.show()
        v.requestFocus()
    }
}
