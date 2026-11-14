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
        if (lpparam.packageName != "android") return
        val names = arrayOf("onResume", "onStart")
        for (n in names) {
            try {
                XposedHelpers.findAndHookMethod("com.android.internal.app.ResolverActivity", lpparam.classLoader, n, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            Restyle.apply(param.thisObject as Activity)
                        } catch (t: Throwable) {
                            XposedBridge.log("BelleChooser: restyle failed: $t")
                        }
                    }
                })
                XposedBridge.log("BelleChooser: hooked ResolverActivity.$n in ${lpparam.processName}")
            } catch (t: Throwable) {
                XposedBridge.log("BelleChooser: could not hook ResolverActivity.$n: $t")
            }
        }
    }
}
