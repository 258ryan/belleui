package com.belleui.video

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import java.util.concurrent.Executors

class Video(
    val id: Long,
    val uri: Uri,
    val name: String,
    val title: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val bucketId: Long,
    val bucket: String,
    val dateSec: Long,
    val size: Long,
) {
    val durText: String = fmtTime(durationMs)
    val detail: String = (if (width > 0 && height > 0) "${width}x$height - " else "") + bucket
}

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val sb = StringBuilder(8)
    if (s >= 3600) sb.append(s / 3600).append(':').append(((s / 60) % 60).let { if (it < 10) "0$it" else "$it" })
    else sb.append(s / 60)
    sb.append(':').append((s % 60).let { if (it < 10) "0$it" else "$it" })
    return sb.toString()
}

class VideoLibrary(private val ctx: Context) {
    @Volatile
    var items: List<Video> = emptyList()
        private set

    /** Resume points, in milliseconds, remembered by the player. */
    private val prefs = ctx.getSharedPreferences("video", Context.MODE_PRIVATE)

    fun resumeAt(v: Video): Long = prefs.getLong("p:${v.id}", 0L)

    var sortByName: Boolean
        get() = prefs.getBoolean("sortName", false)
        set(v) = prefs.edit().putBoolean("sortName", v).apply()

    /** Blocking; call off the main thread. */
    fun load() {
        val out = ArrayList<Video>()
        try {
            ctx.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.BUCKET_ID,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.SIZE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: ""
                    out.add(
                        Video(
                            c.getLong(0), ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)), name,
                            name.substringBeforeLast('.').replace('_', ' ').replace('.', ' ').ifBlank { name },
                            c.getLong(2), c.getInt(3), c.getInt(4), c.getLong(5), c.getString(6) ?: "Unknown", c.getLong(7), c.getLong(8),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
        }
        if (sortByName) out.sortBy { it.title.lowercase() } else out.sortByDescending { it.dateSec }
        items = out
    }

    fun folders(): List<Pair<String, List<Video>>> =
        items.groupBy { it.bucketId }.values.map { it.first().bucket to it }.sortedBy { it.first.lowercase() }

    /** Videos with a saved position, most recent first by when they were added. */
    fun inProgress(): List<Video> = items.filter { resumeAt(it) > 5000 && resumeAt(it) < it.durationMs - 10_000 }
}

private val main = Handler(Looper.getMainLooper())

/** Video thumbnails, loaded in the background; draw code gets null until one is ready. */
object Thumbs {
    private val cache = object : LruCache<Long, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val pending = HashSet<Long>()
    private val failed = HashSet<Long>()
    private val pool = Executors.newFixedThreadPool(2)

    fun get(ctx: Context, v: Video, onReady: () -> Unit): Bitmap? {
        cache.get(v.id)?.let { return it }
        synchronized(this) {
            if (v.id in pending || v.id in failed) return null
            pending.add(v.id)
        }
        pool.execute {
            val bmp = try {
                if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(v.uri, Size(256, 256), null)
                else @Suppress("DEPRECATION") MediaStore.Video.Thumbnails.getThumbnail(ctx.contentResolver, v.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
            } catch (_: Exception) {
                null
            }
            synchronized(this) {
                pending.remove(v.id)
                if (bmp == null) failed.add(v.id)
            }
            if (bmp != null) {
                cache.put(v.id, bmp)
                main.post(onReady)
            }
        }
        return null
    }
}
