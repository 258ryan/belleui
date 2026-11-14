package com.belleui.keyboard

import android.content.Context

/**
 * Word suggestions: a built-in list of the 20,000 most common English words (commonest first), plus the
 * words you type, which come first once you have used them. Everything stays on the device.
 */
class Predictor(private val ctx: Context) {
    private var sorted: Array<String> = emptyArray()
    private var rank: IntArray = IntArray(0)
    private val learned = HashMap<String, Int>()
    private val prefs = ctx.getSharedPreferences("keyboard_words", Context.MODE_PRIVATE)

    @Volatile
    var ready = false
        private set

    fun load() {
        try {
            val words = ctx.assets.open("words.txt").bufferedReader().readLines().filter { it.isNotEmpty() }
            val order = words.indices.sortedBy { words[it] }
            sorted = Array(order.size) { words[order[it]] }
            rank = IntArray(order.size) { order[it] }
        } catch (_: Exception) {
        }
        reloadLearned()
        ready = true
    }

    /** Re-reads the learned words, e.g. after they were cleared from the keyboard's settings. */
    fun reloadLearned() {
        learned.clear()
        for (line in (prefs.getString("learned", "") ?: "").split("\n")) {
            val a = line.split("|")
            if (a.size == 2) a[1].toIntOrNull()?.let { learned[a[0]] = it }
        }
    }

    private fun lowerBound(p: String): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < p) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Up to [max] completions of [prefix] (never the prefix alone), best first. */
    fun suggest(prefix: String, max: Int = 3): List<String> {
        if (!ready || prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        val best = ArrayList<Pair<Int, String>>() // score, word; lower is better
        for ((w, n) in learned) if (w.length > p.length && w.startsWith(p)) best.add(-n to w)
        var i = lowerBound(p)
        // the first few hundred matches are enough: they are not in frequency order, so keep the best ranks
        var seen = 0
        val dict = ArrayList<Pair<Int, String>>()
        while (i < sorted.size && sorted[i].startsWith(p) && seen < 4000) {
            if (sorted[i].length > p.length) dict.add(rank[i] to sorted[i])
            i++
            seen++
        }
        dict.sortBy { it.first }
        for (d in dict) {
            if (best.none { it.second == d.second }) best.add(1_000_000 + d.first to d.second)
            if (best.size >= max * 3) break
        }
        best.sortBy { it.first }
        return best.take(max).map { it.second }
    }

    fun learn(word: String) {
        val w = word.lowercase()
        if (w.length < 2 || w.length > 24) return
        learned[w] = (learned[w] ?: 0) + 1
        if (learned.size > 3000) {
            // forget the words used least
            val keep = learned.entries.sortedByDescending { it.value }.take(2000)
            learned.clear()
            for (e in keep) learned[e.key] = e.value
        }
        save()
    }

    private fun save() {
        prefs.edit().putString("learned", learned.entries.joinToString("\n") { "${it.key}|${it.value}" }).apply()
    }

    fun forget() {
        learned.clear()
        save()
    }

    fun learnedCount() = learned.size
}
