package com.belleui.notes

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.io.File

/** Offers the latest notes (title, text) read-only, for the launcher's Notes widget. */
class NotesProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(arrayOf("title", "text"))
        val dir = File(context!!.filesDir, "notes")
        val files = (dir.listFiles() ?: emptyArray()).filter { it.extension == "txt" }.sortedByDescending { it.lastModified() }.take(6)
        for (f in files) {
            val text = try { f.readText() } catch (_: Exception) { continue }
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            c.addRow(arrayOf<Any>(lines[0], lines.drop(1).joinToString("\n")))
        }
        return c
    }

    override fun getType(uri: Uri) = "vnd.android.cursor.dir/vnd.belleui.note"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
