package com.cineplex.tv

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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

class Section(val id: String, val label: String)

fun sectionsList(): List<Section> = listOf(
    Section("home", "Home"),
    Section("movies", "Movies"),
    Section("tv", "TV Series"),
    Section("fav", "My List"),
    Section("search", "Search"),
    Section("settings", "Settings")
)

/** Thin line icons drawn by hand, so no icon library is needed. */
@Composable
fun RailIcon(id: String, tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (id) {
            "home" -> {
                val p = Path().apply {
                    moveTo(w * 0.12f, h * 0.48f)
                    lineTo(w * 0.5f, h * 0.14f)
                    lineTo(w * 0.88f, h * 0.48f)
                    moveTo(w * 0.22f, h * 0.42f)
                    lineTo(w * 0.22f, h * 0.86f)
                    lineTo(w * 0.78f, h * 0.86f)
                    lineTo(w * 0.78f, h * 0.42f)
                }
                drawPath(p, color = tint, style = st)
            }
            "movies" -> {
                drawRoundRect(
                    color = tint, topLeft = Offset(w * 0.1f, h * 0.2f), size = Size(w * 0.8f, h * 0.6f),
                    cornerRadius = CornerRadius(w * 0.1f, w * 0.1f), style = st
                )
                val tri = Path().apply {
                    moveTo(w * 0.42f, h * 0.36f)
                    lineTo(w * 0.42f, h * 0.64f)
                    lineTo(w * 0.66f, h * 0.5f)
                    close()
                }
                drawPath(tri, color = tint)
            }
            "tv" -> {
                drawRoundRect(
                    color = tint, topLeft = Offset(w * 0.08f, h * 0.16f), size = Size(w * 0.84f, h * 0.55f),
                    cornerRadius = CornerRadius(w * 0.1f, w * 0.1f), style = st
                )
                drawLine(
                    color = tint, start = Offset(w * 0.3f, h * 0.88f), end = Offset(w * 0.7f, h * 0.88f),
                    strokeWidth = sw, cap = StrokeCap.Round
                )
            }
            "fav" -> {
                drawLine(color = tint, start = Offset(w * 0.5f, h * 0.18f), end = Offset(w * 0.5f, h * 0.82f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(color = tint, start = Offset(w * 0.18f, h * 0.5f), end = Offset(w * 0.82f, h * 0.5f), strokeWidth = sw, cap = StrokeCap.Round)
            }
            "search" -> {
                drawCircle(color = tint, radius = w * 0.28f, center = Offset(w * 0.44f, h * 0.44f), style = st)
                drawLine(color = tint, start = Offset(w * 0.66f, h * 0.66f), end = Offset(w * 0.88f, h * 0.88f), strokeWidth = sw, cap = StrokeCap.Round)
            }
            else -> {
                val ys = listOf(0.25f, 0.5f, 0.75f)
                val xs = listOf(0.35f, 0.65f, 0.45f)
                for (i in ys.indices) {
                    drawLine(color = tint, start = Offset(w * 0.12f, h * ys[i]), end = Offset(w * 0.88f, h * ys[i]), strokeWidth = sw, cap = StrokeCap.Round)
                    drawCircle(color = tint, radius = w * 0.085f, center = Offset(w * xs[i], h * ys[i]))
                }
            }
        }
    }
}

/** Slim icon rail that opens into a labelled panel while it has focus. */
@Composable
fun Rail(sections: List<Section>, current: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val w by animateDpAsState(if (expanded) 236.dp else 76.dp, label = "railWidth")
    val panel = if (expanded) {
        Brush.horizontalGradient(listOf(Color(0xFA050507), Color(0xEB050507), Color.Transparent))
    } else {
        Brush.horizontalGradient(listOf(Color(0xFF08080B), Color(0xFF08080B)))
    }
    Column(
        modifier
            .width(w)
            .fillMaxHeight()
            .background(panel)
            .onFocusChanged { st -> expanded = st.hasFocus }
            .padding(vertical = 22.dp, horizontal = 12.dp)
    ) {
        Row(Modifier.padding(start = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(ACCENT),
                contentAlignment = Alignment.Center
            ) {
                Text("C", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }
            if (expanded) Text(
                "CINEPLEX", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp, maxLines = 1, modifier = Modifier.padding(start = 12.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in sections) {
                val selected = s.id == current
                FocusSurface(
                    Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    focusScale = 1f, ring = false, onClick = { onSelect(s.id) }
                ) { focused ->
                    val fill = when {
                        focused -> Color.White
                        selected -> Color(0x26FFFFFF)
                        else -> Color.Transparent
                    }
                    val fg = when {
                        focused -> Color.Black
                        selected -> Color.White
                        else -> Color(0xFFB4B4C0)
                    }
                    Row(
                        Modifier.fillMaxWidth().background(fill).padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RailIcon(s.id, fg)
                        if (expanded) Text(
                            s.label, color = fg, fontSize = 16.sp, maxLines = 1,
                            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                    if (selected && !focused) Box(
                        Modifier.align(Alignment.CenterStart).width(4.dp).height(22.dp)
                            .clip(RoundedCornerShape(2.dp)).background(ACCENT)
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
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
    Box(Modifier.fillMaxWidth().height(165.dp)) {
        val bg = meta?.optString("background").orEmpty()
        if (bg.isNotEmpty()) AsyncImage(
            model = bg, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.7f,
            modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.78f).fillMaxHeight()
        )
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(BG, BG.copy(alpha = 0.88f), Color.Transparent))))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, BG))))
        if (item != null) {
            val rating = meta?.optString("imdbRating").orEmpty()
            val info = listOfNotNull(
                if (item.year > 0) item.year.toString() else null,
                item.quality.ifEmpty { null }, meta?.optString("runtime")?.ifEmpty { null },
                if (rating.isNotEmpty()) "IMDb $rating" else null
            ).joinToString("   ·   ")
            Column(
                Modifier.align(Alignment.CenterStart).padding(start = 40.dp).widthIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    if (item.series) "S E R I E S" else "F I L M",
                    color = ACCENT, fontSize = 10.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    item.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(info, color = Color(0xFFD0D0DA), fontSize = 12.sp)
                Text(
                    meta?.optString("description").orEmpty(), color = Color(0xFFE6E6EE), fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
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
        if (error != null) Text(error, color = Color(0xFFFFB4A9), modifier = Modifier.padding(horizontal = 40.dp, vertical = 8.dp))
        else if (shelves.isEmpty()) Text(
            if (loading) "Loading…" else "Nothing to show yet.",
            color = Color.Gray, modifier = Modifier.padding(40.dp)
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            items(shelves.size) { r ->
                val sh = shelves[r]
                Column {
                    Text(
                        sh.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 40.dp, bottom = 10.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 40.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(sh.items.size) { i ->
                            val card = sh.items[i]
                            PosterCard(
                                app, card, Modifier.width(140.dp),
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
        FocusSurface(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f), focusRequester = fr, onFocus = onFocus,
            shape = RoundedCornerShape(14.dp), onClick = onClick
        ) { _ ->
            Box(Modifier.fillMaxSize().background(CARD), contentAlignment = Alignment.Center) {
                // Title sits underneath, so it shows if the image is missing or fails to load.
                Text(item.title, color = Color.LightGray, textAlign = TextAlign.Center, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
                if (url != null) AsyncImage(
                    model = url, contentDescription = item.title,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
                if (item.quality.isNotEmpty()) Text(
                    item.quality, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .clip(RoundedCornerShape(6.dp)).background(Color(0xAA000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                if (frac != null && frac > 0f) Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF))
                )
                if (frac != null && frac > 0f) Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth(frac).height(4.dp).background(ACCENT)
                )
            }
        }
        Text(
            item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)
        )
        if (item.year > 0) Text(item.year.toString(), color = TEXT_DIM, fontSize = 11.sp)
    }
}

// ───────────────────────── Grid with categories / year / genre / sort ─────────────────────────

@Composable
fun Chip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    FocusSurface(shape = RoundedCornerShape(50), onClick = onClick) { _ ->
        Text(
            text, color = if (selected) Color.Black else Color(0xFFD8D8E0), fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.background(if (selected) Color.White else CARD).padding(horizontal = 18.dp, vertical = 8.dp)
        )
    }
}

private class Opt(val label: String, val value: String)

private val GENRES = listOf(
    "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary", "Drama",
    "Family", "Fantasy", "History", "Horror", "Music", "Mystery", "Romance", "Sci-Fi",
    "Sport", "Thriller", "War", "Western"
)

private fun yearOptions(): List<Int> {
    val now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    return (now downTo 1980).toList()
}

private fun sortLabel(s: SortBy): String = when (s) {
    SortBy.ADDED -> "Latest"
    SortBy.YEAR -> "Release year"
    SortBy.TITLE -> "A–Z"
}

/** Full-screen choice list used by the Genre / Year / Sort pills. */
@Composable
private fun PickerOverlay(
    title: String, options: List<Opt>, selected: String,
    onPick: (String) -> Unit, onDismiss: () -> Unit
) {
    val st = rememberLazyGridState()
    val selIdx = options.indexOfFirst { o -> o.value == selected }.coerceAtLeast(0)
    val fr = remember { FocusRequester() }
    BackHandler { onDismiss() }
    LaunchedEffect(Unit) {
        st.scrollToItem(maxOf(0, selIdx - 4))
        delay(120)
        runCatching { fr.requestFocus() }
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xEB0B0B0F)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.fillMaxWidth(0.62f)) {
            Text(
                title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 6.dp, bottom = 16.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(130.dp), state = st,
                modifier = Modifier.heightIn(max = 330.dp),
                contentPadding = PaddingValues(6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(options.size) { i ->
                    val o = options[i]
                    val sel = i == selIdx
                    FocusSurface(
                        Modifier.fillMaxWidth(),
                        focusRequester = if (sel) fr else null,
                        shape = RoundedCornerShape(50),
                        onClick = { onPick(o.value) }
                    ) { _ ->
                        Text(
                            o.label, color = if (sel) Color.Black else Color.White, fontSize = 15.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                            textAlign = TextAlign.Center, maxLines = 1,
                            modifier = Modifier.fillMaxWidth().background(if (sel) Color.White else SURFACE)
                                .padding(horizontal = 14.dp, vertical = 11.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GridScreen(
    app: App, title: String, list: List<Item>, loading: Boolean, gridState: LazyGridState,
    cats: List<String>?, cat: String, onCat: (String) -> Unit,
    filter: ListFilter?, onFilter: (ListFilter) -> Unit,
    query: String?, onQuery: (String) -> Unit,
    onMore: (() -> Unit)?, errorText: String?,
    emptyText: String, restoreKey: String?, onRestored: () -> Unit, onOpen: (Item) -> Unit,
    progressText: String? = null
) {
    var picker by remember { mutableStateOf<String?>(null) }

    // Endless scrolling: when the last visible card is close to the end of the list, load the next pages.
    if (onMore != null) {
        val last by remember { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
        val more by rememberUpdatedState(onMore)
        LaunchedEffect(list.size, last, loading) {
            if (!loading && last >= list.size - 12) more()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().background(BG).padding(top = 24.dp)
                .then(if (picker != null) Modifier.focusProperties { canFocus = false } else Modifier)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.weight(1f))
                if (filter != null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip("Genre: " + (filter.genre.ifEmpty { "All" }) + "  ▾", selected = filter.genre.isNotEmpty()) { picker = "genre" }
                    Chip("Year: " + (if (filter.year > 0) filter.year.toString() else "All") + "  ▾", selected = filter.year > 0) { picker = "year" }
                    Chip("Sort: " + sortLabel(filter.sort) + "  ▾") { picker = "sort" }
                }
            }
            if (progressText != null) Text(
                progressText, color = TEXT_DIM, fontSize = 13.sp,
                modifier = Modifier.padding(start = 40.dp, top = 2.dp)
            )
            if (query != null) OutlinedTextField(
                value = query, onValueChange = onQuery, singleLine = true, label = { Text("Search movies & series") },
                shape = RoundedCornerShape(50),
                modifier = Modifier.padding(horizontal = 40.dp, vertical = 8.dp).fillMaxWidth(0.5f)
            )
            if (cats != null) LazyRow(
                contentPadding = PaddingValues(horizontal = 40.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(cats.size + 1) { i ->
                    val name = if (i == 0) "All" else cats[i - 1]
                    val value = if (i == 0) "" else cats[i - 1]
                    Chip(name, selected = value == cat) { onCat(value) }
                }
            }
            if (list.isEmpty()) Text(
                errorText ?: (if (loading || progressText != null) "Loading…" else emptyText),
                color = if (errorText != null) Color(0xFFFFB4A9) else Color.Gray, modifier = Modifier.padding(40.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp), state = gridState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(40.dp, 14.dp, 40.dp, 40.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(26.dp)
            ) {
                gridItems(list, key = { it.href }) { card ->
                    PosterCard(
                        app, card, restore = card.href == restoreKey, onRestored = onRestored,
                        onClick = { onOpen(card) }
                    )
                }
            }
        }

        val f = filter
        when (picker) {
            "genre" -> if (f != null) PickerOverlay(
                "Genre",
                listOf(Opt("All genres", "")) + GENRES.map { g -> Opt(g, g) },
                f.genre,
                { v -> onFilter(f.copy(genre = v)); picker = null },
                { picker = null }
            )
            "year" -> if (f != null) PickerOverlay(
                "Release year",
                listOf(Opt("All years", "0")) + yearOptions().map { y -> Opt(y.toString(), y.toString()) },
                f.year.toString(),
                { v -> onFilter(f.copy(year = v.toIntOrNull() ?: 0)); picker = null },
                { picker = null }
            )
            "sort" -> if (f != null) PickerOverlay(
                "Sort by",
                SortBy.values().map { s -> Opt(sortLabel(s), s.name) },
                f.sort.name,
                { v -> onFilter(f.copy(sort = SortBy.valueOf(v))); picker = null },
                { picker = null }
            )
            else -> {}
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
