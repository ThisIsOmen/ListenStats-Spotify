package com.listenstats

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Stat(val name: String, val sub: String, val count: Int, val ms: Long)

class Db private constructor(private val app: Context) : SQLiteOpenHelper(app, "stats.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE plays(id INTEGER PRIMARY KEY AUTOINCREMENT, artist TEXT NOT NULL, " +
                "title TEXT NOT NULL, ts INTEGER NOT NULL, listened INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX idx_plays_ts ON plays(ts)")
        db.execSQL(
            "CREATE TABLE artists(name TEXT PRIMARY KEY, genres TEXT NOT NULL DEFAULT '', " +
                "fetched INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    fun addPlay(artist: String, title: String, ts: Long): Long {
        val w = writableDatabase
        val v = ContentValues().apply {
            put("artist", artist); put("title", title); put("ts", ts)
        }
        val id = w.insert("plays", null, v)
        Splitter.split(app, artist).forEach { addArtist(it) }
        return id
    }

    fun updateListened(id: Long, ms: Long) {
        writableDatabase.execSQL("UPDATE plays SET listened=? WHERE id=?", arrayOf<Any>(ms, id))
    }

    fun total(since: Long): Pair<Int, Long> =
        readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(listened),0) FROM plays WHERE ts>=?",
            arrayOf(since.toString())
        ).use { c -> if (c.moveToFirst()) Pair(c.getInt(0), c.getLong(1)) else Pair(0, 0L) }

    private fun addArtist(name: String) {
        writableDatabase.insertWithOnConflict(
            "artists", null, ContentValues().apply { put("name", name) },
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    /** Приводит таблицу artists в соответствие с текущим способом разделения артистов. */
    fun syncArtists() {
        val valid = HashSet<String>()
        readableDatabase.rawQuery("SELECT DISTINCT artist FROM plays", null).use { c ->
            while (c.moveToNext()) valid.addAll(Splitter.split(app, c.getString(0)))
        }
        valid.forEach { addArtist(it) }
        val existing = ArrayList<String>()
        readableDatabase.rawQuery("SELECT name FROM artists", null).use { c ->
            while (c.moveToNext()) existing.add(c.getString(0))
        }
        existing.filter { it !in valid }.forEach {
            writableDatabase.execSQL("DELETE FROM artists WHERE name=?", arrayOf<Any>(it))
        }
    }

    fun topArtists(since: Long): List<Stat> {
        val m = HashMap<String, LongArray>()
        readableDatabase.rawQuery(
            "SELECT artist, COUNT(*), COALESCE(SUM(listened),0) FROM plays WHERE ts>=? GROUP BY artist",
            arrayOf(since.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val cnt = c.getInt(1).toLong()
                val ms = c.getLong(2)
                Splitter.split(app, c.getString(0)).forEach {
                    val a = m.getOrPut(it) { LongArray(2) }
                    a[0] += cnt; a[1] += ms
                }
            }
        }
        return m.map { Stat(it.key, "", it.value[0].toInt(), it.value[1]) }
            .sortedWith(compareByDescending<Stat> { it.count }.thenByDescending { it.ms })
            .take(100)
    }

    fun topTracks(since: Long): List<Stat> =
        readableDatabase.rawQuery(
            "SELECT title, artist, COUNT(*), COALESCE(SUM(listened),0) FROM plays WHERE ts>=? " +
                "GROUP BY title, artist ORDER BY COUNT(*) DESC, SUM(listened) DESC LIMIT 100",
            arrayOf(since.toString())
        ).use { c ->
            val l = ArrayList<Stat>()
            while (c.moveToNext()) l.add(Stat(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3)))
            l
        }

    fun topGenres(since: Long): List<Stat> {
        val gmap = HashMap<String, String>()
        readableDatabase.rawQuery("SELECT name, genres FROM artists", null).use { c ->
            while (c.moveToNext()) gmap[c.getString(0)] = c.getString(1) ?: ""
        }
        val m = HashMap<String, LongArray>()
        readableDatabase.rawQuery(
            "SELECT artist, COUNT(*), COALESCE(SUM(listened),0) FROM plays WHERE ts>=? GROUP BY artist",
            arrayOf(since.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val cnt = c.getInt(1).toLong()
                val ms = c.getLong(2)
                for (name in Splitter.split(app, c.getString(0))) {
                    val g = gmap[name] ?: ""
                    g.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                        val a = m.getOrPut(it) { LongArray(2) }
                        a[0] += cnt; a[1] += ms
                    }
                }
            }
        }
        return m.map { Stat(it.key, "", it.value[0].toInt(), it.value[1]) }
            .sortedByDescending { it.count }
    }

    fun pending(): List<String> =
        readableDatabase.rawQuery(
            "SELECT name FROM artists WHERE fetched=0 AND attempts<5", null
        ).use { c ->
            val l = ArrayList<String>()
            while (c.moveToNext()) l.add(c.getString(0))
            l
        }

    fun setGenres(name: String, genres: String) {
        writableDatabase.execSQL(
            "UPDATE artists SET genres=?, fetched=1 WHERE name=?", arrayOf<Any>(genres, name)
        )
    }

    fun bumpAttempt(name: String) {
        writableDatabase.execSQL("UPDATE artists SET attempts=attempts+1 WHERE name=?", arrayOf<Any>(name))
    }

    fun retryEmpty() {
        writableDatabase.execSQL("UPDATE artists SET fetched=0, attempts=0 WHERE genres=''")
    }

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }
    }
}
