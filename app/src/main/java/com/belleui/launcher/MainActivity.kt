package com.belleui.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View

class MainActivity : Activity() {
    private lateinit var launcher: LauncherView

    override fun onCreate(savedInstanceState: Bundle?) {
        com.belleui.kit.Belle.detectSysBar(this)
        super.onCreate(savedInstanceState)
        launcher = LauncherView(this)
        setContentView(launcher)
        hideSystemBars()
        if (intent?.getBooleanExtra("belle_recents", false) == true) launcher.post { launcher.openRecents() }
        if (intent?.getBooleanExtra("belle_hub", false) == true) launcher.post { launcher.openHub() }
    }

    override fun onResume() {
        super.onResume()
        launcher.onResumed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        launcher.onResumed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // HOME pressed (or launcher re-launched): return to the home page.
        if (intent.getBooleanExtra("belle_hub", false)) launcher.openHub()
        else if (intent.getBooleanExtra("belle_recents", false)) launcher.openRecents()
        else if (intent.action == Intent.ACTION_MAIN) launcher.goHome()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // Gamepad buttons, D-pad and keyboard arrive here. Analog sticks / hat axes are
    // turned into D-pad key events by the framework, so they land here too.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (launcher.handleKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("A launcher never finishes; BACK is handled in dispatchKeyEvent")
    override fun onBackPressed() {
        // intentionally empty
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        // the themes are "...Fullscreen", which sets the window's fullscreen flag and hides the status bar whatever the
        // visibility flags below say: with the Belle System UI the flag is taken off so the real status bar shows
        if (com.belleui.kit.Belle.sysBar) window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        else window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        // with the Belle System UI the real status bar stays; only the navigation bar goes
        val status = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or (if (com.belleui.kit.Belle.sysBar) 0 else View.SYSTEM_UI_FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or status)
    }
}
