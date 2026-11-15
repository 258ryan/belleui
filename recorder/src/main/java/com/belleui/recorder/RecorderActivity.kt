package com.belleui.recorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.belleui.kit.Act
import com.belleui.kit.Belle
import com.belleui.kit.BelleActivity
import com.belleui.kit.BelleShell
import com.belleui.kit.Ic
import com.belleui.kit.ListDialog
import com.belleui.kit.ListState
import com.belleui.kit.MenuItem
import com.belleui.kit.QueryDialog
import com.belleui.kit.Row
import com.belleui.kit.Screen
import com.belleui.kit.Sounds
import com.belleui.kit.TabDef
import com.belleui.kit.Tool
import com.belleui.kit.Ui

class RecorderActivity : BelleActivity() {
    val store by lazy { RecordingStore(this) }
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val handler = Handler(Looper.getMainLooper())

    private var rec: MediaRecorder? = null
    private var session: RecordingStore.Session? = null
    private var startedAt = 0L
    var recording = false
        private set
    var level = 0f
        private set
    val history = FloatArray(40)
    var choice = 0 // which of the inputs is used
    var onListChanged: (() -> Unit)? = null

    override fun createRoot(shell: BelleShell): Screen = RootScreen(this)

    val elapsed: Long get() = if (recording) SystemClock.elapsedRealtime() - startedAt else 0L

    fun inputs() = Mic.inputs(this)

    fun current(): Input? = inputs().let { it.getOrNull(choice.coerceAtMost(it.size - 1)) }

    private val meter = object : Runnable {
        override fun run() {
            val r = rec ?: return
            val a = try { r.maxAmplitude } catch (_: Exception) { 0 }
            level = (a / 32767f).coerceIn(0f, 1f).let { Math.sqrt(it.toDouble()).toFloat() }
            System.arraycopy(history, 1, history, 0, history.size - 1)
            history[history.size - 1] = level
            shell.invalidate()
            handler.postDelayed(this, 70)
        }
    }

    /** Starts recording from the chosen microphone; [done] gets an error message or null. */
    fun start(done: (String?) -> Unit) {
        if (recording) return
        val input = current()
        if (input == null) {
            done("Plug in a headset with a microphone")
            return
        }
        requestPerms(arrayOf(Manifest.permission.RECORD_AUDIO)) { ok ->
            if (!ok) {
                done("Recording needs the microphone permission")
                return@requestPerms
            }
            if (input.bluetooth) {
                // a Bluetooth headset's microphone needs the voice link switched on first
                try {
                    audio.startBluetoothSco()
                    audio.isBluetoothScoOn = true
                } catch (_: Exception) {
                }
                handler.postDelayed({ begin(input, done) }, 1200)
            } else {
                begin(input, done)
            }
        }
    }

    private fun begin(input: Input, done: (String?) -> Unit) {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96_000)
            r.setAudioSamplingRate(44_100)
            r.setAudioChannels(1)
            if (Build.VERSION.SDK_INT >= 28 && input.info != null) r.setPreferredDevice(input.info)
            val s = store.begin(r)
            session = s
            r.prepare()
            r.start()
            rec = r
            recording = true
            startedAt = SystemClock.elapsedRealtime()
            java.util.Arrays.fill(history, 0f)
            handler.post(meter)
            done(null)
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) { }
            session?.let { store.finish(it, false) }
            session = null
            stopSco()
            done("Couldn't start recording")
        }
    }

    private fun stopSco() {
        try {
            audio.isBluetoothScoOn = false
            audio.stopBluetoothSco()
        } catch (_: Exception) {
        }
    }

    fun stop(): Boolean {
        val r = rec ?: return false
        handler.removeCallbacks(meter)
        var ok = true
        try {
            r.stop()
        } catch (_: Exception) {
            ok = false // stopped almost at once: nothing worth keeping
        }
        r.release()
        rec = null
        recording = false
        level = 0f
        session?.let { store.finish(it, ok) }
        session = null
        stopSco()
        onListChanged?.invoke()
        return ok
    }

    fun share(r: Recording) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, r.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share recording"))
    }

    fun audioPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    override fun onPause() {
        super.onPause()
        // a recording in progress is kept when the app is left
        if (recording) stop()
    }
}

class RootScreen(private val act: RecorderActivity) : Screen() {
    override val lightOk: Boolean get() = true
    override val title get() = "Voice recorder"
    override val tabs = listOf(TabDef("Record", Ic.AUDIO_ON), TabDef("Recordings", Ic.LIST))
    override val tickMs get() = 500L

    private val list = ListState()
    private var items: List<Recording> = emptyList()
    private var loaded = false
    private var player: MediaPlayer? = null
    private var playingId = -1L
    private var note: String? = null

    override fun onShow() {
        act.onListChanged = { loadList() }
        loadList()
    }

    private fun loadList() {
        act.requestPerms(act.audioPerms()) { _ ->
            Thread {
                items = act.store.list()
                loaded = true
                shell.postInvalidate()
            }.start()
        }
    }

    private fun stopPlay() {
        try { player?.release() } catch (_: Exception) { }
        player = null
        playingId = -1
    }

    private fun play(r: Recording) {
        if (playingId == r.id) {
            stopPlay()
            shell.invalidate()
            return
        }
        stopPlay()
        try {
            val m = MediaPlayer()
            m.setDataSource(act, r.uri)
            m.setOnCompletionListener { stopPlay(); shell.postInvalidate() }
            m.prepare()
            m.start()
            player = m
            playingId = r.id
        } catch (_: Exception) {
            shell.toast("Can't play that recording")
        }
        shell.invalidate()
    }

    private fun toggleRecord() {
        if (act.recording) {
            val ok = act.stop()
            shell.toast(if (ok) "Recording saved" else "Too short - not saved")
        } else {
            stopPlay()
            act.start { err ->
                if (err != null) shell.toast(err)
                shell.invalidate()
            }
        }
        shell.invalidate()
    }

    private fun selected(): Recording? = items.getOrNull(list.sel.coerceIn(0, maxOf(0, items.size - 1)))

    override fun tools(): List<Tool> =
        if (tab == 0) listOf(Tool(if (act.recording) Ic.STOP else Ic.AUDIO_ON, { toggleRecord() }))
        else listOf(Tool(if (playingId >= 0) Ic.STOP else Ic.PLAY, { selected()?.let { play(it) } }, selected() != null))

    override fun menu(): List<MenuItem> {
        val m = ArrayList<MenuItem>()
        if (tab == 0) {
            m.add(MenuItem("Choose microphone") { chooseInput() })
        } else {
            selected()?.let { r ->
                m.add(MenuItem("Share") { act.share(r) })
                m.add(MenuItem("Delete") {
                    shell.showOverlay(QueryDialog("Delete \"${r.title}\"?", "Delete", "Cancel") {
                        if (playingId == r.id) stopPlay()
                        shell.toast(if (act.store.delete(r)) "Recording deleted" else "Couldn't delete it")
                        loadList()
                    })
                })
            }
        }
        return m
    }

    private fun chooseInput() {
        val ins = act.inputs()
        if (ins.isEmpty()) {
            shell.toast("No microphone found - plug in a headset")
            return
        }
        shell.showOverlay(ListDialog("Microphone", ins.map { it.name }, act.choice.coerceAtMost(ins.size - 1)) { act.choice = it })
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        if (tab == 0) drawRecord(c, ui, r) else drawList(c, ui, r)
    }

    private fun drawRecord(c: Canvas, ui: Ui, r: RectF) {
        val sc = ui.sc
        val input = act.current()
        // microphone line
        val txt = if (input == null) "No microphone - plug in a headset" else "Microphone: ${input.name}"
        ui.text(c, txt, r.centerX(), r.top + 30f * sc, 18f * sc, if (input == null) 0xFFE5844D.toInt() else Belle.TEXT_MID, Paint.Align.CENTER, maxW = r.width() - 30f * sc, face = ui.light)
        // time
        ui.text(c, fmtTime(act.elapsed), r.centerX(), r.top + 110f * sc, 56f * sc, Color.WHITE, Paint.Align.CENTER)
        // level history
        val meter = RectF(r.left + 40f * sc, r.top + 135f * sc, r.right - 40f * sc, r.top + 215f * sc)
        ui.p.style = Paint.Style.FILL
        ui.p.shader = null
        ui.p.color = Belle.PANEL
        c.drawRoundRect(meter, 8f * sc, 8f * sc, ui.p)
        val n = act.history.size
        val bw = (meter.width() - 16f * sc) / n
        ui.p.color = if (act.recording) Belle.BLUE_LIGHT else ui.al(Color.WHITE, 60)
        for (i in 0 until n) {
            val hgt = maxOf(3f * sc, act.history[i] * (meter.height() - 14f * sc))
            c.drawRoundRect(meter.left + 8f * sc + i * bw + 1f * sc, meter.centerY() - hgt / 2f, meter.left + 8f * sc + (i + 1) * bw - 1f * sc, meter.centerY() + hgt / 2f, 2f * sc, 2f * sc, ui.p)
        }
        // the big button
        val btn = RectF(r.centerX() - 90f * sc, r.bottom - 84f * sc, r.centerX() + 90f * sc, r.bottom - 24f * sc)
        ui.pushButton(c, btn, if (act.recording) "Stop" else "Record", true)
        if (act.recording) {
            ui.p.color = 0xFFE5333A.toInt()
            c.drawCircle(r.left + 50f * sc, r.top + 80f * sc, 8f * sc, ui.p)
        }
    }

    private fun drawList(c: Canvas, ui: Ui, r: RectF) {
        if (!loaded) {
            ui.text(c, "Loading...", r.centerX(), ui.baselineFor(r.centerY(), 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
            return
        }
        if (items.isEmpty()) {
            ui.text(c, "No recordings yet", r.centerX(), ui.baselineFor(r.centerY(), 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
            return
        }
        val g = ui.gfx
        list.draw(c, ui, r, items.size) { i ->
            val it = items[i]
            Row(it.title, if (playingId == it.id) "Playing - press A to stop" else "Press A to play", icon = g.get("qgn_menu_voirec"), right = it.durText)
        }
    }

    override fun onAct(a: Act): Boolean {
        if (tab == 0) {
            if (a == Act.OK) {
                toggleRecord()
                return true
            }
            if (a == Act.LEFT || a == Act.RIGHT) {
                chooseInput()
                return true
            }
            return false
        }
        if (items.isEmpty()) return false
        if (a == Act.OK) {
            selected()?.let { play(it) }
            return true
        }
        return list.act(a, items.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }

    override fun onTap(x: Float, y: Float): Boolean {
        if (tab == 0) {
            toggleRecord()
            return true
        }
        val i = list.hit(shell.ui, RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom), y, items.size)
        if (i < 0) return false
        list.sel = i
        play(items[i])
        return true
    }

    override fun onScroll(dy: Float) = list.scrollBy(if (dy > 0) 1 else -1, items.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
}
