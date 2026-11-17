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

/** Belle measurements and colours, on the 640x480 grid of the Nokia E6 (1 design px = 1 E6 px). */
object Belle {
    const val DEFAULT_BLUE = 0xFF1080DD.toInt()

    /** The accent colour. Belle blue unless a theme colour was chosen in the Styles app. */
    @JvmField var BLUE = DEFAULT_BLUE
    @JvmField var BLUE_LIGHT = 0xFF5CB4F8.toInt()

    /** Bumped whenever the accent changes, so cached tinted graphics are rebuilt. */
    @JvmField @Volatile var accentVersion = 0

    /** Light list screens (the Belle "inverse" look) on or off (set in Styles). */
    @JvmField @Volatile var lightLists = false

    /** Page colour behind the light lists. */
    const val LIGHT_BG = 0xFFF0F0F0.toInt()

    /** Navigation sounds on or off (set in Styles). */
    @JvmField @Volatile var soundsOn = true

    /** The small gamepad letters beside the toolbar buttons (set in Styles). The E6 toolbar has none: off is the real look. */
    @JvmField @Volatile var keyHints = true

    fun setAccent(a: Int) {
        BLUE = a
        BLUE_LIGHT = Color.rgb(
            Color.red(a) + ((255 - Color.red(a)) * 0.4f).toInt(),
            Color.green(a) + ((255 - Color.green(a)) * 0.4f).toInt(),
            Color.blue(a) + ((255 - Color.blue(a)) * 0.4f).toInt(),
        )
        accentVersion++
    }
    const val TEXT_MID = 0xFF999999.toInt()
    const val SEPARATOR = 0xFF444444.toInt()
    const val PANEL = 0xFF1B1B1B.toInt()

    /** True when the Belle System UI draws the status bar: the apps then draw none of their own and sit below the real one. */
    @JvmField @Volatile var sysBar = false

    /** Height in px of the real status bar (read with [detectSysBar]); the apps lay out under it and keep this strip free. */
    @JvmField @Volatile var sysBarPx = 0

    /** Screen px per design px, set by the shell and the launcher, so [statusH] can be given in design px. */
    @JvmField @Volatile var sc = 1f

    /**
     * Height (design px) of the strip at the top that belongs to the status bar: the one the app draws itself, or the
     * room kept free for the real one. The window always runs up to the top edge, so this does not depend on the
     * system reporting the bar as an inset.
     */
    fun statusH(): Float = if (sysBar) sysBarPx / sc else STATUS_H

    /** Looks for the Belle System UI: it writes the boot number into a setting once it is the registered status bar. */
    fun detectSysBar(ctx: android.content.Context) {
        sysBar = try {
            val r = ctx.contentResolver
            val ok = android.provider.Settings.Global.getString(r, "belle_sysui_ok")
            ok != null && ok == android.provider.Settings.Global.getString(r, android.provider.Settings.Global.BOOT_COUNT)
        } catch (_: Throwable) {
            false
        }
        sysBarPx = try {
            val res = android.content.res.Resources.getSystem()
            val id = res.getIdentifier("status_bar_height", "dimen", "android")
            if (id != 0) res.getDimensionPixelSize(id) else (24 * res.displayMetrics.density).toInt()
        } catch (_: Throwable) {
            0
        }
    }

    /** Tells the real status bar what this app's current screen is called (it shows it on the left). */
    fun publishTitle(ctx: android.content.Context, title: String) {
        if (!sysBar) return
        try {
            ctx.sendBroadcast(android.content.Intent("com.belleui.kit.TITLE").setPackage("com.android.systemui").putExtra("pkg", ctx.packageName).putExtra("title", title))
        } catch (_: Throwable) { }
    }

    // From the E6 firmware's own layout data (2001cb90.dll, Elaf_Vga4_touch_land_Normal_Pack); see docs/E6-LAYOUT.md
    const val STATUS_H = 35f   // area_top_pane, landscape flat status pane
    const val TAB_H = 68f      // as tall as the toolbar
    const val TOOL_H = 68f     // area_bottom_pane / control pane (main_pane is t=35 b=68)
    const val ROW_H = 94f      // list_double_pane / list_double_large_graphic_pane
    const val MENU_ITEM_H = 67f // list_single_pane_cp2 in popup_menu_window (480 wide)
    const val LIST_INSET = 14f // list_gen_pane l/r inside listscroll_gen_pane
    const val LIST_ICON = 72f  // list_double_large_graphic_pane_g1: 72x72 at l=10 t=11
    const val LIST_ICON_X = 10f
    const val LIST_TEXT_X = 90f // list_double_large_graphic_pane_t1 l=90
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
    const val SHUFFLE = 40
    const val SHUFFLE_OFF = 41
    const val REPEAT = 42
    const val REPEAT_ONE = 43
    const val REPEAT_OFF = 44
    const val NOWPLAY = 45
    const val FAVOURITE = 46
    const val FORWARD = 47
    const val REWIND = 48
    const val AUDIO_ON = 49
    const val AUDIO_OFF = 50
    const val TOOLS = 51
    const val WLAN_ON = 52
    const val BOOKMARKS = 60
    const val ADD_BOOKMARK = 61
    const val FIND = 62
    const val DOWNLOADS = 63
    const val WEB_HOME = 64
    const val GOTO = 65
    const val HISTORY_BACK = 66
    const val HISTORY_FORWARD = 67
    const val FULLSCREEN = 68
    const val IMAGES = 69
    const val SLIDESHOW = 70
    const val GALLERY = 71
    const val NEW_TIMER = 72
    const val COPY = 80
    const val CUT = 81
    const val PASTE = 82
    const val EDIT = 83
    const val DETAILS = 84
    const val SORT = 85
    const val EJECT = 86
    const val BLUETOOTH = 87
    const val SIGNAL = 88
    const val LOCK = 89
    const val SYNC = 90
    const val CALL = 91
    const val UNMARK = 92

    val stock = mapOf(
        BACK to "toolbar_back", MENU to "toolbar_menu", SETTINGS to "toolbar_settings", ADD to "toolbar_add",
        SEARCH to "toolbar_search", NEXT to "qtg_toolbar_next", PREVIOUS to "qtg_toolbar_previous",
        REFRESH to "toolbar_refresh", DELETE to "toolbar_delete", SHARE to "toolbar_share",
        HOME to "toolbar_home", LIST to "toolbar_list", CLOSE to "qtg_graf_popup_close_normal",
        // original Belle toolbar glyphs from the E6 theme pack
        PLAY to "qtg_toolbar_play", PAUSE to "qtg_toolbar_pause", STOP to "qtg_toolbar_stop",
        SHUFFLE to "qtg_toolbar_shuffle", SHUFFLE_OFF to "qtg_toolbar_shuffle_off",
        REPEAT to "qtg_toolbar_repeat", REPEAT_ONE to "qtg_toolbar_repeat_one", REPEAT_OFF to "qtg_toolbar_repeat_off",
        NOWPLAY to "qtg_toolbar_nowplay", FAVOURITE to "qtg_toolbar_favourites", FORWARD to "qtg_toolbar_forward",
        REWIND to "qtg_toolbar_rewind", AUDIO_ON to "qtg_toolbar_audio_on", AUDIO_OFF to "qtg_toolbar_audio_off",
        TOOLS to "qtg_toolbar_tools", WLAN_ON to "qtg_toolbar_wlan_on",
        BOOKMARKS to "qgn_indi_browser_tb_bm", ADD_BOOKMARK to "qgn_indi_browser_tb_add_bm_new", FIND to "qgn_indi_browser_tb_find",
        DOWNLOADS to "qgn_indi_browser_tb_downloads", WEB_HOME to "qgn_indi_browser_tb_home", GOTO to "qgn_indi_browser_tb_goto",
        HISTORY_BACK to "qgn_indi_browser_tb_history_back", HISTORY_FORWARD to "qgn_indi_browser_tb_history_fw",
        FULLSCREEN to "qgn_indi_browser_tb_full", IMAGES to "qgn_indi_browser_tb_images", SLIDESHOW to "qgn_indi_lgal_tb_slideshow",
        GALLERY to "qgn_indi_tb_gallery", NEW_TIMER to "qgn_indi_tb_new_timer",
        COPY to "qtg_toolbar_copy", CUT to "qtg_toolbar_cut", PASTE to "qtg_toolbar_paste", EDIT to "qtg_toolbar_edit",
        DETAILS to "qtg_toolbar_details", SORT to "qgn_indi_tb_fmgr_sort", EJECT to "qgn_indi_tb_fmgr_eject",
        BLUETOOTH to "qtg_toolbar_bluetooth_on", SIGNAL to "qtg_toolbar_signal_on", LOCK to "qtg_toolbar_lock",
        SYNC to "qtg_toolbar_sync", CALL to "qtg_toolbar_call", UNMARK to "qtg_toolbar_unmark",
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
    /** E6 setting item: the title on top and the value in a dark button under it, on the right ([right] is the value). */
    val button: Boolean = false,
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
    // the firmware's own typeface, Nokia Sans S60 (see NokiaFonts); it has no Light weight, so Light is Regular
    val sans: Typeface = NokiaFonts.regular(ctx)
    val light: Typeface = sans
    val medium: Typeface = NokiaFonts.semibold(ctx)
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

    /** True while drawing on a light list page; dark chrome (bars, buttons, selected rows) switches it off. */
    var lightMode = false
    private var darkDepth = 0
    val lightOn: Boolean get() = lightMode && darkDepth == 0

    /** Runs [block] with the dark-surface text colours (white on the bars, the buttons and the blue highlight). */
    inline fun <T> onDark(block: () -> T): T {
        enterDark()
        try {
            return block()
        } finally {
            leaveDark()
        }
    }

    fun enterDark() {
        darkDepth++
    }

    fun leaveDark() {
        darkDepth--
    }

    /** On a light page the grey and white used for dark pages become dark greys. */
    private fun forPage(color: Int): Int {
        if (!lightOn) return color
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        if (kotlin.math.abs(r - g) > 10 || kotlin.math.abs(g - b) > 10) return color
        val a = Color.alpha(color)
        return when {
            r > 0xE0 -> Color.argb(a, 0x1B, 0x1B, 0x1B)
            r >= 0x80 -> Color.argb(a, 0x66, 0x66, 0x66)
            else -> color
        }
    }

    fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int = Color.WHITE,
        align: Paint.Align = Paint.Align.LEFT, maxW: Float = 0f, face: Typeface = sans,
    ) {
        tp.textSize = size
        tp.color = forPage(color)
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
        val pad = 16f * sc
        if (text.isEmpty()) {
            this.text(c, hint, r.left + pad, baselineFor(r.centerY(), 26f * sc), 26f * sc, 0xFF8A8A8A.toInt(), maxW = r.width() - 2 * pad)
        } else {
            this.text(c, text, r.left + pad, baselineFor(r.centerY(), 26f * sc), 26f * sc, 0xFF1B1B1B.toInt(), maxW = r.width() - 2 * pad)
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

    fun statusBar(c: Canvas, w: Float, title: String, st: StatusInfo) = onDark { statusBar0(c, w, title, st) }

    private fun statusBar0(c: Canvas, w: Float, title: String, st: StatusInfo) {
        val h = Belle.STATUS_H * sc
        rf.set(0f, 0f, w, h)
        fill(c, rf, Color.BLACK)
        nine(c, "qtg_fr_statusbar", rf, 20, 10f)
        val cy = h / 2f
        text(c, title, 6f * sc, baselineFor(cy, 24f * sc), 24f * sc, maxW = w * 0.5f)
        // the right side follows StatusBarDefault.qml of the firmware: every size a fraction of the bar height
        val ch = Math.round(h * 18f / 26f).toFloat()                  // contentHeight
        val padS = 6f * h / 36f                                        // paddingSmall
        val pad34 = Math.round(padS * 3f / 4f).toFloat()
        val clockRight = w - padS
        val clockW = Math.round(h * 44f / 26f).toFloat()
        text(c, st.clock, clockRight, baselineFor(cy, ch, light), ch, align = Paint.Align.RIGHT, face = light)
        val clockLeft = clockRight - max(clockW, measure(st.clock, ch, light))
        val bw = Math.round(h * 24f / 26f).toFloat()
        val bRight = clockLeft - pad34
        val level = (st.batteryPct * 7 + 50) / 100
        battery(c, bRight - bw, cy - ch / 2f, bw, ch, st.batteryPct, h, level.coerceIn(0, 7))
        if (st.wifi) wifi(c, bRight - bw - pad34 - ch / 2f, cy + ch * 0.3f, ch * 0.5f)
    }

    /** The battery as the status bar draws it: the fill is (level + 2) / 13 of the bar height wide, level 0..7. */
    fun battery(c: Canvas, left: Float, top: Float, bw: Float, bh: Float, pct: Int, barH: Float = 0f, level: Int = -1) {
        rf.set(left, top, left + bw, top + bh)
        gfx.draw(c, "qtg_graf_battery_level_bg", rf)
        val full = gfx.get("qtg_graf_battery_level_full")
        if (full != null) {
            c.save()
            val fillW = if (barH > 0f && level >= 0) Math.round(barH * (level + 2) / 13f).toFloat() else bw * (0.12f + 0.76f * pct / 100f)
            c.clipRect(left, top, left + fillW, top + bh)
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
        val m = 58f * sc
        if (n <= 1) return floatArrayOf(w / 2f)
        val step = (w - 2 * m) / (n - 1)
        return FloatArray(n) { m + it * step }
    }

    /** [kinds] are the glyphs left to right; [letters] the gamepad hint beside each (null = none). */
    fun toolbar(c: Canvas, w: Float, top: Float, kinds: IntArray, enabled: BooleanArray, letters: Array<String?>) = onDark { toolbar0(c, w, top, kinds, enabled, letters) }

    private fun toolbar0(c: Canvas, w: Float, top: Float, kinds: IntArray, enabled: BooleanArray, letters: Array<String?>) {
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
            if (l != null && Belle.keyHints) {
                val dx = if (i == kinds.size - 1) -27f * sc else 27f * sc
                text(c, l, xs[i] + dx, baselineFor(cy, 13f * sc, medium), 13f * sc, al(Color.WHITE, 110), Paint.Align.CENTER, face = medium)
            }
        }
    }

    fun tabBar(c: Canvas, w: Float, top: Float, labels: List<String>, kinds: IntArray, selected: Int, focused: Boolean) = onDark { tabBar0(c, w, top, labels, kinds, selected, focused) }

    private fun tabBar0(c: Canvas, w: Float, top: Float, labels: List<String>, kinds: IntArray, selected: Int, focused: Boolean) {
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
            val face = light
            val tsz = 22f * sc
            val textW = measure(labels[i], tsz, face)
            val total = 40f * sc + 10f * sc + textW
            val sx = rf.centerX() - total / 2f
            glyph(c, kinds[i], sx + 20f * sc, top + h / 2f, 34f * sc, col)
            text(c, labels[i], sx + 50f * sc, baselineFor(top + h / 2f, tsz, face), tsz, col, face = face, maxW = tw - 62f * sc)
        }
    }

    // ---- lists ---------------------------------------------------------------------------------

    /** E6 list item: 94 px tall, 20 px inset, 64 px icon, 26 px title, 21 px light subtitle, 1 px separator. */
    fun listRow(c: Canvas, r: RectF, selected: Boolean, row: Row) {
        // the blue highlight keeps white text on a light page too
        if (selected) enterDark()
        try {
            listRow0(c, r, selected, row)
        } finally {
            if (selected) leaveDark()
        }
    }

    private fun listRow0(c: Canvas, r: RectF, selected: Boolean, row: Row) {
        val pad = 20f * sc
        val inv = if (lightOn) "_inverse" else ""
        val dim = if (row.dim) 120 else 255
        if (selected) {
            highlight(c, r, 20f)
        } else {
            p.style = Paint.Style.FILL
            p.shader = null
            p.color = if (lightOn) al(Color.BLACK, 40) else Belle.SEPARATOR
            c.drawRect(r.left, r.bottom - 1f, r.right, r.bottom, p)
        }
        if (row.button && row.right != null) {
            val white0 = al(Color.WHITE, dim)
            text(c, row.title, r.left + pad, baselineFor(r.top + 31f * sc, 26f * sc), 26f * sc, white0, maxW = r.width() - 2 * pad)
            // the value button: 336 px wide, 31 px tall, right-aligned with 51 px of margin (measured on the E6)
            val bw = min(336f * sc, r.width() - 2 * pad - 20f * sc)
            val right = r.right - 51f * sc * (r.width() / (640f * sc)).coerceAtMost(1f)
            tmp.set(right - bw, r.top + 52f * sc, right, r.top + 83f * sc)
            val rad = 5f * sc
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, tmp.top, 0f, tmp.bottom, 0xFF4F4F4F.toInt(), 0xFF262626.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(tmp, rad, rad, p)
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1f * sc
            p.color = 0xFF000000.toInt()
            c.drawRoundRect(tmp, rad, rad, p)
            p.style = Paint.Style.FILL
            text(c, row.right, tmp.centerX(), baselineFor(tmp.centerY(), 22f * sc), 22f * sc, al(Color.WHITE, dim), Paint.Align.CENTER, maxW = tmp.width() - 16f * sc)
            return
        }
        var tx = r.left + pad
        val iconSize = Belle.LIST_ICON * sc
        if (row.icon != null || row.iconKind >= 0) {
            tx = r.left + Belle.LIST_ICON_X * sc
            tmp.set(tx, r.centerY() - iconSize / 2f, tx + iconSize, r.centerY() + iconSize / 2f)
            if (row.icon != null) {
                drawBitmap(c, row.icon, tmp, dim)
            } else {
                iconFrame(c, tmp, row.iconColor, row.iconKind)
            }
            tx = r.left + Belle.LIST_TEXT_X * sc
        }
        var rightEdge = r.right - pad - row.rightReserve * sc
        if (row.drill) {
            val ds = 30f * sc
            gfx.glyph(c, "qtg_graf_drill_down_indicator$inv", r.right - pad - ds / 2f, r.centerY(), ds, 255)
            rightEdge -= ds + 4f * sc
        }
        if (row.check != null) {
            val cs = 56f * sc
            gfx.glyph(c, (if (row.check) "qtg_graf_checkbox_normal_selected" else "qtg_graf_checkbox_normal_unselected") + inv, rightEdge - cs / 2f, r.centerY(), cs)
            rightEdge -= cs + pad
        }
        if (row.right != null) {
            val tw = measure(row.right, 21f * sc, light)
            text(c, row.right, rightEdge, baselineFor(r.centerY(), 21f * sc, light), 21f * sc, if (selected) Color.WHITE else Belle.TEXT_MID, Paint.Align.RIGHT, face = light)
            rightEdge -= tw + pad
        }
        val maxW = rightEdge - tx
        val white = al(Color.WHITE, dim)
        if (row.subtitle != null) {
            text(c, row.title, tx, baselineFor(r.centerY() - 17f * sc, 26f * sc), 26f * sc, white, maxW = maxW)
            text(c, row.subtitle, tx, baselineFor(r.centerY() + 20f * sc, 21f * sc, light), 21f * sc, if (selected) Color.WHITE else al(Belle.TEXT_MID, dim), maxW = maxW, face = light)
        } else {
            text(c, row.title, tx, baselineFor(r.centerY(), 26f * sc), 26f * sc, white, maxW = maxW)
        }
    }

    /** A Belle list section bar: the group's letter on a heading strip. */
    fun sectionHeader(c: Canvas, r: RectF, label: String) {
        val inv = if (lightOn) "_inverse" else ""
        if (!nine(c, "qtg_fr_list_heading_normal$inv", r, 20, 4f)) {
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, r.top, 0f, r.bottom, 0xFF4A4A4A.toInt(), 0xFF383838.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(r, p)
            p.shader = null
        }
        val size = 16f * sc
        text(c, label, r.left + 14f * sc, baselineFor(r.centerY(), size, medium), size, Color.WHITE, face = medium)
    }

    fun scrollbar(c: Canvas, w: Float, top: Float, bottom: Float, frac: Float, thumbFrac: Float) {
        val trackH = bottom - top - 8f * sc
        // ScrollBar.qml: the handle is at least 3 x the 18 px thickness long; its graphic is a 6 px wide capsule, #999999 at 60 %
        val thumbH = max(54f * sc, trackH * thumbFrac).coerceAtMost(trackH)
        val ty = top + 4f * sc + (trackH - thumbH) * frac.coerceIn(0f, 1f)
        rf.set(w - 12f * sc, ty, w - 6f * sc, ty + thumbH)
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = if (lightOn) al(Color.BLACK, 110) else 0x99999999.toInt()
        c.drawRoundRect(rf, 3f * sc, 3f * sc, p)
    }

    fun progressBar(c: Canvas, r0: RectF, frac: Float) {
        // ProgressBar.qml: implicitHeight is sliderThickness, 28 px on the E6; a thinner box is grown around its middle
        val r = RectF(r0)
        val minH = 28f * sc
        if (r.height() < minH) {
            val grow = (minH - r.height()) / 2f
            r.top -= grow
            r.bottom += grow
        }
        val inv = if (lightOn) "_inverse" else ""
        nine(c, "qtg_fr_progressbar_track$inv", r, 20, 10f)
        val f = frac.coerceIn(0f, 1f)
        if (f > 0.01f) {
            tmp.set(r.left, r.top, r.left + max(r.height(), r.width() * f), r.bottom)
            nine(c, "qtg_fr_progressbar_fill", tmp, 20, 10f)
        }
    }

    fun switchButton(c: Canvas, cx: Float, cy: Float, on: Boolean) {
        val h = 60f * sc          // privateStyle.switchButtonHeight
        val w = h * 1.5f           // Switch.qml: the track is 3/2 of that
        rf.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        gfx.draw(c, if (lightOn) "qtg_graf_switchbutton_track_inverse" else "qtg_graf_switchbutton_track", rf)
        if (on) gfx.draw(c, "qtg_graf_switchbutton_fill", rf)
        val hx = if (on) rf.right - h / 2f else rf.left + h / 2f
        tmp.set(hx - h / 2f, cy - h / 2f, hx + h / 2f, cy + h / 2f)
        gfx.draw(c, "qtg_graf_switchbutton_handle_normal", tmp)
    }

    /** A round dark control key (media buttons): a glossy disc with a thin dark ring and a white [kind] glyph in the middle. */
    fun roundButton(c: Canvas, cx: Float, cy: Float, d: Float, kind: Int, pressed: Boolean = false) {
        val rad = d / 2f
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = al(Color.BLACK, 120)
        c.drawCircle(cx, cy + 2f * sc, rad, p)
        p.shader = LinearGradient(0f, cy - rad, 0f, cy + rad,
            if (pressed) intArrayOf(Belle.BLUE_LIGHT, Belle.BLUE) else intArrayOf(0xFF4E4E4E.toInt(), 0xFF141414.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * sc
        p.color = 0xFF000000.toInt()
        c.drawCircle(cx, cy, rad, p)
        p.style = Paint.Style.FILL
        glyph(c, kind, cx, cy, d * 0.52f)
    }

    /** The E6 popup plate: near-black with a lighter top edge, a thin border and a soft shadow (measured on the device). */
    fun popupPlate(c: Canvas, r: RectF) = onDark {
        val rad = 7f * sc
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = al(Color.BLACK, 120)
        rf.set(r.left - 3f * sc, r.top - 1f * sc, r.right + 3f * sc, r.bottom + 6f * sc)
        c.drawRoundRect(rf, rad + 3f * sc, rad + 3f * sc, p)
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(0xFF525252.toInt(), 0xFF303030.toInt(), 0xFF1D1D1D.toInt(), 0xFF1B1B1B.toInt()), floatArrayOf(0f, 0.05f, 0.14f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * sc
        p.color = 0xFF000000.toInt()
        c.drawRoundRect(r, rad, rad, p)
        p.style = Paint.Style.FILL
    }

    fun pushButton(c: Canvas, r: RectF, label: String, selected: Boolean) = onDark { pushButton0(c, r, label, selected) }

    private fun pushButton0(c: Canvas, r: RectF, label: String, selected: Boolean) {
        if (selected) {
            nine(c, "qtg_fr_pushbutton_pressed", r, 40, 20f)
        } else {
            // the E6 dialog button, sampled from the device: a lighter 1 px top edge, then dark grey fading to near black
            val rad = 6f * sc
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(0xFF737373.toInt(), 0xFF404040.toInt(), 0xFF2E2E2E.toInt(), 0xFF1F1F1F.toInt()), floatArrayOf(0f, 0.03f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, rad, rad, p)
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1f * sc
            p.color = 0xFF000000.toInt()
            c.drawRoundRect(r, rad, rad, p)
            p.style = Paint.Style.FILL
        }
        val size = min(28f * sc, r.height() * 0.5f)
        text(c, label, r.centerX(), baselineFor(r.centerY(), size), size, Color.WHITE, Paint.Align.CENTER, maxW = r.width() - 8f * sc)
    }

    fun banner(c: Canvas, w: Float, text: String, t: Float) = onDark { banner0(c, w, text, t) }

    private fun banner0(c: Canvas, w: Float, text: String, t: Float) {
        val top0 = Belle.statusH() * sc
        val h = Belle.TOOL_H * sc
        val top = top0 - (1f - t) * h
        c.save()
        c.clipRect(0f, top0, w, top0 + h)
        rf.set(0f, top, w, top + h)
        // InfoBanner.qml: the graphic at 90 % opacity, text fontSizeMedium (28) with paddingLarge (18) at its sides
        nine(c, "qtg_fr_popup_infobanner", rf, 40, 20f, 230)
        text(c, text, 18f * sc, baselineFor(top + h / 2f, 28f * sc), 28f * sc, Color.WHITE, maxW = w - 36f * sc)
        c.restore()
    }
}
