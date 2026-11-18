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
import com.belleui.kit.Tumbler
import com.belleui.kit.TumblerDialog
import com.belleui.kit.Ui
import kotlin.math.max
import kotlin.math.min

// the Belle player uses the theme blue for selection, progress and icons (the real screenshots), not a colour of its own
private val MAGENTA get() = Belle.BLUE

/** Albums are a list (cover, artist, album), as in the real Belle player; the old cover grid is kept behind this switch. */
private const val ALBUM_GRID = false
private const val ORANGE = 0xFFE8761A.toInt()
private const val GREEN = 0xFF4C9A2E.toInt()

private fun playPauseTool(onClick: () -> Unit) =
    Tool(if (Player.playing) Ic.PAUSE else Ic.PLAY, onClick, Player.current != null)

private fun letterOf(s: String): String = s.trim().firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() }?.toString() ?: "#"

/** The small "now playing" bar over the toolbar: cover, title and artist, a round play / pause key. Tap it for the full view. */
private class MiniPlayer(private val act: MusicActivity) {
    val bar = RectF()
    private val key = RectF()

    fun visible() = Player.current != null

    fun height(ui: Ui) = if (visible()) 72f * ui.sc else 0f

    fun draw(c: Canvas, ui: Ui, r: RectF, shell: com.belleui.kit.BelleShell) {
        val t = Player.current ?: return
        val sc = ui.sc
        bar.set(r.left, r.bottom - 72f * sc, r.right, r.bottom)
        ui.p.style = Paint.Style.FILL
        ui.p.shader = android.graphics.LinearGradient(0f, bar.top, 0f, bar.bottom, 0xFF3A3A3A.toInt(), 0xFF1A1A1A.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(bar, ui.p)
        ui.p.shader = null
        ui.p.color = Belle.BLUE
        c.drawRect(bar.left, bar.top, bar.right, bar.top + 2f * sc, ui.p)
        val art = RectF(bar.left + 10f * sc, bar.top + 10f * sc, bar.left + 62f * sc, bar.bottom - 10f * sc)
        val bmp = ArtCache.peek(t)
        if (bmp != null) ui.drawBitmapCover(c, bmp, art) else {
            ArtCache.load(act, t, 200) { shell.invalidate() }
            ui.fill(c, art, 0xFF2A2A2A.toInt())
            ui.glyph(c, Ic.NOTE, art.centerX(), art.centerY(), 28f * sc, ui.al(Color.WHITE, 120))
        }
        val tx = art.right + 14f * sc
        ui.text(c, t.title, tx, ui.baselineFor(bar.top + 26f * sc, 22f * sc, ui.medium), 22f * sc, maxW = bar.right - tx - 90f * sc, face = ui.medium)
        ui.text(c, t.artist, tx, ui.baselineFor(bar.top + 52f * sc, 18f * sc, ui.light), 18f * sc, Belle.TEXT_MID, maxW = bar.right - tx - 90f * sc, face = ui.light)
        key.set(bar.right - 74f * sc, bar.top + 6f * sc, bar.right - 10f * sc, bar.bottom - 6f * sc)
        ui.roundButton(c, key.centerX(), key.centerY(), 54f * sc, if (Player.playing) Ic.PAUSE else Ic.PLAY)
        val frac = if (Player.duration > 0) Player.position.toFloat() / Player.duration else 0f
        ui.p.color = Belle.BLUE_LIGHT
        c.drawRect(bar.left, bar.bottom - 3f * sc, bar.left + bar.width() * frac, bar.bottom, ui.p)
    }

    /** True if the touch was on the bar (and handled). */
    fun tap(x: Float, y: Float, shell: com.belleui.kit.BelleShell): Boolean {
        if (!visible() || !bar.contains(x, y)) return false
        if (key.contains(x, y)) Player.toggle() else shell.push(NowPlayingScreen(act))
        return true
    }
}

/**
 * The Music home, laid out as the Nokia Music client (Qt Quick, from its package): a light page (#f0f1f2) with the "Music" header, a strip of
 * album covers, the Songs / Albums / Artists / Genres / Playlists button row and then 92 px list items - an 80 px cover, a 24 px bold title, two
 * 22 px grey lines. Everything is one page that scrolls together, as there.
 */
class RootScreen(private val act: MusicActivity) : Screen() {
    override val lightOk: Boolean get() = true
    override val forceLight: Boolean get() = true
    override val lightBg: Int? get() = 0xFFF0F1F2.toInt()
    override val title get() = "Music"

    private val modeNames = listOf("Songs", "Albums", "Artists", "Genres", "Playlists")
    private var mode = 0
    private val page = com.belleui.kit.FlowPage()
    private val mini = MiniPlayer(act)
    private var state = 0 // 0 loading, 1 ready, 2 access denied
    private var albums: List<Pair<String, List<Track>>> = emptyList()
    private var artists: List<Pair<String, List<Track>>> = emptyList()
    private var genres: List<Pair<String, List<Track>>> = emptyList()
    private var playlists: List<Pair<String, List<Track>>> = emptyList()
    private var started = false
    private var builtVersion = -1

    // Nokia Music's own colours (Theme.qml)
    private val ink = 0xFF282828.toInt()
    private val ink2 = 0xFF505050.toInt()
    private val hover = 0xFFD8D8D8.toInt()
    private val divider = 0xFFDDDFE0.toInt()

    override fun onShow() {
        if (!started) {
            started = true
            request()
        } else if (act.libraryDirty) {
            act.libraryDirty = false
            request()
        }
        Player.listen(this) { shell.postInvalidate() }
    }

    private fun request() {
        state = 0
        act.ensureAccess { ok ->
            if (!ok) {
                state = 2
                shell.invalidate()
            } else {
                Thread {
                    if (act.library.folders.isNotEmpty()) act.library.indexFolders()
                    act.library.load()
                    albums = act.library.albums().sortedWith(compareBy({ it.second.first().artist.lowercase() }, { it.first.lowercase() }))
                    artists = act.library.artists()
                    genres = act.library.genres()
                    playlists = act.library.playlists()
                    shell.post {
                        state = 1
                        rebuild()
                        shell.invalidate()
                    }
                }.start()
            }
        }
    }

    private fun count() = when (mode) {
        0 -> act.library.tracks.size
        1 -> albums.size
        2 -> artists.size
        3 -> genres.size
        else -> playlists.size
    }

    private fun shuffleAll() {
        val all = act.library.tracks
        if (all.isEmpty()) return
        Player.shuffle = true
        Player.playQueue(act, all, (all.indices).random())
        openNowPlaying()
    }

    // the real toolbar: back, shuffle, menu (Now playing joins it while something plays)
    override fun tools(): List<Tool> =
        listOf(Tool(Ic.SHUFFLE, { shuffleAll() }, state == 1 && act.library.tracks.isNotEmpty())) +
            (if (Player.current != null) listOf(Tool(Ic.NOWPLAY, { openNowPlaying() })) else emptyList())

    override fun menu(): List<MenuItem> {
        val items = ArrayList<MenuItem>()
        if (state == 1 && act.library.tracks.isNotEmpty()) items.add(MenuItem("Shuffle play all") { shuffleAll() })
        if (Player.current != null) items.add(MenuItem("Go to Now playing") { openNowPlaying() })
        items.add(MenuItem("Refresh") { request() })
        items.add(MenuItem("Settings") { shell.push(SettingsScreen(act)) })
        return items
    }

    private fun openNowPlaying() {
        if (Player.current != null) shell.push(NowPlayingScreen(act))
        else shell.toast("Nothing is playing")
    }

    private fun message(c: Canvas, ui: Ui, r: RectF, text: String) {
        val lines = text.split("\n")
        for ((i, l) in lines.withIndex()) {
            ui.text(c, l, r.centerX(), ui.baselineFor(r.centerY() + (i - (lines.size - 1) / 2f) * 30f * ui.sc, 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
        }
    }

    // ---- the cells ----------------------------------------------------------------------------

    /** "Music" in the 34 px header style, 10 px of air above (GroupHeader headerTwo, topMargin 10). */
    private inner class TitleCell : com.belleui.kit.Cell() {
        override val focusable get() = false
        override fun height(ui: Ui, w: Float) = 66f * ui.sc
        override fun draw(c: Canvas, ui: Ui, r: RectF, focused: Boolean, pressed: Boolean) {
            ui.text(c, "Music", r.left + 16f * ui.sc, ui.baselineFor(r.top + 38f * ui.sc, 34f * ui.sc), 34f * ui.sc, ink)
        }
    }

    /** The promo strip: album covers 144 px high, each with a black 80% name bar along its bottom (PromoPanel, landscape). */
    private inner class HeroCell(private val tiles: List<Pair<String, List<Track>>>) : com.belleui.kit.Cell() {
        private var hsel = 0
        override fun height(ui: Ui, w: Float) = 144f * ui.sc
        override fun draw(c: Canvas, ui: Ui, r: RectF, focused: Boolean, pressed: Boolean) {
            val sc = ui.sc
            val side = r.height()
            var x = r.left
            for ((i, t) in tiles.withIndex()) {
                if (x >= r.right) break
                val box = RectF(x, r.top, min(x + side, r.right), r.bottom)
                val bmp = ArtCache.peek(t.second.first())
                if (bmp != null) ui.drawBitmapCover(c, bmp, RectF(x, r.top, x + side, r.bottom).also { c.save(); c.clipRect(box) })
                else {
                    ArtCache.load(act, t.second.first(), 240) { shell.invalidate() }
                    ui.fill(c, box, 0xFF9AA3AD.toInt())
                    ui.gfx.draw(c, "qgn_graf_mup_npv_defalbumart", box)
                }
                if (bmp != null) c.restore()
                // the name bar
                ui.fill(c, RectF(box.left, box.bottom - 26f * sc, box.right, box.bottom), 0xCC000000.toInt())
                ui.onDark { ui.text(c, t.first, box.left + 10f * sc, ui.baselineFor(box.bottom - 13f * sc, 18f * sc), 18f * sc, 0xFFFFFFFF.toInt(), maxW = box.width() - 20f * sc) }
                if (focused && i == hsel) {
                    ui.p.style = Paint.Style.STROKE
                    ui.p.strokeWidth = 4f * sc
                    ui.p.color = 0xFF1080DD.toInt()
                    c.drawRect(box.left + 2f * sc, box.top + 2f * sc, box.right - 2f * sc, box.bottom - 2f * sc, ui.p)
                    ui.p.style = Paint.Style.FILL
                }
                x += side
            }
        }
        override fun onKey(a: Act): Boolean = when (a) {
            Act.LEFT -> { hsel = max(0, hsel - 1); true }
            Act.RIGHT -> { hsel = min(tiles.size - 1, hsel + 1); true }
            else -> false
        }
        override fun onClick() { tiles.getOrNull(hsel)?.let { shell.push(TrackListScreen(act, it.first, it.second)) } }
        override fun onTapAt(x: Float, y: Float, r: RectF): Boolean {
            val i = ((x - r.left) / r.height()).toInt()
            tiles.getOrNull(i)?.let { hsel = i; onClick(); return true }
            return false
        }
    }

    /** The button row (Songs | Albums | ...): one dark pill, the checked button pressed in. */
    private inner class SegmentCell : com.belleui.kit.Cell() {
        override fun height(ui: Ui, w: Float) = 74f * ui.sc
        private fun bar(r: RectF, sc: Float) = RectF(r.left + 10f * sc, r.top + 11f * sc, r.right - 10f * sc, r.bottom - 11f * sc)
        override fun draw(c: Canvas, ui: Ui, r: RectF, focused: Boolean, pressed: Boolean) {
            val sc = ui.sc
            val b = bar(r, sc)
            val n = modeNames.size
            val w = b.width() / n
            for (i in 0 until n) {
                val seg = RectF(b.left + i * w, b.top, b.left + (i + 1) * w, b.bottom)
                val on = i == mode
                ui.p.style = Paint.Style.FILL
                ui.p.shader = android.graphics.LinearGradient(0f, seg.top, 0f, seg.bottom,
                    if (on) 0xFF1B1B1B.toInt() else 0xFF565656.toInt(), if (on) 0xFF3E3E3E.toInt() else 0xFF2C2C2C.toInt(), android.graphics.Shader.TileMode.CLAMP)
                val rad = 14f * sc
                val path = android.graphics.Path()
                val rr = floatArrayOf(if (i == 0) rad else 0f, if (i == 0) rad else 0f, if (i == n - 1) rad else 0f, if (i == n - 1) rad else 0f,
                    if (i == n - 1) rad else 0f, if (i == n - 1) rad else 0f, if (i == 0) rad else 0f, if (i == 0) rad else 0f)
                path.addRoundRect(seg, rr, android.graphics.Path.Direction.CW)
                c.drawPath(path, ui.p)
                ui.p.shader = null
                if (i > 0) { ui.p.color = 0xFF111111.toInt(); c.drawRect(seg.left, seg.top, seg.left + 1f * sc, seg.bottom, ui.p) }
                ui.onDark { ui.text(c, modeNames[i], seg.centerX(), ui.baselineFor(seg.centerY(), 20f * sc), 20f * sc, 0xFFFFFFFF.toInt(), Paint.Align.CENTER, maxW = seg.width() - 6f * sc) }
            }
            if (focused) {
                ui.p.style = Paint.Style.STROKE
                ui.p.strokeWidth = 3f * sc
                ui.p.color = 0xFF1080DD.toInt()
                c.drawRoundRect(RectF(b.left - 2f * sc, b.top - 2f * sc, b.right + 2f * sc, b.bottom + 2f * sc), 16f * sc, 16f * sc, ui.p)
                ui.p.style = Paint.Style.FILL
            }
        }
        override fun onKey(a: Act): Boolean {
            val m = when (a) { Act.LEFT -> mode - 1; Act.RIGHT -> mode + 1; else -> return false }
            if (m in modeNames.indices) setMode(m)
            return true
        }
        override fun onTapAt(x: Float, y: Float, r: RectF): Boolean {
            val b = bar(r, shell.ui.sc)
            if (!b.contains(x, y)) return false
            setMode(((x - b.left) / (b.width() / modeNames.size)).toInt().coerceIn(0, modeNames.size - 1))
            return true
        }
    }

    /** One list item (ProductLink): 92 px, an 80 px cover, bold title, two grey lines; pressed / focused it turns #d8d8d8. */
    private inner class ItemCell(private val t1: String, private val t2: String, private val t3: String, private val track: Track?, private val open: () -> Unit) : com.belleui.kit.Cell() {
        override fun height(ui: Ui, w: Float) = 92f * ui.sc
        override fun draw(c: Canvas, ui: Ui, r: RectF, focused: Boolean, pressed: Boolean) {
            val sc = ui.sc
            if (focused || pressed) ui.fill(c, r, hover)
            val cover = RectF(r.left + 8f * sc, r.centerY() - 40f * sc, r.left + 88f * sc, r.centerY() + 40f * sc)
            val bmp = track?.let { ArtCache.peek(it) }
            if (bmp != null) ui.drawBitmapCover(c, bmp, cover) else {
                track?.let { ArtCache.load(act, it, 120) { shell.invalidate() } }
                if (!ui.gfx.draw(c, "ns_empty_thumb", cover)) ui.fill(c, cover, 0xFFE4E5E7.toInt())
            }
            val x = r.left + 96f * sc
            val maxW = r.right - x - 12f * sc
            val top = r.centerY() - (if (t3.isEmpty()) 29f else 40.5f) * sc
            ui.text(c, t1, x, top + 25f * sc, 24f * sc, ink, maxW = maxW, face = ui.medium)
            if (t2.isNotEmpty()) ui.text(c, t2, x, top + 32f * sc + 17f * sc, 22f * sc, ink2, maxW = maxW, face = ui.light)
            if (t3.isNotEmpty()) ui.text(c, t3, x, top + 59f * sc + 17f * sc, 22f * sc, ink2, maxW = maxW, face = ui.light)
            ui.fill(c, RectF(r.left + 4f * sc, r.bottom - 1f, r.right - 4f * sc, r.bottom), divider)
        }
        override fun onClick() = open()
    }

    private fun setMode(m: Int) {
        mode = m
        rebuild()
        shell.invalidate()
    }

    private fun rebuild() {
        val out = ArrayList<com.belleui.kit.Cell>()
        out.add(TitleCell())
        if (albums.isNotEmpty()) out.add(HeroCell(albums.take(6)))
        out.add(SegmentCell())
        val n = count()
        for (i in 0 until n) {
            when (mode) {
                0 -> act.library.tracks[i].let { tr -> out.add(ItemCell(tr.title, tr.artist, tr.durText, tr) { open(i) }) }
                1 -> albums[i].let { (name, ts) -> out.add(ItemCell(name, ts.first().artist, "${ts.size} song${if (ts.size == 1) "" else "s"}", ts.first()) { open(i) }) }
                2 -> artists[i].let { (name, ts) -> out.add(ItemCell(name, "${ts.size} song${if (ts.size == 1) "" else "s"}", "", ts.first()) { open(i) }) }
                3 -> genres[i].let { (name, ts) -> out.add(ItemCell(name, "${ts.size} song${if (ts.size == 1) "" else "s"}", "", ts.first()) { open(i) }) }
                else -> playlists[i].let { (name, ts) -> out.add(ItemCell(name, "${ts.size} song${if (ts.size == 1) "" else "s"}", "", ts.firstOrNull()) { open(i) }) }
            }
        }
        page.cells = out
        // the first thing the focus rests on is the Songs / Albums row, as in the client
        page.sel = out.indexOfFirst { it is SegmentCell }
        builtVersion = n
    }

    private fun open(i: Int) {
        when (mode) {
            0 -> {
                Player.playQueue(act, act.library.tracks, i)
                shell.push(NowPlayingScreen(act))
            }
            1 -> shell.push(TrackListScreen(act, albums[i].first, albums[i].second))
            3 -> shell.push(TrackListScreen(act, genres[i].first, genres[i].second))
            4 -> shell.push(TrackListScreen(act, playlists[i].first, playlists[i].second))
            else -> shell.push(TrackListScreen(act, artists[i].first, artists[i].second))
        }
    }

    private fun body(r: RectF) = RectF(r.left, r.top, r.right, r.bottom - mini.height(shell.ui))

    private fun contentRect() = body(RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom))

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        val b = body(r)
        when (state) {
            0 -> message(c, ui, r, "Loading music...")
            2 -> message(c, ui, r, com.belleui.kit.Keys.hint("Music needs access to your audio files.\nPress A to allow."))
            else -> {
                if (count() != builtVersion) rebuild()
                page.draw(c, ui, b)
                if (count() == 0) {
                    message(c, ui, RectF(b.left, b.top + 66f * ui.sc + 144f * ui.sc + 74f * ui.sc, b.right, b.bottom), when (mode) { 3 -> "No genres"; 4 -> "No playlists"; else -> "No music found" })
                }
            }
        }
        mini.draw(c, ui, r, shell)
    }

    override fun onAct(a: Act): Boolean {
        if (state == 2 && a == Act.OK) {
            request()
            return true
        }
        if (state != 1) return false
        return page.act(a)
    }

    override fun onTap(x: Float, y: Float): Boolean {
        if (state == 2) {
            request()
            return true
        }
        if (mini.tap(x, y, shell)) return true
        return page.tap(contentRect(), x, y)
    }

    override fun onScroll(dy: Float) {
        page.drag(dy)
    }
}

/** The songs of one album or artist. */
class TrackListScreen(private val act: MusicActivity, private val name: String, private val tracks: List<Track>) : Screen() {
    override val forceLight: Boolean get() = true
    override val lightBg: Int? get() = 0xFFF0F1F2.toInt()
    override val lightOk: Boolean get() = true
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
            Row(t.title, t.artist, icon = ui.gfx.get("qgn_indi_mp_vc_song"), iconKind = Ic.NOTE, iconColor = MAGENTA)
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
        Player.listen(this) { if (shell.screen === this) shell.postInvalidate() }
    }

    // the Belle Now playing toolbar: back, repeat, shuffle, menu
    override fun tools(): List<Tool> = listOf(
        Tool(when (Player.repeat) { 1 -> Ic.REPEAT; 2 -> Ic.REPEAT_ONE; else -> Ic.REPEAT_OFF }, { Player.repeat = (Player.repeat + 1) % 3 }),
        Tool(if (Player.shuffle) Ic.SHUFFLE else Ic.SHUFFLE_OFF, { Player.shuffle = !Player.shuffle }),
    )

    override fun menu() = listOf(
        MenuItem("Shuffle: " + if (Player.shuffle) "on" else "off") { Player.shuffle = !Player.shuffle },
        MenuItem("Repeat: " + arrayOf("off", "all", "one")[Player.repeat]) { Player.repeat = (Player.repeat + 1) % 3 },
        MenuItem("Previous song") { Player.prev() },
    )

    private fun audio() = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        val t = Player.current
        val pos = Player.position // one query per frame, not four
        if (t == null) {
            ui.text(c, "Nothing is playing", r.centerX(), ui.baselineFor(r.centerY(), 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
            return
        }
        // the Belle Now playing view: album art left; artist, title and album, the bar and three big buttons right
        val pad = 20f * ui.sc
        val side = min(r.height() - 2 * pad, 300f * ui.sc)
        val art = RectF(r.left + pad, r.top + pad, r.left + pad + side, r.top + pad + side)
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
        var y = art.top + 34f * ui.sc
        // artist, title and album are centred, the title the largest, as in the Belle player
        val mid = x + maxW / 2f
        ui.text(c, t.artist, mid, y, 19f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER, maxW = maxW, face = ui.light)
        y += 36f * ui.sc
        ui.text(c, t.title, mid, y, 28f * ui.sc, Color.WHITE, Paint.Align.CENTER, maxW = maxW, face = ui.medium)
        y += 28f * ui.sc
        ui.text(c, t.album, mid, y, 18f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER, maxW = maxW, face = ui.light)

        val barY = r.top + r.height() * 0.46f
        progress.set(x, barY, x + maxW, barY + 26f * ui.sc)
        ui.progressBar(c, progress, if (Player.duration > 0) pos.toFloat() / Player.duration else 0f)
        ui.text(c, fmtTime(pos), x, barY + 44f * ui.sc, 18f * ui.sc, Color.WHITE, face = ui.light)
        ui.text(c, fmtTime(Player.duration), x + maxW, barY + 44f * ui.sc, 18f * ui.sc, Color.WHITE, Paint.Align.RIGHT, face = ui.light)

        // the progress slider with a round knob, as on the E6
        val kx = progress.left + progress.width() * (if (Player.duration > 0) pos.toFloat() / Player.duration else 0f)
        ui.p.style = Paint.Style.FILL
        ui.p.shader = android.graphics.LinearGradient(0f, progress.centerY() - 14f * ui.sc, 0f, progress.centerY() + 14f * ui.sc, 0xFFFFFFFF.toInt(), 0xFFB5B5B5.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawCircle(kx.coerceIn(progress.left, progress.right), progress.centerY(), 14f * ui.sc, ui.p)
        ui.p.shader = null

        // transport: previous, play / pause, next as round keys (shuffle and repeat live in the toolbar)
        val bt = r.top + r.height() * 0.80f
        val cxm = x + maxW / 2f
        val dx = min(112f * ui.sc, maxW * 0.34f)
        val ds = floatArrayOf(66f * ui.sc, 92f * ui.sc, 66f * ui.sc)
        val xs = floatArrayOf(cxm - dx, cxm, cxm + dx)
        for (i in 0..2) {
            ui.roundButton(c, xs[i], bt, ds[i], when (i) { 0 -> Ic.PREVIOUS; 1 -> if (Player.playing) Ic.PAUSE else Ic.PLAY; else -> Ic.NEXT })
            val hh = ds[i] / 2f + 4f * ui.sc
            transport[i + 1].set(xs[i] - hh, bt - hh, xs[i] + hh, bt + hh)
        }
        ui.text(c, "${Player.index + 1} of ${Player.queue.size}", mid, y + 26f * ui.sc, 15f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER, face = ui.light)

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
            transport[1].contains(x, y) -> Player.prev()
            transport[2].contains(x, y) -> Player.toggle()
            transport[3].contains(x, y) -> Player.next()
            else -> {}
        }
        return true
    }
}

private fun limitText(sec: Int) = if (sec <= 0) "Off - show all songs" else "Shorter than ${fmtTime(sec * 1000L)}"

/** Music settings, in Belle SelectionListItem style. */
class SettingsScreen(private val act: MusicActivity) : Screen() {
    override val lightOk: Boolean get() = true
    override val title get() = "Settings"
    private val list = ListState()

    private fun folderText(): String {
        val f = act.library.folders
        return if (f.isEmpty()) "Whole device" else if (f.size == 1) f[0].substringAfterLast('/') else "${f.size} folders"
    }

    private fun rows() = listOf(
        Row("Hide short songs", limitText(act.library.minSeconds), iconKind = Ic.NOTE, iconColor = MAGENTA, drill = true),
        Row("Music folders", folderText(), iconKind = Ic.LIST, iconColor = ORANGE, drill = true),
    )

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        val rows = rows()
        list.draw(c, ui, r, rows.size) { rows[it] }
        ui.text(
            c, "Short songs are left out of Songs, Albums and Artists. With music folders chosen, only music inside them is listed.",
            r.left + 16f * ui.sc, r.top + (rows.size + 0.5f) * Belle.ROW_H * ui.sc, 16f * ui.sc, Belle.TEXT_MID, maxW = r.width() - 32f * ui.sc, face = ui.light,
        )
    }

    private fun edit() {
        val cur = act.library.minSeconds
        val cols = listOf(
            Tumbler((0..2).map { "$it min" }, cur / 60),
            Tumbler((0..59).map { "%02d s".format(it) }, cur % 60),
        )
        shell.showOverlay(TumblerDialog("Hide songs shorter than", cols) { r ->
            val sec = (r[0] * 60 + r[1]).coerceIn(0, 120)
            act.library.minSeconds = sec
            act.libraryDirty = true
            shell.toast(if (sec == 0) "Showing all songs" else "Hiding songs under ${fmtTime(sec * 1000L)}")
        })
    }

    private fun open(i: Int) {
        if (i == 0) edit() else shell.push(FoldersScreen(act))
    }

    override fun onAct(a: Act): Boolean {
        if (a == Act.OK) {
            open(list.sel)
            return true
        }
        return list.act(a, 2, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val i = list.hit(shell.ui, RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom), y, 2)
        if (i < 0) return false
        list.sel = i
        open(i)
        return true
    }
}

/** The folders music is taken from; none chosen means everything on the device. */
class FoldersScreen(private val act: MusicActivity) : Screen() {
    override val lightOk: Boolean get() = true
    override val title get() = "Music folders"
    private val list = ListState()
    private var folders = act.library.folders

    private fun save(v: List<String>) {
        folders = v
        act.library.folders = v
        act.libraryDirty = true
        shell.invalidate()
    }

    private fun add() {
        act.pickFolder { path ->
            if (path != null && path !in folders) {
                save(folders + path)
                shell.toast("Looking for music in ${path.substringAfterLast('/')}...")
                Thread {
                    act.library.indexFolders()
                    shell.post { act.libraryDirty = true }
                }.start()
            }
        }
    }

    override fun tools() = listOf(Tool(Ic.ADD, { add() }))

    override fun menu() = listOf(
        MenuItem("Add folder") { add() },
        MenuItem("Remove selected") { folders.getOrNull(list.sel)?.let { save(folders - it) } },
        MenuItem("Use the whole device") { save(emptyList()) },
    )

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        if (folders.isEmpty()) {
            ui.text(c, "Music from the whole device", r.centerX(), ui.baselineFor(r.centerY() - 14f * ui.sc, 22f * ui.sc), 22f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
            ui.text(c, com.belleui.kit.Keys.hint("Press Y to pick the folder your music is in"), r.centerX(), ui.baselineFor(r.centerY() + 16f * ui.sc, 18f * ui.sc, ui.light), 18f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER, face = ui.light)
            return
        }
        list.draw(c, ui, r, folders.size) { i -> Row(folders[i].substringAfterLast('/'), folders[i], iconKind = Ic.LIST, iconColor = ORANGE) }
    }

    override fun onAct(a: Act): Boolean {
        if (folders.isEmpty()) return false
        return list.act(a, folders.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }

    override fun onScroll(dy: Float) = list.scrollBy(if (dy > 0) 1 else -1, folders.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
}
