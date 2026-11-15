package com.belleui.sysui

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Keeps the status bar's notification count current. */
class NotifListener : NotificationListenerService() {
    private fun count() {
        val n = try {
            activeNotifications.count { sbn ->
                sbn.isClearable && sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0
            }
        } catch (_: Throwable) {
            0
        }
        Bar.setNotifications(n)
    }

    override fun onListenerConnected() = count()
    override fun onNotificationPosted(sbn: StatusBarNotification) = count()
    override fun onNotificationRemoved(sbn: StatusBarNotification) = count()
}
