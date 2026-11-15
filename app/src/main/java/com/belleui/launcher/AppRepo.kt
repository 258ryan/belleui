package com.belleui.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
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
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Color
import android.graphics.RadialGradient
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import kotlin.math.max
import kotlin.math.min

class AppEntry(
    val key: String,
    val label: String,
    val component: ComponentName,
    val icon: Bitmap,
    /** ApplicationInfo.category (API 26+), -1 if unknown. */
    val category: Int = -1,
    /** Extra intent data, for Symbian apps run by EKA21 (app uid, name, device). */
    val extras: android.os.Bundle? = null,
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
            .mapNotNull { ri ->
                try {
                    val cn = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
                    val label = ri.loadLabel(pm).toString()
                    // Belle apps show their own icon; the bundled pack only dresses other apps
                    val own = cn.packageName.startsWith("com.belleui.")
                    val custom = loadCustomIcon(cn.packageName) ?: if (own) loadOwnIcon(cn.packageName) else loadPackIcon(cn.packageName, label)
                    val icon = withShadow(custom ?: frameTile(ri.loadIcon(pm), label))
                    AppEntry(cn.flattenToString(), label, cn, icon, if (Build.VERSION.SDK_INT >= 26) ri.activityInfo.applicationInfo.category else -1)
                } catch (_: Throwable) {
                    null // one bad app must never take the whole list down
                }
            } + symbianApps()
    }

    /** Symbian apps installed in EKA21, listed like normal apps; each one opens straight into the app. */
    private fun symbianApps(): List<AppEntry> {
        val out = ArrayList<AppEntry>()
        val base = android.net.Uri.parse("content://$EKA_AUTH")
        // an unstable client: if the emulator process dies while we ask it, the launcher must not die with it
        val client = try {
            ctx.packageManager.getPackageInfo(EKA_PKG, 0)
            ctx.contentResolver.acquireUnstableContentProviderClient(base)
        } catch (_: Throwable) { null } ?: return out
        try {
            client.query(base.buildUpon().appendPath("apps").build(), null, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    try {
                        val uid = c.getLong(0)
                        val name = c.getString(1) ?: continue
                        val device = c.getString(2) ?: ""
                        val raw = try {
                            client.openFile(base.buildUpon().appendPath("icon").appendPath(uid.toString()).build(), "r")?.let { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { android.graphics.BitmapFactory.decodeStream(it) } }
                        } catch (_: Throwable) { null }
                        val icon = withShadow(loadCustomIcon("eka:$uid") ?: frameTile(raw?.let { android.graphics.drawable.BitmapDrawable(ctx.resources, it) } ?: ctx.packageManager.getApplicationIcon(EKA_PKG), name))
                        val ex = android.os.Bundle().apply {
                            putLong("appUid", uid)
                            putString("appName", name)
                            putString("appDeviceCode", device)
                            putBoolean("appIsShortcut", true)
                        }
                        out.add(AppEntry("eka:$uid", name, ComponentName(EKA_PKG, "$EKA_ACT"), icon, -1, ex))
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
            // EKA21 missing or its process died: no Symbian apps this time
        } finally {
            try { client.release() } catch (_: Throwable) { }
        }
        return out
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

    // ---- bundled N9ish icon pack (assets/icons) -------------------------------------

    private val packApk: Set<String> by lazy { ctx.assets.list("icons/apk")?.toSet() ?: emptySet() }
    private val packGeneric: Set<String> by lazy { ctx.assets.list("icons/generic")?.toSet() ?: emptySet() }

    /** First match wins. Matched against "<package> <label>", lower-case. */
    private val genericRules = listOf(
        "settings" to Regex("settings|setting"),
        "people" to Regex("contacts|people"),
        "phone" to Regex("dialer|phone|telecom"),
        "messaging" to Regex("messag|mms|sms|telegram|chat"),
        "camera" to Regex("camera"),
        "gallery" to Regex("gallery|photos|album"),
        "calendar" to Regex("calendar"),
        "clock" to Regex("clock|alarm"),
        "calculator" to Regex("calculator|calc"),
        "email" to Regex("mail"),
        "notes" to Regex("notes|keep|memo"),
        "map" to Regex("maps|navigation|navi"),
        "weather" to Regex("weather"),
        "store" to Regex("vending|store|market|fdroid"),
        "office" to Regex("office|docs|sheets|slides|word|excel|wps"),
        "mediaplayer" to Regex("music|player|video|vlc|podcast|radio"),
        "browser" to Regex("browser|chrome|firefox|brave|opera|duckduckgo|vivaldi"),
    )

    private fun loadAsset(path: String): Bitmap? = try {
        ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }

    /** The Belle apps wear the real icons of their Nokia counterparts (assets/icons/apk/<package>.png), if there is one. */
    private fun loadOwnIcon(pkg: String): Bitmap? {
        val key = pkg.replace("_", "__").replace(".", "_") + ".png"
        return if (key in packApk) loadAsset("icons/apk/$key") else null
    }

    private fun loadPackIcon(pkg: String, label: String): Bitmap? {
        // Package-specific art; the pack names files with '_' escaped as "__" and '.' as '_'.
        val key = pkg.replace("_", "__").replace(".", "_") + ".png"
        if (key in packApk) loadAsset("icons/apk/$key")?.let { return it }
        val hay = "$pkg $label".lowercase()
        for ((name, rx) in genericRules) {
            if (rx.containsMatchIn(hay) && "$name.png" in packGeneric) return loadAsset("icons/generic/$name.png")
        }
        return null
    }

    /** Belle IconFrame shape: superellipse with exponent 2.8. */
    private fun squircle(r: RectF, n: Double = 2.8): Path {
        val path = Path()
        val cx = r.centerX()
        val cy = r.centerY()
        val a = r.width() / 2.0
        val b = r.height() / 2.0
        val steps = 96
        for (i in 0..steps) {
            val t = 2.0 * Math.PI * i / steps
            val ct = Math.cos(t)
            val st = Math.sin(t)
            val x = (cx + a * Math.signum(ct) * Math.pow(Math.abs(ct), 2.0 / n)).toFloat()
            val y = (cy + b * Math.signum(st) * Math.pow(Math.abs(st), 2.0 / n)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    private fun render(d: Drawable, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(b))
        return b
    }

    /** Average colour of the clearly coloured, opaque pixels, or null for greys / transparent art. */
    private fun dominant(b: Bitmap): Int? {
        var r = 0L
        var g = 0L
        var bl = 0L
        var n = 0
        val step = max(1, b.width / 12)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val px = b.getPixel(x, y)
                val mx = max(Color.red(px), max(Color.green(px), Color.blue(px)))
                val mn = min(Color.red(px), min(Color.green(px), Color.blue(px)))
                if (Color.alpha(px) > 200 && mx - mn > 40) {
                    r += Color.red(px); g += Color.green(px); bl += Color.blue(px); n++
                }
                x += step
            }
            y += step
        }
        if (n < 4) return null
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }

    private fun hsv(color: Int, sMul: Float, vMul: Float): Int {
        val h = FloatArray(3)
        Color.colorToHSV(color, h)
        h[1] = (h[1] * sMul).coerceIn(0f, 1f)
        h[2] = (h[2] * vMul).coerceIn(0f, 1f)
        return Color.HSVToColor(h)
    }

    private fun isBlank(b: Bitmap): Boolean {
        val step = max(1, b.width / 10)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                if (Color.alpha(b.getPixel(x, y)) > 16) return false
                x += step
            }
            y += step
        }
        return true
    }

    /**
     * Tile for apps with no pack art. Adaptive icons are drawn the way every launcher does it:
     * background and foreground layers scaled so the 72/108 safe zone fills the tile, clipped to
     * the Belle superellipse. Colours are untouched. Legacy icons keep their own shape.
     */
    private fun frameTile(d: Drawable, label: String): Bitmap {
        try {
            if (Build.VERSION.SDK_INT >= 26 && d is AdaptiveIconDrawable) {
                val tile = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                val c = Canvas(tile)
                c.clipPath(squircle(RectF(0f, 0f, 96f, 96f)))
                d.background?.let {
                    it.setBounds(-16, -16, 112, 112)
                    it.draw(c)
                }
                d.foreground?.let {
                    it.setBounds(-16, -16, 112, 112)
                    it.draw(c)
                }
                if (!isBlank(tile)) return tile
            }
            val whole = render(d, 96, 96)
            if (!isBlank(whole)) return whole
        } catch (_: Throwable) {
        }
        return letterTile(label)
    }

    /** Last resort so an icon can never come out empty: Belle-blue frame with the app's initial. */
    private fun letterTile(label: String): Bitmap {
        val tile = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val c = Canvas(tile)
        val shape = squircle(RectF(0f, 0f, 96f, 96f))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, 96f, 0xFF4AA3EE.toInt(), 0xFF1080DD.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(shape, p)
        p.shader = null
        p.color = Color.WHITE
        p.textAlign = Paint.Align.CENTER
        p.textSize = 52f
        val fm = p.fontMetrics
        c.drawText(label.trim().take(1).uppercase().ifEmpty { "?" }, 48f, 48f - (fm.ascent + fm.descent) / 2f, p)
        return tile
    }

    /**
     * Puts a 96px tile on a 128px canvas with a soft drop shadow traced from the tile's own alpha,
     * so the shadow always matches the icon's real outline. No overlay is drawn on the art.
     */
    private fun withShadow(tile: Bitmap): Bitmap {
        val art = if (tile.width == 96 && tile.height == 96) tile else Bitmap.createScaledBitmap(tile, 96, 96, true)
        val out = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val blur = Paint()
        blur.maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
        val off = IntArray(2)
        val shadow = art.extractAlpha(blur, off)
        val sp = Paint(Paint.ANTI_ALIAS_FLAG)
        sp.color = 0xAA000000.toInt()
        c.drawBitmap(shadow, 16f + off[0], 10f + off[1] + 5f, sp)
        c.drawBitmap(art, 16f, 10f, Paint(Paint.FILTER_BITMAP_FLAG))
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

    /** 0 = all apps, 1 = games, 2 = media, 3 = everything else. */
    var catFilter: Int
        get() = prefs.getInt("catFilter", 0).coerceIn(0, 3)
        set(v) = prefs.edit().putInt("catFilter", v).apply()

    private fun matchesFilter(a: AppEntry): Boolean {
        val media = a.category == ApplicationInfo.CATEGORY_AUDIO || a.category == ApplicationInfo.CATEGORY_VIDEO || a.category == ApplicationInfo.CATEGORY_IMAGE
        val game = a.category == ApplicationInfo.CATEGORY_GAME
        return when (catFilter) {
            1 -> game
            2 -> media
            3 -> !game && !media
            else -> true
        }
    }

    /** Home widget slots: 0 clock, 1 date, 2 battery, 3 storage, 4 notifications. */
    /** Which of the home screens (0..2) the favourites and widgets below belong to. */
    var homePage = 0

    private fun pk(base: String) = if (homePage == 0) base else "$base$homePage"

    var widgetLeft: Int
        get() = prefs.getInt(pk("widgetLeft"), if (homePage == 0) 0 else if (homePage == 1) 1 else 4).coerceIn(0, 4)
        set(v) = prefs.edit().putInt(pk("widgetLeft"), v).apply()

    var widgetRight: Int
        get() = prefs.getInt(pk("widgetRight"), if (homePage == 0) 2 else if (homePage == 1) 3 else 2).coerceIn(0, 4)
        set(v) = prefs.edit().putInt(pk("widgetRight"), v).apply()

    /** One large Belle widget instead of the two small ones: -1 none, 0 flip clock, 1 Today, 2 music, 3 inbox, 4 toggles. */
    var widgetLarge: Int
        get() = prefs.getInt(pk("widgetLarge"), -1).coerceIn(-1, 5)
        set(v) = prefs.edit().putInt(pk("widgetLarge"), v).apply()

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
            .filter { matchesFilter(it) }
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

    // each of the three home screens has its own four shortcuts; the first starts with the usual phone apps
    fun favoriteEntries(): List<AppEntry> {
        if (homePage == 0 && !prefs.contains("favs") && apps.isNotEmpty()) putList("favs", defaultFavorites())
        val byKey = apps.associateBy { it.key }
        return getList(pk("favs")).mapNotNull { byKey[it] }.take(MAX_FAVS)
    }

    /** The Belle home screen layout (shortcuts and widgets on a grid), saved as text by the launcher. */
    var homeLayout: String?
        get() = prefs.getString("homeLayout", null)
        set(v) {
            prefs.edit().putString("homeLayout", v).apply()
        }

    fun isFavorite(key: String) = key in getList(pk("favs"))

    fun addFavorite(key: String): Boolean {
        val l = getList(pk("favs"))
        if (key in l) return true
        if (l.size >= MAX_FAVS) return false
        l.add(key)
        putList(pk("favs"), l)
        return true
    }

    fun removeFavorite(key: String) {
        val l = getList(pk("favs"))
        l.remove(key)
        putList(pk("favs"), l)
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

    /**
     * What you used lately: the system's own usage record (apps opened from anywhere, not only from here), newest
     * first, then the ones opened from this launcher. Needs "usage access", which the module grants; without it only
     * the launcher's own list is used.
     */
    fun recentEntries(): List<AppEntry> {
        val byKey = apps.associateBy { it.key }
        val own = getList("recents").mapNotNull { byKey[it] }
        return (usageRecents() + own).distinctBy { it.component.packageName }.take(MAX_RECENTS)
    }

    /** When each app was last in front, from the usage record (refreshed with the recents list). */
    @Volatile
    var lastUsed: Map<String, Long> = emptyMap()
        private set

    private fun usageRecents(): List<AppEntry> {
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val end = System.currentTimeMillis()
            val events = usm.queryEvents(end - 12L * 3600_000L, end) ?: return emptyList()
            val last = HashMap<String, Long>()
            val e = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) last[e.packageName] = e.timeStamp
            }
            val byPkg = apps.groupBy { it.component.packageName }
            lastUsed = last
            return last.entries
                .filter { it.value > prefs.getLong("dismissed_" + it.key, 0L) }
                .sortedByDescending { it.value }
                .mapNotNull { byPkg[it.key]?.firstOrNull() }
                .filter { it.component.packageName != ctx.packageName }
        } catch (_: Throwable) {
            return emptyList()
        }
    }

    fun pushRecent(key: String) {
        val l = getList("recents")
        l.remove(key)
        l.add(0, key)
        putList("recents", l.take(MAX_RECENTS))
    }

    fun removeRecent(key: String) {
        val l = getList("recents")
        l.remove(key)
        putList("recents", l)
        apps.firstOrNull { it.key == key }?.let { prefs.edit().putLong("dismissed_" + it.component.packageName, System.currentTimeMillis()).apply() }
    }

    fun clearRecents() {
        putList("recents", emptyList())
        val now = System.currentTimeMillis()
        val ed = prefs.edit()
        for (a in usageRecents()) ed.putLong("dismissed_" + a.component.packageName, now)
        ed.apply()
    }

    companion object {
        private const val EKA_AUTH = "com.github.eka2l1.dualstick.belle"
        private const val EKA_PKG = "com.github.eka2l1.dualstick"
        private const val EKA_ACT = "com.github.eka2l1.emu.EmulatorActivity"
        const val MAX_FAVS = 4
        const val HOME_PAGES = 1
        const val MAX_RECENTS = 12
    }
}
