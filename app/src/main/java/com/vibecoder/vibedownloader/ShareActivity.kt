package com.vibecoder.vibedownloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vibecoder.vibedownloader.engine.Analysis
import com.vibecoder.vibedownloader.engine.LinkAnalyzer
import com.vibecoder.vibedownloader.service.DownloadQueue
import com.vibecoder.vibedownloader.service.Notifier
import com.vibecoder.vibedownloader.ui.components.GradientButton
import com.vibecoder.vibedownloader.ui.screens.FileCard
import com.vibecoder.vibedownloader.ui.screens.LoadingCard
import com.vibecoder.vibedownloader.ui.screens.MediaCard
import com.vibecoder.vibedownloader.ui.screens.ProblemCard
import com.vibecoder.vibedownloader.ui.screens.itemFor
import com.vibecoder.vibedownloader.ui.theme.Vd
import com.vibecoder.vibedownloader.ui.theme.VibeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Opens as a small sheet ON TOP of the app the link was shared from (YouTube,
 * Chrome...). The user picks a quality and taps Download; the sheet closes and
 * the download runs in the background, so they can keep sharing one video after
 * another without ever leaving YouTube.
 */
class ShareActivity : ComponentActivity() {
    private val link = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        link.value = linkFrom(intent)
        if (link.value == null) {
            Toast.makeText(this, "No link to download was shared", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val prefs = Prefs(this)
        setContent {
            VibeTheme {
                ShareSheet(
                    url = link.value ?: "",
                    prefs = prefs,
                    onClose = { finishQuietly() },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).apply {
                            action = Intent.ACTION_SEND
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, link.value)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                        finishQuietly()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let { link.value = it }
    }

    private fun linkFrom(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_SEND) extractLink(intent.getStringExtra(Intent.EXTRA_TEXT)) else null

    private fun finishQuietly() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

@Composable
private fun ShareSheet(url: String, prefs: Prefs, onClose: () -> Unit, onOpenApp: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    var analysis by remember(url) { mutableStateOf<Analysis?>(null) }
    var selected by remember(url) { mutableStateOf("best") }
    var retry by remember { mutableIntStateOf(0) }
    var starting by remember { mutableStateOf(false) }

    // slide the sheet in, and out again before the activity closes
    LaunchedEffect(Unit) { visible = true }
    fun close(after: () -> Unit = onClose) {
        visible = false
        scope.launch { delay(220); after() }
    }
    BackHandler { close() }

    LaunchedEffect(url, retry) {
        analysis = null
        val result = LinkAnalyzer.analyze(context, url)
        analysis = result
        if (result is Analysis.Media) {
            val keys = result.qualities.map { it.key }
            selected = prefs.lastQuality?.takeIf { it in keys } ?: keys.firstOrNull() ?: "best"
        }
    }

    fun queue() {
        val a = analysis ?: return
        val item = itemFor(a, selected) ?: return
        starting = true
        if (a is Analysis.Media) prefs.lastQuality = selected
        scope.launch {
            // queue it (this starts the background service) while the sheet is still on screen
            kotlinx.coroutines.withContext(Dispatchers.IO) { DownloadQueue.add(item) }
            Toast.makeText(context, "Downloading in the background", Toast.LENGTH_SHORT).show()
            close()
        }
    }

    // permissions, asked only when needed (storage on Android 8-9, notifications once on 13+)
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { queue() }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications && !Notifier.canPost(context)) {
                prefs.askedNotifications = true
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else queue()
        } else Toast.makeText(context, "VibeDownloader needs storage access to save files in Downloads", Toast.LENGTH_LONG).show()
    }
    fun download() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) { storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE); return }
        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications && !Notifier.canPost(context)) {
            prefs.askedNotifications = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        queue()
    }

    Box(Modifier.fillMaxSize()) {
        // dimmed background: tapping it closes the sheet
        AnimatedVisibility(visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
            Box(
                Modifier.fillMaxSize().background(Color(0x99000000))
                    .clickable(remember { MutableInteractionSource() }, null) { close() },
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(180)),
        ) {
            val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(Vd.CardHi, Vd.Surface)))
                    .border(1.dp, Vd.Line, shape)
                    .clickable(remember { MutableInteractionSource() }, null) { } // taps inside never close it
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp),
            ) {
                // grab handle
                Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Vd.Line))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Vd.AccentGradient),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Download, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("VibeDownloader", style = MaterialTheme.typography.titleMedium, color = Vd.Text)
                        Text(
                            runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url,
                            style = MaterialTheme.typography.bodySmall, color = Vd.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { close() }) { Icon(Icons.Rounded.Close, "Close", tint = Vd.Muted) }
                }
                Spacer(Modifier.height(12.dp))

                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    val stage = when (analysis) {
                        null -> "loading"
                        is Analysis.File -> "file"
                        is Analysis.Media -> "media"
                        is Analysis.Problem -> "problem"
                    }
                    AnimatedContent(
                        targetState = stage,
                        transitionSpec = { (fadeIn(tween(280)) + expandVertically(tween(280))) togetherWith (fadeOut(tween(150)) + shrinkVertically(tween(200))) },
                        label = "shareStage",
                    ) { s ->
                        when (s) {
                            "loading" -> LoadingCard()
                            "file" -> (analysis as? Analysis.File)?.let { FileCard(it) }
                            "media" -> (analysis as? Analysis.Media)?.let { MediaCard(it, selected) { k -> selected = k } }
                            else -> (analysis as? Analysis.Problem)?.let { ProblemCard(it.message) { retry++ } }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                val ready = analysis is Analysis.File || analysis is Analysis.Media
                GradientButton(
                    if (analysis == null) "Looking at the link..." else "Download", Icons.Rounded.Download,
                    onClick = { if (!starting) download() }, modifier = Modifier.fillMaxWidth(), enabled = ready && !starting,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { close(onOpenApp) }.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.OpenInNew, null, tint = Vd.Muted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open VibeDownloader", style = MaterialTheme.typography.bodyMedium, color = Vd.Muted)
                }
            }
        }
    }
}
