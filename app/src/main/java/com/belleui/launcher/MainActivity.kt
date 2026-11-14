package com.belleui.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View

class MainActivity : Activity() {
    private lateinit var launcher: LauncherView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher = LauncherView(this)
        setContentView(launcher)
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        launcher.onResumed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // HOME pressed (or launcher re-launched): return to the home page.
        if (intent.action == Intent.ACTION_MAIN) launcher.goHome()
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
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }
}
