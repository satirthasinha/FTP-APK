package com.cineplex.tv

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URL

object Net {
    fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        )
        c.setRequestProperty("Referer", "$BASE/")
        try {
            if (c.responseCode >= 400) error("HTTP ${c.responseCode} for $url")
            return c.inputStream.bufferedReader().use { r -> r.readText() }
        } finally {
            c.disconnect()
        }
    }
}

fun absUrl(category: String, href: String): String = Site.absolute(href)

data class Item(
    val href: String,
    val category: String,
    val title: String,
    val year: Int,
    val quality: String,
    val dual: Boolean,
    val series: Boolean,
    val poster: String?,
    val video: String?,
    val resolved: Boolean
)

enum class SortBy { ADDED, YEAR, TITLE }
data class ListFilter(val sort: SortBy = SortBy.ADDED, val quality: String? = null)
class Resume(val item: Item, val pos: Long, val dur: Long)

fun applyFilter(list: List<Item>, f: ListFilter): List<Item> {
    var r = list
    if (f.quality != null) r = r.filter { x -> x.quality == f.quality }
    return when (f.sort) {
        SortBy.ADDED -> r
        SortBy.YEAR -> r.sortedByDescending { x -> x.year }
        SortBy.TITLE -> r.sortedBy { x -> x.title.lowercase() }
    }
}

private const val COLS = "href,category,title,year,quality,dual,series,poster,video,resolved"
private val COLS_I = COLS.split(",").joinToString(",") { c -> "i.$c" }

private fun Cursor.toItem(): Item = Item(
    getString(0), getString(1) ?: "", getString(2), getInt(3), getString(4) ?: "",
    getInt(5) == 1, getInt(6) == 1, getString(7), getString(8), getInt(9) == 1
)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var audio: String
        get() = sp.getString("audio", "en") ?: "en"
        set(v) { sp.edit().putString("audio", v).apply() }
    var subs: String
        get() = sp.getString("subs", "off") ?: "off"
        set(v) { sp.edit().putString("subs", v).apply() }
}

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "cineplex.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE items(href TEXT PRIMARY KEY, category TEXT, title TEXT, year INT, quality TEXT,
            dual INT, series INT, poster TEXT, video TEXT, resolved INT, added INT)"""
        )
        db.execSQL("CREATE TABLE progress(key TEXT PRIMARY KEY, title_href TEXT, position INT, duration INT, ts INT)")
        db.execSQL("CREATE TABLE favorites(href TEXT PRIMARY KEY, added INT)")
        db.execSQL("CREATE TABLE meta(href TEXT PRIMARY KEY, json TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** Remember every title we have seen, so My List and Continue Watching work without the network. */
    fun cacheItems(list: List<Item>) {
        if (list.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for (i in list) {
                db.execSQL(
                    "INSERT OR REPLACE INTO items(href,category,title,year,quality,dual,series,poster,video,resolved,added) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(
                        i.href, i.category, i.title, i.year, i.quality, if (i.dual) 1 else 0,
                        if (i.series) 1 else 0, i.poster, i.video, 1, now
                    )
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun itemFor(href: String): Item? =
        readableDatabase.rawQuery("SELECT $COLS FROM items WHERE href=?", arrayOf(href)).use { c ->
            if (c.moveToFirst()) c.toItem() else null
        }

    // ---- progress ----
    fun progressFor(titleHref: String): Map<String, Pair<Long, Long>> {
        val out = HashMap<String, Pair<Long, Long>>()
        readableDatabase.rawQuery(
            "SELECT key,position,duration FROM progress WHERE title_href=?", arrayOf(titleHref)
        ).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = Pair(c.getLong(1), c.getLong(2))
        }
        return out
    }

    fun saveProgress(key: String, titleHref: String, pos: Long, dur: Long) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO progress(key,title_href,position,duration,ts) VALUES(?,?,?,?,?)",
            arrayOf<Any?>(key, titleHref, pos, dur, System.currentTimeMillis())
        )
    }

    /** Most recent progress row for a title: (key, position, duration). */
    fun lastProgress(titleHref: String): Triple<String, Long, Long>? =
        readableDatabase.rawQuery(
            "SELECT key,position,duration FROM progress WHERE title_href=? ORDER BY ts DESC LIMIT 1",
            arrayOf(titleHref)
        ).use { c ->
            if (c.moveToFirst()) Triple(c.getString(0), c.getLong(1), c.getLong(2)) else null
        }

    fun continueWatching(limit: Int): List<Resume> {
        val rows = ArrayList<Triple<String, Long, Long>>()
        readableDatabase.rawQuery(
            "SELECT title_href,position,duration FROM progress WHERE position>0 ORDER BY ts DESC LIMIT 60", null
        ).use { c ->
            while (c.moveToNext()) rows.add(Triple(c.getString(0), c.getLong(1), c.getLong(2)))
        }
        val out = ArrayList<Resume>()
        val seen = HashSet<String>()
        for ((href, pos, dur) in rows) {
            if (!seen.add(href)) continue
            val item = itemFor(href) ?: continue
            out.add(Resume(item, pos, dur))
            if (out.size >= limit) break
        }
        return out
    }

    // ---- favorites ----
    fun isFav(href: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM favorites WHERE href=?", arrayOf(href)).use { c -> c.moveToFirst() }

    /** Returns true if the title is now a favorite. */
    fun toggleFav(href: String): Boolean {
        return if (isFav(href)) {
            writableDatabase.execSQL("DELETE FROM favorites WHERE href=?", arrayOf<Any?>(href))
            false
        } else {
            writableDatabase.execSQL(
                "INSERT OR REPLACE INTO favorites(href,added) VALUES(?,?)",
                arrayOf<Any?>(href, System.currentTimeMillis())
            )
            true
        }
    }

    fun favorites(): List<Item> {
        val out = mutableListOf<Item>()
        readableDatabase.rawQuery(
            "SELECT $COLS_I FROM items i JOIN favorites f ON f.href=i.href ORDER BY f.added DESC", null
        ).use { c ->
            while (c.moveToNext()) out.add(c.toItem())
        }
        return out
    }

    // ---- metadata cache ----
    fun meta(href: String): String? =
        readableDatabase.rawQuery("SELECT json FROM meta WHERE href=?", arrayOf(href)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun putMeta(href: String, json: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO meta(href,json) VALUES(?,?)", arrayOf<Any?>(href, json))
    }
}

/** Fetches from the website (max 4 requests at once) and caches results in memory. */
class Repo(private val db: Db) {
    private val gate = Semaphore(4)
    private val cache = HashMap<String, List<Item>>()
    private var homeData: Triple<List<Item>, List<Item>, List<Item>>? = null

    private suspend fun <T> io(block: () -> T): T = gate.withPermit { withContext(Dispatchers.IO) { block() } }

    suspend fun home(): Triple<List<Item>, List<Item>, List<Item>> {
        val h = homeData
        if (h != null) return h
        val r = io { Site.home() }
        homeData = r
        withContext(Dispatchers.IO) { db.cacheItems(r.first + r.second + r.third) }
        return r
    }

    private suspend fun cached(key: String, loader: () -> List<Item>): List<Item> {
        val hit = synchronized(cache) { cache[key] }
        if (hit != null) return hit
        val r = io(loader)
        synchronized(cache) { cache[key] = r }
        withContext(Dispatchers.IO) { db.cacheItems(r) }
        return r
    }

    suspend fun movies(cat: String?, page: Int): List<Item> = cached("m|$cat|$page") { Site.movieList(cat, page) }
    suspend fun tv(cat: String?, page: Int): List<Item> = cached("t|$cat|$page") { Site.tvList(cat, page) }

    suspend fun search(q: String): List<Item> {
        val r = io { Site.search(q) }
        withContext(Dispatchers.IO) { db.cacheItems(r) }
        return r
    }

    suspend fun sources(item: Item): List<Src> = io { Site.sources(item) }
    suspend fun seriesInfo(item: Item): SeriesInfo = io { Site.seriesInfo(item) }
    suspend fun seasonEpisodes(item: Item, season: Int): List<Ep> = io { Site.seasonEpisodes(item, season) }
    suspend fun episodeVideo(page: String): String? = io { Site.episodeVideo(page) }
    suspend fun episodeCandidates(page: String): List<String> = io { Site.episodeCandidates(page) }

    fun clearCache() {
        synchronized(cache) { cache.clear() }
        homeData = null
    }
}

class App(ctx: Context) {
    val db = Db(ctx.applicationContext)
    val prefs = Prefs(ctx.applicationContext)
    val repo = Repo(db)

    init {
        CookieHandler.setDefault(CookieManager())
    }
}
