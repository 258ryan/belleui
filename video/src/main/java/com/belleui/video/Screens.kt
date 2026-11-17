package com.belleui.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
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

private const val BLUE = 0xFF2B6FD0.toInt()

/** Video thumbnail cut to the Belle icon shape. */
private fun maskedThumb(c: Canvas, ui: Ui, bmp: Bitmap, r: RectF) {
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

/** A list of videos with thumbnails; A plays. Shared by every screen that lists videos. */
class VideoList(private val act: VideoActivity) {
    val list = ListState(72f)
    private val rr = RectF()

    private fun rowFor(v: Video): Row {
        val resume = act.library.resumeAt(v)
        val sub = if (resume > 5000 && resume < v.durationMs - 10_000) "Resume at ${fmtTime(resume)} - ${v.bucket}" else v.detail
        return Row(v.title, sub, iconKind = Ic.PLAY, iconColor = BLUE, right = v.durText)
    }

    fun draw(c: Canvas, ui: Ui, r: RectF, items: List<Video>, shell: com.belleui.kit.BelleShell) {
        list.draw(c, ui, r, items.size) { rowFor(items[it]) }
        val vis = list.visible(ui, r.height())
        val sc = ui.sc
        c.save()
        c.clipRect(r)
        for (i in maxOf(0, list.top - 1)..minOf(items.size - 1, list.top + vis + 1)) {
            list.rowRect(ui, r, i, rr)
            val x = rr.left + Belle.PADDING * sc
            ui.tmp.set(x, rr.centerY() - 25f * sc, x + 50f * sc, rr.centerY() + 25f * sc)
            val bmp = Thumbs.get(act, items[i]) { shell.invalidate() }
            if (bmp != null) maskedThumb(c, ui, bmp, ui.tmp)
        }
        c.restore()
    }

    fun selected(items: List<Video>): Video? = items.getOrNull(list.sel.coerceIn(0, maxOf(0, items.size - 1)))
}

private fun message(c: Canvas, ui: Ui, r: RectF, text: String) {
    val lines = text.split("\n")
    for ((i, l) in lines.withIndex()) {
        ui.text(c, l, r.centerX(), ui.baselineFor(r.centerY() + (i - (lines.size - 1) / 2f) * 30f * ui.sc, 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER, maxW = r.width() - 40f * ui.sc)
    }
}

/** Videos / Folders / Continue, as Belle tabs. */
class RootScreen(private val act: VideoActivity) : Screen() {
    override val title get() = "Video"
    override val tabs = listOf(TabDef("Videos", Ic.PLAY), TabDef("Folders", Ic.ALBUMS), TabDef("Continue", Ic.HISTORY_FORWARD))

    private val videos = VideoList(act)
    private val continueList = VideoList(act)
    private val folderList = ListState()
    private var folders: List<Pair<String, List<Video>>> = emptyList()
    private var resumable: List<Video> = emptyList()
    private var state = 0 // 0 loading, 1 ready, 2 denied
    private var started = false

    override fun onShow() {
        if (!started) {
            started = true
            load()
        } else if (act.dirty) {
            act.dirty = false
            refreshDerived()
            shell.invalidate()
        }
    }

    private fun refreshDerived() {
        resumable = act.library.inProgress()
    }

    private fun load() {
        state = 0
        act.ensureAccess { ok ->
            if (!ok) {
                state = 2
                shell.invalidate()
            } else {
                Thread {
                    act.library.load()
                    folders = act.library.folders()
                    refreshDerived()
                    shell.post {
                        state = 1
                        shell.invalidate()
                    }
                }.start()
            }
        }
    }

    private fun area() = RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom)
    private fun vis(l: ListState) = l.visible(shell.ui, shell.contentBottom - shell.contentTop)

    private fun items(): List<Video> = if (tab == 0) act.library.items else resumable

    override fun menu(): List<MenuItem> {
        val items = ArrayList<MenuItem>()
        val sel = if (tab == 0) videos.selected(act.library.items) else if (tab == 2) continueList.selected(resumable) else null
        if (sel != null) items.add(MenuItem("Details") { videoDetails(act, shell, sel) })
        items.add(MenuItem("Sort: " + if (act.library.sortByName) "name - switch to newest" else "newest - switch to name") {
            act.library.sortByName = !act.library.sortByName
            load()
        })
        items.add(MenuItem("Refresh") { load() })
        return items
    }

    override fun tools() = listOf(
        Tool(Ic.PLAY, { selectedVideo()?.let { v -> play(v) } }, selectedVideo() != null),
        Tool(Ic.REFRESH, { load() }),
    )

    private fun selectedVideo(): Video? = when (tab) {
        0 -> videos.selected(act.library.items)
        2 -> continueList.selected(resumable)
        else -> null
    }

    private fun play(v: Video) {
        val list = if (tab == 2) resumable else act.library.items
        act.play(list, list.indexOf(v).coerceAtLeast(0))
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        when {
            state == 0 -> message(c, ui, r, "Looking for videos...")
            state == 2 -> message(c, ui, r, com.belleui.kit.Keys.hint("Video needs access to your videos.\nPress A to allow."))
            tab == 1 -> if (folders.isEmpty()) message(c, ui, r, "No videos found") else folderList.draw(c, ui, r, folders.size) { i ->
                Row(folders[i].first, "${folders[i].second.size} video${if (folders[i].second.size == 1) "" else "s"}", iconKind = Ic.ALBUMS, iconColor = BLUE, drill = true)
            }
            tab == 2 && resumable.isEmpty() -> message(c, ui, r, "Nothing to continue yet.\nVideos you stop part-way appear here.")
            items().isEmpty() -> message(c, ui, r, "No videos found")
            tab == 0 -> videos.draw(c, ui, r, act.library.items, shell)
            else -> continueList.draw(c, ui, r, resumable, shell)
        }
    }

    override fun onAct(a: Act): Boolean {
        if (state == 2 && a == Act.OK) {
            load()
            return true
        }
        if (state != 1) return false
        if (tab == 1) {
            if (folders.isEmpty()) return false
            if (a == Act.OK) {
                folders.getOrNull(folderList.sel)?.let { shell.push(FolderScreen(act, it.first, it.second)) }
                return true
            }
            return folderList.act(a, folders.size, vis(folderList))
        }
        val l = if (tab == 0) videos else continueList
        val items = items()
        if (items.isEmpty()) return false
        if (a == Act.OK) {
            l.selected(items)?.let { play(it) }
            return true
        }
        return l.list.act(a, items.size, vis(l.list))
    }

    override fun onTap(x: Float, y: Float): Boolean {
        if (state == 2) {
            load()
            return true
        }
        if (state != 1) return false
        if (tab == 1) {
            val i = folderList.hit(shell.ui, area(), y, folders.size)
            if (i < 0) return false
            folderList.sel = i
            shell.push(FolderScreen(act, folders[i].first, folders[i].second))
            return true
        }
        val l = if (tab == 0) videos else continueList
        val items = items()
        val i = l.list.hit(shell.ui, area(), y, items.size)
        if (i < 0) return false
        l.list.sel = i
        play(items[i])
        return true
    }

    override fun onScroll(dy: Float) {
        val d = if (dy > 0) 1 else -1
        when (tab) {
            1 -> folderList.scrollBy(d, folders.size, vis(folderList))
            0 -> videos.list.scrollBy(d, act.library.items.size, vis(videos.list))
            else -> continueList.list.scrollBy(d, resumable.size, vis(continueList.list))
        }
    }
}

/** The videos in one folder. */
class FolderScreen(private val act: VideoActivity, private val name: String, private val items: List<Video>) : Screen() {
    override val title get() = name
    private val videos = VideoList(act)

    override fun tools() = listOf(Tool(Ic.PLAY, { videos.selected(items)?.let { act.play(items, items.indexOf(it)) } }))

    override fun menu(): List<MenuItem> = videos.selected(items)?.let { v -> listOf(MenuItem("Details") { videoDetails(act, shell, v) }) } ?: emptyList()

    override fun draw(c: Canvas, ui: Ui, r: RectF) = videos.draw(c, ui, r, items, shell)

    private fun vis() = videos.list.visible(shell.ui, shell.contentBottom - shell.contentTop)

    override fun onAct(a: Act): Boolean {
        if (items.isEmpty()) return false
        if (a == Act.OK) {
            videos.selected(items)?.let { act.play(items, items.indexOf(it)) }
            return true
        }
        return videos.list.act(a, items.size, vis())
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val i = videos.list.hit(shell.ui, RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom), y, items.size)
        if (i < 0) return false
        videos.list.sel = i
        act.play(items, i)
        return true
    }

    override fun onScroll(dy: Float) = videos.list.scrollBy(if (dy > 0) 1 else -1, items.size, vis())
}

/** Video details (mpxvideodetailsview.r01): title, format, resolution, bit rate, duration, size, date and where it is. */
internal fun videoDetails(act: VideoActivity, shell: com.belleui.kit.BelleShell, v: Video) {
    val lines = ArrayList<String>()
    lines.add(v.title)
    var format: String? = null
    var bitrate: String? = null
    val mmr = android.media.MediaMetadataRetriever()
    try {
        mmr.setDataSource(act, v.uri)
        format = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        bitrate = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { "${it / 1000} kbit/s" }
    } catch (_: Throwable) {
    } finally {
        try { mmr.release() } catch (_: Throwable) { }
    }
    if (format != null) lines.add("Format: $format")
    if (v.width > 0 && v.height > 0) lines.add("Resolution: ${v.width} x ${v.height}")
    if (bitrate != null) lines.add("Bit rate: $bitrate")
    lines.add("Duration: ${v.durText}")
    lines.add("Size: " + if (v.size > 1_000_000) "%.1f MB".format(v.size / 1_000_000.0) else "${v.size / 1000} kB")
    lines.add("Date: " + android.text.format.DateFormat.format("d MMM yyyy, HH:mm", v.dateSec * 1000))
    lines.add(v.bucket)
    shell.showOverlay(com.belleui.kit.QueryDialog(lines.joinToString("\n"), "OK", null, "Video details") { })
}
