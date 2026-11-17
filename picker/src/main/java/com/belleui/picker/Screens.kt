package com.belleui.picker

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.belleui.kit.Act
import com.belleui.kit.Belle
import com.belleui.kit.Ic
import com.belleui.kit.ListState
import com.belleui.kit.MenuItem
import com.belleui.kit.Row
import com.belleui.kit.Screen
import com.belleui.kit.Tool
import com.belleui.kit.Ui
import java.io.File

private fun message(c: Canvas, ui: Ui, r: RectF, text: String) {
    val lines = text.split("\n")
    for ((i, l) in lines.withIndex()) {
        ui.text(c, l, r.centerX(), ui.baselineFor(r.centerY() + (i - (lines.size - 1) / 2f) * 30f * ui.sc, 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER, maxW = r.width() - 40f * ui.sc)
    }
}

/** Opened from the launcher, or when the picker has no access to files yet. */
class StatusScreen(private val act: PickerActivity) : Screen() {
    override val lightOk: Boolean get() = true
    override val title get() = "Belle Picker"
    private val list = ListState()

    override fun onShow() {
        act.onResumeHook = {
            // back from the settings page: carry on into the picker if an app is waiting
            if (act.mode != Mode.NONE && act.hasAccess()) {
                shell.pop()
                shell.push(RootsScreen(act))
            }
            shell.postInvalidate()
        }
    }

    private fun rows(): List<Row> {
        val g = shell.ui.gfx
        return listOf(
            Row("Access to files", if (act.hasAccess()) "Allowed" else com.belleui.kit.Keys.hint("Not allowed - press A to allow"), icon = g.get("qgn_prop_fmgr_phone_memc_sub"), check = act.hasAccess()),
            Row("Picks for other apps", if (act.mode == Mode.NONE) "Folders and files, whenever an app asks" else "An app is waiting for your choice", icon = g.get("qgn_prop_folder_small")),
        )
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        val rows = rows()
        list.draw(c, ui, r, rows.size) { rows[it] }
        ui.text(
            c, "Other apps ask for a folder or a file; this picker shows the choice in Belle style and hands them access to just that.",
            r.left + 16f * ui.sc, r.top + 3f * Belle.ROW_H * ui.sc, 16f * ui.sc, Belle.TEXT_MID, maxW = r.width() - 32f * ui.sc, face = ui.light,
        )
    }

    override fun onAct(a: Act): Boolean {
        if (a == Act.OK && list.sel == 0 && !act.hasAccess()) {
            act.askAccess()
            return true
        }
        return list.act(a, 2, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }

    override fun onTap(x: Float, y: Float): Boolean {
        if (!act.hasAccess()) act.askAccess()
        return true
    }
}

/** Phone memory, memory cards and a few common places. */
class RootsScreen(private val act: PickerActivity) : Screen() {
    override val lightOk: Boolean get() = true
    override val title get() = when (act.mode) {
        Mode.TREE -> "Choose a folder"
        Mode.CREATE -> "Save to..."
        else -> "Choose a file"
    }
    private val list = ListState()
    private var entries: List<Pair<String, File>> = emptyList()

    override fun onShow() {
        val vols = Fs.volumes(act).map { it.label to it.root }
        val places = listOf("Downloads" to android.os.Environment.DIRECTORY_DOWNLOADS, "Pictures" to android.os.Environment.DIRECTORY_PICTURES, "Camera" to android.os.Environment.DIRECTORY_DCIM,
            "Music" to android.os.Environment.DIRECTORY_MUSIC, "Videos" to android.os.Environment.DIRECTORY_MOVIES, "Documents" to android.os.Environment.DIRECTORY_DOCUMENTS)
            .map { it.first to android.os.Environment.getExternalStoragePublicDirectory(it.second) }.filter { it.second.isDirectory }
        entries = vols + places
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        val g = ui.gfx
        list.draw(c, ui, r, entries.size) { i ->
            val (label, f) = entries[i]
            val vol = i < Fs.volumes(act).size
            Row(label, f.path, icon = g.get(if (vol) (if (i == 0) "qgn_prop_fmgr_phone_memc_sub" else "qgn_prop_fmgr_mmc_sub") else "qgn_prop_folder_small"), drill = true)
        }
    }

    private fun open(i: Int) {
        entries.getOrNull(i)?.let { shell.push(DirScreen(act, it.second, it.first)) }
    }

    override fun onAct(a: Act): Boolean {
        if (a == Act.OK) {
            open(list.sel)
            return true
        }
        return list.act(a, entries.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val i = list.hit(shell.ui, RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom), y, entries.size)
        if (i < 0) return false
        list.sel = i
        open(i)
        return true
    }

    override fun onScroll(dy: Float) = list.scrollBy(if (dy > 0) 1 else -1, entries.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
}

/** One folder: A opens folders and picks files; Y chooses this folder (or saves here). */
class DirScreen(private val act: PickerActivity, private val dir: File, private val label: String? = null) : Screen() {
    override val lightOk: Boolean get() = true
    override val title get() = label ?: dir.name.ifEmpty { dir.path }
    private val list = ListState()
    private var items: List<File> = emptyList()
    private var shown = false

    override fun onShow() {
        items = Fs.list(dir, act.showHidden)
        shown = true
    }

    private fun refresh() {
        items = Fs.list(dir, act.showHidden)
        shell.invalidate()
    }

    override fun tools(): List<Tool> = when (act.mode) {
        Mode.TREE -> listOf(Tool(Ic.CHECK, { act.pickTree(dir) }))
        Mode.CREATE -> listOf(Tool(Ic.CHECK, { save() }))
        else -> if (act.multi) listOf(Tool(Ic.CHECK, { act.pickFiles(act.marked.toList()) }, act.marked.isNotEmpty())) else emptyList()
    }

    override fun menu(): List<MenuItem> {
        val m = ArrayList<MenuItem>()
        when (act.mode) {
            Mode.TREE -> m.add(MenuItem("Use this folder") { act.pickTree(dir) })
            Mode.CREATE -> m.add(MenuItem("Save here") { save() })
            else -> if (act.multi && act.marked.isNotEmpty()) m.add(MenuItem("Done (${act.marked.size} chosen)") { act.pickFiles(act.marked.toList()) })
        }
        m.add(MenuItem("New folder") {
            act.askText("Folder name", "New folder") { name ->
                val f = File(dir, name)
                shell.toast(if (f.exists()) "That name is taken" else if (f.mkdir()) "Folder created" else "Couldn't create the folder")
                refresh()
            }
        })
        m.add(MenuItem(if (act.showHidden) "Hide hidden files" else "Show hidden files") {
            act.showHidden = !act.showHidden
            refresh()
        })
        return m
    }

    private fun save() {
        act.askText("File name", act.suggestedName) { name ->
            if (!act.createHere(dir, name)) shell.toast("Couldn't create that file")
        }
    }

    private fun usable(f: File) = f.isDirectory || act.mode == Mode.OPEN && Fs.matches(Fs.mime(f), act.filters)

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        if (items.isEmpty()) {
            message(c, ui, r, if (dir.canRead()) "This folder is empty" else "Can't open this folder")
            return
        }
        val g = ui.gfx
        list.draw(c, ui, r, items.size) { i ->
            val f = items[i]
            if (f.isDirectory) {
                Row(f.name, null, icon = g.get("qgn_prop_folder_small"), drill = true)
            } else {
                val icon = when (Fs.kind(f)) {
                    "image" -> "qgn_prop_file_images_sub"
                    "audio" -> "qgn_prop_file_audio"
                    "video" -> "qgn_prop_file_video"
                    else -> "qgn_prop_file_all_sub"
                }
                Row(f.name, Fs.fmtSize(f.length()) + "  -  " + Fs.fmtDate(f.lastModified()), icon = g.get(icon), dim = !usable(f), check = if (act.multi && act.mode == Mode.OPEN && usable(f)) f in act.marked else null)
            }
        }
    }

    private fun open(i: Int) {
        val f = items.getOrNull(i) ?: return
        if (f.isDirectory) {
            shell.push(DirScreen(act, f))
        } else if (act.mode == Mode.OPEN && usable(f)) {
            if (act.multi) {
                if (!act.marked.add(f)) act.marked.remove(f)
                shell.invalidate()
            } else {
                act.pickFiles(listOf(f))
            }
        }
    }

    override fun onAct(a: Act): Boolean {
        if (items.isEmpty()) return false
        if (a == Act.OK) {
            open(list.sel)
            return true
        }
        return list.act(a, items.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val i = list.hit(shell.ui, RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom), y, items.size)
        if (i < 0) return false
        list.sel = i
        open(i)
        return true
    }

    override fun onScroll(dy: Float) = list.scrollBy(if (dy > 0) 1 else -1, items.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
}
