package com.belleui.games

import android.os.Environment
import android.util.Xml
import java.io.File
import java.io.FileInputStream

/** Metadata for one game, from an EmulationStation gamelist.xml. */
class Meta(
    val name: String?,
    val desc: String?,
    val image: String?,
    val developer: String?,
    val publisher: String?,
    val genre: String?,
    val players: String?,
    val rating: Float?,
    val year: String?,
)

/** Reads EmulationStation's XML files. */
object Es {
    /** Swappable so the parsing can be tested off-device. */
    var newParser: () -> org.xmlpull.v1.XmlPullParser = { Xml.newPullParser() }

    private fun parser(f: File) = newParser().also {
        try {
            it.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true)
        } catch (_: Exception) {
        }
        it.setInput(FileInputStream(f), null)
    }

    /**
     * es_systems.xml: teaches the scanner the folder names and file types a card's own setup uses.
     * Unknown systems become extra entries so nothing on the card is skipped.
     */
    fun loadSystems(roots: List<File>, extra: MutableList<GameSystem>) {
        for (r in roots) {
            val candidates = listOf(
                File(r, "es_systems.xml"), File(r, ".emulationstation/es_systems.xml"), File(r, "emulationstation/es_systems.xml"),
                File(r, "EmulationStation/es_systems.xml"), File(r.parentFile ?: r, "es_systems.xml"), File(r.parentFile ?: r, ".emulationstation/es_systems.xml"),
            )
            val f = candidates.firstOrNull { it.isFile } ?: continue
            try {
                parseSystems(f, extra)
            } catch (_: Exception) {
            }
        }
    }

    private fun parseSystems(f: File, extra: MutableList<GameSystem>) {
        val p = parser(f)
        var name = ""
        var full = ""
        var path = ""
        var ext = ""
        var platform = ""
        var tag = ""
        var inSystem = false
        var ev = p.eventType
        while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (ev) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (tag == "system") {
                        inSystem = true
                        name = ""; full = ""; path = ""; ext = ""; platform = ""
                    }
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> if (inSystem) {
                    val t = p.text.trim()
                    when (tag) {
                        "name" -> name = t
                        "fullname" -> full = t
                        "path" -> path = t
                        "extension" -> ext = t
                        "platform" -> platform = t
                    }
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    if (p.name == "system" && inSystem) {
                        inSystem = false
                        registerSystem(name, full, path, ext, platform, extra)
                    }
                    tag = ""
                }
            }
            ev = p.next()
        }
    }

    private fun registerSystem(name: String, full: String, path: String, ext: String, platform: String, extra: MutableList<GameSystem>) {
        if (name.isEmpty()) return
        val known = Systems.byFolder(name) ?: Systems.byFolder(platform) ?: Systems.byFolder(full)
        val folder = path.trimEnd('/').substringAfterLast('/')
        if (known != null) {
            Systems.addAlias(name, known)
            if (folder.isNotEmpty()) Systems.addAlias(folder, known)
            return
        }
        // a system we have no table entry for: keep its files, launch through a core named like the system
        val exts = ext.lowercase().split(" ").map { it.trim().removePrefix(".") }.filter { it.isNotEmpty() && it != "zip" && it != "7z" }.toSet()
        val sys = GameSystem(Systems.norm(name), full.ifEmpty { name }, name.uppercase().take(5), exts, setOf(Systems.norm(name), Systems.norm(folder)), listOf(name), full.ifEmpty { name }, 0xFF607D8B.toInt(), folderExts = exts)
        extra.add(sys)
        Systems.addAlias(name, sys)
        if (folder.isNotEmpty()) Systems.addAlias(folder, sys)
    }

    /** gamelist.xml in a system folder: path -> metadata, keyed by the absolute ROM path. */
    fun loadGamelist(sysDir: File, roots: List<File>): Map<String, Meta> {
        val files = ArrayList<File>()
        files.add(File(sysDir, "gamelist.xml"))
        for (r in roots) {
            files.add(File(r, "gamelists/${sysDir.name}/gamelist.xml"))
            files.add(File(r, ".emulationstation/gamelists/${sysDir.name}/gamelist.xml"))
            files.add(File(r, "emulationstation/gamelists/${sysDir.name}/gamelist.xml"))
        }
        val out = HashMap<String, Meta>()
        for (f in files.filter { it.isFile }) {
            try {
                parseGamelist(f, sysDir, out)
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun resolve(base: File, p: String): String {
        val t = p.trim()
        return if (t.startsWith("/")) t else File(base, t.removePrefix("./")).path
    }

    private fun parseGamelist(f: File, sysDir: File, out: MutableMap<String, Meta>) {
        val p = parser(f)
        var tag = ""
        var inGame = false
        val v = HashMap<String, String>()
        var ev = p.eventType
        while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (ev) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (tag == "game") {
                        inGame = true
                        v.clear()
                    }
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> if (inGame && tag.isNotEmpty() && tag != "game") {
                    val t = p.text.trim()
                    if (t.isNotEmpty()) v[tag] = (v[tag]?.let { "$it " } ?: "") + t
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    if (p.name == "game" && inGame) {
                        inGame = false
                        val path = v["path"]
                        if (path != null) {
                            out[resolve(sysDir, path)] = Meta(
                                v["name"], v["desc"], v["image"]?.let { resolve(sysDir, it) } ?: v["thumbnail"]?.let { resolve(sysDir, it) },
                                v["developer"], v["publisher"], v["genre"], v["players"], v["rating"]?.toFloatOrNull(),
                                v["releasedate"]?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) },
                            )
                        }
                    }
                    tag = ""
                }
            }
            ev = p.next()
        }
    }

    /** m3u playlist: the disc files it pulls together (so only the playlist is listed as a game). */
    fun m3uMembers(f: File): List<String> = try {
        f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { resolve(f.parentFile ?: f, it) }
    } catch (_: Exception) {
        emptyList()
    }

    // ---- finding the ROM folders on cards -----------------------------------------------------------

    private fun isSystemFolder(d: File) = Systems.byFolder(d.name) != null

    private fun systemChildren(d: File): Int = try { d.listFiles()?.count { it.isDirectory && isSystemFolder(it) } ?: 0 } catch (_: Exception) { 0 }

    /**
     * Looks through the phone's storage and any SD cards for folders laid out as one subfolder per
     * system (roms/gba, roms/psx ...), two levels deep, and returns them.
     */
    fun discoverRoots(): List<String> {
        val volumes = ArrayList<File>()
        volumes.add(Environment.getExternalStorageDirectory())
        try {
            File("/storage").listFiles()?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" }?.let { volumes.addAll(it) }
        } catch (_: Exception) {
        }
        val found = LinkedHashSet<String>()
        for (v in volumes) {
            fun visit(d: File, depth: Int) {
                if (systemChildren(d) >= 2) {
                    found.add(d.path)
                    return
                }
                if (depth >= 2) return
                val subs = try { d.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "Android" } } catch (_: Exception) { null } ?: return
                for (s in subs) visit(s, depth + 1)
            }
            visit(v, 0)
        }
        return found.toList()
    }
}
