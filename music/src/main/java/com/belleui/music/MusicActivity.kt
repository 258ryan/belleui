package com.belleui.music

import android.Manifest
import android.os.Build
import com.belleui.kit.BelleActivity
import com.belleui.kit.BelleShell
import com.belleui.kit.Screen

class MusicActivity : BelleActivity() {
    val library by lazy { Library(this) }

    override fun createRoot(shell: BelleShell): Screen = RootScreen(this)

    /** Audio access first, then (best effort) notifications for the playback controls. */
    fun ensureAccess(cb: (Boolean) -> Unit) {
        val audio = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        requestPerms(arrayOf(audio)) { ok ->
            cb(ok)
            if (ok && Build.VERSION.SDK_INT >= 33) requestPerms(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { }
        }
    }
}
