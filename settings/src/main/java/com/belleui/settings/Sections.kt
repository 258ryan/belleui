package com.belleui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.LruCache
import android.view.WindowManager
import com.belleui.kit.Act
import com.belleui.kit.Belle
import com.belleui.kit.ListDialog
import com.belleui.kit.ListState
import com.belleui.kit.MenuItem
import com.belleui.kit.QueryDialog
import com.belleui.kit.Row
import com.belleui.kit.Screen
import com.belleui.kit.TabDef
import com.belleui.kit.Ic
import com.belleui.kit.Ui
import java.io.File
import java.text.DateFormat
import java.util.Date

private const val PANEL_WIFI = "android.settings.panel.action.WIFI"
private const val PANEL_INTERNET = "android.settings.panel.action.INTERNET_CONNECTIVITY"
private const val PANEL_VOLUME = "android.settings.panel.action.VOLUME"

private fun onOff(b: Boolean?) = when (b) { true -> "On"; false -> "Off"; null -> "Not available" }

// ================================================================================================
// root
// ================================================================================================

class RootScreen(act: SettingsActivity) : RowsScreen(act) {
    override val title get() = "Settings"
    override val tickMs get() = 3000L

    private var appCount = -1

    override fun onShow() {
        act.onResumeHook = { shell.postInvalidate() }
        if (appCount < 0) {
            Thread {
                appCount = act.packageManager.getInstalledApplications(0).count { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                shell.postInvalidate()
            }.start()
        }
    }

    override val cacheMs get() = 2500L

    override fun rows(): List<Row> {
        val g = shell.ui.gfx
        val vols = Sys.volumes(act)
        val v = vols.first()
        val b = Sys.battery(act)
        val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val media = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxOf(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        val wifi = if (Sys.wifiConnected(act)) "Wi-Fi connected" else if (Sys.wifiEnabled(act)) "Wi-Fi on" else "Wi-Fi off"
        val (ram, _) = Sys.memory(act)
        return listOf(
            Row("Storage", "${Sys.fmtSize(v.free)} free of ${Sys.fmtSize(v.total)}", icon = g.get("qgn_prop_fmgr_phone_memc_sub"), drill = true),
            Row("Apps", if (appCount >= 0) "$appCount installed" else "Counting...", icon = g.get("qgn_prop_set_apps_am"), drill = true),
            Row("Connectivity", "$wifi - Bluetooth ${onOff(Sys.bluetooth()).lowercase()}", icon = g.get("qgn_prop_set_conn_sub"), drill = true),
            Row("Battery", "${b.pct}% - ${b.status}", icon = g.get("qgn_indi_battery_strength"), drill = true),
            Row("Display", "Brightness, screen timeout", icon = g.get("qgn_prop_set_gene_phone"), drill = true),
            Row("Sound", "Media volume $media%", icon = g.get("qgn_prop_set_tones_sub"), drill = true),
            Row("Performance", "CPU speed and temperature (root)", icon = g.get("qgn_prop_set_gene_sub"), drill = true),
            Row("Styles", "Wallpaper, tones and sounds", icon = g.get("qgn_menu_theme"), drill = true),
            Row("About", "${Build.MODEL} - Android ${Build.VERSION.RELEASE} - ${Sys.fmtSize(ram)} RAM", icon = g.get("qgn_prop_cp_dev_about"), drill = true),
            Row("Date & time", "Automatic time, clock format", icon = g.get("qgn_prop_set_datim_sub"), drill = true),
            Row("Language & input", "Language, keyboard, rotation", icon = g.get("qgn_prop_set_gene_sub"), drill = true),
            Row("Location", Cfg.read(act, "secure", "location_mode").let { if (it == null || it == "0") "Off" else "On" }, icon = g.get("qgn_prop_set_apps_loc"), drill = true),
            Row("Security", "Screen lock, unknown apps, SELinux", icon = g.get("qgn_prop_set_device_sub"), drill = true),
            Row("Notifications & sounds", "Access, do not disturb, touch sounds", icon = g.get("qgn_prop_set_tones_sub"), drill = true),
            Row("Accessibility", "Font size, colour, animations", icon = g.get("qgn_prop_set_gene_sensor"), drill = true),
            Row("Default apps", "Home app and more", icon = g.get("qgn_prop_set_default_sub"), drill = true),
            Row("Accounts & sync", "Sync and accounts", icon = g.get("qgn_prop_set_conn_sub"), drill = true),
            Row("Developer options", "USB debugging, animations, touches", icon = g.get("qgn_prop_set_apps_usb"), drill = true),
            Row("Power & restart", "Restart, recovery, quick reboot (root)", icon = g.get("qgn_indi_battery_strength"), drill = true),
            Row("Android settings", "Everything else", icon = g.get("qgn_prop_set_apps_sub"), drill = true),
        )
    }

    override fun open(i: Int) {
        when (i) {
            0 -> shell.push(StorageScreen(act))
            1 -> shell.push(AppsScreen(act))
            2 -> shell.push(ConnectivityScreen(act))
            3 -> shell.push(BatteryScreen(act))
            4 -> shell.push(DisplayScreen(act))
            5 -> shell.push(SoundScreen(act))
            6 -> shell.push(PerformanceScreen(act))
            7 -> {
                val i = act.packageManager.getLaunchIntentForPackage("com.belleui.styles")
                if (i == null || !act.tryStart(i)) shell.toast("Styles isn't installed")
            }
            8 -> shell.push(AboutScreen(act))
            9 -> shell.push(DateTimeScreen(act))
            10 -> shell.push(InputScreen(act))
            11 -> shell.push(LocationScreen(act))
            12 -> shell.push(SecurityScreen(act))
            13 -> shell.push(NotificationsScreen(act))
            14 -> shell.push(AccessibilityScreen(act))
            15 -> shell.push(DefaultAppsScreen(act))
            16 -> shell.push(AccountsScreen(act))
            17 -> shell.push(DeveloperScreen(act))
            18 -> shell.push(PowerScreen(act))
            else -> if (!act.tryStart(Intent(Settings.ACTION_SETTINGS))) shell.toast("Not available")
        }
    }
}

// ================================================================================================
// storage
// ================================================================================================

class StorageScreen(act: SettingsActivity) : RowsScreen(act) {
    override val title get() = "Storage"
    private var media: Map<String, Long>? = null
    private var computing = false
    private var apkBytes = 0L

    private fun mediaPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasMedia() = mediaPerms().all { act.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onShow() {
        if (hasMedia()) compute()
    }

    private fun sum(ctx: Context, uri: Uri): Long {
        var t = 0L
        try {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { while (it.moveToNext()) t += it.getLong(0) }
        } catch (_: Exception) {
        }
        return t
    }

    private fun compute() {
        if (computing) return
        computing = true
        Thread {
            apkBytes = act.packageManager.getInstalledApplications(0).sumOf { File(it.sourceDir).length() }
            media = mapOf(
                "Pictures" to sum(act, MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
                "Videos" to sum(act, MediaStore.Video.Media.EXTERNAL_CONTENT_URI),
                "Audio" to sum(act, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI),
            )
            computing = false
            shell.postInvalidate()
        }.start()
    }

    override fun rows(): List<Row> {
        val g = shell.ui.gfx
        val out = ArrayList<Row>()
        vols = Sys.volumes(act)
        for (v in vols) {
            out.add(Row(v.label, "${Sys.fmtSize(v.free)} free of ${Sys.fmtSize(v.total)}", icon = g.get(if (v.label.startsWith("Memory card")) "qgn_prop_fmgr_mmc_sub" else "qgn_prop_fmgr_phone_memc_sub")))
        }
        val m = media
        if (m != null) {
            out.add(Row("Apps (installed packages)", right = Sys.fmtSize(apkBytes)))
            for ((k, v) in m) out.add(Row(k, right = Sys.fmtSize(v)))
        } else if (!hasMedia()) {
            out.add(Row("See what takes up space", "Allow access to media - press A", drill = true))
        } else {
            out.add(Row("Counting...", dim = true))
        }
        return out
    }

    private var vols: List<Sys.Vol> = emptyList()

    override fun bar(i: Int): Float? {
        if (i == 0) vols = Sys.volumes(act)
        if (i >= vols.size) return null
        val v = vols[i]
        return if (v.total > 0) (v.total - v.free).toFloat() / v.total else null
    }

    override fun open(i: Int) {
        if (media == null && !hasMedia() && i == vols.size) {
            act.requestPerms(mediaPerms()) { ok -> if (ok) compute() }
        }
    }

    override fun adjust(i: Int, dir: Int) {}
}

// ================================================================================================
// apps
// ================================================================================================

class AppsScreen(private val act: SettingsActivity) : Screen() {
    override val title get() = "Apps"
    override val tabs = listOf(TabDef("Installed", Ic.GRID), TabDef("System", Ic.TOOLS))

    private class App(val info: ApplicationInfo, val label: String)

    private var user: List<App> = emptyList()
    private var system: List<App> = emptyList()
    private val lists = arrayOf(ListState(), ListState())
    private val icons = LruCache<String, Bitmap>(80)
    private val versions = HashMap<String, String>()
    private val subtitles = HashMap<String, String>()

    override fun onShow() {
        if (user.isEmpty() && system.isEmpty()) {
            Thread {
                val pm = act.packageManager
                val all = pm.getInstalledApplications(0).map { App(it, pm.getApplicationLabel(it).toString()) }.sortedBy { it.label.lowercase() }
                val (sys, usr) = all.partition { it.info.flags and ApplicationInfo.FLAG_SYSTEM != 0 && it.info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0 }
                user = usr
                system = sys
                shell.postInvalidate()
            }.start()
        } else shell.postInvalidate()
    }

    private fun cur() = if (tab == 0) user else system

    private fun icon(a: App): Bitmap? {
        icons.get(a.info.packageName)?.let { return it }
        return try {
            val d = act.packageManager.getApplicationIcon(a.info)
            val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 64, 64)
            d.draw(Canvas(b))
            icons.put(a.info.packageName, b)
            b
        } catch (_: Exception) {
            null
        }
    }

    private fun version(a: App): String = versions.getOrPut(a.info.packageName) {
        try { "v" + (act.packageManager.getPackageInfo(a.info.packageName, 0).versionName ?: "?") } catch (_: Exception) { "" }
    }

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        val apps = cur()
        if (apps.isEmpty()) {
            ui.text(c, "Loading apps...", r.centerX(), ui.baselineFor(r.centerY(), 20f * ui.sc), 20f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER)
            return
        }
        lists[tab].draw(c, ui, r, apps.size) { i ->
            val a = apps[i]
            Row(a.label, subtitles.getOrPut(a.info.packageName) { version(a) + " - " + Sys.fmtSize(File(a.info.sourceDir).length()) }, icon = icon(a), drill = true)
        }
    }

    private fun rect() = RectF(shell.contentLeft, shell.contentTop, shell.contentRight, shell.contentBottom)

    override fun onAct(a: Act): Boolean {
        val apps = cur()
        if (apps.isEmpty()) return false
        if (a == Act.OK || a == Act.RIGHT) {
            shell.push(AppDetailScreen(act, apps[lists[tab].sel.coerceIn(0, apps.size - 1)].info.packageName))
            return true
        }
        return lists[tab].act(a, apps.size, lists[tab].visible(shell.ui, shell.contentBottom - shell.contentTop))
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val apps = cur()
        val i = lists[tab].hit(shell.ui, rect(), y, apps.size)
        if (i < 0) return false
        lists[tab].sel = i
        shell.push(AppDetailScreen(act, apps[i].info.packageName))
        return true
    }

    override fun onScroll(dy: Float) = lists[tab].scrollBy(if (dy > 0) 1 else -1, cur().size, lists[tab].visible(shell.ui, shell.contentBottom - shell.contentTop))
}

class AppDetailScreen(act: SettingsActivity, private val pkg: String) : RowsScreen(act) {
    private val pm = act.packageManager
    private val info: ApplicationInfo? = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { null }
    private val pi = try { pm.getPackageInfo(pkg, 0) } catch (_: Exception) { null }
    private val launch: Intent? = pm.getLaunchIntentForPackage(pkg)
    private val isSystem = info != null && info.flags and ApplicationInfo.FLAG_SYSTEM != 0 && info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
    override val title get() = info?.let { pm.getApplicationLabel(it).toString() } ?: pkg

    private fun fmt(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    private fun actions(): List<Pair<String, () -> Unit>> {
        val out = ArrayList<Pair<String, () -> Unit>>()
        if (launch != null) out.add("Open" to { act.tryStart(launch) })
        out.add("App info (storage, permissions, force stop)" to { act.tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))) })
        if (!isSystem) out.add("Uninstall" to {
            shell.showOverlay(QueryDialog("Uninstall ${title}?", "Uninstall", "Cancel") { act.tryStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) })
        })
        return out
    }

    override fun menu() = actions().map { (label, run) -> MenuItem(label.substringBefore(" (")) { run() } }

    override fun rows(): List<Row> {
        val a = actions()
        val out = ArrayList<Row>()
        for ((l, _) in a) out.add(Row(l.substringBefore(" ("), if (l.contains("(")) "storage, permissions, force stop" else null, drill = true))
        out.add(Row("Version", right = pi?.versionName ?: "?"))
        out.add(Row("Package", pkg))
        if (pi != null) {
            out.add(Row("Installed", right = fmt(pi.firstInstallTime)))
            out.add(Row("Updated", right = fmt(pi.lastUpdateTime)))
        }
        if (info != null) {
            out.add(Row("Target Android", right = "API ${info.targetSdkVersion}"))
            out.add(Row("Package size", right = Sys.fmtSize(File(info.sourceDir).length())))
        }
        return out
    }

    override fun open(i: Int) {
        actions().getOrNull(i)?.second?.invoke()
    }
}

// ================================================================================================
// connectivity
// ================================================================================================

class ConnectivityScreen(act: SettingsActivity) : RowsScreen(act) {
    override val title get() = "Connectivity"
    override val tickMs get() = 2000L

    override fun onShow() {
        act.onResumeHook = { shell.postInvalidate() }
    }

    override fun rows(): List<Row> {
        val g = shell.ui.gfx
        val wifiOn = Sys.wifiEnabled(act)
        val ip = Sys.ipAddress(act)
        val bt = Sys.bluetooth()
        val wifiSub = when {
            Sys.wifiConnected(act) -> "Connected" + (ip?.let { " - $it" } ?: "")
            wifiOn -> "On - not connected"
            else -> "Off"
        }
        return listOf(
            Row("Wi-Fi", wifiSub, icon = g.get(if (Sys.wifiConnected(act)) "qgn_prop_set_conn_wlan_conn" else if (wifiOn) "qgn_prop_set_conn_wlan" else "qgn_prop_set_conn_wlan_off"), drill = true),
            Row("Bluetooth", onOff(bt), icon = g.get(if (bt == true) "qgn_prop_set_apps_bt_on_shown" else "qgn_prop_set_apps_bt_off"), drill = true, dim = bt == null),
            Row("Airplane mode", onOff(Sys.airplane(act)), icon = g.get("qgn_prop_set_network_sub"), drill = true),
            Row("Mobile network", if (Sys.cellularConnected(act)) "Connected" else "Not connected", icon = g.get("qgn_prop_set_conn_data"), drill = true),
            Row("NFC", onOff(Sys.nfc(act)), icon = g.get("qgn_prop_set_apps_usb"), drill = true, dim = Sys.nfc(act) == null),
            Row("IP address", ip ?: "No network", icon = g.get("qgn_prop_set_conn_vpn")),
            Row("More network options", "Private DNS, hotspot, VPN, saved networks", icon = g.get("qgn_prop_set_apps_sub"), drill = true),
        )
    }

    /** With root, A switches the radio right here; otherwise (or if root says no) the usual settings page opens. */
    private fun toggle(kind: String, nowOn: Boolean?, fallback: () -> Boolean): Boolean {
        shell.toast("Switching...")
        Thread {
            val cmd = when (kind) {
                "airplane" -> "cmd connectivity airplane-mode ${if (nowOn == true) "disable" else "enable"}"
                else -> "svc $kind ${if (nowOn == true) "disable" else "enable"}"
            }
            val done = com.belleui.kit.Root.run(cmd) != null
            shell.post {
                if (done) shell.toast("Done") else if (!fallback()) shell.toast("Not available on this device")
                shell.invalidate()
            }
        }.start()
        return true
    }

    override fun open(i: Int) {
        val ok = when (i) {
            0 -> toggle("wifi", Sys.wifiEnabled(act)) { act.systemPanel(PANEL_WIFI, Settings.ACTION_WIFI_SETTINGS) }
            1 -> toggle("bluetooth", Sys.bluetooth()) { act.tryStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            2 -> toggle("airplane", Sys.airplane(act)) { act.tryStart(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) }
            3 -> act.systemPanel(PANEL_INTERNET, Settings.ACTION_WIRELESS_SETTINGS)
            4 -> act.tryStart(Intent(Settings.ACTION_NFC_SETTINGS))
            5 -> return
            else -> {
                shell.push(NetworkMoreScreen(act))
                return
            }
        }
        if (!ok) shell.toast("Not available on this device")
    }
}

// ================================================================================================
// battery
// ================================================================================================

class BatteryScreen(act: SettingsActivity) : RowsScreen(act) {
    override val title get() = "Battery"
    override val tickMs get() = 2000L
    override val cacheMs get() = 1500L

    override fun rows(): List<Row> {
        val b = Sys.battery(act)
        val out = ArrayList<Row>()
        out.add(Row("Charge", right = "${b.pct}%"))
        out.add(Row("Status", right = b.status))
        Sys.timeLeft(act, b.charging)?.let { out.add(Row("Time", right = it)) }
        out.add(Row("Power source", right = b.plug))
        Sys.currentMa(act)?.let { out.add(Row(if (b.charging) "Charging current" else "Current draw", right = "$it mA")) }
        out.add(Row("Health", right = b.health))
        out.add(Row("Temperature", right = "%.1f °C".format(b.tempC)))
        out.add(Row("Voltage", right = "%.2f V".format(b.voltage)))
        out.add(Row("Technology", right = b.tech))
        out.add(Row("Battery saver", if (Sys.powerSave(act)) "On" else "Off - press A to change", drill = true))
        out.add(Row("Battery usage by app", drill = true))
        return out
    }

    override fun bar(i: Int): Float? = if (i == 0) Sys.battery(act).pct / 100f else null

    override fun open(i: Int) {
        val n = rows().size
        val ok = when (i) {
            n - 2 -> act.tryStart(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            n - 1 -> act.tryStart(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
            else -> return
        }
        if (!ok) shell.toast("Not available on this device")
    }

    override fun adjust(i: Int, dir: Int) {}
}

// ================================================================================================
// display
// ================================================================================================

class DisplayScreen(act: SettingsActivity) : RowsScreen(act) {
    override val title get() = "Display"
    private val timeouts = listOf(15_000, 30_000, 60_000, 120_000, 300_000, 600_000)
    private val timeoutNames = listOf("15 seconds", "30 seconds", "1 minute", "2 minutes", "5 minutes", "10 minutes")

    override fun onShow() {
        act.onResumeHook = { shell.postInvalidate() }
    }

    private fun brightness(): Int = try { Settings.System.getInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS) } catch (_: Exception) { 128 }
    private fun timeout(): Int = try { Settings.System.getInt(act.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) } catch (_: Exception) { 60_000 }
    private fun auto(): Boolean = try { Settings.System.getInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC } catch (_: Exception) { false }
    private fun rotation(): Boolean = try { Settings.System.getInt(act.contentResolver, Settings.System.ACCELEROMETER_ROTATION) == 1 } catch (_: Exception) { false }

    override fun rows(): List<Row> {
        val canWrite = act.canWriteSettings()
        val out = ArrayList<Row>()
        if (!canWrite) out.add(Row("Allow changing settings", "Needed for brightness and timeout - press A", drill = true))
        out.add(Row("Brightness", right = "${brightness() * 100 / 255}%", dim = !canWrite))
        out.add(Row("Automatic brightness", check = auto(), dim = !canWrite))
        val t = timeouts.indexOfFirst { it >= timeout() }.let { if (it < 0) timeouts.size - 1 else it }
        out.add(Row("Screen timeout", timeoutNames[t], drill = true, dim = !canWrite))
        out.add(Row("Auto-rotate", check = rotation(), dim = !canWrite))
        out.add(Row("Wallpaper", "Choose a wallpaper", drill = true))
        out.add(Row("More in Android settings", "Font size, dark theme", drill = true))
        return out
    }

    private fun offset() = if (act.canWriteSettings()) 0 else 1

    override fun bar(i: Int): Float? = if (i == offset()) brightness() / 255f else null

    override fun adjust(i: Int, dir: Int) {
        if (!act.canWriteSettings()) {
            act.askWriteSettings()
            return
        }
        val v = (brightness() + dir * 13).coerceIn(5, 255)
        Settings.System.putInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
        shell.invalidate()
    }

    override fun open(i: Int) {
        if (!act.canWriteSettings() && i == 0) {
            act.askWriteSettings()
            return
        }
        when (i - offset()) {
            1 -> if (act.canWriteSettings()) {
                Settings.System.putInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, if (auto()) 0 else 1)
            } else act.askWriteSettings()
            2 -> if (act.canWriteSettings()) {
                val cur = timeouts.indexOfFirst { it >= timeout() }.let { if (it < 0) timeouts.size - 1 else it }
                shell.showOverlay(ListDialog("Screen timeout", timeoutNames, cur) { k -> Settings.System.putInt(act.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, timeouts[k]) })
            } else act.askWriteSettings()
            3 -> if (act.canWriteSettings()) Settings.System.putInt(act.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (rotation()) 0 else 1) else act.askWriteSettings()
            4 -> if (!act.tryStart(Intent(Intent.ACTION_SET_WALLPAPER))) shell.toast("Not available")
            5 -> if (!act.tryStart(Intent(Settings.ACTION_DISPLAY_SETTINGS))) shell.toast("Not available")
        }
    }
}

// ================================================================================================
// sound
// ================================================================================================

class SoundScreen(act: SettingsActivity) : RowsScreen(act) {
    override val title get() = "Sound"
    private val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val streams = listOf(
        "Media" to AudioManager.STREAM_MUSIC, "Ringtone" to AudioManager.STREAM_RING,
        "Alarm" to AudioManager.STREAM_ALARM, "Notifications" to AudioManager.STREAM_NOTIFICATION,
    )

    private fun pct(s: Int) = am.getStreamVolume(s) * 100 / maxOf(1, am.getStreamMaxVolume(s))

    override fun rows(): List<Row> {
        val out = ArrayList<Row>()
        for ((n, s) in streams) out.add(Row(n, right = "${pct(s)}%"))
        out.add(Row("Ringer mode", when (am.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "Silent"; AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"; else -> "Normal" }, drill = true))
        out.add(Row("More in Android settings", "Ringtones, vibration", drill = true))
        return out
    }

    override fun bar(i: Int): Float? = streams.getOrNull(i)?.let { pct(it.second) / 100f }

    override fun adjust(i: Int, dir: Int) {
        val s = streams.getOrNull(i)?.second ?: return
        val max = am.getStreamMaxVolume(s)
        try {
            am.setStreamVolume(s, (am.getStreamVolume(s) + dir).coerceIn(0, max), 0)
        } catch (_: SecurityException) {
            act.systemPanel(PANEL_VOLUME, Settings.ACTION_SOUND_SETTINGS)
        }
        shell.invalidate()
    }

    override fun open(i: Int) {
        when (i) {
            streams.size -> shell.showOverlay(ListDialog("Ringer mode", listOf("Normal", "Vibrate", "Silent"), when (am.ringerMode) { AudioManager.RINGER_MODE_SILENT -> 2; AudioManager.RINGER_MODE_VIBRATE -> 1; else -> 0 }) { k ->
                try {
                    am.ringerMode = intArrayOf(AudioManager.RINGER_MODE_NORMAL, AudioManager.RINGER_MODE_VIBRATE, AudioManager.RINGER_MODE_SILENT)[k]
                } catch (_: SecurityException) {
                    // silent needs "do not disturb" access
                    act.tryStart(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }
            })
            streams.size + 1 -> if (!act.tryStart(Intent(Settings.ACTION_SOUND_SETTINGS))) shell.toast("Not available")
        }
    }
}

// ================================================================================================
// about
// ================================================================================================

class AboutScreen(act: SettingsActivity) : RowsScreen(act) {
    override val title get() = "About"
    override val tickMs get() = 5000L

    override fun rows(): List<Row> {
        val (total, avail) = Sys.memory(act)
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = (act.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealMetrics(dm)
        return listOf(
            Row("Device", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"),
            Row("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
            Row("Security patch", if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else "-"),
            Row("Build", Build.DISPLAY),
            Row("Kernel", System.getProperty("os.version") ?: "-"),
            Row("Processor", "${Build.SUPPORTED_ABIS.firstOrNull() ?: "-"} - ${Runtime.getRuntime().availableProcessors()} cores - ${Build.HARDWARE}"),
            Row("Memory", "${Sys.fmtSize(avail)} free of ${Sys.fmtSize(total)}"),
            Row("Screen", "${dm.widthPixels} x ${dm.heightPixels} - ${dm.densityDpi} dpi - ${"%.0f".format(display.refreshRate)} Hz"),
            Row("Up time", Sys.uptime()),
            Row("System settings", "BelleUI 0.1"),
        )
    }
}


// ================================================================================================
// performance (root)
// ================================================================================================

/** CPU governor and speed. Reading is free; changing needs root, and it lasts until the next reboot. */
class PerformanceScreen(act: SettingsActivity) : RowsScreen(act) {
    override val title get() = "Performance"
    override val tickMs get() = 2000L
    override val cacheMs get() = 1500L
    private var rootState = 0 // 0 unknown, 1 yes, 2 no

    private fun read(path: String): String? = try { java.io.File(path).readText().trim().ifEmpty { null } } catch (_: Exception) { null }

    private val cpu0 = "/sys/devices/system/cpu/cpu0/cpufreq"

    private fun governors(): List<String> = read("$cpu0/scaling_available_governors")?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()

    private fun mhz(path: String): String? = read(path)?.toLongOrNull()?.let { "${it / 1000} MHz" }

    private fun temperature(): String? {
        for (i in 0..12) {
            val t = read("/sys/class/thermal/thermal_zone$i/temp")?.toLongOrNull() ?: continue
            val c = if (t > 1000) t / 1000.0 else t.toDouble()
            if (c in 10.0..120.0) return "%.0f C".format(c)
        }
        return null
    }

    override fun rows(): List<Row> {
        val g = shell.ui.gfx
        val gov = read("$cpu0/scaling_governor")
        return listOf(
            Row("Mode", gov?.let { presetOf(it) } ?: "Not available", icon = g.get("qgn_prop_set_gene_sub"), drill = true, dim = gov == null),
            Row("CPU governor", gov ?: "Not available", icon = g.get("qgn_prop_set_gene_phone"), drill = true, dim = gov == null),
            Row("CPU speed now", mhz("$cpu0/scaling_cur_freq") ?: "-", icon = g.get("qgn_prop_set_gene_sensor")),
            Row("CPU speed limit", mhz("$cpu0/scaling_max_freq") ?: "-", icon = g.get("qgn_prop_set_gene_sensor")),
            Row("Temperature", temperature() ?: "-", icon = g.get("qgn_indi_battery_strength")),
            Row("Root", when (rootState) { 1 -> "Available"; 2 -> "Not available - changes won't apply"; else -> "Press A to check" }, icon = g.get("qgn_prop_cp_dev_about")),
        )
    }

    private fun presetOf(gov: String) = when (gov) {
        "performance" -> "Performance"
        "powersave", "conservative" -> "Battery saver"
        else -> "Balanced"
    }

    private fun apply(gov: String) {
        shell.toast("Setting $gov...")
        Thread {
            val cmd = "for c in /sys/devices/system/cpu/cpu[0-9]*; do echo $gov > \$c/cpufreq/scaling_governor; done"
            val ok = com.belleui.kit.Root.run(cmd) != null
            rootState = if (ok) 1 else 2
            shell.post { shell.toast(if (ok) "Governor: $gov" else "Needs root - it said no"); refresh(true); shell.invalidate() }
        }.start()
    }

    override fun open(i: Int) {
        val gl = governors()
        when (i) {
            0 -> {
                val pick = mapOf(
                    "Battery saver" to listOf("powersave", "conservative"),
                    "Balanced" to listOf("schedutil", "interactive", "ondemand"),
                    "Performance" to listOf("performance"),
                )
                val names = pick.keys.toList()
                shell.showOverlay(com.belleui.kit.ListDialog("Mode", names, 1) { k ->
                    val want = pick[names[k]]!!.firstOrNull { it in gl }
                    if (want == null) shell.toast("This device has no such governor") else apply(want)
                })
            }
            1 -> if (gl.isEmpty()) shell.toast("Not available") else shell.showOverlay(com.belleui.kit.ListDialog("CPU governor", gl, gl.indexOf(read("$cpu0/scaling_governor"))) { k -> apply(gl[k]) })
            5 -> {
                Thread {
                    rootState = if (com.belleui.kit.Root.available()) 1 else 2
                    shell.postInvalidate()
                }.start()
            }
        }
    }
}
