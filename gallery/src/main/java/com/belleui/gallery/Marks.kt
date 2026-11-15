package com.belleui.gallery

import android.content.Context

/**
 * What the user did to photos without touching the files: favourites, hidden, and "recently deleted" (kept 30 days,
 * then removed for real). Stored by media key.
 */
class Marks(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("gallery_marks", Context.MODE_PRIVATE)
    private val favs = HashSet<Long>()
    private val hidden = HashSet<Long>()
    private val trash = HashMap<Long, Long>() // key -> when it was deleted (ms)

    /** Bumped on every change so cached lists know to rebuild. */
    @Volatile var version = 0
        private set

    init {
        (prefs.getString("fav", "") ?: "").split(",").mapNotNullTo(favs) { it.toLongOrNull() }
        (prefs.getString("hidden", "") ?: "").split(",").mapNotNullTo(hidden) { it.toLongOrNull() }
        for (p in (prefs.getString("trash", "") ?: "").split(",")) {
            val kv = p.split(":")
            if (kv.size == 2) {
                val k = kv[0].toLongOrNull()
                val t = kv[1].toLongOrNull()
                if (k != null && t != null) trash[k] = t
            }
        }
    }

    private fun save() {
        prefs.edit()
            .putString("fav", favs.joinToString(","))
            .putString("hidden", hidden.joinToString(","))
            .putString("trash", trash.entries.joinToString(",") { "${it.key}:${it.value}" })
            .apply()
        version++
    }

    fun isFav(m: Media) = m.key in favs
    fun isHidden(m: Media) = m.key in hidden
    fun isTrashed(m: Media) = m.key in trash
    fun daysLeft(m: Media): Int {
        val t = trash[m.key] ?: return 0
        return (30 - (System.currentTimeMillis() - t) / 86_400_000L).toInt().coerceAtLeast(0)
    }

    fun toggleFav(m: Media) {
        if (!favs.remove(m.key)) favs.add(m.key)
        save()
    }

    fun hide(m: Media) { hidden.add(m.key); save() }
    fun unhide(m: Media) { hidden.remove(m.key); save() }
    fun delete(m: Media) { trash[m.key] = System.currentTimeMillis(); save() }
    fun recover(m: Media) { trash.remove(m.key); save() }

    /** Removes the file for real (best effort: Android may refuse files this app doesn't own). */
    fun deleteForever(m: Media): Boolean {
        val ok = try { ctx.contentResolver.delete(m.uri, null, null) > 0 } catch (_: Throwable) { false }
        if (ok) {
            trash.remove(m.key)
            favs.remove(m.key)
            hidden.remove(m.key)
            save()
        }
        return ok
    }

    /** Photos deleted more than 30 days ago go for real. */
    fun purge(items: List<Media>) {
        val limit = System.currentTimeMillis() - 30L * 86_400_000L
        val old = trash.filter { it.value < limit }.keys
        if (old.isEmpty()) return
        for (m in items) if (m.key in old) deleteForever(m)
    }
}
