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
            // the Belle volume ramp: the grey ramp, with the blue one drawn over it up to the current level
            val ramp = RectF(68f * sc, 34f * sc, w - 22f * sc, h - 10f * sc)
            if (u.gfx.draw(c, "qgn_graf_volume_set_off", ramp)) {
                c.save()
                c.clipRect(ramp.left, ramp.top, ramp.left + ramp.width() * frac, ramp.bottom)
                u.gfx.draw(c, "qgn_graf_volume_set_on", ramp)
                c.restore()
            } else {
                u.progressBar(c, RectF(68f * sc, h - 36f * sc, w - 22f * sc, h - 16f * sc), frac)
            }
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
        ).let { base ->
            // the real System UI adds a way to the full Android power menu (emergency, screenshot, lockdown and the rest)
            val stock = stockPower
            if (stock == null) base else base.dropLast(1) + Entry("More options", "The full Android power menu") { stock() } + base.last()
        }
        showList(sys, mod, "Phone", entries)
    }

    // ------------------------------------------------------------------------------------------
    // the Status menu: the Belle pull-down (quick switches, notifications, activities)
    // ------------------------------------------------------------------------------------------

    /** What is currently notifying, from the Belle launcher's status provider (its listener is the one Android lets read them). */
    private object Notes {
        class Item(val key: String, val pkg: String, val title: String, val text: String?, val ongoing: Boolean, val progress: Float?, val clearable: Boolean, val postTime: Long)

        private val uri = android.net.Uri.parse("content://com.belleui.launcher.status/active")

        /** The real System UI's own notification listener: every notification, with no other app needed. */
        private fun listener(): android.service.notification.NotificationListenerService? = try {
            val dep = Class.forName("com.android.systemui.Dependency")
            val cls = Class.forName("com.android.systemui.statusbar.NotificationListener")
            dep.getMethod("get", Class::class.java).invoke(null, cls) as? android.service.notification.NotificationListenerService
        } catch (_: Throwable) {
            null
        }

        private fun direct(): Array<android.service.notification.StatusBarNotification>? = try {
            listener()?.activeNotifications
        } catch (_: Throwable) {
            null
        }

        private fun fromSbns(sys: Context, list: Array<android.service.notification.StatusBarNotification>): List<Item> {
            val out = ArrayList<Item>()
            for (sbn in list) {
                try {
                    val n = sbn.notification ?: continue
                    if (n.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) continue
                    val x = n.extras
                    val title = x.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
                    val text = x.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
                    if (title.isBlank() && text.isBlank()) continue
                    val max = x.getInt(android.app.Notification.EXTRA_PROGRESS_MAX, 0)
                    val prog = if (max > 0) x.getInt(android.app.Notification.EXTRA_PROGRESS, 0).toFloat() / max else null
                    val ongoing = n.flags and (android.app.Notification.FLAG_ONGOING_EVENT or android.app.Notification.FLAG_FOREGROUND_SERVICE) != 0
                    out.add(Item(sbn.key, sbn.packageName, title.ifBlank { appName(sys, sbn.packageName) }, text.ifBlank { null }, ongoing, prog, n.flags and android.app.Notification.FLAG_NO_CLEAR == 0, sbn.postTime))
                } catch (_: Throwable) {
                }
            }
            return out
        }

        fun active(sys: Context): List<Item> {
            direct()?.let { return fromSbns(sys, it).sortedByDescending { it.postTime } }
            val out = ArrayList<Item>()
            try {
                sys.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val ix = { n: String -> c.getColumnIndex(n) }
                    while (c.moveToNext()) {
                        val pkg = c.getString(ix("pkg"))
                        val title = c.getString(ix("title")).ifBlank { appName(sys, pkg) }
                        val prog = c.getFloat(ix("progress"))
                        out.add(Item(c.getString(ix("key")), pkg, title, c.getString(ix("text")).ifBlank { null }, c.getInt(ix("ongoing")) != 0, if (prog >= 0f) prog else null, c.getInt(ix("clearable")) != 0, c.getLong(ix("post"))))
                    }
                }
            } catch (_: Throwable) {
            }
            return out.sortedByDescending { it.postTime }
        }

        /** Why the list might be empty: null when everything works. */
        fun problem(sys: Context): String? {
            if (direct() != null) return null
            val b = try {
                sys.contentResolver.call(android.net.Uri.parse("content://com.belleui.launcher.status"), "status", null, null)
            } catch (_: Throwable) {
                null
            } ?: return "The Belle launcher is missing or too old"
            if (!b.getBoolean("allowed")) return "Not allowed to read them (uid ${b.getInt("uid")})"
            if (!b.getBoolean("connected")) return "Allow notification access for the Belle launcher"
            return null
        }

        fun appName(sys: Context, pkg: String): String = try {
            sys.packageManager.getApplicationLabel(sys.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) {
            pkg
        }

        private fun call(sys: Context, method: String, arg: String?) = try {
            sys.contentResolver.call(android.net.Uri.parse("content://com.belleui.launcher.status"), method, arg, null)
        } catch (_: Throwable) {
            null
        }

        fun cancel(sys: Context, n: Item) {
            val l = listener()
            if (l != null) {
                try {
                    l.cancelNotification(n.key)
                    return
                } catch (_: Throwable) {
                }
            }
            call(sys, "cancel", n.key)
        }

        fun clear(sys: Context) {
            call(sys, "clear", null)
        }

        /** Runs the notification's own action from here (the System UI may start apps from the background). */
        fun open(sys: Context, n: Item) {
            try {
                val own = direct()?.firstOrNull { it.key == n.key }
                val pi = own?.notification?.contentIntent ?: call(sys, "open", n.key)?.getParcelable<android.app.PendingIntent>("intent")
                pi?.send()
            } catch (_: Throwable) {
            }
        }
    }

    /** Set by the real System UI: opens its own full power menu. */
    @Volatile
    var stockPower: (() -> Unit)? = null

    /** Set by the real System UI: opens its own full quick settings and notification panel. */
    @Volatile
    var stockShade: (() -> Unit)? = null

    /** How many notifications are showing, for the status bar's mail mark. */
    fun activeCount(sys: Context): Int = Notes.active(sys).count { !it.ongoing }

    private class PullDown(val sys: Context, val mod: Context, val close: () -> Unit) : View(sys) {
        private var ui: Ui? = null
        private val am = sys.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private val handler = Handler(Looper.getMainLooper())
        private val hasCell = sys.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)
        private var notes: List<Notes.Item> = emptyList()
        private var noteProblem: String? = null
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
        private fun focusCount() = 6 + normal().size + acts().size + (if (stockShade != null) 1 else 0)

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
            noteProblem = if (notes.isEmpty()) Notes.problem(sys) else null
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
            val panelH = height.toFloat()
            val off = -(1f - slide) * panelH
            hits.clear()
            c.save()
            c.translate(0f, off)
            c.clipRect(0f, 0f, w, panelH)
            // panel
            u.p.style = Paint.Style.FILL
            u.p.shader = null
            u.p.shader = android.graphics.LinearGradient(0f, 0f, 0f, panelH, 0xFF1E1E1E.toInt(), 0xFF080808.toInt(), android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, panelH, u.p)
            u.p.shader = null

            // the status line: title on the left, battery and clock on the right, as on every Belle screen
            u.p.color = Color.BLACK
            c.drawRect(0f, 0f, w, 28f * sc, u.p)
            u.text(c, "Status", 10f * sc, 21f * sc, 19f * sc, Color.WHITE)
            u.text(c, android.text.format.DateFormat.format(if (android.text.format.DateFormat.is24HourFormat(sys)) "HH:mm" else "h:mm a", System.currentTimeMillis()).toString() + "   " + batteryText().substringBefore(' '), w - 10f * sc, 21f * sc, 17f * sc, Color.WHITE, Paint.Align.RIGHT)

            // quick switches (fixed at the top)
            val barH = 124f * sc
            u.p.color = 0xFF0C0C0C.toInt()
            c.drawRect(0f, 28f * sc, w, barH, u.p)
            // the real Belle panel switches (Belle FP2 theme): lit in blue when on; Silence is lit when the phone is quiet
            val on = booleanArrayOf(if (hasCell) dataOn() else airplane(), wifi(), bt(), !soundOn(), false)
            val names = arrayOf(if (hasCell) "Data" else "Airplane", "Wi-Fi", "Bluetooth", "Silence", "Settings")
            val art = arrayOf("signal", "wlan", "bt", "audio", "signal")
            val tiles = switchCount(u)
            val cell = w / tiles
            for (i in 0 until tiles) {
                val cx = cell * i + cell / 2f
                val focused = sel == 0 && sw == i
                rf.set(cx - 29f * sc, 34f * sc, cx + 29f * sc, 34f * sc + 66f * sc)
                if (i == 4) {
                    // Settings has no picture of its own: a dark rounded tile in the same style, with the Belle gear
                    u.p.shader = android.graphics.LinearGradient(0f, rf.top + 4f * sc, 0f, rf.bottom - 8f * sc, 0xFF3A3A3A.toInt(), 0xFF1C1C1C.toInt(), android.graphics.Shader.TileMode.CLAMP)
                    c.drawRoundRect(rf.left + 3f * sc, rf.top + 5f * sc, rf.right - 3f * sc, rf.bottom - 9f * sc, 14f * sc, 14f * sc, u.p)
                    u.p.shader = null
                } else if (!u.gfx.draw(c, "qtg_graf_panel_switch_${art[i]}_${if (on[i]) "on" else "off"}", rf)) {
                    u.p.color = if (on[i]) Belle.BLUE else 0xFF3A3A3A.toInt()
                    c.drawCircle(cx, 67f * sc, 27f * sc, u.p)
                }
                // the Settings tile has no art of its own: the Belle settings glyph on a dark tile
                if (i == 4) u.gfx.glyph(c, "toolbar_settings", cx, rf.centerY() - 2f * sc, 30f * sc, 255)
                if (focused) {
                    u.p.style = Paint.Style.STROKE
                    u.p.strokeWidth = 3f * sc
                    u.p.color = Color.WHITE
                    c.drawRoundRect(rf.left - 3f * sc, rf.top - 3f * sc, rf.right + 3f * sc, rf.bottom + 3f * sc, 16f * sc, 16f * sc, u.p)
                    u.p.style = Paint.Style.FILL
                }
                u.text(c, names[i], cx, 118f * sc, 15f * sc, Color.WHITE, Paint.Align.CENTER, face = u.light)
            }
            hits.add(0 to RectF(0f, 28f * sc, w, barH))

            // everything below scrolls
            c.save()
            c.clipRect(0f, barH, w, panelH - 24f * sc)
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
                val h = 40f * sc
                u.p.shader = android.graphics.LinearGradient(0f, y, 0f, y + h, 0xFF4A4A4A.toInt(), 0xFF383838.toInt(), android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(0f, y, w, y + h, u.p)
                u.p.shader = null
                u.text(c, title, 14f * sc, y + 28f * sc, 20f * sc, Color.WHITE, face = u.medium)
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
            // activities that always apply: the profile, and the brightness (a list item with a bar)
            run {
                val h = 62f * sc
                val focus = sel == 1
                if (focus) {
                    u.p.shader = android.graphics.LinearGradient(0f, y, 0f, y + h, Belle.BLUE_LIGHT, Belle.BLUE, android.graphics.Shader.TileMode.CLAMP)
                    c.drawRect(0f, y, w, y + h, u.p)
                    u.p.shader = null
                } else {
                    u.p.color = 0xFF3A3A3A.toInt()
                    c.drawRect(0f, y + h - 1f, w, y + h, u.p)
                }
                val pi = Profiles.current(sys)
                rf.set(14f * sc, y + 11f * sc, 14f * sc + 40f * sc, y + 11f * sc + 40f * sc)
                u.gfx.draw(c, if (pi == 1) "qgn_indi_home_profile_silent" else if (pi == 2) "qgn_indi_home_profile_timed" else "qgn_indi_home_profile_general", rf)
                u.text(c, "Profile", 66f * sc, y + 28f * sc, 20f * sc, Color.WHITE, face = u.medium)
                u.text(c, Profiles.names[pi], 66f * sc, y + 52f * sc, 16f * sc, if (focus) Color.WHITE else Belle.TEXT_MID, face = u.light)
                u.text(c, "A: change", w - 14f * sc, y + 38f * sc, 15f * sc, if (focus) Color.WHITE else Belle.TEXT_MID, Paint.Align.RIGHT, face = u.light)
                mark(1, y, y + h)
                y += h
            }
            run {
                val h = 62f * sc
                val focus = sel == 4
                if (focus) {
                    u.p.shader = android.graphics.LinearGradient(0f, y, 0f, y + h, Belle.BLUE_LIGHT, Belle.BLUE, android.graphics.Shader.TileMode.CLAMP)
                    c.drawRect(0f, y, w, y + h, u.p)
                    u.p.shader = null
                } else {
                    u.p.color = 0xFF3A3A3A.toInt()
                    c.drawRect(0f, y + h - 1f, w, y + h, u.p)
                }
                u.text(c, "Brightness", 66f * sc, y + 28f * sc, 20f * sc, Color.WHITE, face = u.medium)
                u.progressBar(c, RectF(66f * sc, y + 38f * sc, w - 20f * sc, y + 54f * sc), brightness() / 255f)
                mark(4, y, y + h)
                y += h
            }

            val normal = normal()
            header("Notifications", if (normal.isNotEmpty()) "Clear" else null, 5)
            if (normal.isEmpty()) {
                u.text(c, noteProblem ?: "No notifications", 66f * sc, y + 30f * sc, 18f * sc, if (noteProblem != null) 0xFFE8A33D.toInt() else Belle.TEXT_MID, maxW = w - 80f * sc, face = u.light)
                y += 44f * sc
            }
            var idx = 6
            fun noteRow(n: Notes.Item, index: Int) {
                val h = 66f * sc
                val focus = sel == index
                if (focus) {
                    u.p.shader = android.graphics.LinearGradient(0f, y, 0f, y + h, Belle.BLUE_LIGHT, Belle.BLUE, android.graphics.Shader.TileMode.CLAMP)
                    c.drawRect(0f, y, w, y + h, u.p)
                    u.p.shader = null
                } else {
                    u.p.color = 0xFF3A3A3A.toInt()
                    c.drawRect(0f, y + h - 1f, w, y + h, u.p)
                }
                val ic = icon(n.pkg)
                rf.set(14f * sc, y + 13f * sc, 14f * sc + 40f * sc, y + 13f * sc + 40f * sc)
                if (ic != null) c.drawBitmap(ic, null, rf, null)
                if (!n.ongoing && android.os.SystemClock.uptimeMillis() > 0 && System.currentTimeMillis() - n.postTime < 10 * 60_000L) {
                    u.p.color = 0xFFE23B3B.toInt()
                    c.drawCircle(52f * sc, y + 15f * sc, 5f * sc, u.p)
                }
                val tx = 66f * sc
                u.text(c, n.title, tx, y + 28f * sc, 20f * sc, Color.WHITE, maxW = w - tx - 14f * sc, face = u.medium)
                if (n.progress != null) {
                    u.progressBar(c, RectF(tx, y + 38f * sc, w - 16f * sc, y + 54f * sc), n.progress)
                } else if (!n.text.isNullOrBlank()) {
                    u.text(c, n.text, tx, y + 52f * sc, 16f * sc, if (focus) Color.WHITE else Belle.TEXT_MID, maxW = w - tx - 14f * sc, face = u.light)
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
            if (stockShade != null) {
                // the full Android panel: every quick settings tile and the rest of the stock shade
                val h = 62f * sc
                val focus = sel == idx
                if (focus) {
                    u.p.shader = android.graphics.LinearGradient(0f, y, 0f, y + h, Belle.BLUE_LIGHT, Belle.BLUE, android.graphics.Shader.TileMode.CLAMP)
                    c.drawRect(0f, y, w, y + h, u.p)
                    u.p.shader = null
                }
                u.text(c, "All quick settings", 66f * sc, y + 28f * sc, 20f * sc, Color.WHITE, face = u.medium)
                u.text(c, "Every tile of the Android panel", 66f * sc, y + 52f * sc, 16f * sc, if (focus) Color.WHITE else Belle.TEXT_MID, face = u.light)
                mark(idx, y, y + h)
                y += h
            }
            c.restore()
            // keep the focused row on screen
            val view = panelH - barH - 24f * sc
            if (selBottom - scroll > barH + view) scroll = selBottom - barH - view
            if (selTop - scroll < barH) scroll = selTop - barH
            scroll = scroll.coerceAtLeast(0f)

            // the pull handle: the Belle panel handle graphic on a dark strip, like the guideline
            u.p.color = 0xFF1A1A1A.toInt()
            c.drawRect(0f, panelH - 24f * sc, w, panelH, u.p)
            rf.set(w / 2f - 39f * sc, panelH - 25f * sc, w / 2f + 39f * sc, panelH - 1f * sc)
            if (!u.gfx.draw(c, "qtg_graf_panel_handle_normal", rf)) {
                u.p.color = 0x55FFFFFF
                c.drawRect(w / 2f - 30f * sc, panelH - 14f * sc, w / 2f + 30f * sc, panelH - 11f * sc, u.p)
            }
            c.restore()
        }

        private val rf = RectF()

        /** Four switches, and a fifth (Settings) when the Belle settings glyph is there to draw. */
        private fun switchCount(u: Ui) = if (u.gfx.get("toolbar_settings") != null) 5 else 4

        // ---- input ----

        private fun activate() {
            val normal = normal()
            val acts = acts()
            when {
                sel == 0 -> if (sw == 4) {
                    close()
                    launch("com.belleui.settings")
                } else toggle(sw)
                sel == 1 -> Profiles.apply(sys, (Profiles.current(sys) + 1) % Profiles.names.size)
                sel == 5 -> {
                    for (n in normal) if (n.clearable) Notes.cancel(sys, n)
                    notesAt = 0
                }
                sel in 6 until 6 + normal.size + acts.size -> {
                    val n = (normal + acts)[sel - 6]
                    Notes.open(sys, n)
                    close()
                }
                sel == 6 + normal.size + acts.size && stockShade != null -> {
                    close()
                    stockShade?.invoke()
                }
                else -> {}
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
                KeyEvent.KEYCODE_DPAD_UP -> {
                    sel = (sel - 1).coerceAtLeast(0)
                    if (sel == 3 || sel == 2) sel = 1
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    sel = (sel + 1).coerceAtMost(last)
                    if (sel == 2 || sel == 3) sel = 4
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> when (sel) {
                    0 -> sw = (sw + switchCount(ui!!) - 1) % switchCount(ui!!)
                    2 -> volume(AudioManager.STREAM_MUSIC, -1)
                    3 -> volume(AudioManager.STREAM_RING, -1)
                    4 -> setBrightness(brightness() - 16)
                    else -> {}
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> when (sel) {
                    0 -> sw = (sw + 1) % switchCount(ui!!)
                    2 -> volume(AudioManager.STREAM_MUSIC, 1)
                    3 -> volume(AudioManager.STREAM_RING, 1)
                    4 -> setBrightness(brightness() + 16)
                    else -> {}
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> activate()
                KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_DEL -> {
                    // dismiss the highlighted notification
                    val all = normal() + acts()
                    if (sel in 6 until 6 + all.size) {
                        val n = all[sel - 6]
                        if (!n.ongoing) Notes.cancel(sys, n)
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
            val panelH = height.toFloat()
            if (e.y > panelH) {
                close()
                return true
            }
            for ((i, rect) in hits.reversed()) if (rect.contains(e.x, e.y)) {
                if (i == 0) {
                    sw = (e.x / (width / switchCount(ui!!).toFloat())).toInt().coerceIn(0, switchCount(ui!!) - 1)
                }
                sel = i
                activate()
                invalidate()
                return true
            }
            return true
        }
    }

    /** True while the Status menu or another full-screen Belle dialog is up. */
    val statusOpen: Boolean get() = dialog != null

    fun closeStatus() {
        dialog?.dismiss()
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
