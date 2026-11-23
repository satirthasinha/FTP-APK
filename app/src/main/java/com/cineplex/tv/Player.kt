package com.cineplex.tv

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import java.net.HttpURLConnection
import java.net.URL

/** Looks at the first bytes of the URL (following redirects) to see whether it is an HLS playlist. */
private fun sniffHls(url: String): Boolean {
    if (Site.isHlsUrl(url)) return true
    val lower = url.substringBefore('?').lowercase()
    if (listOf(".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v").any { lower.endsWith(it) }) return false
    return try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
        c.setRequestProperty("Referer", "$BASE/")
        c.setRequestProperty("Range", "bytes=0-1023")
        try {
            val type = (c.contentType ?: "").lowercase()
            if (type.contains("mpegurl")) return true
            val buf = ByteArray(64)
            val n = c.inputStream.use { it.read(buf) }
            n > 0 && String(buf, 0, n, Charsets.ISO_8859_1).trimStart('\uFEFF', ' ', '\n', '\r').startsWith("#EXTM3U")
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        Log.w("CinePlexPlayer", "HLS sniff failed for $url", e)
        false
    }
}

/** "file.mp4/index.m3u8" is just an HLS wrapper of file.mp4; prefer the direct file, which plays fine. */
private val WRAPPED_RE = Regex("(?i)^(.+\\.(?:mp4|mkv|webm|m4v|mov))/(?:index|master)\\.m3u8$")

private fun direct(url: String): String {
    val q = url.indexOf('?')
    val base = if (q >= 0) url.substring(0, q) else url
    val rest = if (q >= 0) url.substring(q) else ""
    return WRAPPED_RE.find(base)?.let { it.groupValues[1] + rest } ?: url
}

@Composable
fun PlayerScreen(
    app: App,
    queue0: List<QItem>,
    start: Int,
    startPos: Long,
    onExit: () -> Unit
) {
    val queue = remember(queue0) {
        queue0.map { item ->
            val primary = direct(item.url)
            val fallbacks = buildList {
                if (primary != item.url) add(item.url)
                item.alts.forEach { alt ->
                    val directAlt = direct(alt)
                    add(directAlt)
                    if (directAlt != alt) add(alt)
                }
            }.distinct().filter { it != primary }
            QItem(item.key, primary, item.title, item.titleHref, fallbacks)
        }
    }
    val rawUrls = remember(queue0) { queue0.associate { it.key to it.url } }
    val hls: Map<String, Boolean>? by produceState(initialValue = null, queue) {
        value = withContext(Dispatchers.IO) {
            queue.associate { it.key to sniffHls(it.url) }
        }
    }
    val map = hls ?: return
    PlayerContent(app, queue, rawUrls, start, startPos, map, onExit)
}

@Composable
private fun PlayerContent(
    app: App,
    queue: List<QItem>,
    rawUrls: Map<String, String>,
    start: Int,
    startPos: Long,
    hlsMap: Map<String, Boolean>,
    onExit: () -> Unit
) {
    val ctx = LocalContext.current

    val titleOf = remember(queue) {
        queue.associate { it.key to it.titleHref }
    }

    var curKey by remember(queue, start) {
        mutableStateOf(queue[start].key)
    }

    val httpFactory = remember {
        DefaultHttpDataSource.Factory()
            .setUserAgent(
                "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"
            )
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
        DefaultMediaSourceFactory(
            DefaultDataSource.Factory(ctx, httpFactory)
        )
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
                    tp.setIgnoredTextSelectionFlags(
                        C.SELECTION_FLAG_DEFAULT
                    )
                } else {
                    tp.setPreferredTextLanguage(subs)
                }

                trackSelectionParameters = tp.build()

                setMediaItems(
                    queue.map { q ->
                        MediaItem.Builder()
                            .setUri(q.url)
                            .setMediaId(q.key)
                            .setMimeType(
                                if (hlsMap[q.key] == true) MimeTypes.APPLICATION_M3U8 else null
                            )
                            .build()
                    },
                    start,
                    startPos
                )

                prepare()
                playWhenReady = true
            }
    }

    val attempts = remember { mutableMapOf<String, Int>() }

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

        val th = titleOf[curKey] ?: curKey

        if (pos > dur * 0.95) {
            app.db.saveProgress(curKey, th, 0, dur)
        } else {
            app.db.saveProgress(curKey, th, pos, dur)
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int
            ) {
                curKey = mediaItem?.mediaId ?: curKey
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("CinePlexPlayer", "ERROR: ${error.errorCodeName}", error)
                val item = exo.currentMediaItem
                val idx = exo.currentMediaItemIndex
                val id = item?.mediaId
                val qItem = queue.firstOrNull { it.key == id }
                val orig = qItem?.url
                if (item == null || id == null || orig == null || qItem == null) return
                val n = attempts[id] ?: 0

                fun showError() {
                    val http = generateSequence<Throwable>(error) { it.cause }
                        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                    val msg = "Playback error: ${error.errorCodeName}\n" +
                        (if (http != null) "HTTP ${http.responseCode}\nURL: ${http.dataSpec.uri}\n" else "") +
                        "Item: $orig\nPage: $id\nTried: " +
                        qItem.alts.joinToString("\n  ", prefix = "\n  ")
                    Log.e("CinePlexPlayer", msg)
                    val tv = android.widget.TextView(ctx).apply {
                        text = msg
                        setTextColor(android.graphics.Color.WHITE)
                        setBackgroundColor(0xCC000000.toInt())
                        textSize = 15f
                        setPadding(32, 24, 32, 24)
                    }
                    view.overlayFrameLayout?.apply { removeAllViews(); addView(tv) }
                }

                fun playCandidate(nextUrl: String, headerStage: Int) {
                    when (headerStage) {
                        0 -> httpFactory.setDefaultRequestProperties(
                            mapOf(
                                "Referer" to "$BASE/",
                                "Origin" to BASE,
                                "Accept" to "*/*",
                                "Connection" to "keep-alive"
                            )
                        )
                        1 -> httpFactory.setDefaultRequestProperties(
                            mapOf("Referer" to "$BASE/", "Accept" to "*/*")
                        )
                        else -> httpFactory.setDefaultRequestProperties(
                            mapOf("Accept" to "*/*")
                        )
                    }
                    val pos = exo.currentPosition
                    val isHls = Site.isHlsUrl(nextUrl)
                    exo.replaceMediaItem(
                        idx,
                        MediaItem.Builder()
                            .setUri(nextUrl)
                            .setMediaId(id)
                            .setMimeType(if (isHls) MimeTypes.APPLICATION_M3U8 else null)
                            .build()
                    )
                    exo.seekTo(idx, pos)
                    exo.prepare()
                    exo.playWhenReady = true
                }

                val alts = qItem.alts
                val retryPlan = buildList {
                    for (stage in 0..2) {
                        if (stage > 0) add(orig to stage)
                        for (alt in alts) {
                            add(alt to stage)
                        }
                    }
                    val base = orig.substringBefore('?')
                    val toggled = if (base.endsWith("/index.m3u8", true)) {
                        base.removeSuffix("/index.m3u8")
                    } else {
                        "$base/index.m3u8"
                    }
                    val raw = rawUrls[id] ?: orig
                    if (raw != orig) add(raw to 1)
                    if (toggled != orig) add(toggled to 1)
                    if (toggled != orig) add(toggled to 2)
                }.distinct()

                if (n < retryPlan.size) {
                    val (nextUrl, stage) = retryPlan[n]
                    attempts[id] = n + 1
                    Log.w("CinePlexPlayer", "Retry ${n + 1}/${retryPlan.size} (stage=$stage): $nextUrl")
                    playCandidate(nextUrl, stage)
                } else {
                    showError()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                Log.d(
                    "CinePlexPlayer",
                    "State: $state"
                )
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

    AndroidView(
        factory = { view },
        modifier = Modifier.fillMaxSize()
    )
}
