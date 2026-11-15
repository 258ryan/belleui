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
            Hud.stockShade = { openStock(ctx) }
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

    /** The recents key: the Belle launcher's Recent list instead of the stock overview, which needs the stock launcher. */
    @JvmStatic
    fun recents(ctx: Context): Boolean {
        return try {
            if (locked(ctx)) return false
            val i = android.content.Intent("com.belleui.launcher.RECENTS")
                .setClassName("com.belleui.launcher", "com.belleui.launcher.MainActivity")
                .putExtra("belle_recents", true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (ctx.packageManager.resolveActivity(i, 0) == null) return false
            ctx.startActivity(i)
            true
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
}
