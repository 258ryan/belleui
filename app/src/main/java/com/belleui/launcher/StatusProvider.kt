package com.belleui.launcher

import android.app.Notification
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/**
 * The live notification list for the Belle Status menu. The launcher's listener is the one Android lets read
 * notifications, so the System UI (or the Chooser hooks running inside it) asks here. Only the System UI and the
 * system itself may ask.
 */
class StatusProvider : ContentProvider() {
    override fun onCreate() = true

    private fun allowed(): Boolean {
        val uid = Binder.getCallingUid()
        // the system, the launcher itself, root and the adb shell (for testing: root can read notifications anyway)
        if (uid == 1000 || uid == 0 || uid == 2000 || uid == Process.myUid()) return true
        return context?.packageManager?.getPackagesForUid(uid)?.contains("com.android.systemui") == true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(arrayOf("key", "pkg", "title", "text", "ongoing", "post", "progress", "clearable", "autocancel"))
        if (!allowed()) return c
        val list = try { HubListenerService.instance?.activeNotifications } catch (_: Throwable) { null } ?: return c
        for (sbn in list) {
            val n = sbn.notification ?: continue
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            val x = n.extras
            val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = (x.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
            if (title.isBlank() && text.isBlank()) continue
            val max = x.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
            val prog = if (max > 0) x.getInt(Notification.EXTRA_PROGRESS, 0).toFloat() / max else -1f
            val ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0
            c.addRow(arrayOf<Any>(
                sbn.key, sbn.packageName, title, text, if (ongoing) 1 else 0, sbn.postTime, prog,
                if (n.flags and Notification.FLAG_NO_CLEAR == 0) 1 else 0,
                if (n.flags and Notification.FLAG_AUTO_CANCEL != 0) 1 else 0,
            ))
        }
        return c
    }

    /** "cancel" dismisses one; "clear" dismisses all that can be; "open" returns the notification's own action to send. */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == "status") {
            // always answers, so the Status menu can say why the list is empty
            return Bundle().apply {
                putBoolean("allowed", allowed())
                putInt("uid", Binder.getCallingUid())
                putBoolean("connected", HubListenerService.instance != null)
                putInt("count", try { HubListenerService.instance?.activeNotifications?.size ?: -1 } catch (_: Throwable) { -2 })
            }
        }
        if (!allowed()) return null
        val svc = HubListenerService.instance ?: return null
        val out = Bundle()
        try {
            when (method) {
                "cancel" -> if (arg != null) svc.cancelNotification(arg)
                "clear" -> svc.cancelAllNotifications()
                "open" -> {
                    val sbn = svc.activeNotifications?.firstOrNull { it.key == arg }
                    sbn?.notification?.contentIntent?.let { out.putParcelable("intent", it) }
                }
            }
        } catch (_: Throwable) {
        }
        return out
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
