package com.belleui.recorder

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** A microphone that can be recorded from. */
class Input(val info: AudioDeviceInfo?, val name: String, val external: Boolean) {
    val bluetooth: Boolean get() = info?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
}

/**
 * The device has no microphone of its own, so recording is meant for a headset: wired and USB headsets
 * and Bluetooth headsets are found here, and a built-in microphone is offered only if there is one.
 */
object Mic {
    fun inputs(ctx: Context): List<Input> {
        if (Build.VERSION.SDK_INT < 23) return listOf(Input(null, "Headset or microphone", true))
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val out = ArrayList<Input>()
        for (d in am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            val name = d.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL }
            when (d.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> out.add(Input(d, "Wired headset" + (name?.let { " ($it)" } ?: ""), true))
                AudioDeviceInfo.TYPE_USB_HEADSET -> out.add(Input(d, "USB headset" + (name?.let { " ($it)" } ?: ""), true))
                AudioDeviceInfo.TYPE_USB_DEVICE -> out.add(Input(d, "USB microphone" + (name?.let { " ($it)" } ?: ""), true))
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> out.add(Input(d, "Bluetooth headset" + (name?.let { " ($it)" } ?: ""), true))
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> out.add(Input(d, "Built-in microphone", false))
            }
        }
        // headsets first
        return out.sortedByDescending { it.external }
    }
}
