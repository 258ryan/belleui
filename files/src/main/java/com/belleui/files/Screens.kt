package com.belleui.files

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.belleui.kit.Act
import com.belleui.kit.Belle
import com.belleui.kit.Ic
import com.belleui.kit.ListDialog
import com.belleui.kit.ListState
import com.belleui.kit.MenuItem
import com.belleui.kit.QueryDialog
import com.belleui.kit.Row
import com.belleui.kit.Screen
import com.belleui.kit.Tool
import com.belleui.kit.Ui
import java.io.File
import java.util.concurrent.Executors

private val GREY = 0xFF58606B.toInt()

private fun rect(s: Screen) = RectF(s.shell.contentLeft, s.shell.contentTop, s.shell.contentRight, s.shell.contentBottom)

private fun rowFor(ui: Ui, f: File, count: Int?, check: Boolean?): Row {
    val g = ui.gfx
    val date = Fs.fmtDate(f.lastModified())
    return when (Fs.kind(f)) {
        "dir" -> Row(f.name, (if (count != null) "$count item${if (count == 1) "" else "s"} - " else "") + date, icon = g.get("qgn_prop_folder_small"), drill = true, check = check)
        "image" -> Row(f.name, Fs.fmtSize(f.length()) + " - " + date, icon = g.get("qgn_prop_file_images_sub"), check = check)
        "audio" -> Row(f.name, Fs.fmtSize(f.length()) + " - " + date, icon = g.get("qgn_prop_file_audio"), check = check)
        "video" -> Row(f.name, Fs.fmtSize(f.length()) + " - " + date, icon = g.get("qgn_prop_file_video"), check = check)
        "apk" -> Row(f.name, Fs.fmtSize(f.length()) + " - " + date, icon = g.get("qgn_prop_folder_install"), check = check)
        else -> Row(f.name, Fs.fmtSize(f.length()) + " - " + date, iconKind = Ic.LIST, iconColor = GREY, check = check)
    }
}

/** Drives and quick places, as on Symbian's File manager home. */
class RootScreen(private val act: FilesActivity) : Screen() {
    override val lightOk: Boolean get() = true
    override val title get() = "File manager"
    private val list = ListState()
    private var volumes: List<Volume> = emptyList()
    private var places: List<Pair<String, File>> = emptyList()
    private class VolInfo(val label: String, val text: String, val frac: Float)
    private var volInfo: List<VolInfo> = emptyList()

    override fun onShow() {
        act.onResumeHook = { reload(); shell.postInvalidate() }
        reload()
    }

    private fun reload() {
        volumes = if (act.hasAccess()) Fs.volumes(act) else emptyList()
        places = if (act.hasAccess()) Fs.places() else emptyList()
        // drive sizes are read once here, not on every frame
        volInfo = volumes.map { v ->
            val total = v.total
            val free = v.free
            VolInfo(v.label, "${Fs.fmtSize(free)} free of ${Fs.fmtSize(total)}", if (total > 0) (total - free).toFloat() / total else 0f)
        }
    }

    override fun menu() = listOf(
        MenuItem("Hidden files: " + if (act.showHidden) "shown" else "hidden") { act.showHidden = !act.showHidden },
        MenuItem("Sort by: " + Fs.sortNames[act.sort]) {
            shell.showOverlay(ListDialog("Sort by", Fs.sortNames, act.sort) { act.sort = it })
        },
    )

    private fun rows(): List<Row> {
        val g = shell.ui.gfx
        val out = ArrayList<Row>()
        for (v in volumes) {
            val used = v.total - v.free
            out.add(Row(v.label, "${Fs.fmtSize(v.free)} free of ${Fs.fmtSize(v.total)}", icon = g.get(if (v.removable) "qgn_prop_mmc_memc_large" else "qgn_prop_phone_memc_large"), drill = true, rightReserve = 0f))
            if (used < 0) continue
        }
        for ((name, f) in places) out.add(Row(name, f.path.substringAfter("/0/", f.path), icon = g.get(when (name) { "Pictures", "Camera" -> "qgn_prop_folder_images"; "Videos" -> "qgn_prop_folder_video"; else -> "qgn_prop_folder_small" }), drill = true))
        return out
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        if (!act.hasAccess()) {
            ui.text(c, "File manager needs access to all files.", r.centerX(), ui.baselineFor(r.centerY() - 14f * ui.sc, 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
            ui.text(c, com.belleui.kit.Keys.hint("Press A to allow."), r.centerX(), ui.baselineFor(r.centerY() + 16f * ui.sc, 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
            return
        }
        val rows = rows()
        // drive rows are drawn blank, then title, free space and a full-size Belle bar are laid over them
        val shown = rows.mapIndexed { i, row -> if (i < volInfo.size) Row("", icon = row.icon) else row }
        list.draw(c, ui, r, shown.size) { shown[it] }
        for ((i, v) in volInfo.withIndex()) {
            val rr = list.rowRect(ui, r, i, ui.tmp)
            if (rr.bottom < r.top || rr.top > r.bottom) continue
            val sc = ui.sc
            val pad = Belle.PADDING * sc
            val tx = rr.left + pad + 62f * sc
            ui.text(c, v.label, tx, rr.top + 28f * sc, 22f * sc, maxW = rr.right - tx - pad - 200f * sc)
            ui.text(c, v.text, rr.right - pad - 8f * sc, rr.top + 27f * sc, 18f * sc, if (i == list.sel) android.graphics.Color.WHITE else Belle.TEXT_MID, Paint.Align.RIGHT, face = ui.light)
            val bar = RectF(tx, rr.top + 38f * sc, rr.right - pad - 8f * sc, rr.top + 58f * sc)
            ui.progressBar(c, bar, v.frac)
        }
    }

    private fun open(i: Int) {
        val n = volumes.size
        val f = if (i < n) volumes[i].root else places.getOrNull(i - n)?.second
        if (f != null) shell.push(FolderScreen(act, f, if (i < n) volumes[i].label else null))
    }

    override fun onAct(a: Act): Boolean {
        if (!act.hasAccess()) {
            if (a == Act.OK) {
                act.askAccess()
                return true
            }
            return false
        }
        if (a == Act.OK || a == Act.RIGHT) {
            open(list.sel)
            return true
        }
        return list.act(a, rows().size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }

    override fun onTap(x: Float, y: Float): Boolean {
        if (!act.hasAccess()) {
            act.askAccess()
            return true
        }
        val i = list.hit(shell.ui, rect(this), y, rows().size)
        if (i < 0) return false
        list.sel = i
        open(i)
        return true
    }

    override fun onScroll(dy: Float) = list.scrollBy(if (dy > 0) 1 else -1, rows().size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
}

private val pool = Executors.newFixedThreadPool(2)

/** One folder. */
class FolderScreen(private val act: FilesActivity, private val dir: File, private val label: String?) : Screen() {
    override val lightOk: Boolean get() = true
    override val title get() = label ?: dir.name.ifEmpty { "/" }
    private val list = ListState()
    private var entries: List<File> = emptyList()
    private val marked = LinkedHashSet<String>()
    private val counts = HashMap<String, Int>()
    private val counting = HashSet<String>()

    override fun onShow() = refresh()

    private fun refresh() {
        entries = Fs.list(dir, act.showHidden, act.sort)
        marked.retainAll(entries.map { it.path }.toSet())
        list.sel = list.sel.coerceIn(0, maxOf(0, entries.size - 1))
        shell.postInvalidate()
    }

    private fun current(): File? = entries.getOrNull(list.sel)
    private fun targets(): List<File> = if (marked.isNotEmpty()) entries.filter { it.path in marked } else listOfNotNull(current())

    override fun tools() = listOf(
        Tool(Ic.CHECK, { toggleMark(true) }),
        if (Clipboard.files.isNotEmpty()) Tool(Ic.PASTE, { paste() }) else Tool(Ic.ADD, { newFolder() }),
    )

    override fun menu(): List<MenuItem> {
        val items = ArrayList<MenuItem>()
        val cur = current()
        if (cur != null) {
            items.add(MenuItem("Open") { open(cur) })
            items.add(MenuItem(if (cur.path in marked) "Unmark" else "Mark") { toggleMark(false) })
            items.add(MenuItem("Mark all") { marked.addAll(entries.map { it.path }) })
            items.add(MenuItem("Copy") { clip(false) })
            items.add(MenuItem("Cut") { clip(true) })
        }
        if (Clipboard.files.isNotEmpty()) items.add(MenuItem("Paste here (${Clipboard.files.size})") { paste() })
        if (cur != null) {
            items.add(MenuItem("Delete") { delete() })
            if (marked.size <= 1) items.add(MenuItem("Rename") { rename(cur) })
            items.add(MenuItem("Details") { details(cur) })
        }
        items.add(MenuItem("New folder") { newFolder() })
        items.add(MenuItem("Sort by: " + Fs.sortNames[act.sort]) {
            shell.showOverlay(ListDialog("Sort by", Fs.sortNames, act.sort) {
                act.sort = it
                refresh()
            })
        })
        items.add(MenuItem("Hidden files: " + if (act.showHidden) "shown" else "hidden") {
            act.showHidden = !act.showHidden
            refresh()
        })
        return items
    }

    // ---- actions --------------------------------------------------------------------------------

    private fun open(f: File) {
        if (f.isDirectory) shell.push(FolderScreen(act, f, null)) else act.openFile(f)?.let { shell.toast(it) }
    }

    private fun toggleMark(advance: Boolean) {
        val f = current() ?: return
        if (!marked.remove(f.path)) marked.add(f.path)
        if (advance && list.sel < entries.size - 1) list.sel++
    }

    private fun clip(cut: Boolean) {
        val t = targets()
        Clipboard.set(t, cut)
        marked.clear()
        shell.toast((if (cut) "Cut " else "Copied ") + "${t.size} item${if (t.size == 1) "" else "s"}")
    }

    private fun paste() {
        val files = Clipboard.files
        val move = Clipboard.cut
        if (files.isEmpty()) return
        shell.toast("Working...")
        pool.execute {
            var ok = 0
            for (f in files) if (Fs.transfer(f, dir, move)) ok++
            if (move) Clipboard.clear()
            shell.post {
                shell.toast(if (ok == files.size) "Pasted $ok item${if (ok == 1) "" else "s"}" else "Pasted $ok of ${files.size}")
                refresh()
            }
        }
    }

    private fun delete() {
        val t = targets()
        if (t.isEmpty()) return
        val msg = if (t.size == 1) "Delete \"${t[0].name}\"?" else "Delete ${t.size} items?"
        shell.showOverlay(QueryDialog(msg, "Delete", "Cancel") {
            pool.execute {
                val ok = t.count { Fs.delete(it) }
                shell.post {
                    marked.clear()
                    shell.toast(if (ok == t.size) "Deleted" else "Couldn't delete ${t.size - ok}")
                    refresh()
                }
            }
        })
    }

    private fun rename(f: File) {
        act.askText("New name", f.name) { n ->
            if (n.contains('/')) shell.toast("Names can't contain /") else {
                val ok = f.renameTo(File(f.parentFile, n))
                shell.toast(if (ok) "Renamed" else "Couldn't rename")
                refresh()
            }
        }
    }

    private fun newFolder() {
        act.askText("Folder name", "New folder") { n ->
            val ok = n.isNotEmpty() && !n.contains('/') && File(dir, n).mkdirs()
            shell.toast(if (ok) "Folder created" else "Couldn't create the folder")
            refresh()
        }
    }

    private fun details(f: File) {
        pool.execute {
            val size = Fs.sizeOf(f)
            val items = if (f.isDirectory) "\n${Fs.countItems(f)} items" else ""
            shell.post {
                shell.showOverlay(QueryDialog("${f.name}\n${Fs.fmtSize(size)}$items\n${Fs.fmtDate(f.lastModified())}\n${f.parent}", "OK", null) { })
            }
        }
    }

    // ---- drawing and input ------------------------------------------------------------------------

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        if (entries.isEmpty()) {
            ui.text(c, "Folder is empty", r.centerX(), ui.baselineFor(r.centerY(), 22f * ui.sc), 22f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
            return
        }
        list.draw(c, ui, r, entries.size) { i ->
            val f = entries[i]
            var n: Int? = null
            if (f.isDirectory) {
                n = counts[f.path]
                if (n == null && counting.add(f.path)) {
                    pool.execute {
                        counts[f.path] = Fs.countItems(f)
                        shell.postInvalidate()
                    }
                }
            }
            rowFor(ui, f, n, if (marked.isNotEmpty()) f.path in marked else null)
        }
        if (Clipboard.files.isNotEmpty()) {
            val msg = (if (Clipboard.cut) "Cut: " else "Copied: ") + "${Clipboard.files.size} - Start pastes here"
            ui.text(c, msg, r.right - 12f * ui.sc, r.bottom - 8f * ui.sc, 14f * ui.sc, Belle.BLUE_LIGHT, Paint.Align.RIGHT, face = ui.light)
        }
    }

    override fun onAct(a: Act): Boolean {
        when (a) {
            Act.BACK -> if (marked.isNotEmpty()) {
                marked.clear()
                return true
            } else return false
            Act.LEFT -> {
                shell.perform(Act.BACK)
                return true
            }
            Act.RIGHT, Act.OK -> {
                current()?.let { open(it) }
                return true
            }
            else -> {}
        }
        if (entries.isEmpty()) return false
        return list.act(a, entries.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val i = list.hit(shell.ui, rect(this), y, entries.size)
        if (i < 0) return false
        list.sel = i
        if (marked.isNotEmpty()) toggleMark(false) else open(entries[i])
        return true
    }

    override fun onLongPress(x: Float, y: Float) {
        val i = list.hit(shell.ui, rect(this), y, entries.size)
        if (i >= 0) {
            list.sel = i
            toggleMark(false)
            shell.invalidate()
        }
    }

    override fun onScroll(dy: Float) = list.scrollBy(if (dy > 0) 1 else -1, entries.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
}
