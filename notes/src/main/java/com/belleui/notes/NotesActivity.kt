package com.belleui.notes

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import com.belleui.kit.Act
import com.belleui.kit.Belle
import com.belleui.kit.BelleActivity
import com.belleui.kit.BelleShell
import com.belleui.kit.Ic
import com.belleui.kit.ListState
import com.belleui.kit.MenuItem
import com.belleui.kit.QueryDialog
import com.belleui.kit.Row
import com.belleui.kit.Screen
import com.belleui.kit.Tool
import com.belleui.kit.Ui
import java.io.File

class Note(val file: File, val text: String) {
    val modified: Long = file.lastModified()
    val title: String = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "Empty note"
    val preview: String = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.drop(1).joinToString("  ").take(80)
}

/** Notes kept as plain text files, one per note, in the app's own folder. */
class NotesActivity : BelleActivity() {
    private lateinit var field: EditText
    private var editing: File? = null
    private val dir by lazy { File(filesDir, "notes").also { it.mkdirs() } }
    var notes: List<Note> = emptyList()
        private set
    var onChanged: (() -> Unit)? = null

    override fun createRoot(shell: BelleShell): Screen = ListScreen(this)

    override fun buildContent(shell: BelleShell): View {
        val root = FrameLayout(this)
        root.addView(shell, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        field = EditText(this)
        // a pale yellow sticky note, with dark writing
        // the Belle notes paper
        try {
            val bmp = android.graphics.BitmapFactory.decodeStream(assets.open("belle/qgn_graf_hs_notes_bg_8x6.png"))
            field.background = android.graphics.drawable.BitmapDrawable(resources, bmp)
        } catch (_: Exception) {
            field.setBackgroundColor(0xFFFFF4B8.toInt())
        }
        field.setTextColor(0xFF2B2B2B.toInt())
        field.setHintTextColor(0xFF9A936B.toInt())
        field.hint = "Write a note. Press back to save."
        field.gravity = Gravity.TOP or Gravity.START
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        field.setPadding(28, 20, 28, 20)
        field.isSingleLine = false
        root.addView(field, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        field.visibility = View.GONE
        return root
    }

    override fun shellHandlesKeys() = field.visibility != View.VISIBLE

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (field.visibility == View.VISIBLE && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) closeEditor()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun reload() {
        notes = (dir.listFiles() ?: emptyArray()).filter { it.extension == "txt" }
            .map { Note(it, try { it.readText() } catch (_: Exception) { "" }) }
            .sortedByDescending { it.modified }
        onChanged?.invoke()
    }

    fun newNote() = openEditor(File(dir, "note_${System.currentTimeMillis()}.txt"), "")

    fun openEditor(f: File, text: String) {
        editing = f
        field.setText(text)
        field.setSelection(field.text.length)
        field.visibility = View.VISIBLE
        field.requestFocus()
        field.post { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, 0) }
    }

    private fun closeEditor() {
        val f = editing ?: return
        val t = field.text.toString()
        try {
            if (t.isBlank()) f.delete() else f.writeText(t)
        } catch (_: Exception) {
        }
        editing = null
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
        field.visibility = View.GONE
        field.clearFocus()
        shell.requestFocus()
        reload()
        shell.invalidate()
    }

    fun delete(n: Note) {
        n.file.delete()
        reload()
    }

    fun share(n: Note) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, n.text), "Share note"))
    }

    override fun onPause() {
        super.onPause()
        // an open note is kept if the app is left
        if (editing != null) {
            val f = editing!!
            val t = field.text.toString()
            try { if (t.isNotBlank()) f.writeText(t) } catch (_: Exception) { }
        }
    }
}

class ListScreen(private val act: NotesActivity) : Screen() {
    override val lightOk: Boolean get() = true
    override val title get() = "Notes"
    private val list = ListState()

    override fun onShow() {
        act.onChanged = { shell.postInvalidate() }
        act.reload()
    }

    private fun selected(): Note? = act.notes.getOrNull(list.sel.coerceIn(0, maxOf(0, act.notes.size - 1)))

    override fun tools() = listOf(Tool(Ic.ADD, { act.newNote() }))

    override fun menu(): List<MenuItem> {
        val items = ArrayList<MenuItem>()
        items.add(MenuItem("New note") { act.newNote() })
        selected()?.let { n ->
            items.add(MenuItem("Share") { act.share(n) })
            items.add(MenuItem("Delete") {
                shell.showOverlay(QueryDialog("Delete \"${n.title.take(30)}\"?", "Delete", "Cancel") {
                    act.delete(n)
                    shell.toast("Note deleted")
                })
            })
        }
        return items
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        val notes = act.notes
        if (notes.isEmpty()) {
            ui.text(c, "No notes yet", r.centerX(), ui.baselineFor(r.centerY() - 14f * ui.sc, 22f * ui.sc), 22f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
            ui.text(c, "Press Y to write one", r.centerX(), ui.baselineFor(r.centerY() + 16f * ui.sc, 18f * ui.sc, ui.light), 18f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER, face = ui.light)
            return
        }
        val g = ui.gfx
        list.draw(c, ui, r, notes.size) { i ->
            val n = notes[i]
            val when_ = DateUtils.getRelativeTimeSpanString(n.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
            Row(n.title, if (n.preview.isEmpty()) when_ else "$when_ - ${n.preview}", icon = g.get("qgn_menu_note"))
        }
    }

    override fun onAct(a: Act): Boolean {
        if (act.notes.isEmpty()) return false
        if (a == Act.OK) {
            selected()?.let { act.openEditor(it.file, it.text) }
            return true
        }
        return list.act(a, act.notes.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val i = list.hit(shell.ui, RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom), y, act.notes.size)
        if (i < 0) return false
        list.sel = i
        act.notes[i].let { act.openEditor(it.file, it.text) }
        return true
    }

    override fun onScroll(dy: Float) = list.scrollBy(if (dy > 0) 1 else -1, act.notes.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
}
