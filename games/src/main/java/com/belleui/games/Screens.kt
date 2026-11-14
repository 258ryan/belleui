package com.belleui.games

import android.graphics.Canvas
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

/** Art (or a placeholder) for a game, fitted into [box]. */
private fun drawArt(c: Canvas, ui: Ui, act: GamesActivity, shell: com.belleui.kit.BelleShell, g: Game, box: RectF) {
    val bmp = act.art.get(g) { shell.invalidate() }
    if (bmp != null) {
        ui.drawBitmapFit(c, bmp, box)
        return
    }
    ui.p.style = Paint.Style.FILL
    ui.p.shader = null
    ui.p.color = 0xFF1E1E1E.toInt()
    c.drawRoundRect(box, 6f * ui.sc, 6f * ui.sc, ui.p)
    ui.p.color = g.system.color
    c.drawRect(box.left, box.top, box.right, box.top + 6f * ui.sc, ui.p)
    val s = min(box.width(), box.height()) * 0.62f
    ui.tmp.set(box.centerX() - s / 2, box.centerY() - s / 2 + 4f * ui.sc, box.centerX() + s / 2, box.centerY() + s / 2 + 4f * ui.sc)
    ui.gfx.draw(c, "qgn_menu_games", ui.tmp)
    ui.text(c, g.system.short, box.left + 6f * ui.sc, box.top + 24f * ui.sc, 14f * ui.sc, ui.al(Color.WHITE, 230), face = ui.medium)
}

/** Game tiles in a grid, shared by Games and a system's page. */
class GameGrid(private val act: GamesActivity) {
    val grid = GridState(5, 1.22f)

    fun draw(c: Canvas, ui: Ui, r: RectF, games: List<Game>, shell: com.belleui.kit.BelleShell) {
        grid.draw(c, ui, r, games.size) { cv, cell, i, selected ->
            val g = games[i]
            val pad = (if (selected) 10f else 5f) * ui.sc
            val box = RectF(cell.left + pad, cell.top + pad, cell.right - pad, cell.bottom - 30f * ui.sc)
            drawArt(cv, ui, act, shell, g, box)
            ui.text(cv, g.title, cell.centerX(), cell.bottom - 10f * ui.sc, 15f * ui.sc, Color.WHITE, Paint.Align.CENTER, cell.width() - 8f * ui.sc)
        }
    }
}

// ================================================================================================
// root: Featured / Games / Systems / Emulators
// ================================================================================================

class RootScreen(private val act: GamesActivity) : Screen() {
    override val title get() = "Games"
    override val tabs = listOf(TabDef("Featured", Ic.FAVOURITE), TabDef("Games", Ic.GRID), TabDef("Systems", Ic.LIST), TabDef("Emulators", Ic.TOOLS))

    private val featured = Featured()
    private val gameGrid = GameGrid(act)
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
        if (tab == 3) checkRemote()
    }

    override fun tools() = listOf(
        Tool(Ic.REFRESH, { rescan() }),
        Tool(Ic.TOOLS, { shell.push(SettingsScreen(act)) }),
    )

    override fun menu() = listOf(
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
        !act.hasAccess() -> "Games needs access to your files to find ROMs.\nPress A to allow."
        act.scanning && act.games.isEmpty() -> "Looking for games..."
        act.games.isEmpty() -> "No games found.\nIn Settings use \"Find ROM cards\", or add a ROM folder."
        else -> null
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        if (tab >= 2) {
            if (tab == 2) drawSystems(c, ui, r) else drawEmulators(c, ui, r)
            return
        }
        val msg = emptyMessage()
        if (msg != null) {
            message(c, ui, r, msg)
            return
        }
        if (tab == 0) featured.draw(c, ui, r) else gameGrid.draw(c, ui, r, act.games, shell)
    }

    // ---- systems ----------------------------------------------------------------------------

    private fun systemsWithGames(): List<Pair<GameSystem, Int>> =
        Systems.all.map { s -> s to act.games.count { it.system === s } }.filter { it.second > 0 }

    private fun drawSystems(c: Canvas, ui: Ui, r: RectF) {
        val list = systemsWithGames()
        if (list.isEmpty()) {
            message(c, ui, r, emptyMessage() ?: "No games found")
            return
        }
        systemList.draw(c, ui, r, list.size) { i ->
            val (s, n) = list[i]
            val emu = if (s.emu == Emu.PPSSPP && act.prefs.pspWithPpsspp) "PPSSPP" else "RetroArch (${act.prefs.core(s)})"
            Row(s.name, "$n game${if (n == 1) "" else "s"} - $emu", iconKind = Ic.PLAY, iconColor = s.color, drill = true)
        }
    }

    // ---- emulators and the RetroArch remote ----------------------------------------------------

    private fun emuRows(): List<Row> {
        val ra = act.emus.retroArch()
        val pp = act.emus.ppsspp()
        return listOf(
            Row("RetroArch", if (ra != null) "Installed - $ra" else "Not installed", iconKind = Ic.PLAY, iconColor = 0xFF3E5CC8.toInt(), drill = ra != null, dim = ra == null),
            Row("PPSSPP", if (pp != null) "Installed - $pp" else "Not installed", iconKind = Ic.PLAY, iconColor = 0xFF2B2F36.toInt(), drill = pp != null, dim = pp == null),
            Row("RetroArch remote", remoteStatus ?: "Press A to check", iconKind = Ic.WLAN_ON, iconColor = 0xFF1F9D63.toInt()),
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
                if (a == Act.OK) {
                    openGame(act.games[gameGrid.grid.sel.coerceIn(0, act.games.size - 1)])
                    true
                } else {
                    gameGrid.grid.act(a, act.games.size, rect())
                }
            }
            2 -> {
                val list = systemsWithGames()
                if (list.isEmpty()) return false
                if (a == Act.OK) {
                    val s = list[systemList.sel.coerceIn(0, list.size - 1)].first
                    shell.push(SystemScreen(act, s))
                    true
                } else systemList.act(a, list.size, systemList.visible(shell.ui, shell.contentBottom - shell.contentTop))
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
                val i = gameGrid.grid.hit(r, x, y, act.games.size)
                if (i < 0) return false
                gameGrid.grid.sel = i
                openGame(act.games[i])
            }
            2 -> {
                val list = systemsWithGames()
                val i = systemList.hit(shell.ui, r, y, list.size)
                if (i < 0) return false
                systemList.sel = i
                shell.push(SystemScreen(act, list[i].first))
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
            1 -> gameGrid.grid.scrollBy(d, act.games.size, rect())
            2 -> systemList.scrollBy(d, systemsWithGames().size, systemList.visible(shell.ui, shell.contentBottom - shell.contentTop))
            else -> emuList.scrollBy(d, emuRows().size, emuList.visible(shell.ui, shell.contentBottom - shell.contentTop))
        }
    }

    // ---- Featured: a hero banner and shelves, like a store front ---------------------------------

    private inner class Shelf(val title: String, val games: List<Game>)

    private inner class Featured {
        private var built = -1
        private var shelves: List<Shelf> = emptyList()
        private var shelfSel = 0
        private val hsel = IntArray(4)
        private val hTarget = IntArray(4)
        private val hAnim = FloatArray(4)
        private var vAnim = 0f
        private var vTarget = 0f
        private val hits = ArrayList<Pair<RectF, Game>>()

        private val heroH get() = 176f
        private val shelfH get() = 190f

        private fun build() {
            if (built == act.version) return
            built = act.version
            val all = act.games
            val st = act.prefs
            val recent = all.filter { (st.peek(it.path)?.launches ?: 0) > 0 }.sortedByDescending { st.peek(it.path)!!.lastPlayed }.take(14)
            val favs = all.filter { st.peek(it.path)?.fav == true }.sortedBy { it.title.lowercase() }.take(14)
            val added = all.sortedByDescending { it.modified }.take(14)
            val hero = (favs + recent + added).distinctBy { it.path }.take(6)
            val list = ArrayList<Shelf>()
            list.add(Shelf("Featured", hero))
            if (recent.isNotEmpty()) list.add(Shelf("Recently played", recent))
            if (favs.isNotEmpty()) list.add(Shelf("Favourites", favs))
            list.add(Shelf("Recently added", added))
            shelves = list.take(4)
            shelfSel = shelfSel.coerceIn(0, shelves.size - 1)
            for (i in hsel.indices) hsel[i] = hsel[i].coerceIn(0, max(0, (shelves.getOrNull(i)?.games?.size ?: 1) - 1))
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
            val g = shelf.games.getOrNull(hsel[0]) ?: return
            val card = RectF(r.left + 14f * sc, top + 8f * sc, r.right - 14f * sc, top + 154f * sc)
            if (shelfSel == 0) ui.highlight(c, RectF(card).apply { inset(-5f * sc, -5f * sc) }, 14f)
            ui.p.style = Paint.Style.FILL
            ui.p.shader = null
            ui.p.color = Belle.PANEL
            c.drawRoundRect(card, 8f * sc, 8f * sc, ui.p)
            ui.p.color = g.system.color
            c.drawRect(card.left, card.top + 8f * sc, card.left + 5f * sc, card.bottom - 8f * sc, ui.p)
            val art = RectF(card.left + 18f * sc, card.top + 10f * sc, card.left + 18f * sc + 126f * sc, card.bottom - 10f * sc)
            drawArt(c, ui, act, shell, g, art)
            val x = art.right + 20f * sc
            val maxW = card.right - x - 16f * sc
            ui.text(c, "FEATURED GAME", x, card.top + 30f * sc, 13f * sc, Belle.BLUE_LIGHT, face = ui.medium)
            ui.text(c, g.title, x, card.top + 62f * sc, 26f * sc, maxW = maxW)
            ui.text(c, g.system.name, x, card.top + 88f * sc, 18f * sc, Belle.TEXT_MID, maxW = maxW, face = ui.light)
            val st = act.prefs.peek(g.path)
            val line = if (st != null && st.launches > 0) "Played ${st.launches}x - ${playTime(st.playMs)}" else "Not played yet"
            ui.text(c, line, x, card.top + 112f * sc, 16f * sc, Belle.TEXT_MID, maxW = maxW, face = ui.light)
            val btn = RectF(card.right - 130f * sc, card.bottom - 52f * sc, card.right - 14f * sc, card.bottom - 10f * sc)
            ui.pushButton(c, btn, "Play", shelfSel == 0)
            // page dots
            val n = shelf.games.size
            val cx = card.centerX()
            for (i in 0 until n) {
                ui.p.color = if (i == hsel[0]) Color.WHITE else ui.al(Color.WHITE, 90)
                c.drawCircle(cx + (i - (n - 1) / 2f) * 14f * sc, card.bottom + 12f * sc, 3.2f * sc, ui.p)
            }
            hits.add(RectF(card) to g)
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
            for ((i, g) in shelf.games.withIndex()) {
                val x = r.left + 18f * sc + (i - hAnim[si]) * pitch
                if (x + tile < r.left || x > r.right) continue
                val box = RectF(x, top + 46f * sc, x + tile, top + 46f * sc + tile)
                val sel = shelfSel == si && hsel[si] == i
                if (sel) ui.highlight(c, RectF(box.left - 6f * sc, box.top - 6f * sc, box.right + 6f * sc, box.bottom + 32f * sc), 12f)
                val art = if (sel) RectF(box).apply { inset(4f * sc, 4f * sc) } else box
                drawArt(c, ui, act, shell, g, art)
                ui.text(c, g.title, box.centerX(), box.bottom + 20f * sc, 15f * sc, Color.WHITE, Paint.Align.CENTER, tile)
                hits.add(RectF(box.left - 6f * sc, box.top - 6f * sc, box.right + 6f * sc, box.bottom + 32f * sc) to g)
            }
        }

        fun act(a: Act): Boolean {
            if (shelves.isEmpty()) return false
            val sh = shelves[shelfSel]
            when (a) {
                Act.UP -> if (shelfSel > 0) shelfSel-- else return false
                Act.DOWN -> if (shelfSel < shelves.size - 1) shelfSel++ else return false
                Act.LEFT -> if (shelfSel == 0) hsel[0] = (hsel[0] - 1 + sh.games.size) % sh.games.size else if (hsel[shelfSel] > 0) hsel[shelfSel]--
                Act.RIGHT -> if (shelfSel == 0) hsel[0] = (hsel[0] + 1) % sh.games.size else if (hsel[shelfSel] < sh.games.size - 1) hsel[shelfSel]++
                Act.OK -> sh.games.getOrNull(hsel[shelfSel])?.let { openGame(it) }
                else -> return false
            }
            return true
        }

        fun tap(x: Float, y: Float): Boolean {
            for ((rect, g) in hits) if (rect.contains(x, y)) {
                openGame(g)
                return true
            }
            return false
        }

        fun scroll(d: Int) {
            shelfSel = (shelfSel + d).coerceIn(0, max(0, shelves.size - 1))
        }
    }
}

/** A system's games. */
class SystemScreen(private val act: GamesActivity, private val system: GameSystem) : Screen() {
    override val title get() = system.name
    private val grid = GameGrid(act)
    private val games get() = act.games.filter { it.system === system }

    override fun menu(): List<MenuItem> {
        val items = ArrayList<MenuItem>()
        if (system.emu == Emu.PPSSPP) {
            items.add(MenuItem("PSP opens in: " + if (act.prefs.pspWithPpsspp) "PPSSPP" else "RetroArch") { act.prefs.pspWithPpsspp = !act.prefs.pspWithPpsspp })
        }
        if (system.cores.size > 1 || system.emu == Emu.RETROARCH) {
            items.add(MenuItem("Core: ${act.prefs.core(system)}") {
                shell.showOverlay(ListDialog("Core for ${system.short}", system.cores, system.cores.indexOf(act.prefs.core(system))) { i -> act.prefs.setCore(system, system.cores[i]) })
            })
        }
        return items
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) = grid.draw(c, ui, r, games, shell)

    private fun rect() = RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom)

    override fun onAct(a: Act): Boolean {
        val g = games
        if (g.isEmpty()) return false
        if (a == Act.OK) {
            shell.push(GameScreen(act, g[grid.grid.sel.coerceIn(0, g.size - 1)]))
            return true
        }
        return grid.grid.act(a, g.size, rect())
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val g = games
        val i = grid.grid.hit(rect(), x, y, g.size)
        if (i < 0) return false
        grid.grid.sel = i
        shell.push(GameScreen(act, g[i]))
        return true
    }

    override fun onScroll(dy: Float) = grid.grid.scrollBy(if (dy > 0) 1 else -1, games.size, rect())
}

// ================================================================================================
// one game
// ================================================================================================

class GameScreen(private val act: GamesActivity, private val game: Game) : Screen() {
    override val title get() = game.title
    private val playBtn = RectF()
    private val favBtn = RectF()

    private fun fav() = act.prefs.peek(game.path)?.fav == true

    override fun tools() = listOf(
        Tool(Ic.FAVOURITE, { toggleFav() }),
        Tool(Ic.PLAY, { play() }),
    )

    override fun menu(): List<MenuItem> {
        val items = ArrayList<MenuItem>()
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
        val err = act.play(game)
        shell.toast(err ?: "Starting ${game.title}...")
    }

    private fun details() {
        val st = act.prefs.peek(game.path)
        val played = if (st != null && st.launches > 0) "Played ${st.launches}x, ${playTime(st.playMs)}" else "Not played yet"
        shell.showOverlay(QueryDialog("${game.rawName}\n${game.system.name}  -  ${size(game.size)}\n$played\n${game.file.parent}", "OK", null) { })
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
            Row("File access", if (act.hasAccess()) "Allowed" else "Not allowed - press A", iconKind = Ic.CHECK, iconColor = 0xFF1F9D63.toInt()),
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
            ui.text(c, "Press Y to add the folder that holds your ROMs", r.centerX(), ui.baselineFor(r.centerY() + 16f * ui.sc, 18f * ui.sc, ui.light), 18f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER, face = ui.light)
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
