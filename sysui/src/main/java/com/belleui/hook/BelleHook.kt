package com.belleui.hook

import android.app.KeyguardManager
import android.content.Context
import com.belleui.vector.Hud

/**
 * The entry points the stock System UI calls (patched in by sysui-stock/restyle.py) to show the Belle Status menu,
 * power menu and volume pop-up. Each returns true when the Belle one was shown and false to let the stock one run,
 * so anything that goes wrong here leaves the stock screens working.
 */
object BelleHook {
    private fun locked(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    } catch (_: Throwable) {
        true
    }

    @Volatile
    private var bypass = false

    /** Opens the stock quick settings panel: the next call from the System UI is let through. */
    private fun openStock(ctx: Context) {
        try {
            bypass = true
            val dep = Class.forName("com.android.systemui.Dependency")
            val cq = dep.getMethod("get", Class::class.java).invoke(null, Class.forName("com.android.systemui.statusbar.CommandQueue"))
            cq!!.javaClass.getMethod("animateExpandSettingsPanel", String::class.java).invoke(cq, null as String?)
        } catch (_: Throwable) {
            bypass = false
        }
    }

    /** The pull-down: the Status menu, never over the lock screen. */
    @JvmStatic
    fun status(ctx: Context): Boolean {
        return try {
            if (bypass) {
                bypass = false
                return false
            }
            if (locked(ctx)) return false
            Hud.toggleStatus(ctx, ctx)
            true
        } catch (_: Throwable) {
            false
        }
    }

    @Volatile
    private var bypassPower = false

    /** Opens the stock power menu: the next call from the System UI is let through. */
    private fun openStockPower(ctx: Context) {
        try {
            bypassPower = true
            val dep = Class.forName("com.android.systemui.Dependency")
            val cq = dep.getMethod("get", Class::class.java).invoke(null, Class.forName("com.android.systemui.statusbar.CommandQueue"))
            cq!!.javaClass.getMethod("showGlobalActionsMenu").invoke(cq)
        } catch (_: Throwable) {
            bypassPower = false
        }
    }

    @JvmStatic
    fun power(ctx: Context): Boolean {
        return try {
            if (bypassPower) {
                bypassPower = false
                return false
            }
            if (locked(ctx)) return false
            Hud.stockPower = { openStockPower(ctx) }
            Hud.showPower(ctx, ctx)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** The recents key: the Belle list of the device's real open apps (read inside System UI, no launcher or usage access needed). */
    @JvmStatic
    fun recents(ctx: Context): Boolean {
        return try {
            if (locked(ctx)) return false
            Hud.showRecents(ctx, ctx)
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun volume(ctx: Context, stream: Int): Boolean {
        return try {
            Hud.showVolume(ctx, ctx, stream)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** An incoming notification: the Belle banner instead of the stock heads-up (not over the lock screen or for calls). */
    @JvmStatic
    fun banner(sbn: android.service.notification.StatusBarNotification): Boolean {
        return try {
            val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context
            if (locked(app)) return false
            Hud.showBanner(app, app, sbn)
        } catch (_: Throwable) {
            false
        }
    }

    private var barDownY = 0f
    private var barOpened = false

    /**
     * Touches on the status bar. A pull down opens the Belle Status menu and the stock Android shade is never shown;
     * the events are swallowed so the stock panel never starts to open under it. Not on the lock screen.
     */
    @JvmStatic
    fun barTouch(ctx: Context, e: android.view.MotionEvent): Boolean {
        return try {
            if (locked(ctx)) return false
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    barDownY = e.rawY
                    barOpened = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val slop = 14f * ctx.resources.displayMetrics.density
                    if (!barOpened && e.rawY - barDownY > slop) {
                        barOpened = true
                        // a pull down while the Status menu is already open must not toggle it shut
                        if (!Hud.statusOpen) status(ctx)
                    }
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }
}
