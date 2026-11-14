package com.belleui.files

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.webkit.MimeTypeMap
import java.io.File
import java.text.DateFormat
import java.util.Date

/** What the user has copied or cut, waiting to be pasted. */
object Clipboard {
    var files: List<File> = emptyList()
    var cut = false
    fun set(f: List<File>, cut: Boolean) {
        files = f
        this.cut = cut
    }
    fun clear() {
        files = emptyList()
    }
}

class Volume(val label: String, val root: File, val removable: Boolean) {
    val total: Long get() = try { StatFs(root.path).totalBytes } catch (_: Exception) { 0L }
    val free: Long get() = try { StatFs(root.path).availableBytes } catch (_: Exception) { 0L }
}

object Fs {
    const val SORT_NAME = 0
    const val SORT_DATE = 1
    const val SORT_SIZE = 2
    const val SORT_TYPE = 3
    val sortNames = listOf("Name", "Date (newest first)", "Size (largest first)", "Type")

    fun fmtSize(b: Long): String = when {
        b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
        b >= 1_000_000 -> "%.1f MB".format(b / 1e6)
        b >= 1_000 -> "%.0f kB".format(b / 1e3)
        else -> "$b B"
    }

    fun fmtDate(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    fun volumes(ctx: Context): List<Volume> {
        val out = ArrayList<Volume>()
        val primary = Environment.getExternalStorageDirectory()
        out.add(Volume("Phone memory", primary, false))
        try {
            // other volumes show up as extra app-specific dirs: strip the Android/data part to get the card root
            val dirs = ctx.getExternalFilesDirs(null)
            for (i in 1 until dirs.size) {
                val d = dirs[i] ?: continue
                val root = File(d.path.substringBefore("/Android/data"))
                if (root.isDirectory && out.none { it.root.path == root.path }) {
                    out.add(Volume(if (out.size == 1) "Memory card" else "Memory card ${out.size}", root, true))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun places(): List<Pair<String, File>> {
        fun p(type: String) = Environment.getExternalStoragePublicDirectory(type)
        return listOf(
            "Downloads" to p(Environment.DIRECTORY_DOWNLOADS), "Pictures" to p(Environment.DIRECTORY_PICTURES),
            "Camera" to p(Environment.DIRECTORY_DCIM), "Music" to p(Environment.DIRECTORY_MUSIC),
            "Videos" to p(Environment.DIRECTORY_MOVIES), "Documents" to p(Environment.DIRECTORY_DOCUMENTS),
        ).filter { it.second.isDirectory }
    }

    fun list(dir: File, hidden: Boolean, sort: Int): List<File> {
        val all = try { dir.listFiles() } catch (_: SecurityException) { null } ?: return emptyList()
        val files = all.filter { hidden || !it.name.startsWith(".") }
        val cmp: Comparator<File> = when (sort) {
            SORT_DATE -> compareByDescending<File> { it.lastModified() }
            SORT_SIZE -> compareByDescending<File> { if (it.isDirectory) -1L else it.length() }
            SORT_TYPE -> compareBy<File> { it.extension.lowercase() }.thenBy { it.name.lowercase() }
            else -> compareBy<File> { it.name.lowercase() }
        }
        return files.sortedWith(compareByDescending<File> { it.isDirectory }.then(cmp))
    }

    fun mime(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

    fun kind(f: File): String {
        if (f.isDirectory) return "dir"
        val m = mime(f)
        return when {
            m.startsWith("image/") -> "image"
            m.startsWith("audio/") -> "audio"
            m.startsWith("video/") -> "video"
            f.extension.equals("apk", true) -> "apk"
            else -> "file"
        }
    }

    fun sizeOf(f: File): Long {
        if (f.isFile) return f.length()
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.add(f)
        var n = 0
        while (stack.isNotEmpty() && n < 200_000) {
            val d = stack.removeLast()
            for (c in d.listFiles() ?: continue) {
                n++
                if (c.isDirectory) stack.add(c) else total += c.length()
            }
        }
        return total
    }

    fun countItems(d: File): Int = try { d.list()?.size ?: 0 } catch (_: Exception) { 0 }

    fun delete(f: File): Boolean = if (f.isDirectory) f.deleteRecursively() else f.delete()

    private fun freeName(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (target.exists()) {
            target = File(dir, "$base ($i)$ext")
            i++
        }
        return target
    }

    /** Copies (or moves) [src] into [dir]; returns false on failure. Never overwrites. */
    fun transfer(src: File, dir: File, move: Boolean): Boolean {
        if (src.isDirectory && dir.path.startsWith(src.path)) return false // folder into itself
        val target = freeName(dir, src.name)
        if (move && src.renameTo(target)) return true
        val ok = if (src.isDirectory) src.copyRecursively(target, overwrite = false) { _, _ -> kotlin.io.OnErrorAction.SKIP } else {
            try {
                src.copyTo(target, overwrite = false)
                true
            } catch (_: Exception) {
                false
            }
        }
        if (ok && move) delete(src)
        return ok
    }
}
