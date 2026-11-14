package com.belleui.games

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.StrictMode
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Launches games in RetroArch and PPSSPP. */
class Emulators(private val ctx: Context, private val prefs: Prefs) {
    private val pm: PackageManager get() = ctx.packageManager

    val retroArchPackages = listOf("com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32")
    val ppssppPackages = listOf("org.ppsspp.ppsspp", "org.ppsspp.ppssppgold")

    private fun installed(pkg: String) = try {
        pm.getApplicationInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun installedRetroArch(): List<String> = retroArchPackages.filter { installed(it) }
    fun installedPpsspp(): List<String> = ppssppPackages.filter { installed(it) }

    /** The RetroArch build to use: the one chosen in settings if installed, else the first found. */
    fun retroArch(): String? {
        val chosen = prefs.retroarchPackage
        if (chosen != null && installed(chosen)) return chosen
        return installedRetroArch().firstOrNull()
    }

    fun ppsspp(): String? = installedPpsspp().firstOrNull()

    /** Which emulator will open [g]. */
    fun emulatorFor(g: Game): Emu =
        if (g.system.emu == Emu.PPSSPP && prefs.pspWithPpsspp && ppsspp() != null) Emu.PPSSPP else Emu.RETROARCH

    /** @return null on success, or a message for the user. */
    fun launch(g: Game): String? {
        return when (emulatorFor(g)) {
            Emu.PPSSPP -> launchPpsspp(g)
            Emu.RETROARCH -> launchRetroArch(g)
        }
    }

    /**
     * RetroArch's documented Android launch intent: content path, libretro core, config and data
     * folders are all passed as extras, so the game starts straight away with no menus.
     */
    private fun launchRetroArch(g: Game): String? {
        val pkg = retroArch() ?: return "RetroArch is not installed"
        val ai: ApplicationInfo = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { return "RetroArch is not installed" }
        val core = prefs.core(g.system)
        val sd = Environment.getExternalStorageDirectory().path
        val intent = Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(pkg, "com.retroarch.browser.retroactivity.RetroActivityFuture"))
            .putExtra("ROM", g.path)
            .putExtra("LIBRETRO", "${ai.dataDir}/cores/${core}_libretro_android.so")
            .putExtra("CONFIGFILE", "$sd/Android/data/$pkg/files/retroarch.cfg")
            .putExtra("IME", "com.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME")
            .putExtra("DATADIR", ai.dataDir)
            .putExtra("APK", ai.sourceDir)
            .putExtra("SDCARD", sd)
            .putExtra("DOWNLOADS", "$sd/Download")
            .putExtra("SCREENSHOTS", "$sd/Pictures")
            .putExtra("BOWSER", "")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return try {
            ctx.startActivity(intent)
            null
        } catch (_: Exception) {
            "Couldn't start RetroArch. Is the ${core} core installed?"
        }
    }

    /** PPSSPP opens a game when it is given the file with ACTION_VIEW. */
    private fun launchPpsspp(g: Game): String? {
        val pkg = ppsspp() ?: return "PPSSPP is not installed"
        // PPSSPP wants a plain file:// URI; allow handing one to another app
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        val intent = Intent(Intent.ACTION_VIEW)
            .setComponent(ComponentName(pkg, "org.ppsspp.ppsspp.PpssppActivity"))
            .setDataAndType(Uri.fromFile(g.file), "application/octet-stream")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return try {
            ctx.startActivity(intent)
            null
        } catch (_: Exception) {
            "Couldn't start PPSSPP"
        }
    }

    fun openApp(pkg: String?): String? {
        if (pkg == null) return "Not installed"
        val i = pm.getLaunchIntentForPackage(pkg) ?: return "Not installed"
        return try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (_: Exception) {
            "Couldn't open it"
        }
    }
}

/**
 * Talks to a running RetroArch over its UDP network-command interface (port 55355).
 * It must be switched on in RetroArch: Settings > Network > Network Commands.
 */
object RetroArchControl {
    private const val PORT = 55355

    /** Sends [command]; returns the reply, "" if it was sent but nothing came back, or null on failure. */
    fun send(command: String, wantReply: Boolean = false): String? {
        return try {
            DatagramSocket().use { s ->
                s.soTimeout = 500
                val data = command.toByteArray()
                s.send(DatagramPacket(data, data.size, InetAddress.getByName("127.0.0.1"), PORT))
                if (!wantReply) return ""
                val buf = ByteArray(512)
                val p = DatagramPacket(buf, buf.size)
                try {
                    s.receive(p)
                    String(p.data, 0, p.length).trim()
                } catch (_: SocketTimeoutException) {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** "Playing: <game>", "Idle", or null if RetroArch isn't answering. */
    fun status(): String? {
        val r = send("GET_STATUS", wantReply = true) ?: return null
        // reply looks like: GET_STATUS PLAYING <system>,<content>,crc32=...   or GET_STATUS CONTENTLESS
        val parts = r.split(" ", limit = 3)
        return when {
            parts.size >= 3 && parts[1].equals("PLAYING", true) -> "Playing: " + parts[2].split(",").getOrElse(1) { parts[2] }
            parts.size >= 2 && parts[1].equals("PAUSED", true) -> "Paused" + if (parts.size >= 3) ": " + parts[2].split(",").getOrElse(1) { parts[2] } else ""
            else -> "Idle"
        }
    }
}
