package com.belleui.music

import android.Manifest
import android.content.Intent
import android.os.Build
import com.belleui.kit.BelleActivity
import com.belleui.kit.BelleShell
import com.belleui.kit.Screen

class MusicActivity : BelleActivity() {
    override val appAccent: Int? get() = 0xFFD2409A.toInt()

    val library by lazy { Library(this) }

    /** Set when a setting changed what the library should contain; the main screen reloads on return. */
    var libraryDirty = false

    override fun createRoot(shell: BelleShell): Screen = RootScreen(this)

    /** Audio access first, then (best effort) notifications for the playback controls. */
    fun ensureAccess(cb: (Boolean) -> Unit) {
        val audio = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        requestPerms(arrayOf(audio)) { ok ->
            cb(ok)
            if (ok && Build.VERSION.SDK_INT >= 33) requestPerms(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { }
        }
    }

    private var folderCb: ((String?) -> Unit)? = null

    fun pickFolder(cb: (String?) -> Unit) {
        folderCb = cb
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 78)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 78) {
            val path = data?.data?.let { treeToPath(it) }
            folderCb?.invoke(path)
            folderCb = null
        }
    }
}
