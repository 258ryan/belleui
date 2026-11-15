package com.belleui.tube

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.URL
import java.util.Locale

/** Everything that talks to YouTube through NewPipe's extractor. All of it blocks: call it off the main thread. */
object Tube {
    private var ready = false

    @Synchronized
    private fun init() {
        if (ready) return
        val loc = try { Localization.fromLocale(Locale.getDefault()) } catch (_: Throwable) { Localization.DEFAULT }
        NewPipe.init(TubeDownloader(), loc)
        ready = true
    }

    private val yt get() = ServiceList.YouTube

    fun trending(): List<StreamInfoItem> {
        init()
        val ke = yt.kioskList.defaultKioskExtractor
        ke.fetchPage()
        return streams(KioskInfo.getInfo(ke).relatedItems)
    }

    fun search(query: String): List<StreamInfoItem> {
        init()
        val info = SearchInfo.getInfo(yt, yt.searchQHFactory.fromQuery(query))
        return streams(info.relatedItems)
    }

    fun video(url: String): StreamInfo {
        init()
        return StreamInfo.getInfo(url)
    }

    private fun streams(items: List<InfoItem>): List<StreamInfoItem> = items.filterIsInstance<StreamInfoItem>()

    // ---- thumbnails -------------------------------------------------------------------------------

    private val thumbs = object : LruCache<String, Bitmap>(60) {}

    fun cached(url: String?): Bitmap? = if (url == null) null else thumbs.get(url)

    /** Downloads and caches a thumbnail, scaled down; null if it can't be had. */
    fun thumbnail(url: String?): Bitmap? {
        if (url == null) return null
        thumbs.get(url)?.let { return it }
        return try {
            val conn = URL(url).openConnection()
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            val raw = conn.getInputStream().use { BitmapFactory.decodeStream(it) } ?: return null
            val w = 192
            val h = (raw.height * (w.toFloat() / raw.width)).toInt().coerceAtLeast(1)
            val b = Bitmap.createScaledBitmap(raw, w, h, true)
            if (b !== raw) raw.recycle()
            thumbs.put(url, b)
            b
        } catch (_: Throwable) {
            null
        }
    }

    fun thumbUrl(item: InfoItem): String? = item.thumbnails.let { if (it.isEmpty()) null else it[it.size / 2].url }

    fun duration(sec: Long): String {
        if (sec <= 0) return "live"
        val h = sec / 3600
        val m = sec % 3600 / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun views(n: Long): String = when {
        n < 0 -> ""
        n >= 1_000_000 -> "%.1fM views".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fK views".format(n / 1_000.0)
        else -> "$n views"
    }
}
