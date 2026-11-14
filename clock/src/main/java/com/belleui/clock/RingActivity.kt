package com.belleui.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.text.format.DateFormat
import android.view.WindowManager
import com.belleui.kit.Act
import com.belleui.kit.Belle
import com.belleui.kit.BelleActivity
import com.belleui.kit.BelleShell
import com.belleui.kit.Ic
import com.belleui.kit.Screen
import com.belleui.kit.Tool
import com.belleui.kit.Ui

/** Shown over the lock screen while an alarm rings: A stops, Y snoozes for 10 minutes. */
class RingActivity : BelleActivity() {
    private val closer = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun createRoot(shell: BelleShell): Screen = RingScreen(this, intent.getStringExtra("label") ?: "Alarm")

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        if (Build.VERSION.SDK_INT < 27) {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        super.onCreate(savedInstanceState)
        val f = IntentFilter(ACTION_CLOSE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(closer, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(closer, f)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(closer)
        } catch (_: IllegalArgumentException) {
        }
        super.onDestroy()
    }

    fun sendToService(action: String) {
        startService(Intent(this, AlarmService::class.java).setAction(action))
        finish()
    }

    companion object {
        const val ACTION_CLOSE = "com.belleui.clock.CLOSE_RING"
    }
}

class RingScreen(private val act: RingActivity, private val label: String) : Screen() {
    override val title get() = "Alarm"
    override val tickMs get() = 1000L

    override fun tools() = listOf(
        Tool(Ic.REFRESH, { act.sendToService(AlarmService.ACTION_SNOOZE) }),
        Tool(Ic.STOP, { act.sendToService(AlarmService.ACTION_STOP) }),
    )

    override fun draw(c: Canvas, ui: Ui, r: RectF) {
        val fmt = if (DateFormat.is24HourFormat(act)) "HH:mm" else "h:mm a"
        val time = DateFormat.format(fmt, System.currentTimeMillis()).toString()
        ui.text(c, time, r.centerX(), ui.baselineFor(r.centerY() - 40f * ui.sc, 88f * ui.sc, ui.light), 88f * ui.sc, align = Paint.Align.CENTER, face = ui.light)
        ui.text(c, label, r.centerX(), ui.baselineFor(r.centerY() + 30f * ui.sc, 26f * ui.sc), 26f * ui.sc, Belle.BLUE_LIGHT, Paint.Align.CENTER, maxW = r.width() - 40f * ui.sc)
        ui.text(c, "Y  Snooze 10 min        A  Stop", r.centerX(), ui.baselineFor(r.centerY() + 90f * ui.sc, 18f * ui.sc, ui.light), 18f * ui.sc, Belle.TEXT_MID, Paint.Align.CENTER, face = ui.light)
    }

    override fun onAct(a: Act): Boolean {
        when (a) {
            Act.OK, Act.BACK -> act.sendToService(AlarmService.ACTION_STOP)
            else -> return false
        }
        return true
    }

    override fun onTap(x: Float, y: Float): Boolean {
        act.sendToService(AlarmService.ACTION_STOP)
        return true
    }
}
