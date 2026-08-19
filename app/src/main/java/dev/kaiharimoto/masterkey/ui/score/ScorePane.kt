package dev.kaiharimoto.masterkey.ui.score

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
 * Three design points worth stating plainly:
 *
 *  1. **Verovio is also the timing source.** It engraves the notation *and*
 *     produces the timemap from the same internal representation, so the ids in
 *     the timemap are the ids in the SVG. A hybrid of two engines would have to
 *     join them on measure/beat position and hope they agreed.
 *  2. **The bridge is not a clock.** Position is pushed at ~20 Hz, not per frame.
 *     A score cursor moves at note boundaries, so 20 Hz looks identical to 60,
 *     while pumping a 60 Hz clock across `evaluateJavascript` is exactly how a
 *     WebView ends up fighting the hardware-accelerated canvas next to it.
 *  3. **Status is reported from Compose, not from inside the page.** The page
 *     cannot tell you it failed to load; a WebView that never renders is just a
 *     dark rectangle, which is exactly how this feature has failed twice. So the
 *     overlay below sits *on top* of the WebView and stays there until the
 *     engraver reports that it has actually drawn something.
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
    /** Engraving size as a percentage; see AppSettings.scoreZoom. */
    zoom: Int = 70,
    /** Why there is nothing to engrave, when the file could not be read at all. */
    loadError: String? = null,
    onEvent: (ScoreEvent) -> Unit = {},
) {
    val context = LocalContext.current

    val assetLoader = remember {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }

    var status: ScoreStatus by remember { mutableStateOf(ScoreStatus.Starting) }
    val bridge = remember {
        ScoreBridge { event ->
            when (event) {
                is ScoreEvent.Loaded -> status = ScoreStatus.Showing
                is ScoreEvent.Failed -> status = ScoreStatus.Broken(event.message)
                else -> Unit
            }
            onEvent(event)
        }
    }

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

            // Display-only, and it must not swallow the keyboard: a focused
            // WebView takes arrow keys and space before Compose ever sees them,
            // which would break every transport shortcut while the score is up.
            isFocusable = false
            isFocusableInTouchMode = false

            addJavascriptInterface(bridge, "MasterKey")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                // The page failing to load is the one failure the page itself
                // cannot report, so it has to be caught out here.
                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (!request.isForMainFrame) return
                    bridge.report("The score view couldn't load: ${error.description}")
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    if (!request.isForMainFrame) return
                    bridge.report("The score view couldn't load (${errorResponse.statusCode}).")
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    bridge.report("The score view ran out of memory and was closed.")
                    return true // we handled it; without this the app is killed
                }
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

    // Verovio is seven megabytes of WebAssembly, so a second or two of silence is
    // normal and a minute of it is not. Saying so beats a blank rectangle.
    LaunchedEffect(scoreXml, bridge) {
        if (scoreXml == null) return@LaunchedEffect
        delay(ENGRAVER_TIMEOUT_MS)
        if (status == ScoreStatus.Starting) {
            status = ScoreStatus.Broken(
                "The engraver didn't start. Reopening the song usually clears it.",
            )
        }
    }

    // Engraving size. A relayout, so it waits until the score is actually up.
    LaunchedEffect(zoom, bridge) {
        bridge.awaitLoaded()
        webView.post {
            webView.evaluateJavascript("window.MasterKeyScore.setZoom($zoom);", null)
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

        val message = when {
            loadError != null -> loadError
            scoreXml == null -> "No sheet music is linked to this song."
            status is ScoreStatus.Broken -> (status as ScoreStatus.Broken).message
            else -> null
        }
        val busy = message == null && status == ScoreStatus.Starting

        if (message != null || busy) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0D0F14)),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(color = Color(0xFF7A8496))
                } else {
                    Text(
                        text = message.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF9AA3B4),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
}

/** What the pane should be showing right now. */
private sealed interface ScoreStatus {
    /** The engraver has not reported a drawn page yet. */
    data object Starting : ScoreStatus
    data object Showing : ScoreStatus
    data class Broken(val message: String) : ScoreStatus
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

    /** Reports a failure raised on the Kotlin side, through the same channel. */
    fun report(message: String) = onEvent(ScoreEvent.Failed(message))

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

/** How long the engraver gets to start before the pane says something is wrong. */
private const val ENGRAVER_TIMEOUT_MS = 15_000L
