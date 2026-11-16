package com.belleui.styles

import android.Manifest
import android.app.WallpaperManager
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.util.Size
import com.belleui.kit.Act
import com.belleui.kit.Belle
import com.belleui.kit.GridState
import com.belleui.kit.Ic
import com.belleui.kit.ListDialog
import com.belleui.kit.ListState
import com.belleui.kit.MenuItem
import com.belleui.kit.Row
import com.belleui.kit.Sounds
import com.belleui.kit.ThemeStore
import com.belleui.kit.Screen
import com.belleui.kit.TabDef
import com.belleui.kit.Tool
import com.belleui.kit.Ui
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private fun imagePerms(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

private fun audioPerms(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

// ================================================================================================
// root
// ================================================================================================

class RootScreen(private val act: StylesActivity) : Screen() {
    override val lightOk: Boolean get() = true
    override val title get() = "Styles"
    private val list = ListState()
    private val types = intArrayOf(RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION, RingtoneManager.TYPE_ALARM)
    private val toneTitles = arrayOfNulls<String>(3)

    private val accents = listOf(
        "Belle blue" to 0xFF1080DD.toInt(), "Teal" to 0xFF27B3A0.toInt(), "Green" to 0xFF4C9A2E.toInt(), "Orange" to 0xFFE8761A.toInt(),
        "Red" to 0xFFD0342C.toInt(), "Magenta" to 0xFFD0338A.toInt(), "Purple" to 0xFF8A4CB5.toInt(), "Steel" to 0xFF5C7A99.toInt(),
    )
    private val styles by lazy { act.getSharedPreferences("styles", android.content.Context.MODE_PRIVATE) }

    private fun accentName(): String {
        val a = styles.getInt("accent", 0)
        return if (a == 0) "Belle blue" else accents.firstOrNull { it.second == a }?.first ?: "Custom"
    }

    private fun soundsOn() = styles.getBoolean("sounds", true)

    /** key, label, subtitle for the three feedback switches */
    private val switches = listOf(
        Triple(Settings.System.SOUND_EFFECTS_ENABLED, "Touch sounds", "Clicks when you press things"),
        Triple(Settings.System.HAPTIC_FEEDBACK_ENABLED, "Vibrate on touch", "Short buzz for presses"),
        Triple("lockscreen_sounds_enabled", "Screen lock sound", "When the screen locks or unlocks"),
    )

    override fun onShow() {
        act.onResumeHook = { loadTitles() }
        loadTitles()
    }

    private fun loadTitles() {
        Thread {
            for (i in types.indices) toneTitles[i] = toneTitle(act, types[i])
            shell.postInvalidate()
        }.start()
    }

    private fun switchOn(key: String) = try { Settings.System.getInt(act.contentResolver, key, 1) != 0 } catch (_: Exception) { true }

    private fun rows(): List<Row> {
        val g = shell.ui.gfx
        val out = ArrayList<Row>()
        out.add(Row("Wallpaper", "Pictures for Home and lock screen", icon = g.get("qgn_menu_photos"), drill = true))
        out.add(Row("Theme colour", accentName() + " - all Belle apps and the launcher", icon = g.get("qgn_menu_theme"), drill = true))
        out.add(Row("Navigation sounds", "Soft clicks as you move and choose", icon = g.get("qgn_prop_set_tones_sub"), check = soundsOn()))
        out.add(Row("Ringtone", toneTitles[0] ?: "...", icon = g.get("qgn_prop_set_tones_sub"), drill = true))
        out.add(Row("Notification tone", toneTitles[1] ?: "...", icon = g.get("qgn_prop_set_tones_sub"), drill = true))
        out.add(Row("Alarm tone", toneTitles[2] ?: "...", icon = g.get("qgn_prop_set_tones_sub"), drill = true))
        for ((k, label, sub) in switches) out.add(Row(label, sub, icon = g.get("qgn_prop_set_gene_phone"), check = switchOn(k)))
        out.add(Row("Light lists", "Light Belle look for list apps (Settings, Files, Notes...)", icon = g.get("qgn_menu_theme"), check = styles.getBoolean("light", false)))
        out.add(Row("Button hints", "Gamepad letters beside the toolbar buttons (the E6 has none)", icon = g.get("qgn_prop_set_gene_phone"), check = styles.getBoolean("hints", true)))
        return out
    }

    private fun area() = RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom)

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        val rows = rows()
        list.draw(c, ui, r, rows.size) { rows[it] }
    }

    private fun open(i: Int) {
        when {
            i == 0 -> shell.push(WallpaperScreen(act))
            i == 1 -> shell.showOverlay(ListDialog("Theme colour", accents.map { it.first }, accents.indexOfFirst { it.first == accentName() }) { k ->
                val a = accents[k].second
                ThemeStore.apply(act, a, soundsOn())
                styles.edit().putInt("accent", a).apply()
                shell.toast("${accents[k].first} - other apps update when you open them")
                shell.invalidate()
            })
            i == 2 -> {
                val on = !soundsOn()
                styles.edit().putBoolean("sounds", on).apply()
                ThemeStore.apply(act, styles.getInt("accent", 0).let { if (it == 0) Belle.DEFAULT_BLUE else it }, on)
                if (styles.getInt("accent", 0) == 0) styles.edit().remove("accent").apply()
                if (on) Sounds.play(Sounds.SELECT)
            }
            i == 9 -> {
                val on = !styles.getBoolean("light", false)
                styles.edit().putBoolean("light", on).apply()
                ThemeStore.apply(act, styles.getInt("accent", 0).let { if (it == 0) Belle.DEFAULT_BLUE else it }, soundsOn(), on)
                if (styles.getInt("accent", 0) == 0) styles.edit().remove("accent").apply()
                shell.toast(if (on) "Light lists on - other apps update when you open them" else "Light lists off")
            }
            i == 10 -> {
                val on = !styles.getBoolean("hints", true)
                styles.edit().putBoolean("hints", on).apply()
                ThemeStore.apply(act, styles.getInt("accent", 0).let { if (it == 0) Belle.DEFAULT_BLUE else it }, soundsOn(), hints = on)
                if (styles.getInt("accent", 0) == 0) styles.edit().remove("accent").apply()
                shell.toast(if (on) "Button hints on" else "Button hints off - other apps update when you open them")
            }
            i in 3..5 -> shell.push(TonesScreen(act, types[i - 3], listOf("Ringtone", "Notification tone", "Alarm tone")[i - 3]))
            else -> {
                if (!act.canWriteSettings()) {
                    act.askWriteSettings()
                    shell.toast("Allow \"Modify system settings\" for Styles, then come back")
                    return
                }
                val key = switches[i - 6].first
                try {
                    Settings.System.putInt(act.contentResolver, key, if (switchOn(key)) 0 else 1)
                } catch (_: Exception) {
                    shell.toast("Couldn't change that")
                }
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
        val i = list.hit(shell.ui, area(), y, rows().size)
        if (i < 0) return false
        list.sel = i
        open(i)
        return true
    }

    override fun onScroll(dy: Float) = list.scrollBy(if (dy > 0) 1 else -1, rows().size, list.visible(shell.ui, shell.contentBottom - shell.contentTop))
}

fun toneTitle(ctx: android.content.Context, type: Int): String = try {
    val u = RingtoneManager.getActualDefaultRingtoneUri(ctx, type)
    if (u == null) "Silent" else (RingtoneManager.getRingtone(ctx, u)?.getTitle(ctx) ?: "Default")
} catch (_: Exception) {
    "Default"
}

// ================================================================================================
// tones
// ================================================================================================

class Tone(val title: String, val uri: Uri) {
    val silent: Boolean get() = uri == Uri.EMPTY
}

/** The ringtone picker other apps ask for: what to offer and what is selected now. */
class Pick(val existing: Uri?, val showDefault: Boolean, val showSilent: Boolean)

class TonesScreen(private val act: StylesActivity, private val type: Int, private val what: String, private val pick: Pick? = null) : Screen() {
    override val lightOk: Boolean get() = true
    override val title get() = what
    override val tabs = listOf(TabDef("Tones", Ic.BELL), TabDef("Music", Ic.NOTE))
    private val lists = arrayOf(ListState(), ListState())
    private var tones: List<Tone>? = null
    private var music: List<Tone>? = null
    private var current: Uri? = if (pick != null) pick.existing else try { RingtoneManager.getActualDefaultRingtoneUri(act, type) } catch (_: Exception) { null }
    private var chosen: Tone? = null
    private var playing: Ringtone? = null
    private var note: String? = null

    override fun onShow() {
        if (tones == null) {
            Thread {
                val out = ArrayList<Tone>()
                if (pick != null && pick.showSilent) out.add(Tone("Silent", Uri.EMPTY))
                if (pick != null && pick.showDefault && type != RingtoneManager.TYPE_ALL) out.add(Tone("Default", RingtoneManager.getDefaultUri(type)))
                try {
                    val rm = RingtoneManager(act)
                    rm.setType(type)
                    rm.cursor?.use { c ->
                        while (c.moveToNext()) out.add(Tone(c.getString(RingtoneManager.TITLE_COLUMN_INDEX) ?: "", rm.getRingtoneUri(c.position)))
                    }
                } catch (_: Exception) {
                }
                tones = out
                shell.postInvalidate()
            }.start()
        }
    }

    override fun onTabChanged() {
        if (tab == 1 && music == null) {
            act.requestPerms(audioPerms()) { ok ->
                if (!ok) {
                    note = "Allow access to music to use your own songs."
                    music = emptyList()
                    shell.postInvalidate()
                    return@requestPerms
                }
                Thread {
                    val out = ArrayList<Tone>()
                    try {
                        act.contentResolver.query(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE),
                            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
                        )?.use { c ->
                            while (c.moveToNext() && out.size < 5000) {
                                out.add(Tone(c.getString(1) ?: "", ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0))))
                            }
                        }
                    } catch (_: Exception) {
                    }
                    music = out
                    shell.postInvalidate()
                }.start()
            }
        }
    }

    private fun items(): List<Tone>? = if (tab == 0) tones else music

    private fun area() = RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom)

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        val items = items()
        val msg = when {
            items == null -> "Loading..."
            items.isEmpty() -> note ?: if (tab == 0) "No tones found" else "No music found"
            else -> null
        }
        if (msg != null) {
            ui.text(c, msg, r.centerX(), ui.baselineFor(r.centerY(), 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER, maxW = r.width() - 40f * ui.sc)
            return
        }
        val cur = current
        lists[tab].draw(c, ui, r, items!!.size) { i ->
            val t = items[i]
            val isCur = (cur != null && cur == t.uri) || (pick != null && cur == null && t.silent)
            Row(t.title, iconKind = Ic.NOTE, right = if (chosen === t) "Selected" else if (isCur) "Current" else null)
        }
    }

    /** Picker mode: A previews, A again hands the tone back to the app that asked. */
    private fun choose(t: Tone) {
        stop()
        if (chosen === t) {
            act.setResult(android.app.Activity.RESULT_OK, android.content.Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, if (t.silent) null else t.uri))
            act.finish()
            return
        }
        chosen = t
        if (!t.silent) {
            try {
                playing = RingtoneManager.getRingtone(act, t.uri)
                playing?.play()
            } catch (_: Exception) {
            }
        }
        shell.toast(com.belleui.kit.Keys.hint("Press A again to use it"))
        shell.invalidate()
    }

    private fun apply(t: Tone) {
        if (pick != null) {
            choose(t)
            return
        }
        stop()
        if (!act.canWriteSettings()) {
            act.askWriteSettings()
            shell.toast("Allow \"Modify system settings\" for Styles, then pick again")
            return
        }
        try {
            RingtoneManager.setActualDefaultRingtoneUri(act, type, t.uri)
            current = t.uri
            shell.toast("$what set")
            val r = RingtoneManager.getRingtone(act, t.uri)
            playing = r
            r?.play()
        } catch (_: Exception) {
            shell.toast("Couldn't set that one")
        }
    }

    private fun stop() {
        try {
            playing?.stop()
        } catch (_: Exception) {
        }
        playing = null
    }

    override fun onAct(a: Act): Boolean {
        val items = items() ?: return false
        if (items.isEmpty()) return false
        if (a == Act.BACK) {
            stop()
            return false
        }
        if (a == Act.OK) {
            items.getOrNull(lists[tab].sel)?.let { apply(it) }
            return true
        }
        return lists[tab].act(a, items.size, lists[tab].visible(shell.ui, shell.contentBottom - shell.contentTop))
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val items = items() ?: return false
        val i = lists[tab].hit(shell.ui, area(), y, items.size)
        if (i < 0) return false
        lists[tab].sel = i
        apply(items[i])
        return true
    }

    override fun onScroll(dy: Float) {
        val items = items() ?: return
        lists[tab].scrollBy(if (dy > 0) 1 else -1, items.size, lists[tab].visible(shell.ui, shell.contentBottom - shell.contentTop))
    }

    override fun menu() = listOf(MenuItem("Stop preview") { stop() })
}

// ================================================================================================
// wallpapers
// ================================================================================================

/** Small bitmaps made off the main thread, cached by size in memory. */
class Thumbs {
    private val mem = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val busy = HashSet<String>()
    private val none = HashSet<String>()

    @Synchronized
    private fun claim(k: String) = busy.add(k)

    fun get(key: String, onReady: () -> Unit, make: () -> Bitmap?): Bitmap? {
        mem.get(key)?.let { return it }
        synchronized(this) { if (key in none) return null }
        if (!claim(key)) return null
        pool.execute {
            val b = try { make() } catch (_: Throwable) { null }
            synchronized(this) {
                busy.remove(key)
                if (b == null) none.add(key)
            }
            if (b != null) {
                mem.put(key, b)
                onReady()
            }
        }
        return null
    }
}

class Photo(val id: Long, val uri: Uri, val name: String)

class WallpaperScreen(private val act: StylesActivity) : Screen() {
    override val title get() = "Wallpaper"
    override val tabs = listOf(TabDef("Styles", Ic.IMAGES), TabDef("Photos", Ic.PHOTO))
    private val grids = arrayOf(GridState(3, 0.88f), GridState(3, 0.88f))
    private val thumbs = Thumbs()
    private var photos: List<Photo>? = null
    private var note: String? = null

    private fun area() = RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom)

    private fun screenSize(): Pair<Int, Int> {
        val dm = act.resources.displayMetrics
        return max(dm.widthPixels, dm.heightPixels) to min(dm.widthPixels, dm.heightPixels)
    }

    override fun onTabChanged() {
        if (tab == 1 && photos == null) {
            act.requestPerms(imagePerms()) { ok ->
                if (!ok) {
                    note = "Allow access to photos to use your own pictures."
                    photos = emptyList()
                    shell.postInvalidate()
                    return@requestPerms
                }
                Thread {
                    val out = ArrayList<Photo>()
                    try {
                        act.contentResolver.query(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
                            null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC",
                        )?.use { c ->
                            while (c.moveToNext() && out.size < 4000) {
                                out.add(Photo(c.getLong(0), ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)), c.getString(1) ?: ""))
                            }
                        }
                    } catch (_: Exception) {
                    }
                    photos = out
                    shell.postInvalidate()
                }.start()
            }
        }
    }

    private fun count() = if (tab == 0) Papers.names.size else photos?.size ?: 0

    private fun thumb(i: Int): Bitmap? =
        if (tab == 0) thumbs.get("p$i", { shell.postInvalidate() }) { Papers.render(i, 200, 150) }
        else thumbs.get("m${photos!![i].id}", { shell.postInvalidate() }) { photoThumb(photos!![i].uri) }

    private fun photoThumb(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return act.contentResolver.loadThumbnail(uri, Size(320, 240), null)
            } catch (_: Exception) {
            }
        }
        return loadPhoto(act, uri, 320)
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        if (tab == 1 && photos == null) {
            ui.text(c, "Looking for pictures...", r.centerX(), ui.baselineFor(r.centerY(), 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
            return
        }
        if (count() == 0) {
            ui.text(c, note ?: "No pictures found", r.centerX(), ui.baselineFor(r.centerY(), 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER, maxW = r.width() - 40f * ui.sc)
            return
        }
        val sc = ui.sc
        grids[tab].draw(c, ui, r, count()) { cv, cell, i, selected ->
            val pad = (if (selected) 8f else 12f) * sc
            val box = RectF(cell.left + pad, cell.top + pad, cell.right - pad, cell.bottom - 30f * sc)
            val b = thumb(i)
            if (b != null) {
                ui.drawBitmapCover(cv, b, box)
            } else {
                ui.p.style = Paint.Style.FILL
                ui.p.shader = null
                ui.p.color = 0xFF1E1E1E.toInt()
                cv.drawRect(box, ui.p)
            }
            val label = if (tab == 0) Papers.names[i] else photos!![i].name
            ui.text(cv, label, cell.centerX(), cell.bottom - 10f * sc, 15f * sc, Color.WHITE, Paint.Align.CENTER, cell.width() - 10f * sc)
        }
    }

    private fun open(i: Int) {
        val (w, h) = screenSize()
        if (tab == 0) {
            shell.push(PreviewScreen(act, Papers.names[i]) { Papers.render(i, w, h) })
        } else {
            val p = photos!![i]
            shell.push(PreviewScreen(act, p.name) { loadPhoto(act, p.uri, 2200) })
        }
    }

    override fun onAct(a: Act): Boolean {
        if (count() == 0) return false
        if (a == Act.OK) {
            open(grids[tab].sel.coerceIn(0, count() - 1))
            return true
        }
        return grids[tab].act(a, count(), area())
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val i = grids[tab].hit(area(), x, y, count())
        if (i < 0) return false
        grids[tab].sel = i
        open(i)
        return true
    }

    override fun onScroll(dy: Float) = grids[tab].scrollBy(if (dy > 0) 1 else -1, count(), area())
}

/** A picture decoded no larger than [maxDim] on its long side, turned upright by its EXIF tag. */
fun loadPhoto(ctx: android.content.Context, uri: Uri, maxDim: Int): Bitmap? {
    try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (max(o.outWidth, o.outHeight) / (s * 2) >= maxDim) s *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) } ?: return null
        val rot = try {
            ctx.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } ?: 1
        } catch (_: Exception) {
            1
        }
        val deg = when (rot) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        if (deg == 0f) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
    } catch (_: Throwable) {
        return null
    }
}

/** Shows how the picture will sit on the screen; D-pad moves it, L1/R1 zoom, A sets it. */
class PreviewScreen(private val act: StylesActivity, private val name: String, private val source: () -> Bitmap?) : Screen() {
    override val title get() = name
    private var bmp: Bitmap? = null
    private var failed = false
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val frame = RectF()
    private val dest = RectF()

    override fun onShow() {
        if (bmp == null && !failed) {
            Thread {
                bmp = source()
                if (bmp == null) failed = true
                shell.postInvalidate()
            }.start()
        }
    }

    override fun tools() = listOf(Tool(Ic.CHECK, { choose() }))

    override fun menu() = listOf(
        MenuItem("Set as Home screen") { apply(WallpaperManager.FLAG_SYSTEM) },
        MenuItem("Set as lock screen") { apply(WallpaperManager.FLAG_LOCK) },
        MenuItem("Set as both") { apply(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) },
        MenuItem("Reset position") { zoom = 1f; panX = 0f; panY = 0f; shell.invalidate() },
    )

    private fun choose() {
        if (bmp == null) return
        shell.showOverlay(ListDialog("Set wallpaper", listOf("Home screen", "Lock screen", "Home and lock screen"), 2) { i ->
            apply(intArrayOf(WallpaperManager.FLAG_SYSTEM, WallpaperManager.FLAG_LOCK, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)[i])
        })
    }

    private fun layout(r: RectF, b: Bitmap, ui: Ui) {
        val dm = act.resources.displayMetrics
        val aspect = max(dm.widthPixels, dm.heightPixels).toFloat() / min(dm.widthPixels, dm.heightPixels)
        val margin = 12f * ui.sc
        var w = r.width() - 2 * margin
        var h = w / aspect
        if (h > r.height() - 2 * margin) {
            h = r.height() - 2 * margin
            w = h * aspect
        }
        frame.set(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2)
        val scale = max(w / b.width, h / b.height) * zoom
        val iw = b.width * scale
        val ih = b.height * scale
        val maxX = max(0f, (iw - w) / 2)
        val maxY = max(0f, (ih - h) / 2)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        dest.set(frame.centerX() - iw / 2 + panX, frame.centerY() - ih / 2 + panY, frame.centerX() + iw / 2 + panX, frame.centerY() + ih / 2 + panY)
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        val b = bmp
        if (b == null) {
            ui.text(c, if (failed) "Couldn't open this picture" else "Loading...", r.centerX(), ui.baselineFor(r.centerY(), 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
            return
        }
        layout(r, b, ui)
        c.save()
        c.clipRect(frame)
        ui.p.shader = null
        ui.p.style = Paint.Style.FILL
        ui.p.isFilterBitmap = true
        c.drawBitmap(b, null, dest, ui.p)
        c.restore()
        ui.p.style = Paint.Style.STROKE
        ui.p.strokeWidth = 1.5f * ui.sc
        ui.p.color = ui.al(Color.WHITE, 120)
        c.drawRect(frame, ui.p)
        ui.p.style = Paint.Style.FILL
        ui.text(c, "D-pad move  -  L1/R1 zoom  -  A set", r.centerX(), r.bottom - 4f * ui.sc, 14f * ui.sc, ui.al(Color.WHITE, 170), Paint.Align.CENTER, face = ui.light)
    }

    override fun onAct(a: Act): Boolean {
        val step = 28f * shell.ui.sc
        when (a) {
            Act.LEFT -> panX += step
            Act.RIGHT -> panX -= step
            Act.UP -> panY += step
            Act.DOWN -> panY -= step
            Act.PREV -> zoom = max(1f, zoom - 0.25f)
            Act.NEXT -> zoom = min(4f, zoom + 0.25f)
            Act.OK -> choose()
            else -> return false
        }
        shell.invalidate()
        return true
    }

    override fun onTap(x: Float, y: Float): Boolean {
        choose()
        return true
    }

    private fun apply(flags: Int) {
        val b = bmp ?: return
        if (frame.width() <= 0f) return
        val dm = act.resources.displayMetrics
        val w = max(dm.widthPixels, dm.heightPixels)
        val h = min(dm.widthPixels, dm.heightPixels)
        val k = w / frame.width()
        val d = RectF((dest.left - frame.left) * k, (dest.top - frame.top) * k, (dest.right - frame.left) * k, (dest.bottom - frame.top) * k)
        shell.toast("Setting wallpaper...")
        Thread {
            var ok = true
            try {
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Canvas(out).drawBitmap(b, null, d, Paint(Paint.FILTER_BITMAP_FLAG))
                act.wallpapers.setBitmap(out, null, true, flags)
            } catch (_: Throwable) {
                ok = false
            }
            shell.post {
                shell.toast(if (ok) "Wallpaper set" else "Couldn't set the wallpaper")
                if (ok) act.onWallpaperSet()
            }
        }.start()
    }
}
