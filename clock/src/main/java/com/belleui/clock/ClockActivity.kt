package com.belleui.clock

import android.Manifest
import android.os.Build
import com.belleui.kit.BelleActivity
import com.belleui.kit.BelleShell
import com.belleui.kit.Screen

class ClockActivity : BelleActivity() {
    override val appAccent: Int? get() = 0xFFE0702D.toInt()

    val store by lazy { AlarmStore(this) }

    override fun createRoot(shell: BelleShell): Screen {
        // Notifications are needed for the full-screen alarm; ask quietly once.
        if (Build.VERSION.SDK_INT >= 33) requestPerms(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { }
        return ClockScreen(this)
    }
}
