package com.listenstats

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class Now(val artist: String, val title: String, val playing: Boolean)

object Bus {
    val tick = MutableStateFlow(0)
    val resume = MutableStateFlow(0)
    val now = MutableStateFlow<Now?>(null)
    fun bump() = tick.update { it + 1 }
}

object Prefs {
    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun get(c: Context, k: String): String = sp(c).getString(k, "") ?: ""
    fun put(c: Context, k: String, v: String) = sp(c).edit().putString(k, v.trim()).apply()
    fun bool(c: Context, k: String, def: Boolean): Boolean = sp(c).getBoolean(k, def)
    fun putBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()

    fun allowed(c: Context, pkg: String): Boolean {
        if (pkg == "com.spotify.music") return true
        if (pkg.startsWith("com.android.chrome") || pkg.startsWith("com.chrome.")) {
            return bool(c, "chrome", true)
        }
        return bool(c, "all", false)
    }
}

private val WS = Regex("\\s+")
private val FEAT = Regex("\\s*[\\(\\[]\\s*(feat|ft|with)\\.?[^\\)\\]]*[\\)\\]]", RegexOption.IGNORE_CASE)

fun clean(s: String): String = s.replace(WS, " ").trim()
fun cleanTitle(s: String): String = clean(s.replace(FEAT, ""))

fun isAd(artist: String, title: String): Boolean =
    artist.equals("Spotify", true) ||
        title.equals("Advertisement", true) ||
        title.equals("Реклама", true) ||
        title.equals("Spotify", true)

object Splitter {
    private val DEFAULT = listOf(
        "Tyler, The Creator", "Earth, Wind & Fire", "Crosby, Stills, Nash & Young",
        "Crosby, Stills & Nash", "Emerson, Lake & Palmer", "Peter, Paul and Mary"
    )

    /** Делит строку «A, B, C» на отдельных артистов, не ломая имена из списка исключений. */
    fun split(c: Context, full: String): List<String> {
        if (!Prefs.bool(c, "split", true)) return listOf(full)
        val ex = (DEFAULT + Prefs.get(c, "exc").lines()).map { it.trim() }.filter { it.isNotEmpty() }
        var s = full
        val keep = ArrayList<String>()
        for (e in ex) {
            val idx = s.indexOf(e, ignoreCase = true)
            if (idx >= 0) {
                keep.add(s.substring(idx, idx + e.length))
                s = s.replaceRange(idx, idx + e.length, "\u0001${keep.size - 1}\u0001")
            }
        }
        return s.split(Regex("\\s*[,;]\\s*")).map { part ->
            var q = part
            keep.forEachIndexed { i, k -> q = q.replace("\u0001${i}\u0001", k) }
            clean(q)
        }.filter { it.isNotEmpty() }.distinct().ifEmpty { listOf(full) }
    }
}
