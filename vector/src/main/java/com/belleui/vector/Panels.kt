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
    // the Status menu: profile, switches, volumes, brightness
    // ------------------------------------------------------------------------------------------

    private class StatusView(val sys: Context, val mod: Context, val close: () -> Unit) : View(sys) {
        private var ui: Ui? = null
        private val am = sys.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private val box = RectF()
        private val r = RectF()
        private var row = 0
        private var col = 0
        private val handler = Handler(Looper.getMainLooper())
        private val tick = object : Runnable {
            override fun run() {
                invalidate()
                handler.postDelayed(this, 700)
            }
        }

        // rows: 0 profiles, 1 switches, 2 media volume, 3 ringtone volume, 4 brightness, 5 buttons
        private val rowCount = 6
        private val toggleNames = arrayOf("Wi-Fi", "Bluetooth", "Airplane", "Sound")

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            handler.post(tick)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            handler.removeCallbacks(tick)
        }

        private fun wifi(): Boolean = try { (sys.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled } catch (_: Throwable) { false }

        private fun bt(): Boolean = try {
            @Suppress("DEPRECATION")
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        } catch (_: Throwable) {
            false
        }

        private fun airplane(): Boolean = try { Settings.Global.getInt(sys.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0 } catch (_: Throwable) { false }

        private fun soundOn(): Boolean = am.ringerMode == AudioManager.RINGER_MODE_NORMAL

        private fun toggle(i: Int) {
            try {
                when (i) {
                    0 -> @Suppress("DEPRECATION") (sys.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).setWifiEnabled(!wifi())
                    1 -> @Suppress("DEPRECATION") android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.let { if (it.isEnabled) it.disable() else it.enable() }
                    2 -> {
                        val on = !airplane()
                        try {
                            val cm = sys.getSystemService(Context.CONNECTIVITY_SERVICE)
                            cm.javaClass.getMethod("setAirplaneMode", Boolean::class.javaPrimitiveType).invoke(cm, on)
                        } catch (_: Throwable) {
                            Settings.Global.putInt(sys.contentResolver, Settings.Global.AIRPLANE_MODE_ON, if (on) 1 else 0)
                            sys.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", on))
                        }
                    }
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

        private fun battery(): String {
            val i = sys.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return ""
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val chg = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            return "${level * 100 / scale}%" + if (chg) " charging" else ""
        }

        override fun onDraw(c: Canvas) {
            val u = ui ?: ui(mod, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).also { ui = it }
            val sc = u.sc
            val w = min(width * 0.94f, 600f * sc)
            val h = min(height * 0.94f, 440f * sc)
            box.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
            u.nine(c, "qtg_fr_popup", box, 40, 20f)
            val x0 = box.left + 22f * sc
            val x1 = box.right - 22f * sc
            // header
            val now = java.util.Date()
            u.text(c, android.text.format.DateFormat.getTimeFormat(sys).format(now), x0, box.top + 44f * sc, 34f * sc, Color.WHITE)
            u.text(c, android.text.format.DateFormat.getMediumDateFormat(sys).format(now), x0 + 120f * sc, box.top + 40f * sc, 17f * sc, Belle.TEXT_MID, face = u.light)
            u.text(c, "Battery " + battery(), x1, box.top + 40f * sc, 17f * sc, Belle.TEXT_MID, Paint.Align.RIGHT, face = u.light)
            var y = box.top + 62f * sc
            // 0: profiles
            val chipW = (x1 - x0 - 3 * 8f * sc) / 4f
            val cur = Profiles.current(sys)
            for (i in 0..3) {
                r.set(x0 + i * (chipW + 8f * sc), y, x0 + i * (chipW + 8f * sc) + chipW, y + 40f * sc)
                u.pushButton(c, r, Profiles.names[i], row == 0 && col == i)
                if (i == cur) {
                    u.p.style = Paint.Style.STROKE
                    u.p.strokeWidth = 2.5f * sc
                    u.p.color = Belle.BLUE_LIGHT
                    c.drawRoundRect(r.left - 3f * sc, r.top - 3f * sc, r.right + 3f * sc, r.bottom + 3f * sc, 10f * sc, 10f * sc, u.p)
                    u.p.style = Paint.Style.FILL
                }
            }
            y += 54f * sc
            // 1: switches
            val on = booleanArrayOf(wifi(), bt(), airplane(), soundOn())
            for (i in 0..3) {
                r.set(x0 + i * (chipW + 8f * sc), y, x0 + i * (chipW + 8f * sc) + chipW, y + 40f * sc)
                u.pushButton(c, r, toggleNames[i] + (if (on[i]) ": on" else ": off"), row == 1 && col == i)
            }
            y += 54f * sc
            // 2-4: sliders
            fun slider(label: String, frac: Float, focused: Boolean) {
                u.text(c, label, x0, y + 18f * sc, 17f * sc, if (focused) Color.WHITE else Belle.TEXT_MID, face = u.light)
                r.set(x0 + 120f * sc, y, x1, y + 20f * sc)
                if (focused) {
                    u.p.style = Paint.Style.STROKE
                    u.p.strokeWidth = 2.5f * sc
                    u.p.color = Belle.BLUE_LIGHT
                    c.drawRoundRect(r.left - 4f * sc, r.top - 4f * sc, r.right + 4f * sc, r.bottom + 4f * sc, 12f * sc, 12f * sc, u.p)
                    u.p.style = Paint.Style.FILL
                }
                u.progressBar(c, r, frac)
                y += 42f * sc
            }
            slider("Media", am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1), row == 2)
            slider("Ringtone", am.getStreamVolume(AudioManager.STREAM_RING).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_RING).coerceAtLeast(1), row == 3)
            slider("Brightness", brightness() / 255f, row == 4)
            // 5: buttons
            val bw = (x1 - x0 - 8f * sc) / 2f
            r.set(x0, y + 4f * sc, x0 + bw, y + 44f * sc)
            u.pushButton(c, r, "Notifications", row == 5 && col == 0)
            r.set(x0 + bw + 8f * sc, y + 4f * sc, x1, y + 44f * sc)
            u.pushButton(c, r, "Settings", row == 5 && col == 1)
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
            val step = 1
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    row = (row - 1 + rowCount) % rowCount
                    col = min(col, if (row <= 1) 3 else if (row == 5) 1 else 0)
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    row = (row + 1) % rowCount
                    col = min(col, if (row <= 1) 3 else if (row == 5) 1 else 0)
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> when (row) {
                    0, 1 -> col = (col + 3) % 4
                    2 -> volume(AudioManager.STREAM_MUSIC, -step)
                    3 -> volume(AudioManager.STREAM_RING, -step)
                    4 -> setBrightness(brightness() - 16)
                    5 -> col = (col + 1) % 2
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> when (row) {
                    0, 1 -> col = (col + 1) % 4
                    2 -> volume(AudioManager.STREAM_MUSIC, step)
                    3 -> volume(AudioManager.STREAM_RING, step)
                    4 -> setBrightness(brightness() + 16)
                    5 -> col = (col + 1) % 2
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> when (row) {
                    0 -> Profiles.apply(sys, col)
                    1 -> toggle(col)
                    5 -> {
                        close()
                        if (col == 0) launch("com.belleui.launcher", "belle_hub") else launch("com.belleui.settings")
                        return true
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
            if (e.action == android.view.MotionEvent.ACTION_DOWN && !box.contains(e.x, e.y)) close()
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
        val v = StatusView(sys, mod) { d.dismiss() }
        d = fullDialog(sys, v) { code, ev -> v.key(code, ev) }
        dialog = d
        d.show()
        v.requestFocus()
    }
}
