package com.belleui.settings

import android.app.KeyguardManager
import android.bluetooth.BluetoothAdapter
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.belleui.kit.ListDialog
import com.belleui.kit.QueryDialog
import com.belleui.kit.Root
import com.belleui.kit.Row

/** One line of a settings page: its title, what it says now, whether it is a switch, and what pressing it does. */
class Setting(
    val title: String,
    val sub: () -> String? = { null },
    val check: (() -> Boolean)? = null,
    val drill: Boolean = false,
    val onOpen: (ConfigScreen) -> Unit,
)

/** A page of [Setting]s. Android keeps these in the system tables; with root they are written there directly. */
abstract class ConfigScreen(act: SettingsActivity, private val name: String) : RowsScreen(act) {
    override val title get() = name
    override val cacheMs get() = 1500L
    private var cache: List<Setting> = emptyList()

    abstract fun items(): List<Setting>

    override fun onShow() {
        act.onResumeHook = { reload() }
    }

    fun reload() {
        refresh(true)
        shell.postInvalidate()
    }

    override fun rows(): List<Row> {
        cache = items()
        return cache.map { s ->
            val icon = shell.ui.gfx.get("qgn_prop_set_gene_sub")
            val sub = s.sub()
            val check = s.check?.invoke()
            // a setting with a value and no sub page is an E6 setting item: title with the value in a button
            if (check == null && !s.drill && sub != null) Row(s.title, right = sub, button = true)
            else Row(s.title, sub, icon = icon, check = check, drill = s.drill)
        }
    }

    override fun open(i: Int) {
        cache.getOrNull(i)?.onOpen?.invoke(this)
    }
}

/** Building blocks for the pages. */
object Cfg {
    fun read(act: Context, ns: String, key: String): String? {
        val cr: ContentResolver = act.contentResolver
        return try {
            when (ns) {
                "global" -> Settings.Global.getString(cr, key)
                "secure" -> Settings.Secure.getString(cr, key)
                else -> Settings.System.getString(cr, key)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun prop(key: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    } catch (_: Throwable) {
        ""
    }

    /** Writes through root ("settings put"), off the main thread, then redraws. */
    fun write(s: ConfigScreen, ns: String, key: String, value: String) {
        Thread {
            val ok = Root.run("settings put $ns $key $value") != null
            s.shell.post {
                if (!ok) s.shell.toast("Needs root")
                s.reload()
            }
        }.start()
    }

    fun run(s: ConfigScreen, cmd: String, done: String? = null) {
        Thread {
            val ok = Root.run(cmd) != null
            s.shell.post {
                s.shell.toast(if (ok) (done ?: "Done") else "Needs root")
                s.reload()
            }
        }.start()
    }

    fun toggle(act: SettingsActivity, title: String, ns: String, key: String, sub: String? = null, on: String = "1", off: String = "0") = Setting(
        title, sub = { sub }, check = { read(act, ns, key) == on },
        onOpen = { s -> write(s, ns, key, if (read(act, ns, key) == on) off else on) },
    )

    fun choice(act: SettingsActivity, title: String, ns: String, key: String, labels: List<String>, values: List<String>, def: String) = Setting(
        title,
        sub = { val v = read(act, ns, key) ?: def; labels.getOrNull(values.indexOf(v).takeIf { it >= 0 } ?: values.indexOf(def)) ?: v },
        drill = true,
        onOpen = { s ->
            val cur = values.indexOf(read(act, ns, key) ?: def)
            s.shell.showOverlay(ListDialog(title, labels, cur) { k -> write(s, ns, key, values[k]) })
        },
    )

    fun launch(act: SettingsActivity, title: String, sub: String?, vararg actions: String) = Setting(title, sub = { sub }, drill = true) { s ->
        val ok = actions.any { a -> act.tryStart(Intent(a)) }
        if (!ok) s.shell.toast("Not available on this device")
    }

    fun rootAction(title: String, sub: String?, confirm: String?, cmd: String, done: String? = null) = Setting(title, sub = { sub }, drill = true) { s ->
        if (confirm == null) run(s, cmd, done)
        else s.shell.showOverlay(QueryDialog(confirm, title, "Cancel") { run(s, cmd, done) })
    }
}

// ================================================================================================
// the pages
// ================================================================================================

class DateTimeScreen(private val a: SettingsActivity) : ConfigScreen(a, "Date & time") {
    override fun items() = listOf(
        Cfg.toggle(a, "Automatic date & time", "global", "auto_time", "Use the network time"),
        Cfg.toggle(a, "Automatic time zone", "global", "auto_time_zone"),
        Cfg.choice(a, "Clock format", "system", "time_12_24", listOf("Use locale default", "12-hour", "24-hour"), listOf("", "12", "24"), ""),
        Cfg.launch(a, "Set date, time and time zone", java.text.DateFormat.getDateTimeInstance().format(java.util.Date()), Settings.ACTION_DATE_SETTINGS),
    )
}

class LocationScreen(private val a: SettingsActivity) : ConfigScreen(a, "Location") {
    override fun items() = listOf(
        Cfg.choice(a, "Location", "secure", "location_mode", listOf("Off", "Device only", "Battery saving", "High accuracy"), listOf("0", "1", "2", "3"), "0"),
        Cfg.toggle(a, "Wi-Fi scanning", "global", "wifi_scan_always_enabled", "Let apps scan for Wi-Fi even when it is off"),
        Cfg.toggle(a, "Bluetooth scanning", "global", "ble_scan_always_enabled", "Let apps scan for Bluetooth devices"),
        Cfg.launch(a, "App location permissions", null, Settings.ACTION_PRIVACY_SETTINGS),
    )
}

class InputScreen(private val a: SettingsActivity) : ConfigScreen(a, "Language & input") {
    private fun imes() = try {
        (a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList
    } catch (_: Throwable) {
        emptyList()
    }

    override fun items(): List<Setting> {
        val pm = a.packageManager
        val ims = imes()
        val cur = Cfg.read(a, "secure", "default_input_method")
        val labels = ims.map { it.loadLabel(pm).toString() }
        return listOf(
            Cfg.launch(a, "Language", java.util.Locale.getDefault().displayName, Settings.ACTION_LOCALE_SETTINGS),
            Setting("Keyboard", sub = { ims.firstOrNull { it.id == cur }?.loadLabel(pm)?.toString() ?: "Default" }, drill = true) { s ->
                if (ims.isEmpty()) s.shell.toast("No keyboards enabled") else
                    s.shell.showOverlay(ListDialog("Keyboard", labels, ims.indexOfFirst { it.id == cur }) { k -> Cfg.run(s, "ime set ${ims[k].id}", "Keyboard changed") })
            },
            Cfg.launch(a, "Manage keyboards", null, Settings.ACTION_INPUT_METHOD_SETTINGS),
            Cfg.toggle(a, "Keyboard with a hardware keyboard", "secure", "show_ime_with_hard_keyboard", "Show the on-screen keyboard while a keyboard is connected"),
            Cfg.toggle(a, "Auto-rotate screen", "system", "accelerometer_rotation"),
            Cfg.choice(a, "Pointer speed", "system", "pointer_speed", listOf("Slowest", "Slow", "Default", "Fast", "Fastest"), listOf("-7", "-3", "0", "3", "7"), "0"),
            Cfg.launch(a, "Text-to-speech", null, "com.android.settings.TTS_SETTINGS"),
        )
    }
}

class SecurityScreen(private val a: SettingsActivity) : ConfigScreen(a, "Security") {
    private var enforce: String? = null

    override fun onShow() {
        super.onShow()
        Thread {
            enforce = Root.run("getenforce")
            shell.postInvalidate()
        }.start()
    }

    override fun items(): List<Setting> {
        val km = a.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return listOf(
            Setting("Screen lock", sub = { if (km.isDeviceSecure) "PIN, pattern or password" else "None" }, drill = true) { s ->
                if (!a.tryStart(Intent(Settings.ACTION_SECURITY_SETTINGS))) s.shell.toast("Not available")
            },
            Cfg.launch(a, "Install unknown apps", null, Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            Cfg.launch(a, "Device admin apps", null, Settings.ACTION_SECURITY_SETTINGS),
            Setting("Encryption", sub = { Cfg.prop("ro.crypto.state").ifBlank { "Unknown" } }) { },
            Setting("SELinux", sub = { enforce ?: "Needs root" }) { s ->
                s.shell.showOverlay(ListDialog("SELinux", listOf("Enforcing", "Permissive"), if (enforce == "Permissive") 1 else 0) { k ->
                    Cfg.run(s, "setenforce ${if (k == 0) 1 else 0}")
                    Thread {
                        Thread.sleep(600)
                        enforce = Root.run("getenforce")
                        s.shell.postInvalidate()
                    }.start()
                })
            },
            Setting("Security patch", sub = { Cfg.prop("ro.build.version.security_patch").ifBlank { "Unknown" } }) { },
        )
    }
}

class NotificationsScreen(private val a: SettingsActivity) : ConfigScreen(a, "Notifications & sounds") {
    override fun items() = listOf(
        Cfg.launch(a, "Notification access", "Apps that can read notifications", Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        Cfg.choice(a, "Do not disturb", "global", "zen_mode", listOf("Off", "Priority only", "Total silence", "Alarms only"), listOf("0", "1", "2", "3"), "0"),
        Cfg.toggle(a, "Notifications on the lock screen", "secure", "lock_screen_show_notifications"),
        Cfg.toggle(a, "Vibrate on touch", "system", "haptic_feedback_enabled"),
        Cfg.toggle(a, "Touch sounds", "system", "sound_effects_enabled"),
        Cfg.toggle(a, "Dial pad tones", "system", "dtmf_tone"),
        Cfg.toggle(a, "Charging sounds", "global", "charging_sounds_enabled"),
        Cfg.launch(a, "Notification settings for apps", null, "android.settings.NOTIFICATION_SETTINGS"),
    )
}

class AccessibilityScreen(private val a: SettingsActivity) : ConfigScreen(a, "Accessibility") {
    override fun items() = listOf(
        Cfg.choice(a, "Font size", "system", "font_scale", listOf("Small", "Default", "Large", "Largest"), listOf("0.85", "1.0", "1.15", "1.3"), "1.0"),
        Cfg.toggle(a, "Colour inversion", "secure", "accessibility_display_inversion_enabled"),
        Cfg.toggle(a, "Colour correction", "secure", "accessibility_display_daltonizer_enabled"),
        Cfg.toggle(a, "High contrast text", "secure", "high_text_contrast_enabled"),
        Cfg.choice(a, "Touch and hold delay", "secure", "long_press_timeout", listOf("Short", "Medium", "Long"), listOf("300", "400", "1000"), "400"),
        Setting("Remove animations", check = { listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale").all { Cfg.read(a, "global", it) == "0" || Cfg.read(a, "global", it) == "0.0" } }) { s ->
            val off = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale").all { Cfg.read(a, "global", it) == "0" || Cfg.read(a, "global", it) == "0.0" }
            Cfg.run(s, listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale").joinToString(" ; ") { "settings put global $it ${if (off) "1" else "0"}" })
        },
        Cfg.launch(a, "Accessibility services", null, Settings.ACTION_ACCESSIBILITY_SETTINGS),
    )
}

class DefaultAppsScreen(private val a: SettingsActivity) : ConfigScreen(a, "Default apps") {
    private fun homes() = a.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)

    override fun items(): List<Setting> {
        val pm = a.packageManager
        val list = homes()
        val cur = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        return listOf(
            Setting("Home app", sub = { list.firstOrNull { it.activityInfo.packageName == cur }?.loadLabel(pm)?.toString() ?: "Not set" }, drill = true) { s ->
                s.shell.showOverlay(ListDialog("Home app", list.map { it.loadLabel(pm).toString() }, list.indexOfFirst { it.activityInfo.packageName == cur }) { k ->
                    val ai = list[k].activityInfo
                    Cfg.run(s, "cmd package set-home-activity ${ai.packageName}/${ai.name}", "Home app changed")
                })
            },
            Cfg.launch(a, "Browser, phone, messages and more", null, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Cfg.launch(a, "Open by default", null, "android.settings.MANAGE_DOMAIN_URLS"),
        )
    }
}

class AccountsScreen(private val a: SettingsActivity) : ConfigScreen(a, "Accounts & sync") {
    override fun items() = listOf(
        Setting("Automatically sync data", check = { ContentResolver.getMasterSyncAutomatically() }) { s ->
            try {
                ContentResolver.setMasterSyncAutomatically(!ContentResolver.getMasterSyncAutomatically())
                s.reload()
            } catch (_: Throwable) {
                s.shell.toast("Not allowed")
            }
        },
        Cfg.launch(a, "Add an account", null, Settings.ACTION_ADD_ACCOUNT),
        Cfg.launch(a, "Accounts and sync settings", null, Settings.ACTION_SYNC_SETTINGS),
    )
}

class NetworkMoreScreen(private val a: SettingsActivity) : ConfigScreen(a, "More network options") {
    private val dnsLabels = listOf("Off", "Automatic", "dns.google", "one.one.one.one", "dns.adguard.com")

    override fun items(): List<Setting> {
        val mode = Cfg.read(a, "global", "private_dns_mode") ?: "opportunistic"
        val spec = Cfg.read(a, "global", "private_dns_specifier")
        return listOf(
            Setting("Private DNS", sub = { when (mode) { "off" -> "Off"; "hostname" -> spec ?: "Hostname"; else -> "Automatic" } }, drill = true) { s ->
                val cur = when (mode) { "off" -> 0; "hostname" -> dnsLabels.indexOf(spec).takeIf { it >= 0 } ?: -1; else -> 1 }
                s.shell.showOverlay(ListDialog("Private DNS", dnsLabels, cur) { k ->
                    when (k) {
                        0 -> Cfg.write(s, "global", "private_dns_mode", "off")
                        1 -> Cfg.write(s, "global", "private_dns_mode", "opportunistic")
                        else -> Cfg.run(s, "settings put global private_dns_specifier ${dnsLabels[k]} ; settings put global private_dns_mode hostname")
                    }
                })
            },
            Setting("Saved Wi-Fi networks", drill = true) { s ->
                Thread {
                    val out = Root.run("cmd wifi list-networks") ?: "Needs root"
                    s.shell.post { s.shell.showOverlay(QueryDialog(out.lines().take(10).joinToString("\n"), "OK", null) { }) }
                }.start()
            },
            Setting("Paired Bluetooth devices", drill = true) { s ->
                val names = try {
                    @Suppress("DEPRECATION")
                    BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.map { it.name ?: it.address } ?: emptyList()
                } catch (_: Throwable) {
                    emptyList()
                }
                if (names.isEmpty()) s.shell.toast("No paired devices") else s.shell.showOverlay(ListDialog("Paired devices", names, -1) { })
            },
            Cfg.launch(a, "Hotspot and tethering", null, "android.settings.TETHER_SETTINGS"),
            Cfg.launch(a, "VPN", null, Settings.ACTION_VPN_SETTINGS),
            Cfg.launch(a, "Data usage", null, Settings.ACTION_DATA_USAGE_SETTINGS),
        )
    }
}

class DeveloperScreen(private val a: SettingsActivity) : ConfigScreen(a, "Developer options") {
    private val scaleLabels = listOf("Off", "0.5x", "1x", "1.5x", "2x")
    private val scaleValues = listOf("0", "0.5", "1.0", "1.5", "2.0")

    override fun items() = listOf(
        Cfg.toggle(a, "Developer options", "global", "development_settings_enabled"),
        Cfg.toggle(a, "USB debugging", "global", "adb_enabled"),
        Cfg.toggle(a, "Wireless debugging", "global", "adb_wifi_enabled"),
        Cfg.choice(a, "Stay awake while charging", "global", "stay_on_while_plugged_in", listOf("Off", "Always (AC, USB, wireless)"), listOf("0", "7"), "0"),
        Cfg.toggle(a, "Show touches", "system", "show_touches"),
        Cfg.toggle(a, "Pointer location", "system", "pointer_location"),
        Cfg.toggle(a, "Do not keep activities", "global", "always_finish_activities"),
        Cfg.choice(a, "Window animation scale", "global", "window_animation_scale", scaleLabels, scaleValues, "1.0"),
        Cfg.choice(a, "Transition animation scale", "global", "transition_animation_scale", scaleLabels, scaleValues, "1.0"),
        Cfg.choice(a, "Animator duration scale", "global", "animator_duration_scale", scaleLabels, scaleValues, "1.0"),
        Cfg.launch(a, "Android developer settings", null, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
    )
}

class PowerScreen(private val a: SettingsActivity) : ConfigScreen(a, "Power & restart") {
    override fun items() = listOf(
        Cfg.rootAction("Restart", null, "Restart the phone?", "reboot"),
        Cfg.rootAction("Power off", null, "Switch the phone off?", "reboot -p"),
        Cfg.rootAction("Restart to recovery", null, "Restart into recovery?", "reboot recovery"),
        Cfg.rootAction("Restart to bootloader", null, "Restart into the bootloader?", "reboot bootloader"),
        Cfg.rootAction("Restart the system UI", "Without a full restart", "Restart the system UI?", "pkill -f com.android.systemui", "System UI restarted"),
        Cfg.rootAction("Quick reboot (software)", "Restarts Android only", "Restart Android without rebooting the phone?", "setprop ctl.restart zygote"),
    )
}
