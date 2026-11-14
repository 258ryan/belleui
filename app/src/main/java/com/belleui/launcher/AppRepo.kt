package com.belleui.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.MediaStore

class AppEntry(
    val key: String,
    val label: String,
    val component: ComponentName,
    val icon: Bitmap,
)

/** Installed apps plus the user's layout (order, hidden, favourites, recents). */
class AppRepo(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("belleui", Context.MODE_PRIVATE)

    @Volatile
    var apps: List<AppEntry> = emptyList()
        private set

    /** Blocking; call off the main thread. */
    fun reload() {
        val pm = ctx.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { ri ->
                val cn = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
                val custom = loadCustomIcon(cn.packageName)
                val icon = if (custom != null) bake(custom, full = true) else bake(toBitmap(ri.loadIcon(pm)), full = false)
                AppEntry(cn.flattenToString(), ri.loadLabel(pm).toString(), cn, icon)
            }
    }

    /**
     * Optional icon pack: drop `<package.name>.png` into
     * Android/data/com.belleui.launcher/files/icons/ and it replaces that app's icon
     * (used as a full tile, so supply glossy rounded-square artwork).
     */
    private fun loadCustomIcon(pkg: String): Bitmap? {
        val f = java.io.File(ctx.getExternalFilesDir("icons") ?: return null, "$pkg.png")
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    /** Skeuomorphic tile: soft drop shadow, glass plate, gloss, rim light. 128px canvas, plate 96px. */
    private fun bake(glyph: Bitmap, full: Boolean): Bitmap {
        val out = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val fp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val plate = RectF(16f, 10f, 112f, 106f)
        val r = 22f
        p.color = 0x99000000.toInt()
        p.maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL)
        c.save()
        c.translate(0f, 6f)
        c.drawRoundRect(plate, r, r, p)
        c.restore()
        p.maskFilter = null
        if (full) {
            c.save()
            c.clipPath(Path().apply { addRoundRect(plate, r, r, Path.Direction.CW) })
            c.drawBitmap(glyph, null, plate, fp)
            c.restore()
        } else {
            p.shader = LinearGradient(0f, plate.top, 0f, plate.bottom, 0xFF4A5461.toInt(), 0xFF141A22.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(plate, r, r, p)
            p.shader = null
            c.drawBitmap(glyph, null, RectF(30f, 24f, 98f, 92f), fp)
        }
        // gloss on the top half
        c.save()
        c.clipRect(plate.left, plate.top, plate.right, plate.centerY())
        p.shader = LinearGradient(0f, plate.top, 0f, plate.centerY(), 0x70FFFFFF, 0x06FFFFFF, Shader.TileMode.CLAMP)
        c.drawRoundRect(plate, r, r, p)
        p.shader = null
        c.restore()
        // rim light
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f
        p.color = 0x66FFFFFF
        c.drawRoundRect(RectF(plate.left + 0.75f, plate.top + 0.75f, plate.right - 0.75f, plate.bottom - 0.75f), r, r, p)
        return out
    }

    private fun toBitmap(d: Drawable): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return bmp
    }

    // ---- simple persisted lists ------------------------------------------------

    private fun getList(k: String): MutableList<String> =
        (prefs.getString(k, "") ?: "").split("|").filter { it.isNotEmpty() }.toMutableList()

    private fun putList(k: String, l: List<String>) {
        prefs.edit().putString(k, l.joinToString("|")).apply()
    }

    // ---- settings -------------------------------------------------------------

    var themeIndex: Int
        get() = prefs.getInt("theme", 0).coerceIn(0, Themes.all.size - 1)
        set(v) = prefs.edit().putInt("theme", v).apply()

    var swapPad: Boolean
        get() = prefs.getBoolean("swapPad", false)
        set(v) = prefs.edit().putBoolean("swapPad", v).apply()

    var showHidden: Boolean
        get() = prefs.getBoolean("showHidden", false)
        set(v) = prefs.edit().putBoolean("showHidden", v).apply()

    // ---- menu order / hidden --------------------------------------------------

    fun menuApps(includeHidden: Boolean): List<AppEntry> {
        val order = getList("order")
        val rank = HashMap<String, Int>()
        order.forEachIndexed { i, k -> rank[k] = i }
        val hidden = getList("hidden").toSet()
        return apps
            .filter { includeHidden || it.key !in hidden }
            .sortedWith(compareBy<AppEntry>({ rank[it.key] ?: Int.MAX_VALUE }, { it.label.lowercase() }))
    }

    /** Moves an item inside the visible list and persists the new order. */
    fun moveInOrder(visibleKeys: List<String>, from: Int, to: Int) {
        val l = visibleKeys.toMutableList()
        val k = l.removeAt(from)
        l.add(to, k)
        val rest = getList("order").filter { it !in l }
        putList("order", l + rest)
    }

    fun isHidden(key: String) = key in getList("hidden")

    fun setHidden(key: String, hide: Boolean) {
        val l = getList("hidden")
        l.remove(key)
        if (hide) l.add(key)
        putList("hidden", l)
    }

    // ---- favourites (home tiles, max 4) --------------------------------------

    fun favoriteEntries(): List<AppEntry> {
        if (!prefs.contains("favs") && apps.isNotEmpty()) putList("favs", defaultFavorites())
        val byKey = apps.associateBy { it.key }
        return getList("favs").mapNotNull { byKey[it] }.take(MAX_FAVS)
    }

    fun isFavorite(key: String) = key in getList("favs")

    fun addFavorite(key: String): Boolean {
        val l = getList("favs")
        if (key in l) return true
        if (l.size >= MAX_FAVS) return false
        l.add(key)
        putList("favs", l)
        return true
    }

    fun removeFavorite(key: String) {
        val l = getList("favs")
        l.remove(key)
        putList("favs", l)
    }

    private fun defaultFavorites(): List<String> {
        val pm = ctx.packageManager
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
        )
        val out = ArrayList<String>()
        for (i in intents) {
            val pkg = pm.resolveActivity(i, 0)?.activityInfo?.packageName ?: continue
            val app = apps.firstOrNull { it.component.packageName == pkg } ?: continue
            if (app.key !in out) out.add(app.key)
        }
        return out
    }

    // ---- recents ---------------------------------------------------------------

    fun recentEntries(): List<AppEntry> {
        val byKey = apps.associateBy { it.key }
        return getList("recents").mapNotNull { byKey[it] }
    }

    fun pushRecent(key: String) {
        val l = getList("recents")
        l.remove(key)
        l.add(0, key)
        putList("recents", l.take(MAX_RECENTS))
    }

    companion object {
        const val MAX_FAVS = 4
        const val MAX_RECENTS = 12
    }
}
