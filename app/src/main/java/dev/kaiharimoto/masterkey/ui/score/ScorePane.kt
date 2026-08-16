package dev.kaiharimoto.masterkey.ui.score

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import dev.kaiharimoto.masterkey.core.model.Piece
import dev.kaiharimoto.masterkey.ui.player.ScaffoldSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * Sheet music, rendered by Verovio inside a local WebView.
 *
 * Two design points worth stating plainly:
 *
 *  1. **Verovio is also the timing source.** It engraves the notation *and*
 *     produces the timemap from the same internal representation, so the ids in
 *     the timemap are the ids in the SVG. A hybrid of two engines would have to
 *     join them on measure/beat position and hope they agreed.
 *  2. **The bridge is not a clock.** Position is pushed at ~20 Hz, not per frame.
 *     A score cursor moves at note boundaries, so 20 Hz looks identical to 60,
 *     while pumping a 60 Hz clock across `evaluateJavascript` is exactly how a
 *     WebView ends up fighting the hardware-accelerated canvas next to it.
 *
 * Assets are served through [WebViewAssetLoader] rather than `file://` — the
 * latter breaks same-origin and interferes with WASM instantiation.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ScorePane(
    scoreXml: String?,
    piece: Piece,
    // Named `scaffold`, not `settings`: inside the WebView builder below,
    // `settings` already means WebSettings.
    scaffold: ScaffoldSettings,
    positionProvider: () -> Long,
    modifier: Modifier = Modifier,
    onEvent: (ScoreEvent) -> Unit = {},
) {
    val context = LocalContext.current

    val assetLoader = remember {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }

    val bridge = remember { ScoreBridge(onEvent) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Everything is bundled; nothing here should ever touch the network.
            settings.blockNetworkLoads = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            setBackgroundColor(android.graphics.Color.parseColor("#0D0F14"))
            isVerticalScrollBarEnabled = false

            addJavascriptInterface(bridge, "MasterKey")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
            }

            loadUrl("https://appassets.androidplatform.net/assets/score/index.html")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView.removeJavascriptInterface("MasterKey")
            webView.destroy()
        }
    }

    // Hand the score over once the engraver reports ready.
    LaunchedEffect(scoreXml, bridge) {
        if (scoreXml == null) return@LaunchedEffect
        bridge.awaitReady()
        webView.post {
            webView.evaluateJavascript(
                "window.MasterKeyScore.load(${JSONObject.quote(scoreXml)});",
                null,
            )
        }
    }

    // Reading aids that the engraver itself renders — fingering comes straight
    // from <technical><fingering>, so it costs nothing to switch on.
    LaunchedEffect(scaffold.showFingering, bridge) {
        bridge.awaitLoaded()
        webView.post {
            webView.evaluateJavascript(
                "window.MasterKeyScore.setFingering(${scaffold.showFingering});",
                null,
            )
        }
    }

    // Cursor updates. Musical position in quarter notes, so it needs no tempo
    // conversion on the JS side and stays correct at any playback speed.
    LaunchedEffect(bridge, piece) {
        bridge.awaitLoaded()
        val ticksPerQuarter = piece.tempoMap.ticksPerQuarter.toDouble().coerceAtLeast(1.0)
        var lastQuarter = -1.0
        while (coroutineContext.isActive) {
            val quarters = positionProvider() / ticksPerQuarter
            if (kotlin.math.abs(quarters - lastQuarter) > 0.01) {
                lastQuarter = quarters
                webView.evaluateJavascript(
                    "window.MasterKeyScore.seekQuarter($quarters);",
                    null,
                )
            }
            delay(CURSOR_INTERVAL_MS)
        }
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

sealed interface ScoreEvent {
    data object Ready : ScoreEvent
    data class Loaded(val pages: Int, val events: Int, val lastQuarter: Double) : ScoreEvent
    data class PageChanged(val page: Int, val pages: Int) : ScoreEvent
    data class Failed(val message: String) : ScoreEvent
}

/**
 * JS → Kotlin channel.
 *
 * Only lifecycle and error notifications cross this way; the score never asks
 * for the clock, it is told.
 */
private class ScoreBridge(private val onEvent: (ScoreEvent) -> Unit) {

    @Volatile private var ready = false
    @Volatile private var loaded = false

    @JavascriptInterface
    fun onScoreEvent(payload: String) {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        when (json.optString("type")) {
            "ready" -> {
                ready = true
                onEvent(ScoreEvent.Ready)
            }

            "loaded" -> {
                loaded = true
                onEvent(
                    ScoreEvent.Loaded(
                        pages = json.optInt("pages"),
                        events = json.optInt("events"),
                        lastQuarter = json.optDouble("lastQstamp", 0.0),
                    ),
                )
            }

            "page" -> onEvent(
                ScoreEvent.PageChanged(json.optInt("page"), json.optInt("pages")),
            )

            "error" -> onEvent(ScoreEvent.Failed(json.optString("message")))
        }
    }

    suspend fun awaitReady() {
        while (!ready) delay(50)
    }

    suspend fun awaitLoaded() {
        while (!loaded) delay(50)
    }
}

/** 20 Hz. Fast enough that the cursor never looks stepped, slow enough that the
 *  bridge never competes with the highway's render loop. */
private const val CURSOR_INTERVAL_MS = 50L
