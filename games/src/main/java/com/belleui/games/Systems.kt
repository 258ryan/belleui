package com.belleui.games

import java.io.File

enum class Emu { RETROARCH, PPSSPP }

/**
 * A game system. [cores] are libretro core names (without "_libretro_android.so"), best first.
 * [thumb] is the folder name used by the libretro box-art server. [exts] are the file types it
 * accepts anywhere; [folderExts] are extra types accepted when the file sits in the system's own
 * folder (EmulationStation-style cards); cartridge systems also accept .zip / .7z when [archive].
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
    val folderExts: Set<String> = emptySet(),
    val archive: Boolean = true,
) {
    fun accepts(ext: String, inOwnFolder: Boolean): Boolean =
        ext in exts || (inOwnFolder && ext in folderExts) || (archive && (ext == "zip" || ext == "7z") && inOwnFolder)
}

object Systems {
    private fun s(
        id: String, name: String, short: String, exts: String, aliases: String, cores: String, thumb: String, color: Long,
        emu: Emu = Emu.RETROARCH, folderExts: String = "", archive: Boolean = true,
    ) = GameSystem(
        id, name, short, exts.split(" ").filter { it.isNotEmpty() }.toSet(), aliases.split(" ").filter { it.isNotEmpty() }.toSet(),
        cores.split(" ").filter { it.isNotEmpty() }, thumb, color.toInt(), emu, folderExts.split(" ").filter { it.isNotEmpty() }.toSet(), archive,
    )

    val all: List<GameSystem> = listOf(
        s("nes", "Nintendo Entertainment System", "NES", "nes unf unif", "nes famicom fc nintendo nintendoentertainmentsystem", "fceumm nestopia mesen", "Nintendo - Nintendo Entertainment System", 0xFFD0342C),
        s("fds", "Famicom Disk System", "FDS", "fds qd", "fds famicomdisksystem nintendofamicomdisksystem", "fceumm mesen", "Nintendo - Family Computer Disk System", 0xFFB5392A),
        s("snes", "Super Nintendo", "SNES", "sfc smc fig swc bs", "snes sfc superfamicom supernintendo supernintendoentertainmentsystem", "snes9x snes9x2010 bsnes", "Nintendo - Super Nintendo Entertainment System", 0xFF6A4CB5),
        s("n64", "Nintendo 64", "N64", "n64 z64 v64 ndd", "n64 nintendo64", "mupen64plus_next parallel_n64", "Nintendo - Nintendo 64", 0xFFE0A21A),
        s("gb", "Game Boy", "GB", "gb", "gb gameboy", "gambatte mgba sameboy", "Nintendo - Game Boy", 0xFF5C8A3A),
        s("gbc", "Game Boy Color", "GBC", "gbc", "gbc gameboycolor", "gambatte mgba sameboy", "Nintendo - Game Boy Color", 0xFF2FA6B8),
        s("gba", "Game Boy Advance", "GBA", "gba", "gba gameboyadvance", "mgba vba_next gpsp", "Nintendo - Game Boy Advance", 0xFF3E5CC8),
        s("nds", "Nintendo DS", "NDS", "nds dsi", "nds nintendods ds", "melonds desmume", "Nintendo - Nintendo DS", 0xFF8AA1B5),
        s("vb", "Virtual Boy", "VB", "vb vboy", "vb virtualboy vboy", "mednafen_vb", "Nintendo - Virtual Boy", 0xFFB3262D),
        s("md", "Sega Mega Drive / Genesis", "MD", "md gen smd", "md genesis megadrive segagenesis segamegadrive", "genesis_plus_gx picodrive", "Sega - Mega Drive - Genesis", 0xFF1F5FA8, folderExts = "bin"),
        s("segacd", "Sega CD / Mega-CD", "SCD", "", "segacd megacd scd mcd", "genesis_plus_gx picodrive", "Sega - Mega-CD - Sega CD", 0xFF2A4F86, folderExts = "chd cue iso ccd m3u", archive = false),
        s("sega32x", "Sega 32X", "32X", "32x", "sega32x 32x s32x", "picodrive", "Sega - 32X", 0xFF4B6FA8),
        s("sms", "Sega Master System", "SMS", "sms", "sms mastersystem segamastersystem", "genesis_plus_gx picodrive smsplus", "Sega - Master System - Mark III", 0xFFC8421F),
        s("gg", "Sega Game Gear", "GG", "gg", "gg gamegear segagamegear", "genesis_plus_gx smsplus", "Sega - Game Gear", 0xFF3A7DBF),
        s("sg1000", "Sega SG-1000", "SG", "sg", "sg1000 sg segasg1000", "genesis_plus_gx", "Sega - SG-1000", 0xFF5A6FA0),
        s("saturn", "Sega Saturn", "SAT", "", "saturn segasaturn", "mednafen_saturn yabasanshiro kronos", "Sega - Saturn", 0xFF3B5A8C, folderExts = "chd cue iso ccd mdf m3u", archive = false),
        s("dc", "Sega Dreamcast", "DC", "cdi gdi", "dc dreamcast segadreamcast", "flycast", "Sega - Dreamcast", 0xFFE06A1F, folderExts = "chd cue m3u", archive = false),
        s("pce", "PC Engine / TurboGrafx-16", "PCE", "pce sgx", "pce pcengine tg16 turbografx turbografx16 supergrafx", "mednafen_pce_fast mednafen_pce", "NEC - PC Engine - TurboGrafx 16", 0xFFE87A1A),
        s("pcecd", "PC Engine CD", "PCECD", "", "pcecd tgcd pcenginecd turbografxcd", "mednafen_pce_fast mednafen_pce", "NEC - PC Engine CD - TurboGrafx-CD", 0xFFD2691E, folderExts = "chd cue iso m3u", archive = false),
        s("a26", "Atari 2600", "2600", "a26", "atari2600 a2600 2600", "stella stella2014", "Atari - 2600", 0xFFB5651D, folderExts = "bin"),
        s("a5200", "Atari 5200", "5200", "a52", "atari5200 a5200 5200", "atari800", "Atari - 5200", 0xFF9C5A22, folderExts = "bin"),
        s("a7800", "Atari 7800", "7800", "a78", "atari7800 a7800 7800", "prosystem", "Atari - 7800", 0xFF8A5528, folderExts = "bin"),
        s("lynx", "Atari Lynx", "LYNX", "lnx", "lynx atarilynx", "handy mednafen_lynx", "Atari - Lynx", 0xFFC47A2C),
        s("jaguar", "Atari Jaguar", "JAG", "j64 jag", "jaguar atarijaguar", "virtualjaguar", "Atari - Jaguar", 0xFFA6482B, folderExts = "rom"),
        s("ngp", "Neo Geo Pocket", "NGP", "ngp", "ngp neogeopocket", "mednafen_ngp", "SNK - Neo Geo Pocket", 0xFF7A8A99),
        s("ngpc", "Neo Geo Pocket Color", "NGPC", "ngc", "ngpc neogeopocketcolor", "mednafen_ngp", "SNK - Neo Geo Pocket Color", 0xFF4C9A8C),
        s("ws", "WonderSwan", "WS", "ws", "ws wswan wonderswan", "mednafen_wswan", "Bandai - WonderSwan", 0xFF5C6BC0),
        s("wsc", "WonderSwan Color", "WSC", "wsc", "wsc wswanc wonderswancolor", "mednafen_wswan", "Bandai - WonderSwan Color", 0xFF7E57C2),
        s("coleco", "ColecoVision", "COL", "col", "coleco colecovision", "bluemsx gearcoleco", "Coleco - ColecoVision", 0xFF8D6E63, folderExts = "rom bin"),
        s("intv", "Intellivision", "INTV", "int", "intv intellivision", "freeintv", "Mattel - Intellivision", 0xFF6D8A6A, folderExts = "bin rom"),
        s("msx", "MSX", "MSX", "mx1 mx2 dsk cas", "msx msx1 msx2", "bluemsx fmsx", "Microsoft - MSX", 0xFF4F8A8B, folderExts = "rom"),
        s("c64", "Commodore 64", "C64", "d64 t64 prg crt x64 d71 d81", "c64 commodore64", "vice_x64 vice_x64sc", "Commodore - 64", 0xFF7A6C9E, folderExts = "tap"),
        s("amiga", "Commodore Amiga", "AMI", "adf lha ipf hdf uae", "amiga commodoreamiga", "puae", "Commodore - Amiga", 0xFF8A5A9A),
        s("zxs", "ZX Spectrum", "ZXS", "tzx z80 sna", "zxspectrum zxs spectrum", "fuse", "Sinclair - ZX Spectrum", 0xFF3F6E99, folderExts = "tap"),
        s("psx", "Sony PlayStation", "PS1", "", "psx ps1 playstation psone sonyplaystation", "pcsx_rearmed swanstation mednafen_psx_hw", "Sony - PlayStation", 0xFF58606B, folderExts = "chd cue pbp m3u iso", archive = false),
        s("psp", "Sony PSP", "PSP", "", "psp playstationportable sonypsp", "ppsspp", "Sony - PlayStation Portable", 0xFF2B2F36, Emu.PPSSPP, folderExts = "iso cso pbp chd", archive = false),
        s("neogeo", "Neo Geo", "NEO", "", "neogeo", "fbneo mame2003_plus", "SNK - Neo Geo", 0xFFB71C1C, folderExts = ""),
        s("arcade", "Arcade", "ARC", "", "arcade mame mame2003 mame2010 fbneo fba fbalpha cps1 cps2 cps3 naomi atomiswave", "fbneo mame2003_plus mame", "MAME", 0xFFC2185B),
    )

    /** Types that more than one system uses; the folder decides, with a sensible default. */
    private val AMBIGUOUS = setOf("iso", "cso", "pbp", "chd", "cue", "m3u", "bin", "zip", "7z", "rom", "tap", "ccd")

    private val byExt: Map<String, GameSystem> = HashMap<String, GameSystem>().also { m ->
        for (sys in all) for (e in sys.exts) if (e !in AMBIGUOUS && e !in m) m[e] = sys
    }
    private val byAlias: MutableMap<String, GameSystem> = HashMap<String, GameSystem>().also { m ->
        for (sys in all) for (a in sys.aliases) m[a] = sys
    }

    fun byId(id: String) = all.firstOrNull { it.id == id }

    fun norm(name: String) = name.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** The system a folder holds, from its name (any capitalisation or punctuation). */
    fun byFolder(name: String): GameSystem? = byAlias[norm(name)]

    /** Teaches the scanner an extra folder name, e.g. from es_systems.xml. */
    fun addAlias(name: String, sys: GameSystem) {
        byAlias[norm(name)] = sys
    }

    /** Folders that hold art, saves and the like, never games. */
    val ignoredFolders = setOf(
        "bios", "media", "images", "image", "imgs", "videos", "video", "downloadedimages", "manuals", "manual", "covers", "snaps", "snap",
        "boxart", "boxarts", "marquees", "thumbnails", "cheats", "mods", "patches", "dlc", "saves", "savestates", "states", "screenshots",
        "docs", "music", "themes", "scripts", "namedboxarts", "namedsnaps", "namedtitles", "fanart", "wheel", "box2dfront", "mixrbv2",
    )

    /** Without a system folder, guess from the file alone. */
    fun detect(f: File): GameSystem? {
        val ext = f.extension.lowercase()
        byExt[ext]?.let { return it }
        if (ext !in AMBIGUOUS) return null
        var p: File? = f.parentFile
        var depth = 0
        while (p != null && depth < 4) {
            byFolder(p.name)?.let { return it }
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
