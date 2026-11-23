package com.cineplex.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(app: App, queue: List<QItem>, start: Int, startPos: Long, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val titleOf = remember { queue.associate { q -> q.key to q.titleHref } }
    var curKey by remember { mutableStateOf(queue[start].key) }
    val exo = remember {
        ExoPlayer.Builder(ctx).build().apply {
            // Language preferences come from Settings (default: English audio, subtitles off).
            val tp = trackSelectionParameters.buildUpon().setPreferredAudioLanguage(app.prefs.audio)
            val subs = app.prefs.subs
            if (subs == "off") tp.setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            else tp.setPreferredTextLanguage(subs)
            trackSelectionParameters = tp.build()
            setMediaItems(queue.map { q -> MediaItem.Builder().setUri(q.url).setMediaId(q.key).build() }, start, startPos)
            prepare()
            playWhenReady = true
        }
    }
    val view = remember { PlayerView(ctx).apply { player = exo; keepScreenOn = true; controllerShowTimeoutMs = 4000 } }

    fun save() {
        val pos = exo.currentPosition
        val dur = exo.duration
        if (dur <= 0 || pos < 10_000) return
        val th = titleOf[curKey] ?: curKey
        if (pos > dur * 0.95) app.db.saveProgress(curKey, th, 0, dur) else app.db.saveProgress(curKey, th, pos, dur)
    }

    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    app.db.saveProgress(curKey, titleOf[curKey] ?: curKey, 0, 1)   // finished -> watched
                }
                curKey = mediaItem?.mediaId ?: curKey
            }
        }
        exo.addListener(l)
        onDispose {
            save()
            exo.removeListener(l)
            exo.release()
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(15_000); save() } }
    LaunchedEffect(Unit) { view.isFocusable = true; view.requestFocus() }

    // First Back hides the controls, second leaves the player.
    BackHandler { if (view.isControllerFullyVisible) view.hideController() else onExit() }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}
