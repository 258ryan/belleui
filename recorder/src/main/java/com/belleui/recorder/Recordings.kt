package com.belleui.recorder

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Recording(val id: Long, val uri: Uri, val name: String, val durationMs: Long, val dateSec: Long) {
    val title: String = name.substringBeforeLast('.')
    val durText: String = fmtTime(durationMs)
}

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val sb = StringBuilder(8)
    if (s >= 3600) sb.append(s / 3600).append(':').append(((s / 60) % 60).let { if (it < 10) "0$it" else "$it" })
    else sb.append(s / 60)
    sb.append(':').append((s % 60).let { if (it < 10) "0$it" else "$it" })
    return sb.toString()
}

/** Where recordings go, how they are written, and how they are listed. */
class RecordingStore(private val ctx: Context) {
    // Android 12 added a Recordings folder; before that they live in Music/Recordings
    private val relPath: String get() = if (Build.VERSION.SDK_INT >= 31) "Recordings/BelleUI" else "Music/Recordings"

    class Session(val uri: Uri?, val file: File?, val pfd: ParcelFileDescriptor?)

    private fun newName(): String = "Recording " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date()) + ".m4a"

    /** A place to write the next recording, and the recorder pointed at it. */
    fun begin(rec: MediaRecorder): Session {
        val name = newName()
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, relPath)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException("no storage")
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "w") ?: throw IllegalStateException("no storage")
            rec.setOutputFile(pfd.fileDescriptor)
            return Session(uri, null, pfd)
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Recordings").also { it.mkdirs() }
        val f = File(dir, name)
        rec.setOutputFile(f.path)
        return Session(null, f, null)
    }

    fun finish(s: Session, keep: Boolean) {
        try { s.pfd?.close() } catch (_: Exception) { }
        if (s.uri != null) {
            if (keep) {
                ctx.contentResolver.update(s.uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            } else {
                ctx.contentResolver.delete(s.uri, null, null)
            }
        } else if (s.file != null) {
            if (keep) android.media.MediaScannerConnection.scanFile(ctx, arrayOf(s.file.path), arrayOf("audio/mp4"), null) else s.file.delete()
        }
    }

    fun list(): List<Recording> {
        val out = ArrayList<Recording>()
        val (sel, args) = if (Build.VERSION.SDK_INT >= 29)
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?" to arrayOf("Recordings/%", "Music/Recordings/%")
        else "${MediaStore.Audio.Media.DATA} LIKE ?" to arrayOf("%/Recordings/%")
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED),
                sel, args, "${MediaStore.Audio.Media.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out.add(Recording(c.getLong(0), ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)), c.getString(1) ?: "", c.getLong(2), c.getLong(3)))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Rename sound (voicerecorder.r01). */
    fun rename(r: Recording, title: String): Boolean = try {
        val n = title.trim().replace('/', ' ')
        if (n.isEmpty()) false
        else ctx.contentResolver.update(r.uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$n.m4a") }, null, null) > 0
    } catch (_: Exception) {
        false
    }

    fun delete(r: Recording): Boolean = try {
        ctx.contentResolver.delete(r.uri, null, null) > 0
    } catch (_: Exception) {
        false
    }
}
