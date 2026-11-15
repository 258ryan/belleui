package com.belleui.sysui

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.belleui.vector.Hud
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * The Belle system UI: registers with the status bar service as "the" status bar, draws the status bar window, and
 * answers the system's requests (open the shade, the power menu, recent apps). Every step is wrapped, so a failure
 * in one leaves the others (and the phone) working.
 */
object Bar {
    private const val TAG = "BelleSysUi"
    private const val DESCRIPTOR = "com.android.internal.statusbar.IStatusBar"
    private lateinit var ctx: Context
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var view: BarView? = null

    @Volatile
    var notifications = 0
        private set
    var wifi = false
        private set
    var batteryPct = 100
        private set
    var title = ""
        private set
    var charging = false
        private set

    fun start(c: Context) {
        if (started) return
        started = true
        ctx = c
        step("hidden api") { unseal() }
        step("window") { addWindow() }
        step("receivers") { receivers() }
        step("register") { register(0) }
        step("title") { pollTitle() }
    }

    /** The label of the app on screen: the Belle status bar names what you are looking at. */
    private fun pollTitle() {
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            val top = am.getRunningTasks(1).firstOrNull()?.topActivity
            val label = if (top == null) "" else {
                val pm = ctx.packageManager
                try {
                    pm.getActivityInfo(top, 0).loadLabel(pm).toString().ifBlank { pm.getApplicationLabel(pm.getApplicationInfo(top.packageName, 0)).toString() }
                } catch (_: Throwable) {
                    top.packageName
                }
            }
            val n = Hud.activeCount(ctx)
            if (n != notifications) {
                notifications = n
                view?.invalidate()
            }
            if (label != title) {
                title = label
                view?.invalidate()
            }
        } catch (_: Throwable) {
        }
        main.postDelayed({ pollTitle() }, 1500)
    }

    private fun step(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "$what failed", t)
        }
    }

    /** System apps are normally exempt from the hidden-API limits; this makes sure of it for the few calls below. */
    private fun unseal() {
        val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
        val getDeclared = Class::class.java.getDeclaredMethod("getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java)
        val vm = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
        val getRuntime = getDeclared.invoke(vm, "getRuntime", arrayOf<Class<*>>()) as Method
        val setEx = getDeclared.invoke(vm, "setHiddenApiExemptions", arrayOf<Class<*>>(Array<String>::class.java)) as Method
        setEx.invoke(getRuntime.invoke(null), arrayOf("L") as Any)
    }

    private fun addWindow() {
        val res = android.content.res.Resources.getSystem()
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        val h = if (id != 0) res.getDimensionPixelSize(id) else (24 * res.displayMetrics.density).toInt()
        val v = BarView(ctx, h)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, h, 2000 /* TYPE_STATUS_BAR */,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.FILL_HORIZONTAL
        lp.title = "StatusBar"
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp)
        view = v
    }

    private fun receivers() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = i.getIntExtra("level", 0)
                    val scale = i.getIntExtra("scale", 100).coerceAtLeast(1)
                    batteryPct = level * 100 / scale
                    charging = i.getIntExtra("plugged", 0) != 0
                }
                view?.invalidate()
            }
        }, f)
        // the volume pop-up: the system sends this whenever a volume changes
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val stream = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                if (stream in intArrayOf(0, 2, 3, 4, 5)) {
                    try {
                        Hud.showVolume(ctx, ctx, stream)
                    } catch (t: Throwable) {
                        Log.e(TAG, "volume", t)
                    }
                }
            }
        }, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                main.post { view?.invalidate() }
            }

            override fun onLost(n: Network) {
                wifi = false
                main.post { view?.invalidate() }
            }
        })
    }

    // ---- being the status bar -----------------------------------------------------------------------

    /** The system's calls arrive by number; the numbers are read from the framework's own generated class. */
    private val names: Map<Int, String> by lazy {
        val m = HashMap<Int, String>()
        try {
            val stub = Class.forName("$DESCRIPTOR\$Stub")
            for (f in stub.declaredFields) {
                if (f.name.startsWith("TRANSACTION_")) {
                    f.isAccessible = true
                    m[f.getInt(null)] = f.name.removePrefix("TRANSACTION_")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "call table", t)
        }
        Log.i(TAG, "status bar calls known: ${m.size}")
        m
    }

    private class BarBinder : Binder() {
        override fun getInterfaceDescriptor(): String = DESCRIPTOR

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code in IBinder.FIRST_CALL_TRANSACTION..IBinder.LAST_CALL_TRANSACTION) {
                // every call on this interface is one-way: nothing to answer
                val name = names[code]
                if (name != null) main.post { handle(name) }
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun handle(name: String) {
        Log.i(TAG, "system asked: $name")
        try {
            Settings.Global.putString(ctx.contentResolver, "belle_sysui_last", name)
        } catch (_: Throwable) {
        }
        try {
            when (name) {
                "animateExpandNotificationsPanel", "animateExpandSettingsPanel" -> if (!Hud.statusOpen) Hud.toggleStatus(ctx, ctx)
                "togglePanel" -> Hud.toggleStatus(ctx, ctx)
                "animateCollapsePanels" -> Hud.closeStatus()
                "showGlobalActionsMenu" -> Hud.showPower(ctx, ctx)
                "toggleRecentApps", "showRecentApps" -> recents()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "handling $name", t)
        }
    }

    /** The recents key opens the Belle launcher's Recent tab. */
    fun recents() {
        val i = Intent("com.belleui.launcher.RECENTS")
            .setClassName("com.belleui.launcher", "com.belleui.launcher.MainActivity")
            .putExtra("belle_recents", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (ctx.packageManager.resolveActivity(i, 0) != null) ctx.startActivity(i)
    }

    private fun register(attempt: Int) {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "statusbar") as IBinder
            val svc = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val barCls = Class.forName(DESCRIPTOR)
            val mine = BarBinder()
            names // build the table now
            val proxy = Proxy.newProxyInstance(barCls.classLoader, arrayOf(barCls)) { _, m, _ -> if (m.name == "asBinder") mine else null }
            svc!!.javaClass.getMethod("registerStatusBar", barCls).invoke(svc, proxy)
            Log.i(TAG, "registered as the status bar")
            // tells the boot guard (the module's scripts) that this build came up
            Settings.Global.putString(ctx.contentResolver, "belle_sysui_ok", Settings.Global.getString(ctx.contentResolver, Settings.Global.BOOT_COUNT) ?: "1")
        } catch (t: Throwable) {
            Log.e(TAG, "register attempt $attempt failed", t)
            if (attempt < 15) main.postDelayed({ register(attempt + 1) }, 2000)
        }
    }
}
