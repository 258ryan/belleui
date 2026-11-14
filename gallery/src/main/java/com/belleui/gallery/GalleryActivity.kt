package com.belleui.gallery

import android.Manifest
import android.content.Intent
import android.os.Build
import com.belleui.kit.BelleActivity
import com.belleui.kit.BelleShell
import com.belleui.kit.Screen

class GalleryActivity : BelleActivity() {
    val library by lazy { MediaLibrary(this) }

    /** Set when the folder choice changed; the main screen reloads when it is shown again. */
    var libraryDirty = false

    override fun createRoot(shell: BelleShell): Screen = RootScreen(this)

    fun ensureAccess(cb: (Boolean) -> Unit) {
        val perms = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPerms(perms, cb)
    }

    private var folderCb: ((String?) -> Unit)? = null

    fun pickFolder(cb: (String?) -> Unit) {
        folderCb = cb
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 79)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 79) {
            val path = data?.data?.let { treeToPath(it) }
            folderCb?.invoke(path)
            folderCb = null
        }
    }
}
