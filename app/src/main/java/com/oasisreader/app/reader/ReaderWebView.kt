package com.oasisreader.app.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.oasisreader.app.data.BookStore
import com.oasisreader.app.data.FontManager

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ReaderWebView(
    store: BookStore,
    fonts: FontManager,
    page: LoadState.Ready?,
    onCreated: (WebView) -> Unit,
    onPage: (Int, Int) -> Unit,
    onProgress: (Float, Boolean) -> Unit,
    onTapCenter: () -> Unit,
    onAddHighlight: (String, String, String, String) -> Unit,
    onTapHighlight: (String) -> Unit,
    onLookup: (String) -> Unit,
    onOpenImage: (String) -> Unit,
    onEdge: (Int, Float) -> Unit,
    onLink: (String) -> Unit,
    onJump: () -> Unit = {},
    onTurn: (Boolean, Float) -> Unit,
    onDrag: (Int, Float, Float, Float) -> Unit,
    onReady: () -> Unit = {},
    onCover: (Boolean) -> Unit = {},
    onCopy: (String) -> Unit = {},
    sidePad: Dp = 0.dp,
) {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }

    val loader = remember {
        WebViewAssetLoader.Builder()
            .setDomain(ASSET_HOST)
            .addPathHandler("/books/", WebViewAssetLoader.InternalStoragePathHandler(context, store.root))
            .addPathHandler("/fonts/", WebViewAssetLoader.InternalStoragePathHandler(context, fonts.root))
            .build()
    }

    // callbacks change every recomposition; the bridge reads the latest ones
    val callbacks = remember { Callbacks() }
    callbacks.onPage = onPage
    callbacks.onProgress = onProgress
    callbacks.onTap = onTapCenter
    callbacks.onAdd = onAddHighlight
    callbacks.onTapHl = onTapHighlight
    callbacks.onLookup = onLookup
    callbacks.onImage = onOpenImage
    callbacks.onEdge = onEdge
    callbacks.onLink = onLink
    callbacks.onJump = onJump
    callbacks.onTurn = onTurn
    callbacks.onDrag = onDrag
    callbacks.onReady = onReady
    callbacks.onCover = onCover
    callbacks.onCopy = onCopy
    val bridge = remember { JsBridge(main, callbacks) }

    AndroidView(
        modifier = Modifier.fillMaxSize().padding(horizontal = sidePad),
        factory = { ctx ->
            QuietWebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportZoom(false)
                settings.textZoom = 100
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addJavascriptInterface(bridge, "Oasis")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        loader.shouldInterceptRequest(request.url)

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        // every navigation goes through the app (chapters are loaded by the reader)
                        main.post { callbacks.onLink(request.url.toString()) }
                        return true
                    }
                }
                onCreated(this)
            }
        },
        update = { wv ->
            if (page != null && wv.tag != page.token) {
                wv.tag = page.token
                wv.loadDataWithBaseURL(page.baseUrl, page.html, "text/html", "utf-8", null)
            }
        },
    )
}

internal class Callbacks {
    var onPage: (Int, Int) -> Unit = { _, _ -> }
    var onProgress: (Float, Boolean) -> Unit = { _, _ -> }
    var onTap: () -> Unit = {}
    var onAdd: (String, String, String, String) -> Unit = { _, _, _, _ -> }
    var onTapHl: (String) -> Unit = {}
    var onLookup: (String) -> Unit = {}
    var onImage: (String) -> Unit = {}
    var onEdge: (Int, Float) -> Unit = { _, _ -> }
    var onLink: (String) -> Unit = {}
    var onJump: () -> Unit = {}
    var onTurn: (Boolean, Float) -> Unit = { _, _ -> }
    var onDrag: (Int, Float, Float, Float) -> Unit = { _, _, _, _ -> }
    var onReady: () -> Unit = {}
    var onCover: (Boolean) -> Unit = {}
    var onCopy: (String) -> Unit = {}
}

/** Called from the page script (window.Oasis). Calls arrive on a background thread. */
internal class JsBridge(private val main: Handler, private val cb: Callbacks) {
    @JavascriptInterface fun page(cur: Int, total: Int) { main.post { cb.onPage(cur, total) } }
    @JavascriptInterface fun progress(f: Float, end: Boolean) { main.post { cb.onProgress(f, end) } }
    @JavascriptInterface fun tap() { main.post { cb.onTap() } }
    @JavascriptInterface fun addHighlight(id: String, text: String, color: String, anchor: String) {
        main.post { cb.onAdd(id, text, color, anchor) }
    }
    @JavascriptInterface fun tapHighlight(id: String) { main.post { cb.onTapHl(id) } }
    @JavascriptInterface fun openImage(src: String) { main.post { cb.onImage(src) } }
    @JavascriptInterface fun edge(dir: Int, y: Float) { main.post { cb.onEdge(dir, if (y.isNaN()) 0.5f else y) } }
    @JavascriptInterface fun link(url: String) { main.post { cb.onLink(url) } }
    @JavascriptInterface fun jump() { main.post { cb.onJump() } }
    @JavascriptInterface fun lookup(text: String) { main.post { cb.onLookup(text) } }
    @JavascriptInterface fun turn(dir: Int, y: Float) { main.post { cb.onTurn(dir > 0, y) } }
    @JavascriptInterface fun dragStart(dir: Int, yFrac: Float, w: Float) { main.post { cb.onDrag(0, w, yFrac, dir.toFloat()) } }
    @JavascriptInterface fun dragMove(x: Float, y: Float) { main.post { cb.onDrag(1, x, y, 0f) } }
    @JavascriptInterface fun dragEnd(dx: Float, vx: Float) { main.post { cb.onDrag(2, dx, 0f, vx) } }
    @JavascriptInterface fun ready() { main.post { cb.onReady() } }
    @JavascriptInterface fun cover(on: Boolean) { main.post { cb.onCover(on) } }
    @JavascriptInterface fun copy(text: String) { main.post { cb.onCopy(text) } }
}

internal fun android.content.Context.findActivity(): Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper && c !is Activity) c = c.baseContext
    return c as? Activity
}
