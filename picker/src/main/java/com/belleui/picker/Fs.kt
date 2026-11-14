package com.belleui.picker

import android.content.Context
import android.os.Environment
import android.webkit.MimeTypeMap
import java.io.File
import java.text.DateFormat
import java.util.Date

class Volume(val label: String, val root: File)

object Fs {
    fun fmtSize(b: Long): String = when {
        b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
        b >= 1_000_000 -> "%.1f MB".format(b / 1e6)
        b >= 1_000 -> "%.0f kB".format(b / 1e3)
        else -> "$b B"
    }

    fun fmtDate(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    /** Phone memory first, then any memory card or USB drive. */
    fun volumes(ctx: Context): List<Volume> {
        val out = ArrayList<Volume>()
        out.add(Volume("Phone memory", Environment.getExternalStorageDirectory()))
        try {
            val dirs = ctx.getExternalFilesDirs(null)
            for (i in 1 until dirs.size) {
                val d = dirs[i] ?: continue
                val root = File(d.path.substringBefore("/Android/data"))
                if (root.isDirectory && out.none { it.root.path == root.path }) out.add(Volume(if (out.size == 1) "Memory card" else "Memory card ${out.size}", root))
            }
        } catch (_: Exception) {
        }
        // anything else mounted under /storage (USB drives and the like)
        try {
            for (d in File("/storage").listFiles() ?: emptyArray()) {
                if (d.name == "emulated" || d.name == "self" || !d.isDirectory || !d.canRead()) continue
                if (out.none { it.root.path == d.path }) out.add(Volume("Drive ${d.name}", d))
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun list(dir: File, hidden: Boolean): List<File> {
        val all = try { dir.listFiles() } catch (_: SecurityException) { null } ?: return emptyList()
        return all.filter { hidden || !it.name.startsWith(".") }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun mime(f: File): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"

    /** True if [mime] satisfies one of the file types the calling app asked for. */
    fun matches(mime: String, filters: List<String>): Boolean {
        if (filters.isEmpty()) return true
        return filters.any { f ->
            f == "*/*" || f == mime || (f.endsWith("/*") && mime.startsWith(f.dropLast(1)))
        }
    }

    fun kind(f: File): String {
        if (f.isDirectory) return "dir"
        val m = mime(f)
        return when {
            m.startsWith("image/") -> "image"
            m.startsWith("audio/") -> "audio"
            m.startsWith("video/") -> "video"
            else -> "file"
        }
    }
}
