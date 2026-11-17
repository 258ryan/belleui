package com.belleui.gallery

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import com.belleui.kit.Act
import com.belleui.kit.Belle
import com.belleui.kit.Ic
import com.belleui.kit.MenuItem
import com.belleui.kit.QueryDialog
import com.belleui.kit.Screen
import com.belleui.kit.Tool
import com.belleui.kit.Ui
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

private fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * Full-screen viewer, with the behaviour of symbian3-photos: swipe between photos (with a gap and a spring), pinch to zoom
 * up to 6x, double-tap to 2.5x, drag to pan, swipe down to close. Keys: left / right, A, L1 / R1 as before.
 */
class ViewerScreen(private val act: GalleryActivity, private val items0: List<Media>, start: Int, private val mode: Int = NORMAL) : Screen() {
    companion object {
        const val NORMAL = 0
        const val HIDDEN = 1
        const val TRASH = 2
        private const val MAX_ZOOM = 6f
        private const val DOUBLE_ZOOM = 2.5f
        private const val GAP = 20f
    }

    private var items = items0.toList()
    private var index = start.coerceIn(0, max(0, items.size - 1))
    private var zoom = 1f
    private var zoomTarget = 1f
    private var panX = 0f
    private var panY = 0f
    private var pageX = 0f
    private var pageV = 0f
    private var dismissY = 0f
    private var dismissing = false
    private var slideshow = false
    private var lastAdvance = 0L
    private var lastNs = 0L

    // touch
    private val tracker: VelocityTracker = VelocityTracker.obtain()
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downT = 0L
    private var gesture = 0 // 0 undecided, 1 pan, 2 page, 3 dismiss, 4 pinch, 5 ignore
    private var lastTapT = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var focusX = 0f
    private var focusY = 0f
    private var viewRect = RectF()
    private var panVX = 0f
    private var panVY = 0f
    private var flingingPan = false
    private val scaler = ScaleGestureDetector(act, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            gesture = 4
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            val nz = (zoom * d.scaleFactor).coerceIn(0.85f, MAX_ZOOM)
            val f = nz / zoom
            // keep the point between the fingers where it is
            val cx = viewRect.centerX()
            val cy = viewRect.centerY()
            panX = panX * f + (d.focusX - cx) * (1 - f)
            panY = panY * f + (d.focusY - cy) * (1 - f)
            zoom = nz
            zoomTarget = nz
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            if (zoom < 1f) zoomTarget = 1f
        }
    })

    private val current get() = items[index]
    override val title get() = if (items.isEmpty()) "" else current.name
    override val tickMs get() = if (slideshow) 500L else 0L
    override val rawTouch get() = true

    override fun tools() = listOf(
        Tool(Ic.FAVOURITE, { favourite() }),
        Tool(Ic.SHARE, { share() }),
    )

    override fun menu(): List<MenuItem> {
        if (items.isEmpty()) return emptyList()
        return when (mode) {
            TRASH -> listOf(MenuItem("Recover") { recover() }, MenuItem("Delete for good") { deleteForever() }, MenuItem("Details") { details() })
            HIDDEN -> listOf(MenuItem("Unhide") { unhide() }, MenuItem("Details") { details() }, MenuItem("Share") { share() })
            else -> listOf(
                MenuItem(if (act.marks.isFav(current)) "Remove from favourites" else "Favourite") { favourite() },
                MenuItem("Details") { details() },
                MenuItem("Share") { share() },
                MenuItem("Hide") { hide() },
                MenuItem("Delete") { delete() },
                MenuItem("Slideshow: " + if (slideshow) "stop" else "start") {
                    slideshow = !slideshow
                    lastAdvance = System.currentTimeMillis()
                },
            )
        }
    }

    private fun removeCurrent() {
        items = items.filterIndexed { i, _ -> i != index }
        if (items.isEmpty()) shell.pop() else index = index.coerceAtMost(items.size - 1)
        zoom = 1f; zoomTarget = 1f; panX = 0f; panY = 0f
        shell.invalidate()
    }

    private fun favourite() {
        if (items.isEmpty()) return
        act.marks.toggleFav(current)
        shell.toast(if (act.marks.isFav(current)) "Added to favourites" else "Removed from favourites")
    }

    private fun hide() { act.marks.hide(current); shell.toast("Hidden"); removeCurrent() }
    private fun unhide() { act.marks.unhide(current); shell.toast("Unhidden"); removeCurrent() }
    private fun delete() { act.marks.delete(current); shell.toast("Moved to Recently deleted"); removeCurrent() }
    private fun recover() { act.marks.recover(current); shell.toast("Recovered"); removeCurrent() }
    private fun deleteForever() {
        shell.showOverlay(QueryDialog("Delete ${current.name} for good?", "Delete", "Cancel") {
            if (act.marks.deleteForever(current)) removeCurrent() else shell.toast("Android would not let Gallery delete this file")
        })
    }

    private fun go(d: Int) {
        val n = index + d
        if (n in items.indices) {
            index = n
            zoom = 1f; zoomTarget = 1f; panX = 0f; panY = 0f
            // the new page slides in from the side it comes from
            pageX = if (d > 0) viewRect.width() + GAP * (shell.ui.sc) else -(viewRect.width() + GAP * shell.ui.sc)
            pageV = 0f
            shell.invalidate()
        } else if (slideshow) {
            slideshow = false
        }
    }

    private fun share() {
        if (items.isEmpty()) return
        val i = Intent(Intent.ACTION_SEND).setType(current.mime).putExtra(Intent.EXTRA_STREAM, current.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        act.startActivity(Intent.createChooser(i, "Share"))
    }

    private fun play() {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(current.uri, current.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { act.startActivity(i) } catch (_: Exception) { shell.toast("No video player found") }
    }

    private fun details() {
        val m = current
        val date = DateFormat.format("d MMM yyyy, HH:mm", m.dateSec * 1000).toString()
        val size = if (m.size > 1_000_000) "%.1f MB".format(m.size / 1_000_000.0) else "${m.size / 1000} kB"
        val dims = if (m.width > 0) "${m.width} x ${m.height}  -  $size" else size
        val lines = ArrayList<String>()
        lines.add(m.name)
        lines.add(dims)
        lines.add(date)
        lines.add(m.bucket)
        if (!m.isVideo) lines.addAll(exifLines(m))
        shell.showOverlay(QueryDialog(lines.joinToString("\n"), "OK", null, "Details") { })
    }

    private fun exifLines(m: Media): List<String> {
        val out = ArrayList<String>()
        try {
            act.contentResolver.openInputStream(m.uri)?.use { s ->
                val e = ExifInterface(s)
                val cam = listOfNotNull(e.getAttribute(ExifInterface.TAG_MAKE), e.getAttribute(ExifInterface.TAG_MODEL)).joinToString(" ").trim()
                if (cam.isNotEmpty()) out.add(cam)
                val bits = ArrayList<String>()
                e.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { bits.add("f/$it") }
                e.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let { t -> bits.add(if (t < 1.0) "1/${Math.round(1.0 / t)} s" else "$t s") }
                e.getAttribute("ISOSpeedRatings")?.let { bits.add("ISO $it") }
                e.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { f ->
                    val p = f.split("/")
                    val mm = if (p.size == 2) (p[0].toDoubleOrNull() ?: 0.0) / (p[1].toDoubleOrNull() ?: 1.0) else f.toDoubleOrNull()
                    if (mm != null && mm > 0) bits.add("%.0f mm".format(mm))
                }
                if (bits.isNotEmpty()) out.add(bits.joinToString("   "))
            }
        } catch (_: Throwable) { }
        return out
    }

    // ---- physics ------------------------------------------------------------------------------------

    private fun spring(v: Float, vel: Float, target: Float, omega: Float, dt: Float): Pair<Float, Float> {
        val e = exp(-omega * dt)
        val r = v - target
        val c = vel + omega * r
        val moved = r + c * dt
        return Pair(target + moved * e, (c - omega * moved) * e)
    }

    private fun clampPan(bw: Float, bh: Float, r: RectF) {
        val scale = min(r.width() / bw, r.height() / bh) * zoom
        val maxX = max(0f, (bw * scale - r.width()) / 2f)
        val maxY = max(0f, (bh * scale - r.height()) / 2f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    private fun step(ui: Ui, dt: Float) {
        val touching = gesture != 0 && gesture != 5 && touchDown
        if (!touching) {
            if (abs(pageX) > 0.5f || abs(pageV) > 1f) {
                val (v, vel) = spring(pageX, pageV, 0f, 17f, dt)
                pageX = v; pageV = vel
                ui.busy = true
            } else { pageX = 0f; pageV = 0f }
            if (abs(dismissY) > 0.5f && !dismissing) {
                val (v, _) = spring(dismissY, 0f, 0f, 15f, dt)
                dismissY = if (abs(v) < 0.5f) 0f else v
                ui.busy = true
            }
            if (abs(zoomTarget - zoom) > 0.002f) {
                val e = exp(-16f * dt)
                zoom = zoomTarget + (zoom - zoomTarget) * e
                ui.busy = true
            } else zoom = zoomTarget
            if (flingingPan) {
                panX += panVX * dt
                panY += panVY * dt
                val d = Math.pow(0.998, (dt * 1000f).toDouble()).toFloat()
                panVX *= d; panVY *= d
                if (abs(panVX) < 12f && abs(panVY) < 12f) flingingPan = false
                ui.busy = true
            }
        }
    }

    private var touchDown = false

    // ---- drawing --------------------------------------------------------------------------------------

    private fun drawPage(c: Canvas, ui: Ui, r: RectF, m: Media, dx: Float, z: Float, px: Float, py: Float, isCurrent: Boolean) {
        val bmp = if (m.isVideo) Thumbs.get(act, m) { shell.invalidate() } else FullImages.peek(m)
        if (!m.isVideo) FullImages.load(act, m, 1600) { shell.invalidate() }
        c.save()
        c.translate(dx, 0f)
        if (bmp == null) {
            val t = Thumbs.get(act, m) { shell.invalidate() }
            if (t != null) ui.drawBitmapFit(c, t, r, z, px, py)
            else ui.text(c, "Loading...", r.centerX(), ui.baselineFor(r.centerY(), 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
        } else {
            ui.drawBitmapFit(c, bmp, r, z, px, py)
        }
        if (m.isVideo) {
            ui.p.style = Paint.Style.FILL
            ui.p.shader = null
            ui.p.color = ui.al(Color.BLACK, 150)
            c.drawCircle(r.centerX(), r.centerY(), 44f * ui.sc, ui.p)
            ui.glyph(c, Ic.PLAY, r.centerX() + 3f * ui.sc, r.centerY(), 56f * ui.sc)
            ui.text(c, fmtDuration(m.durationMs), r.right - 12f * ui.sc, r.bottom - 12f * ui.sc, 18f * ui.sc, Color.WHITE, Paint.Align.RIGHT)
        }
        c.restore()
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        if (items.isEmpty()) return
        viewRect = RectF(r)
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f)
        lastNs = now
        if (slideshow && System.currentTimeMillis() - lastAdvance > 3000) {
            lastAdvance = System.currentTimeMillis()
            go(1)
        }
        step(ui, dt)
        val m = current
        val bmp = if (m.isVideo) null else FullImages.peek(m)
        if (bmp != null) clampPan(bmp.width.toFloat(), bmp.height.toFloat(), r)
        val gap = GAP * ui.sc
        c.save()
        c.clipRect(r)
        // swiping down to close: the picture follows the finger and shrinks a little
        if (dismissY > 0f) {
            val k = 1f - min(0.25f, dismissY / (r.height() * 2f))
            c.translate(0f, dismissY)
            c.scale(k, k, r.centerX(), r.centerY())
        }
        drawPage(c, ui, r, m, pageX, zoom, panX, panY, true)
        if (pageX > 0f) items.getOrNull(index - 1)?.let { drawPage(c, ui, r, it, pageX - r.width() - gap, 1f, 0f, 0f, false) }
        if (pageX < 0f) items.getOrNull(index + 1)?.let { drawPage(c, ui, r, it, pageX + r.width() + gap, 1f, 0f, 0f, false) }
        items.getOrNull(index + 1)?.let { if (!it.isVideo) FullImages.load(act, it, 1600) { } }
        c.restore()
        val tag = "${index + 1} / ${items.size}" + (if (zoom > 1.05f) "   ${"%.1f".format(zoom)}x" else "") +
            (if (mode == TRASH) "   ${act.marks.daysLeft(m)} days left" else "")
        ui.text(c, tag, r.left + 12f * ui.sc, r.bottom - 12f * ui.sc, 18f * ui.sc, ui.al(Color.WHITE, 220), face = ui.light)
        if (act.marks.isFav(m)) ui.glyph(c, Ic.FAVOURITE, r.right - 24f * ui.sc, r.bottom - 22f * ui.sc, 26f * ui.sc)
    }

    // ---- keys -------------------------------------------------------------------------------------------

    private fun toggleZoom(x: Float, y: Float) {
        if (current.isVideo) return
        if (zoomTarget > 1.2f) {
            zoomTarget = 1f
            panX = 0f; panY = 0f
        } else {
            zoomTarget = DOUBLE_ZOOM
            // zoom in on the tapped point
            val cx = viewRect.centerX()
            val cy = viewRect.centerY()
            panX = (cx - x) * (DOUBLE_ZOOM - 1f)
            panY = (cy - y) * (DOUBLE_ZOOM - 1f)
        }
    }

    override fun onAct(a: Act): Boolean {
        if (items.isEmpty()) return false
        when (a) {
            Act.OK -> if (current.isVideo) play() else toggleZoom(viewRect.centerX(), viewRect.centerY())
            Act.PREV -> go(-1)
            Act.NEXT -> go(1)
            Act.LEFT -> if (zoom > 1.05f) panX += 80f else go(-1)
            Act.RIGHT -> if (zoom > 1.05f) panX -= 80f else go(1)
            Act.UP -> if (zoom > 1.05f) panY += 80f else return false
            Act.DOWN -> if (zoom > 1.05f) panY -= 80f else return false
            else -> return false
        }
        return true
    }

    // ---- touch ------------------------------------------------------------------------------------------

    override fun onTouch(e: MotionEvent): Boolean {
        if (items.isEmpty()) return true
        val sc = shell.ui.sc
        scaler.onTouchEvent(e)
        if (scaler.isInProgress) { touchDown = true; return true }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDown = true
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; downT = e.eventTime
                gesture = 0
                flingingPan = false
                tracker.clear()
                tracker.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (gesture == 4) return true
                tracker.addMovement(e)
                val dx = e.x - lastX
                val dy = e.y - lastY
                lastX = e.x; lastY = e.y
                if (gesture == 0 && (abs(e.x - downX) > 14f * sc || abs(e.y - downY) > 14f * sc)) {
                    gesture = when {
                        zoom > 1.05f -> 1
                        abs(e.x - downX) > abs(e.y - downY) -> 2
                        e.y > downY -> 3
                        else -> 5
                    }
                }
                when (gesture) {
                    1 -> { panX += dx; panY += dy }
                    2 -> {
                        // no neighbour on that side: the page resists
                        val blocked = (pageX > 0 && index == 0) || (pageX < 0 && index == items.size - 1)
                        pageX += if (blocked) dx * 0.3f else dx
                    }
                    3 -> dismissY = max(0f, dismissY + dy)
                }
            }
            MotionEvent.ACTION_UP -> {
                tracker.addMovement(e)
                tracker.computeCurrentVelocity(1000)
                val vx = tracker.xVelocity
                val vy = tracker.yVelocity
                touchDown = false
                when (gesture) {
                    0 -> tap(e)
                    1 -> { panVX = vx; panVY = vy; flingingPan = abs(vx) > 60f || abs(vy) > 60f }
                    2 -> {
                        val w = viewRect.width()
                        if ((pageX > w * 0.25f || vx > 500f) && index > 0) go(-1).also { pageX = pageX - w - GAP * sc }
                        else if ((pageX < -w * 0.25f || vx < -500f) && index < items.size - 1) go(1).also { pageX = pageX + w + GAP * sc }
                        else pageV = vx * 0.2f
                    }
                    3 -> if (dismissY > viewRect.height() * 0.2f || vy > 900f) { dismissing = true; shell.pop() }
                }
                if (zoom < 1f) zoomTarget = 1f
                gesture = 0
            }
            MotionEvent.ACTION_CANCEL -> { touchDown = false; gesture = 0 }
            MotionEvent.ACTION_POINTER_UP -> { if (gesture == 4) { /* wait for the lift */ } }
        }
        return true
    }

    private fun tap(e: MotionEvent) {
        val sc = shell.ui.sc
        val dbl = e.eventTime - lastTapT < 350 && abs(e.x - lastTapX) < 60f * sc && abs(e.y - lastTapY) < 60f * sc
        lastTapT = e.eventTime
        lastTapX = e.x
        lastTapY = e.y
        if (dbl) {
            lastTapT = 0
            toggleZoom(e.x, e.y)
            return
        }
        val w = shell.width
        when {
            current.isVideo -> play()
            e.x < w * 0.18f -> go(-1)
            e.x > w * 0.82f -> go(1)
        }
    }
}
