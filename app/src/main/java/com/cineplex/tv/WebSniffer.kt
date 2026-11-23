package com.cineplex.tv

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The real video request the site's own player made: address, headers (incl. Cookie) and User-Agent. */
class SniffHit(val url: String, val headers: Map<String, String>, val ua: String)

/**
 * Opens an episode page in a hidden WebView, lets the site's own JavaScript start the player,
 * and records the first video request it makes. That is exactly what a browser does, so any token,
 * cookie or header the page adds on its own is captured too.
 */
object WebSniffer {
    private const val TAG = "CinePlexSniff"

    @Volatile
    var lastNote: String = ""

    private val MEDIA = Regex("(?i)\\.(m3u8q?|mp4|mkv|webm|m4v|mov)$")
    private val DROP = setOf("user-agent", "range", "accept-encoding", "host", "connection", "content-length")

    private const val PLAY_JS =
        "(function(){try{var v=document.querySelector('video');" +
            "if(v){v.muted=true;var p=v.play();if(p&&p.catch){p.catch(function(){});}}" +
            "var b=document.querySelector('.vjs-big-play-button,.plyr__control--overlaid,.play-btn,#play,#playBtn');" +
            "if(b){b.click();}}catch(e){}})();"

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun sniff(ctx: Context, pageUrl: String, timeoutMs: Long = 25000L): SniffHit? {
        val result = CompletableDeferred<SniffHit?>()
        val notes = StringBuilder("webview: $pageUrl\n")
        var web: WebView? = null

        withContext(Dispatchers.Main) {
            try {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                val w = WebView(ctx.applicationContext)
                web = w
                cm.setAcceptThirdPartyCookies(w, true)
                val s = w.settings
                s.javaScriptEnabled = true
                s.domStorageEnabled = true
                s.mediaPlaybackRequiresUserGesture = false
                val ua = s.userAgentString

                w.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val u = request.url.toString()
                        val path = u.substringBefore('?').substringBefore('#')
                        if (MEDIA.containsMatchIn(path)) {
                            notes.append("media request: $u\n")
                            if (!result.isCompleted) {
                                val h = LinkedHashMap<String, String>()
                                for ((k, v) in request.requestHeaders) {
                                    if (k.lowercase() !in DROP) h[k] = v
                                }
                                val cookie = cm.getCookie(u)
                                if (!cookie.isNullOrEmpty()) h["Cookie"] = cookie
                                if (h.keys.none { it.equals("Referer", true) }) h["Referer"] = pageUrl
                                result.complete(SniffHit(u, h, ua))
                            }
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(PLAY_JS, null)
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse
                    ) {
                        val u = request.url.toString()
                        if (MEDIA.containsMatchIn(u.substringBefore('?'))) {
                            notes.append("browser also got HTTP ${errorResponse.statusCode}: $u\n")
                        }
                    }
                }
                w.loadUrl(pageUrl)
            } catch (e: Exception) {
                Log.w(TAG, "WebView failed", e)
                notes.append("webview error: ${e.message}\n")
                result.complete(null)
            }
        }

        val hit = withTimeoutOrNull(timeoutMs) {
            while (!result.isCompleted) {
                delay(2000)
                if (!result.isCompleted) {
                    withContext(Dispatchers.Main) { web?.evaluateJavascript(PLAY_JS, null) }
                }
            }
            result.await()
        }

        withContext(Dispatchers.Main) {
            try {
                web?.stopLoading()
                web?.destroy()
            } catch (e: Exception) {
            }
        }

        notes.append(if (hit != null) "captured: ${hit.url}\n" else "no video request seen\n")
        lastNote = notes.toString()
        Log.d(TAG, lastNote)
        return hit
    }
}
