package com.belleui.vector

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** Explains how to switch the module on; Vector's manager does the rest. */
class InfoActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val t = TextView(this)
        t.setTextColor(Color.WHITE)
        t.textSize = 17f
        t.setPadding(40, 40, 40, 40)
        t.text = "Belle Chooser\n\n" +
            "A Vector (Xposed) module that gives Android's \"Open with\" and Share lists the Belle look: a dark panel, a blue highlight and gamepad focus.\n\n" +
            "To switch it on:\n" +
            "1. Open Vector, then Modules, and turn on Belle Chooser.\n" +
            "2. Tick \"System Framework\" and, if you see it, \"Android System\" as the scope.\n" +
            "3. Reboot.\n\n" +
            "If nothing changes, open the Vector log: it says \"BelleChooser\" when the hook ran."
        val s = ScrollView(this)
        s.setBackgroundColor(Color.BLACK)
        s.addView(t)
        setContentView(s)
    }
}
