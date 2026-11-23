package com.cineplex.tv

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

const val BASE = "http://103.136.200.66"

val MOVIE_CATS = listOf(
    "3D Movies", "4K Movies", "Animation", "Anime",
    "Bangla Dubbed", "Bangla Movies", "Chinese",
    "Documentaries", "Documentary", "Dual Audio", "English",
    "Exclusive Full HD", "Foreign", "Hindi",
    "Hindi Dubbed", "Indian Bangla", "Indonesian",
    "Italy Movies", "Japanese", "Kids Cartoon", "Korean",
    "Pakistani", "Punjabi", "Turkey Movies",
    "Hindi Dubbed/Chinees Movies", "Hindi Dubbed/English Movies",
    "Hindi Dubbed/Indonesian Movies", "Hindi Dubbed/Italy Movies",
    "Hindi Dubbed/Japanese Movies", "Hindi Dubbed/Korean Movies",
    "Hindi Dubbed/Tamil Movies"
)

val TV_CATS = listOf(
    "Animation Series", "Bangla Animation", "Hindi Animation",
    "English Animation", "Others Animation",
    "Award Shows", "Bangla Shows", "English Shows",
    "Hindi Shows", "Others Shows",
    "Bangla Series", "Bangla Tv Series", "Bangla Drama",
    "Entertainment", "Indian Bangla", "Indian Bangla Drama",
    "Documentary", "Islamic Series", "Bangla Dubbed",
    "Hindi Dubbed", "Web Series", "Hindi Series",
    "Pakistani Series", "English Series", "Chinese Series",
    "Korean Series", "Japanese Series", "Others Series", "Others Dubbed Series",
    "WWE", "AEW Wrestling", "WWE Wrestling"
)

class Src(val url: String, val label: String)
class Season(val num: Int, val name: String)
class Ep(
    val page: String,
    val name: String,
    val label: String,
    val season: Int,
    val epNo: Int
)
class SeriesInfo(
    val seasons: List<Season>,
    val defaultSeason: Int,
    val episodes: List<Ep>
)

/** What a URL really serves: an HLS playlist, a plain media file, an error / HTML page, or "could not tell". */
enum class Probe { HLS, FILE, BAD, UNKNOWN }

object Site {

    private const val EXT = "mkv|mp4|m3u8q|m3u8|avi|mov|webm|m4v"

    private val CARD_RE = Regex(
        "<a\\s[^>]*?href=[\"'](?:https?://[^/\"']+)?/?(view|watch|tview)\\.php\\?(?:[^\"']*?&(?:amp;)?)?(?:series_id|id)=(\\d+)[^\"']*[\"'][^>]*>([\\s\\S]*?)</a>",
        RegexOption.IGNORE_CASE
    )

    private val IMG_TAG_RE = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)

    private fun attr(attrs: String, name: String): String? {
        val m = Regex(
            "(?:^|[\\s\"'])" + Regex.escape(name) + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')",
            RegexOption.IGNORE_CASE
        ).find(attrs) ?: return null
        return m.groupValues[1].ifEmpty { m.groupValues[2] }
    }

    private val ANCHOR_RE = Regex("<a\\b([^>]*)>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)

    private val SOURCE_TAG_RE = Regex(
        "<(?:source|video)\\b[^>]*?\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']",
        RegexOption.IGNORE_CASE
    )

    private val TAG_RE = Regex("<[^>]+>")

    private val YEAR_SPAN_RE = Regex(
        "(?:shrink-0|text-gray-400)\">\\s*((?:19|20)\\d{2})\\s*<"
    )

    private val YEAR_RE = Regex("\\b(19\\d{2}|20\\d{2})\\b")

    private val QUALITY_RE = Regex(
        "\\b(2160|1080|720|480)\\s*[pP]\\b"
    )

    private val CAT_RE = Regex(
        "catLabel[^>]*>([^<]+)<"
    )

    private val ABS_VIDEO_RE = Regex(
        "https?://[^\\s\"'<>()]+?\\.(?:$EXT)(?=[/?#\\s\"'<>()]|$)(?:/[^/\\s\"'<>()?#]*\\.m3u8q?)?(?:\\?[^\\s\"'<>]*)?",
        RegexOption.IGNORE_CASE
    )

    private val REL_VIDEO_RE = Regex(
        "[\"']([^\"'\\s<>]+?\\.(?:$EXT)(?=[/?#\"'\\s<>]|$)(?:/[^/\"'\\s<>?#]*\\.m3u8q?)?(?:\\?[^\"'\\s<>]*)?)[\"']",
        RegexOption.IGNORE_CASE
    )

    private val IFRAME_RE = Regex(
        "<iframe[^>]*?src\\s*=\\s*[\"\']([^\"\']+)[\"\']",
        RegexOption.IGNORE_CASE
    )

    private val PLAYER_LINK_RE = Regex(
        "(?:href|src)\\s*=\\s*[\"\']([^\"\']*(?:player|stream|embed|download)\\.php\\?[^\"\']*)[\"\']",
        RegexOption.IGNORE_CASE
    )

    private val EP_TITLE_RE = Regex(
        "font-semibold\">([^<]*)<"
    )

    private val QUALITY_NAME_RE = Regex(
        "(2160|1080|720|480)p",
        RegexOption.IGNORE_CASE
    )

    private fun enc(s: String): String =
        URLEncoder.encode(s, "UTF-8")

    fun unescape(s: String): String {
        var r = s
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")

        r = Regex("&#(\\d+);").replace(r) { m ->
            m.groupValues[1].toInt().toChar().toString()
        }

        return r
    }

    fun absolute(u0: String): String {
        val u = u0.trim().replace(" ", "%20")

        return when {
            u.startsWith("http://") ||
                u.startsWith("https://") -> u

            u.startsWith("//") -> "http:$u"

            u.startsWith("/") -> BASE + u

            else -> "$BASE/$u"
        }
    }

    private fun posterPath(p: String): String? {
        val s = p.trim()

        if (s.isEmpty()) return null

        if (s.startsWith("http") || s.startsWith("/")) {
            return s
        }

        return "/uploads/" + s.trimStart('/')
    }

    // ───────────── Movie and TV lists ─────────────

    fun parseCards(html: String): List<Item> {
        val out = ArrayList<Item>()
        val seen = HashSet<String>()

        for (m in CARD_RE.findAll(html)) {
            val kind = m.groupValues[1]
            val id = m.groupValues[2]
            val block = m.groupValues[3]

            val href =
                (if (kind == "view") "view.php?id=" else "tview.php?id=") + id

            if (!seen.add(href)) continue

            var poster: String? = null
            var title = ""

            for (im in IMG_TAG_RE.findAll(block)) {
                val tag = im.value
                val src = attr(tag, "src") ?: attr(tag, "data-src") ?: continue

                if (src.contains("IMDB_Logo", ignoreCase = true)) {
                    continue
                }

                val t = attr(tag, "alt")?.ifBlank { null } ?: attr(tag, "title") ?: ""

                poster = src
                title = unescape(t)
                break
            }

            if (title.isEmpty()) continue

            val text = unescape(TAG_RE.replace(block, " "))

            val ym = YEAR_SPAN_RE.find(block) ?: YEAR_RE.find(text)

            val year = ym?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val qm = QUALITY_RE.find(text)

            val quality =
                if (qm != null) qm.groupValues[1] + "p" else ""

            val cat = CAT_RE.find(block)
                ?.groupValues?.get(1)?.trim() ?: ""

            out.add(
                Item(
                    href,
                    cat,
                    title,
                    year,
                    quality,
                    false,
                    kind != "view",
                    poster,
                    null,
                    true
                )
            )
        }

        return out
    }

    /** Home page: trending, latest TV, latest movies. */
    fun home(): Triple<List<Item>, List<Item>, List<Item>> {
        val html = Net.get("$BASE/")

        val iTrend = html.indexOf("id=\"carouselTrack\"")
        val iTv = html.indexOf("id=\"tvRow\"")
        val iGrid = html.indexOf("id=\"movieGrid\"")

        if (iTrend < 0 && iTv < 0 && iGrid < 0) {
            return Triple(
                emptyList(),
                emptyList(),
                parseCards(html)
            )
        }

        val marks = listOf(iTrend, iTv, iGrid, html.length)

        fun seg(start: Int): String {
            if (start < 0) return ""

            val end = marks.filter { x -> x > start }.minOrNull()
                ?: html.length

            return html.substring(start, end)
        }

        return Triple(
            parseCards(seg(iTrend)),
            parseCards(seg(iTv)),
            parseCards(seg(iGrid))
        )
    }

    fun movieList(cat: String?, page: Int): List<Item> {
        if (cat == null) {
            return if (page <= 1) {
                home().third
            } else {
                parseCards(
                    Net.get(
                        "$BASE/load_more_movies.php?offset=" +
                            (24 + (page - 2) * 12)
                    )
                )
            }
        }

        return parseCards(
            Net.get(
                "$BASE/category.php?category=${enc(cat)}" +
                    (if (page > 1) "&page=$page" else "")
            )
        )
    }

    fun tvList(cat: String?, page: Int): List<Item> {
        val pageQ = if (page > 1) "&page=$page" else ""

        if (cat == null) {
            // "All TV Series" on the site is tcategory.php with an empty category.
            val all = try {
                parseCards(Net.get("$BASE/tcategory.php?category=$pageQ"))
            } catch (e: Exception) {
                emptyList()
            }
            if (all.isNotEmpty()) return all

            return parseCards(
                Net.get("$BASE/tvs.php" + (if (page > 1) "?page=$page" else ""))
            )
        }

        return parseCards(Net.get("$BASE/tcategory.php?category=${enc(cat)}$pageQ"))
    }

    fun search(q: String): List<Item> {
        val txt = Net.get(
            "$BASE/search_ajax.php?q=${enc(q)}&limit=40"
        )

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

            val series =
                o.optString("type").equals("series", ignoreCase = true) ||
                    url.contains("tview")

            val href =
                (if (series) "tview.php?id=" else "view.php?id=") + id

            val title = unescape(o.optString("title"))

            if (title.isEmpty()) continue

            out.add(
                Item(
                    href,
                    o.optString("category"),
                    title,
                    o.optString("year").toIntOrNull() ?: 0,
                    "",
                    false,
                    series,
                    posterPath(o.optString("poster")),
                    null,
                    true
                )
            )
        }

        return out
    }

    // ───────────── Video source handling ─────────────

    /** True when the URL points to an HLS playlist, including this server's .m3u8q suffix. */
    fun isHlsUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#')
        return path.endsWith(".m3u8", ignoreCase = true) ||
            path.endsWith(".m3u8q", ignoreCase = true)
    }

    fun fileName(u: String): String {
        val raw = u.substringBefore("?").substringAfterLast('/')

        return try {
            URLDecoder.decode(
                raw.replace("+", "%2B"),
                "UTF-8"
            )
        } catch (e: Exception) {
            raw
        }
    }

    fun extractVideoUrls(html0: String): List<String> {
        val html = unescape(html0)
            .replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("&amp;", "&")

        val out = LinkedHashSet<String>()

        for (m in ABS_VIDEO_RE.findAll(html)) {
            out.add(m.value.trimEnd('.', ',', ';'))
        }

        for (m in REL_VIDEO_RE.findAll(html)) {
            out.add(absolute(m.groupValues[1]))
        }

        for (m in SOURCE_TAG_RE.findAll(html)) {
            val v = m.groupValues[1].trim()
            if (!v.startsWith("blob:") && !v.startsWith("data:")) out.add(absolute(v))
        }

        val assignment = Regex(
            "(?:videoSrc|file|source|src)\\s*[:=]\\s*[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE
        )

        for (m in assignment.findAll(html)) {
            val value = m.groupValues[1].trim()

            if (
                Regex(
                    "\\.(?:$EXT)(?:[?#]|$)",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(value)
            ) {
                out.add(absolute(value))
            }
        }

        return out.filter {
            it.startsWith("http://") || it.startsWith("https://")
        }
    }

    private fun labelFor(u: String): String {
        val name = fileName(u)
        val q = QUALITY_NAME_RE.find(name)

        return if (q != null) {
            q.groupValues[1] + "p"
        } else {
            name.take(28)
        }
    }

    private fun innerLinks(html: String): List<String> {
        val out = LinkedHashSet<String>()

        for (m in IFRAME_RE.findAll(html)) {
            out.add(absolute(unescape(m.groupValues[1])))
        }

        for (m in PLAYER_LINK_RE.findAll(html)) {
            out.add(absolute(unescape(m.groupValues[1])))
        }

        return out.toList()
    }

    /**
     * Resolves a movie to direct media URLs.
     * Checks player and detail pages, then uses download.php
     * as a fallback for ExoPlayer to follow server redirects.
     */
    fun sources(item: Item): List<Src> {
        val id = Regex("(?:^|[?&])id=(\\d+)")
            .find(item.href)
            ?.groupValues
            ?.get(1)
            ?: item.href.substringAfter("id=", "")
                .substringBefore("&")
                .substringBefore("#")

        if (id.isBlank()) return emptyList()

        return resolve(
            listOf("$BASE/player.php?id=$id", "$BASE/view.php?id=$id"),
            "$BASE/download.php?id=$id"
        )
    }

    /** The movie lookup: read the pages in order, stop at the first video link, else use download.php. */
    private fun resolve(startPages: List<String>, downloadEndpoint: String): List<Src> {
        val found = LinkedHashSet<String>()
        val visited = HashSet<String>()
        val pending = ArrayDeque<String>()

        pending.addAll(startPages)

        var inspected = 0

        while (
            pending.isNotEmpty() &&
            inspected < 12 &&
            found.isEmpty()
        ) {
            val page = pending.removeFirst()

            if (!visited.add(page)) continue

            inspected++

            val html = try {
                Net.get(page)
            } catch (e: Exception) {
                android.util.Log.w("CinePlexSite", "Could not read $page", e)
                continue
            }

            found.addAll(extractVideoUrls(html))

            if (found.isNotEmpty()) break

            for (link in innerLinks(html)) {
                if (link.startsWith(BASE) && !visited.contains(link)) {
                    pending.addLast(link)
                }
            }
        }

        if (found.isNotEmpty()) {
            return found.map { url -> Src(url, labelFor(url)) }
        }

        return listOf(Src(downloadEndpoint, "Server stream"))
    }

    // ───────────── TV series ─────────────

    private val SERIES_PAGES = listOf("watch.php", "tview.php")
    private val seriesPage = HashMap<String, String>()

    private fun idOf(href: String): String =
        Regex("(?:^|[?&])(?:id|series_id)=(\\d+)").find(href)?.groupValues?.get(1)
            ?: href.substringAfter("id=", "").substringBefore("&").substringBefore("#")

    private val SEASON_SELECT_RE = Regex(
        "<select[^>]*(?:name|id)\\s*=\\s*[\"']season[\"'][^>]*>([\\s\\S]*?)</select>",
        RegexOption.IGNORE_CASE
    )
    private val OPTION_TAG_RE = Regex("<option\\b([^>]*)>([^<]*)</option>", RegexOption.IGNORE_CASE)
    private val SEASON_LINK_RE = Regex("[?&]season=(\\d+)")
    private val EP_PARAM_RE = Regex("[?&](?:ep|episode|eid|episode_id|e)=(\\d+)", RegexOption.IGNORE_CASE)

    private fun parseSeasons(html: String): Pair<List<Season>, Int> {
        val out = ArrayList<Season>()
        var sel = -1

        val seg = SEASON_SELECT_RE.find(html)?.groupValues?.get(1)
        if (seg != null) {
            for (m in OPTION_TAG_RE.findAll(seg)) {
                val n = attr(m.groupValues[1], "value")?.toIntOrNull() ?: continue
                val name = unescape(m.groupValues[2]).trim()
                out.add(Season(n, if (name.isEmpty()) "Season $n" else name))
                if (m.groupValues[1].contains("selected", ignoreCase = true)) sel = n
            }
        }

        if (out.isEmpty()) {
            val nums = SEASON_LINK_RE.findAll(unescape(html))
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .toSortedSet()
            for (n in nums) out.add(Season(n, "Season $n"))
        }

        val def = if (sel >= 0) sel else out.firstOrNull()?.num ?: 1
        return Pair(out, def)
    }

    private fun parseEpisodeList(html: String, season: Int, seriesId: String): List<Ep> {
        val out = ArrayList<Ep>()
        val seen = HashSet<Int>()

        for (m in ANCHOR_RE.findAll(html)) {
            val attrs = m.groupValues[1]
            val href0 = unescape(attr(attrs, "href") ?: "").trim()
            val epNo = (attr(attrs, "data-ep") ?: attr(attrs, "data-episode"))?.toIntOrNull()
                ?: EP_PARAM_RE.find(href0)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue

            if (!seen.add(epNo)) continue

            val e = html.indexOf("</li>", m.range.last)
            val seg = html.substring(m.range.first, if (e < 0) html.length else e)

            val title = EP_TITLE_RE.find(seg)?.groupValues?.get(1)?.trim()
                ?: unescape(TAG_RE.replace(m.groupValues[2], " ")).trim().replace(Regex("\\s+"), " ")

            val page = if (href0.isEmpty() || href0.startsWith("#") || href0.startsWith("javascript", true)) {
                "$BASE/watch.php?series_id=$seriesId&season=$season&ep=$epNo"
            } else {
                absolute(href0.substringBefore("#"))
            }

            out.add(
                Ep(
                    page,
                    unescape(title).ifEmpty { "Episode $epNo" },
                    "E$epNo",
                    season,
                    epNo
                )
            )
        }

        return out
    }

    fun seriesInfo(item: Item): SeriesInfo {
        val id = idOf(item.href)

        var lastErr: Exception? = null
        var gotPage = false

        val candidates = ArrayList<String>()
        val known = seriesPage[id]
        if (known != null) candidates.add("$known?series_id=$id")
        for (p in SERIES_PAGES) candidates.add("$p?series_id=$id")
        for (p in SERIES_PAGES) candidates.add("$p?id=$id")

        for (p in candidates.distinct()) {
            var html = try {
                Net.get("$BASE/$p")
            } catch (e: Exception) {
                lastErr = e
                continue
            }
            gotPage = true

            val parsed = parseSeasons(html)
            val def = parsed.second
            var eps = parseEpisodeList(html, def, id)

            // Page lists seasons but no episodes yet: ask for the default season explicitly.
            if (eps.isEmpty() && parsed.first.isNotEmpty()) {
                html = try {
                    Net.get("$BASE/${p.substringBefore('?')}?series_id=$id&season=$def")
                } catch (e: Exception) {
                    html
                }
                eps = parseEpisodeList(html, def, id)
            }

            if (eps.isNotEmpty() || parsed.first.isNotEmpty()) {
                seriesPage[id] = p.substringBefore('?')
                val ss = if (parsed.first.isEmpty()) {
                    listOf(Season(def, "Season $def"))
                } else {
                    parsed.first
                }
                return SeriesInfo(ss, def, eps)
            }
        }

        val err = lastErr
        if (!gotPage && err != null) throw err

        return SeriesInfo(emptyList(), 1, emptyList())
    }

    fun seasonEpisodes(item: Item, season: Int): List<Ep> {
        val id = idOf(item.href)
        val pages = (listOfNotNull(seriesPage[id]) + SERIES_PAGES).distinct()

        for (p in pages) {
            val html = try {
                Net.get("$BASE/$p?series_id=$id&season=$season")
            } catch (e: Exception) {
                continue
            }
            val eps = parseEpisodeList(html, season, id)
            if (eps.isNotEmpty()) return eps
        }

        return emptyList()
    }


    private const val UA =
        "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    /**
     * Looks at the first bytes of a URL (following redirects by hand, http<->https included)
     * and says whether it is an HLS playlist, a real media file, or an HTML/JSON/error page.
     */
    fun probe(url: String, referer: String = "$BASE/"): Probe {
        var cur = url
        return try {
            for (hop in 0..5) {
                val c = URL(cur).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 10000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Referer", referer)
                c.setRequestProperty("Accept", "*/*")
                c.setRequestProperty("Range", "bytes=0-1023")
                try {
                    val code = c.responseCode
                    if (code in 300..399) {
                        val loc = c.getHeaderField("Location") ?: return Probe.BAD
                        cur = URL(URL(cur), loc).toString()
                        continue
                    }
                    if (code >= 400) return Probe.BAD
                    val type = (c.contentType ?: "").lowercase()
                    if (type.contains("mpegurl")) return Probe.HLS
                    val buf = ByteArray(64)
                    val n = c.inputStream.use { it.read(buf) }
                    val head = if (n > 0) {
                        String(buf, 0, n, Charsets.ISO_8859_1).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
                    } else ""
                    return when {
                        head.startsWith("#EXTM3U") -> Probe.HLS
                        type.startsWith("text/html") ||
                            type.startsWith("application/json") ||
                            head.startsWith("<") -> Probe.BAD
                        else -> Probe.FILE
                    }
                } finally {
                    c.disconnect()
                }
            }
            Probe.BAD
        } catch (e: Exception) {
            android.util.Log.w("CinePlexSite", "probe failed for $url", e)
            Probe.UNKNOWN
        }
    }

    /** Reads a page as text, but never downloads a video by mistake (checks content type, caps size). */
    private fun fetchText(url: String, maxBytes: Int = 2_000_000): String? {
        var cur = url
        try {
            for (hop in 0..5) {
                val c = URL(cur).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Referer", "$BASE/")
                c.setRequestProperty("Accept", "text/html,application/json,*/*")
                try {
                    val code = c.responseCode
                    if (code in 300..399) {
                        val loc = c.getHeaderField("Location") ?: return null
                        cur = URL(URL(cur), loc).toString()
                        continue
                    }
                    if (code >= 400) return null
                    val type = (c.contentType ?: "").lowercase()
                    val isText = type.isEmpty() || type.startsWith("text/") || type.contains("json") ||
                        type.contains("javascript") || type.contains("xml")
                    if (!isText) return null   // a video / binary file, not a page
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    c.inputStream.use { ins ->
                        while (out.size() < maxBytes) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    return out.toString("UTF-8")
                } finally {
                    c.disconnect()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("CinePlexSite", "Could not read $url", e)
        }
        return null
    }

    private fun decodedPath(u: String): String {
        val raw = u.substringBefore('?').substringBefore('#')
        return try {
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            raw
        }
    }

    private val SXE_RE = Regex("""(?<![a-z0-9])s0*(\d{1,2})[\s._-]*e0*(\d{1,3})(?!\d)""", RegexOption.IGNORE_CASE)

    /**
     * A page may list the video of every episode (or a "next episodes" sidebar).
     * Keep only the links that belong to this season/episode, judging by the file name (S01E03, E03, Episode 3).
     */
    private fun forEpisode(urls: List<String>, season: Int, epNo: Int): List<String> {
        if (urls.size < 2 || epNo <= 0) return urls
        val epRe = Regex("""(?<![a-z0-9])(?:e|ep|episode)[\s._-]*0*$epNo(?!\d)""", RegexOption.IGNORE_CASE)

        fun score(u: String): Int {
            val path = decodedPath(u)
            val m = SXE_RE.findAll(path).lastOrNull()
            if (m != null) {
                val s = m.groupValues[1].toIntOrNull() ?: -1
                val e = m.groupValues[2].toIntOrNull() ?: -1
                return if (e == epNo && (season <= 0 || s == season)) 3 else -1
            }
            return if (epRe.containsMatchIn(path)) 2 else 0
        }

        val scored = urls.map { u -> u to score(u) }
        val best = scored.maxOf { it.second }
        if (best > 0) return scored.filter { it.second == best }.map { it.first }
        // Nothing is labelled with this episode: only drop links that clearly belong to another one.
        return scored.filter { it.second >= 0 }.map { it.first }.ifEmpty { urls }
    }

    private val MEDIA_PATH_RE = Regex("\\.(?:$EXT)$", RegexOption.IGNORE_CASE)

    // ── Debug info about the last episode lookup (the player copies it to the clipboard when playback fails) ──
    @Volatile private var dbgPageUrl: String = ""
    @Volatile private var dbgHtml: String = ""
    @Volatile private var dbgLog: String = ""
    @Volatile private var dbgFound: List<String> = emptyList()

    private val DEBUG_KEY_RE = Regex(
        """(?i)(m3u8|\.mp4|\.mkv|token|expires|signature|md5|secure|fetch\(|ajax|xmlhttprequest|xhrsetup|loadsource|new hls|withcredentials|cookie|referrer)"""
    )
    private val PHP_RE = Regex("""[A-Za-z0-9_./-]+\.php""")
    private val SCRIPT_SRC_RE = Regex("""<script[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /** Short excerpts around the interesting words of [text] (video links, tokens, ajax calls ...). */
    private fun digest(label: String, text: String, maxWindows: Int): String {
        val sb = StringBuilder("== $label (${text.length} chars)\n")
        var lastEnd = -1
        var n = 0
        for (m in DEBUG_KEY_RE.findAll(text)) {
            if (m.range.first < lastEnd) continue
            if (n >= maxWindows) break
            val a = (m.range.first - 120).coerceAtLeast(0)
            val b = (m.range.last + 200).coerceAtMost(text.length - 1)
            sb.append("... ").append(text.substring(a, b + 1).replace(Regex("\\s+"), " ")).append(" ...\n")
            lastEnd = b
            n++
        }
        return sb.toString()
    }

    private class UaCase(val name: String, val ua: String?, val referer: String?, val origin: Boolean)

    /** One request with one client identity; reports status, a few headers and the first bytes of the answer. */
    private fun diag(url: String, uc: UaCase): String {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.instanceFollowRedirects = false
            if (uc.ua != null) c.setRequestProperty("User-Agent", uc.ua)
            if (uc.referer != null) c.setRequestProperty("Referer", uc.referer)
            if (uc.origin) c.setRequestProperty("Origin", BASE)
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            try {
                val code = c.responseCode
                val type = c.contentType ?: "-"
                val server = c.getHeaderField("Server") ?: "-"
                val loc = c.getHeaderField("Location")
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val body = stream?.use { ins ->
                    val b = ByteArray(160)
                    val n = ins.read(b)
                    if (n > 0) String(b, 0, n, Charsets.ISO_8859_1).replace(Regex("\\s+"), " ") else ""
                } ?: ""
                "  [${uc.name}] HTTP $code type=$type server=$server" +
                    (if (loc != null) " location=$loc" else "") +
                    (if (body.isNotEmpty()) " body=$body" else "")
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            "  [${uc.name}] failed: ${e.javaClass.simpleName} ${e.message}"
        }
    }

    /** Asks the server for the same video as different clients, to find out which identity it accepts. */
    private fun clientMatrix(urls: List<String>, page: String): String {
        val chromeDesktop = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val chromeAndroid = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        val cases = listOf(
            UaCase("app-UA + base referer", UA, "$BASE/", false),
            UaCase("app-UA, no referer", UA, null, false),
            UaCase("chrome-desktop + page referer + origin", chromeDesktop, page, true),
            UaCase("chrome-android + page referer", chromeAndroid, page, false),
            UaCase("vlc", "VLC/3.0.20 LibVLC/3.0.20", null, false)
        )
        val sb = StringBuilder()
        val ch = java.net.CookieHandler.getDefault()
        val cookies = (ch as? java.net.CookieManager)?.cookieStore?.cookies
            ?.joinToString { it.name + "@" + it.domain } ?: "-"
        sb.append("cookies held by the app: ").append(cookies).append('\n')
        val tests = LinkedHashSet<String>()
        for (u in urls.take(1)) {
            tests.add(u)
            val base = u.substringBefore('?').let { b ->
                if (b.endsWith("/master.m3u8", true)) b.dropLast("/master.m3u8".length)
                else if (b.endsWith("/index.m3u8", true)) b.dropLast("/index.m3u8".length)
                else b
            }
            tests.add(base)
        }
        for (u in tests) {
            sb.append("client test: ").append(u).append('\n')
            for (uc in cases) sb.append(diag(u, uc)).append('\n')
        }
        return sb.toString()
    }

    /** Plain-text report of the last episode lookup: what was read, what was found, how the page gets its video. */
    fun debugReport(): String {
        val sb = StringBuilder()
        sb.append(dbgLog).append('\n')
        if (dbgFound.isNotEmpty()) sb.append(clientMatrix(dbgFound, dbgPageUrl))
        val html = dbgHtml
        if (html.isNotEmpty()) {
            sb.append(digest("page $dbgPageUrl", html, 25))
            sb.append("php names in page: ")
                .append(PHP_RE.findAll(html).map { it.value }.distinct().take(40).joinToString(", "))
                .append('\n')
            val srcs = SCRIPT_SRC_RE.findAll(html)
                .map { absolute(unescape(it.groupValues[1])) }
                .filter { it.startsWith(BASE) }
                .distinct().take(4).toList()
            sb.append("scripts: ").append(srcs.joinToString(", ")).append('\n')
            for (src in srcs) {
                val js = fetchText(src, 600_000) ?: continue
                sb.append(digest("script $src", js, 12))
                sb.append("php names in script: ")
                    .append(PHP_RE.findAll(js).map { it.value }.distinct().take(30).joinToString(", "))
                    .append('\n')
            }
        }
        return sb.toString().take(14000)
    }

    /**
     * Episode lookup, built the same way as the movie lookup (read the page, follow its player links,
     * take the video link), plus checks the movie path does not need:
     *  - only the episode's own page and the matching player page are read (no guessed ids),
     *  - a page that lists many videos is narrowed down to this episode,
     *  - every candidate is probed, so HTML / error answers go to the back of the list instead of
     *    being handed to ExoPlayer first,
     *  - download.php with the episode's own query stays as the very last resort.
     */
    fun episodeCandidates(page: String, season0: Int = 0, ep0: Int = 0): List<String> {
        if (MEDIA_PATH_RE.containsMatchIn(page.substringBefore('?'))) return listOf(page)

        val q = page.substringAfter('?', "").substringBefore('#')
        val season = if (season0 > 0) season0 else (SEASON_LINK_RE.find("?$q")?.groupValues?.get(1)?.toIntOrNull() ?: 0)
        val epNo = if (ep0 > 0) ep0 else (EP_PARAM_RE.find("?$q")?.groupValues?.get(1)?.toIntOrNull() ?: 0)
        val script = page.substringBefore('?').substringAfterLast('/').lowercase()
        val referer = if (page.startsWith("http")) page else "$BASE/"

        val log = StringBuilder("episode page=$page\nseason=$season ep=$epNo\n")
        dbgPageUrl = page
        dbgHtml = ""

        val pending = ArrayDeque<String>()
        pending.addLast(page)
        if (q.isNotEmpty() && script != "player.php") pending.addLast("$BASE/player.php?$q")

        val visited = HashSet<String>()
        val found = LinkedHashSet<String>()
        var inspected = 0

        while (pending.isNotEmpty() && inspected < 10 && found.isEmpty()) {
            val p = pending.removeFirst()
            if (!visited.add(p)) continue
            inspected++

            val html = fetchText(p)
            if (html == null) {
                log.append("could not read: $p\n")
                continue
            }
            log.append("read: $p (${html.length} chars)\n")
            if (dbgHtml.isEmpty()) {
                dbgHtml = html
                dbgPageUrl = p
            }

            val all = extractVideoUrls(html)
            log.append("video links on it: ${all.size}\n")
            found.addAll(forEpisode(all, season, epNo))
            if (found.isNotEmpty()) break

            for (link in innerLinks(html)) {
                if (link.startsWith(BASE) && !visited.contains(link)) pending.addLast(link)
            }
        }

        val good = ArrayList<String>()
        val bad = ArrayList<String>()
        for (u in found.take(6)) {
            val pr = probe(u, referer)
            log.append("probe $pr  $u\n")
            if (pr == Probe.BAD) bad.add(u) else good.add(u)
        }

        val out = LinkedHashSet<String>()
        out.addAll(good)
        out.addAll(found.drop(6))
        out.addAll(bad)
        if (q.isNotEmpty()) out.add("$BASE/download.php?$q")
        dbgLog = log.toString()
        dbgFound = found.toList()
        return out.toList()
    }

    /** Opens one episode page and returns its video address. */
    fun episodeVideo(page: String): String? {
        val visited = HashSet<String>()

        fun scan(url: String, depth: Int): String? {
            if (depth > 2 || !visited.add(url)) return null

            val html = try {
                Net.get(url)
            } catch (e: Exception) {
                return null
            }

            extractVideoUrls(html).firstOrNull()?.let { return it }

            for (link in innerLinks(html)) {
                scan(link, depth + 1)?.let { return it }
            }

            return null
        }

        scan(page, 0)?.let { return it }

        // Same trick as movies: try the player page, then let the server's download endpoint redirect.
        val q = page.substringAfter('?', "")

        if (q.isNotEmpty()) {
            scan("$BASE/player.php?$q", 0)?.let { return it }
            return "$BASE/download.php?$q"
        }

        return null
    }
}
