package com.belleui.tube

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.belleui.kit.Act
import com.belleui.kit.Belle
import com.belleui.kit.Ic
import com.belleui.kit.ListState
import com.belleui.kit.MenuItem
import com.belleui.kit.QueryDialog
import com.belleui.kit.Row
import com.belleui.kit.Screen
import com.belleui.kit.Tool
import com.belleui.kit.Ui
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** A list of videos (trending, search results, related). Subclasses say how to fetch them. */
abstract class VideoListScreen(protected val act: TubeActivity) : Screen() {
    protected val list = ListState()
    protected var items: List<StreamInfoItem> = emptyList()
    private var loading = false
    private var loaded = false
    private var error: String? = null
    private val inFlight = HashSet<String>()

    abstract fun fetch(): List<StreamInfoItem>

    override fun onShow() {
        if (!loaded && !loading) reload()
    }

    fun reload() {
        loading = true
        error = null
        shell.invalidate()
        act.bg({ fetch() }) { r ->
            loading = false
            loaded = true
            r.onSuccess { items = it }
            r.onFailure { error = it.message ?: it.javaClass.simpleName }
            shell.invalidate()
        }
    }

    protected fun search() {
        act.askText("Search YouTube", "") { q -> shell.push(SearchScreen(act, q)) }
    }

    override fun tools() = listOf(Tool(Ic.SEARCH, { search() }), Tool(Ic.REFRESH, { reload() }))

    override fun menu() = listOf(
        MenuItem("Search") { search() },
        MenuItem("Refresh") { reload() },
        MenuItem("Open a link") { act.askText("YouTube link", "https://") { shell.push(VideoScreen(act, it, "Video")) } },
        MenuItem("About") {
            shell.showOverlay(QueryDialog("Tube uses NewPipe's extractor to read YouTube without a Google account. No ads, no tracking.", "OK", null, "About Tube") { })
        },
    )

    private fun thumb(it: StreamInfoItem): android.graphics.Bitmap? {
        val url = Tube.thumbUrl(it) ?: return null
        Tube.cached(url)?.let { b -> return b }
        if (inFlight.add(url)) act.bg({ Tube.thumbnail(url) }) { shell.invalidate() }
        return null
    }

    private fun subtitle(i: StreamInfoItem): String {
        val parts = ArrayList<String>()
        if (!i.uploaderName.isNullOrBlank()) parts.add(i.uploaderName)
        parts.add(Tube.duration(i.duration))
        val v = Tube.views(i.viewCount)
        if (v.isNotEmpty()) parts.add(v)
        return parts.joinToString("  -  ")
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        if (items.isEmpty()) {
            val msg = when {
                loading -> "Loading..."
                error != null -> "Couldn't load: $error"
                loaded -> "Nothing found"
                else -> ""
            }
            ui.text(c, msg, r.centerX(), ui.baselineFor(r.centerY(), 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER, maxW = r.width() - 40f * ui.sc)
            return
        }
        list.draw(c, ui, r, items.size) { i ->
            val it = items[i]
            Row(it.name ?: "", subtitle(it), icon = thumb(it), drill = true)
        }
    }

    private fun area() = RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom)

    private fun open(i: Int) {
        val it = items.getOrNull(i) ?: return
        shell.push(VideoScreen(act, it.url, it.name ?: "Video"))
    }

    override fun onAct(a: Act): Boolean {
        if (items.isEmpty()) return false
        if (a == Act.OK || a == Act.RIGHT) {
            open(list.sel)
            return true
        }
        return list.act(a, items.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val i = list.hit(shell.ui, area(), y, items.size)
        if (i < 0) return false
        list.sel = i
        open(i)
        return true
    }

    override fun onScroll(dy: Float) {
        list.scrollBy(if (dy > 0) 1 else -1, items.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }
}

class TrendingScreen(act: TubeActivity) : VideoListScreen(act) {
    override val title get() = "Tube"
    override fun fetch() = Tube.trending()
}

class SearchScreen(act: TubeActivity, private val query: String) : VideoListScreen(act) {
    override val title get() = query
    override fun fetch() = Tube.search(query)
}

class RelatedScreen(act: TubeActivity, private val name: String, private val source: List<StreamInfoItem>) : VideoListScreen(act) {
    override val title get() = name
    override fun fetch() = source
}

/** One video: what it is, and the ways to play it. */
class VideoScreen(private val act: TubeActivity, private val url: String, private val name: String) : Screen() {
    override val title get() = name
    private val list = ListState()
    private var info: StreamInfo? = null
    private var error: String? = null
    private var loading = false

    private class Entry(val row: Row, val action: (() -> Unit)?)

    private var entries: List<Entry> = emptyList()

    override fun onShow() {
        if (info == null && !loading) {
            loading = true
            act.bg({ Tube.video(url) }) { r ->
                loading = false
                r.onSuccess { info = it; build(it) }
                r.onFailure { error = it.message ?: it.javaClass.simpleName }
                shell.invalidate()
            }
        }
    }

    private fun build(i: StreamInfo) {
        val out = ArrayList<Entry>()
        out.add(Entry(Row(i.name ?: name, listOfNotNull(i.uploaderName?.takeIf { it.isNotBlank() }, Tube.duration(i.duration), Tube.views(i.viewCount).takeIf { it.isNotEmpty() }).joinToString("  -  ")), null))
        val muxed = i.videoStreams.filter { it.isUrl }.sortedByDescending { it.resolution?.filter { c -> c.isDigit() }?.toIntOrNull() ?: 0 }
        for (v in muxed) {
            val res = v.resolution ?: "video"
            out.add(Entry(Row("Play $res", v.format?.getName() ?: "", iconKind = Ic.PLAY, drill = true)) { act.play(v.content, i.name ?: name, false) })
        }
        val hls = i.hlsUrl
        if (muxed.isEmpty() && !hls.isNullOrBlank()) {
            out.add(Entry(Row("Play (live or adaptive)", "Streams in the best quality available", iconKind = Ic.PLAY, drill = true)) { act.play(hls, i.name ?: name, false) })
        }
        val audio = i.audioStreams.filter { it.isUrl }.maxByOrNull { it.averageBitrate }
        if (audio != null) {
            out.add(Entry(Row("Play audio only", "${audio.averageBitrate} kbit/s", iconKind = Ic.NOTE, drill = true)) { act.play(audio.content, i.name ?: name, true) })
        }
        val related = i.relatedItems.filterIsInstance<StreamInfoItem>()
        if (related.isNotEmpty()) {
            out.add(Entry(Row("Related videos", "${related.size} videos", iconKind = Ic.LIST, drill = true)) { shell.push(RelatedScreen(act, "Related", related)) })
        }
        val desc = i.description?.content?.trim().orEmpty()
        if (desc.isNotEmpty()) {
            out.add(Entry(Row("Description", desc.lineSequence().firstOrNull()?.take(60) ?: "", iconKind = Ic.LIST, drill = true)) {
                shell.showOverlay(QueryDialog(desc.take(500).replace("\r", ""), "OK", null, "Description") { })
            })
        }
        entries = out
    }

    override fun menu() = listOf(
        MenuItem("Open in browser") {
            try { act.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Throwable) { shell.toast("No browser") }
        },
    )

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        if (entries.isEmpty()) {
            val msg = if (error != null) "Couldn't open: $error" else "Loading..."
            ui.text(c, msg, r.centerX(), ui.baselineFor(r.centerY(), 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER, maxW = r.width() - 40f * ui.sc)
            return
        }
        list.draw(c, ui, r, entries.size) { entries[it].row }
    }

    private fun area() = RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom)

    override fun onAct(a: Act): Boolean {
        if (entries.isEmpty()) return false
        if (a == Act.OK || a == Act.RIGHT) {
            entries.getOrNull(list.sel)?.action?.invoke()
            return true
        }
        return list.act(a, entries.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val i = list.hit(shell.ui, area(), y, entries.size)
        if (i < 0) return false
        list.sel = i
        entries[i].action?.invoke()
        return true
    }

    override fun onScroll(dy: Float) {
        list.scrollBy(if (dy > 0) 1 else -1, entries.size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
    }
}
