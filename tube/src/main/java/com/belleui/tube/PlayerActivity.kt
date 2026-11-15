package com.belleui.tube

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.belleui.kit.Belle
import com.belleui.kit.Ic
import com.belleui.kit.Ui

/**
 * Full-screen player. A / centre: play and pause; left / right: seek 10 s (hold for more); up / down: volume; B: back.
 * Streams over HTTP with Android's own MediaPlayer, so it plays what YouTube serves as one file (mp4, up to 360p/720p).
 */
class PlayerActivity : Activity() {
    private lateinit var surface: SurfaceView
    private lateinit var hud: Hud
    private var mp: MediaPlayer? = null
    private var prepared = false
    private var surfaceReady = false
    private var url = ""
    private var title = ""
    private var audioOnly = false
    private var shownUntil = 0L
    private var note: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val ticker = object : Runnable {
        override fun run() {
            hud.invalidate()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFormat(PixelFormat.TRANSLUCENT)
        url = intent.getStringExtra("url") ?: run { finish(); return }
        title = intent.getStringExtra("title") ?: ""
        audioOnly = intent.getBooleanExtra("audio", false)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        hud = Hud(this)
        root.addView(hud, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        hideBars()
        volumeControlStream = AudioManager.STREAM_MUSIC
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                surfaceReady = true
                if (mp == null) open() else mp?.setDisplay(h)
            }

            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}

            override fun surfaceDestroyed(h: SurfaceHolder) {
                surfaceReady = false
                mp?.setDisplay(null)
            }
        })
        handler.post(ticker)
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    private fun open() {
        val m = MediaPlayer()
        mp = m
        try {
            m.setDataSource(this, Uri.parse(url), mapOf("User-Agent" to TubeDownloader.USER_AGENT))
            if (surfaceReady) m.setDisplay(surface.holder)
            m.setScreenOnWhilePlaying(true)
            m.setOnPreparedListener {
                prepared = true
                it.start()
                show()
            }
            m.setOnCompletionListener { finish() }
            m.setOnErrorListener { _, what, extra ->
                note = "Can't play this (error $what/$extra)"
                shownUntil = Long.MAX_VALUE
                hud.invalidate()
                true
            }
            m.setOnVideoSizeChangedListener { _, w, h -> fit(w, h) }
            m.prepareAsync()
            note = "Loading..."
            shownUntil = Long.MAX_VALUE
        } catch (t: Throwable) {
            note = "Can't play this: ${t.message}"
            shownUntil = Long.MAX_VALUE
        }
    }

    private fun fit(vw: Int, vh: Int) {
        if (vw <= 0 || vh <= 0) return
        val root = surface.parent as View
        val rw = root.width.toFloat()
        val rh = root.height.toFloat()
        val s = minOf(rw / vw, rh / vh)
        surface.layoutParams = FrameLayout.LayoutParams((vw * s).toInt(), (vh * s).toInt(), Gravity.CENTER)
    }

    private fun show() {
        note = null
        shownUntil = System.currentTimeMillis() + 3500
        hud.invalidate()
    }

    private fun toggle() {
        val m = mp ?: return
        if (!prepared) return
        if (m.isPlaying) m.pause() else m.start()
        show()
    }

    private fun seek(ms: Int) {
        val m = mp ?: return
        if (!prepared) return
        m.seekTo((m.currentPosition + ms).coerceIn(0, m.duration))
        show()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> toggle()
            KeyEvent.KEYCODE_DPAD_LEFT -> seek(if (e.repeatCount > 8) -30_000 else -10_000)
            KeyEvent.KEYCODE_DPAD_RIGHT -> seek(if (e.repeatCount > 8) 30_000 else 10_000)
            KeyEvent.KEYCODE_DPAD_UP -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); show() }
            KeyEvent.KEYCODE_DPAD_DOWN -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); show() }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> finish()
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }

    override fun onPause() {
        super.onPause()
        if (mp?.isPlaying == true) mp?.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { mp?.release() } catch (_: Throwable) { }
        mp = null
        super.onDestroy()
    }

    /** The slim control bar: title, a progress bar and the times, shown for a few seconds after any key. */
    private inner class Hud(ctx: Context) : View(ctx) {
        private var ui: Ui? = null
        private val bar = RectF()

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_UP) {
                val sc = ui?.sc ?: 1f
                if (e.y > height - 70f * sc && prepared) {
                    val f = ((e.x - 70f * sc) / (width - 140f * sc)).coerceIn(0f, 1f)
                    mp?.seekTo((f * (mp?.duration ?: 0)).toInt())
                    show()
                } else if (System.currentTimeMillis() < shownUntil) toggle() else show()
            }
            return true
        }

        override fun onDraw(c: Canvas) {
            val u = ui ?: Ui(context).also { it.sc = minOf(width / 640f, height / 480f); ui = it }
            val sc = u.sc
            val m = mp
            val now = System.currentTimeMillis()
            if (audioOnly) {
                u.text(c, title, width / 2f, u.baselineFor(height * 0.42f, 26f * sc), 26f * sc, Color.WHITE, Paint.Align.CENTER, maxW = width - 60f * sc)
                u.glyph(c, Ic.NOTE, width / 2f, height * 0.28f, 64f * sc)
            }
            val n = note
            if (n != null) {
                u.text(c, n, width / 2f, u.baselineFor(height / 2f, 22f * sc), 22f * sc, Color.WHITE, Paint.Align.CENTER, maxW = width - 60f * sc)
                return
            }
            if (now > shownUntil) return
            u.p.style = Paint.Style.FILL
            u.p.shader = null
            u.p.color = 0xB0000000.toInt()
            c.drawRect(0f, 0f, width.toFloat(), 46f * sc, u.p)
            c.drawRect(0f, height - 76f * sc, width.toFloat(), height.toFloat(), u.p)
            u.text(c, title, 16f * sc, u.baselineFor(23f * sc, 22f * sc), 22f * sc, Color.WHITE, maxW = width - 32f * sc)
            val dur = if (prepared && m != null) m.duration else 0
            val pos = if (prepared && m != null) m.currentPosition else 0
            bar.set(70f * sc, height - 42f * sc, width - 70f * sc, height - 24f * sc)
            u.progressBar(c, bar, if (dur > 0) pos.toFloat() / dur else 0f)
            u.text(c, fmt(pos), 14f * sc, u.baselineFor(height - 33f * sc, 17f * sc), 17f * sc, Color.WHITE)
            u.text(c, fmt(dur), width - 14f * sc, u.baselineFor(height - 33f * sc, 17f * sc), 17f * sc, Color.WHITE, Paint.Align.RIGHT)
            u.glyph(c, if (m?.isPlaying == true) Ic.PAUSE else Ic.PLAY, width / 2f, height - 62f * sc, 30f * sc)
            if (now < shownUntil && m?.isPlaying == true) postInvalidateDelayed(500)
        }

        private fun fmt(ms: Int): String {
            val s = ms / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
