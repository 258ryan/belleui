package com.belleui.games

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors

class Game(
    val path: String,
    val system: GameSystem,
    val size: Long,
    val modified: Long,
) {
    val file: File get() = File(path)
    val rawName: String = File(path).nameWithoutExtension

    /** "Super Mario Land (World) [!]" -> "Super Mario Land" */
    val title: String = rawName.replace(Regex("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]"), "").trim().ifEmpty { rawName }
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
        return listOf("$base/ROMs", "$base/roms", "$base/Games", "$base/RetroArch/roms").filter { File(it).isDirectory }
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

/** Finds games under the chosen folders. */
object Scanner {
    private const val MAX_FILES = 6000

    /** Blocking; call off the main thread. */
    fun scan(roots: List<String>): List<Game> {
        val out = ArrayList<Game>()
        val seen = HashSet<String>()
        fun walk(dir: File, depth: Int) {
            if (depth > 5 || out.size >= MAX_FILES) return
            val files = try { dir.listFiles() } catch (_: SecurityException) { null } ?: return
            for (f in files) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    walk(f, depth + 1)
                } else {
                    val sys = Systems.detect(f) ?: continue
                    // cue sheets and bins describe one disc: keep the .cue / .chd / .m3u, skip stray tracks
                    val path = f.path
                    if (seen.add(path)) out.add(Game(path, sys, f.length(), f.lastModified()))
                }
            }
        }
        for (r in roots) walk(File(r), 0)
        out.sortBy { it.title.lowercase() }
        return out
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

private val main = Handler(Looper.getMainLooper())

/** Box art from the libretro thumbnail server, cached on disk; null until it is available. */
class Art(private val ctx: Context, private val prefs: Prefs) {
    private val mem = LruCache<String, Bitmap>(12 * 1024 * 1024)
    private val pool = Executors.newFixedThreadPool(2)
    private val pending = HashSet<String>()
    private val dir = File(ctx.cacheDir, "art").also { it.mkdirs() }

    private fun key(g: Game): String {
        val d = MessageDigest.getInstance("MD5").digest((g.system.id + "/" + g.rawName).toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun get(g: Game, onReady: () -> Unit): Bitmap? {
        val k = key(g)
        mem.get(k)?.let { return it }
        val file = File(dir, "$k.png")
        if (file.exists()) {
            val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2 })
            if (bmp != null) {
                mem.put(k, bmp)
                return bmp
            }
        }
        if (!prefs.downloadArt || File(dir, "$k.miss").exists()) return null
        synchronized(this) {
            if (!pending.add(k)) return null
        }
        pool.execute {
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
            if (!ok) File(dir, "$k.miss").createNewFile()
            synchronized(this) { pending.remove(k) }
            if (ok) main.post(onReady)
        }
        return null
    }
}
