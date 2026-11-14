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

class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val uri: Uri,
)

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) {
        String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    } else {
        String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }
}

/** Music on the device via MediaStore. */
class Library(private val ctx: Context) {
    @Volatile
    var tracks: List<Track> = emptyList()
        private set

    /** Blocking; call off the main thread. */
    fun load() {
        val out = ArrayList<Track>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
        )
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 10000",
                null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE",
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

    fun peek(t: Track): Bitmap? = cache.get(t.id)

    fun load(ctx: Context, t: Track, size: Int, done: () -> Unit) {
        if (cache.get(t.id) != null || t.id in failed) return
        Thread {
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
            if (bmp != null) android.os.Handler(android.os.Looper.getMainLooper()).post(done)
        }.start()
    }
}
