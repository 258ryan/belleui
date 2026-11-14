package com.belleui.vector

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

/**
 * Belle pieces inside the system UI process: the volume pop-up, the power menu and the Status menu replace the
 * stock ones when their show methods are called. Every hook falls back to the stock screen if anything goes
 * wrong, so a mistake here can never leave the device without a volume pop-up or power menu.
 */
object SystemUiHook {
    private const val MODULE = "com.belleui.vector"

    private fun modContext(sys: Context): Context? = try {
        sys.createPackageContext(MODULE, Context.CONTEXT_IGNORE_SECURITY)
    } catch (t: Throwable) {
        XposedBridge.log("BelleChooser: can't reach the module's resources: $t")
        null
    }

    /** Hooks every overload called [name] in [className], running [show] instead of it. */
    private fun replace(lpparam: XC_LoadPackage.LoadPackageParam, className: String, name: String, show: (Context, Context, Any, Array<Any?>) -> Unit) {
        try {
            val cls = XposedHelpers.findClass(className, lpparam.classLoader)
            val methods: List<Method> = cls.declaredMethods.filter { it.name == name }
            if (methods.isEmpty()) {
                XposedBridge.log("BelleChooser: $className.$name not found")
                return
            }
            for (m in methods) {
                m.isAccessible = true
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val sys = XposedHelpers.getObjectField(param.thisObject, "mContext") as Context
                            val mod = modContext(sys) ?: return
                            show(sys, mod, param.thisObject, param.args)
                            param.setResult(null)
                        } catch (t: Throwable) {
                            XposedBridge.log("BelleChooser: $name failed, using the stock one: $t")
                        }
                    }
                })
            }
            XposedBridge.log("BelleChooser: hooked $className.$name (${methods.size})")
        } catch (t: Throwable) {
            XposedBridge.log("BelleChooser: could not hook $className.$name: $t")
        }
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        // volume pop-up
        replace(lpparam, "com.android.systemui.volume.VolumeDialogImpl", "show") { sys, mod, self, _ ->
            val stream = try { XposedHelpers.getIntField(self, "mActiveStream") } catch (_: Throwable) { android.media.AudioManager.STREAM_MUSIC }
            Hud.showVolume(sys, mod, stream)
        }
        // power menu
        replace(lpparam, "com.android.systemui.globalactions.GlobalActionsDialog", "showOrHideDialog") { sys, mod, _, _ ->
            Hud.showPower(sys, mod)
        }
        // the Status menu stands in for the notification shade and the quick settings panel
        replace(lpparam, "com.android.systemui.statusbar.phone.StatusBar", "animateExpandNotificationsPanel") { sys, mod, _, _ -> Hud.toggleStatus(sys, mod) }
        replace(lpparam, "com.android.systemui.statusbar.phone.StatusBar", "animateExpandSettingsPanel") { sys, mod, _, _ -> Hud.toggleStatus(sys, mod) }
    }
}
