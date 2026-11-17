package com.android.systemui.screenshot

import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.provider.MediaStore
import android.util.Log
import android.view.Display
import com.belleui.vector.Hud

/**
 * Takes a screenshot when the system asks (power + volume, the power menu, a hotkey). The system binds this service by name,
 * sends a message and waits for two replies: the saved picture's address (1) and "done" (2).
 */
class TakeScreenshotService : Service() {
    private val worker = Handler(Looper.getMainLooper())
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val reply = msg.replyTo
            Thread {
                val uri = try { capture() } catch (t: Throwable) { Log.e("BelleShot", "capture failed", t); null }
                worker.post {
                    try {
                        if (uri != null) reply?.send(Message.obtain(null, 1, uri))
                        reply?.send(Message.obtain(null, 2))
                    } catch (_: Throwable) { }
                    try {
                        Hud.showNote(this@TakeScreenshotService, this@TakeScreenshotService, if (uri != null) "Screenshot saved" else "Screenshot failed", if (uri != null) "Pictures/Screenshots" else "Could not capture the screen")
                    } catch (_: Throwable) { }
                }
            }.start()
        }
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun capture(): Uri? {
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        d.getRealSize(size)
        // SurfaceControl.screenshot(sourceCrop, width, height, rotation): the whole display, as it is rotated now
        val sc = Class.forName("android.view.SurfaceControl")
        val bmp = sc.getMethod("screenshot", Rect::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(null, Rect(), size.x, size.y, d.rotation) as? Bitmap ?: return null
        val soft = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
        val name = "Screenshot_" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return null
        contentResolver.openOutputStream(uri)?.use { soft.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return uri
    }
}
