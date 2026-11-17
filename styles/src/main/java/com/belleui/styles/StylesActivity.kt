package com.belleui.styles

import android.app.WallpaperManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.belleui.kit.BelleActivity
import com.belleui.kit.BelleShell
import com.belleui.kit.Screen

open class StylesActivity : BelleActivity() {
    override fun createRoot(shell: BelleShell): Screen = RootScreen(this)

    val wallpapers: WallpaperManager get() = WallpaperManager.getInstance(this)

    fun canWriteSettings(): Boolean = Settings.System.canWrite(this)

    /** Changing tones and feedback settings needs the "modify system settings" switch. */
    fun askWriteSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    /** Called after a wallpaper was set; when another app asked for it, the caller is answered and this closes. */
    open fun onWallpaperSet() {}

    var onResumeHook: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        onResumeHook?.invoke()
    }
}
