
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
import kotlinx.coroutines.delay

/** Detect HLS from URL or response content. */
private fun sniffHls(url: String): Boolean {
    if (Site.isHlsUrl(url)) return true

    val lower = url.substringBefore('?').substringBefore('#').lowercase()

    // A wrapped HLS URL can contain a media-file extension before /master.m3u8.
    if (Regex("/(?:index|master)\\.m3u8$", RegexOption.IGNORE_CASE)
            .containsMatchIn(lower)
    ) return true

    if (listOf(".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v")
            .any { lower.endsWith(it) }
    ) return false

    return try {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.instanceFollowRedirects = true
        c.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"
        )
        c.setRequestProperty("Referer", "$BASE/")
        c.setRequestProperty("Range", "bytes=0-1023")

        try {
            val type = (c.contentType ?: "").lowercase()

            if (type.contains("mpegurl")) return true

            val buf = ByteArray(64)
            val n = c.inputStream.use { it.read(buf) }

            n > 0 && String(
                buf,
                0,
                n,
                Charsets.ISO_8859_1
            ).trimStart('\uFEFF', ' ', '\n', '\r').startsWith("#EXTM3U")
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        Log.w("CinePlexPlayer", "HLS sniff failed for $url", e)
        false
    }
}

/*
 * IMPORTANT:
 * Never strip /master.m3u8 or /index.m3u8 from a TV episode URL.
 * The server may require the original HLS playlist URL.
 */

@Composable
fun PlayerScreen(
    app: App,
    queue0: List<QItem>,
    start: Int,
    startPos: Long,
    onExit: () -> Unit
) {
    // Preserve original URLs for both movies and TV episodes.
    val queue = remember(queue0) {
        queue0.map {
            QItem(
                it.key,
                it.url,
                it.title,
                it.titleHref,
                it.alts
            )
        }
    }

    val rawUrls = remember(queue0) {
        queue0.associate { it.key to it.url }
    }

    val hls by produceState<Map<String, Boolean>?>(null, queue) {
        value = kotlinx.coroutines.withContext(
            kotlinx.coroutines.Dispatchers.IO
        ) {
            queue.associate { it.key to sniffHls(it.url) }
        }
    }

    val map = hls ?: return

    PlayerContent(
        app,
        queue,
        rawUrls,
        start,
        startPos,
        map,
        onExit
    )
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

    val titleOf = remember {
        queue.associate { it.key to it.titleHref }
    }

    var curKey by remember {
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

    val exo = remember(queue, hlsMap) {
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
                                if (hlsMap[q.key] == true) {
                                    MimeTypes.APPLICATION_M3U8
                                } else {
                                    null
                                }
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
                Log.e(
                    "CinePlexPlayer",
                    "ERROR: ${error.errorCodeName}",
                    error
                )

                val item = exo.currentMediaItem
                val idx = exo.currentMediaItemIndex
                val id = item?.mediaId
                val orig = queue.firstOrNull { it.key == id }?.url

                if (item == null || id == null || orig == null) return

                val n = attempts[id] ?: 0

                fun showError() {
                    val http = generateSequence<Throwable>(error) { it.cause }
                        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                        .firstOrNull()

                    val msg =
                        "Playback error: ${error.errorCodeName}\n" +
                        (if (http != null) {
                            "HTTP ${http.responseCode}\nURL: ${http.dataSpec.uri}\n"
                        } else "") +
                        "Item: $orig\nPage: $id\nTried: " +
                        (queue.firstOrNull { it.key == id }?.alts
                            ?: emptyList<String>())
                            .joinToString("\n  ", prefix = "\n  ")

                    Log.e("CinePlexPlayer", msg)

                    val tv = android.widget.TextView(ctx).apply {
                        text = msg
                        setTextColor(android.graphics.Color.WHITE)
                        setBackgroundColor(0xCC000000.toInt())
                        textSize = 15f
                        setPadding(32, 24, 32, 24)
                    }

                    view.overlayFrameLayout?.apply {
                        removeAllViews()
                        addView(tv)
                    }
                }

                // Try all alternative episode sources before showing an error.
                val alts = queue.firstOrNull { it.key == id }?.alts
                    ?: emptyList()

                if (alts.isNotEmpty()) {
                    if (n < alts.size) {
                        attempts[id] = n + 1

                        val next = alts[n]

                        Log.w(
                            "CinePlexPlayer",
                            "Episode fallback ${n + 1}/${alts.size}: $next"
                        )

                        // Retry fallback without Origin header.
                        httpFactory.setDefaultRequestProperties(
                            mapOf(
                                "Referer" to "$BASE/",
                                "Accept" to "*/*"
                            )
                        )

                        val pos = exo.currentPosition
                        val isHls = sniffHls(next)

                        exo.replaceMediaItem(
                            idx,
                            MediaItem.Builder()
                                .setUri(next)
                                .setMediaId(id)
                                .setMimeType(
                                    if (isHls) MimeTypes.APPLICATION_M3U8
                                    else null
                                )
                                .build()
                        )

                        exo.seekTo(idx, pos)
                        exo.prepare()
                        exo.playWhenReady = true
                    } else {
                        showError()
                    }

                    return
                }

                // Retry original URL with different request headers.
                if (n < 2) {
                    attempts[id] = n + 1

                    if (n == 1) {
                        httpFactory.setDefaultRequestProperties(
                            mapOf("Accept" to "*/*")
                        )
                    }

                    Log.w(
                        "CinePlexPlayer",
                        "Retry ${n + 1} with original URL: $orig"
                    )

                    val isHls = hlsMap[id] == true || sniffHls(orig)
                    val pos = exo.currentPosition

                    exo.replaceMediaItem(
                        idx,
                        MediaItem.Builder()
                            .setUri(orig)
                            .setMediaId(id)
                            .setMimeType(
                                if (isHls) MimeTypes.APPLICATION_M3U8
                                else null
                            )
                            .build()
                    )

                    exo.seekTo(idx, pos)
                    exo.prepare()
                    exo.playWhenReady = true
                } else {
                    showError()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                Log.d("CinePlexPlayer", "State: $state")
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
