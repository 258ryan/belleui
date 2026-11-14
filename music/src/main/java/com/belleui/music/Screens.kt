package com.belleui.music

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.os.SystemClock
import com.belleui.kit.Act
import com.belleui.kit.Belle
import com.belleui.kit.Ic
import com.belleui.kit.ListState
import com.belleui.kit.MenuItem
import com.belleui.kit.Row
import com.belleui.kit.Screen
import com.belleui.kit.TabDef
import com.belleui.kit.Tool
import com.belleui.kit.Ui
import kotlin.math.max
import kotlin.math.min

private const val MAGENTA = 0xFFE9338A.toInt()
private const val ORANGE = 0xFFE8761A.toInt()
private const val GREEN = 0xFF4C9A2E.toInt()

private fun playPauseTool(onClick: () -> Unit) =
    Tool(if (Player.playing) Ic.PAUSE else Ic.PLAY, onClick, Player.current != null)

/** Songs / Albums / Artists, as Belle tabs. */
class RootScreen(private val act: MusicActivity) : Screen() {
    override val title get() = "Music"
    override val tabs = listOf(TabDef("Songs", Ic.NOTE), TabDef("Albums", Ic.DISC), TabDef("Artists", Ic.PERSON))

    private val lists = Array(3) { ListState() }
    private var state = 0 // 0 loading, 1 ready, 2 access denied
    private var albums: List<Pair<String, List<Track>>> = emptyList()
    private var artists: List<Pair<String, List<Track>>> = emptyList()
    private var started = false

    override fun onShow() {
        if (!started) {
            started = true
            request()
        }
        Player.listeners.add { shell.postInvalidate() }
    }

    private fun request() {
        state = 0
        act.ensureAccess { ok ->
            if (!ok) {
                state = 2
                shell.invalidate()
            } else {
                Thread {
                    act.library.load()
                    albums = act.library.albums()
                    artists = act.library.artists()
                    shell.post {
                        state = 1
                        shell.invalidate()
                    }
                }.start()
            }
        }
    }

    private fun count() = when (tab) {
        0 -> act.library.tracks.size
        1 -> albums.size
        else -> artists.size
    }

    override fun tools(): List<Tool> = listOf(
        Tool(Ic.NOWPLAY, { openNowPlaying() }, Player.current != null),
        playPauseTool { Player.toggle() },
    )

    override fun menu(): List<MenuItem> {
        val items = ArrayList<MenuItem>()
        if (state == 1 && act.library.tracks.isNotEmpty()) {
            items.add(MenuItem("Shuffle all") {
                Player.shuffle = true
                val all = act.library.tracks
                Player.playQueue(act, all, (all.indices).random())
                openNowPlaying()
            })
        }
        items.add(MenuItem("Rescan library") { request() })
        return items
    }

    private fun openNowPlaying() {
        if (Player.current != null) shell.push(NowPlayingScreen(act))
        else shell.toast("Nothing is playing")
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        when (state) {
            0 -> message(c, ui, r, "Loading music...")
            2 -> message(c, ui, r, "Music needs access to your audio files.\nPress A to allow.")
            else -> if (count() == 0) message(c, ui, r, "No music found") else {
                lists[tab].draw(c, ui, r, count()) { i -> rowAt(i) }
            }
        }
    }

    private fun message(c: Canvas, ui: Ui, r: RectF, text: String) {
        val lines = text.split("\n")
        for ((i, l) in lines.withIndex()) {
            ui.text(c, l, r.centerX(), ui.baselineFor(r.centerY() + (i - (lines.size - 1) / 2f) * 30f * ui.sc, 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
        }
    }

    private fun rowAt(i: Int): Row {
        val gfx = shell.ui.gfx
        return when (tab) {
            0 -> act.library.tracks[i].let { Row(it.title, "${it.artist} - ${it.album}", icon = gfx.get("qgn_indi_mp_vc_song"), iconKind = Ic.NOTE, iconColor = MAGENTA, right = fmtTime(it.durationMs)) }
            1 -> albums[i].let { (name, ts) -> Row(name, "${ts.first().artist} - ${ts.size} songs", icon = gfx.get("qgn_indi_mp_vc_album"), iconKind = Ic.DISC, iconColor = ORANGE, drill = true) }
            else -> artists[i].let { (name, ts) -> Row(name, "${ts.size} songs", icon = gfx.get("qgn_indi_mp_vc_artist"), iconKind = Ic.PERSON, iconColor = GREEN, drill = true) }
        }
    }

    private fun open(i: Int) {
        when (tab) {
            0 -> {
                Player.playQueue(act, act.library.tracks, i)
                shell.push(NowPlayingScreen(act))
            }
            1 -> shell.push(TrackListScreen(act, albums[i].first, albums[i].second))
            else -> shell.push(TrackListScreen(act, artists[i].first, artists[i].second))
        }
    }

    override fun onAct(a: Act): Boolean {
        if (state == 2 && a == Act.OK) {
            request()
            return true
        }
        if (state != 1 || count() == 0) return false
        val vis = lists[tab].visible(shell.ui, shell.contentBottom - shell.contentTop)
        return when (a) {
            Act.OK -> {
                open(lists[tab].sel)
                true
            }
            else -> lists[tab].act(a, count(), vis)
        }
    }

    override fun onTap(x: Float, y: Float): Boolean {
        if (state == 2) {
            request()
            return true
        }
        val r = RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom)
        val i = lists[tab].hit(shell.ui, r, y, count())
        if (i < 0) return false
        lists[tab].sel = i
        open(i)
        return true
    }

    override fun onScroll(dy: Float) {
        val vis = lists[tab].visible(shell.ui, shell.contentBottom - shell.contentTop)
        lists[tab].scrollBy(if (dy > 0) 1 else -1, count(), vis)
    }
}

/** The songs of one album or artist. */
class TrackListScreen(private val act: MusicActivity, private val name: String, private val tracks: List<Track>) : Screen() {
    override val title get() = name
    private val list = ListState()

    override fun tools(): List<Tool> = listOf(
        Tool(Ic.NOWPLAY, { if (Player.current != null) shell.push(NowPlayingScreen(act)) }, Player.current != null),
        playPauseTool { Player.toggle() },
    )

    override fun menu() = listOf(
        MenuItem("Play all") {
            Player.shuffle = false
            Player.playQueue(act, tracks, 0)
            shell.push(NowPlayingScreen(act))
        },
        MenuItem("Shuffle") {
            Player.shuffle = true
            Player.playQueue(act, tracks, tracks.indices.random())
            shell.push(NowPlayingScreen(act))
        },
    )

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        list.draw(c, ui, r, tracks.size) { i ->
            val t = tracks[i]
            Row(t.title, t.artist, icon = ui.gfx.get("qgn_indi_mp_vc_song"), iconKind = Ic.NOTE, iconColor = MAGENTA, right = fmtTime(t.durationMs))
        }
    }

    private fun play(i: Int) {
        Player.playQueue(act, tracks, i)
        shell.push(NowPlayingScreen(act))
    }

    override fun onAct(a: Act): Boolean {
        val vis = list.visible(shell.ui, shell.contentBottom - shell.contentTop)
        if (a == Act.OK) {
            play(list.sel)
            return true
        }
        return list.act(a, tracks.size, vis)
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val r = RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom)
        val i = list.hit(shell.ui, r, y, tracks.size)
        if (i < 0) return false
        list.sel = i
        play(i)
        return true
    }

    override fun onScroll(dy: Float) {
        list.scrollBy(if (dy > 0) 1 else -1, tracks.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }
}

/** Belle "now playing": album art, title, artist, progress bar, on-screen transport buttons, volume. */
class NowPlayingScreen(private val act: MusicActivity) : Screen() {
    override val title get() = "Now playing"
    override val tickMs get() = 500L
    private var artFor = -1L
    private val progress = RectF()
    private val transport = Array(5) { RectF() }
    private var volumeUntil = 0L

    override fun onShow() {
        Player.listeners.add { if (shell.screen === this) shell.postInvalidate() }
    }

    override fun tools(): List<Tool> = listOf(
        playPauseTool { Player.toggle() },
        Tool(Ic.NEXT, { Player.next() }, Player.current != null),
    )

    override fun menu() = listOf(
        MenuItem("Shuffle: " + if (Player.shuffle) "on" else "off") { Player.shuffle = !Player.shuffle },
        MenuItem("Repeat: " + arrayOf("off", "all", "one")[Player.repeat]) { Player.repeat = (Player.repeat + 1) % 3 },
        MenuItem("Previous song") { Player.prev() },
    )

    private fun audio() = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        val t = Player.current
        if (t == null) {
            ui.text(c, "Nothing is playing", r.centerX(), ui.baselineFor(r.centerY(), 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
            return
        }
        val pad = 20f * ui.sc
        val side = min(r.height() - 2 * pad, 300f * ui.sc)
        val art = RectF(r.left + pad, r.centerY() - side / 2f, r.left + pad + side, r.centerY() + side / 2f)
        val bmp = ArtCache.peek(t)
        if (bmp != null) {
            ui.drawBitmap(c, bmp, art)
            ui.p.style = Paint.Style.STROKE
            ui.p.strokeWidth = 1f * ui.sc
            ui.p.color = ui.al(Color.WHITE, 60)
            c.drawRect(art, ui.p)
            ui.p.style = Paint.Style.FILL
        } else {
            // the original Belle "no album art" graphic from the E6 theme
            if (!ui.gfx.draw(c, "qgn_graf_mup_npv_defalbumart", art)) ui.iconFrame(c, art, MAGENTA, Ic.NOTE)
            if (artFor != t.id) {
                artFor = t.id
                ArtCache.load(act, t, 600) { shell.invalidate() }
            }
        }

        val x = art.right + pad
        val maxW = r.right - x - pad
        var y = art.top + 30f * ui.sc
        ui.text(c, t.title, x, y, 24f * ui.sc, maxW = maxW)
        y += 34f * ui.sc
        ui.text(c, t.artist, x, y, 20f * ui.sc, Belle.TEXT_MID, maxW = maxW, face = ui.light)
        y += 28f * ui.sc
        ui.text(c, t.album, x, y, 18f * ui.sc, Belle.TEXT_MID, maxW = maxW, face = ui.light)

        val barY = art.bottom - 134f * ui.sc
        progress.set(x, barY, x + maxW, barY + 20f * ui.sc)
        ui.progressBar(c, progress, if (Player.duration > 0) Player.position.toFloat() / Player.duration else 0f)
        ui.text(c, fmtTime(Player.position), x, barY + 44f * ui.sc, 18f * ui.sc, Color.WHITE, face = ui.light)
        ui.text(c, fmtTime(Player.duration), x + maxW, barY + 44f * ui.sc, 18f * ui.sc, Color.WHITE, Paint.Align.RIGHT, face = ui.light)

        // transport: shuffle, previous, play / pause, next, repeat
        val cy = art.bottom - 38f * ui.sc
        val xs = floatArrayOf(0.08f, 0.30f, 0.50f, 0.70f, 0.92f).map { x + maxW * it }
        val shuffleKind = if (Player.shuffle) Ic.SHUFFLE else Ic.SHUFFLE_OFF
        val repeatKind = when (Player.repeat) { 1 -> Ic.REPEAT; 2 -> Ic.REPEAT_ONE; else -> Ic.REPEAT_OFF }
        ui.glyph(c, shuffleKind, xs[0], cy, 36f * ui.sc)
        ui.glyph(c, Ic.PREVIOUS, xs[1], cy, 44f * ui.sc)
        val round = if (Player.playing) "qgn_indi_mup2_button_pause" else "qgn_indi_mup2_button_play"
        if (!ui.gfx.glyph(c, round, xs[2], cy, 64f * ui.sc)) ui.glyph(c, if (Player.playing) Ic.PAUSE else Ic.PLAY, xs[2], cy, 44f * ui.sc)
        ui.glyph(c, Ic.NEXT, xs[3], cy, 44f * ui.sc)
        ui.glyph(c, repeatKind, xs[4], cy, 36f * ui.sc)
        val half = 30f * ui.sc
        for (i in 0..4) transport[i].set(xs[i] - half, cy - half, xs[i] + half, cy + half)

        // transient volume indicator
        if (SystemClock.uptimeMillis() < volumeUntil) {
            val am = audio()
            val frac = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            val box = RectF(r.centerX() - 150f * ui.sc, r.top + 14f * ui.sc, r.centerX() + 150f * ui.sc, r.top + 70f * ui.sc)
            ui.nine(c, "qtg_fr_popup", box, 40, 20f)
            ui.glyph(c, if (frac <= 0f) Ic.AUDIO_OFF else Ic.AUDIO_ON, box.left + 34f * ui.sc, box.centerY(), 34f * ui.sc)
            ui.progressBar(c, RectF(box.left + 64f * ui.sc, box.centerY() - 10f * ui.sc, box.right - 20f * ui.sc, box.centerY() + 10f * ui.sc), frac)
            ui.busy = true
        }
    }

    private fun volume(up: Boolean) {
        audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        volumeUntil = SystemClock.uptimeMillis() + 1500
    }

    override fun onAct(a: Act): Boolean {
        when (a) {
            Act.OK -> Player.toggle()
            Act.LEFT -> Player.seekBy(-10_000)
            Act.RIGHT -> Player.seekBy(10_000)
            Act.PREV -> Player.prev()
            Act.NEXT -> Player.next()
            Act.UP -> volume(true)
            Act.DOWN -> volume(false)
            else -> return false
        }
        return true
    }

    override fun onTap(x: Float, y: Float): Boolean {
        when {
            progress.contains(x, y) && Player.duration > 0 -> Player.seek((Player.duration * ((x - progress.left) / progress.width())).toLong())
            transport[0].contains(x, y) -> Player.shuffle = !Player.shuffle
            transport[1].contains(x, y) -> Player.prev()
            transport[3].contains(x, y) -> Player.next()
            transport[4].contains(x, y) -> Player.repeat = (Player.repeat + 1) % 3
            else -> Player.toggle()
        }
        return true
    }
}
