package com.belleui.video

import android.Manifest
import android.content.Intent
import android.os.Build
import com.belleui.kit.BelleActivity
import com.belleui.kit.BelleShell
import com.belleui.kit.Screen

class VideoActivity : BelleActivity() {
    val library by lazy { VideoLibrary(this) }

    /** Set when something changed what the list should show; the main screen reloads when shown again. */
    var dirty = false

    override fun createRoot(shell: BelleShell): Screen = RootScreen(this)

    fun ensureAccess(cb: (Boolean) -> Unit) {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        requestPerms(arrayOf(perm), cb)
    }

    /** Plays [list] starting at [index] in the full-screen player. */
    fun play(list: List<Video>, index: Int) {
        // the player gets a window of the list around the chosen video, to keep the intent small
        val from = (index - 150).coerceAtLeast(0)
        val to = (index + 150).coerceAtMost(list.size)
        val part = list.subList(from, to)
        val i = Intent(this, PlayerActivity::class.java)
            .putExtra("uris", part.map { it.uri.toString() }.toTypedArray())
            .putExtra("titles", part.map { it.title }.toTypedArray())
            .putExtra("ids", part.map { it.id }.toLongArray())
            .putExtra("index", index - from)
        startActivity(i)
        dirty = true
    }

    override fun onResume() {
        super.onResume()
        shell.invalidate()
    }
}
