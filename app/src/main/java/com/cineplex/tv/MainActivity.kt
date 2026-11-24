package com.cineplex.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

sealed class Screen {
    data class Detail(val item: Item) : Screen()
    data class Play(val queue: List<QItem>, val start: Int, val pos: Long) : Screen()
}

class MainActivity : ComponentActivity() {
    private lateinit var app: App
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = App(applicationContext)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Root(app) } }
    }
}

@Composable
fun Root(app: App) {
    val stack = remember { mutableStateListOf<Screen>() }
    var section by rememberSaveable { mutableStateOf("home") }
    var query by rememberSaveable { mutableStateOf("") }
    var sortName by rememberSaveable { mutableStateOf(SortBy.ADDED.name) }
    var year by rememberSaveable { mutableIntStateOf(0) }
    var genre by rememberSaveable { mutableStateOf("") }
    var movieCat by rememberSaveable { mutableStateOf("") }
    var tvCat by rememberSaveable { mutableStateOf("") }
    var maxPages by rememberSaveable { mutableIntStateOf(4) }
    var restoreKey by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<Item>>(emptyList()) }
    var itemsKey by remember { mutableStateOf("") }
    var shelves by remember { mutableStateOf<List<Shelf>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var endReached by remember { mutableStateOf(false) }
    var loadedPages by remember { mutableIntStateOf(0) }
    val genreMap = remember { mutableStateMapOf<String, List<String>>() }
    val gridState = rememberLazyGridState()
    val sections = remember { sectionsList() }
    val homeVisible = stack.isEmpty()
    val filter = ListFilter(SortBy.valueOf(sortName), year, genre)
    val curKey = when (section) {
        "movies" -> "movies|$movieCat"
        "tv" -> "tv|$tvCat"
        else -> section
    }

    LaunchedEffect(section, movieCat, tvCat, maxPages, query, homeVisible) {
        if (!homeVisible) return@LaunchedEffect
        error = null
        if (section == "home") {
            loading = true
            val local = withContext(Dispatchers.IO) { localShelves(app) }
            shelves = local
            try {
                val remote = remoteShelves(app)
                shelves = local + remote
            } catch (e: Exception) {
                error = "Couldn't reach the server (" + BASE + "). Check your Wi-Fi / ISP connection."
            }
            loading = false
        } else if (section == "fav") {
            val r = withContext(Dispatchers.IO) { app.db.favorites() }
            items = r
            itemsKey = curKey
            loading = false
        } else if (section == "search") {
            delay(350)
            if (query.isBlank()) {
                items = emptyList()
                itemsKey = curKey
                loading = false
            } else {
                loading = true
                try {
                    items = app.repo.search(query.trim())
                    itemsKey = curKey
                } catch (e: Exception) {
                    error = "Search failed."
                }
                loading = false
            }
        } else if (section == "movies" || section == "tv") {
            val cat = if (section == "movies") movieCat else tvCat
            val seed = itemsKey == curKey && items.isNotEmpty()
            // Same list, pages already loaded (e.g. back from a details page): nothing to do.
            if (seed && loadedPages >= maxPages) {
                loading = false
                return@LaunchedEffect
            }
            loading = true
            val acc = ArrayList<Item>()
            val seen = HashSet<String>()
            if (seed) {
                acc.addAll(items)
                acc.forEach { x -> seen.add(x.href) }
            } else {
                endReached = false
                loadedPages = 0
            }
            var p = loadedPages + 1
            while (p <= maxPages) {
                val page: List<Item>? = try {
                    if (section == "movies") app.repo.movies(cat.ifEmpty { null }, p) else app.repo.tv(cat.ifEmpty { null }, p)
                } catch (e: Exception) {
                    null
                }
                if (page == null) {
                    if (acc.isEmpty()) error = "Couldn't load this list."
                    break
                }
                var added = 0
                for (x in page) {
                    if (seen.add(x.href)) {
                        acc.add(x)
                        added++
                    }
                }
                items = ArrayList(acc)
                itemsKey = curKey
                loadedPages = p
                loading = false
                if (added == 0) {
                    endReached = true
                    break
                }
                p++
            }
            loading = false
        }
    }

    // Genre: titles carry no genre on the site, so look it up (cached) and filter once known.
    LaunchedEffect(genre, items.size, section) {
        if (genre.isEmpty() || (section != "movies" && section != "tv")) return@LaunchedEffect
        val todo = items.filter { x -> x.href !in genreMap }
        for (chunk in todo.chunked(6)) {
            val res = coroutineScope {
                chunk.map { x ->
                    async(Dispatchers.IO) {
                        val m = Meta.get(app.db, x)
                        val arr = m?.optJSONArray("genres") ?: m?.optJSONArray("genre")
                        val g = if (arr == null) emptyList() else (0 until arr.length()).map { i -> arr.getString(i) }
                        x.href to g
                    }
                }.awaitAll()
            }
            for (r in res) genreMap[r.first] = r.second
        }
    }
    LaunchedEffect(curKey) { gridState.scrollToItem(0) }

    BackHandler(enabled = stack.isEmpty() && section != "home") { section = "home" }
    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }

    val s = stack.lastOrNull()
    if (s == null) {
        val compact = isCompact()
        Box(Modifier.fillMaxSize().background(BG).safeDrawingPadding()) {
            Box(Modifier.fillMaxSize().padding(start = if (compact) 0.dp else 76.dp, bottom = if (compact) 62.dp else 0.dp)) {
                val open: (Item) -> Unit = { picked ->
                    restoreKey = picked.href
                    stack.add(Screen.Detail(picked))
                }
                if (section == "home") {
                    HomeRows(app, shelves, loading, error, restoreKey, { restoreKey = null }, open)
                } else if (section == "settings") {
                    SettingsScreen(app)
                } else {
                    val shown = if (itemsKey == curKey) items else emptyList()
                    val isList = section == "movies" || section == "tv"
                    val label = sections.firstOrNull { x -> x.id == section }?.label ?: ""
                    GridScreen(
                        app, label,
                        if (isList) applyFilter(shown, filter, genreMap) else shown,
                        loading, gridState,
                        cats = if (section == "movies") MOVIE_CATS else if (section == "tv") TV_CATS else null,
                        cat = if (section == "movies") movieCat else tvCat,
                        onCat = { c ->
                            if (section == "movies") movieCat = c else tvCat = c
                            maxPages = 4
                        },
                        filter = if (isList) filter else null,
                        onFilter = { f ->
                            sortName = f.sort.name
                            year = f.year
                            genre = f.genre
                        },
                        query = if (section == "search") query else null,
                        onQuery = { q -> query = q },
                        onMore = if (isList && !endReached && maxPages < 40 && shown.isNotEmpty()) ({ maxPages += 4 }) else null,
                        progressText = if (isList && genre.isNotEmpty() && shown.any { x -> x.href !in genreMap })
                            "Finding " + genre + " titles…  " + shown.count { x -> x.href in genreMap } + " / " + shown.size + " checked"
                        else null,
                        errorText = error,
                        emptyText = when (section) {
                            "fav" -> "Your list is empty. Open a title and choose Add to My List."
                            "search" -> "Type a title to search."
                            else -> "Nothing here yet."
                        },
                        restoreKey = restoreKey, onRestored = { restoreKey = null }, onOpen = open
                    )
                }
            }
            val pick: (String) -> Unit = { id -> if (id != section) maxPages = 4; section = id }
            if (compact) BottomBar(sections, section, pick, Modifier.align(Alignment.BottomCenter))
            else Rail(sections, section, pick, Modifier.align(Alignment.CenterStart))
        }
    } else if (s is Screen.Detail) {
        DetailScreen(
            app, s.item,
            onBack = { stack.removeAt(stack.lastIndex) },
            onPlay = { q, i, p -> stack.add(Screen.Play(q, i, p)) }
        )
    } else if (s is Screen.Play) {
        PlayerScreen(app, s.queue, s.start, s.pos) { stack.removeAt(stack.lastIndex) }
    }
}
