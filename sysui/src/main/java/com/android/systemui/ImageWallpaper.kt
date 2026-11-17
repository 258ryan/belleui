package com.android.systemui

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import kotlin.math.max

/** Draws the still wallpaper the user picked (centre-cropped), under the name the system expects. */
class ImageWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine = object : Engine() {
        private var bmp: Bitmap? = null
        private var w = 0
        private var h = 0
        private val changed = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                bmp = null
                draw()
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            registerReceiver(changed, IntentFilter(Intent.ACTION_WALLPAPER_CHANGED))
        }

        override fun onDestroy() {
            try {
                unregisterReceiver(changed)
            } catch (_: Throwable) {
            }
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            w = width
            h = height
            draw()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) = draw()

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) draw()
        }

        private fun load(): Bitmap? {
            try {
                val pfd = WallpaperManager.getInstance(this@ImageWallpaper).getWallpaperFile(WallpaperManager.FLAG_SYSTEM) ?: return null
                pfd.use {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, o)
                    var s = 1
                    while (o.outWidth / (s * 2) >= max(w, 1) && o.outHeight / (s * 2) >= max(h, 1)) s *= 2
                    val real = BitmapFactory.Options().apply { inSampleSize = s }
                    return BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, real)
                }
            } catch (_: Throwable) {
                return null
            }
        }

        private fun draw() {
            if (w <= 0 || h <= 0) return
            val holder = surfaceHolder ?: return
            val c = try { holder.lockCanvas() } catch (_: Throwable) { null } ?: return
            try {
                c.drawColor(Color.BLACK)
                val b = bmp ?: load().also { bmp = it }
                if (b != null) {
                    // centre-crop
                    val k = max(w / b.width.toFloat(), h / b.height.toFloat())
                    val sw = (w / k).toInt()
                    val sh = (h / k).toInt()
                    val l = (b.width - sw) / 2
                    val t = (b.height - sh) / 2
                    c.drawBitmap(b, Rect(l, t, l + sw, t + sh), Rect(0, 0, w, h), null)
                }
            } finally {
                holder.unlockCanvasAndPost(c)
            }
        }
    }
}
