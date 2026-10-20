package com.listenstats

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HttpException(val code: Int) : RuntimeException("HTTP $code")

object Genres {
    private val exec = Executors.newSingleThreadExecutor()
    private val queued = AtomicBoolean(false)

    private var token = ""
    private var tokenExp = 0L

    private val BAD = setOf(
        "seen live", "favorites", "favorite", "favourites", "favourite", "male vocalists",
        "female vocalists", "american", "british", "usa", "uk", "canadian", "all", "spotify",
        "under 2000 listeners", "love", "awesome", "good", "best"
    )

    fun kick(ctx: Context) {
        if (!queued.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        exec.execute {
            queued.set(false)
            try { process(app) } catch (_: Exception) {}
        }
    }

    private fun process(ctx: Context) {
        val db = Db.get(ctx)
        db.syncArtists()
        for (name in db.pending()) {
            try {
                val g = lookup(ctx, name)
                db.setGenres(name, g.joinToString(","))
                Bus.bump()
            } catch (e: IOException) {
                return // нет сети или лимит запросов: попробуем позже
            } catch (e: Exception) {
                db.bumpAttempt(name)
            }
            Thread.sleep(1100)
        }
    }

    private fun lookup(ctx: Context, name: String): List<String> {
        val sid = Prefs.get(ctx, "sid")
        val sec = Prefs.get(ctx, "ssec")
        val lk = Prefs.get(ctx, "lfm")
        val sources = ArrayList<() -> List<String>>()
        if (sid.isNotBlank() && sec.isNotBlank()) sources.add { spotify(name, sid, sec) }
        sources.add { musicBrainz(name) }
        if (lk.isNotBlank()) sources.add { lastFm(name, lk) }

        var netErr = false
        for (s in sources) {
            try {
                val r = normalize(s())
                if (r.isNotEmpty()) return r
            } catch (e: IOException) {
                netErr = true
            } catch (e: Exception) {
            }
        }
        if (netErr) throw IOException("network")
        return emptyList()
    }

    private fun normalize(l: List<String>): List<String> =
        l.map { it.lowercase().replace('-', ' ').trim() }
            .filter { it.isNotEmpty() && it !in BAD && !it.matches(Regex("\\d+s?")) }
            .distinct()
            .take(3)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun http(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10000
            c.readTimeout = 10000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 429 || code >= 500) throw IOException("HTTP $code")
            if (code !in 200..299) throw HttpException(code)
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun spotifyToken(id: String, secret: String): String {
        if (token.isNotEmpty() && System.currentTimeMillis() < tokenExp) return token
        val basic = Base64.encodeToString("$id:$secret".toByteArray(), Base64.NO_WRAP)
        val txt = http(
            "https://accounts.spotify.com/api/token", "POST",
            mapOf(
                "Authorization" to "Basic $basic",
                "Content-Type" to "application/x-www-form-urlencoded"
            ),
            "grant_type=client_credentials"
        )
        val j = JSONObject(txt)
        token = j.getString("access_token")
        tokenExp = System.currentTimeMillis() + (j.optLong("expires_in", 3600) - 60) * 1000
        return token
    }

    private fun spotify(name: String, id: String, secret: String): List<String> {
        val t = spotifyToken(id, secret)
        val txt = http(
            "https://api.spotify.com/v1/search?q=${enc("artist:\"$name\"")}&type=artist&limit=5",
            headers = mapOf("Authorization" to "Bearer $t")
        )
        val items = JSONObject(txt).getJSONObject("artists").getJSONArray("items")
        if (items.length() == 0) return emptyList()
        var pick = items.getJSONObject(0)
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            if (o.optString("name").equals(name, true)) { pick = o; break }
        }
        val g = pick.optJSONArray("genres") ?: return emptyList()
        return (0 until g.length()).map { g.getString(it) }
    }

    private fun musicBrainz(name: String): List<String> {
        val txt = http(
            "https://musicbrainz.org/ws/2/artist/?query=${enc("artist:\"$name\"")}&fmt=json&limit=3",
            headers = mapOf("User-Agent" to "ListenStats/1.0 (personal use)")
        )
        val arr = JSONObject(txt).optJSONArray("artists") ?: return emptyList()
        if (arr.length() == 0) return emptyList()
        var pick = arr.getJSONObject(0)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("name").equals(name, true)) { pick = o; break }
        }
        val tags = pick.optJSONArray("tags") ?: return emptyList()
        return (0 until tags.length()).map { tags.getJSONObject(it) }
            .sortedByDescending { it.optInt("count") }
            .map { it.getString("name") }
    }

    private fun lastFm(name: String, key: String): List<String> {
        val txt = http(
            "https://ws.audioscrobbler.com/2.0/?method=artist.gettoptags&artist=${enc(name)}" +
                "&api_key=$key&format=json"
        )
        val tags = JSONObject(txt).optJSONObject("toptags")?.optJSONArray("tag") ?: return emptyList()
        return (0 until minOf(tags.length(), 8)).map { tags.getJSONObject(it).getString("name") }
    }
}
