package com.cineplex.tv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class QItem(
    val key: String,
    val url: String,
    val title: String,
    val titleHref: String,
    /** Fallback links tried in order if [url] fails (used for episodes). */
    val alts: List<String> = emptyList()
)

private val SEASON_RE = Regex("season=(\\d+)")

@Composable
fun Btn(text: String, modifier: Modifier = Modifier, fr: FocusRequester? = null, primary: Boolean = false, onClick: () -> Unit) {
    FocusBox(modifier, focusRequester = fr, onClick = onClick) {
        Text(
            text, color = if (primary) Color.Black else Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.background(if (primary) Color.White else CARD).padding(horizontal = 22.dp, vertical = 12.dp)
        )
    }
}

@Composable
fun DetailScreen(app: App, item: Item, onPlay: (List<QItem>, Int, Long) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var meta by remember { mutableStateOf<JSONObject?>(null) }
    var prog by remember { mutableStateOf<Map<String, Pair<Long, Long>>>(emptyMap()) }
    var last by remember { mutableStateOf<Triple<String, Long, Long>?>(null) }
    var fav by remember { mutableStateOf(false) }
    var srcs by remember { mutableStateOf<List<Src>>(emptyList()) }
    var seasons by remember { mutableStateOf<List<Season>>(emptyList()) }
    var season by remember { mutableIntStateOf(0) }
    var eps by remember { mutableStateOf<List<Ep>>(emptyList()) }
    var epsSeason by remember { mutableIntStateOf(-1) }
    var epsLoaded by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var errMsg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var focusDone by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    val leftScroll = rememberScrollState()

    LaunchedEffect(item.href, reload) {
        loaded = false
        failed = false
        val local = withContext(Dispatchers.IO) {
            Triple(app.db.progressFor(item.href), app.db.isFav(item.href), app.db.lastProgress(item.href))
        }
        prog = local.first
        fav = local.second
        last = local.third
        try {
            if (item.series) {
                val info = app.repo.seriesInfo(item)
                val lp0 = local.third
                // Open on the season you were last watching.
                val lpSeason = if (lp0 == null) null else SEASON_RE.find(lp0.first)?.groupValues?.get(1)?.toIntOrNull()
                val target = if (lpSeason != null && info.seasons.any { s -> s.num == lpSeason }) lpSeason else info.defaultSeason
                val list = if (target == info.defaultSeason) info.episodes else app.repo.seasonEpisodes(item, target)
                seasons = info.seasons
                season = info.seasons.indexOfFirst { s -> s.num == target }.coerceAtLeast(0)
                eps = list
                epsSeason = target
                epsLoaded = true
            } else {
                srcs = app.repo.sources(item)
            }
        } catch (e: Exception) {
            failed = true
            errMsg = e.message ?: e.javaClass.simpleName
        }
        loaded = true
        meta = Meta.get(app.db, item)
    }
    LaunchedEffect(season, seasons) {
        val sn = seasons.getOrNull(season)
        if (sn != null && sn.num != epsSeason) {
            epsLoaded = false
            eps = runCatching { app.repo.seasonEpisodes(item, sn.num) }.getOrDefault(emptyList())
            epsSeason = sn.num
            epsLoaded = true
        }
    }
    val ready = if (item.series) eps.isNotEmpty() else srcs.isNotEmpty()
    LaunchedEffect(ready) {
        if (ready && !focusDone) {
            focusDone = true
            delay(120)
            runCatching { playFocus.requestFocus() }
        }
    }

    // ---- actions ----
    fun playMovie(resumeAt: Long) {
        scope.launch {
            busy = true
            // The video link is signed and short-lived, so fetch a fresh one right before playing.
            val fresh = runCatching { app.repo.sources(item) }.getOrDefault(srcs)
            busy = false
            val s = fresh.firstOrNull()
            if (s == null) Toast.makeText(ctx, "Couldn't find the video link", Toast.LENGTH_LONG).show()
            else onPlay(listOf(QItem(item.href, s.url, item.title, item.href)), 0, resumeAt)
        }
    }

    fun playEpisodes(from: Int, resumeAt: Long) {
        scope.launch {
            busy = true
            val chunk = eps.drop(from).take(4)   // this episode + the next 3, so auto-advance works
            val cands = chunk.map { e ->
                async { runCatching { app.repo.episodeCandidates(e.page) }.getOrDefault(emptyList()) }
            }.awaitAll()
            val q = ArrayList<QItem>()
            for (i in chunk.indices) {
                val c = cands[i]
                // Preserve every remaining episode source so PlayerScreen can retry them.
                if (c.isNotEmpty()) q.add(QItem(chunk[i].page, c[0], item.title + " " + chunk[i].label, item.href, c.drop(1)))
            }
            busy = false
            if (q.isEmpty()) Toast.makeText(ctx, "Couldn't find the video for this episode", Toast.LENGTH_LONG).show()
            else onPlay(q, 0, if (chunk.isNotEmpty() && q[0].key == chunk[0].page) resumeAt else 0L)
        }
    }

    // Series: work out what "Play" should do (resume / next episode / first episode).
    val lp = last
    var nextIdx = 0
    var resumeEp = 0L
    var hasHistory = false
    if (lp != null) {
        val li = eps.indexOfFirst { e -> e.page == lp.first }
        if (li >= 0) {
            hasHistory = true
            if (lp.second > 0) {
                nextIdx = li
                resumeEp = lp.second
            } else {
                nextIdx = if (li + 1 < eps.size) li + 1 else li
            }
        }
    }
    val nextEp = eps.getOrNull(nextIdx)

    val m = meta
    val genres = m?.optJSONArray("genres")?.let { a -> (0 until a.length()).joinToString(", ") { i -> a.getString(i) } } ?: ""
    val bg = m?.optString("background").orEmpty()
    val rating = m?.optString("imdbRating").orEmpty()
    val info = listOfNotNull(
        if (item.year > 0) item.year.toString() else null,
        item.quality.ifEmpty { null }, m?.optString("runtime")?.ifEmpty { null },
        if (rating.isNotEmpty()) "IMDb $rating" else null
    ).joinToString("  ·  ")
    val favLabel = if (fav) "Remove from My List" else "Add to My List"

    Box(Modifier.fillMaxSize().background(BG)) {
        if (bg.isNotEmpty()) AsyncImage(
            model = bg, contentDescription = null, contentScale = ContentScale.Crop,
            alpha = 0.28f, modifier = Modifier.fillMaxSize()
        )
        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            // Left column scrolls, and the buttons sit right under the title so they are always visible.
            Column(
                Modifier.weight(if (item.series) 0.42f else 0.7f).verticalScroll(leftScroll),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(item.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(info, color = Color.LightGray, fontSize = 15.sp)
                if (!item.series) {
                    val src = srcs.firstOrNull()
                    val saved = prog[item.href]
                    val resumeAt = if (saved != null && saved.first > 0) saved.first else 0L
                    val playLabel = when {
                        busy -> "Starting…"
                        !loaded -> "Finding video…"
                        src == null -> "No video found"
                        resumeAt > 0 -> "Resume " + fmtTime(resumeAt)
                        else -> "Play"
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Btn(playLabel, fr = playFocus, primary = true) { if (src != null && !busy) playMovie(resumeAt) }
                        Btn("Open in VLC") { if (src != null) openInVlc(ctx, src.url, item.title) }
                    }
                } else {
                    val epName = if (nextEp == null) "" else nextEp.label + " " + nextEp.name.take(24)
                    val playLabel = when {
                        busy -> "Starting…"
                        nextEp == null && epsLoaded -> "No episodes found"
                        nextEp == null -> "Loading…"
                        resumeEp > 0 -> "Resume " + nextEp.label + "  " + fmtTime(resumeEp)
                        hasHistory -> "Play next: " + epName
                        else -> "Play " + epName
                    }
                    Btn(playLabel, fr = playFocus, primary = true) { if (nextEp != null && !busy) playEpisodes(nextIdx, resumeEp) }
                }
                Btn(favLabel) { fav = app.db.toggleFav(item.href) }
                if (loaded && failed) {
                    Text("Couldn't reach the server. $errMsg", color = Color(0xFFFFB4A9), fontSize = 14.sp)
                    Btn("Retry") { reload++ }
                } else if (loaded && item.series && epsLoaded && eps.isEmpty()) {
                    Text("No episodes could be read from the series page.", color = Color(0xFFFFB4A9), fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Btn("Retry") { reload++ }
                        Btn("Open page in browser") {
                            openInBrowser(ctx, BASE + "/watch.php?series_id=" + item.href.substringAfter("id="))
                        }
                    }
                } else if (loaded && !item.series && srcs.isEmpty()) {
                    Text("No video link was found on the player page.", color = Color(0xFFFFB4A9), fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Btn("Retry") { reload++ }
                        Btn("Open page in browser") {
                            openInBrowser(ctx, BASE + "/player.php?id=" + item.href.substringAfter("id="))
                        }
                    }
                }
                if (genres.isNotEmpty()) Text(genres, color = Color.Gray, fontSize = 14.sp)
                Text(m?.optString("description").orEmpty(), color = Color.White, fontSize = 15.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
                if (m != null) Text("Details: Cinemeta", color = Color.DarkGray, fontSize = 11.sp)
            }
            if (item.series) Column(Modifier.weight(0.58f)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    items(seasons.size) { i ->
                        Btn(seasons[i].name, primary = i == season) { season = i }
                    }
                }
                if (!epsLoaded && seasons.isNotEmpty()) Text("Loading episodes…", color = Color.Gray)
                if (epsLoaded && eps.isEmpty() && !failed) Text("No episodes found for this season.", color = Color.Gray)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(eps.size) { i ->
                        val e = eps[i]
                        val p = prog[e.page]
                        val watched = p != null && p.first == 0L && p.second > 0
                        val resumeAt = if (p != null && p.first > 0) p.first else 0L
                        val label = (if (watched) "✓ " else "▶ ") + e.label + "  " + e.name.take(44) +
                            (if (resumeAt > 0) "   (resume " + fmtTime(resumeAt) + ")" else "")
                        FocusBox(Modifier.fillMaxWidth(), onClick = { if (!busy) playEpisodes(i, resumeAt) }) {
                            Text(
                                label, color = Color.White, fontSize = 16.sp, maxLines = 1,
                                modifier = Modifier.fillMaxWidth().background(CARD).padding(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

fun openInVlc(ctx: Context, url: String, title: String) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*")
        .setPackage("org.videolan.vlc").putExtra("title", title)
    try {
        ctx.startActivity(i)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "Install VLC for Android from the Play Store", Toast.LENGTH_LONG).show()
    }
}

fun openInBrowser(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Toast.makeText(ctx, "No browser installed on this TV", Toast.LENGTH_LONG).show()
    }
}
