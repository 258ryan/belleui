package com.belleui.sysui

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** The Belle System UI's own notification listener (registered as a system service, so no "allow access" step). */
class SysListener : NotificationListenerService() {
    override fun onListenerConnected() {
        com.belleui.vector.Hud.ownListener = this
        Bar.notesChanged()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Bar.posted(this, sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        Bar.notesChanged()
    }
}
