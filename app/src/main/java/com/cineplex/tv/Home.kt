package com.cineplex.tv

import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import org.json.JSONObject

// ───────────────────────── Sections & side rail ─────────────────────────

class Section(val id: String, val emoji: String, val label: String)

fun sectionsList(): List<Section> = listOf(
    Section("home", "🏠", "Home"),
    Section("movies", "🎬", "Movies"),
    Section("tv", "📺", "TV Series"),
    Section("fav", "⭐", "My List"),
    Section("search", "🔍", "Search"),
    Section("settings", "⚙️", "Settings")
)

/** Slim icon rail that expands with labels while it has focus. */
@Composable
fun Rail(sections: List<Section>, current: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val w by animateDpAsState(if (expanded) 230.dp else 76.dp, label = "railWidth")
    Column(
        modifier
            .width(w)
            .fillMaxHeight()
            .background(Color(0xFF101014))
            .onFocusChanged { st -> expanded = st.hasFocus }
            .padding(vertical = 24.dp, horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
    ) {
        for (s in sections) {
            FocusBox(Modifier.fillMaxWidth(), onClick = { onSelect(s.id) }) {
                Row(
                    Modifier.fillMaxWidth().background(if (s.id == current) ACCENT else CARD)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.emoji, fontSize = 22.sp)
                    if (expanded) Text(
                        s.label, color = Color.White, fontSize = 17.sp, maxLines = 1,
                        modifier = Modifier.padding(start = 14.dp)
                    )
                }
            }
        }
    }
}

// ───────────────────────── Home: hero + shelves ─────────────────────────

class Shelf(val title: String, val items: List<Item>, val frac: Map<String, Float> = emptyMap())

fun localShelves(app: App): List<Shelf> {
    val out = ArrayList<Shelf>()
    val cw = app.db.continueWatching(20)
    if (cw.isNotEmpty()) {
        val fr = HashMap<String, Float>()
        for (r in cw) fr[r.item.href] = if (r.dur > 1) (r.pos.toFloat() / r.dur.toFloat()).coerceIn(0f, 1f) else 0f
        out.add(Shelf("Continue watching", cw.map { r -> r.item }, fr))
    }
    val fav = app.db.favorites()
    if (fav.isNotEmpty()) out.add(Shelf("My list", fav.take(30)))
    return out
}

suspend fun remoteShelves(app: App): List<Shelf> {
    val h = app.repo.home()
    val out = ArrayList<Shelf>()
    if (h.first.isNotEmpty()) out.add(Shelf("Weekly top trending", h.first))
    if (h.second.isNotEmpty()) out.add(Shelf("Latest TV series", h.second))
    if (h.third.isNotEmpty()) out.add(Shelf("Latest movies", h.third))
    return out
}

@Composable
fun Hero(item: Item?, meta: JSONObject?) {
    Box(Modifier.fillMaxWidth().height(250.dp)) {
        val bg = meta?.optString("background").orEmpty()
        if (bg.isNotEmpty()) AsyncImage(
            model = bg, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.6f,
            modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.74f).fillMaxHeight()
        )
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(BG, BG.copy(alpha = 0.85f), Color.Transparent))))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, BG))))
        if (item != null) {
            val rating = meta?.optString("imdbRating").orEmpty()
            val info = listOfNotNull(
                if (item.year > 0) item.year.toString() else null,
                item.quality.ifEmpty { null }, meta?.optString("runtime")?.ifEmpty { null },
                if (rating.isNotEmpty()) "IMDb $rating" else null
            ).joinToString("  ·  ")
            Column(
                Modifier.align(Alignment.CenterStart).padding(start = 36.dp).widthIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(item.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(info, color = Color.LightGray, fontSize = 14.sp)
                Text(meta?.optString("description").orEmpty(), color = Color.White, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun HomeRows(
    app: App, shelves: List<Shelf>, loading: Boolean, error: String?,
    restoreKey: String?, onRestored: () -> Unit, onOpen: (Item) -> Unit
) {
    var focused by remember { mutableStateOf<Item?>(null) }
    var meta by remember { mutableStateOf<JSONObject?>(null) }
    val shown = focused ?: shelves.firstOrNull()?.items?.firstOrNull()
    // Hero details load only after focus settles, so scrolling fires no requests.
    LaunchedEffect(shown?.href) {
        meta = null
        val s = shown
        if (s != null) {
            delay(350)
            meta = Meta.get(app.db, s)
        }
    }
    Column(Modifier.fillMaxSize().background(BG)) {
        Hero(shown, meta)
        if (error != null) Text(error, color = Color(0xFFFFB4A9), modifier = Modifier.padding(horizontal = 36.dp, vertical = 8.dp))
        else if (shelves.isEmpty()) Text(
            if (loading) "Loading…" else "Nothing to show yet.",
            color = Color.Gray, modifier = Modifier.padding(36.dp)
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(shelves.size) { r ->
                val sh = shelves[r]
                Column {
                    Text(
                        sh.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 36.dp, bottom = 8.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 36.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(sh.items.size) { i ->
                            val card = sh.items[i]
                            PosterCard(
                                app, card, Modifier.width(128.dp),
                                frac = sh.frac[card.href],
                                restore = card.href == restoreKey,
                                onRestored = onRestored,
                                onFocus = { focused = card },
                                onClick = { onOpen(card) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── Poster card ─────────────────────────

@Composable
fun PosterCard(
    app: App, item: Item, modifier: Modifier = Modifier, frac: Float? = null,
    restore: Boolean = false, onRestored: () -> Unit = {}, onFocus: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    var alt by remember(item.href) { mutableStateOf<String?>(null) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(item.href) {
        // No poster on the site: fall back to Cinemeta's poster.
        if (item.poster == null) alt = Meta.get(app.db, item)?.optString("poster")?.ifEmpty { null }
    }
    LaunchedEffect(restore) {
        if (restore) {
            delay(80)
            runCatching { fr.requestFocus() }
            onRestored()
        }
    }
    val p = item.poster
    val url = if (p != null) Site.absolute(p) else alt
    Column(modifier) {
        FocusBox(Modifier.fillMaxWidth().aspectRatio(2f / 3f), focusRequester = fr, onFocus = onFocus, onClick = onClick) {
            Box(Modifier.fillMaxSize().background(CARD), contentAlignment = Alignment.Center) {
                // Title sits underneath, so it shows if the image is missing or fails to load.
                Text(item.title, color = Color.LightGray, textAlign = TextAlign.Center, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
                if (url != null) AsyncImage(
                    model = url, contentDescription = item.title,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
                if (frac != null && frac > 0f) Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth(frac).height(4.dp).background(ACCENT)
                )
            }
        }
        Text(item.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(
            listOf(if (item.year > 0) item.year.toString() else "", item.quality).filter { s -> s.isNotEmpty() }.joinToString(" · "),
            color = Color.Gray, fontSize = 11.sp
        )
    }
}

// ───────────────────────── Grid with categories / sort / filter ─────────────────────────

@Composable
fun Chip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    FocusBox(onClick = onClick) {
        Text(
            text, color = if (selected) Color.Black else Color.White, fontSize = 15.sp,
            modifier = Modifier.background(if (selected) Color.White else CARD).padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

private val QUALITIES = listOf<String?>(null, "2160p", "1080p", "720p", "480p")

private fun sortLabel(s: SortBy): String = when (s) {
    SortBy.ADDED -> "Latest"
    SortBy.YEAR -> "Year"
    SortBy.TITLE -> "A–Z"
}

@Composable
fun GridScreen(
    app: App, title: String, list: List<Item>, loading: Boolean, gridState: LazyGridState,
    cats: List<String>?, cat: String, onCat: (String) -> Unit,
    filter: ListFilter?, onFilter: (ListFilter) -> Unit,
    query: String?, onQuery: (String) -> Unit,
    onMore: (() -> Unit)?, errorText: String?,
    emptyText: String, restoreKey: String?, onRestored: () -> Unit, onOpen: (Item) -> Unit
) {
    Column(Modifier.fillMaxSize().background(BG).padding(top = 18.dp)) {
        Text(
            title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 36.dp, bottom = 8.dp)
        )
        if (query != null) OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true, label = { Text("Search movies & series") },
            modifier = Modifier.padding(horizontal = 36.dp, vertical = 6.dp).fillMaxWidth(0.5f)
        )
        if (cats != null) LazyRow(
            contentPadding = PaddingValues(horizontal = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            items(cats.size + 1) { i ->
                val name = if (i == 0) "All" else cats[i - 1]
                val value = if (i == 0) "" else cats[i - 1]
                Chip(name, selected = value == cat) { onCat(value) }
            }
        }
        if (filter != null) Row(
            Modifier.padding(start = 36.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Chip("Sort: " + sortLabel(filter.sort)) {
                onFilter(filter.copy(sort = SortBy.values()[(filter.sort.ordinal + 1) % SortBy.values().size]))
            }
            Chip("Quality: " + (filter.quality ?: "All")) {
                onFilter(filter.copy(quality = QUALITIES[(QUALITIES.indexOf(filter.quality) + 1) % QUALITIES.size]))
            }
            if (onMore != null) Chip("Load more") { onMore() }
        }
        if (list.isEmpty()) Text(
            errorText ?: (if (loading) "Loading…" else emptyText),
            color = if (errorText != null) Color(0xFFFFB4A9) else Color.Gray, modifier = Modifier.padding(36.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp), state = gridState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(36.dp, 12.dp, 36.dp, 36.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            gridItems(list, key = { it.href }) { card ->
                PosterCard(
                    app, card, restore = card.href == restoreKey, onRestored = onRestored,
                    onClick = { onOpen(card) }
                )
            }
        }
    }
}

// ───────────────────────── Settings ─────────────────────────

private fun nextOf(list: List<String>, cur: String): String = list[(list.indexOf(cur) + 1) % list.size]

private fun langName(code: String): String = when (code) {
    "en" -> "English"
    "hi" -> "Hindi"
    "bn" -> "Bangla"
    "off" -> "Off"
    else -> code
}

@Composable
fun SettingsScreen(app: App) {
    val ctx = LocalContext.current
    var audio by remember { mutableStateOf(app.prefs.audio) }
    var subs by remember { mutableStateOf(app.prefs.subs) }
    Column(
        Modifier.fillMaxSize().background(BG).padding(36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Btn("Audio language: " + langName(audio)) {
            audio = nextOf(listOf("en", "hi", "bn"), audio)
            app.prefs.audio = audio
        }
        Btn("Subtitles: " + langName(subs)) {
            subs = nextOf(listOf("off", "en", "bn", "hi"), subs)
            app.prefs.subs = subs
        }
        Text("Applies the next time you start a video.", color = Color.Gray, fontSize = 13.sp)
        Btn("Reload lists (clear cache)") {
            app.repo.clearCache()
            Toast.makeText(ctx, "Cache cleared — lists will reload", Toast.LENGTH_LONG).show()
        }
        Text("Server: $BASE", color = Color.DarkGray, fontSize = 12.sp)
    }
}
