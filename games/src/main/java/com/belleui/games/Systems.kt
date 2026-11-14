package com.belleui.games

import java.io.File

enum class Emu { RETROARCH, PPSSPP }

/**
 * A game system. [cores] are libretro core names (without "_libretro_android.so"), best first.
 * [thumb] is the folder name used by the libretro box-art server.
 */
class GameSystem(
    val id: String,
    val name: String,
    val short: String,
    val exts: Set<String>,
    val aliases: Set<String>,
    val cores: List<String>,
    val thumb: String,
    val color: Int,
    val emu: Emu = Emu.RETROARCH,
)

object Systems {
    val all = listOf(
        GameSystem("nes", "Nintendo Entertainment System", "NES", setOf("nes"), setOf("nes", "famicom", "fc"), listOf("fceumm", "nestopia", "mesen"), "Nintendo - Nintendo Entertainment System", 0xFFD0342C.toInt()),
        GameSystem("snes", "Super Nintendo", "SNES", setOf("sfc", "smc", "fig", "swc"), setOf("snes", "sfc", "superfamicom", "supernintendo"), listOf("snes9x", "snes9x2010", "bsnes"), "Nintendo - Super Nintendo Entertainment System", 0xFF6A4CB5.toInt()),
        GameSystem("gb", "Game Boy", "GB", setOf("gb"), setOf("gb", "gameboy"), listOf("gambatte", "mgba", "sameboy"), "Nintendo - Game Boy", 0xFF5C8A3A.toInt()),
        GameSystem("gbc", "Game Boy Color", "GBC", setOf("gbc"), setOf("gbc", "gameboycolor"), listOf("gambatte", "mgba", "sameboy"), "Nintendo - Game Boy Color", 0xFF2FA6B8.toInt()),
        GameSystem("gba", "Game Boy Advance", "GBA", setOf("gba"), setOf("gba", "gameboyadvance"), listOf("mgba", "vba_next", "gpsp"), "Nintendo - Game Boy Advance", 0xFF3E5CC8.toInt()),
        GameSystem("n64", "Nintendo 64", "N64", setOf("n64", "z64", "v64"), setOf("n64", "nintendo64"), listOf("mupen64plus_next", "parallel_n64"), "Nintendo - Nintendo 64", 0xFFE0A21A.toInt()),
        GameSystem("md", "Sega Mega Drive / Genesis", "MD", setOf("md", "gen", "smd"), setOf("md", "genesis", "megadrive", "segagenesis"), listOf("genesis_plus_gx", "picodrive"), "Sega - Mega Drive - Genesis", 0xFF1F5FA8.toInt()),
        GameSystem("sms", "Sega Master System", "SMS", setOf("sms"), setOf("sms", "mastersystem"), listOf("genesis_plus_gx", "picodrive", "smsplus"), "Sega - Master System - Mark III", 0xFFC8421F.toInt()),
        GameSystem("gg", "Sega Game Gear", "GG", setOf("gg"), setOf("gg", "gamegear"), listOf("genesis_plus_gx", "smsplus"), "Sega - Game Gear", 0xFF3A7DBF.toInt()),
        GameSystem("pce", "PC Engine / TurboGrafx", "PCE", setOf("pce"), setOf("pce", "pcengine", "tg16", "turbografx"), listOf("mednafen_pce_fast", "mednafen_pce"), "NEC - PC Engine - TurboGrafx 16", 0xFFE87A1A.toInt()),
        GameSystem("a26", "Atari 2600", "2600", setOf("a26"), setOf("atari2600", "a2600", "2600"), listOf("stella", "stella2014"), "Atari - 2600", 0xFFB5651D.toInt()),
        GameSystem("psx", "Sony PlayStation", "PS1", setOf("chd", "cue", "m3u"), setOf("psx", "ps1", "playstation", "psone"), listOf("pcsx_rearmed", "swanstation", "beetle_psx_hw"), "Sony - PlayStation", 0xFF58606B.toInt()),
        GameSystem("psp", "Sony PSP", "PSP", setOf("iso", "cso", "pbp"), setOf("psp", "playstationportable"), listOf("ppsspp"), "Sony - PlayStation Portable", 0xFF2B2F36.toInt(), Emu.PPSSPP),
        GameSystem("arcade", "Arcade", "ARC", emptySet(), setOf("arcade", "mame", "fbneo", "fba", "neogeo", "cps1", "cps2", "cps3"), listOf("fbneo", "mame2003_plus", "mame"), "MAME", 0xFFC2185B.toInt()),
    )

    /** Extensions that several systems use; the folder name decides. */
    private val AMBIGUOUS = setOf("iso", "cso", "pbp", "chd", "cue", "m3u", "bin", "zip", "7z")

    private val byExt: Map<String, GameSystem> = HashMap<String, GameSystem>().also { m ->
        for (s in all) for (e in s.exts) if (e !in AMBIGUOUS) m[e] = s
    }
    private val byAlias: Map<String, GameSystem> = HashMap<String, GameSystem>().also { m ->
        for (s in all) for (a in s.aliases) m[a] = s
    }

    fun byId(id: String) = all.firstOrNull { it.id == id }

    fun detect(f: File): GameSystem? {
        val ext = f.extension.lowercase()
        byExt[ext]?.let { return it }
        if (ext !in AMBIGUOUS) return null
        // look at the folder names above the file, nearest first
        var p: File? = f.parentFile
        var depth = 0
        while (p != null && depth < 4) {
            val key = p.name.lowercase().replace(Regex("[^a-z0-9]"), "")
            byAlias[key]?.let { return it }
            p = p.parentFile
            depth++
        }
        return when (ext) {
            "iso", "cso", "pbp" -> byId("psp")
            "chd", "cue", "m3u" -> byId("psx")
            else -> null
        }
    }
}
