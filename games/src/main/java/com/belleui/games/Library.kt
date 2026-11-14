package com.belleui.games

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors

private val TAG = Regex("[\\(\\[]([^\\)\\]]*)[\\)\\]]")
private val DISC = Regex("(?:disc|disk|cd)\\s*(\\d+)", RegexOption.IGNORE_CASE)

class Game(
    val path: String,
    val system: GameSystem,
    val size: Long,
    val modified: Long,
    val meta: Meta? = null,
    /** The system's own folder on the card, where gamelist.xml and art live. */
    val sysDir: String? = null,
) {
    val file: File get() = File(path)
    val rawName: String = File(path).nameWithoutExtension

    /** From gamelist.xml if given, else "Super Mario Land (World) [!]" -> "Super Mario Land" */
    val title: String = meta?.name?.takeIf { it.isNotBlank() }
        ?: rawName.replace(TAG, "").replace(Regex("\\s+"), " ").trim().ifEmpty { rawName }

    /** "USA, Rev 1" / "Disc 2" - what tells this file apart from other versions of the same title. */
    val label: String = TAG.findAll(rawName).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() && it != "!" }.joinToString(", ").ifEmpty { File(path).extension.uppercase().ifEmpty { "Default" } }

    val artKey: String = MD5(system.id + "/" + rawName)

    /** Every version and disc of this title, best first (includes this game). Set by the scanner. */
    var variants: List<Game> = listOf(this)

    /** The version the library shows; stats and favourites are kept on it. */
    val rep: Game get() = variants[0]

    val titleKey: String = system.id + "/" + (Systems.norm(title).ifEmpty { rawName.lowercase() })

    /** Sorts discs in order, then the preferred region first, then betas and prototypes last. */
    val rank: Int get() {
        val l = rawName.lowercase()
        val disc = DISC.find(rawName)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val region = when {
            "world" in l || "usa" in l || "(u)" in l -> 0
            "europe" in l || "(e)" in l -> 1
            "japan" in l || "(j)" in l -> 2
            else -> 3
        }
        val junk = if (Regex("beta|proto|demo|sample|unl|hack|pirate").containsMatchIn(l)) 20 else 0
        return disc * 100 + junk + region
    }
}

fun MD5(s: String): String {
    val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
    return d.joinToString("") { "%02x".format(it) }
}

class Stat(var launches: Int = 0, var lastPlayed: Long = 0, var playMs: Long = 0, var fav: Boolean = false)

/** Settings and per-game stats, kept in SharedPreferences. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("games", Context.MODE_PRIVATE)
    private val stats = HashMap<String, Stat>()

    init {
        for (line in (p.getString("stats", "") ?: "").split("\n")) {
            val a = line.split("|")
            if (a.size >= 5) {
                try {
                    stats[a[0]] = Stat(a[1].toInt(), a[2].toLong(), a[3].toLong(), a[4] == "1")
                } catch (_: Exception) {
                }
            }
        }
    }

    fun stat(path: String): Stat = stats.getOrPut(path) { Stat() }
    fun peek(path: String): Stat? = stats[path]

    fun save() {
        val sb = StringBuilder()
        for ((k, v) in stats) if (v.launches > 0 || v.fav) sb.append(k.replace("|", " ")).append('|').append(v.launches).append('|').append(v.lastPlayed).append('|').append(v.playMs).append('|').append(if (v.fav) 1 else 0).append('\n')
        p.edit().putString("stats", sb.toString()).apply()
    }

    var roots: List<String>
        get() = (p.getString("roots", null) ?: defaultRoots().joinToString("\n")).split("\n").filter { it.isNotBlank() }
        set(v) = p.edit().putString("roots", v.joinToString("\n")).apply()

    private fun defaultRoots(): List<String> {
        val base = android.os.Environment.getExternalStorageDirectory().path
        val found = try { Es.discoverRoots() } catch (_: Exception) { emptyList() }
        val fallback = listOf("$base/ROMs", "$base/roms", "$base/Games", "$base/RetroArch/roms").filter { File(it).isDirectory }
        return (found + fallback).distinct()
    }

    /** Looks for ROM folders (one subfolder per system) on phone storage and SD cards and adds them. */
    fun addDiscoveredRoots(): List<String> {
        val found = Es.discoverRoots()
        val cur = roots.toMutableList()
        val added = found.filter { it !in cur }
        if (added.isNotEmpty()) roots = cur + added
        return added
    }

    var downloadArt: Boolean
        get() = p.getBoolean("art", true)
        set(v) = p.edit().putBoolean("art", v).apply()

    /** PSP games: PPSSPP (true) or the RetroArch ppsspp core (false). */
    var pspWithPpsspp: Boolean
        get() = p.getBoolean("pspPpsspp", true)
        set(v) = p.edit().putBoolean("pspPpsspp", v).apply()

    var retroarchPackage: String?
        get() = p.getString("raPkg", null)
        set(v) = p.edit().putString("raPkg", v).apply()

    fun core(system: GameSystem): String = p.getString("core_" + system.id, null) ?: system.cores.first()
    fun setCore(system: GameSystem, core: String) = p.edit().putString("core_" + system.id, core).apply()
}

/** Finds games under the chosen folders, the way EmulationStation lays a card out. */
object Scanner {
    private const val MAX_FILES = 40000

    /** Blocking; call off the main thread. */
    fun scan(rootPaths: List<String>): List<Game> {
        val roots = rootPaths.map { File(it) }
        val out = ArrayList<Game>()
        val seen = HashSet<String>()
        val hidden = HashSet<String>() // discs that a .m3u playlist already represents

        val extra = ArrayList<GameSystem>()
        Es.loadSystems(roots, extra)

        fun walk(dir: File, sys: GameSystem?, sysDir: File?, depth: Int, meta: Map<String, Meta>) {
            if (depth > 6 || out.size >= MAX_FILES) return
            val files = try { dir.listFiles() } catch (_: SecurityException) { null } ?: return
            // playlists first, so their member discs can be hidden
            for (f in files) if (f.isFile) {
                if (f.extension.equals("m3u", true)) hidden.addAll(Es.m3uMembers(f))
                else if (f.extension.equals("cue", true) && f.length() < 65536) hidden.addAll(cueMembers(f))
            }
            for (f in files) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    val key = Systems.norm(f.name)
                    if (key in Systems.ignoredFolders) continue
                    val s = sys ?: Systems.byFolder(f.name)
                    if (sys == null && s != null) {
                        // entering a system folder: read its gamelist.xml once
                        walk(f, s, f, depth + 1, Es.loadGamelist(f, roots))
                    } else {
                        walk(f, s, sysDir, depth + 1, meta)
                    }
                } else {
                    val ext = f.extension.lowercase()
                    val s = if (sys != null) (if (sys.accepts(ext, true)) sys else null) else Systems.detect(f)
                    if (s == null) continue
                    if (!seen.add(f.path)) continue
                    out.add(Game(f.path, s, f.length(), f.lastModified(), meta[f.path], (sysDir ?: f.parentFile)?.path))
                }
            }
        }
        for (r in roots) {
            // the chosen folder may itself be a system folder (e.g. .../roms/gba) or hold many
            val own = Systems.byFolder(r.name)
            if (own != null) walk(r, own, r, 0, Es.loadGamelist(r, roots)) else walk(r, null, null, 0, emptyMap())
        }
        // one entry per title: regions, revisions and discs of a game are its versions
        val groups = LinkedHashMap<String, ArrayList<Game>>()
        for (g in out) if (g.path !in hidden) groups.getOrPut(g.titleKey) { ArrayList() }.add(g)
        val result = ArrayList<Game>(groups.size)
        for (list in groups.values) {
            val sorted = list.sortedWith(compareBy<Game>({ it.rank }, { it.label }))
            // prefer a version that has gamelist.xml data, unless it is a later disc
            for (g in sorted) g.variants = sorted
            result.add(sorted[0])
        }
        result.sortBy { it.title.lowercase() }
        return result
    }

    /** The track files a .cue sheet points at, so they don't show up as games of their own. */
    private fun cueMembers(f: File): List<String> = try {
        f.readLines().mapNotNull { Regex("FILE\\s+\"?([^\"]+)\"?\\s+\\w+", RegexOption.IGNORE_CASE).find(it.trim())?.groupValues?.get(1) }
            .map { File(f.parentFile, it).path }
    } catch (_: Exception) {
        emptyList()
    }
}

/** Turns an Android document-tree URI (from the folder picker) into a real path. */
fun treeToPath(uri: Uri): String? {
    val id = try { android.provider.DocumentsContract.getTreeDocumentId(uri) } catch (_: Exception) { return null }
    val parts = id.split(":", limit = 2)
    val vol = parts[0]
    val rel = parts.getOrElse(1) { "" }
    val base = if (vol == "primary") android.os.Environment.getExternalStorageDirectory().path else "/storage/$vol"
    return if (rel.isEmpty()) base else "$base/$rel"
}

private val main by lazy { Handler(Looper.getMainLooper()) }

/**
 * Box art: from the card's own scraped images, else the libretro thumbnail server (cached on disk).
 * Everything that touches files or the network runs on worker threads; [get] only does a memory lookup,
 * so scrolling a big library stays smooth.
 */
class Art(private val ctx: Context, private val prefs: Prefs) {
    private val mem = object : LruCache<String, Bitmap>(14 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val queue = object : java.util.concurrent.LinkedBlockingDeque<Runnable>() {
        // newest request first: what is on screen now matters more than what was scrolled past
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }
    private val pool = java.util.concurrent.ThreadPoolExecutor(2, 2, 0, java.util.concurrent.TimeUnit.MILLISECONDS, queue)
    private val busy = HashSet<String>()
    private val none = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val asked = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val dir = File(ctx.cacheDir, "art").also { it.mkdirs() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private val imageExts = listOf("png", "jpg", "jpeg", "webp")
    private val artDirs = listOf(
        "images", "media/box2dfront", "media/boxart", "media/covers", "media/images", "media/mixrbv2", "media/screenshots", "media/thumbnails",
        "downloaded_images", "boxart", "covers", "snap", "imgs", "Named_Boxarts", "media/Named_Boxarts",
    )

    /** Art that came with the card: gamelist.xml's image, or the usual scraper folders next to the ROMs. */
    private fun localArt(g: Game): File? {
        g.meta?.image?.let { p -> File(p).takeIf { it.isFile }?.let { return it } }
        val base = g.sysDir?.let { File(it) } ?: return null
        val names = listOf(g.rawName, g.title).distinct()
        for (n in names) for (d in artDirs) for (e in imageExts) {
            for (suffix in listOf("", "-image", "-thumb")) {
                val f = File(base, "$d/$n$suffix.$e")
                if (f.isFile) return f
            }
        }
        return null
    }

    /** Decoded no larger than needed: a tile is far smaller than a scraped screenshot. */
    private fun decode(path: String): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= 300 && o.outHeight / (s * 2) >= 300) s *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    /** The art if it is in memory; otherwise null, and it is fetched in the background and [onReady] runs. */
    fun get(g: Game, onReady: () -> Unit): Bitmap? {
        val k = g.artKey
        mem.get(k)?.let { return it }
        if (k in none) return null
        asked[k] = SystemClock.uptimeMillis()
        synchronized(this) {
            if (!busy.add(k)) return null
        }
        pool.execute {
            var bmp: Bitmap? = null
            var final = true
            try {
                if (SystemClock.uptimeMillis() - (asked[k] ?: 0L) > 1500) {
                    final = false // scrolled away before it was its turn
                } else {
                    bmp = load(g, k)
                }
            } catch (_: Throwable) {
            }
            if (bmp != null) mem.put(k, bmp) else if (final) none.add(k)
            synchronized(this) { busy.remove(k) }
            if (bmp != null) main.post(onReady)
        }
        return null
    }

    private fun load(g: Game, k: String): Bitmap? {
        localArt(g)?.let { f -> decode(f.path)?.let { return it } }
        val file = File(dir, "$k.png")
        if (file.exists()) decode(file.path)?.let { return it }
        if (!prefs.downloadArt || File(dir, "$k.miss").exists()) return null
        var ok = false
        try {
            // libretro's naming replaces these characters with underscores
            val name = g.rawName.replace(Regex("[&*/:`<>?\\\\|\"]"), "_")
            val url = URL("https://thumbnails.libretro.com/" + enc(g.system.thumb) + "/Named_Boxarts/" + enc(name) + ".png")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 8000
            if (c.responseCode == 200) {
                c.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                ok = true
            }
            c.disconnect()
        } catch (_: Exception) {
        }
        if (!ok) {
            File(dir, "$k.miss").createNewFile()
            return null
        }
        return decode(file.path)
    }
}
