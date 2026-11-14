package com.belleui.kit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Belle measurements (Qt Components nHD profile, 1 px = 1 device px) and colours. */
object Belle {
    const val BLUE = 0xFF1080DD.toInt()
    const val BLUE_LIGHT = 0xFF5CB4F8.toInt()
    const val TEXT_MID = 0xFF999999.toInt()
    const val SEPARATOR = 0xFF444444.toInt()
    const val PANEL = 0xFF1B1B1B.toInt()

    const val STATUS_H = 26f
    const val TAB_H = 50f      // landscape; 60 in portrait
    const val TOOL_H = 60f
    const val ROW_H = 70f
    const val MENU_ITEM_H = 64f
    const val PADDING = 12f
}

/** Glyph ids. The first group uses the stock Belle toolbar graphics, the rest are drawn. */
object Ic {
    const val BACK = 0
    const val MENU = 1
    const val SETTINGS = 2
    const val ADD = 3
    const val SEARCH = 4
    const val NEXT = 5
    const val PREVIOUS = 6
    const val REFRESH = 7
    const val DELETE = 8
    const val SHARE = 9
    const val HOME = 10
    const val LIST = 11
    const val CLOSE = 12
    const val PLAY = 20
    const val PAUSE = 21
    const val STOP = 22
    const val GRID = 23
    const val CLOCK = 24
    const val NOTE = 25
    const val DISC = 26
    const val PERSON = 27
    const val BELL = 28
    const val STOPWATCH = 29
    const val HOURGLASS = 30
    const val PHOTO = 31
    const val ALBUMS = 32
    const val GLOBE = 33
    const val CHECK = 34
    const val OK = 35
    const val FLAG = 36
    const val STAR = 37

    val stock = mapOf(
        BACK to "toolbar_back", MENU to "toolbar_menu", SETTINGS to "toolbar_settings", ADD to "toolbar_add",
        SEARCH to "toolbar_search", NEXT to "toolbar_next", PREVIOUS to "toolbar_previous",
        REFRESH to "toolbar_refresh", DELETE to "toolbar_delete", SHARE to "toolbar_share",
        HOME to "toolbar_home", LIST to "toolbar_list", CLOSE to "qtg_graf_popup_close_normal",
    )
}

/** One row of a Belle list. */
class Row(
    val title: String,
    val subtitle: String? = null,
    val icon: Bitmap? = null,
    val iconKind: Int = -1,
    val iconColor: Int = Belle.BLUE,
    val right: String? = null,
    val drill: Boolean = false,
    val dim: Boolean = false,
    val check: Boolean? = null,
    /** Space kept free at the right edge, e.g. for a switch drawn over the row. */
    val rightReserve: Float = 0f,
)

/** Drawing helpers shared by every Belle app. [sc] scales the 640x480 design grid to the screen. */
class Ui(val ctx: Context) {
    var sc = 1f
    var k = 0.3f            // per-frame easing factor, set by the shell
    var busy = false        // anything animating sets this to request another frame
    val gfx = BelleGfx(ctx)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    val rf = RectF()
    val tmp = RectF()
    val sans: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val path = Path()
    private val coverSrc = android.graphics.Rect()

    fun al(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    fun mix(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) * (1 - t) + Color.red(b) * t).toInt()
        val g = (Color.green(a) * (1 - t) + Color.green(b) * t).toInt()
        val bl = (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt()
        return Color.rgb(r, g, bl)
    }

    // ---- text --------------------------------------------------------------------------------

    fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int = Color.WHITE,
        align: Paint.Align = Paint.Align.LEFT, maxW: Float = 0f, face: Typeface = sans,
    ) {
        tp.textSize = size
        tp.color = color
        tp.textAlign = align
        tp.typeface = face
        val t = if (maxW > 0f) TextUtils.ellipsize(s, tp, maxW, TextUtils.TruncateAt.END).toString() else s
        c.drawText(t, x, y, tp)
    }

    fun measure(s: String, size: Float, face: Typeface = sans): Float {
        tp.textSize = size
        tp.typeface = face
        return tp.measureText(s)
    }

    /** Baseline that vertically centres text of [size] on [cy]. */
    fun baselineFor(cy: Float, size: Float, face: Typeface = sans): Float {
        tp.textSize = size
        tp.typeface = face
        val fm = tp.fontMetrics
        return cy - (fm.ascent + fm.descent) / 2f
    }

    fun fill(c: Canvas, r: RectF, color: Int) {
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = color
        c.drawRect(r, p)
    }

    fun nine(c: Canvas, name: String, to: RectF, srcBorder: Int = 40, dstBorder: Float = 20f, alpha: Int = 255) =
        gfx.nine(c, name, to, srcBorder, dstBorder * sc, alpha)

    /** Belle highlight: the theme's blue pressed frame. */
    fun highlight(c: Canvas, r: RectF, radius: Float = 12f) {
        if (nine(c, "qtg_fr_list_pressed", r, 40, radius)) return
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Belle.BLUE
        c.drawRoundRect(r, radius * sc, radius * sc, p)
    }

    // ---- shapes ------------------------------------------------------------------------------

    /** Belle IconFrame shape: superellipse with exponent 2.8. */
    fun squircle(r: RectF, out: Path = path, n: Double = 2.8): Path {
        out.reset()
        val cx = r.centerX()
        val cy = r.centerY()
        val a = r.width() / 2.0
        val b = r.height() / 2.0
        val steps = 72
        for (i in 0..steps) {
            val t = 2.0 * PI * i / steps
            val ct = cos(t)
            val st = sin(t)
            val x = (cx + a * Math.signum(ct) * Math.pow(abs(ct), 2.0 / n)).toFloat()
            val y = (cy + b * Math.signum(st) * Math.pow(abs(st), 2.0 / n)).toFloat()
            if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
        out.close()
        return out
    }

    /** Belle IconFrame: gradient superellipse, top glow, thin rim, white glyph. */
    fun iconFrame(c: Canvas, r: RectF, color: Int, kind: Int) {
        val shape = squircle(r)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, mix(color, Color.WHITE, 0.28f), mix(color, Color.BLACK, 0.35f), Shader.TileMode.CLAMP)
        c.drawPath(shape, p)
        p.shader = RadialGradient(r.centerX(), r.top, r.width() * 0.7f, al(Color.WHITE, 85), al(Color.WHITE, 0), Shader.TileMode.CLAMP)
        c.drawPath(shape, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f * sc
        p.color = al(Color.BLACK, 120)
        c.drawPath(shape, p)
        p.style = Paint.Style.FILL
        if (kind >= 0) glyph(c, kind, r.centerX(), r.centerY(), r.width() * 0.58f, Color.WHITE)
    }

    /** Draws [bmp] filling [to] (centre-cropped, like a Belle thumbnail). */
    fun drawBitmapCover(c: Canvas, bmp: Bitmap, to: RectF, alpha: Int = 255) {
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val scale = max(to.width() / bw, to.height() / bh)
        val sw = to.width() / scale
        val sh = to.height() / scale
        val sx = ((bw - sw) / 2f).toInt()
        val sy = ((bh - sh) / 2f).toInt()
        coverSrc.set(sx, sy, (sx + sw).toInt().coerceAtMost(bmp.width), (sy + sh).toInt().coerceAtMost(bmp.height))
        p.shader = null
        p.alpha = alpha
        c.drawBitmap(bmp, coverSrc, to, p)
        p.alpha = 255
    }

    /** Draws [bmp] scaled to fit inside [box], times [zoom], shifted by (panX, panY). */
    fun drawBitmapFit(c: Canvas, bmp: Bitmap, box: RectF, zoom: Float = 1f, panX: Float = 0f, panY: Float = 0f) {
        val scale = min(box.width() / bmp.width, box.height() / bmp.height) * zoom
        val w = bmp.width * scale
        val h = bmp.height * scale
        tmp.set(box.centerX() - w / 2f + panX, box.centerY() - h / 2f + panY, box.centerX() + w / 2f + panX, box.centerY() + h / 2f + panY)
        p.shader = null
        c.save()
        c.clipRect(box)
        c.drawBitmap(bmp, null, tmp, p)
        c.restore()
    }

    /** Belle TextField: the one pale surface on a Belle screen. */
    fun textField(c: Canvas, r: RectF, text: String, hint: String, focused: Boolean) {
        nine(c, if (focused) "qtg_fr_textfield_highlighted" else "qtg_fr_textfield_editable", r, 40, 20f)
        val pad = 12f * sc
        if (text.isEmpty()) {
            this.text(c, hint, r.left + pad, baselineFor(r.centerY(), 20f * sc), 20f * sc, 0xFF8A8A8A.toInt(), maxW = r.width() - 2 * pad)
        } else {
            this.text(c, text, r.left + pad, baselineFor(r.centerY(), 20f * sc), 20f * sc, 0xFF1B1B1B.toInt(), maxW = r.width() - 2 * pad)
        }
    }

    fun drawBitmap(c: Canvas, bmp: Bitmap, to: RectF, alpha: Int = 255) {
        val ip = p
        ip.shader = null
        ip.alpha = alpha
        c.drawBitmap(bmp, null, to, ip)
        ip.alpha = 255
    }

    // ---- glyphs ------------------------------------------------------------------------------

    fun glyph(c: Canvas, kind: Int, cx: Float, cy: Float, s: Float, color: Int = Color.WHITE) {
        val stock = Ic.stock[kind]
        if (stock != null && gfx.glyph(c, stock, cx, cy, s, Color.alpha(color))) return
        p.shader = null
        p.color = color
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        val sw = max(1.5f, s * 0.1f)
        when (kind) {
            Ic.PLAY -> {
                p.style = Paint.Style.FILL
                path.reset()
                path.moveTo(cx - s * 0.26f, cy - s * 0.38f)
                path.lineTo(cx + s * 0.40f, cy)
                path.lineTo(cx - s * 0.26f, cy + s * 0.38f)
                path.close()
                c.drawPath(path, p)
            }
            Ic.PAUSE -> {
                p.style = Paint.Style.FILL
                rf.set(cx - s * 0.30f, cy - s * 0.36f, cx - s * 0.08f, cy + s * 0.36f)
                c.drawRoundRect(rf, 2f * sc, 2f * sc, p)
                rf.set(cx + s * 0.08f, cy - s * 0.36f, cx + s * 0.30f, cy + s * 0.36f)
                c.drawRoundRect(rf, 2f * sc, 2f * sc, p)
            }
            Ic.STOP -> {
                p.style = Paint.Style.FILL
                rf.set(cx - s * 0.30f, cy - s * 0.30f, cx + s * 0.30f, cy + s * 0.30f)
                c.drawRoundRect(rf, 3f * sc, 3f * sc, p)
            }
            Ic.GRID -> {
                p.style = Paint.Style.FILL
                val q = s * 0.4f
                val g = s * 0.1f
                for (ix in 0..1) for (iy in 0..1) {
                    val x = cx - q - g / 2 + ix * (q + g)
                    val y = cy - q - g / 2 + iy * (q + g)
                    rf.set(x, y, x + q, y + q)
                    c.drawRoundRect(rf, 3f * sc, 3f * sc, p)
                }
            }
            Ic.CLOCK -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = sw
                c.drawCircle(cx, cy, s * 0.42f, p)
                c.drawLine(cx, cy, cx, cy - s * 0.26f, p)
                c.drawLine(cx, cy, cx + s * 0.2f, cy + s * 0.08f, p)
            }
            Ic.NOTE -> {
                p.style = Paint.Style.FILL
                rf.set(cx - s * 0.34f, cy + s * 0.12f, cx - s * 0.04f, cy + s * 0.36f)
                c.drawOval(rf, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = sw
                c.drawLine(cx - s * 0.06f, cy + s * 0.24f, cx - s * 0.06f, cy - s * 0.38f, p)
                c.drawLine(cx - s * 0.06f, cy - s * 0.38f, cx + s * 0.34f, cy - s * 0.24f, p)
            }
            Ic.DISC -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = sw
                c.drawCircle(cx, cy, s * 0.4f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy, s * 0.1f, p)
            }
            Ic.PERSON -> {
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy - s * 0.17f, s * 0.2f, p)
                rf.set(cx - s * 0.38f, cy + s * 0.08f, cx + s * 0.38f, cy + s * 0.8f)
                c.drawArc(rf, 180f, 180f, true, p)
            }
            Ic.BELL -> {
                p.style = Paint.Style.FILL
                rf.set(cx - s * 0.3f, cy - s * 0.42f, cx + s * 0.3f, cy + s * 0.16f)
                c.drawArc(rf, 180f, 180f, true, p)
                rf.set(cx - s * 0.3f, cy - s * 0.13f, cx + s * 0.3f, cy + s * 0.24f)
                c.drawRect(rf, p)
                rf.set(cx - s * 0.42f, cy + s * 0.22f, cx + s * 0.42f, cy + s * 0.31f)
                c.drawRoundRect(rf, 2f * sc, 2f * sc, p)
                c.drawCircle(cx, cy + s * 0.42f, s * 0.08f, p)
            }
            Ic.STOPWATCH -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = sw
                c.drawCircle(cx, cy + s * 0.06f, s * 0.34f, p)
                c.drawLine(cx, cy + s * 0.06f, cx + s * 0.16f, cy - s * 0.1f, p)
                c.drawLine(cx - s * 0.1f, cy - s * 0.4f, cx + s * 0.1f, cy - s * 0.4f, p)
            }
            Ic.HOURGLASS -> {
                p.style = Paint.Style.FILL
                path.reset()
                path.moveTo(cx - s * 0.3f, cy - s * 0.4f)
                path.lineTo(cx + s * 0.3f, cy - s * 0.4f)
                path.lineTo(cx + s * 0.04f, cy)
                path.lineTo(cx + s * 0.3f, cy + s * 0.4f)
                path.lineTo(cx - s * 0.3f, cy + s * 0.4f)
                path.lineTo(cx - s * 0.04f, cy)
                path.close()
                c.drawPath(path, p)
            }
            Ic.PHOTO -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = sw
                rf.set(cx - s * 0.4f, cy - s * 0.32f, cx + s * 0.4f, cy + s * 0.32f)
                c.drawRoundRect(rf, 3f * sc, 3f * sc, p)
                p.style = Paint.Style.FILL
                path.reset()
                path.moveTo(cx - s * 0.32f, cy + s * 0.24f)
                path.lineTo(cx - s * 0.1f, cy - s * 0.02f)
                path.lineTo(cx + s * 0.06f, cy + s * 0.14f)
                path.lineTo(cx + s * 0.18f, cy + s * 0.02f)
                path.lineTo(cx + s * 0.32f, cy + s * 0.24f)
                path.close()
                c.drawPath(path, p)
                c.drawCircle(cx + s * 0.2f, cy - s * 0.14f, s * 0.07f, p)
            }
            Ic.ALBUMS -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = sw
                rf.set(cx - s * 0.24f, cy - s * 0.4f, cx + s * 0.42f, cy + s * 0.2f)
                c.drawRoundRect(rf, 3f * sc, 3f * sc, p)
                rf.set(cx - s * 0.42f, cy - s * 0.2f, cx + s * 0.24f, cy + s * 0.4f)
                p.style = Paint.Style.FILL
                p.color = al(color, 255)
                c.drawRoundRect(rf, 3f * sc, 3f * sc, p)
            }
            Ic.GLOBE -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = sw
                c.drawCircle(cx, cy, s * 0.4f, p)
                rf.set(cx - s * 0.17f, cy - s * 0.4f, cx + s * 0.17f, cy + s * 0.4f)
                c.drawOval(rf, p)
                c.drawLine(cx - s * 0.4f, cy, cx + s * 0.4f, cy, p)
            }
            Ic.CHECK -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = sw * 1.3f
                c.drawLine(cx - s * 0.3f, cy, cx - s * 0.08f, cy + s * 0.24f, p)
                c.drawLine(cx - s * 0.08f, cy + s * 0.24f, cx + s * 0.32f, cy - s * 0.24f, p)
            }
            Ic.FLAG -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = sw
                c.drawLine(cx - s * 0.28f, cy - s * 0.4f, cx - s * 0.28f, cy + s * 0.4f, p)
                p.style = Paint.Style.FILL
                path.reset()
                path.moveTo(cx - s * 0.26f, cy - s * 0.38f)
                path.lineTo(cx + s * 0.34f, cy - s * 0.22f)
                path.lineTo(cx - s * 0.26f, cy - s * 0.02f)
                path.close()
                c.drawPath(path, p)
            }
            Ic.STAR -> {
                p.style = Paint.Style.FILL
                path.reset()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) s * 0.42f else s * 0.18f
                    val a = (-PI / 2 + i * PI / 5).toFloat()
                    val x = cx + cos(a) * r
                    val y = cy + sin(a) * r
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                c.drawPath(path, p)
            }
            else -> { // Ic.OK: ring with a dot
                p.style = Paint.Style.STROKE
                p.strokeWidth = sw
                c.drawCircle(cx, cy, s * 0.46f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy, s * 0.16f, p)
            }
        }
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.BUTT
        p.strokeJoin = Paint.Join.MITER
    }

    // ---- chrome ------------------------------------------------------------------------------

    fun statusBar(c: Canvas, w: Float, title: String, st: StatusInfo) {
        val h = Belle.STATUS_H * sc
        rf.set(0f, 0f, w, h)
        fill(c, rf, Color.BLACK)
        nine(c, "qtg_fr_statusbar", rf, 20, 10f)
        val cy = h / 2f
        text(c, title, 8f * sc, baselineFor(cy, 18f * sc), 18f * sc, maxW = w * 0.5f)
        val tw = measure(st.clock, 18f * sc)
        text(c, st.clock, w - 8f * sc, baselineFor(cy, 18f * sc), 18f * sc, align = Paint.Align.RIGHT)
        val bw = 24f * sc
        val bh = 18f * sc
        val bLeft = w - 8f * sc - tw - 10f * sc - bw
        battery(c, bLeft, cy - bh / 2f, bw, bh, st.batteryPct)
        if (st.wifi) wifi(c, bLeft - 18f * sc, h * 0.76f, 10f * sc)
    }

    fun battery(c: Canvas, left: Float, top: Float, bw: Float, bh: Float, pct: Int) {
        rf.set(left, top, left + bw, top + bh)
        gfx.draw(c, "qtg_graf_battery_level_bg", rf)
        val full = gfx.get("qtg_graf_battery_level_full")
        if (full != null) {
            c.save()
            c.clipRect(left, top, left + bw * (0.12f + 0.76f * pct / 100f), top + bh)
            gfx.draw(c, "qtg_graf_battery_level_full", rf)
            c.restore()
        }
    }

    fun wifi(c: Canvas, cx: Float, cy: Float, r: Float) {
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.6f * sc
        p.color = Color.WHITE
        for (i in 1..3) {
            val rr = r * i / 3f
            rf.set(cx - rr, cy - rr, cx + rr, cy + rr)
            c.drawArc(rf, 225f, 90f, false, p)
        }
        p.style = Paint.Style.FILL
        c.drawCircle(cx, cy, 1.5f * sc, p)
    }

    /** Centres of [n] evenly spread toolbar buttons: first at the left edge, last at the right edge. */
    fun toolCenters(w: Float, n: Int): FloatArray {
        val m = 32f * sc
        if (n <= 1) return floatArrayOf(w / 2f)
        val step = (w - 2 * m) / (n - 1)
        return FloatArray(n) { m + it * step }
    }

    /** [kinds] are the glyphs left to right; [letters] the gamepad hint beside each (null = none). */
    fun toolbar(c: Canvas, w: Float, top: Float, kinds: IntArray, enabled: BooleanArray, letters: Array<String?>) {
        val h = Belle.TOOL_H * sc
        rf.set(0f, top, w, top + h)
        fill(c, rf, Color.BLACK)
        nine(c, "qtg_fr_toolbar", rf, 40, 20f)
        val cy = top + h / 2f
        val xs = toolCenters(w, kinds.size)
        for (i in kinds.indices) {
            val alpha = if (enabled[i]) 255 else 90
            glyph(c, kinds[i], xs[i], cy, 30f * sc, al(Color.WHITE, alpha))
            val l = letters[i]
            if (l != null) {
                val dx = if (i == kinds.size - 1) -27f * sc else 27f * sc
                text(c, l, xs[i] + dx, baselineFor(cy, 13f * sc, medium), 13f * sc, al(Color.WHITE, 110), Paint.Align.CENTER, face = medium)
            }
        }
    }

    fun tabBar(c: Canvas, w: Float, top: Float, labels: List<String>, kinds: IntArray, selected: Int, focused: Boolean) {
        val h = Belle.TAB_H * sc
        val tw = w / labels.size
        rf.set(0f, top, w, top + h)
        fill(c, rf, Color.BLACK)
        nine(c, "qtg_fr_tab_bar", rf, 40, 20f)
        for (i in labels.indices) {
            val sel = i == selected
            rf.set(i * tw, top, (i + 1) * tw, top + h)
            if (sel) {
                fill(c, rf, Color.BLACK)
                nine(c, "qtg_fr_tab_active", rf, 40, 20f, 190)
            } else {
                nine(c, "qtg_fr_tab_passive_normal", rf, 40, 20f)
            }
            if (sel && focused) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3f * sc
                p.color = Belle.BLUE_LIGHT
                c.drawRect(rf.left + 2f * sc, rf.top + 2f * sc, rf.right - 2f * sc, rf.bottom - 2f * sc, p)
                p.style = Paint.Style.FILL
            }
            val col = if (sel) Color.WHITE else Belle.TEXT_MID
            val face = if (sel) sans else light
            val tsz = 18f * sc
            val textW = measure(labels[i], tsz, face)
            val total = 30f * sc + 8f * sc + textW
            val sx = rf.centerX() - total / 2f
            glyph(c, kinds[i], sx + 15f * sc, top + h / 2f, 26f * sc, col)
            text(c, labels[i], sx + 38f * sc, baselineFor(top + h / 2f, tsz, face), tsz, col, face = face, maxW = tw - 50f * sc)
        }
    }

    // ---- lists ---------------------------------------------------------------------------------

    /** Belle ListItem: 70px, 12px inset, 50px icon, 22px title, 18px light subtitle, 1px separator. */
    fun listRow(c: Canvas, r: RectF, selected: Boolean, row: Row) {
        val pad = Belle.PADDING * sc
        val dim = if (row.dim) 120 else 255
        if (selected) {
            highlight(c, r, 20f)
        } else {
            p.style = Paint.Style.FILL
            p.shader = null
            p.color = Belle.SEPARATOR
            c.drawRect(r.left, r.bottom - 1f, r.right, r.bottom, p)
        }
        var tx = r.left + pad
        val iconSize = 50f * sc
        if (row.icon != null || row.iconKind >= 0) {
            tmp.set(tx, r.centerY() - iconSize / 2f, tx + iconSize, r.centerY() + iconSize / 2f)
            if (row.icon != null) {
                drawBitmap(c, row.icon, tmp, dim)
            } else {
                iconFrame(c, tmp, row.iconColor, row.iconKind)
            }
            tx += iconSize + pad
        }
        var rightEdge = r.right - pad - row.rightReserve * sc
        if (row.drill) {
            val ds = 30f * sc
            gfx.glyph(c, "qtg_graf_drill_down_indicator", r.right - pad - ds / 2f, r.centerY(), ds, 255)
            rightEdge -= ds + 4f * sc
        }
        if (row.check != null) {
            val cs = 42f * sc
            gfx.glyph(c, if (row.check) "qtg_graf_checkbox_normal_selected" else "qtg_graf_checkbox_normal_unselected", rightEdge - cs / 2f, r.centerY(), cs)
            rightEdge -= cs + pad
        }
        if (row.right != null) {
            val tw = measure(row.right, 18f * sc, light)
            text(c, row.right, rightEdge, baselineFor(r.centerY(), 18f * sc, light), 18f * sc, if (selected) Color.WHITE else Belle.TEXT_MID, Paint.Align.RIGHT, face = light)
            rightEdge -= tw + pad
        }
        val maxW = rightEdge - tx
        val white = al(Color.WHITE, dim)
        if (row.subtitle != null) {
            text(c, row.title, tx, baselineFor(r.centerY() - 14f * sc, 22f * sc), 22f * sc, white, maxW = maxW)
            text(c, row.subtitle, tx, baselineFor(r.centerY() + 15f * sc, 18f * sc, light), 18f * sc, if (selected) Color.WHITE else al(Belle.TEXT_MID, dim), maxW = maxW, face = light)
        } else {
            text(c, row.title, tx, baselineFor(r.centerY(), 22f * sc), 22f * sc, white, maxW = maxW)
        }
    }

    fun scrollbar(c: Canvas, w: Float, top: Float, bottom: Float, frac: Float, thumbFrac: Float) {
        val trackH = bottom - top - 8f * sc
        val thumbH = max(24f * sc, trackH * thumbFrac)
        val ty = top + 4f * sc + (trackH - thumbH) * frac.coerceIn(0f, 1f)
        rf.set(w - 6f * sc, ty, w - 3f * sc, ty + thumbH)
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = al(Color.WHITE, 150)
        c.drawRoundRect(rf, 1.5f * sc, 1.5f * sc, p)
    }

    fun progressBar(c: Canvas, r: RectF, frac: Float) {
        nine(c, "qtg_fr_progressbar_track", r, 20, 10f)
        val f = frac.coerceIn(0f, 1f)
        if (f > 0.01f) {
            tmp.set(r.left, r.top, r.left + max(r.height(), r.width() * f), r.bottom)
            nine(c, "qtg_fr_progressbar_fill", tmp, 20, 10f)
        }
    }

    fun switchButton(c: Canvas, cx: Float, cy: Float, on: Boolean) {
        val w = 66f * sc
        val h = 44f * sc
        rf.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        gfx.draw(c, "qtg_graf_switchbutton_track", rf)
        if (on) gfx.draw(c, "qtg_graf_switchbutton_fill", rf)
        val hx = if (on) rf.right - h / 2f else rf.left + h / 2f
        tmp.set(hx - h / 2f, cy - h / 2f, hx + h / 2f, cy + h / 2f)
        gfx.draw(c, "qtg_graf_switchbutton_handle_normal", tmp)
    }

    fun pushButton(c: Canvas, r: RectF, label: String, selected: Boolean) {
        if (selected) {
            nine(c, "qtg_fr_pushbutton_pressed", r, 40, 20f)
        } else {
            // colours sampled from the real Belle Cancel / Yes / No buttons
            val rad = 6f * sc
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, r.top, 0f, r.bottom, 0xFF585953.toInt(), 0xFF393A34.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, rad, rad, p)
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1f * sc
            p.color = al(Color.BLACK, 170)
            c.drawRoundRect(r, rad, rad, p)
            p.style = Paint.Style.FILL
        }
        text(c, label, r.centerX(), baselineFor(r.centerY(), 22f * sc), 22f * sc, Color.WHITE, Paint.Align.CENTER, maxW = r.width() - 8f * sc)
    }

    fun banner(c: Canvas, w: Float, text: String, t: Float) {
        val top0 = Belle.STATUS_H * sc
        val h = Belle.TOOL_H * sc
        val top = top0 - (1f - t) * h
        c.save()
        c.clipRect(0f, top0, w, top0 + h)
        rf.set(0f, top, w, top + h)
        fill(c, rf, Color.BLACK)
        nine(c, "qtg_fr_popup_infobanner", rf, 40, 20f)
        text(c, text, 16f * sc, baselineFor(top + h / 2f, 20f * sc), 20f * sc, Color.WHITE, maxW = w - 32f * sc)
        c.restore()
    }
}
