package com.cineplex.tv

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
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


    /** Episodes use the same lookup as movies, with the episode's own pages. */
    fun episodeCandidates(page: String): List<String> {
        val q = page.substringAfter('?', "")
        val pages = ArrayList<String>()
        if (q.isNotEmpty()) pages.add("$BASE/player.php?$q")
        pages.add(page)
        val epIds = Regex("(?:^|&)(?!series_id=)[a-z_]*=(\\d+)", RegexOption.IGNORE_CASE)
            .findAll(q).map { it.groupValues[1] }.distinct().toList()
        for (v in epIds) pages.add("$BASE/player.php?id=$v")
        val fallback = if (q.isNotEmpty()) "$BASE/download.php?$q" else page
        return resolve(pages, fallback).map { it.url }
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
