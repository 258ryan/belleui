package com.belleui.gallery

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import java.util.concurrent.Executors
import kotlin.math.max

class Media(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val name: String,
    val mime: String,
    val dateSec: Long,
    val bucketId: Long,
    val bucket: String,
    val width: Int,
    val height: Int,
    val size: Long,
    val durationMs: Long,
) {
    val key: Long get() = id * 2 + if (isVideo) 1 else 0
}

class MediaLibrary(private val ctx: Context) {
    @Volatile
    var items: List<Media> = emptyList()
        private set

    private val prefs = ctx.getSharedPreferences("gallery", Context.MODE_PRIVATE)

    /** Only pictures and videos inside these folders are listed; empty = everything on the device. */
    var folders: List<String>
        get() = (prefs.getString("folders", "") ?: "").split("\n").filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("folders", v.joinToString("\n")).apply()

    private fun where(dataColumn: String): Pair<String?, Array<String>?> {
        val dirs = folders
        if (dirs.isEmpty()) return null to null
        val sel = "(" + dirs.joinToString(" OR ") { "$dataColumn LIKE ? ESCAPE '\\'" } + ")"
        val args = dirs.map { it.trimEnd('/').replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "/%" }
        return sel to args.toTypedArray()
    }

    /** Asks Android to index the pictures and videos in the chosen folders, so new or card files show up. */
    fun indexFolders() {
        val exts = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "mp4", "mkv", "avi", "mov", "3gp", "webm", "m4v")
        val paths = ArrayList<String>()
        fun walk(d: java.io.File, depth: Int) {
            if (depth > 8 || paths.size > 20000) return
            for (f in d.listFiles() ?: return) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) walk(f, depth + 1) else if (f.extension.lowercase() in exts) paths.add(f.path)
            }
        }
        for (d in folders) walk(java.io.File(d), 0)
        if (paths.isNotEmpty()) android.media.MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
    }

    /** Blocking; call off the main thread. */
    fun load() {
        val out = ArrayList<Media>()
        val (imgSel, imgArgs) = where(MediaStore.Images.Media.DATA)
        val (vidSel, vidArgs) = where(MediaStore.Video.Media.DATA)
        try {
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.SIZE,
                ),
                imgSel, imgArgs, "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out.add(
                        Media(
                            c.getLong(0), ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)), false,
                            c.getString(1) ?: "", c.getString(2) ?: "image/*", c.getLong(3), c.getLong(4), c.getString(5) ?: "Unknown",
                            c.getInt(6), c.getInt(7), c.getLong(8), 0,
                        ),
                    )
                }
            }
        } catch (_: Exception) {
        }
        try {
            ctx.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.MIME_TYPE,
                    MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.BUCKET_ID, MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION,
                ),
                vidSel, vidArgs, "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out.add(
                        Media(
                            c.getLong(0), ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)), true,
                            c.getString(1) ?: "", c.getString(2) ?: "video/*", c.getLong(3), c.getLong(4), c.getString(5) ?: "Unknown",
                            c.getInt(6), c.getInt(7), c.getLong(8), c.getLong(9),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
        }
        out.sortByDescending { it.dateSec }
        items = out
    }

    fun albums(): List<Pair<String, List<Media>>> =
        items.groupBy { it.bucketId }.values.map { it.first().bucket to it }.sortedByDescending { it.second.first().dateSec }
}

private val main = Handler(Looper.getMainLooper())

/** Thumbnails, loaded in the background; draw code asks for them and gets null until ready. */
object Thumbs {
    private val cache = object : LruCache<Long, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val pending = HashSet<Long>()
    private val failed = HashSet<Long>()
    private val pool = Executors.newFixedThreadPool(3)

    fun get(ctx: Context, m: Media, onReady: () -> Unit): Bitmap? {
        cache.get(m.key)?.let { return it }
        synchronized(this) {
            if (m.key in pending || m.key in failed) return null
            pending.add(m.key)
        }
        pool.execute {
            val bmp = try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ctx.contentResolver.loadThumbnail(m.uri, Size(256, 256), null)
                } else if (m.isVideo) {
                    @Suppress("DEPRECATION")
                    MediaStore.Video.Thumbnails.getThumbnail(ctx.contentResolver, m.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Thumbnails.getThumbnail(ctx.contentResolver, m.id, MediaStore.Images.Thumbnails.MINI_KIND, null)
                }
            } catch (_: Exception) {
                null
            }
            synchronized(this) {
                pending.remove(m.key)
                if (bmp == null) failed.add(m.key)
            }
            if (bmp != null) {
                // centre-crop to a square so grid cells and list icons never distort it
                val side = minOf(bmp.width, bmp.height)
                val square = if (bmp.width == bmp.height) bmp else Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
                cache.put(m.key, square)
                main.post(onReady)
            }
        }
        return null
    }
}

/** Full-size photos for the viewer, decoded down to screen size with EXIF rotation applied. */
object FullImages {
    private val cache = object : LruCache<Long, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val pending = HashSet<Long>()
    private val pool = Executors.newFixedThreadPool(2)

    fun peek(m: Media): Bitmap? = cache.get(m.key)

    fun load(ctx: Context, m: Media, maxPx: Int, onReady: () -> Unit) {
        if (m.isVideo || cache.get(m.key) != null) return
        synchronized(this) {
            if (m.key in pending) return
            pending.add(m.key)
        }
        pool.execute {
            var bmp: Bitmap? = null
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(m.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                bmp = ctx.contentResolver.openInputStream(m.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                if (bmp != null) {
                    val orientation = try {
                        ctx.contentResolver.openInputStream(m.uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                    } catch (_: Exception) {
                        null
                    } ?: ExifInterface.ORIENTATION_NORMAL
                    val mx = Matrix()
                    when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> mx.postRotate(90f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> mx.postRotate(180f)
                        ExifInterface.ORIENTATION_ROTATE_270 -> mx.postRotate(270f)
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> mx.postScale(-1f, 1f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> mx.postScale(1f, -1f)
                    }
                    if (!mx.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mx, true)
                }
            } catch (_: Throwable) {
                bmp = null
            }
            synchronized(this) { pending.remove(m.key) }
            if (bmp != null) {
                cache.put(m.key, bmp)
                main.post(onReady)
            }
        }
    }
}
