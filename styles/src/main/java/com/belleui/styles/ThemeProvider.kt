package com.belleui.styles

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Offers the theme colour and the navigation-sound switch to the other Belle apps (read-only).
 * "accent" is 0 until a colour has been chosen, so apps keep their own look until then.
 */
class ThemeProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val p = context!!.getSharedPreferences("styles", android.content.Context.MODE_PRIVATE)
        val c = MatrixCursor(arrayOf("accent", "sounds"))
        c.addRow(arrayOf<Any>(p.getInt("accent", 0), if (p.getBoolean("sounds", true)) 1 else 0))
        return c
    }

    override fun getType(uri: Uri) = "vnd.android.cursor.item/vnd.belleui.theme"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
