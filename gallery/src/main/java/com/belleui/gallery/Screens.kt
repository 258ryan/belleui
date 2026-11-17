package com.belleui.gallery

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import com.belleui.kit.Act
import com.belleui.kit.Belle
import com.belleui.kit.Ic
import com.belleui.kit.ListState
import com.belleui.kit.MenuItem
import com.belleui.kit.QueryDialog
import com.belleui.kit.Row
import com.belleui.kit.Screen
import com.belleui.kit.TabDef
import com.belleui.kit.Tool
import com.belleui.kit.Ui

private val LEVELS = arrayOf("Years", "Months", "Days")

/** A screen whose body is a date-grouped photo grid. */
abstract class GridScreen(protected val act: GalleryActivity) : Screen() {
    protected val grid = PhotosGrid(act) { shell }
    override val rawTouch get() = true
    abstract fun shown(): List<Media>

    protected fun content() = RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom)

    protected open fun open(i: Int) {
        val list = shown()
        if (i in list.indices) shell.push(ViewerScreen(act, list, i, viewerMode()))
    }

    protected open fun viewerMode() = ViewerScreen.NORMAL

    protected fun drawGrid(c: Canvas, ui: Ui, r: RectF) {
        grid.onOpen = { open(it) }
        grid.draw(c, ui, r, shown()) { act.marks.isFav(it) }
    }

    protected fun gridAct(a: Act): Boolean {
        grid.onOpen = { open(it) }
        return grid.act(a, shown(), content())
    }

    protected fun gridTouch(e: MotionEvent): Boolean {
        grid.onOpen = { open(it) }
        return grid.touch(e, shown(), content(), shell.ui.sc)
    }

    protected fun zoomItems() = (0..2).map { l -> MenuItem("Show by " + LEVELS[l].lowercase() + if (grid.level == l) "  (now)" else "") { grid.changeLevel(l) } }

    protected fun zoomTool() = Tool(Ic.GRID, { grid.changeLevel((grid.level + 1) % 3); shell.toast("By " + LEVELS[grid.level].lowercase()) })
}

/** Photos / Albums / Favourites, as Belle tabs. */
class RootScreen(act: GalleryActivity) : GridScreen(act) {
    override val title get() = "Gallery"
    override val tabs = listOf(TabDef("Photos", Ic.PHOTO), TabDef("Albums", Ic.ALBUMS), TabDef("Favourites", Ic.FAVOURITE))

    private val favGrid = PhotosGrid(act) { shell }
    private val albumList = ListState()
    private var albums: List<Pair<String, List<Media>>> = emptyList()
    private var albumsVersion = -1
    private var state = 0 // 0 loading, 1 ready, 2 denied
    private var started = false

    override fun shown(): List<Media> = if (tab == 2) act.favourites() else act.photos()

    private val activeGrid get() = if (tab == 2) favGrid else grid

    override fun onShow() {
        if (!started) {
            started = true
            load()
        } else if (act.libraryDirty) {
            act.libraryDirty = false
            load()
        }
    }

    private fun load() {
        state = 0
        act.ensureAccess { ok ->
            if (!ok) {
                state = 2
                shell.invalidate()
            } else {
                Thread {
                    if (act.library.folders.isNotEmpty()) act.library.indexFolders()
                    act.library.load()
                    act.marks.purge(act.library.items)
                    shell.post {
                        albumsVersion = -1
                        state = 1
                        shell.invalidate()
                    }
                }.start()
            }
        }
    }

    override fun tools() = listOf(zoomTool())

    override fun menu() = zoomItems() + listOf(
        MenuItem("Hidden") { shell.push(ItemsScreen(act, "Hidden", ViewerScreen.HIDDEN)) },
        MenuItem("Recently deleted") { shell.push(ItemsScreen(act, "Recently deleted", ViewerScreen.TRASH)) },
        MenuItem("Refresh") { load() },
        MenuItem("Choose folders") { shell.push(FoldersScreen(act)) },
    )

    private fun message(c: Canvas, ui: Ui, r: RectF, text: String) {
        val lines = text.split("\n")
        for ((i, l) in lines.withIndex()) {
            ui.text(c, l, r.centerX(), ui.baselineFor(r.centerY() + (i - (lines.size - 1) / 2f) * 30f * ui.sc, 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
        }
    }

    private fun albumsNow(): List<Pair<String, List<Media>>> {
        val v = act.marks.version * 100003 + act.library.items.size
        if (v != albumsVersion) {
            albumsVersion = v
            albums = act.photos().groupBy { it.bucketId }.values.map { it.first().bucket to it }.sortedByDescending { it.second.first().dateSec }
        }
        return albums
    }

    override fun open(i: Int) {
        val list = shown()
        if (i in list.indices) shell.push(ViewerScreen(act, list, i, ViewerScreen.NORMAL))
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        val all = act.photos()
        when {
            state == 0 -> message(c, ui, r, "Loading...")
            state == 2 -> message(c, ui, r, com.belleui.kit.Keys.hint("Gallery needs access to your photos and videos.\nPress A to allow."))
            tab == 1 -> {
                val al = albumsNow()
                if (al.isEmpty()) message(c, ui, r, "No albums") else albumList.draw(c, ui, r, al.size) { i ->
                    val (name, list) = al[i]
                    val thumb = Thumbs.get(act, list.first()) { shell.invalidate() }
                    Row(name, "${list.size} items", icon = thumb ?: ui.gfx.get("qgn_prop_album_photo_video"), iconKind = Ic.PHOTO, drill = true)
                }
            }
            tab == 2 -> if (act.favourites().isEmpty()) message(c, ui, r, "No favourites yet\nOpen a photo and choose Favourite") else {
                favGrid.onOpen = { open(it) }
                favGrid.draw(c, ui, r, act.favourites()) { true }
            }
            all.isEmpty() -> message(c, ui, r, "No photos or videos")
            else -> drawGrid(c, ui, r)
        }
    }

    override fun onAct(a: Act): Boolean {
        if (state == 2 && a == Act.OK) {
            load()
            return true
        }
        if (state != 1) return false
        if (tab == 1) {
            val al = albumsNow()
            val vis = albumList.visible(shell.ui, shell.contentBottom - shell.contentTop)
            if (a == Act.OK) {
                al.getOrNull(albumList.sel)?.let { shell.push(AlbumScreen(act, it.first, it.second)) }
                return true
            }
            return albumList.act(a, al.size, vis)
        }
        favGrid.onOpen = { open(it) }
        return activeGrid.act(a, shown(), content())
    }

    override fun onTouch(e: MotionEvent): Boolean {
        if (state == 2 && e.actionMasked == MotionEvent.ACTION_UP) {
            load()
            return true
        }
        if (state != 1) return true
        if (tab == 1) {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val i = albumList.hit(shell.ui, content(), e.y, albumsNow().size)
                if (i >= 0 && Math.abs(e.y - lastDownY) < 20f * shell.ui.sc) {
                    albumList.sel = i
                    shell.push(AlbumScreen(act, albumsNow()[i].first, albumsNow()[i].second))
                }
            } else if (e.actionMasked == MotionEvent.ACTION_DOWN) lastDownY = e.y
            else if (e.actionMasked == MotionEvent.ACTION_MOVE) {
                val dy = lastDownY - e.y
                if (Math.abs(dy) > 40f * shell.ui.sc) {
                    albumList.scrollBy(if (dy > 0) 1 else -1, albumsNow().size, albumList.visible(shell.ui, shell.contentBottom - shell.contentTop))
                    lastDownY = e.y
                }
            }
            return true
        }
        favGrid.onOpen = { open(it) }
        return activeGrid.touch(e, shown(), content(), shell.ui.sc)
    }

    private var lastDownY = 0f
}

/** Hidden, Recently deleted: a grid with its own way of opening. */
class ItemsScreen(act: GalleryActivity, override val title: String, private val mode: Int) : GridScreen(act) {
    override fun shown(): List<Media> = if (mode == ViewerScreen.TRASH) act.trashed() else act.hiddenItems()
    override fun viewerMode() = mode

    override fun menu() = zoomItems()

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        if (shown().isEmpty()) {
            ui.text(c, if (mode == ViewerScreen.TRASH) "Nothing recently deleted" else "Nothing hidden", r.centerX(), ui.baselineFor(r.centerY(), 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
            return
        }
        drawGrid(c, ui, r)
        if (mode == ViewerScreen.TRASH) ui.text(c, "Photos here are removed for good after 30 days", r.centerX(), r.bottom - 10f * ui.sc, 15f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER, face = ui.light)
    }

    override fun onAct(a: Act) = gridAct(a)
    override fun onTouch(e: MotionEvent) = gridTouch(e)
}

class AlbumScreen(act: GalleryActivity, override val title: String, private val items: List<Media>) : GridScreen(act) {
    override fun shown(): List<Media> = items.filter { !act.marks.isHidden(it) && !act.marks.isTrashed(it) }
    override fun menu() = zoomItems()
    override fun tools() = listOf(zoomTool())
    override fun draw(c: Canvas, ui: Ui, r: RectF) = drawGrid(c, ui, r)
    override fun onAct(a: Act) = gridAct(a)
    override fun onTouch(e: MotionEvent) = gridTouch(e)
}
