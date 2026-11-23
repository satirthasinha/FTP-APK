
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    app: App,
    queue: List<QItem>,
    start: Int,
    startPos: Long,
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
                            // The server uses .m3u8q for an HLS playlist.
                            .setMimeType(
                                if (Site.isHlsUrl(q.url)) MimeTypes.APPLICATION_M3U8 else null
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

                Log.e(
                    "CinePlexPlayer",
                    "Message: ${error.message}"
                )
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
