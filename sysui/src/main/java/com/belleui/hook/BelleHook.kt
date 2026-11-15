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

    /** The pull-down: the Status menu, never over the lock screen. */
    @JvmStatic
    fun status(ctx: Context): Boolean {
        return try {
            if (locked(ctx)) return false
            Hud.toggleStatus(ctx, ctx)
            true
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun power(ctx: Context): Boolean {
        return try {
            if (locked(ctx)) return false
            Hud.showPower(ctx, ctx)
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
