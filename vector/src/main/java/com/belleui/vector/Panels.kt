package com.belleui.vector

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Shader
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
import kotlin.math.max
import kotlin.math.min

/** Everything here runs inside the system UI process, drawn with the Belle kit from this module's own context. */
object Hud {
    private val main = Handler(Looper.getMainLooper())

    internal fun ui(mod: Context, w: Int, h: Int): Ui {
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
    // incoming-notification banner (replaces the stock heads-up)
    // ------------------------------------------------------------------------------------------

    private var bannerView: BannerView? = null
    private val hideBanner = Runnable { removeBanner(true) }

    private class BannerView(val sys: Context, val mod: Context, val title: String, val text: String, val icon: android.graphics.drawable.Drawable?, val open: (() -> Unit)?) : View(sys) {
        private var ui: Ui? = null
        private val box = RectF()

        override fun onDraw(c: Canvas) {
            val u = ui ?: ui(mod, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).also { ui = it }
            val sc = u.sc
            val w = width.toFloat()
            val h = height.toFloat()
            u.p.style = Paint.Style.FILL
            u.p.shader = LinearGradient(0f, 0f, 0f, h, 0xF2404040.toInt(), 0xF2181818.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, u.p)
            u.p.shader = null
            u.p.color = 0x33FFFFFF
            c.drawRect(0f, 0f, w, 1f, u.p)
            u.p.color = Belle.BLUE
            c.drawRect(0f, h - 3f * sc, w, h, u.p)
            val isz = 36f * sc
            var x = 14f * sc
            if (icon != null) {
                icon.setBounds((x).toInt(), ((h - isz) / 2f).toInt(), (x + isz).toInt(), ((h + isz) / 2f).toInt())
                icon.draw(c)
                x += isz + 14f * sc
            }
            val maxW = w - x - 14f * sc
            val ts = 19f * sc
            val bs = 16f * sc
            if (text.isBlank()) {
                u.text(c, title, x, u.baselineFor(h / 2f, ts, u.medium), ts, Color.WHITE, maxW = maxW, face = u.medium)
            } else {
                u.text(c, title, x, u.baselineFor(h * 0.34f, ts, u.medium), ts, Color.WHITE, maxW = maxW, face = u.medium)
                u.text(c, text, x, u.baselineFor(h * 0.70f, bs, u.light), bs, 0xFFC8C8C8.toInt(), maxW = maxW, face = u.light)
            }
        }

        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            if (e.action == android.view.MotionEvent.ACTION_UP) {
                try { open?.invoke() } catch (_: Throwable) { }
                removeBanner(true)
            }
            return true
        }
    }

    /** Shows [sbn] as a Belle banner under the status bar; false lets the stock heads-up run. */
    /** A plain Belle banner with no notification behind it (low battery, screenshot saved). */
    fun showNote(sys: Context, mod: Context, title: String, body: String) {
        main.post {
            try {
                removeBanner(false)
                val wm = sys.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val dm = sys.resources.displayMetrics
                val sc = min(dm.widthPixels / 640f, dm.heightPixels / 480f)
                val v = BannerView(sys, mod, title, body, null, null)
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, (64 * sc).toInt(), 2020,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                )
                lp.gravity = Gravity.TOP
                lp.title = "BelleBanner"
                wm.addView(v, lp)
                bannerView = v
                v.translationY = -64f * sc
                v.animate().translationY(0f).setDuration(220).start()
                main.postDelayed(hideBanner, 4500)
            } catch (_: Throwable) { }
        }
    }

    fun showBanner(sys: Context, mod: Context, sbn: android.service.notification.StatusBarNotification): Boolean {
        val n = sbn.notification
        if (n.fullScreenIntent != null) return false
        val ex = n.extras
        val title = (ex.getCharSequence(android.app.Notification.EXTRA_TITLE) ?: "").toString()
        val body = (ex.getCharSequence(android.app.Notification.EXTRA_TEXT) ?: ex.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT) ?: "").toString()
        if (title.isBlank() && body.isBlank()) return false
        val icon = try {
            val pctx = sys.createPackageContext(sbn.packageName, 0)
            n.smallIcon?.loadDrawable(pctx)?.mutate()?.also { it.setTint(Color.WHITE) }
        } catch (_: Throwable) { null }
        val pi = n.contentIntent
        main.post {
            try {
                removeBanner(false)
                val wm = sys.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val dm = sys.resources.displayMetrics
                val sc = min(dm.widthPixels / 640f, dm.heightPixels / 480f)
                val v = BannerView(sys, mod, title.ifBlank { sbn.packageName }, body, icon, if (pi == null) null else ({ pi.send() }))
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, (64 * sc).toInt(), 2020,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                )
                lp.gravity = Gravity.TOP
                lp.title = "BelleBanner"
                wm.addView(v, lp)
                bannerView = v
                v.translationY = -64f * sc
                v.animate().translationY(0f).setDuration(220).start()
                main.postDelayed(hideBanner, 4500)
            } catch (_: Throwable) { }
        }
        return true
    }

    private fun removeBanner(animate: Boolean) {
        main.removeCallbacks(hideBanner)
        val v = bannerView ?: return
        bannerView = null
        val kill = Runnable { try { (v.sys.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Throwable) { } }
        if (animate) v.animate().translationY(-v.height.toFloat()).setDuration(180).withEndAction(kill).start() else kill.run()
    }

    // ------------------------------------------------------------------------------------------
    // a Belle list dialog (the power menu)
    // ------------------------------------------------------------------------------------------

    class Entry(val title: String, val subtitle: String? = null, val icon: android.graphics.Bitmap? = null, val onClose: (() -> Unit)? = null, val action: () -> Unit)

    private class ListView(val sys: Context, val mod: Context, val title: String, entries0: List<Entry>, val close: () -> Unit) : View(sys) {
        val entries = entries0.toMutableList()
        var sel = 0
        private var top = 0
        private var vis = 1
        private var rowH = 0f
        private var ui: Ui? = null
        private val box = RectF()
        private val row = RectF()

        override fun onDraw(c: Canvas) {
            val u = ui ?: ui(mod, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).also { ui = it }
            val sc = u.sc
            val rich = entries.any { it.icon != null }
            rowH = (if (rich) Belle.ROW_H else Belle.MENU_ITEM_H + 10f) * sc
            val head = 54f * sc
            vis = max(1, min(entries.size, ((height * 0.9f - head - 16f * sc) / rowH).toInt()))
            if (sel < top) top = sel else if (sel >= top + vis) top = sel - vis + 1
            top = top.coerceIn(0, max(0, entries.size - vis))
            val w = min(width * 0.9f, (if (rich) 540f else 440f) * sc)
            val h = head + vis * rowH + 12f * sc + (if (rich) 28f * sc else 0f)
            box.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
            u.popupPlate(c, box)
            u.text(c, title, box.left + 18f * sc, u.baselineFor(box.top + head / 2f + 2f * sc, 24f * sc), 24f * sc, 0xFF4A90D9.toInt(), face = u.medium)
            u.p.style = Paint.Style.FILL
            u.p.shader = null
            u.p.color = 0xFF4A90D9.toInt()
            c.drawRect(box.left + 8f * sc, box.top + head, box.right - 8f * sc, box.top + head + 2f * sc, u.p)
            for (j in 0 until vis) {
                val i = top + j
                val e = entries[i]
                row.set(box.left + 6f * sc, box.top + head + 4f * sc + j * rowH, box.right - 6f * sc, box.top + head + 4f * sc + (j + 1) * rowH)
                u.listRow(c, row, i == sel, Row(e.title, e.subtitle, icon = e.icon, rightReserve = if (e.onClose != null) 44f else 0f))
                if (e.onClose != null) u.glyph(c, Ic.CLOSE, row.right - 30f * sc, row.centerY(), 28f * sc)
            }
            if (rich) {
                u.text(c, "A  Open     Y  Close app", box.centerX(), u.baselineFor(box.bottom - 16f * sc, 17f * sc, u.light), 17f * sc, Belle.TEXT_MID, Paint.Align.CENTER, face = u.light)
            }
            if (entries.size > vis) u.scrollbar(c, box.right - 4f * sc, box.top + head + 4f * sc, box.top + head + 4f * sc + vis * rowH, top / (entries.size - vis).toFloat(), vis.toFloat() / entries.size)
        }

        private fun closeSel() {
            val e = entries.getOrNull(sel) ?: return
            val oc = e.onClose ?: return
            try { oc() } catch (_: Throwable) { }
            entries.removeAt(sel)
            if (entries.isEmpty()) close() else sel = sel.coerceIn(0, entries.size - 1)
            invalidate()
        }

        fun key(code: Int, ev: KeyEvent): Boolean {
            if (ev.action != KeyEvent.ACTION_DOWN) return true
            if (entries.isEmpty()) {
                close()
                return true
            }
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> sel = (sel - 1 + entries.size) % entries.size
                KeyEvent.KEYCODE_DPAD_DOWN -> sel = (sel + 1) % entries.size
                KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (entries[sel].onClose == null) return code != KeyEvent.KEYCODE_DPAD_RIGHT && false
                    closeSel()
                    return true
                }
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
            val sc = ui?.sc ?: 1f
            val i = top + ((e.y - box.top - 58f * sc) / rowH).toInt()
            if (e.y > box.top + 58f * sc && i in entries.indices) {
                sel = i
                if (entries[i].onClose != null && e.x > box.right - 64f * sc) {
                    closeSel()
                } else {
                    close()
                    entries[i].action()
                }
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

    /** The Belle recents: the device's real task list (the same one the ROM's own app switcher uses), open or close each app. */
    fun showRecents(sys: Context, mod: Context): Boolean {
        return try {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val atm = atmCls.getMethod("getService").invoke(null)!!
            val iface = Class.forName("android.app.IActivityTaskManager")
            val uid = android.os.Process.myUserHandle().hashCode()
            val cu = Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as Int
            // the newest tasks, including those that keep themselves out of recents (games started from a frontend)
            val slice = iface.getMethod("getRecentTasks", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(atm, 30, 1 or 2, cu)!!
            @Suppress("UNCHECKED_CAST")
            val tasks = slice.javaClass.getMethod("getList").invoke(slice) as List<Any>
            val running = iface.getMethod("getTasks", Int::class.javaPrimitiveType).invoke(atm, 1) as List<*>
            val topId = running.firstOrNull()?.let { it.javaClass.getField("taskId").getInt(it) } ?: -1
            val pm = sys.packageManager
            val remove = iface.getMethod("removeTask", Int::class.javaPrimitiveType)
            val start = iface.getMethod("startActivityFromRecents", Int::class.javaPrimitiveType, android.os.Bundle::class.java)
            val seen = HashSet<String>()
            val entries = ArrayList<Entry>()
            for (t in tasks) {
                val id = t.javaClass.getField("taskId").getInt(t)
                val base = t.javaClass.getField("baseIntent").get(t) as? Intent
                val orig = t.javaClass.getField("origActivity").get(t) as? android.content.ComponentName
                val comp = orig ?: base?.component ?: continue
                val pkg = comp.packageName
                if (id == topId || pkg == "com.android.systemui" || pkg == "com.belleui.launcher" || pkg == "android" || !seen.add(pkg)) continue
                val info = try { pm.getActivityInfo(comp, 0) } catch (_: Throwable) { continue }
                val label = info.loadLabel(pm).toString()
                val d = info.loadIcon(pm)
                val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, 96, 96)
                d.draw(Canvas(bmp))
                entries.add(Entry(label, null, bmp, { try { remove.invoke(atm, id) } catch (_: Throwable) { } }) {
                    try { start.invoke(atm, id, null) } catch (_: Throwable) { }
                })
            }
            if (entries.isEmpty()) {
                showList(sys, mod, "Recent", listOf(Entry("No other apps open") { }))
            } else {
                // the same "close apps" action as the ROM's switcher: every task but the one on screen, home and the system,
                // including the running tasks of apps that keep themselves out of recents
                val ids = ArrayList<Int>()
                for (t in tasks) ids.add(t.javaClass.getField("taskId").getInt(t))
                entries.add(Entry("Close all apps", "Frees memory; apps keep their saved data") {
                    Thread {
                        val keep = setOf("com.android.systemui", "com.belleui.launcher", "android")
                        val all = HashSet<Int>(ids)
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val run = iface.getMethod("getFilteredTasks", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).invoke(atm, Int.MAX_VALUE, false) as List<Any>
                            for (r in run) {
                                val pkg = (r.javaClass.getField("baseActivity").get(r) as? android.content.ComponentName)?.packageName
                                if (pkg != null && pkg !in keep) all.add(r.javaClass.getField("taskId").getInt(r))
                            }
                        } catch (_: Throwable) { }
                        for (id in all) {
                            if (id == topId) continue
                            try { remove.invoke(atm, id) } catch (_: Throwable) { }
                        }
                    }.start()
                })
                showList(sys, mod, "Recent apps", entries)
            }
            true
        } catch (_: Throwable) {
            false
        }
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
    internal object Notes {
        class Item(val key: String, val pkg: String, val title: String, val text: String?, val ongoing: Boolean, val progress: Float?, val clearable: Boolean, val postTime: Long)

        private val uri = android.net.Uri.parse("content://com.belleui.launcher.status/active")

        /** The real System UI's own notification listener: every notification, with no other app needed. */
        private fun listener(): android.service.notification.NotificationListenerService? = ownListener ?: try {
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

    /** The notification listener of the standalone Belle System UI (null when the stock System UI is in use). */
    @Volatile var ownListener: android.service.notification.NotificationListenerService? = null

    /** How many notifications are showing, for the status bar's mail mark. */
    fun activeCount(sys: Context): Int = Notes.active(sys).count { !it.ongoing }

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
        val v = StatusMenu(sys, mod) { d.dismiss() }
        d = fullDialog(sys, v) { code, ev -> v.key(code, ev) }
        dialog = d
        d.show()
        v.requestFocus()
    }
}
