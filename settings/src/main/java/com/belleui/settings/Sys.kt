package com.belleui.settings

import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.net.Inet4Address

/** Reads of live system state, all without special permissions. */
object Sys {
    fun fmtSize(b: Long): String = when {
        b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
        b >= 1_000_000 -> "%.0f MB".format(b / 1e6)
        b >= 1_000 -> "%.0f kB".format(b / 1e3)
        else -> "$b B"
    }

    class Vol(val label: String, val path: String) {
        val total: Long get() = try { StatFs(path).totalBytes } catch (_: Exception) { 0L }
        val free: Long get() = try { StatFs(path).availableBytes } catch (_: Exception) { 0L }
    }

    fun volumes(ctx: Context): List<Vol> {
        val out = ArrayList<Vol>()
        out.add(Vol("Phone memory", Environment.getDataDirectory().path))
        try {
            val dirs = ctx.getExternalFilesDirs(null)
            for (i in 1 until dirs.size) {
                val d = dirs[i] ?: continue
                val root = File(d.path.substringBefore("/Android/data"))
                if (root.isDirectory) out.add(Vol(if (i == 1) "Memory card" else "Memory card $i", root.path))
            }
        } catch (_: Exception) {
        }
        return out
    }

    class Battery(
        val pct: Int, val charging: Boolean, val plug: String, val health: String, val tempC: Float,
        val voltage: Float, val tech: String, val status: String,
    )

    fun battery(ctx: Context): Battery {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plug = when (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Battery"
        }
        val health = when (i?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheated"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            else -> "Unknown"
        }
        val status = when (st) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Unknown"
        }
        return Battery(
            if (level >= 0 && scale > 0) level * 100 / scale else 0,
            st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL,
            plug, health, (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f,
            (i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000f, i?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "-", status,
        )
    }

    fun currentMa(ctx: Context): Int? = try {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (ua == Int.MIN_VALUE || ua == 0) null else kotlin.math.abs(ua) / 1000
    } catch (_: Exception) {
        null
    }

    fun timeLeft(ctx: Context, charging: Boolean): String? {
        if (Build.VERSION.SDK_INT < 28 || !charging) return null
        return try {
            val ms = (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).computeChargeTimeRemaining()
            if (ms <= 0) null else "${ms / 3_600_000} h ${(ms / 60_000) % 60} min to full"
        } catch (_: Exception) {
            null
        }
    }

    fun powerSave(ctx: Context): Boolean = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode

    // ---- connectivity ---------------------------------------------------------------------------

    private fun caps(ctx: Context): NetworkCapabilities? = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)
    } catch (_: Exception) {
        null
    }

    fun wifiConnected(ctx: Context) = caps(ctx)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    fun cellularConnected(ctx: Context) = caps(ctx)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

    fun wifiEnabled(ctx: Context): Boolean = try {
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).isWifiEnabled
    } catch (_: Exception) {
        false
    }

    fun ipAddress(ctx: Context): String? = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress
    } catch (_: Exception) {
        null
    }

    /** null = no Bluetooth on this device. */
    fun bluetooth(): Boolean? = try {
        BluetoothAdapter.getDefaultAdapter()?.isEnabled
    } catch (_: Exception) {
        null
    }

    fun airplane(ctx: Context): Boolean = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    fun nfc(ctx: Context): Boolean? = try {
        NfcAdapter.getDefaultAdapter(ctx)?.isEnabled
    } catch (_: Exception) {
        null
    }

    // ---- about ----------------------------------------------------------------------------------

    fun memory(ctx: Context): Pair<Long, Long> {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.totalMem to mi.availMem
    }

    fun uptime(): String {
        val s = SystemClock.elapsedRealtime() / 1000
        return if (s >= 86400) "${s / 86400} d ${(s / 3600) % 24} h" else "${s / 3600} h ${(s / 60) % 60} min"
    }
}
