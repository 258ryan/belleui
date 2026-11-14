package com.belleui.gallery

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.format.DateFormat
import com.belleui.kit.Act
import com.belleui.kit.Belle
import com.belleui.kit.GridState
import com.belleui.kit.Ic
import com.belleui.kit.ListState
import com.belleui.kit.MenuItem
import com.belleui.kit.QueryDialog
import com.belleui.kit.Row
import com.belleui.kit.Screen
import com.belleui.kit.TabDef
import com.belleui.kit.Tool
import com.belleui.kit.Ui
import kotlin.math.max
import kotlin.math.min

private fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** Square thumbnail grid shared by Photos and album screens. */
class MediaGrid(private val act: GalleryActivity) {
    val grid = GridState(5, 1f)

    fun draw(c: Canvas, ui: Ui, r: RectF, items: List<Media>, shell: com.belleui.kit.BelleShell) {
        grid.draw(c, ui, r, items.size) { cv, cell, i, selected ->
            val m = items[i]
            // the selected thumbnail is drawn smaller so the blue highlight frame shows around it
            val g = (if (selected) 8f else 2f) * ui.sc
            val box = RectF(cell.left + g, cell.top + g, cell.right - g, cell.bottom - g)
            val bmp = Thumbs.get(act, m) { shell.invalidate() }
            if (bmp != null) {
                ui.drawBitmapCover(cv, bmp, box)
            } else {
                ui.fill(cv, box, 0xFF1E1E1E.toInt())
                ui.glyph(cv, if (m.isVideo) Ic.PLAY else Ic.PHOTO, box.centerX(), box.centerY(), 36f * ui.sc, ui.al(Color.WHITE, 70))
            }
            if (m.isVideo) {
                ui.p.style = Paint.Style.FILL
                ui.p.shader = null
                ui.p.color = ui.al(Color.BLACK, 140)
                cv.drawCircle(box.centerX(), box.centerY(), 20f * ui.sc, ui.p)
                ui.glyph(cv, Ic.PLAY, box.centerX() + 1.5f * ui.sc, box.centerY(), 26f * ui.sc)
                ui.text(cv, fmtDuration(m.durationMs), box.right - 4f * ui.sc, box.bottom - 5f * ui.sc, 14f * ui.sc, Color.WHITE, Paint.Align.RIGHT)
            }
        }
    }
}

/** Photos / Albums, as Belle tabs. */
class RootScreen(private val act: GalleryActivity) : Screen() {
    override val title get() = "Gallery"
    override val tabs = listOf(TabDef("Photos", Ic.PHOTO), TabDef("Albums", Ic.ALBUMS))

    private val photos = MediaGrid(act)
    private val albumList = ListState()
    private var albums: List<Pair<String, List<Media>>> = emptyList()
    private var state = 0 // 0 loading, 1 ready, 2 denied
    private var started = false

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
                    albums = act.library.albums()
                    shell.post {
                        state = 1
                        shell.invalidate()
                    }
                }.start()
            }
        }
    }

    override fun menu() = listOf(
        MenuItem("Refresh") { load() },
        MenuItem("Choose folders") { shell.push(FoldersScreen(act)) },
    )

    private fun message(c: Canvas, ui: Ui, r: RectF, text: String) {
        val lines = text.split("\n")
        for ((i, l) in lines.withIndex()) {
            ui.text(c, l, r.centerX(), ui.baselineFor(r.centerY() + (i - (lines.size - 1) / 2f) * 30f * ui.sc, 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
        }
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        val items = act.library.items
        when {
            state == 0 -> message(c, ui, r, "Loading...")
            state == 2 -> message(c, ui, r, "Gallery needs access to your photos and videos.\nPress A to allow.")
            items.isEmpty() -> message(c, ui, r, "No photos or videos")
            tab == 0 -> photos.draw(c, ui, r, items, shell)
            else -> albumList.draw(c, ui, r, albums.size) { i ->
                val (name, list) = albums[i]
                val thumb = Thumbs.get(act, list.first()) { shell.invalidate() }
                Row(name, "${list.size} items", icon = thumb ?: ui.gfx.get("qgn_prop_album_photo_video"), iconKind = Ic.PHOTO, drill = true)
            }
        }
    }

    private fun contentRect() = RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom)

    override fun onAct(a: Act): Boolean {
        if (state == 2 && a == Act.OK) {
            load()
            return true
        }
        val items = act.library.items
        if (state != 1 || items.isEmpty()) return false
        if (tab == 0) {
            if (a == Act.OK) {
                shell.push(ViewerScreen(act, items, photos.grid.sel))
                return true
            }
            return photos.grid.act(a, items.size, contentRect())
        }
        val vis = albumList.visible(shell.ui, shell.contentBottom - shell.contentTop)
        if (a == Act.OK) {
            albums.getOrNull(albumList.sel)?.let { shell.push(AlbumScreen(act, it.first, it.second)) }
            return true
        }
        return albumList.act(a, albums.size, vis)
    }

    override fun onTap(x: Float, y: Float): Boolean {
        if (state == 2) {
            load()
            return true
        }
        val items = act.library.items
        if (tab == 0) {
            val i = photos.grid.hit(contentRect(), x, y, items.size)
            if (i < 0) return false
            photos.grid.sel = i
            shell.push(ViewerScreen(act, items, i))
        } else {
            val i = albumList.hit(shell.ui, contentRect(), y, albums.size)
            if (i < 0) return false
            albumList.sel = i
            shell.push(AlbumScreen(act, albums[i].first, albums[i].second))
        }
        return true
    }

    override fun onScroll(dy: Float) {
        val d = if (dy > 0) 1 else -1
        if (tab == 0) photos.grid.scrollBy(d, act.library.items.size, contentRect())
        else albumList.scrollBy(d, albums.size, albumList.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }
}

class AlbumScreen(private val act: GalleryActivity, private val name: String, private val items: List<Media>) : Screen() {
    override val title get() = name
    private val grid = MediaGrid(act)

    override fun draw(c: Canvas, ui: Ui, r: RectF) = grid.draw(c, ui, r, items, shell)

    private fun rect() = RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom)

    override fun onAct(a: Act): Boolean {
        if (a == Act.OK) {
            shell.push(ViewerScreen(act, items, grid.grid.sel))
            return true
        }
        return grid.grid.act(a, items.size, rect())
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val i = grid.grid.hit(rect(), x, y, items.size)
        if (i < 0) return false
        grid.grid.sel = i
        shell.push(ViewerScreen(act, items, i))
        return true
    }

    override fun onScroll(dy: Float) = grid.grid.scrollBy(if (dy > 0) 1 else -1, items.size, rect())
}

/** Full-screen viewer: next / previous, zoom and pan, slideshow, share, details. */
class ViewerScreen(private val act: GalleryActivity, private val items: List<Media>, start: Int) : Screen() {
    private var index = start.coerceIn(0, items.size - 1)
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var slideshow = false
    private var lastAdvance = 0L

    private val current get() = items[index]
    override val title get() = current.name
    override val tickMs get() = if (slideshow) 500L else 0L

    override fun tools() = listOf(
        Tool(Ic.SHARE, { share() }),
        Tool(Ic.SLIDESHOW, {
            slideshow = !slideshow
            lastAdvance = System.currentTimeMillis()
            shell.toast(if (slideshow) "Slideshow started" else "Slideshow stopped")
        }),
    )

    override fun menu() = listOf(
        MenuItem("Details") { details() },
        MenuItem("Share") { share() },
        MenuItem("Slideshow: " + if (slideshow) "stop" else "start") {
            slideshow = !slideshow
            lastAdvance = System.currentTimeMillis()
        },
    )

    private fun go(d: Int) {
        val n = index + d
        if (n in items.indices) {
            index = n
            zoom = 1f
            panX = 0f
            panY = 0f
            shell.invalidate()
        } else if (slideshow) {
            slideshow = false
        }
    }

    private fun cycleZoom() {
        if (current.isVideo) return
        zoom = if (zoom >= 4f) 1f else zoom * 2f
        panX = 0f
        panY = 0f
    }

    private fun share() {
        val i = Intent(Intent.ACTION_SEND).setType(current.mime).putExtra(Intent.EXTRA_STREAM, current.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        act.startActivity(Intent.createChooser(i, "Share"))
    }

    private fun play() {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(current.uri, current.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            act.startActivity(i)
        } catch (_: Exception) {
            shell.toast("No video player found")
        }
    }

    private fun details() {
        val m = current
        val date = DateFormat.format("d MMM yyyy, HH:mm", m.dateSec * 1000).toString()
        val size = if (m.size > 1_000_000) "%.1f MB".format(m.size / 1_000_000.0) else "${m.size / 1000} kB"
        val dims = if (m.width > 0) "${m.width} x ${m.height}  -  $size" else size
        shell.showOverlay(QueryDialog("${m.name}\n$dims\n$date\n${m.bucket}", "OK", null) { })
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        val m = current
        if (slideshow && System.currentTimeMillis() - lastAdvance > 3000) {
            lastAdvance = System.currentTimeMillis()
            go(1)
        }
        val maxPx = 1600
        val bmp = if (m.isVideo) Thumbs.get(act, m) { shell.invalidate() } else FullImages.peek(m)
        if (!m.isVideo) {
            FullImages.load(act, m, maxPx) { shell.invalidate() }
            items.getOrNull(index + 1)?.let { FullImages.load(act, it, maxPx) { } }
        }
        if (bmp == null) {
            ui.text(c, "Loading...", r.centerX(), ui.baselineFor(r.centerY(), 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
        } else {
            val scale = min(r.width() / bmp.width, r.height() / bmp.height) * zoom
            val maxX = max(0f, (bmp.width * scale - r.width()) / 2f)
            val maxY = max(0f, (bmp.height * scale - r.height()) / 2f)
            panX = panX.coerceIn(-maxX, maxX)
            panY = panY.coerceIn(-maxY, maxY)
            ui.drawBitmapFit(c, bmp, r, zoom, panX, panY)
        }
        if (m.isVideo) {
            ui.p.style = Paint.Style.FILL
            ui.p.shader = null
            ui.p.color = ui.al(Color.BLACK, 150)
            c.drawCircle(r.centerX(), r.centerY(), 44f * ui.sc, ui.p)
            ui.glyph(c, Ic.PLAY, r.centerX() + 3f * ui.sc, r.centerY(), 56f * ui.sc)
            ui.text(c, fmtDuration(m.durationMs), r.right - 12f * ui.sc, r.bottom - 12f * ui.sc, 18f * ui.sc, Color.WHITE, Paint.Align.RIGHT)
        }
        ui.text(c, "${index + 1} / ${items.size}" + if (zoom > 1f) "   ${zoom.toInt()}x" else "", r.left + 12f * ui.sc, r.bottom - 12f * ui.sc, 18f * ui.sc, ui.al(Color.WHITE, 220), face = ui.light)
    }

    override fun onAct(a: Act): Boolean {
        when (a) {
            Act.OK -> if (current.isVideo) play() else cycleZoom()
            Act.PREV -> go(-1)
            Act.NEXT -> go(1)
            Act.LEFT -> if (zoom > 1f) panX += 80f else go(-1)
            Act.RIGHT -> if (zoom > 1f) panX -= 80f else go(1)
            Act.UP -> if (zoom > 1f) panY += 80f else return false
            Act.DOWN -> if (zoom > 1f) panY -= 80f else return false
            else -> return false
        }
        return true
    }

    override fun onTap(x: Float, y: Float): Boolean {
        when {
            x < shell.width / 3f -> go(-1)
            x > shell.width * 2f / 3f -> go(1)
            current.isVideo -> play()
            else -> cycleZoom()
        }
        return true
    }
}
