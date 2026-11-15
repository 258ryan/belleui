package com.belleui.vector

import android.app.Activity
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hooks Android's app chooser (ResolverActivity, which the Share chooser extends) and restyles it after it
 * has been built. It never replaces the chooser's logic, so the list of apps and what happens when one is
 * picked stay Android's own; only colours, the highlight and focus change. If anything fails it logs and the
 * chooser stays as it was.
 */
class ChooserHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        Status.note(lpparam.processName ?: "?", "loaded into ${lpparam.processName} for ${lpparam.packageName}")
        if (lpparam.packageName == "com.android.systemui") {
            SystemUiHook.install(lpparam)
            return
        }
        if (lpparam.packageName != "android") return
        hookRecents(lpparam)
        val names = arrayOf("onResume", "onStart")
        for (n in names) {
            try {
                XposedHelpers.findAndHookMethod("com.android.internal.app.ResolverActivity", lpparam.classLoader, n, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            Restyle.apply(param.thisObject as Activity)
                            Status.note(lpparam.processName ?: "?", "restyled a chooser (${param.thisObject.javaClass.simpleName})")
                        } catch (t: Throwable) {
                            XposedBridge.log("BelleChooser: restyle failed: $t")
                        }
                    }
                })
                XposedBridge.log("BelleChooser: hooked ResolverActivity.$n in ${lpparam.processName}")
                Status.note(lpparam.processName ?: "?", "hooked ResolverActivity.$n")
            } catch (t: Throwable) {
                XposedBridge.log("BelleChooser: could not hook ResolverActivity.$n: $t")
                Status.note(lpparam.processName ?: "?", "could not hook ResolverActivity.$n: ${t.javaClass.simpleName}")
            }
        }
    }

    /**
     * The recent-apps key. In the system process, where key presses are handled, the call that opens the stock
     * recent-apps screen is turned into "open the Belle launcher on its Recent tab". If the launcher isn't
     * installed, or anything is unexpected, the stock screen opens as usual.
     */
    private fun hookRecents(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cls = try {
            XposedHelpers.findClass("com.android.server.policy.PhoneWindowManager", lpparam.classLoader)
        } catch (_: Throwable) {
            return // not the system process
        }
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val ctx = XposedHelpers.getObjectField(param.thisObject, "mContext") as android.content.Context
                    val i = android.content.Intent("com.belleui.launcher.RECENTS")
                        .setClassName("com.belleui.launcher", "com.belleui.launcher.MainActivity")
                        .putExtra("belle_recents", true)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    if (ctx.packageManager.resolveActivity(i, 0) == null) return
                    ctx.startActivity(i)
                    param.setResult(null) // the stock screen stays closed
                } catch (t: Throwable) {
                    XposedBridge.log("BelleChooser: recents hook failed, using the stock screen: $t")
                }
            }
        }
        // the plain press, and the Alt+Tab form some devices send
        for (name in arrayOf("toggleRecentApps")) {
            try {
                XposedHelpers.findAndHookMethod(cls, name, hook)
                XposedBridge.log("BelleChooser: hooked PhoneWindowManager.$name")
                Status.note(lpparam.processName ?: "?", "hooked PhoneWindowManager.$name")
            } catch (t: Throwable) {
                XposedBridge.log("BelleChooser: could not hook PhoneWindowManager.$name: $t")
                Status.note(lpparam.processName ?: "?", "could not hook PhoneWindowManager.$name: ${t.javaClass.simpleName}")
            }
        }
        try {
            XposedHelpers.findAndHookMethod(cls, "showRecentApps", java.lang.Boolean.TYPE, hook)
            XposedBridge.log("BelleChooser: hooked PhoneWindowManager.showRecentApps")
        } catch (t: Throwable) {
            XposedBridge.log("BelleChooser: could not hook PhoneWindowManager.showRecentApps: $t")
        }
    }
}
