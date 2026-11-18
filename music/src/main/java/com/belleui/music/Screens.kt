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

/**
 * The client's RadioHeader: a 40 px bar under the status bar, blue (#4099e4 to #2184de), "Title - Artist" in white 22 px bold and the play / pause
 * icon on the right. It shows while something plays and takes you to the player when tapped.
 */
private class MiniPlayer(private val act: MusicActivity) {
    val bar = RectF()
    private val key = RectF()

    fun visible() = Player.current != null

    fun height(ui: Ui) = if (visible()) 40f * ui.sc else 0f

    fun draw(c: Canvas, ui: Ui, r: RectF, shell: com.belleui.kit.BelleShell) {
        val t = Player.current ?: return
        val sc = ui.sc
        bar.set(r.left, r.top, r.right, r.top + 40f * sc)
        ui.p.style = Paint.Style.FILL
        ui.p.shader = android.graphics.LinearGradient(0f, bar.top, 0f, bar.bottom, 0xFF4099E4.toInt(), 0xFF2184DE.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(bar, ui.p)
        ui.p.shader = null
        key.set(bar.right - 50f * sc, bar.top, bar.right, bar.bottom)
        ui.onDark {
            ui.text(c, t.title + " - " + t.artist, bar.left + 18f * sc, ui.baselineFor(bar.centerY() + 2f * sc, 22f * sc, ui.medium), 22f * sc, 0xFFFFFFFF.toInt(), maxW = bar.width() - 18f * sc - 54f * sc, face = ui.medium)
            ui.glyph(c, if (Player.playing) Ic.NM_PAUSE else Ic.NM_PLAY, key.centerX(), key.centerY(), 24f * sc, 0xFFFFFFFF.toInt())
        }
        val frac = if (Player.duration > 0) Player.position.toFloat() / Player.duration else 0f
        ui.p.color = 0x99FFFFFF.toInt()
        c.drawRect(bar.left, bar.bottom - 2f * sc, bar.left + bar.width() * frac, bar.bottom, ui.p)
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
        // PromoPanel (landscape): six squares, each a sixth of the width, so the strip always fills the page exactly - no tile is cut or squeezed
        override fun height(ui: Ui, w: Float) = w / 6f + 8f * ui.sc
        override fun draw(c: Canvas, ui: Ui, r: RectF, focused: Boolean, pressed: Boolean) {
            val sc = ui.sc
            val side = r.width() / 6f
            var x = r.left
            for ((i, t) in tiles.withIndex()) {
                if (x >= r.right) break
                val box = RectF(x, r.top, x + side, r.top + side)
                val bmp = ArtCache.peek(t.second.first())
                if (bmp != null) ui.drawBitmapCover(c, bmp, box)
                else {
                    ArtCache.load(act, t.second.first(), 240) { shell.invalidate() }
                    ui.fill(c, box, 0xFFE4E5E7.toInt())
                    ui.gfx.fit(c, "qgn_graf_mup_npv_defalbumart", RectF(box.left + 10f * sc, box.top + 8f * sc, box.right - 10f * sc, box.bottom - 30f * sc))
                }
                // the name bar (#99000000, 26 px)
                ui.fill(c, RectF(box.left, box.bottom - 26f * sc, box.right, box.bottom), 0x99000000.toInt())
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
            if (y > r.top + r.width() / 6f) return false
            val i = ((x - r.left) / (r.width() / 6f)).toInt()
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
                // no cover yet: a visible placeholder (a light tile with the default album picture), not an empty box
                ui.fill(c, cover, 0xFFE4E5E7.toInt())
                ui.gfx.fit(c, "qgn_graf_mup_npv_defalbumart", RectF(cover.left + 6f * sc, cover.top + 6f * sc, cover.right - 6f * sc, cover.bottom - 6f * sc))
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

    private fun body(r: RectF) = RectF(r.left, r.top + mini.height(shell.ui), r.right, r.bottom)

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

/**
 * The songs of one album, artist, genre or playlist, as the Nokia Music product page: the cover and the names at the top with the blue button, then
 * "1. Title" rows (64 px) with the time in a white pill; the track that plays is tinted (#E3E5E8), has a blue edge and shows its progress.
 */
class TrackListScreen(private val act: MusicActivity, private val name: String, private val tracks: List<Track>) : Screen() {
    override val forceLight: Boolean get() = true
    override val lightBg: Int? get() = 0xFFF0F1F2.toInt()
    override val lightOk: Boolean get() = true
    override val title get() = name
    override val tickMs get() = 500L
    private val page = com.belleui.kit.FlowPage()
    private val ink = 0xFF282828.toInt()
    private val ink2 = 0xFF505050.toInt()
    private val blue = 0xFF1080DD.toInt()

    override fun onShow() {
        Player.listen(this) { if (shell.screen === this) shell.postInvalidate() }
        page.cells = buildCells()
        page.sel = 0
    }

    override fun tools(): List<Tool> = listOf(
        Tool(Ic.NM_PREV, { Player.prev() }, Player.current != null),
        Tool(if (Player.playing) Ic.NM_PAUSE else Ic.NM_PLAY, { if (Player.current != null) Player.toggle() else playFrom(0) }),
        Tool(Ic.NM_NEXT, { Player.next() }, Player.current != null),
    )

    override fun menu() = listOf(
        MenuItem("Play all") { Player.shuffle = false; playFrom(0) },
        MenuItem("Shuffle") { Player.shuffle = true; Player.playQueue(act, tracks, tracks.indices.random()); shell.push(NowPlayingScreen(act)) },
        MenuItem("Go to Now playing") { if (Player.current != null) shell.push(NowPlayingScreen(act)) },
    )

    private fun playFrom(i: Int) {
        Player.playQueue(act, tracks, i)
        shell.push(NowPlayingScreen(act))
    }

    private fun total(): String {
        val ms = tracks.sumOf { it.durationMs }
        val mins = ms / 60000
        return "${tracks.size} song${if (tracks.size == 1) "" else "s"} - ${if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min"}"
    }

    private inner class HeaderCell : com.belleui.kit.Cell() {
        override fun height(ui: Ui, w: Float) = 170f * ui.sc
        private val btn = RectF()
        override fun draw(c: Canvas, ui: Ui, r: RectF, focused: Boolean, pressed: Boolean) {
            val sc = ui.sc
            val side = 140f * sc
            val cover = RectF(r.left + 16f * sc, r.top + 14f * sc, r.left + 16f * sc + side, r.top + 14f * sc + side)
            val first = tracks.firstOrNull()
            val bmp = first?.let { ArtCache.peek(it) }
            if (bmp != null) ui.drawBitmapCover(c, bmp, cover) else {
                first?.let { ArtCache.load(act, it, 300) { shell.invalidate() } }
                ui.fill(c, cover, 0xFFE4E5E7.toInt())
                ui.gfx.fit(c, "qgn_graf_mup_npv_defalbumart", RectF(cover.left + 12f * sc, cover.top + 12f * sc, cover.right - 12f * sc, cover.bottom - 12f * sc))
            }
            val x = cover.right + 16f * sc
            val maxW = r.right - x - 16f * sc
            ui.text(c, name, x, r.top + 14f * sc + 28f * sc, 28f * sc, ink, maxW = maxW, face = ui.medium)
            ui.text(c, first?.artist ?: "", x, r.top + 14f * sc + 28f * sc + 28f * sc, 22f * sc, ink2, maxW = maxW, face = ui.light)
            ui.text(c, total(), x, r.top + 14f * sc + 28f * sc + 56f * sc, 22f * sc, ink2, maxW = maxW, face = ui.light)
            // the blue button (the client's price button)
            btn.set(x, r.bottom - 14f * sc - 46f * sc, x + 150f * sc, r.bottom - 14f * sc)
            ui.p.style = Paint.Style.FILL
            ui.p.shader = android.graphics.LinearGradient(0f, btn.top, 0f, btn.bottom, 0xFF2D93EA.toInt(), 0xFF1080DD.toInt(), android.graphics.Shader.TileMode.CLAMP)
            c.drawRoundRect(btn, 22f * sc, 22f * sc, ui.p)
            ui.p.shader = null
            ui.onDark { ui.text(c, "Play all", btn.centerX(), ui.baselineFor(btn.centerY(), 22f * sc, ui.medium), 22f * sc, 0xFFFFFFFF.toInt(), Paint.Align.CENTER, face = ui.medium) }
            if (focused) {
                ui.p.style = Paint.Style.STROKE
                ui.p.strokeWidth = 3f * sc
                ui.p.color = 0xFF282828.toInt()
                c.drawRoundRect(RectF(btn.left - 3f * sc, btn.top - 3f * sc, btn.right + 3f * sc, btn.bottom + 3f * sc), 24f * sc, 24f * sc, ui.p)
                ui.p.style = Paint.Style.FILL
            }
        }
        override fun onClick() { Player.shuffle = false; playFrom(0) }
    }

    private inner class CaptionCell : com.belleui.kit.Cell() {
        override val focusable get() = false
        override fun height(ui: Ui, w: Float) = 38f * ui.sc
        override fun draw(c: Canvas, ui: Ui, r: RectF, focused: Boolean, pressed: Boolean) {
            ui.fill(c, RectF(r.left + 8f * ui.sc, r.top, r.right - 8f * ui.sc, r.top + 1f), 0xFFDDDFE0.toInt())
            ui.text(c, "Tap on a song to play it", r.left + 16f * ui.sc, ui.baselineFor(r.centerY() + 2f * ui.sc, 18f * ui.sc, ui.light), 18f * ui.sc, 0xFF8C8C8C.toInt(), face = ui.light)
        }
    }

    private inner class TrackCell(private val i: Int) : com.belleui.kit.Cell() {
        private fun playing() = Player.current?.id == tracks[i].id
        override fun height(ui: Ui, w: Float) = (if (playing()) 88f else 64f) * ui.sc
        override fun draw(c: Canvas, ui: Ui, r: RectF, focused: Boolean, pressed: Boolean) {
            val sc = ui.sc
            val tr = tracks[i]
            val on = playing()
            if (focused || pressed) ui.fill(c, r, 0xFFD8D8D8.toInt()) else if (on) ui.fill(c, r, 0xFFE3E5E8.toInt())
            if (on) ui.fill(c, RectF(r.left, r.top, r.left + 5f * sc, r.bottom), blue)
            val pill = RectF(r.right - 12f * sc - 96f * sc, r.top + 12f * sc, r.right - 12f * sc, r.top + 12f * sc + 40f * sc)
            ui.p.style = Paint.Style.FILL
            ui.p.color = 0xFFFFFFFF.toInt()
            c.drawRoundRect(pill, 20f * sc, 20f * sc, ui.p)
            ui.p.style = Paint.Style.STROKE
            ui.p.strokeWidth = 1f * sc
            ui.p.color = 0xFFD2D4D6.toInt()
            c.drawRoundRect(pill, 20f * sc, 20f * sc, ui.p)
            ui.p.style = Paint.Style.FILL
            ui.text(c, tr.durText, pill.centerX(), ui.baselineFor(pill.centerY(), 22f * sc, ui.light), 22f * sc, ink, Paint.Align.CENTER, face = ui.light)
            ui.text(c, "${i + 1}. ${tr.title}", r.left + 16f * sc, ui.baselineFor(r.top + 32f * sc, 22f * sc, ui.medium), 22f * sc, ink, maxW = pill.left - r.left - 30f * sc, face = ui.medium)
            if (on) {
                val bar = RectF(r.left + 16f * sc, r.bottom - 26f * sc, r.right - 16f * sc, r.bottom - 16f * sc)
                ui.fill(c, bar, 0xFF3A3A3C.toInt())
                val f = if (Player.duration > 0) (Player.position.toFloat() / Player.duration).coerceIn(0f, 1f) else 0f
                ui.fill(c, RectF(bar.left, bar.top, bar.left + bar.width() * f, bar.bottom), blue)
            }
            ui.fill(c, RectF(r.left + 4f * sc, r.bottom - 1f, r.right - 4f * sc, r.bottom), 0xFFDDDFE0.toInt())
        }
        override fun onClick() { if (playing()) shell.push(NowPlayingScreen(act)) else playFrom(i) }
    }

    private fun buildCells(): List<com.belleui.kit.Cell> {
        val out = ArrayList<com.belleui.kit.Cell>()
        out.add(HeaderCell())
        out.add(CaptionCell())
        for (i in tracks.indices) out.add(TrackCell(i))
        return out
    }

    private fun rect() = RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom)

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        page.invalidateLayout()
        page.draw(c, ui, r)
    }

    override fun onAct(a: Act): Boolean = page.act(a)

    override fun onTap(x: Float, y: Float): Boolean = page.tap(rect(), x, y)

    override fun onScroll(dy: Float) = page.drag(dy)
}

/**
 * Now playing as the Nokia Music streaming view: black, the cover filling the whole page (cropped to fit, never stretched); with no cover the artist
 * and the title are set huge and faint instead (160 px at 50 %, 400 px bold at 20 %). The toolbar carries the client's own previous / play-pause /
 * next icons. A shade at the bottom holds the readable title, artist, the time and the progress line.
 */
class NowPlayingScreen(private val act: MusicActivity) : Screen() {
    override val title get() = "Now playing"
    override val tickMs get() = 500L
    private var artFor = -1L
    private val progress = RectF()
    private var volumeUntil = 0L

    override fun onShow() {
        Player.listen(this) { if (shell.screen === this) shell.postInvalidate() }
    }

    // the client's player toolbar: back, previous, play / pause, next (menu on the right)
    override fun tools(): List<Tool> = listOf(
        Tool(Ic.NM_PREV, { Player.prev() }),
        Tool(if (Player.playing) Ic.NM_PAUSE else Ic.NM_PLAY, { Player.toggle() }),
        Tool(Ic.NM_NEXT, { Player.next() }),
    )

    override fun menu() = listOf(
        MenuItem("Shuffle: " + if (Player.shuffle) "on" else "off") { Player.shuffle = !Player.shuffle },
        MenuItem("Repeat: " + arrayOf("off", "all", "one")[Player.repeat]) { Player.repeat = (Player.repeat + 1) % 3 },
    )

    private fun audio() = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        val t = Player.current
        val pos = Player.position // one query per frame
        if (t == null) {
            ui.text(c, "Nothing is playing", r.centerX(), ui.baselineFor(r.centerY(), 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
            return
        }
        val sc = ui.sc
        val bmp = ArtCache.peek(t)
        c.save()
        c.clipRect(r)
        if (bmp != null) {
            ui.drawBitmapCover(c, bmp, r)
        } else {
            if (artFor != t.id) {
                artFor = t.id
                ArtCache.load(act, t, 900) { shell.invalidate() }
            }
            // StreamingView: the artist 160 px at 50 % and the title 400 px bold at 20 %, huge behind everything
            ui.text(c, t.artist, r.left + 6f * sc, r.top + 80f * sc + 130f * sc, 160f * sc, ui.al(Color.WHITE, 128), face = ui.light)
            ui.text(c, t.title, r.left + 6f * sc, r.top + 80f * sc + 130f * sc - 10f * sc + 280f * sc, 400f * sc, ui.al(Color.WHITE, 51), face = ui.medium)
        }
        // the shade and the readable track info
        val shade = r.bottom - 120f * sc
        ui.p.style = Paint.Style.FILL
        ui.p.shader = android.graphics.LinearGradient(0f, shade, 0f, r.bottom, 0x00000000, 0xE6000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(r.left, shade, r.right, r.bottom, ui.p)
        ui.p.shader = null
        val x = r.left + 16f * sc
        val maxW = r.width() - 32f * sc
        ui.text(c, t.title, x, r.bottom - 66f * sc, 30f * sc, Color.WHITE, maxW = maxW, face = ui.medium)
        ui.text(c, t.artist + (if (t.album.isNotBlank()) " - " + t.album else ""), x, r.bottom - 38f * sc, 22f * sc, 0xFFD0D0D0.toInt(), maxW = maxW - 120f * sc, face = ui.light)
        ui.text(c, fmtTime(pos) + " / " + fmtTime(Player.duration), r.right - 16f * sc, r.bottom - 38f * sc, 20f * sc, 0xFFD0D0D0.toInt(), Paint.Align.RIGHT, face = ui.light)
        progress.set(r.left, r.bottom - 14f * sc, r.right, r.bottom)
        ui.p.color = 0x55FFFFFF
        c.drawRect(progress.left, progress.bottom - 6f * sc, progress.right, progress.bottom, ui.p)
        val f = if (Player.duration > 0) (pos.toFloat() / Player.duration).coerceIn(0f, 1f) else 0f
        ui.p.color = 0xFF1080DD.toInt()
        c.drawRect(progress.left, progress.bottom - 6f * sc, progress.left + progress.width() * f, progress.bottom, ui.p)
        c.restore()

        // transient volume indicator
        if (SystemClock.uptimeMillis() < volumeUntil) {
            val am = audio()
            val frac = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            val box = RectF(r.centerX() - 150f * sc, r.top + 14f * sc, r.centerX() + 150f * sc, r.top + 70f * sc)
            ui.nine(c, "qtg_fr_popup", box, 40, 20f)
            ui.glyph(c, if (frac <= 0f) Ic.AUDIO_OFF else Ic.AUDIO_ON, box.left + 34f * sc, box.centerY(), 34f * sc)
            ui.progressBar(c, RectF(box.left + 64f * sc, box.centerY() - 10f * sc, box.right - 20f * sc, box.centerY() + 10f * sc), frac)
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
        if (progress.contains(x, y) && Player.duration > 0) Player.seek((Player.duration * ((x - progress.left) / progress.width())).toLong())
        else Player.toggle()
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
