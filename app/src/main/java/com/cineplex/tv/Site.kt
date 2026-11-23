package com.cineplex.tv

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLDecoder
import java.net.URLEncoder

/** Change this if the server address changes. */
const val BASE = "http://103.136.200.66"

val MOVIE_CATS = listOf(
    "3D Movies", "4K Movies", "Animation", "Anime", "Bangla", "Bangla Dubbed", "Bangla Movies", "Chinese",
    "Documentaries", "Documentary", "Dual Audio", "English", "Exclusive Full HD", "Foreign", "Hindi",
    "Hindi Dubbed", "Indian Bangla", "Indonesian", "Italy Movies", "Japanese", "Kids Cartoon", "Korean",
    "Pakistani", "Punjabi", "Turkey Movies",
    "Hindi Dubbed/Chinees Movies", "Hindi Dubbed/English Movies", "Hindi Dubbed/Indonesian Movies",
    "Hindi Dubbed/Italy Movies", "Hindi Dubbed/Japanese Movies", "Hindi Dubbed/Korean Movies",
    "Hindi Dubbed/Tamil Movies"
)

val TV_CATS = listOf(
    "Animation Series", "Bangla Animation", "Hindi Animation", "English Animation", "Others Animation",
    "Award Shows", "Bangla Shows", "English Shows", "Hindi Shows", "Others Shows",
    "Bangla Series", "Bangla Tv Series", "Bangla Drama", "Entertainment", "Indian Bangla", "Indian Bangla Drama",
    "Documentary", "Islamic Series", "Bangla Dubbed", "Hindi Dubbed",
    "Web Series", "Hindi Series", "Pakistani Series", "English Series", "Chinese Series", "Korean Series",
    "Japanese Series", "Others Dubbed Series", "WWE", "AEW Wrestling", "WWE Wrestling"
)

class Src(val url: String, val label: String)
class Season(val num: Int, val name: String)
class Ep(val page: String, val name: String, val label: String, val season: Int, val epNo: Int)
class SeriesInfo(val seasons: List<Season>, val defaultSeason: Int, val episodes: List<Ep>)

/**
 * Everything that knows about the website's pages lives here.
 * If the site changes, this is the only file to adjust.
 *
 * How the site works (read from its real pages):
 *  - lists:        category.php?category=X&page=N, tcategory.php?category=X&page=N, load_more_movies.php?offset=N
 *  - movie video:  player.php?id=ID contains   const videoSrc = "/v/m/....mp4?md5=...&expires=..."   (signed, short-lived)
 *  - series:       watch.php?id=ID[&season=S] lists episodes; each episode page watch.php?id=ID&season=S&ep=E
 *                  has <source src="/hls/.../master.m3u8">
 */
object Site {
    private const val EXT = "mkv|mp4|m3u8|avi|mov|webm|m4v"

    private val CARD_RE = Regex("<a\\s+href=\"/?(view|watch|tview)\\.php\\?id=(\\d+)[^\"]*\"[^>]*>([\\s\\S]*?)</a>")
    private val IMG_RE = Regex("<img[^>]*?src=\"([^\"]+)\"[^>]*?alt=\"([^\"]*)\"")
    private val TAG_RE = Regex("<[^>]+>")
    private val YEAR_SPAN_RE = Regex("(?:shrink-0|text-gray-400)\">\\s*((?:19|20)\\d{2})\\s*<")
    private val YEAR_RE = Regex("\\b(19\\d{2}|20\\d{2})\\b")
    private val QUALITY_RE = Regex("\\b(2160|1080|720|480)\\s*[pP]\\b")
    private val CAT_RE = Regex("catLabel[^>]*>([^<]+)<")

    private val ABS_VIDEO_RE = Regex("https?://[^\\s\"'<>()]+?\\.(?:$EXT)(?:\\?[^\\s\"'<>]*)?", RegexOption.IGNORE_CASE)
    private val REL_VIDEO_RE = Regex("[\"']([^\"'\\s<>]+?\\.(?:$EXT)(?:\\?[^\"'\\s<>]*)?)[\"']", RegexOption.IGNORE_CASE)
    private val IFRAME_RE = Regex("<iframe[^>]*?src=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    private val PLAYER_LINK_RE = Regex("href=\"([^\"]*(?:player|stream)\\.php\\?[^\"]*)\"", RegexOption.IGNORE_CASE)
    private val EP_CARD_RE = Regex("<a href=\"([^\"]+)\"[^>]*data-ep=\"(\\d+)\"")
    private val EP_TITLE_RE = Regex("font-semibold\">([^<]*)<")
    private val OPTION_RE = Regex("<option value=\"(\\d+)\"([^>]*)>([^<]*)</option>")
    private val QUALITY_NAME_RE = Regex("(2160|1080|720|480)p", RegexOption.IGNORE_CASE)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun unescape(s: String): String {
        var r = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        r = Regex("&#(\\d+);").replace(r) { m -> m.groupValues[1].toInt().toChar().toString() }
        return r
    }

    fun absolute(u0: String): String {
        val u = u0.trim().replace(" ", "%20")
        return when {
            u.startsWith("http://") || u.startsWith("https://") -> u
            u.startsWith("//") -> "http:$u"
            u.startsWith("/") -> BASE + u
            else -> "$BASE/$u"
        }
    }

    private fun posterPath(p: String): String? {
        val s = p.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("http") || s.startsWith("/")) return s
        return "/uploads/" + s.trimStart('/')
    }

    // ───────────── lists of movies / series ─────────────

    fun parseCards(html: String): List<Item> {
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        for (m in CARD_RE.findAll(html)) {
            val kind = m.groupValues[1]
            val id = m.groupValues[2]
            val block = m.groupValues[3]
            val href = (if (kind == "view") "view.php?id=" else "tview.php?id=") + id
            if (!seen.add(href)) continue
            var poster: String? = null
            var title = ""
            for (im in IMG_RE.findAll(block)) {
                val src = im.groupValues[1]
                if (src.contains("IMDB_Logo", ignoreCase = true)) continue
                poster = src
                title = unescape(im.groupValues[2])
                break
            }
            if (title.isEmpty()) continue
            val text = unescape(TAG_RE.replace(block, " "))
            val ym = YEAR_SPAN_RE.find(block) ?: YEAR_RE.find(text)
            val year = ym?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val qm = QUALITY_RE.find(text)
            val quality = if (qm != null) qm.groupValues[1] + "p" else ""
            val cat = CAT_RE.find(block)?.groupValues?.get(1)?.trim() ?: ""
            out.add(Item(href, cat, title, year, quality, false, kind != "view", poster, null, true))
        }
        return out
    }

    /** Home page: (trending, latest TV, latest movies). */
    fun home(): Triple<List<Item>, List<Item>, List<Item>> {
        val html = Net.get("$BASE/")
        val iTrend = html.indexOf("id=\"carouselTrack\"")
        val iTv = html.indexOf("id=\"tvRow\"")
        val iGrid = html.indexOf("id=\"movieGrid\"")
        if (iTrend < 0 && iTv < 0 && iGrid < 0) return Triple(emptyList(), emptyList(), parseCards(html))
        val marks = listOf(iTrend, iTv, iGrid, html.length)
        fun seg(start: Int): String {
            if (start < 0) return ""
            val end = marks.filter { x -> x > start }.minOrNull() ?: html.length
            return html.substring(start, end)
        }
        return Triple(parseCards(seg(iTrend)), parseCards(seg(iTv)), parseCards(seg(iGrid)))
    }

    fun movieList(cat: String?, page: Int): List<Item> {
        if (cat == null) {
            // The home page shows the first 24; load_more_movies.php continues from offset 24 in steps of 12.
            return if (page <= 1) home().third
            else parseCards(Net.get("$BASE/load_more_movies.php?offset=" + (24 + (page - 2) * 12)))
        }
        return parseCards(Net.get("$BASE/category.php?category=" + enc(cat) + (if (page > 1) "&page=$page" else "")))
    }

    fun tvList(cat: String?, page: Int): List<Item> {
        if (cat == null) return parseCards(Net.get("$BASE/tvs.php" + (if (page > 1) "?page=$page" else "")))
        return parseCards(Net.get("$BASE/tcategory.php?category=" + enc(cat) + (if (page > 1) "&page=$page" else "")))
    }

    fun search(q: String): List<Item> {
        val txt = Net.get("$BASE/search_ajax.php?q=" + enc(q) + "&limit=40")
        val root = JSONTokener(txt).nextValue()
        val arr: JSONArray? = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("results")
            else -> null
        }
        val out = ArrayList<Item>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty()) continue
            val url = o.optString("url")
            val series = o.optString("type").equals("series", ignoreCase = true) || url.contains("tview")
            val href = (if (series) "tview.php?id=" else "view.php?id=") + id
            val title = unescape(o.optString("title"))
            if (title.isEmpty()) continue
            out.add(
                Item(
                    href, o.optString("category"), title, o.optString("year").toIntOrNull() ?: 0, "", false, series,
                    posterPath(o.optString("poster")), null, true
                )
            )
        }
        return out
    }

    // ───────────── video links ─────────────

    fun fileName(u: String): String {
        val raw = u.substringBefore("?").substringAfterLast('/')
        return try { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { raw }
    }

    fun extractVideoUrls(html0: String): List<String> {
        val html = html0.replace("\\/", "/").replace("&amp;", "&")
        val out = LinkedHashSet<String>()
        for (m in ABS_VIDEO_RE.findAll(html)) out.add(m.value)
        for (m in REL_VIDEO_RE.findAll(html)) out.add(absolute(m.groupValues[1]))
        return out.toList()
    }

    private fun labelFor(u: String): String {
        val name = fileName(u)
        val q = QUALITY_NAME_RE.find(name)
        return if (q != null) q.groupValues[1] + "p" else name.take(28)
    }

    private fun innerLinks(html: String): List<String> {
        val out = LinkedHashSet<String>()
        for (m in IFRAME_RE.findAll(html)) out.add(absolute(unescape(m.groupValues[1])))
        for (m in PLAYER_LINK_RE.findAll(html)) out.add(absolute(unescape(m.groupValues[1])))
        return out.toList()
    }

    /** Playable sources for a movie, read from player.php (fresh signed link) with view.php as a fallback. */
    fun sources(item: Item): List<Src> {
        val id = item.href.substringAfter("id=")
        val found = LinkedHashSet<String>()
        for (p in listOf("player.php?id=$id", "view.php?id=$id")) {
            val html = try { Net.get("$BASE/$p") } catch (e: Exception) { continue }
            found.addAll(extractVideoUrls(html))
            if (found.isEmpty()) {
                for (link in innerLinks(html).take(3)) {
                    val h2 = try { Net.get(link) } catch (e: Exception) { continue }
                    found.addAll(extractVideoUrls(h2))
                    if (found.isNotEmpty()) break
                }
            }
            if (found.isNotEmpty()) break
        }
        return found.map { u -> Src(u, labelFor(u)) }
    }

    // ───────────── TV series ─────────────

    private fun parseSeasons(html: String): Pair<List<Season>, Int> {
        val i = html.indexOf("name=\"season\"")
        if (i < 0) return Pair(emptyList(), 1)
        val e = html.indexOf("</select>", i)
        val seg = html.substring(i, if (e < 0) html.length else e)
        val out = ArrayList<Season>()
        var sel = -1
        for (m in OPTION_RE.findAll(seg)) {
            val n = m.groupValues[1].toIntOrNull() ?: continue
            val name = unescape(m.groupValues[3]).trim()
            out.add(Season(n, if (name.isEmpty()) "Season $n" else name))
            if (m.groupValues[2].contains("selected")) sel = n
        }
        val def = if (sel >= 0) sel else (out.firstOrNull()?.num ?: 1)
        return Pair(out, def)
    }

    private fun parseEpisodeList(html: String, season: Int): List<Ep> {
        val out = ArrayList<Ep>()
        val seen = HashSet<Int>()
        for (m in EP_CARD_RE.findAll(html)) {
            val epNo = m.groupValues[2].toInt()
            if (!seen.add(epNo)) continue
            val e = html.indexOf("</li>", m.range.last)
            val seg = html.substring(m.range.first, if (e < 0) html.length else e)
            val title = EP_TITLE_RE.find(seg)?.groupValues?.get(1)?.trim() ?: ""
            val page = absolute(unescape(m.groupValues[1]).substringBefore("#"))
            out.add(Ep(page, unescape(title).ifEmpty { "Episode $epNo" }, "E$epNo", season, epNo))
        }
        return out
    }

    fun seriesInfo(item: Item): SeriesInfo {
        val id = item.href.substringAfter("id=")
        var lastErr: Exception? = null
        for (p in listOf("watch.php?id=$id", "watch.php?series_id=$id")) {
            val html = try { Net.get("$BASE/$p") } catch (e: Exception) { lastErr = e; continue }
            val parsed = parseSeasons(html)
            val def = parsed.second
            val eps = parseEpisodeList(html, def)
            if (eps.isNotEmpty() || parsed.first.isNotEmpty()) {
                val ss = if (parsed.first.isEmpty()) listOf(Season(def, "Season $def")) else parsed.first
                return SeriesInfo(ss, def, eps)
            }
        }
        val err = lastErr
        if (err != null) throw err
        return SeriesInfo(emptyList(), 1, emptyList())
    }

    fun seasonEpisodes(item: Item, season: Int): List<Ep> {
        val id = item.href.substringAfter("id=")
        return parseEpisodeList(Net.get("$BASE/watch.php?id=$id&season=$season"), season)
    }

    /** Opens one episode page and returns its (HLS) video address. */
    fun episodeVideo(page: String): String? {
        val html = Net.get(page)
        val urls = extractVideoUrls(html)
        if (urls.isNotEmpty()) return urls.first()
        for (m in IFRAME_RE.findAll(html)) {
            val h2 = try { Net.get(absolute(unescape(m.groupValues[1]))) } catch (e: Exception) { continue }
            val u = extractVideoUrls(h2)
            if (u.isNotEmpty()) return u.first()
        }
        return null
    }
}
