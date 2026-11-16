package com.belleui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.belleui.kit.BelleActivity
import com.belleui.kit.BelleShell
import com.belleui.kit.Screen

class SettingsActivity : BelleActivity() {
    override val appAccent: Int? get() = 0xFF5E7DB0.toInt()

    override fun createRoot(shell: BelleShell): Screen = RootScreen(this)

    /** Opens a system screen; false if this device doesn't have it. */
    fun tryStart(i: Intent): Boolean = try {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }

    fun systemPanel(panel: String, fallback: String): Boolean =
        tryStart(Intent(if (Build.VERSION.SDK_INT >= 29) panel else fallback))

    fun canWriteSettings(): Boolean = Settings.System.canWrite(this)

    fun askWriteSettings() {
        tryStart(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
    }

    var onResumeHook: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        onResumeHook?.invoke()
    }

    val ctx: Context get() = this
}
