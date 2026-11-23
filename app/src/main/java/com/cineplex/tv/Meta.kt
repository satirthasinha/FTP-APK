package com.cineplex.tv

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs

/** Cinemeta: free, keyless IMDb-based metadata. Cached per title ("{}" = not found). */
object Meta {
    private val YEAR_RE = Regex("""\d{4}""")

    private fun base(t: String): String = t.replace(Regex("^\\d+\\s*[-.]\\s*"), "")

    private fun clean(t: String): String = t.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun norm(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** Searches Cinemeta and returns the best matching id, or null. */
    private fun findId(kind: String, query: String, title: String, year: Int): String? {
        val q = Uri.encode(query)
        val hits = JSONObject(Net.get("https://v3-cinemeta.strem.io/catalog/$kind/top/search=$q.json")).optJSONArray("metas")
            ?: return null
        var bestId: String? = null
        var bestScore = 0
        val want = norm(title)
        for (i in 0 until hits.length()) {
            val h = hits.getJSONObject(i)
            val y = YEAR_RE.find(h.optString("releaseInfo"))?.value?.toIntOrNull()
            val dy = if (y == null) 99 else abs(y - year)
            val nameEq = norm(h.optString("name")) == want
            val score = when {
                dy == 0 && nameEq -> 5
                dy == 0 -> 4
                dy <= 1 && nameEq -> 3
                dy <= 1 -> 2
                nameEq -> 1
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                bestId = h.optString("id")
            }
        }
        return bestId
    }

    suspend fun get(db: Db, item: Item): JSONObject? = withContext(Dispatchers.IO) {
        val cached = db.meta(item.href)
        if (cached != null) {
            JSONObject(cached).takeIf { j -> j.length() > 0 }
        } else try {
            val kind = if (item.series) "series" else "movie"
            val b = base(item.title)
            val queries = listOf(clean(b), clean(b.substringBefore(":").substringBefore(" - "))).distinct()
            var id: String? = null
            for (q in queries) {
                if (q.isEmpty()) continue
                id = findId(kind, q, item.title, item.year)
                if (id != null) break
            }
            if (id == null) {
                db.putMeta(item.href, "{}")
                null
            } else {
                val m = JSONObject(Net.get("https://v3-cinemeta.strem.io/meta/$kind/$id.json")).optJSONObject("meta")
                    ?: JSONObject()
                db.putMeta(item.href, m.toString())
                m.takeIf { j -> j.length() > 0 }
            }
        } catch (e: Exception) {
            null   // network error: don't cache, retry next time
        }
    }
}
