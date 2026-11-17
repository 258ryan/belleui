package com.belleui.gallery

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import com.belleui.kit.Act
import com.belleui.kit.BelleShell
import com.belleui.kit.Ic
import com.belleui.kit.Ui
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The library: photos grouped under date headers (years, months or days), three zoom levels you pinch between, a finger
 * scroll with fling and rubber band, and a date scrubber down the right edge. Ported in spirit from symbian3-photos.
 */
class PhotosGrid(private val act: GalleryActivity, private val shell: () -> BelleShell) {
    var level = 1 // 0 years, 1 months, 2 days
    var sel = 0
    var onOpen: (Int) -> Unit = {}
    var keyMode = false

    private val cols = intArrayOf(8, 5, 4)
    private val gap = 2f

    private class Section(val label: String, val first: Int, val count: Int, var top: Float = 0f, var rowsTop: Float = 0f)

    private var sections: List<Section> = emptyList()
    private var builtFor: List<Media>? = null
    private var builtLevel = -1
    private var builtW = 0f
    private var contentH = 0f
    private var cellW = 0f
    private var rowH = 0f
    private var headH = 0f
    private var nc = 5

    // scroll physics
    private var scroll = 0f
    private var vel = 0f
    private var viewH = 0f
    private var lastNs = 0L
    private var dragging = false
    private var scrubbing = false
    private var scrubLabel: String? = null
    private var flinging = false
    private var targetScroll = Float.NaN

    private val tracker: VelocityTracker = VelocityTracker.obtain()
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var downT = 0L
    private var moved = false
    private var scaleAcc = 1f
    private var pinching = false
    private val scaler = ScaleGestureDetector(act, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            scaleAcc = 1f
            pinching = true
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            scaleAcc *= d.scaleFactor
            // spread: bigger pictures (fewer columns); pinch together: more of the library at once
            if (scaleAcc > 1.25f && level < 2) { changeLevel(level + 1); scaleAcc = 1f }
            else if (scaleAcc < 0.8f && level > 0) { changeLevel(level - 1); scaleAcc = 1f }
            return true
        }
    })

    private val yearF = SimpleDateFormat("yyyy", Locale.getDefault())
    private val monthF = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    private val dayF = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault())
    private val cal = Calendar.getInstance()

    fun changeLevel(l: Int) {
        if (l == level) return
        // keep what was at the middle of the screen in view
        val anchor = itemAt(0f, viewH / 2f)
        level = l
        builtLevel = -1
        if (anchor >= 0) sel = anchor
        pendingAnchor = anchor
        shell().invalidate()
    }

    private var pendingAnchor = -1

    private fun key(m: Media): Int {
        cal.timeInMillis = m.dateSec * 1000
        val y = cal.get(Calendar.YEAR)
        val mo = cal.get(Calendar.MONTH)
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return when (level) { 0 -> y; 1 -> y * 100 + mo; else -> y * 10000 + mo * 100 + d }
    }

    private fun label(m: Media): String {
        val d = Date(m.dateSec * 1000)
        return when (level) { 0 -> yearF.format(d); 1 -> monthF.format(d); else -> dayF.format(d) }
    }

    private fun build(items: List<Media>, w: Float, sc: Float) {
        if (builtFor === items && builtLevel == level && builtW == w) return
        builtFor = items
        builtLevel = level
        builtW = w
        nc = cols[level]
        cellW = w / nc
        rowH = cellW
        headH = 42f * sc
        val out = ArrayList<Section>()
        var y = 0f
        var i = 0
        while (i < items.size) {
            val k = key(items[i])
            val start = i
            while (i < items.size && key(items[i]) == k) i++
            val s = Section(label(items[start]), start, i - start)
            s.top = y
            s.rowsTop = y + headH
            val rows = (s.count + nc - 1) / nc
            y = s.rowsTop + rows * rowH + 6f * sc
            out.add(s)
        }
        sections = out
        contentH = y
    }

    private fun sectionOf(item: Int): Section? {
        var lo = 0
        var hi = sections.size - 1
        var found: Section? = null
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (sections[mid].first <= item) { found = sections[mid]; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    private fun rectOf(item: Int, out: RectF): RectF? {
        val s = sectionOf(item) ?: return null
        val k = item - s.first
        val col = k % nc
        val row = k / nc
        val x = col * cellW
        val y = s.rowsTop + row * rowH
        out.set(x, y, x + cellW, y + rowH)
        return out
    }

    /** The item under a point of the view (content coordinates given the scroll), or -1. */
    private fun itemAt(x: Float, y: Float): Int {
        val cy = y + scroll
        var lo = 0
        var hi = sections.size - 1
        var si = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (sections[mid].top <= cy) { si = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (si < 0) return -1
        val s = sections[si]
        if (cy < s.rowsTop) return -1
        val row = ((cy - s.rowsTop) / rowH).toInt()
        val col = (x / cellW).toInt().coerceIn(0, nc - 1)
        val k = row * nc + col
        return if (k < s.count) s.first + k else -1
    }

    private val maxScroll get() = max(0f, contentH - viewH)

    private fun rubber(raw: Float): Float {
        // the scroll position shown for a raw (possibly overshooting) one: the pull gets harder the further it goes
        val dim = viewH
        return when {
            raw < 0 -> -(1 - 1 / (-raw * 0.55f / dim + 1)) * dim
            raw > maxScroll -> maxScroll + (1 - 1 / ((raw - maxScroll) * 0.55f / dim + 1)) * dim
            else -> raw
        }
    }

    private var rawScroll = 0f

    fun act(a: Act, items: List<Media>, r: RectF): Boolean {
        if (items.isEmpty()) return false
        keyMode = true
        val before = sel
        when (a) {
            Act.LEFT -> if (sel > 0) sel--
            Act.RIGHT -> if (sel < items.size - 1) sel++
            Act.UP -> sel = max(0, sel - nc)
            Act.DOWN -> sel = min(items.size - 1, sel + nc)
            Act.PREV -> { if (level > 0) changeLevel(level - 1); return true }
            Act.NEXT -> { if (level < 2) changeLevel(level + 1); return true }
            Act.OK -> { onOpen(sel); return true }
            else -> return false
        }
        keepSelVisible()
        return sel != before
    }

    private fun keepSelVisible() {
        val rc = rectOf(sel, RectF()) ?: return
        val pad = 8f
        targetScroll = when {
            rc.top - pad < scroll -> rc.top - pad - headH
            rc.bottom + pad > scroll + viewH -> rc.bottom + pad - viewH
            else -> Float.NaN
        }.let { if (it.isNaN()) it else it.coerceIn(0f, maxScroll) }
    }

    private fun stepPhysics(dt: Float, ui: Ui) {
        if (dragging || scrubbing) return
        if (flinging) {
            rawScroll += vel * dt
            vel *= 0.998f.pow(dt * 1000f)
            if (rawScroll < 0 || rawScroll > maxScroll) {
                // past the end: spring back (omega 13, as symbian3-photos)
                val tgt = if (rawScroll < 0) 0f else maxScroll
                val w = 13f
                val e = exp(-w * dt)
                val rr = rawScroll - tgt
                val c = vel + w * rr
                val moved = rr + c * dt
                rawScroll = tgt + moved * e
                vel = (c - w * moved) * e
                if (abs(rawScroll - tgt) < 0.5f && abs(vel) < 20f) { rawScroll = tgt; vel = 0f; flinging = false }
            } else if (abs(vel) < 12f) { vel = 0f; flinging = false }
            scroll = rubber(rawScroll)
            ui.busy = true
        } else if (!targetScroll.isNaN()) {
            scroll += (targetScroll - scroll) * ui.k
            if (abs(targetScroll - scroll) < 0.5f) { scroll = targetScroll; targetScroll = Float.NaN } else ui.busy = true
            rawScroll = scroll
        }
    }

    fun draw(c: Canvas, ui: Ui, r: RectF, items: List<Media>, mark: (Media) -> Boolean) {
        val sc = ui.sc
        viewH = r.height()
        build(items, r.width(), sc)
        if (pendingAnchor >= 0) {
            val rc = rectOf(pendingAnchor, RectF())
            if (rc != null) {
                rawScroll = (rc.centerY() - viewH / 2f).coerceIn(0f, maxScroll)
                scroll = rawScroll
                vel = 0f
                flinging = false
            }
            pendingAnchor = -1
        }
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f)
        lastNs = now
        stepPhysics(dt, ui)
        if (!flinging && !dragging && targetScroll.isNaN()) scroll = scroll.coerceIn(0f, maxScroll)

        c.save()
        c.clipRect(r)
        val rc = RectF()
        for (s in sections) {
            if (s.top + (s.rowsTop - s.top) + ((s.count + nc - 1) / nc) * rowH - scroll < 0f) continue
            if (s.top - scroll > r.height()) break
            val first = max(0, ((scroll - s.rowsTop) / rowH).toInt()) * nc
            val last = min(s.count, (((scroll + r.height() - s.rowsTop) / rowH).toInt() + 1) * nc)
            for (k in first until last) {
                val i = s.first + k
                val col = k % nc
                val row = k / nc
                val x = r.left + col * cellW
                val y = r.top + s.rowsTop + row * rowH - scroll
                val g = (if (keyMode && i == sel) 7f else gap) * sc / 2f
                rc.set(x + g, y + g, x + cellW - g, y + rowH - g)
                if (keyMode && i == sel) ui.highlight(c, RectF(x, y, x + cellW, y + rowH), 8f)
                val m = items[i]
                val bmp = Thumbs.get(act, m) { shell().invalidate() }
                if (bmp != null) ui.drawBitmapCover(c, bmp, rc) else ui.fill(c, rc, 0xFF1E1E1E.toInt())
                if (m.isVideo) {
                    ui.text(c, fmtDur(m.durationMs), rc.right - 4f * sc, rc.bottom - 5f * sc, 13f * sc, Color.WHITE, Paint.Align.RIGHT)
                    ui.glyph(c, Ic.PLAY, rc.left + 14f * sc, rc.bottom - 14f * sc, 20f * sc)
                }
                if (mark(m)) ui.glyph(c, Ic.FAVOURITE, rc.left + 14f * sc, rc.top + 14f * sc, 20f * sc, Color.WHITE)
            }
            // the date header
            val hy = r.top + s.top - scroll
            if (hy + headH > r.top && hy < r.bottom) {
                ui.p.style = Paint.Style.FILL
                ui.p.shader = null
                ui.p.color = 0xCC000000.toInt()
                c.drawRect(r.left, max(hy, r.top), r.right, max(hy, r.top) + headH, ui.p)
                ui.text(c, s.label, r.left + 14f * sc, ui.baselineFor(max(hy, r.top) + headH / 2f, 22f * sc), 22f * sc, Color.WHITE, face = ui.medium)
            }
        }
        c.restore()
        // a header that sticks to the top while its section scrolls under it is drawn above; keep the floating scrub label
        if (scrubbing && scrubLabel != null) {
            val w = ui.measure(scrubLabel!!, 24f * sc) + 30f * sc
            val tr = RectF(r.right - w - 50f * sc, r.top + (r.height() - 44f * sc) * (scroll / max(1f, maxScroll)), r.right - 50f * sc, 0f)
            tr.bottom = tr.top + 44f * sc
            ui.p.style = Paint.Style.FILL
            ui.p.color = 0xE0202020.toInt()
            c.drawRoundRect(tr, 8f * sc, 8f * sc, ui.p)
            ui.text(c, scrubLabel!!, tr.centerX(), ui.baselineFor(tr.centerY(), 24f * sc), 24f * sc, Color.WHITE, Paint.Align.CENTER)
        }
        // the scrub strip / scroll bar
        if (maxScroll > 0f) {
            ui.scrollbar(c, r.right, r.top, r.bottom, scroll / maxScroll, viewH / contentH)
        }
    }

    private fun fmtDur(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    /** Touch: drag to scroll with fling, pinch for the zoom level, drag on the right edge to scrub the dates, tap to open. */
    fun touch(e: MotionEvent, items: List<Media>, r: RectF, sc: Float): Boolean {
        scaler.onTouchEvent(e)
        if (scaler.isInProgress) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                keyMode = false
                downX = e.x; downY = e.y; lastY = e.y; downT = e.eventTime
                moved = false
                flinging = false
                vel = 0f
                targetScroll = Float.NaN
                tracker.clear()
                tracker.addMovement(e)
                scrubbing = e.x > r.right - 40f * sc
                dragging = !scrubbing
                rawScroll = scroll
                if (scrubbing) scrubTo(e.y, r)
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinching) return true
                tracker.addMovement(e)
                if (abs(e.y - downY) > 14f * sc || abs(e.x - downX) > 14f * sc) moved = true
                if (scrubbing) {
                    scrubTo(e.y, r)
                } else if (moved) {
                    val dy = e.y - lastY
                    // dragging past an end moves the picture only a fraction of the finger
                    rawScroll -= if (rawScroll < 0 || rawScroll > maxScroll) dy / 3f else dy
                    scroll = rubber(rawScroll)
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (pinching) { pinching = false; dragging = false; return true }
                tracker.addMovement(e)
                tracker.computeCurrentVelocity(1000)
                if (scrubbing) {
                    scrubbing = false
                } else if (!moved && e.eventTime - downT < 400) {
                    val i = itemAt(e.x - r.left, e.y - r.top)
                    if (i in items.indices) { sel = i; onOpen(i) }
                } else {
                    vel = -tracker.yVelocity
                    flinging = abs(vel) > 40f || rawScroll < 0 || rawScroll > maxScroll
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; scrubbing = false; pinching = false }
        }
        return true
    }

    private fun scrubTo(y: Float, r: RectF) {
        val f = ((y - r.top) / r.height()).coerceIn(0f, 1f)
        rawScroll = f * maxScroll
        scroll = rawScroll
        val i = itemAt(0f, 1f)
        scrubLabel = i.takeIf { it >= 0 }?.let { sectionOf(it)?.label }
    }
}
