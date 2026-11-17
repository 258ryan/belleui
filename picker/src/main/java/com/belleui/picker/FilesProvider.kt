package com.belleui.picker

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * Shows the shared storage (phone memory, cards, drives) to other apps as documents. Nothing is reachable
 * without a grant: the picker hands one out when you choose a folder or file, which is how the system file
 * picker works too. Document ids are plain paths, so an app (or a script) can turn one back into a path.
 */
class FilesProvider : DocumentsProvider() {
    companion object {
        const val AUTHORITY = "com.belleui.picker.documents"
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES)
        private val DOC_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
    }

    override fun onCreate() = true

    private fun volumes() = Fs.volumes(context!!)

    /** Only places on shared storage may be reached, never the app data of this or any other app. */
    private fun fileFor(id: String): File {
        val f = File(id)
        val canon = try { f.canonicalFile } catch (_: Exception) { throw FileNotFoundException("bad path") }
        if (volumes().none { canon.path == it.root.canonicalPath || canon.path.startsWith(it.root.canonicalPath + "/") }) throw FileNotFoundException("outside storage")
        return canon
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: ROOT_COLUMNS)
        for (v in volumes()) {
            c.newRow()
                .add(Root.COLUMN_ROOT_ID, v.root.path)
                .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
                .add(Root.COLUMN_ICON, context!!.applicationInfo.icon)
                .add(Root.COLUMN_TITLE, v.label)
                .add(Root.COLUMN_DOCUMENT_ID, v.root.path)
                .add(Root.COLUMN_AVAILABLE_BYTES, try { v.root.usableSpace } catch (_: Exception) { 0L })
        }
        return c
    }

    private fun addRow(c: MatrixCursor, f: File) {
        val dir = f.isDirectory
        var flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        flags = flags or if (dir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        val root = volumes().firstOrNull { it.root.path == f.path }
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, f.path)
            .add(Document.COLUMN_DISPLAY_NAME, root?.label ?: f.name)
            .add(Document.COLUMN_SIZE, if (dir) null else f.length())
            .add(Document.COLUMN_MIME_TYPE, if (dir) Document.MIME_TYPE_DIR else Fs.mime(f))
            .add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            .add(Document.COLUMN_FLAGS, flags)
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val f = fileFor(documentId)
        if (!f.exists()) throw FileNotFoundException(documentId)
        return MatrixCursor(projection ?: DOC_COLUMNS).also { addRow(it, f) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val dir = fileFor(parentDocumentId)
        val c = MatrixCursor(projection ?: DOC_COLUMNS)
        for (f in dir.listFiles() ?: emptyArray()) addRow(c, f)
        return c
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun openDocumentThumbnail(documentId: String, sizeHint: android.graphics.Point?, signal: CancellationSignal?): AssetFileDescriptor? = null

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val dir = fileFor(parentDocumentId)
        var f = File(dir, displayName)
        var n = 1
        // never overwrite: "name (1).ext"
        while (f.exists()) {
            val base = displayName.substringBeforeLast('.', displayName)
            val ext = if ('.' in displayName) "." + displayName.substringAfterLast('.') else ""
            f = File(dir, "$base ($n)$ext")
            n++
        }
        fileFor(f.parentFile!!.path)
        if (mimeType == Document.MIME_TYPE_DIR) f.mkdir() else f.createNewFile()
        return f.path
    }

    override fun deleteDocument(documentId: String) {
        val f = fileFor(documentId)
        if (volumes().any { it.root.path == f.path }) throw FileNotFoundException("not a folder you can delete")
        if (!(if (f.isDirectory) f.deleteRecursively() else f.delete())) throw FileNotFoundException("could not delete")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val f = fileFor(documentId)
        val to = File(f.parentFile, displayName)
        fileFor(to.parentFile!!.path)
        if (to.exists() || !f.renameTo(to)) throw FileNotFoundException("could not rename")
        return to.path
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        val p = fileFor(parentDocumentId).path
        val d = fileFor(documentId).path
        d == p || d.startsWith("$p/")
    } catch (_: Exception) {
        false
    }

    override fun getDocumentType(documentId: String): String {
        val f = fileFor(documentId)
        return if (f.isDirectory) Document.MIME_TYPE_DIR else Fs.mime(f)
    }
}
