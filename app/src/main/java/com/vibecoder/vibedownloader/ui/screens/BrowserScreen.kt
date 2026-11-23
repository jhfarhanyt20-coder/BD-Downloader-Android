package com.vibecoder.vibedownloader.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MovieFilter
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vibecoder.vibedownloader.VIDEO_SITES
import com.vibecoder.vibedownloader.engine.Analysis
import com.vibecoder.vibedownloader.engine.AppLog
import com.vibecoder.vibedownloader.engine.FileNames
import com.vibecoder.vibedownloader.ui.components.GlassCard
import com.vibecoder.vibedownloader.ui.components.IconBadge
import com.vibecoder.vibedownloader.ui.components.SectionLabel
import com.vibecoder.vibedownloader.ui.theme.Vd
import java.net.URLEncoder

/**
 * The built-in browser's state. The WebView lives here (not in the screen), so
 * switching tabs keeps the page. Security: no JavaScript bridge, no file or
 * content access, never mixed content, Safe Browsing on, no third-party cookies,
 * and other apps are only opened after asking.
 */
class BrowserState {
    private var web: WebView? = null
    var generation by mutableIntStateOf(0)
        private set
    var address by mutableStateOf("")
    var pageUrl by mutableStateOf<String?>(null)
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var editing by mutableStateOf(false)
    /** Video and audio addresses the page asked for (observed only, never changed). */
    val found = mutableStateListOf<String>()
    var askOpen by mutableStateOf<Pair<Intent, String>?>(null)
    var onFile: ((Analysis.File) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    fun obtain(context: Context): WebView {
        web?.let { w ->
            (w.parent as? ViewGroup)?.removeView(w)
            w.onResume()
            return w
        }
        val w = WebView(context)
        w.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        w.settings.apply {
            javaScriptEnabled = true          // most sites need it; no JavaScript interface is ever added
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, false)
        }
        w.webViewClient = Client()
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
            override fun onReceivedTitle(view: WebView, t: String?) { title = t ?: "" }
        }
        // direct file downloads go into our queue, never the system downloader
        w.setDownloadListener { url, _, contentDisposition, mimetype, contentLength ->
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                Toast.makeText(context, "This file can't be saved from the browser", Toast.LENGTH_SHORT).show()
                return@setDownloadListener
            }
            val guessed = runCatching { URLUtil.guessFileName(url, contentDisposition, mimetype) }.getOrNull()
            val name = FileNames.sanitize(FileNames.fromContentDisposition(contentDisposition) ?: guessed ?: FileNames.fromUrl(url))
            val host = runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull() ?: ""
            val mime = mimetype?.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: FileNames.mimeFor(name)
            AppLog.i("browser", "Download offered by the page: $host ($mime, $contentLength bytes)")
            onFile?.invoke(Analysis.File(url, name, contentLength.coerceAtLeast(0L), mime, host))
        }
        web = w
        return w
    }

    fun open(input: String) {
        val url = toUrl(input) ?: return
        address = url
        editing = false
        web?.loadUrl(url) ?: run { pendingUrl = url }
        pageUrl = url
    }

    var pendingUrl: String? = null

    fun back() { web?.takeIf { it.canGoBack() }?.goBack() }
    fun forward() { web?.takeIf { it.canGoForward() }?.goForward() }
    fun reload() { if (progress < 100) web?.stopLoading() else web?.reload() }
    fun home() {
        web?.let { it.stopLoading(); it.loadUrl("about:blank"); it.clearHistory() }
        pageUrl = null; address = ""; title = ""; found.clear(); canBack = false; canForward = false
    }

    fun pause() { runCatching { web?.onPause() } }

    fun destroy() {
        web?.let { w -> runCatching { (w.parent as? ViewGroup)?.removeView(w); w.stopLoading(); w.destroy() } }
        web = null
    }

    private fun refreshNav(view: WebView) {
        canBack = view.canGoBack()
        canForward = view.canGoForward()
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase() ?: return true
            if (scheme == "http" || scheme == "https" || scheme == "about") return false
            // another app (intent://, market://, mailto: ...): never launched by itself
            if (!request.hasGesture()) return true
            val intent = safeIntent(uri.toString()) ?: return true
            if (scheme == "intent") {
                intent.getStringExtra("browser_fallback_url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let {
                    view.loadUrl(it); return true
                }
            }
            askOpen = intent to (if (scheme == "intent") intent.`package` ?: "another app" else scheme)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (url != null && url != "about:blank") {
                if (url != pageUrl) found.clear()
                pageUrl = url
                if (!editing) address = url
            }
            refreshNav(view)
        }

        override fun onPageFinished(view: WebView, url: String?) { refreshNav(view) }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (url != null && url != "about:blank") {
                pageUrl = url
                if (!editing) address = url
            }
            refreshNav(view)
        }

        // observe only: note video/audio requests for "Found on this page", always load them unchanged
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val u = request.url
            val path = u.path?.lowercase() ?: ""
            if ((u.scheme == "https" || u.scheme == "http") && MEDIA.containsMatchIn(path)) {
                val s = u.toString()
                main.post { if (s !in found && found.size < 30) found += s }
            }
            return null
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // the page's renderer stopped: start a fresh browser instead of closing the app
            AppLog.w("browser", "Renderer gone (crash=${detail.didCrash()})")
            if (web === view) {
                runCatching { (view.parent as? ViewGroup)?.removeView(view); view.destroy() }
                web = null
                pendingUrl = pageUrl
                generation++
            }
            return true
        }
    }

    companion object {
        private val MEDIA = Regex("""\.(mp4|m4a|mp3|webm|m3u8)$""")

        /** An address or a search: only http(s) pages are ever opened. */
        fun toUrl(input: String): String? {
            val t = input.trim()
            if (t.isEmpty()) return null
            val lower = t.lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://")) return t
            val looksLikeHost = !t.contains(' ') && Regex("""^[\w.-]+\.[a-z]{2,}(:\d+)?(/.*)?$""", RegexOption.IGNORE_CASE).matches(t)
            if (looksLikeHost) return "https://$t"
            return "https://duckduckgo.com/?q=" + URLEncoder.encode(t, "UTF-8")
        }

        /** An intent that can only open a browsable activity: no fixed component, no selector, no permission grants. */
        fun safeIntent(url: String): Intent? = runCatching {
            val i = if (url.startsWith("intent:", true)) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addCategory(Intent.CATEGORY_BROWSABLE)
            i.component = null
            i.selector = null
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            i
        }.getOrNull()

        fun isVideoPage(url: String?): Boolean {
            if (url == null) return false
            val u = runCatching { Uri.parse(url) }.getOrNull() ?: return false
            val host = u.host?.lowercase() ?: return false
            if (VIDEO_SITES.none { host == it || host.endsWith(".$it") }) return false
            val path = u.path ?: ""
            return path.length > 1 || (u.query?.isNotBlank() == true)
        }
    }
}

private data class QuickSite(val name: String, val url: String, val color: Color)

private val QuickSites = listOf(
    QuickSite("YouTube", "https://m.youtube.com", Color(0xFFFF4E45)),
    QuickSite("Facebook", "https://m.facebook.com", Color(0xFF4C8DF6)),
    QuickSite("Instagram", "https://www.instagram.com", Color(0xFFE1306C)),
    QuickSite("TikTok", "https://www.tiktok.com", Color(0xFF25F4EE)),
    QuickSite("X", "https://x.com", Color(0xFF9AA1A9)),
    QuickSite("Vimeo", "https://vimeo.com", Color(0xFF1AB7EA)),
)

@Composable
fun BrowserScreen(state: BrowserState, onDownloadPage: (String) -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    BackHandler(enabled = state.pageUrl != null) {
        if (state.canBack) state.back() else state.home()
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { state.pause() } }

    Column(Modifier.fillMaxSize()) {
        // ---------------- address bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            NavButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", state.canBack) { state.back() }
            NavButton(Icons.AutoMirrored.Rounded.ArrowForward, "Forward", state.canForward) { state.forward() }
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier.weight(1f).height(44.dp).clip(shape).background(Vd.Surface).border(1.dp, if (state.editing) Vd.AccentLight else Vd.Line, shape)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val secure = state.pageUrl?.startsWith("https://") == true && !state.editing
                Icon(if (secure) Icons.Rounded.Lock else Icons.Rounded.Search, null, tint = if (secure) Vd.Green else Vd.Faint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (state.address.isEmpty()) Text("Search or type a web address", style = MaterialTheme.typography.bodyMedium, color = Vd.Faint, maxLines = 1)
                    BasicTextField(
                        value = state.address,
                        onValueChange = { state.address = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Vd.Text),
                        cursorBrush = SolidColor(Vd.Accent2),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { state.open(state.address); focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth()
                            .semantics { contentDescription = "Address bar" }
                            .onFocusChanged { f -> state.editing = f.isFocused },
                    )
                }
                if (state.editing && state.address.isNotEmpty()) {
                    Icon(Icons.Rounded.Close, "Clear address", tint = Vd.Muted, modifier = Modifier.size(18.dp).clip(CircleShape).clickable { state.address = "" })
                }
            }
            NavButton(if (state.progress < 100 && state.pageUrl != null) Icons.Rounded.Close else Icons.Rounded.Refresh, "Reload", state.pageUrl != null) { state.reload() }
        }
        // ---------------- progress line
        val p by animateFloatAsState(state.progress / 100f, tween(250), label = "webProgress")
        val showLine = state.pageUrl != null && state.progress < 100
        val alpha by animateFloatAsState(if (showLine) 1f else 0f, tween(300), label = "lineAlpha")
        Canvas(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).height(3.dp)) {
            if (alpha > 0f) {
                drawRoundRect(Brush.horizontalGradient(listOf(Vd.AccentLight, Vd.Accent2)), size = Size(size.width * p, size.height),
                    cornerRadius = CornerRadius(size.height, size.height), alpha = alpha)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.pageUrl == null) {
                BrowserHome { state.open(it) }
            } else {
                key(state.generation) {
                    AndroidView(
                        factory = { ctx ->
                            state.obtain(ctx).also { w -> state.pendingUrl?.let { u -> state.pendingUrl = null; w.loadUrl(u) } }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // ---------------- found on this page
            var listOpen by remember { mutableStateOf(false) }
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                AnimatedVisibility(listOpen && state.found.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    GlassCard(Modifier.widthIn(max = 320.dp).heightIn(max = 300.dp).padding(bottom = 8.dp), padding = 12) {
                        SectionLabel("Found on this page")
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.found.toList().takeLast(12).reversed().forEach { u ->
                                val name = FileNames.fromUrl(u) ?: u
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Vd.CardHi)
                                        .clickable { listOpen = false; onDownloadPage(u) }.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(FileNames.extension(name).uppercase().ifBlank { "FILE" }, style = MaterialTheme.typography.labelMedium, color = Vd.Accent2)
                                    Spacer(Modifier.width(8.dp))
                                    Text(name, style = MaterialTheme.typography.bodySmall, color = Vd.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                AnimatedVisibility(state.found.isNotEmpty() && state.pageUrl != null, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                    val shape = RoundedCornerShape(18.dp)
                    Row(
                        Modifier.clip(shape).background(Vd.Card).border(1.dp, Vd.Line, shape).clickable { listOpen = !listOpen }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.MovieFilter, null, tint = Vd.Accent2, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Found on this page  ${state.found.size}", style = MaterialTheme.typography.labelMedium, color = Vd.Text)
                    }
                }
            }

            // ---------------- download this video
            androidx.compose.animation.AnimatedVisibility(
                visible = BrowserState.isVideoPage(state.pageUrl),
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
            ) {
                val shape = RoundedCornerShape(26.dp)
                Row(
                    Modifier.clip(shape).background(Vd.AccentGradient).clickable { state.pageUrl?.let(onDownloadPage) }
                        .padding(horizontal = 18.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Download, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Download", style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
            }
        }
    }

    state.askOpen?.let { (intent, what) ->
        AlertDialog(
            onDismissRequest = { state.askOpen = null },
            containerColor = Vd.Card,
            icon = { Icon(Icons.Rounded.Public, null, tint = Vd.AccentLight) },
            title = { Text("Open another app?", color = Vd.Text) },
            text = { Text("This page wants to open $what. Only continue if you trust this site.", color = Vd.Muted, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    state.askOpen = null
                    try { context.startActivity(intent) } catch (e: Exception) {
                        Toast.makeText(context, "No app on this phone can open this link", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Open", color = Vd.AccentLight) }
            },
            dismissButton = { TextButton(onClick = { state.askOpen = null }) { Text("Stay here", color = Vd.Muted) } },
        )
    }
}

@Composable
private fun NavButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(icon, label, tint = if (enabled) Vd.Text else Vd.Line, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun BrowserHome(onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp)) {
        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Public, size = 44)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Browse and download", style = MaterialTheme.typography.titleMedium, color = Vd.Text)
                    Text("Open a video page and tap Download. Files you open go straight to your downloads.",
                        style = MaterialTheme.typography.bodySmall, color = Vd.Muted)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        SectionLabel("Quick start")
        QuickSites.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                row.forEach { site ->
                    val shape = RoundedCornerShape(18.dp)
                    Column(
                        Modifier.weight(1f).clip(shape).background(Brush.verticalGradient(listOf(Vd.CardHi, Vd.Card))).border(1.dp, Vd.Line, shape)
                            .clickable { onOpen(site.url) }.padding(vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(site.color.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) { Text(site.name.take(1), style = MaterialTheme.typography.titleMedium, color = site.color) }
                        Spacer(Modifier.height(8.dp))
                        Text(site.name, style = MaterialTheme.typography.bodySmall, color = Vd.Text)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Searches use DuckDuckGo. Other apps are never opened without asking you first.",
            style = MaterialTheme.typography.bodySmall, color = Vd.Faint,
        )
        Spacer(Modifier.height(24.dp))
    }
}
