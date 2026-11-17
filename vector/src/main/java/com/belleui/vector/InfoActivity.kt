package com.belleui.vector

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Says what the module is doing: which hooks attached in which processes, and a button to test the chooser. */
class InfoActivity : Activity() {
    private lateinit var report: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(36, 36, 36, 36)
        fun text(t: String, size: Float, color: Int = Color.WHITE): TextView {
            val v = TextView(this)
            v.text = t
            v.textSize = size
            v.setTextColor(color)
            v.setPadding(0, 8, 0, 8)
            col.addView(v)
            return v
        }
        text("Belle Chooser", 22f, 0xFF5CB4F8.toInt())
        text(
            "A Vector (Xposed) module: Belle look for the Open with and Share lists, the recent-apps key opens the Belle launcher's Recent tab, " +
                "and the volume pop-up, power menu and Status menu are Belle too.\n\n" +
                "Turn it on in Vector (Modules), tick System Framework and System UI as scope (and Android System if listed), then reboot.", 15f,
        )
        text("What the hooks did since the last reboot:", 16f, 0xFF5CB4F8.toInt())
        report = text("", 14f, 0xFFCCCCCC.toInt())
        val test = Button(this)
        test.text = "Test the chooser"
        test.setOnClickListener {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Belle test"), "Share with"))
        }
        col.addView(test)
        val refresh = Button(this)
        refresh.text = "Refresh"
        refresh.setOnClickListener { fill() }
        col.addView(refresh)
        val s = ScrollView(this)
        s.setBackgroundColor(Color.BLACK)
        s.addView(col)
        setContentView(s)
        fill()
    }

    override fun onResume() {
        super.onResume()
        fill()
    }

    private fun fill() {
        val sb = StringBuilder()
        for ((label, process) in listOf("System process" to "android", "Chooser process" to "system:ui", "System UI" to "com.android.systemui")) {
            val v = try { Settings.Global.getString(contentResolver, Status.key(process)) } catch (_: Exception) { null }
            sb.append(label).append(": ")
            if (v.isNullOrBlank()) sb.append("nothing reported yet\n\n") else sb.append("\n").append(v).append("\n\n")
        }
        sb.append("If a line is missing, the module was not loaded in that process: check the scope in Vector, then reboot.")
        report.text = sb.toString()
    }
}
