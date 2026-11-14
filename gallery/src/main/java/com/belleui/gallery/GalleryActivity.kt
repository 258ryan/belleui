package com.belleui.gallery

import android.Manifest
import android.os.Build
import com.belleui.kit.BelleActivity
import com.belleui.kit.BelleShell
import com.belleui.kit.Screen

class GalleryActivity : BelleActivity() {
    val library by lazy { MediaLibrary(this) }

    override fun createRoot(shell: BelleShell): Screen = RootScreen(this)

    fun ensureAccess(cb: (Boolean) -> Unit) {
        val perms = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPerms(perms, cb)
    }
}
