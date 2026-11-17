package com.android.systemui.usb

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Bundle
import com.belleui.kit.Act
import com.belleui.kit.BelleActivity
import com.belleui.kit.BelleShell
import com.belleui.kit.QueryDialog
import com.belleui.kit.Screen
import com.belleui.kit.Ui

/** "Allow USB debugging?": the question the system asks when a new computer connects. Answers through the debugging manager. */
class UsbDebuggingActivity : BelleActivity() {
    private var fingerprint = ""
    private var key = ""
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        key = intent.getStringExtra("key") ?: ""
        fingerprint = intent.getStringExtra("fingerprints") ?: intent.getStringExtra("fingerprint") ?: ""
        super.onCreate(savedInstanceState)
    }

    private fun answer(allow: Boolean) {
        if (answered) return
        answered = true
        try {
            val svc = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "adb") as android.os.IBinder
            val mgr = Class.forName("android.debug.IAdbManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, svc)!!
            if (allow) mgr.javaClass.getMethod("allowDebugging", Boolean::class.javaPrimitiveType, String::class.java).invoke(mgr, false, key)
            else mgr.javaClass.getMethod("denyDebugging").invoke(mgr)
        } catch (_: Throwable) { }
        finish()
    }

    override fun createRoot(shell: BelleShell): Screen = object : Screen() {
        override val title get() = "USB debugging"
        override fun onShow() {
            shell.showOverlay(QueryDialog("Allow USB debugging?\nThe computer's key fingerprint is:\n$fingerprint", "Allow", "Deny", "USB debugging") { answer(true) })
        }

        override fun draw(c: Canvas, ui: Ui, r: RectF) {}
        override fun onAct(a: Act): Boolean {
            if (a == Act.BACK) { answer(false); return true }
            return false
        }
    }

    override fun onStop() {
        super.onStop()
        if (!answered) answer(false)
    }
}
