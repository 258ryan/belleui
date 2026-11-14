package com.belleui.gallery

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import com.belleui.kit.Act
import com.belleui.kit.Belle
import com.belleui.kit.Ic
import com.belleui.kit.ListState
import com.belleui.kit.MenuItem
import com.belleui.kit.Row
import com.belleui.kit.Screen
import com.belleui.kit.Tool
import com.belleui.kit.Ui

/** Turns an Android document-tree URI (from the folder picker) into a real path. */
fun treeToPath(uri: Uri): String? {
    val id = try { android.provider.DocumentsContract.getTreeDocumentId(uri) } catch (_: Exception) { return null }
    // the Belle picker hands out plain paths as document ids
    if (uri.authority == "com.belleui.picker.documents") return id
    val parts = id.split(":", limit = 2)
    val vol = parts[0]
    val rel = parts.getOrElse(1) { "" }
    val base = if (vol == "primary") android.os.Environment.getExternalStorageDirectory().path else "/storage/$vol"
    return if (rel.isEmpty()) base else "$base/$rel"
}

/** The folders the Gallery looks in; none chosen means everything on the device. */
class FoldersScreen(private val act: GalleryActivity) : Screen() {
    override val title get() = "Gallery folders"
    private val list = ListState()
    private var folders = act.library.folders

    private fun save(v: List<String>) {
        folders = v
        act.library.folders = v
        act.libraryDirty = true
        shell.invalidate()
    }

    private fun add() {
        act.pickFolder { path ->
            if (path != null && path !in folders) {
                save(folders + path)
                shell.toast("Looking in ${path.substringAfterLast('/')}...")
                Thread { act.library.indexFolders() }.start()
            }
        }
    }

    override fun tools() = listOf(Tool(Ic.ADD, { add() }))

    override fun menu() = listOf(
        MenuItem("Add folder") { add() },
        MenuItem("Remove selected") { folders.getOrNull(list.sel)?.let { save(folders - it) } },
        MenuItem("Use the whole device") { save(emptyList()) },
    )

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        if (folders.isEmpty()) {
            ui.text(c, "Pictures and videos from the whole device", r.centerX(), ui.baselineFor(r.centerY() - 14f * ui.sc, 22f * ui.sc), 22f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER, maxW = r.width() - 30f * ui.sc)
            ui.text(c, "Press Y to pick the folders to show", r.centerX(), ui.baselineFor(r.centerY() + 16f * ui.sc, 18f * ui.sc, ui.light), 18f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER, face = ui.light)
            return
        }
        list.draw(c, ui, r, folders.size) { i -> Row(folders[i].substringAfterLast('/'), folders[i], iconKind = Ic.ALBUMS) }
    }

    override fun onAct(a: Act): Boolean {
        if (folders.isEmpty()) return false
        return list.act(a, folders.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }

    override fun onScroll(dy: Float) = list.scrollBy(if (dy > 0) 1 else -1, folders.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
}
