package com.belleui.music

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlin.math.max

class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val uri: Uri,
) {
    // built once, not on every frame of a scrolling list
    val sub: String = "$artist - $album"
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

/** Turns an Android document-tree URI (from the folder picker) into a real path. */
fun treeToPath(uri: Uri): String? {
    val id = try { android.provider.DocumentsContract.getTreeDocumentId(uri) } catch (_: Exception) { return null }
    val parts = id.split(":", limit = 2)
    val vol = parts[0]
    val rel = parts.getOrElse(1) { "" }
    val base = if (vol == "primary") android.os.Environment.getExternalStorageDirectory().path else "/storage/$vol"
    return if (rel.isEmpty()) base else "$base/$rel"
}

/** Music on the device via MediaStore. */
private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "mid", "amr")

class Library(private val ctx: Context) {
    @Volatile
    var tracks: List<Track> = emptyList()
        private set

    private val prefs = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)

    /** Songs shorter than this many seconds are hidden; 0 = show everything. Limited to 0..120. */
    var minSeconds: Int
        get() = prefs.getInt("minSeconds", 0).coerceIn(0, 120)
        set(v) = prefs.edit().putInt("minSeconds", v.coerceIn(0, 120)).apply()

    /** Only music inside these folders is shown; empty = the whole device. */
    var folders: List<String>
        get() = (prefs.getString("folders", "") ?: "").split("\n").filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("folders", v.joinToString("\n")).apply()

    /** Asks Android to index the audio files in the chosen folders, so new or card music shows up. */
    fun indexFolders() {
        val paths = ArrayList<String>()
        fun walk(d: java.io.File, depth: Int) {
            if (depth > 8 || paths.size > 20000) return
            for (f in d.listFiles() ?: return) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) walk(f, depth + 1)
                else if (f.extension.lowercase() in AUDIO_EXTS) paths.add(f.path)
            }
        }
        for (d in folders) walk(java.io.File(d), 0)
        if (paths.isNotEmpty()) android.media.MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
    }

    /** Blocking; call off the main thread. */
    fun load() {
        val out = ArrayList<Track>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
        )
        val dirs = folders
        var where = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ${max(1, minSeconds) * 1000L}"
        val args = ArrayList<String>()
        if (dirs.isNotEmpty()) {
            where += " AND (" + dirs.joinToString(" OR ") { "${MediaStore.Audio.Media.DATA} LIKE ? ESCAPE '\\'" } + ")"
            for (d in dirs) args.add(d.trimEnd('/').replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "/%")
        }
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, where,
                if (args.isEmpty()) null else args.toTypedArray(), "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out.add(
                        Track(
                            id, c.getString(1) ?: "Unknown", c.getString(2)?.takeIf { it != "<unknown>" } ?: "Unknown artist",
                            c.getString(3) ?: "Unknown album", c.getLong(4), c.getLong(5),
                            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
        }
        tracks = out
    }

    fun albums(): List<Pair<String, List<Track>>> =
        tracks.groupBy { it.album }.toList().sortedBy { it.first.lowercase() }

    fun artists(): List<Pair<String, List<Track>>> =
        tracks.groupBy { it.artist }.toList().sortedBy { it.first.lowercase() }
}

/** Album art, loaded off the main thread. */
object ArtCache {
    private val cache = LruCache<Long, Bitmap>(24)
    private val failed = HashSet<Long>()
    private val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun peek(t: Track): Bitmap? = cache.get(t.id)

    fun load(ctx: Context, t: Track, size: Int, done: () -> Unit) {
        if (cache.get(t.id) != null || t.id in failed) return
        pool.execute {
            val bmp = try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ctx.contentResolver.loadThumbnail(t.uri, Size(size, size), null)
                } else {
                    val art = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), t.albumId)
                    ctx.contentResolver.openInputStream(art)?.use { BitmapFactory.decodeStream(it) }
                }
            } catch (_: Exception) {
                null
            }
            if (bmp != null) cache.put(t.id, bmp) else synchronized(failed) { failed.add(t.id) }
            if (bmp != null) main.post(done)
        }
    }
}
