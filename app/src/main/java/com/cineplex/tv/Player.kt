package com.cineplex.tv

import android.annotation.SuppressLint
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.json.JSONTokener

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PlayerScreen(
    app: App,
    queue: List<QItem>,
    start: Int,
    startPos: Long,
    onExit: () -> Unit
) {
    if (queue.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Nothing to play", color = Color.White)
        }
        return
    }

    val ctx = LocalContext.current
    val current = remember(queue, start) { queue.getOrNull(start) ?: queue.first() }

    val webView = remember(ctx) {
        WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false)
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    CookieManager.getInstance().flush()
                    Log.d("CinePlexWebPlayer", "Loaded: $url")
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        Log.e(
                            "CinePlexWebPlayer",
                            "${error.errorCode}: ${error.description} (${request.url})"
                        )
                    }
                }
            }
        }
    }

    // Load the original movie-detail or episode page. Its own player handles streaming.
    LaunchedEffect(webView, current.url) {
        webView.loadUrl(current.url)
    }

    // Seek and periodically save progress when the page uses an HTML5 <video> element.
    LaunchedEffect(webView, current.key, startPos) {
        if (startPos > 0L) {
            delay(2500)
            webView.evaluateJavascript(
                "(function(){var v=document.querySelector('video');if(v){v.currentTime=${startPos / 1000.0};return 'ok';}return 'no-video';})()",
                null
            )
        }

        while (true) {
            delay(15000)
            webView.evaluateJavascript(
                "(function(){var v=document.querySelector('video');return v?JSON.stringify({p:Math.floor(v.currentTime*1000),d:Math.floor(v.duration*1000)}):null;})()"
            ) { raw ->
                runCatching {
                    val encoded = raw ?: return@evaluateJavascript
                    val decoded = JSONTokener(encoded).nextValue() as? String
                        ?: return@evaluateJavascript
                    val data = JSONObject(decoded)
                    val position = data.optLong("p")
                    val duration = data.optLong("d")
                    if (duration > 0L && position >= 10_000L) {
                        val savedPosition = if (position > duration * 0.95) 0L else position
                        app.db.saveProgress(
                            current.key,
                            current.titleHref,
                            savedPosition,
                            duration
                        )
                    }
                }.onFailure {
                    Log.d("CinePlexWebPlayer", "Progress not available: ${it.message}")
                }
            }
        }
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onExit()
    }

    AndroidView(
        factory = { webView },
        modifier = Modifier.fillMaxSize(),
        update = { view ->
            if (view.url == null) view.loadUrl(current.url)
        }
    )

    DisposableEffect(webView) {
        onDispose {
            runCatching { webView.stopLoading() }
            runCatching { webView.loadUrl("about:blank") }
            runCatching { webView.destroy() }
        }
    }
}
