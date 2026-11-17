package com.belleui.games

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.belleui.kit.Act
import com.belleui.kit.Belle
import com.belleui.kit.GridState
import com.belleui.kit.Ic
import com.belleui.kit.ListDialog
import com.belleui.kit.ListState
import com.belleui.kit.MenuItem
import com.belleui.kit.QueryDialog
import com.belleui.kit.Row
import com.belleui.kit.Screen
import com.belleui.kit.TabDef
import com.belleui.kit.Tool
import com.belleui.kit.Ui
import android.os.SystemClock
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private fun playTime(ms: Long): String {
    val m = ms / 60_000
    return if (m < 1) "under a minute" else if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}

private fun size(bytes: Long): String =
    if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else if (bytes >= 1_000_000) "%.0f MB".format(bytes / 1e6) else "${bytes / 1000} kB"

/** The first genre in a game's gamelist.xml entry, tidied up. */
fun genreOfGame(g: Game): String? {
    val raw = g.rep.meta?.genre ?: g.meta?.genre ?: return null
    val first = raw.split('/', ',', ';', '&').first().trim()
    if (first.isEmpty()) return null
    return first.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
}

/** Box art cut to the Belle icon shape (the same superellipse the app icons use), with the icon's gloss and rim. */
private fun maskedArt(c: Canvas, ui: Ui, bmp: Bitmap, r: RectF) {
    val shape = Path(ui.squircle(r))
    c.save()
    c.clipPath(shape)
    ui.drawBitmapCover(c, bmp, r)
    c.restore()
    ui.p.style = Paint.Style.FILL
    ui.p.shader = RadialGradient(r.centerX(), r.top, r.width() * 0.7f, ui.al(Color.WHITE, 70), ui.al(Color.WHITE, 0), Shader.TileMode.CLAMP)
    c.drawPath(shape, ui.p)
    ui.p.shader = null
    ui.p.style = Paint.Style.STROKE
    ui.p.strokeWidth = 1.2f * ui.sc
    ui.p.color = ui.al(Color.BLACK, 120)
    c.drawPath(shape, ui.p)
    ui.p.style = Paint.Style.FILL
}

/** The largest square that fits [box], centred. */
private fun square(box: RectF): RectF {
    val s = min(box.width(), box.height())
    return RectF(box.centerX() - s / 2, box.centerY() - s / 2, box.centerX() + s / 2, box.centerY() + s / 2)
}

/** Art for a game in an icon-shaped tile; a coloured icon frame stands in until (or unless) there is art. */
private fun drawArt(c: Canvas, ui: Ui, act: GamesActivity, shell: com.belleui.kit.BelleShell, g: Game, box: RectF) {
    val sq = square(box)
    val bmp = act.art.get(g) { shell.invalidate() }
    if (bmp != null) maskedArt(c, ui, bmp, sq) else ui.iconFrame(c, sq, g.system.color, Ic.PLAY)
}

/**
 * A Belle list of games: box art, title and a line of detail. A starts the game straight away;
 * L1 / R1 jump to the previous / next letter of the alphabet.
 */
class GameList(private val act: GamesActivity, private val showSystem: Boolean) {
    val list = ListState(72f)
    private val rr = RectF()
    private var letter = ""
    private var letterUntil = 0L

    private fun initial(g: Game): String {
        val ch = g.title.firstOrNull()?.uppercaseChar() ?: '#'
        return if (ch in 'A'..'Z') ch.toString() else "#"
    }

    private fun detail(g: Game): String {
        val st = act.prefs.peek(g.rep.path)
        val parts = ArrayList<String>(3)
        if (st?.fav == true) parts.add("Favourite")
        if (st != null && st.launches > 0) parts.add("Played ${st.launches}x")
        if (g.variants.size > 1) parts.add("${g.variants.size} versions")
        if (parts.isEmpty()) parts.add(if (showSystem) g.system.name else "Not played yet")
        return parts.joinToString(" - ")
    }

    fun selected(games: List<Game>): Game? = games.getOrNull(list.sel.coerceIn(0, max(0, games.size - 1)))

    fun draw(c: Canvas, ui: Ui, r: RectF, games: List<Game>, shell: com.belleui.kit.BelleShell) {
        list.draw(c, ui, r, games.size) { i ->
            val g = games[i]
            Row(g.title, detail(g), iconKind = Ic.PLAY, iconColor = g.system.color, right = if (showSystem) g.system.short else null)
        }
        // box art over the icon slot, cropped to a square
        val vis = list.visible(ui, r.height())
        val sc = ui.sc
        c.save()
        c.clipRect(r)
        for (i in max(0, list.top - 1)..min(games.size - 1, list.top + vis + 1)) {
            list.rowRect(ui, r, i, rr)
            val x = rr.left + Belle.PADDING * sc
            ui.tmp.set(x, rr.centerY() - 25f * sc, x + 50f * sc, rr.centerY() + 25f * sc)
            val bmp = act.art.get(games[i]) { shell.invalidate() }
            if (bmp != null) maskedArt(c, ui, bmp, ui.tmp)
        }
        c.restore()
        // the letter being jumped to
        if (SystemClock.uptimeMillis() < letterUntil) {
            val box = RectF(r.centerX() - 60f * sc, r.centerY() - 60f * sc, r.centerX() + 60f * sc, r.centerY() + 60f * sc)
            ui.nine(c, "qtg_fr_popup", box, 40, 20f)
            ui.text(c, letter, box.centerX(), ui.baselineFor(box.centerY(), 64f * sc), 64f * sc, Color.WHITE, Paint.Align.CENTER)
            ui.busy = true
        }
    }

    /** Handles list movement and the letter jump; OK is left to the caller. */
    fun act(a: Act, games: List<Game>, vis: Int, letters: Boolean = true): Boolean {
        if (games.isEmpty()) return false
        if (letters && (a == Act.PREV || a == Act.NEXT)) {
            val cur = list.sel.coerceIn(0, games.size - 1)
            val here = initial(games[cur])
            var i = cur
            if (a == Act.NEXT) {
                while (i < games.size - 1 && initial(games[i]) == here) i++
            } else {
                while (i > 0 && initial(games[i - 1]) == here) i--
                if (i == cur && i > 0) {
                    val prev = initial(games[i - 1])
                    while (i > 0 && initial(games[i - 1]) == prev) i--
                    i = max(0, i - 0)
                    // land on the first game of the previous letter
                    while (i > 0 && initial(games[i - 1]) == initial(games[i])) i--
                }
            }
            list.sel = i
            list.ensure(games.size, vis)
            letter = initial(games[i])
            letterUntil = SystemClock.uptimeMillis() + 900
            return true
        }
        return list.act(a, games.size, vis)
    }

    fun hit(ui: Ui, r: RectF, y: Float, count: Int) = list.hit(ui, r, y, count)

    fun scroll(d: Int, count: Int, vis: Int) = list.scrollBy(d, count, vis)
}

/** The menu items that belong to one game, shared by every screen that lists games. */
private fun gameMenu(act: GamesActivity, shell: com.belleui.kit.BelleShell, g: Game?): List<MenuItem> {
    if (g == null) return emptyList()
    val items = ArrayList<MenuItem>()
    val fav = act.prefs.peek(g.rep.path)?.fav == true
    items.add(MenuItem(if (fav) "Remove from favourites" else "Add to favourites") { act.toggleFavourite(g); shell.invalidate() })
    items.add(MenuItem("Details") { shell.push(GameScreen(act, g)) })
    if (g.variants.size > 1) {
        items.add(MenuItem("Play another version...") {
            val labels = g.variants.map { v -> v.label }
            shell.showOverlay(ListDialog("Version or disc", labels, 0) { i ->
                val err = act.play(g.variants[i])
                shell.toast(err ?: "Starting ${g.title}...")
            })
        })
    }
    if (act.emus.emulatorFor(g) == Emu.RETROARCH) {
        items.add(MenuItem("Core: ${act.prefs.core(g.system)}") {
            shell.showOverlay(ListDialog("Core for ${g.system.short}", g.system.cores, g.system.cores.indexOf(act.prefs.core(g.system))) { i -> act.prefs.setCore(g.system, g.system.cores[i]) })
        })
    }
    return items
}

private fun startGame(act: GamesActivity, shell: com.belleui.kit.BelleShell, g: Game) {
    val err = act.play(g)
    shell.toast(err ?: "Starting ${g.title}...")
}

// ================================================================================================
// root: Featured / Games / Systems / Emulators
// ================================================================================================

class RootScreen(private val act: GamesActivity) : Screen() {
    override val title get() = "Games"
    override val tabs = listOf(TabDef("Store", Ic.HOME), TabDef("Library", Ic.GRID), TabDef("Emulators", Ic.TOOLS))

    private val featured = Featured()
    private val systemList = ListState()
    private val emuList = ListState()
    private var remoteStatus: String? = null
    private var started = false

    override fun onShow() {
        act.onResumeHook = {
            if (act.games.isEmpty() && act.hasAccess()) rescan()
            shell.postInvalidate()
        }
        if (!started) {
            started = true
            if (act.hasAccess()) rescan()
        }
    }

    private fun rescan() = act.rescan { shell.invalidate() }

    override fun onTabChanged() {
        if (tab == 2) checkRemote()
    }

    /** The game the highlight is on, for the buttons and the menu. */
    private fun focused(): Game? = if (tab == 0 && act.games.isNotEmpty()) featured.focused() else null

    private fun openSearch() {
        if (act.games.isEmpty()) return
        shell.push(GameListScreen(act, "Search", { act.games }, true, null, autoSearch = true))
    }

    override fun tools(): List<Tool> {
        val g = focused()
        return listOf(
            Tool(Ic.SEARCH, { openSearch() }, act.games.isNotEmpty()),
            Tool(Ic.PLAY, { focused()?.let { startGame(act, shell, it) } }, g != null),
        )
    }

    override fun menu(): List<MenuItem> =
        gameMenu(act, shell, focused()) + listOf(
            MenuItem("Rescan library") { rescan() },
            MenuItem("Settings") { shell.push(SettingsScreen(act)) },
        )

    private fun rect() = RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom)

    private fun message(c: Canvas, ui: Ui, r: RectF, text: String) {
        val lines = text.split("\n")
        for ((i, l) in lines.withIndex()) {
            ui.text(c, l, r.centerX(), ui.baselineFor(r.centerY() + (i - (lines.size - 1) / 2f) * 30f * ui.sc, 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER, maxW = r.width() - 40f * ui.sc)
        }
    }

    /** Text to show instead of content, or null when there are games. */
    private fun emptyMessage(): String? = when {
        !act.hasAccess() -> com.belleui.kit.Keys.hint("Games needs access to your files to find ROMs.\nPress A to allow.")
        act.scanning && act.games.isEmpty() -> "Looking for games..."
        act.games.isEmpty() -> "No games found.\nIn Settings use \"Find ROM cards\", or add a ROM folder."
        else -> null
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        if (tab >= 1) {
            if (tab == 1) drawSystems(c, ui, r) else drawEmulators(c, ui, r)
            return
        }
        val msg = emptyMessage()
        if (msg != null) {
            message(c, ui, r, msg)
            return
        }
        featured.draw(c, ui, r)
    }

    // ---- library: collections first, then one row per system ---------------------------------------

    private class Entry(val title: String, val sub: String, val color: Int, val kind: Int, val system: GameSystem?, val source: () -> List<Game>)

    private var entriesFor = -1
    private var entries: List<Entry> = emptyList()

    private fun entries(): List<Entry> {
        if (entriesFor == act.version) return entries
        entriesFor = act.version
        val st = act.prefs
        val all = act.games
        val favs = all.filter { st.peek(it.rep.path)?.fav == true }
        val played = all.filter { (st.peek(it.rep.path)?.launches ?: 0) > 0 }
        val list = ArrayList<Entry>()
        fun n(x: Int) = "$x game${if (x == 1) "" else "s"}"
        list.add(Entry("All games", n(all.size), 0xFF3E5CC8.toInt(), Ic.LIST, null) { act.games })
        if (favs.isNotEmpty()) list.add(Entry("Favourites", n(favs.size), 0xFFE8761A.toInt(), Ic.FAVOURITE, null) { act.games.filter { st.peek(it.rep.path)?.fav == true } })
        if (played.isNotEmpty()) list.add(Entry("Played", n(played.size), 0xFF1F9D63.toInt(), Ic.CLOCK, null) { act.games.filter { (st.peek(it.rep.path)?.launches ?: 0) > 0 } })
        for ((s, games) in act.bySystem) {
            val versions = games.sumOf { it.variants.size }
            val emu = if (s.emu == Emu.PPSSPP && act.prefs.pspWithPpsspp) "PPSSPP" else "RetroArch"
            list.add(Entry(s.name, "${games.size} title${if (games.size == 1) "" else "s"}${if (versions > games.size) " ($versions files)" else ""} - $emu", s.color, Ic.PLAY, s) {
                act.bySystem.firstOrNull { it.first === s }?.second ?: emptyList()
            })
        }
        entries = list
        return list
    }

    private fun drawSystems(c: Canvas, ui: Ui, r: RectF) {
        if (act.games.isEmpty()) {
            message(c, ui, r, emptyMessage() ?: "No games found")
            return
        }
        val list = entries()
        systemList.draw(c, ui, r, list.size) { i ->
            val e = list[i]
            Row(e.title, e.sub, iconKind = e.kind, iconColor = e.color, drill = true)
        }
    }

    private fun openEntry(i: Int) {
        val e = entries().getOrNull(i) ?: return
        shell.push(GameListScreen(act, e.title, e.source, e.system == null, e.system))
    }

    // ---- emulators and the RetroArch remote ----------------------------------------------------

    private fun emuRows(): List<Row> {
        val ra = act.emus.retroArch()
        val pp = act.emus.ppsspp()
        return listOf(
            Row("RetroArch", if (ra != null) "Installed - $ra" else "Not installed", iconKind = Ic.PLAY, iconColor = 0xFF3E5CC8.toInt(), drill = ra != null, dim = ra == null),
            Row("PPSSPP", if (pp != null) "Installed - $pp" else "Not installed", iconKind = Ic.PLAY, iconColor = 0xFF2B2F36.toInt(), drill = pp != null, dim = pp == null),
            Row("RetroArch remote", remoteStatus ?: com.belleui.kit.Keys.hint("Press A to check"), iconKind = Ic.WLAN_ON, iconColor = 0xFF1F9D63.toInt()),
            Row("Save state", "Of the game running in RetroArch"),
            Row("Load state"),
            Row("Pause / resume"),
            Row("Reset game"),
            Row("Quit game"),
            Row("Set up the remote", "Network commands must be switched on", drill = true),
        )
    }

    private fun drawEmulators(c: Canvas, ui: Ui, r: RectF) {
        val rows = emuRows()
        emuList.draw(c, ui, r, rows.size) { rows[it] }
    }

    private fun checkRemote() {
        remoteStatus = "Checking..."
        Thread {
            val s = RetroArchControl.status()
            remoteStatus = s ?: "Not answering - see \"Set up the remote\""
            shell.postInvalidate()
        }.start()
    }

    private fun remote(cmd: String, done: String) {
        Thread {
            val ok = RetroArchControl.send(cmd) != null
            shell.post { shell.toast(if (ok) done else "RetroArch isn't listening - see \"Set up the remote\"") }
        }.start()
    }

    private fun emuAction(i: Int) {
        when (i) {
            0 -> act.emus.openApp(act.emus.retroArch())?.let { shell.toast(it) }
            1 -> act.emus.openApp(act.emus.ppsspp())?.let { shell.toast(it) }
            2 -> checkRemote()
            3 -> remote("SAVE_STATE", "State saved")
            4 -> remote("LOAD_STATE", "State loaded")
            5 -> remote("PAUSE_TOGGLE", "Paused / resumed")
            6 -> remote("RESET", "Game reset")
            7 -> shell.showOverlay(QueryDialog("Quit the game running in RetroArch?", "Quit", "Cancel") { remote("QUIT", "Game closed") })
            8 -> shell.showOverlay(QueryDialog("In RetroArch open\nSettings > Network and switch on\nNetwork Commands.\nThen the buttons here control your game.", "OK", null) { })
        }
    }

    // ---- input ------------------------------------------------------------------------------

    private fun openGame(g: Game) = shell.push(GameScreen(act, g))

    override fun onAct(a: Act): Boolean {
        if (!act.hasAccess() && a == Act.OK) {
            act.askAccess { rescan() }
            return true
        }
        return when (tab) {
            0 -> if (act.games.isEmpty()) false else featured.act(a)
            1 -> {
                if (act.games.isEmpty()) return false
                val n = entries().size
                if (a == Act.OK) {
                    openEntry(systemList.sel.coerceIn(0, n - 1))
                    true
                } else systemList.act(a, n, systemList.visible(shell.ui, shell.contentBottom - shell.contentTop))
            }
            else -> {
                val n = emuRows().size
                if (a == Act.OK) {
                    emuAction(emuList.sel)
                    true
                } else emuList.act(a, n, emuList.visible(shell.ui, shell.contentBottom - shell.contentTop))
            }
        }
    }

    override fun onTap(x: Float, y: Float): Boolean {
        if (!act.hasAccess()) {
            act.askAccess { rescan() }
            return true
        }
        val r = rect()
        when (tab) {
            0 -> return featured.tap(x, y)
            1 -> {
                val i = systemList.hit(shell.ui, r, y, entries().size)
                if (i < 0) return false
                systemList.sel = i
                openEntry(i)
            }
            else -> {
                val i = emuList.hit(shell.ui, r, y, emuRows().size)
                if (i < 0) return false
                emuList.sel = i
                emuAction(i)
            }
        }
        return true
    }

    override fun onScroll(dy: Float) {
        val d = if (dy > 0) 1 else -1
        when (tab) {
            0 -> featured.scroll(d)
            1 -> systemList.scrollBy(d, entries().size, systemList.visible(shell.ui, shell.contentBottom - shell.contentTop))
            else -> emuList.scrollBy(d, emuRows().size, emuList.visible(shell.ui, shell.contentBottom - shell.contentTop))
        }
    }

    // ---- Store front: a hero banner, shelves of games, and tiles to browse by genre and system ----------

    private inner class Tile(val title: String, val sub: String, val color: Int, val kind: Int, val game: Game?, val open: (() -> Unit)?)
    private inner class Shelf(val title: String, val tiles: List<Tile>)

    private fun genreOf(g: Game): String? = genreOfGame(g)

    private val genreColors = intArrayOf(0xFFD0342C.toInt(), 0xFF3E5CC8.toInt(), 0xFF1F9D63.toInt(), 0xFFE8761A.toInt(), 0xFF8A4CB5.toInt(), 0xFF2FA6B8.toInt(), 0xFFC2185B.toInt(), 0xFF5C8A3A.toInt())

    private inner class Featured {
        private var built = -1
        private var shelves: List<Shelf> = emptyList()
        private var shelfSel = 0
        private val hsel = IntArray(12)
        private val hTarget = IntArray(12)
        private val hAnim = FloatArray(12)
        private var vAnim = 0f
        private var vTarget = 0f
        private val hits = ArrayList<Pair<RectF, Tile>>()

        private val heroH get() = 176f
        private val shelfH get() = 204f

        private fun gameTile(g: Game) = Tile(g.title, g.system.name, g.system.color, Ic.PLAY, g, null)

        private fun build() {
            if (built == act.version) return
            built = act.version
            val all = act.games
            val st = act.prefs
            val recent = all.filter { (st.peek(it.rep.path)?.launches ?: 0) > 0 }.sortedByDescending { st.peek(it.rep.path)!!.lastPlayed }.take(14)
            val favs = all.filter { st.peek(it.rep.path)?.fav == true }.sortedBy { it.title.lowercase() }.take(14)
            val added = all.sortedByDescending { it.modified }.take(14)
            val rated = all.filter { (it.rep.meta?.rating ?: 0f) > 0f }.sortedByDescending { it.rep.meta?.rating ?: 0f }.take(14)
            val fresh = all.filter { (st.peek(it.rep.path)?.launches ?: 0) == 0 }.shuffled(kotlin.random.Random(7)).take(14)
            val hero = (recent.take(3) + favs + rated.take(3) + added).distinctBy { it.path }.take(6)
            val list = ArrayList<Shelf>()
            list.add(Shelf("Featured", hero.map { gameTile(it) }))
            if (recent.isNotEmpty()) list.add(Shelf("Continue playing", recent.map { gameTile(it) }))
            if (favs.isNotEmpty()) list.add(Shelf("Favourites", favs.map { gameTile(it) }))
            if (rated.size >= 3) list.add(Shelf("Top rated", rated.map { gameTile(it) }))
            list.add(Shelf("Recently added", added.map { gameTile(it) }))
            if (fresh.size >= 3) list.add(Shelf("Try something new", fresh.map { gameTile(it) }))
            // genres come from gamelist.xml
            val genres = all.groupBy { genreOf(it) }.filterKeys { it != null }.toList().sortedByDescending { it.second.size }.take(24)
            if (genres.isNotEmpty()) {
                list.add(Shelf("Browse by genre", genres.mapIndexed { i, (name, games) ->
                    val n = name!!
                    Tile(n, "${games.size} game${if (games.size == 1) "" else "s"}", genreColors[(n.hashCode() and 0x7fffffff) % genreColors.size], Ic.STAR, null) {
                        shell.push(GameListScreen(act, n, { games }, true, null))
                    }
                }))
            }
            list.add(Shelf("Browse by system", act.bySystem.map { (sys, games) ->
                Tile(sys.short, "${games.size} game${if (games.size == 1) "" else "s"}", sys.color, Ic.GRID, null) { shell.push(SystemScreen(act, sys)) }
            }))
            shelves = list.take(11)
            shelfSel = shelfSel.coerceIn(0, shelves.size - 1)
            for (i in hsel.indices) hsel[i] = hsel[i].coerceIn(0, max(0, (shelves.getOrNull(i)?.tiles?.size ?: 1) - 1))
        }

        private fun tops(): FloatArray {
            val t = FloatArray(shelves.size + 1)
            for (i in shelves.indices) t[i + 1] = t[i] + (if (i == 0) heroH else shelfH)
            return t
        }

        fun draw(c: Canvas, ui: Ui, r: RectF) {
            build()
            hits.clear()
            val sc = ui.sc
            val tops = tops()
            val total = tops[shelves.size] * sc
            vTarget = (tops[shelfSel] * sc).coerceIn(0f, max(0f, total - r.height()))
            vAnim += (vTarget - vAnim) * ui.k
            if (abs(vTarget - vAnim) < 0.5f) vAnim = vTarget else ui.busy = true
            c.save()
            c.clipRect(r)
            for ((si, shelf) in shelves.withIndex()) {
                val top = r.top + tops[si] * sc - vAnim
                val h = (if (si == 0) heroH else shelfH) * sc
                if (top + h < r.top || top > r.bottom) continue
                if (si == 0) drawHero(c, ui, r, top, shelf) else drawShelf(c, ui, r, si, top, shelf)
            }
            c.restore()
            if (total > r.height()) ui.scrollbar(c, r.right, r.top, r.bottom, vAnim / (total - r.height()), r.height() / total)
        }

        private fun drawHero(c: Canvas, ui: Ui, r: RectF, top: Float, shelf: Shelf) {
            val sc = ui.sc
            val tile = shelf.tiles.getOrNull(hsel[0]) ?: return
            val g = tile.game ?: return
            val card = RectF(r.left + 14f * sc, top + 8f * sc, r.right - 14f * sc, top + 154f * sc)
            if (shelfSel == 0) ui.highlight(c, RectF(card).apply { inset(-5f * sc, -5f * sc) }, 14f)
            ui.p.style = Paint.Style.FILL
            ui.p.shader = null
            ui.p.color = Belle.PANEL
            c.drawRoundRect(card, 8f * sc, 8f * sc, ui.p)
            ui.p.color = g.system.color
            c.drawRect(card.left, card.top + 8f * sc, card.left + 5f * sc, card.bottom - 8f * sc, ui.p)
            val side = card.height() - 20f * sc
            val art = RectF(card.left + 20f * sc, card.top + 10f * sc, card.left + 20f * sc + side, card.top + 10f * sc + side)
            drawArt(c, ui, act, shell, g, art)
            val x = art.right + 20f * sc
            val maxW = card.right - x - 16f * sc
            val played = (act.prefs.peek(g.rep.path)?.launches ?: 0) > 0
            ui.text(c, if (played) "CONTINUE PLAYING" else "FEATURED GAME", x, card.top + 30f * sc, 13f * sc, Belle.BLUE_LIGHT, face = ui.medium)
            ui.text(c, g.title, x, card.top + 62f * sc, 26f * sc, maxW = maxW)
            val genre = genreOf(g)
            ui.text(c, g.system.name + (genre?.let { " - $it" } ?: ""), x, card.top + 88f * sc, 18f * sc, Belle.TEXT_MID, maxW = maxW, face = ui.light)
            val st = act.prefs.peek(g.rep.path)
            val line = if (st != null && st.launches > 0) "Played ${st.launches}x - ${playTime(st.playMs)}" else "Not played yet"
            ui.text(c, line, x, card.top + 112f * sc, 16f * sc, Belle.TEXT_MID, maxW = maxW, face = ui.light)
            val btn = RectF(card.right - 130f * sc, card.bottom - 52f * sc, card.right - 14f * sc, card.bottom - 10f * sc)
            ui.pushButton(c, btn, "Play", shelfSel == 0)
            val n = shelf.tiles.size
            val cx = card.centerX()
            for (i in 0 until n) {
                ui.p.color = if (i == hsel[0]) Color.WHITE else ui.al(Color.WHITE, 90)
                c.drawCircle(cx + (i - (n - 1) / 2f) * 14f * sc, card.bottom + 12f * sc, 3.2f * sc, ui.p)
            }
            hits.add(RectF(card) to tile)
        }

        private fun drawShelf(c: Canvas, ui: Ui, r: RectF, si: Int, top: Float, shelf: Shelf) {
            val sc = ui.sc
            ui.text(c, shelf.title, r.left + 16f * sc, top + 26f * sc, 20f * sc, face = ui.medium)
            ui.p.style = Paint.Style.FILL
            ui.p.shader = null
            ui.p.color = Belle.SEPARATOR
            c.drawRect(r.left + 14f * sc, top + 34f * sc, r.right - 14f * sc, top + 35f * sc, ui.p)
            val tile = 112f * sc
            val pitch = 128f * sc
            val vis = max(1, ((r.width() - 28f * sc) / pitch).toInt())
            if (hsel[si] < hTarget[si]) hTarget[si] = hsel[si] else if (hsel[si] >= hTarget[si] + vis) hTarget[si] = hsel[si] - vis + 1
            hAnim[si] += (hTarget[si] - hAnim[si]) * ui.k
            if (abs(hTarget[si] - hAnim[si]) < 0.004f) hAnim[si] = hTarget[si].toFloat() else ui.busy = true
            for ((i, t) in shelf.tiles.withIndex()) {
                val x = r.left + 18f * sc + (i - hAnim[si]) * pitch
                if (x + tile < r.left || x > r.right) continue
                val box = RectF(x, top + 46f * sc, x + tile, top + 46f * sc + tile)
                val sel = shelfSel == si && hsel[si] == i
                val hit = RectF(box.left - 6f * sc, box.top - 6f * sc, box.right + 6f * sc, box.bottom + 50f * sc)
                if (sel) ui.highlight(c, hit, 12f)
                val art = if (sel) RectF(box).apply { inset(4f * sc, 4f * sc) } else box
                if (t.game != null) drawArt(c, ui, act, shell, t.game, art) else ui.iconFrame(c, art, t.color, t.kind)
                ui.text(c, t.title, box.centerX(), box.bottom + 20f * sc, 15f * sc, Color.WHITE, Paint.Align.CENTER, tile + 8f * sc)
                ui.text(c, t.sub, box.centerX(), box.bottom + 38f * sc, 13f * sc, Belle.TEXT_MID, Paint.Align.CENTER, tile + 8f * sc, face = ui.light)
                hits.add(hit to t)
            }
        }

        private fun use(t: Tile) {
            if (t.game != null) startGame(act, shell, t.game) else t.open?.invoke()
        }

        fun act(a: Act): Boolean {
            if (shelves.isEmpty()) return false
            val sh = shelves[shelfSel]
            when (a) {
                Act.UP -> if (shelfSel > 0) shelfSel-- else return false
                Act.DOWN -> if (shelfSel < shelves.size - 1) shelfSel++ else return false
                Act.LEFT -> if (shelfSel == 0) hsel[0] = (hsel[0] - 1 + sh.tiles.size) % sh.tiles.size else if (hsel[shelfSel] > 0) hsel[shelfSel]--
                Act.RIGHT -> if (shelfSel == 0) hsel[0] = (hsel[0] + 1) % sh.tiles.size else if (hsel[shelfSel] < sh.tiles.size - 1) hsel[shelfSel]++
                Act.OK -> sh.tiles.getOrNull(hsel[shelfSel])?.let { use(it) }
                else -> return false
            }
            return true
        }

        fun tap(x: Float, y: Float): Boolean {
            for ((rect, t) in hits) if (rect.contains(x, y)) {
                use(t)
                return true
            }
            return false
        }

        fun focused(): Game? = shelves.getOrNull(shelfSel)?.tiles?.getOrNull(hsel[shelfSel])?.game

        fun scroll(d: Int) {
            shelfSel = (shelfSel + d).coerceIn(0, max(0, shelves.size - 1))
        }
    }
}

/** A system's games, as a list; A starts one. */
fun SystemScreen(act: GamesActivity, system: GameSystem): Screen =
    GameListScreen(act, system.name, { act.bySystem.firstOrNull { it.first === system }?.second ?: emptyList() }, false, system)

/**
 * Any list of games - a system, a genre, everything - for libraries of thousands: search as you type,
 * show only favourites / played / never played, pick a genre, change the order, jump by letter (A to Z order).
 */
class GameListScreen(
    private val act: GamesActivity,
    private val name: String,
    private val source: () -> List<Game>,
    showSystem: Boolean,
    private val system: GameSystem?,
    private val autoSearch: Boolean = false,
) : Screen() {
    private val gameList = GameList(act, showSystem)
    private var query = ""
    private var filter = 0 // 0 all, 1 favourites, 2 played, 3 never played
    private var sort = 0 // 0 A-Z, 1 recently added, 2 most played, 3 last played
    private var genre: String? = null

    private var cached: List<Game> = emptyList()
    private var cacheKey = ""
    private var cacheVersion = -1
    private var cacheSource = 0

    private val filterNames = listOf("All games", "Favourites", "Played", "Never played")
    private val sortNames = listOf("A to Z", "Recently added", "Most played", "Last played")

    /** The games to show, rebuilt only when something that affects them changed. */
    private val games: List<Game>
        get() {
            val src = source()
            val key = "$query|$filter|$sort|$genre"
            if (cacheVersion == act.version && cacheKey == key && cacheSource == src.size) return cached
            cacheVersion = act.version
            cacheKey = key
            cacheSource = src.size
            val st = act.prefs
            val q = query.trim().lowercase()
            var out: List<Game> = src
            if (q.isNotEmpty()) out = out.filter { it.title.lowercase().contains(q) || it.rawName.lowercase().contains(q) }
            if (genre != null) out = out.filter { genreOfGame(it) == genre }
            out = when (filter) {
                1 -> out.filter { st.peek(it.rep.path)?.fav == true }
                2 -> out.filter { (st.peek(it.rep.path)?.launches ?: 0) > 0 }
                3 -> out.filter { (st.peek(it.rep.path)?.launches ?: 0) == 0 }
                else -> out
            }
            out = when (sort) {
                1 -> out.sortedByDescending { it.modified }
                2 -> out.sortedByDescending { st.peek(it.rep.path)?.launches ?: 0 }
                3 -> out.sortedByDescending { st.peek(it.rep.path)?.lastPlayed ?: 0L }
                else -> out
            }
            cached = out
            return out
        }

    override val title: String
        get() {
            val n = games.size
            val extra = ArrayList<String>()
            if (query.isNotBlank()) extra.add("\"${query.trim()}\"")
            if (filter != 0) extra.add(filterNames[filter].lowercase())
            genre?.let { extra.add(it) }
            return name + (if (extra.isNotEmpty()) " - " + extra.joinToString(", ") else "") + " ($n)"
        }

    override fun onShow() {
        if (autoSearch) {
            autoSearchDone()
        }
    }

    private var started = false

    private fun autoSearchDone() {
        if (started) return
        started = true
        search()
    }

    private fun search() {
        act.askSearch(query) { q ->
            query = q
            gameList.list.sel = 0
            gameList.list.top = 0
            shell.invalidate()
        }
    }

    override fun tools(): List<Tool> {
        val g = gameList.selected(games)
        return listOf(
            Tool(Ic.SEARCH, { search() }),
            Tool(Ic.PLAY, { gameList.selected(games)?.let { startGame(act, shell, it) } }, g != null),
        )
    }

    override fun menu(): List<MenuItem> {
        val items = ArrayList<MenuItem>()
        items.add(MenuItem("Search...") { search() })
        items.add(MenuItem("Show: ${filterNames[filter]}") {
            shell.showOverlay(ListDialog("Show", filterNames, filter) { filter = it; gameList.list.sel = 0; gameList.list.top = 0 })
        })
        items.add(MenuItem("Order: ${sortNames[sort]}") {
            shell.showOverlay(ListDialog("Order", sortNames, sort) { sort = it; gameList.list.sel = 0; gameList.list.top = 0 })
        })
        val genres = source().mapNotNull { genreOfGame(it) }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }.map { it.first }
        if (genres.isNotEmpty()) {
            items.add(MenuItem("Genre: ${genre ?: "all"}") {
                val opts = listOf("All genres") + genres
                shell.showOverlay(ListDialog("Genre", opts, if (genre == null) 0 else opts.indexOf(genre)) { i ->
                    genre = if (i == 0) null else opts[i]
                    gameList.list.sel = 0
                    gameList.list.top = 0
                })
            })
        }
        if (query.isNotBlank() || filter != 0 || genre != null) {
            items.add(MenuItem("Clear search and filters") { query = ""; filter = 0; genre = null; gameList.list.sel = 0; gameList.list.top = 0 })
        }
        items.addAll(gameMenu(act, shell, gameList.selected(games)))
        if (system?.emu == Emu.PPSSPP) {
            items.add(MenuItem("PSP opens in: " + if (act.prefs.pspWithPpsspp) "PPSSPP" else "RetroArch") { act.prefs.pspWithPpsspp = !act.prefs.pspWithPpsspp })
        }
        return items
    }

    private fun rect() = RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom)
    private fun vis() = gameList.list.visible(shell.ui, shell.contentBottom - shell.contentTop)

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        val g = games
        if (g.isEmpty()) {
            val msg = if (query.isNotBlank() || filter != 0 || genre != null) com.belleui.kit.Keys.hint("Nothing matches.\nPress X, then \"Clear search and filters\".") else "No games here yet"
            val lines = msg.split("\n")
            for ((i, l) in lines.withIndex()) ui.text(c, l, r.centerX(), ui.baselineFor(r.centerY() + (i - (lines.size - 1) / 2f) * 30f * ui.sc, 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER, maxW = r.width() - 40f * ui.sc)
            return
        }
        gameList.draw(c, ui, r, g, shell)
    }

    override fun onAct(a: Act): Boolean {
        val g = games
        if (g.isEmpty()) return false
        if (a == Act.OK) {
            gameList.selected(g)?.let { startGame(act, shell, it) }
            return true
        }
        return gameList.act(a, g, vis(), letters = sort == 0)
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val g = games
        val i = gameList.hit(shell.ui, rect(), y, g.size)
        if (i < 0) return false
        gameList.list.sel = i
        startGame(act, shell, g[i])
        return true
    }

    override fun onScroll(dy: Float) = gameList.scroll(if (dy > 0) 1 else -1, games.size, vis())
}

// ================================================================================================
// one game
// ================================================================================================

class GameScreen(private val act: GamesActivity, game0: Game) : Screen() {
    /** The title as the library shows it; [cur] is the version or disc that Play starts. */
    private val game: Game = game0.rep
    private var cur: Game = game0
    private val versions get() = game.variants

    override val title get() = game.title
    private val playBtn = RectF()
    private val favBtn = RectF()

    private fun fav() = act.prefs.peek(game.path)?.fav == true

    override fun tools(): List<Tool> {
        val t = arrayListOf(Tool(Ic.FAVOURITE, { toggleFav() }), Tool(Ic.PLAY, { play() }))
        if (versions.size > 1) t.add(0, Tool(Ic.LIST, { chooseVersion() }))
        return t
    }

    private fun chooseVersion() {
        val labels = versions.map { v -> v.label + "  (" + size(v.size) + ")" }
        shell.showOverlay(ListDialog("Version or disc", labels, versions.indexOf(cur).coerceAtLeast(0)) { i ->
            cur = versions[i]
            shell.invalidate()
        })
    }

    override fun menu(): List<MenuItem> {
        val items = ArrayList<MenuItem>()
        if (versions.size > 1) items.add(MenuItem("Version: ${cur.label}") { chooseVersion() })
        items.add(MenuItem(if (fav()) "Remove from favourites" else "Add to favourites") { toggleFav() })
        items.add(MenuItem("Details") { details() })
        if (game.system.emu == Emu.PPSSPP) {
            items.add(MenuItem("PSP opens in: " + if (act.prefs.pspWithPpsspp) "PPSSPP" else "RetroArch") { act.prefs.pspWithPpsspp = !act.prefs.pspWithPpsspp })
        }
        if (act.emus.emulatorFor(game) == Emu.RETROARCH) {
            items.add(MenuItem("Core: ${act.prefs.core(game.system)}") {
                shell.showOverlay(ListDialog("Core for ${game.system.short}", game.system.cores, game.system.cores.indexOf(act.prefs.core(game.system))) { i -> act.prefs.setCore(game.system, game.system.cores[i]) })
            })
        }
        return items
    }

    private fun toggleFav() {
        act.toggleFavourite(game)
        shell.toast(if (fav()) "Added to favourites" else "Removed from favourites")
    }

    private fun play() {
        val err = act.play(cur)
        shell.toast(err ?: "Starting ${game.title}...")
    }

    private fun details() {
        val st = act.prefs.peek(game.path)
        val played = if (st != null && st.launches > 0) "Played ${st.launches}x, ${playTime(st.playMs)}" else "Not played yet"
        shell.showOverlay(QueryDialog("${cur.rawName}\n${game.system.name}  -  ${size(cur.size)}\n$played\n${cur.file.parent}", "OK", null) { })
    }

    /** Word-wraps [text] into at most [maxLines] lines that fit [maxW]. */
    private fun wrap(ui: Ui, text: String, size: Float, maxW: Float, maxLines: Int): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (w in text.split(Regex("\\s+"))) {
            val t = if (line.isEmpty()) w else "$line $w"
            if (ui.measure(t, size, ui.light) <= maxW) {
                line = t
            } else {
                if (line.isNotEmpty()) out.add(line)
                line = w
                if (out.size == maxLines) break
            }
        }
        if (out.size < maxLines && line.isNotEmpty()) out.add(line)
        if (out.size == maxLines && text.length > out.joinToString(" ").length) out[maxLines - 1] = out[maxLines - 1].trimEnd('.', ',') + "..."
        return out
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        val sc = ui.sc
        val pad = 20f * sc
        val side = min(r.height() - 2 * pad, 250f * sc)
        val art = RectF(r.left + pad, r.top + pad, r.left + pad + side, r.top + pad + side)
        drawArt(c, ui, act, shell, game, art)
        val x = art.right + pad
        val maxW = r.right - x - pad
        val m = game.meta
        var y = r.top + pad + 28f * sc
        ui.text(c, game.title, x, y, 26f * sc, maxW = maxW)
        y += 28f * sc
        val sysLine = game.system.name + (m?.year?.let { " - $it" } ?: "")
        ui.text(c, sysLine, x, y, 18f * sc, Belle.TEXT_MID, maxW = maxW - 90f * sc, face = ui.light)
        // rating from gamelist.xml, as five stars
        m?.rating?.let { rating ->
            val stars = (rating * 5f + 0.5f).toInt().coerceIn(0, 5)
            for (i in 0 until 5) ui.glyph(c, Ic.STAR, x + maxW - 84f * sc + i * 17f * sc, y - 6f * sc, 15f * sc, if (i < stars) 0xFFFFB300.toInt() else ui.al(Color.WHITE, 60))
        }
        val credits = listOfNotNull(m?.developer, m?.publisher?.takeIf { it != m.developer }, m?.genre, m?.players?.let { "$it player${if (it == "1") "" else "s"}" }).joinToString("  -  ")
        if (credits.isNotEmpty()) {
            y += 24f * sc
            ui.text(c, credits, x, y, 15f * sc, Belle.TEXT_MID, maxW = maxW, face = ui.light)
        }
        val st = act.prefs.peek(game.path)
        val played = if (st != null && st.launches > 0) {
            "Played ${st.launches}x - ${playTime(st.playMs)} - last ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(st.lastPlayed))}"
        } else "Not played yet"
        y += 24f * sc
        ui.text(c, played, x, y, 15f * sc, Belle.TEXT_MID, maxW = maxW, face = ui.light)
        if (versions.size > 1) {
            y += 24f * sc
            ui.text(c, "${versions.size} versions - playing: ${cur.label}", x, y, 15f * sc, Belle.BLUE_LIGHT, maxW = maxW, face = ui.light)
        }
        val how = when (act.emus.emulatorFor(game)) {
            Emu.PPSSPP -> "Opens in PPSSPP"
            Emu.RETROARCH -> if (act.emus.retroArch() != null) "Opens in RetroArch - ${act.prefs.core(game.system)} core" else "RetroArch is not installed"
        }
        y += 24f * sc
        ui.text(c, how, x, y, 15f * sc, if (act.emus.retroArch() == null && act.emus.emulatorFor(game) == Emu.RETROARCH) 0xFFE5844D.toInt() else Belle.BLUE_LIGHT, maxW = maxW, face = ui.light)
        m?.desc?.takeIf { it.isNotBlank() }?.let { d ->
            var dy = y + 24f * sc
            for (line in wrap(ui, d, 15f * sc, maxW, 2)) {
                ui.text(c, line, x, dy, 15f * sc, ui.al(Color.WHITE, 200), maxW = maxW, face = ui.light)
                dy += 20f * sc
            }
        }
        val by = art.bottom - 52f * sc
        playBtn.set(x, by, x + 150f * sc, by + 52f * sc)
        favBtn.set(x + 160f * sc, by, min(r.right - pad, x + 160f * sc + 190f * sc), by + 52f * sc)
        ui.pushButton(c, playBtn, "Play", true)
        ui.pushButton(c, favBtn, if (fav()) "Unfavourite" else "Favourite", false)
    }

    override fun onAct(a: Act): Boolean {
        if (a == Act.OK) {
            play()
            return true
        }
        return false
    }

    override fun onTap(x: Float, y: Float): Boolean {
        if (favBtn.contains(x, y)) toggleFav() else play()
        return true
    }
}

// ================================================================================================
// settings
// ================================================================================================

class SettingsScreen(private val act: GamesActivity) : Screen() {
    override val title get() = "Settings"
    private val list = ListState()

    private fun rows(): List<Row> {
        val ra = act.emus.installedRetroArch()
        return listOf(
            Row("ROM folders", "${act.prefs.roots.size} folder${if (act.prefs.roots.size == 1) "" else "s"}", iconKind = Ic.LIST, iconColor = 0xFF3E5CC8.toInt(), drill = true),
            Row("File access", if (act.hasAccess()) "Allowed" else com.belleui.kit.Keys.hint("Not allowed - press A"), iconKind = Ic.CHECK, iconColor = 0xFF1F9D63.toInt()),
            Row("PSP games open with", if (act.prefs.pspWithPpsspp) "PPSSPP" else "RetroArch (ppsspp core)", iconKind = Ic.PLAY, iconColor = 0xFF2B2F36.toInt()),
            Row("Download box art", "From the libretro thumbnail server", iconKind = Ic.IMAGES, iconColor = 0xFFC2185B.toInt(), check = act.prefs.downloadArt),
            Row("RetroArch app", act.emus.retroArch() ?: "Not installed", iconKind = Ic.TOOLS, iconColor = 0xFF58606B.toInt(), drill = ra.size > 1),
            Row("Find ROM cards", "Look for roms/<system> folders on SD cards", iconKind = Ic.SEARCH, iconColor = 0xFF1F9D63.toInt(), drill = true),
        )
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        val rows = rows()
        list.draw(c, ui, r, rows.size) { rows[it] }
    }

    private fun open(i: Int) {
        when (i) {
            0 -> shell.push(FoldersScreen(act))
            1 -> act.askAccess { act.rescan { shell.invalidate() } }
            2 -> act.prefs.pspWithPpsspp = !act.prefs.pspWithPpsspp
            3 -> act.prefs.downloadArt = !act.prefs.downloadArt
            4 -> {
                val ra = act.emus.installedRetroArch()
                if (ra.size > 1) shell.showOverlay(ListDialog("RetroArch build", ra, ra.indexOf(act.emus.retroArch())) { k -> act.prefs.retroarchPackage = ra[k] })
                else if (ra.isEmpty()) shell.toast("Install RetroArch first")
            }
            5 -> {
                shell.toast("Looking for ROM cards...")
                Thread {
                    val added = act.prefs.addDiscoveredRoots()
                    shell.post {
                        shell.toast(if (added.isEmpty()) "No new ROM folders found" else "Added ${added.size} folder${if (added.size == 1) "" else "s"}")
                        act.rescan { shell.invalidate() }
                    }
                }.start()
            }
        }
    }

    override fun onAct(a: Act): Boolean {
        if (a == Act.OK) {
            open(list.sel)
            return true
        }
        return list.act(a, rows().size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val i = list.hit(shell.ui, RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom), y, rows().size)
        if (i < 0) return false
        list.sel = i
        open(i)
        return true
    }
}

class FoldersScreen(private val act: GamesActivity) : Screen() {
    override val title get() = "ROM folders"
    private val list = ListState()
    private var roots = act.prefs.roots

    override fun tools() = listOf(Tool(Ic.ADD, { add() }))

    override fun menu() = listOf(
        MenuItem("Add folder") { add() },
        MenuItem("Remove folder") {
            val f = roots.getOrNull(list.sel)
            if (f != null) {
                roots = roots - f
                act.prefs.roots = roots
                act.rescan { shell.invalidate() }
            }
        },
    )

    private fun add() {
        act.pickFolder { path ->
            if (path != null && path !in roots) {
                roots = roots + path
                act.prefs.roots = roots
                act.rescan { shell.invalidate() }
                shell.toast("Scanning $path")
            }
            shell.invalidate()
        }
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        if (roots.isEmpty()) {
            ui.text(c, "No folders yet", r.centerX(), ui.baselineFor(r.centerY() - 14f * ui.sc, 22f * ui.sc), 22f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
            ui.text(c, com.belleui.kit.Keys.hint("Press Y to add the folder that holds your ROMs"), r.centerX(), ui.baselineFor(r.centerY() + 16f * ui.sc, 18f * ui.sc, ui.light), 18f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER, face = ui.light)
            return
        }
        list.draw(c, ui, r, roots.size) { i -> Row(roots[i].substringAfterLast('/'), roots[i], iconKind = Ic.LIST, iconColor = 0xFF3E5CC8.toInt()) }
    }

    override fun onAct(a: Act): Boolean {
        if (roots.isEmpty()) return false
        return list.act(a, roots.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }

    override fun onScroll(dy: Float) = list.scrollBy(if (dy > 0) 1 else -1, roots.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
}
