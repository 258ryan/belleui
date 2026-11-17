package com.belleui.launcher

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.File
import java.util.concurrent.Executors

class Note(val key: String, val pkg: String, val title: String, val text: String, val time: Long)

/** History of notifications, kept after they are dismissed so the Hub can show everything that happened. */
object HubStore {
    val notes = ArrayList<Note>() // newest first
    var version = 0
    val intents = HashMap<String, PendingIntent>() // content intents of notifications that are still showing
    private var loaded = false
    private val io = Executors.newSingleThreadExecutor()
    private const val MAX = 200

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n")
    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'p' -> sb.append('|')
                    'n' -> sb.append('\n')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = File(ctx.filesDir, "hub_notes.txt")
            if (f.isFile) {
                for (line in f.readLines()) {
                    val p = line.split("|")
                    if (p.size >= 5) notes.add(Note(unesc(p[1]), unesc(p[2]), unesc(p[3]), unesc(p[4]), p[0].toLongOrNull() ?: 0L))
                }
                version++
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun add(ctx: Context, n: Note) {
        load(ctx)
        val same = notes.firstOrNull { it.key == n.key }
        if (same != null && same.title == n.title && same.text == n.text) return
        notes.add(0, n)
        while (notes.size > MAX) notes.removeAt(notes.size - 1)
        version++
        save(ctx)
    }

    @Synchronized
    fun remove(ctx: Context, n: Note) {
        notes.remove(n)
        version++
        save(ctx)
    }

    @Synchronized
    fun clear(ctx: Context) {
        notes.clear()
        version++
        save(ctx)
    }

    private fun save(ctx: Context) {
        val lines = notes.joinToString("\n") { "${it.time}|${esc(it.key)}|${esc(it.pkg)}|${esc(it.title)}|${esc(it.text)}" }
        val app = ctx.applicationContext
        io.execute {
            try {
                File(app.filesDir, "hub_notes.txt").writeText(lines)
            } catch (_: Exception) {
            }
        }
    }

    fun hasAccess(ctx: Context): Boolean =
        Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(ctx.packageName) == true
}

/** Receives every notification so the Hub can list it. Needs the user to allow notification access once. */
class HubListenerService : NotificationListenerService() {
    companion object {
        /** The connected listener, so the status provider can hand the live list to the System UI. */
        @Volatile
        var instance: HubListenerService? = null
    }

    private fun record(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == packageName) return
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS || n.category == Notification.CATEGORY_SERVICE) return
        val x = n.extras
        val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (x.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: x.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return
        n.contentIntent?.let { HubStore.intents[sbn.key] = it }
        HubStore.add(this, Note(sbn.key, sbn.packageName, title, text, sbn.postTime))
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onListenerConnected() {
        instance = this
        HubStore.load(this)
        try {
            activeNotifications?.forEach { record(it) }
        } catch (_: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = record(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        HubStore.intents.remove(sbn.key)
    }
}
