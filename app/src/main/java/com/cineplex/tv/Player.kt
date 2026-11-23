package com.cineplex.tv

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "CinePlexPlayer"

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"

private val MEDIA_EXT = listOf(".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v")

/** Decides whether a URL is an HLS playlist, by name first and by content (first bytes) when the name says nothing. */
private fun sniffHls(url: String): Boolean {
    if (Site.isHlsUrl(url)) return true
    val lower = url.substringBefore('?').lowercase()
    if (MEDIA_EXT.any { lower.endsWith(it) }) return false
    // e.g. download.php?id=123 has no extension
    return Site.probe(url) == Probe.HLS
}

/** "file.mp4/index.m3u8" is just an HLS wrapper of file.mp4; prefer the direct file, which plays fine. */
private val WRAPPED_RE = Regex("(?i)^(.+\\.(?:mp4|mkv|webm|m4v|mov))/(?:index|master)\\.m3u8$")

private fun direct(url: String): String {
    val q = url.indexOf('?')
    val base = if (q >= 0) url.substring(0, q) else url
    val rest = if (q >= 0) url.substring(q) else ""
    return WRAPPED_RE.find(base)?.let { it.groupValues[1] + rest } ?: url
}

/** Adds or removes the "/index.m3u8" wrapper (some links only work in one of the two forms). */
private fun toggled(url: String): String {
    val q = url.indexOf('?')
    val base = if (q >= 0) url.substring(0, q) else url
    val rest = if (q >= 0) url.substring(q) else ""
    val suffix = "/index.m3u8"
    val t = if (base.endsWith(suffix, true)) base.dropLast(suffix.length) else base + suffix
    return t + rest
}

/**
 * Every address worth trying for one item, best first:
 * the direct media file, then the link as found, then the other candidates, then the toggled HLS wrapper.
 */
private fun playlistFor(urls: List<String>): List<String> {
    val out = LinkedHashSet<String>()
    for (u in urls) {
        out.add(direct(u))
        out.add(u)
    }
    urls.firstOrNull()?.let { out.add(toggled(direct(it))) }
    return out.toList()
}

private fun mediaItemFor(url: String, id: String, hls: Boolean): MediaItem =
    MediaItem.Builder()
        .setUri(url)
        .setMediaId(id)
        .setMimeType(if (hls) MimeTypes.APPLICATION_M3U8 else null)
        .build()

/** What one queue entry resolved to. [index] says which queue entry it belongs to. */
private class Ready(val index: Int, val urls: List<String>, val hls: Boolean)

/**
 * Movies arrive with a ready link ([QItem.url]).
 * Episodes arrive with an empty url and are looked up here, right before they play,
 * exactly like a movie is looked up right before it plays (the links are signed and short-lived).
 */
private fun prepare(item: QItem, index: Int): Ready {
    val base: List<String> =
        if (item.url.isEmpty()) {
            try {
                Site.episodeCandidates(item.key, item.season, item.epNo)
            } catch (e: Exception) {
                Log.w(TAG, "Episode lookup failed for ${item.key}", e)
                emptyList()
            }
        } else {
            listOf(item.url) + item.alts
        }
    val urls = playlistFor(base)
    val hls = urls.firstOrNull()?.let { sniffHls(it) } ?: false
    Log.d(TAG, "Prepared ${item.title}: ${urls.size} candidate(s), hls=$hls\n" + urls.joinToString("\n"))
    return Ready(index, urls, hls)
}

@Composable
private fun Message(text: String) {
    Box(
        Modifier.fillMaxSize().background(Color.Black).padding(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 18.sp)
    }
}

@Composable
fun PlayerScreen(
    app: App,
    queue: List<QItem>,
    start: Int,
    startPos: Long,
    onExit: () -> Unit
) {
    var cur by remember { mutableIntStateOf(start.coerceIn(0, queue.lastIndex)) }
    val idx = cur

    // Resolve the current entry in the background. Restarts whenever the entry changes.
    val ready by produceState<Ready?>(null, idx) {
        value = null
        value = withContext(Dispatchers.IO) { prepare(queue[idx], idx) }
    }

    val r = ready
    if (r == null || r.index != idx) {
        BackHandler { onExit() }
        Message("Finding video…\n\n" + queue[idx].title)
    } else if (r.urls.isEmpty()) {
        BackHandler { onExit() }
        Message(
            "Couldn't find the video for\n" + queue[idx].title +
                "\n\nPage: " + queue[idx].key +
                "\n\nPress Back to return."
        )
    } else {
        // A fresh player per entry: every episode runs through the same path as a movie.
        key(idx) {
            PlayerContent(
                app = app,
                item = queue[idx],
                ready = r,
                startPos = if (idx == start) startPos else 0L,
                onEnded = {
                    if (idx + 1 < queue.size) {
                        cur = idx + 1
                    } else {
                        onExit()
                    }
                },
                onExit = onExit
            )
        }
    }
}

@Composable
private fun PlayerContent(
    app: App,
    item: QItem,
    ready: Ready,
    startPos: Long,
    onEnded: () -> Unit,
    onExit: () -> Unit
) {
    val ctx = LocalContext.current
    val urls = ready.urls

    val httpFactory = remember {
        DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(30000)
            .setReadTimeoutMs(30000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "$BASE/",
                    "Origin" to BASE,
                    "Accept" to "*/*",
                    "Connection" to "keep-alive"
                )
            )
    }

    val mediaSourceFactory = remember {
        DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, httpFactory))
    }

    val exo = remember {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                val tp = trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage(app.prefs.audio)

                val subs = app.prefs.subs

                if (subs == "off") {
                    tp.setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                } else {
                    tp.setPreferredTextLanguage(subs)
                }

                trackSelectionParameters = tp.build()

                setMediaItem(mediaItemFor(urls[0], item.key, ready.hls), startPos)
                prepare()
                playWhenReady = true
            }
    }

    // Index of the candidate address that is playing right now, and a log of the ones that failed.
    val attempt = remember { intArrayOf(0) }
    val failures = remember { ArrayList<String>() }
    var errorText by remember { mutableStateOf<String?>(null) }

    val view = remember {
        PlayerView(ctx).apply {
            player = exo
            keepScreenOn = true
            useController = true
            controllerShowTimeoutMs = 4000
            requestFocus()
        }
    }

    fun save() {
        val pos = exo.currentPosition
        val dur = exo.duration

        if (dur <= 0 || pos < 10000) return

        if (pos > dur * 0.95) {
            app.db.saveProgress(item.key, item.titleHref, 0, dur)
        } else {
            app.db.saveProgress(item.key, item.titleHref, pos, dur)
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {

            override fun onPlayerError(error: PlaybackException) {
                val n0 = attempt[0]
                val failedUrl = urls.getOrElse(n0) { "?" }
                val http = generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()

                Log.e(TAG, "ERROR: ${error.errorCodeName} on #${n0 + 1} $failedUrl", error)
                failures.add(
                    "#${n0 + 1} ${error.errorCodeName}" +
                        (if (http != null) "  HTTP ${http.responseCode}" else "") +
                        "\n     $failedUrl"
                )

                val n = n0 + 1
                if (n < urls.size) {
                    // Walk through the other addresses before giving up (same for movies and episodes).
                    attempt[0] = n
                    // Some servers reject the Origin / Referer headers; later attempts drop them.
                    httpFactory.setDefaultRequestProperties(
                        if (n >= 3) mapOf("Accept" to "*/*")
                        else mapOf("Referer" to "$BASE/", "Accept" to "*/*")
                    )
                    val next = urls[n]
                    Log.w(TAG, "Trying address ${n + 1}/${urls.size}: $next")
                    val pos = exo.currentPosition
                    exo.setMediaItem(mediaItemFor(next, item.key, Site.isHlsUrl(next)), pos)
                    exo.prepare()
                    exo.playWhenReady = true
                } else {
                    errorText = "Playback error\n" + item.title +
                        "\n\nPage: " + item.key +
                        "\n\nTried " + urls.size + " address(es):\n" +
                        failures.joinToString("\n") +
                        "\n\nPress Back to return."
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                Log.d(TAG, "State: $state")
                if (state == Player.STATE_ENDED) {
                    val dur = exo.duration
                    // Finished: mark as watched, then move on to the next entry (if any).
                    if (dur > 0) app.db.saveProgress(item.key, item.titleHref, 0, dur)
                    onEnded()
                }
            }
        }

        exo.addListener(listener)

        onDispose {
            save()
            exo.removeListener(listener)
            view.player = null
            exo.release()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(15000)
            save()
        }
    }

    BackHandler {
        if (view.isControllerFullyVisible) {
            view.hideController()
        } else {
            onExit()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { view },
            modifier = Modifier.fillMaxSize()
        )
        val err = errorText
        if (err != null) {
            Text(
                err,
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(Color(0xCC000000))
                    .padding(24.dp)
            )
        }
    }
}
