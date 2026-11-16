package com.belleui.gallery

import android.Manifest
import android.content.Intent
import android.os.Build
import com.belleui.kit.BelleActivity
import com.belleui.kit.BelleShell
import com.belleui.kit.Screen

class GalleryActivity : BelleActivity() {
    val library by lazy { MediaLibrary(this) }
    val marks by lazy { Marks(this) }

    private var cacheKey = -1L
    private var shownList: List<Media> = emptyList()
    private var favList: List<Media> = emptyList()
    private var hiddenList: List<Media> = emptyList()
    private var trashList: List<Media> = emptyList()

    private fun rebuild() {
        val key = marks.version.toLong() * 1_000_003L + library.items.size + System.identityHashCode(library.items)
        if (key == cacheKey) return
        cacheKey = key
        val all = library.items
        shownList = all.filter { !marks.isHidden(it) && !marks.isTrashed(it) }
        favList = shownList.filter { marks.isFav(it) }
        hiddenList = all.filter { marks.isHidden(it) && !marks.isTrashed(it) }
        trashList = all.filter { marks.isTrashed(it) }
    }

    /** The library as the user sees it: without hidden and deleted photos. */
    fun photos(): List<Media> { rebuild(); return shownList }
    fun favourites(): List<Media> { rebuild(); return favList }
    fun hiddenItems(): List<Media> { rebuild(); return hiddenList }
    fun trashed(): List<Media> { rebuild(); return trashList }

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
