package com.belleui.video

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.belleui.kit.Belle
import com.belleui.kit.Ic
import com.belleui.kit.Ui
import kotlin.math.abs

/**
 * Full-screen player. A or the centre of the D-pad plays and pauses; left / right seek (hold to go faster);
 * up / down change the volume; L1 / R1 go to the previous / next video; X changes the picture fit; Y the speed;
 * B stops. It remembers where you stopped.
 */
class PlayerActivity : Activity() {
    private lateinit var surface: SurfaceView
    private lateinit var controls: Controls
    private var mp: MediaPlayer? = null
    private var prepared = false
    private var uris: List<String> = emptyList()
    private var titles: List<String> = emptyList()
    private var ids: LongArray = LongArray(0)
    private var index = 0
    private var fit = 0 // 0 fit, 1 fill, 2 stretch
    private var speed = 1f
    private var vw = 0
    private var vh = 0
    private var wantResume = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("video", Context.MODE_PRIVATE) }
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var surfaceReady = false

    private var shownUntil = 0L
    private var seekTarget = -1L
    private var volUntil = 0L
    private var note: String? = null
    private var noteUntil = 0L

    private val saver = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFormat(PixelFormat.TRANSLUCENT)
        val i = intent
        val list = i.getStringArrayExtra("uris")
        if (list != null) {
            uris = list.toList()
            titles = i.getStringArrayExtra("titles")?.toList() ?: list.map { "" }
            ids = i.getLongArrayExtra("ids") ?: LongArray(list.size) { -1 }
            index = i.getIntExtra("index", 0).coerceIn(0, uris.size - 1)
        } else if (i.data != null) {
            uris = listOf(i.data.toString())
            titles = listOf(i.data!!.lastPathSegment ?: "Video")
            ids = longArrayOf(-1)
        } else {
            finish()
            return
        }
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        controls = Controls(this)
        root.addView(controls, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        hideBars()
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
        handler.postDelayed(saver, 5000)
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

    private fun key(i: Int = index) = if (ids.getOrNull(i) ?: -1L >= 0) "p:${ids[i]}" else "pu:${uris[i].hashCode()}"

    private fun open() {
        savePosition()
        mp?.release()
        prepared = false
        vw = 0
        vh = 0
        wantResume = prefs.getLong(key(), 0L)
        val m = MediaPlayer()
        mp = m
        try {
            m.setDataSource(this, Uri.parse(uris[index]))
            m.setDisplay(surface.holder)
            m.setScreenOnWhilePlaying(true)
            m.setOnVideoSizeChangedListener { _, w, h ->
                vw = w
                vh = h
                applyFit()
            }
            m.setOnPreparedListener {
                prepared = true
                val dur = it.duration
                if (wantResume in 5000 until (dur - 10_000)) it.seekTo(wantResume.toInt())
                it.start()
                if (speed != 1f) setSpeed(speed, false)
                show()
            }
            m.setOnCompletionListener {
                prefs.edit().remove(key()).apply()
                if (index < uris.size - 1) next() else finish()
            }
            m.setOnErrorListener { _, _, _ ->
                notice("Can't play this video")
                true
            }
            m.prepareAsync()
        } catch (_: Exception) {
            notice("Can't open this video")
        }
        show()
    }

    private fun applyFit() {
        if (vw <= 0 || vh <= 0) return
        val root = surface.parent as View
        val rw = root.width.toFloat()
        val rh = root.height.toFloat()
        if (rw <= 0f) {
            root.post { applyFit() }
            return
        }
        val scale = when (fit) {
            0 -> minOf(rw / vw, rh / vh)
            1 -> maxOf(rw / vw, rh / vh)
            else -> 0f
        }
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        if (fit == 2) {
            lp.width = rw.toInt()
            lp.height = rh.toInt()
        } else {
            lp.width = (vw * scale).toInt()
            lp.height = (vh * scale).toInt()
        }
        surface.layoutParams = lp
    }

    private fun savePosition() {
        val m = mp ?: return
        if (!prepared) return
        try {
            val p = m.currentPosition.toLong()
            val d = m.duration.toLong()
            val e = prefs.edit()
            if (p > 5000 && p < d - 10_000) e.putLong(key(), p) else e.remove(key())
            e.apply()
        } catch (_: Exception) {
        }
    }

    private fun toggle() {
        val m = mp ?: return
        if (!prepared) return
        if (m.isPlaying) m.pause() else m.start()
        show()
    }

    private fun seekBy(deltaMs: Long) {
        val m = mp ?: return
        if (!prepared) return
        val base = if (seekTarget >= 0) seekTarget else m.currentPosition.toLong()
        seekTarget = (base + deltaMs).coerceIn(0, m.duration.toLong())
        m.seekTo(seekTarget.toInt())
        show()
    }

    private fun next() {
        if (index < uris.size - 1) {
            index++
            open()
        }
    }

    private fun prev() {
        val m = mp
        if (m != null && prepared && m.currentPosition > 4000) {
            m.seekTo(0)
            show()
        } else if (index > 0) {
            index--
            open()
        }
    }

    private fun setSpeed(s: Float, announce: Boolean = true) {
        speed = s
        try {
            val m = mp ?: return
            val wasPlaying = m.isPlaying
            m.playbackParams = PlaybackParams().setSpeed(s)
            if (!wasPlaying && prepared) m.pause()
        } catch (_: Exception) {
        }
        if (announce) notice("Speed ${if (s == 1f) "normal" else "${s}x"}")
    }

    private fun notice(t: String) {
        note = t
        noteUntil = SystemClock.uptimeMillis() + 1600
        show()
    }

    fun show() {
        shownUntil = SystemClock.uptimeMillis() + 3500
        controls.invalidate()
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        val rep = e.repeatCount
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (rep == 0) toggle()
            KeyEvent.KEYCODE_DPAD_LEFT -> seekBy(-(if (rep > 8) 30_000L else if (rep > 2) 20_000L else 10_000L))
            KeyEvent.KEYCODE_DPAD_RIGHT -> seekBy(if (rep > 8) 30_000L else if (rep > 2) 20_000L else 10_000L)
            KeyEvent.KEYCODE_BUTTON_L2 -> if (rep == 0) seekBy(-60_000)
            KeyEvent.KEYCODE_BUTTON_R2 -> if (rep == 0) seekBy(60_000)
            KeyEvent.KEYCODE_DPAD_UP -> volume(true)
            KeyEvent.KEYCODE_DPAD_DOWN -> volume(false)
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (rep == 0) prev()
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_MEDIA_NEXT -> if (rep == 0) next()
            KeyEvent.KEYCODE_BUTTON_X -> if (rep == 0) {
                fit = (fit + 1) % 3
                applyFit()
                notice(arrayOf("Fit to screen", "Fill the screen", "Stretch")[fit])
            }
            KeyEvent.KEYCODE_BUTTON_Y -> if (rep == 0) {
                val steps = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
                val at = steps.indexOfFirst { kotlin.math.abs(it - speed) < 0.01f }
                setSpeed(steps[(at + 1) % steps.size])
            }
            KeyEvent.KEYCODE_BUTTON_START -> show()
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (rep == 0) finish()
            }
            else -> return super.onKeyDown(code, e)
        }
        return true
    }

    override fun onKeyUp(code: Int, e: KeyEvent): Boolean {
        seekTarget = -1
        return super.onKeyUp(code, e)
    }

    private fun volume(up: Boolean) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        volUntil = SystemClock.uptimeMillis() + 1500
        controls.invalidate()
    }

    override fun onPause() {
        super.onPause()
        savePosition()
        try {
            mp?.pause()
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(saver)
        savePosition()
        mp?.release()
        mp = null
        super.onDestroy()
    }

    /** The controls, drawn over the picture in the Belle style and hidden a few seconds after the last key. */
    private inner class Controls(ctx: Context) : View(ctx) {
        private val ui = Ui(ctx)
        private val bar = RectF()
        private val shade = Paint()

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            ui.sc = w / 640f
            val sc = ui.sc
            val now = SystemClock.uptimeMillis()
            val m = mp
            val playing = try { m != null && prepared && m.isPlaying } catch (_: Exception) { false }
            val visible = now < shownUntil || (!playing && prepared)
            if (!prepared && m != null) {
                ui.text(c, "Loading...", w / 2f, ui.baselineFor(h / 2f, 22f * sc), 22f * sc, Color.WHITE, Paint.Align.CENTER)
            }
            if (visible && prepared) {
                // top and bottom shading, so the controls read over any picture
                shade.shader = android.graphics.LinearGradient(0f, 0f, 0f, 80f * sc, 0xCC000000.toInt(), 0, android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, 80f * sc, shade)
                shade.shader = android.graphics.LinearGradient(0f, h - 120f * sc, 0f, h, 0, 0xDD000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(0f, h - 120f * sc, w, h, shade)
                shade.shader = null
                ui.text(c, titles.getOrNull(index) ?: "", 20f * sc, 38f * sc, 22f * sc, Color.WHITE, maxW = w - 40f * sc)
                if (uris.size > 1) ui.text(c, "${index + 1} of ${uris.size}", w - 20f * sc, 38f * sc, 16f * sc, ui.al(Color.WHITE, 180), Paint.Align.RIGHT, face = ui.light)
                val dur = try { m!!.duration.toLong() } catch (_: Exception) { 0L }
                val pos = if (seekTarget >= 0) seekTarget else try { m!!.currentPosition.toLong() } catch (_: Exception) { 0L }
                bar.set(20f * sc, h - 62f * sc, w - 20f * sc, h - 42f * sc)
                ui.progressBar(c, bar, if (dur > 0) pos.toFloat() / dur else 0f)
                ui.text(c, fmtTime(pos), 20f * sc, h - 14f * sc, 18f * sc, Color.WHITE, face = ui.light)
                ui.text(c, fmtTime(dur), w - 20f * sc, h - 14f * sc, 18f * sc, Color.WHITE, Paint.Align.RIGHT, face = ui.light)
                val round = if (playing) "qgn_indi_mup2_button_pause" else "qgn_indi_mup2_button_play"
                if (!ui.gfx.glyph(c, round, w / 2f, h - 22f * sc, 56f * sc)) ui.glyph(c, if (playing) Ic.PAUSE else Ic.PLAY, w / 2f, h - 22f * sc, 40f * sc)
                if (playing) postInvalidateDelayed(500)
            }
            if (now < volUntil) {
                val frac = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxOf(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                val box = RectF(w / 2f - 150f * sc, 90f * sc, w / 2f + 150f * sc, 146f * sc)
                ui.nine(c, "qtg_fr_popup", box, 40, 20f)
                ui.glyph(c, if (frac <= 0f) Ic.AUDIO_OFF else Ic.AUDIO_ON, box.left + 34f * sc, box.centerY(), 34f * sc)
                ui.progressBar(c, RectF(box.left + 64f * sc, box.centerY() - 10f * sc, box.right - 20f * sc, box.centerY() + 10f * sc), frac)
                postInvalidateDelayed(100)
            }
            val n = note
            if (n != null && now < noteUntil) {
                val box = RectF(w / 2f - 130f * sc, h / 2f - 28f * sc, w / 2f + 130f * sc, h / 2f + 28f * sc)
                ui.nine(c, "qtg_fr_popup", box, 40, 20f)
                ui.text(c, n, box.centerX(), ui.baselineFor(box.centerY(), 20f * sc), 20f * sc, Color.WHITE, Paint.Align.CENTER, maxW = box.width() - 24f * sc)
                postInvalidateDelayed(100)
            }
            if (visible && !playing) postInvalidateDelayed(500)
        }
    }
}
