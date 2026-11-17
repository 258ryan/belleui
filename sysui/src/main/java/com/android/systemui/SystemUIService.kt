package com.android.systemui

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.belleui.sysui.Bar

/** Started by the system at boot under the stock name; brings up the Belle status bar. */
class SystemUIService : Service() {
    override fun onCreate() {
        super.onCreate()
        Bar.start(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
