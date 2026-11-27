package com.vibecoder.vibedownloader.ui.sheet

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vibecoder.vibedownloader.Prefs
import com.vibecoder.vibedownloader.data.DownloadItem
import com.vibecoder.vibedownloader.engine.Analysis
import com.vibecoder.vibedownloader.engine.LinkAnalyzer
import com.vibecoder.vibedownloader.extractLinks
import com.vibecoder.vibedownloader.service.DownloadQueue
import com.vibecoder.vibedownloader.service.Notifier
import com.vibecoder.vibedownloader.ui.components.GlassCard
import com.vibecoder.vibedownloader.ui.components.GradientButton
import com.vibecoder.vibedownloader.ui.components.IconBadge
import com.vibecoder.vibedownloader.ui.components.SoftButton
import com.vibecoder.vibedownloader.ui.screens.DuplicateCard
import com.vibecoder.vibedownloader.ui.screens.FileCard
import com.vibecoder.vibedownloader.ui.screens.LoadingCard
import com.vibecoder.vibedownloader.ui.screens.MediaCard
import com.vibecoder.vibedownloader.ui.screens.MultiLinkCard
import com.vibecoder.vibedownloader.ui.screens.ProblemCard
import com.vibecoder.vibedownloader.ui.screens.itemFor
import com.vibecoder.vibedownloader.ui.screens.rememberBatchState
import com.vibecoder.vibedownloader.ui.screens.rememberDuplicate
import com.vibecoder.vibedownloader.ui.theme.Vd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reads a link from the clipboard. Works only while the app has focus (Android 10+). */
fun readClipboard(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()

/**
 * The download sheet: slides up from the bottom over a dimmed background.
 * Used by the share popup (over YouTube, Chrome...), the smart floating button,
 * and inside the app by the browser. One link: quality picker. Many links: a
 * checklist with one quality for all. No link: a friendly paste field.
 */
@Composable
fun DownloadSheet(
    text: String,
    onTextChange: (String) -> Unit,
    prefs: Prefs,
    onClose: () -> Unit,
    onShowItem: (String) -> Unit,
    modifier: Modifier = Modifier,
    presetFile: Analysis.File? = null,
    fromBubble: Boolean = false,
    inApp: Boolean = false,
    onOpenApp: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    fun close(after: () -> Unit = onClose) {
        visible = false
        scope.launch { delay(230); after() }
    }
    BackHandler { close() }

    val links = remember(text) { extractLinks(text) }
    val stage = when {
        presetFile != null -> "one"
        links.size >= 2 -> "many"
        links.size == 1 -> "one"
        else -> "none"
    }

    // permissions, asked only when needed (storage on Android 8-9, notifications once on 13+)
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke(); pending = null
    }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications && !Notifier.canPost(context)) {
                prefs.askedNotifications = true
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else { pending?.invoke(); pending = null }
        } else {
            pending = null
            Toast.makeText(context, "BD Downloader needs storage access to save files in Downloads", Toast.LENGTH_LONG).show()
        }
    }
    fun withPermissions(action: () -> Unit) {
        pending = action
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) { storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE); return }
        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications && !Notifier.canPost(context)) {
            prefs.askedNotifications = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        pending = null
        action()
    }
    var starting by remember { mutableStateOf(false) }
    fun queue(items: List<DownloadItem>) {
        if (items.isEmpty() || starting) return
        withPermissions {
            starting = true
            scope.launch {
                withContext(Dispatchers.IO) { if (items.size == 1) DownloadQueue.add(items[0]) else DownloadQueue.addAll(items) }
                val msg = when {
                    items.size > 1 -> "${items.size} downloads added to the queue"
                    inApp -> "Added to your downloads"
                    else -> "Downloading in the background"
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                close()
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        // dimmed background: tapping it closes the sheet
        AnimatedVisibility(visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
            Box(
                Modifier.fillMaxSize().background(Vd.Scrim)
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
                    .heightIn(max = 680.dp)
                    .clip(shape)
                    .background(Vd.Surface)
                    .border(1.dp, Vd.Line, shape)
                    .clickable(remember { MutableInteractionSource() }, null) { } // taps inside never close it
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Vd.Line))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Vd.AccentGradient),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Download, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("BD Downloader", style = MaterialTheme.typography.titleMedium, color = Vd.Text)
                        val sub = when (stage) {
                            "many" -> "${links.size} links"
                            "none" -> "Paste a link to download"
                            else -> (presetFile?.url ?: links.firstOrNull())?.let { u ->
                                runCatching { java.net.URI(u).host?.removePrefix("www.") }.getOrNull() ?: u
                            } ?: ""
                        }
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = Vd.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { close() }) { Icon(Icons.Rounded.Close, "Close", tint = Vd.Muted) }
                }
                Spacer(Modifier.height(12.dp))

                AnimatedContent(
                    targetState = stage,
                    transitionSpec = { (fadeIn(tween(280)) + expandVertically(tween(280))) togetherWith (fadeOut(tween(150)) + shrinkVertically(tween(200))) },
                    label = "sheetStage",
                    modifier = Modifier.weight(1f, fill = false),
                ) { s ->
                    when (s) {
                        "many" -> {
                            val batch = rememberBatchState(prefs.batchQuality)
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                MultiLinkCard(links, batch, adding = starting, onAdd = {
                                    prefs.batchQuality = batch.quality
                                    queue(batch.items(links))
                                })
                            }
                        }
                        "none" -> NoLinkContent(text, onTextChange, fromBubble)
                        else -> SingleLink(
                            url = presetFile?.url ?: links.firstOrNull() ?: "",
                            presetFile = presetFile, prefs = prefs, starting = starting,
                            onQueue = { queue(listOf(it)) },
                            onShow = { id -> close { onShowItem(id) } },
                        )
                    }
                }

                if (onOpenApp != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { close(onOpenApp) }.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.OpenInNew, null, tint = Vd.Muted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open BD Downloader", style = MaterialTheme.typography.bodyMedium, color = Vd.Muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun SingleLink(
    url: String,
    presetFile: Analysis.File?,
    prefs: Prefs,
    starting: Boolean,
    onQueue: (DownloadItem) -> Unit,
    onShow: (String) -> Unit,
) {
    val context = LocalContext.current
    var analysis by remember(url, presetFile) { mutableStateOf<Analysis?>(presetFile) }
    var selected by remember(url) { mutableStateOf("best") }
    var retry by remember { mutableIntStateOf(0) }
    var again by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url, retry, presetFile) {
        if (presetFile != null && retry == 0) { analysis = presetFile; return@LaunchedEffect }
        analysis = null
        val result = LinkAnalyzer.analyze(context, url)
        analysis = result
        if (result is Analysis.Media) {
            val keys = result.qualities.map { it.key }
            selected = prefs.lastQuality?.takeIf { it in keys } ?: keys.firstOrNull() ?: "best"
        }
    }
    val item = remember(analysis, selected) { analysis?.let { itemFor(it, selected) } }
    val dup = rememberDuplicate(item)

    Column {
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
            AnimatedVisibility(dup != null && !again, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    dup?.let { m ->
                        DuplicateCard(m, onShow = onShow, onDownloadAgain = {
                            again = true
                            item?.let { onQueue(it.copy(id = java.util.UUID.randomUUID().toString())) }
                        })
                    }
                }
            }
        }
        AnimatedVisibility(dup == null || again, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column {
                Spacer(Modifier.height(14.dp))
                val ready = analysis is Analysis.File || analysis is Analysis.Media
                GradientButton(
                    if (analysis == null) "Looking at the link..." else "Download", Icons.Rounded.Download,
                    onClick = {
                        val a = analysis
                        if (a is Analysis.Media) prefs.lastQuality = selected
                        item?.let(onQueue)
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = ready && !starting,
                )
            }
        }
    }
}

@Composable
private fun NoLinkContent(text: String, onTextChange: (String) -> Unit, fromBubble: Boolean) {
    val context = LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState())) {
        GlassCard(Modifier.fillMaxWidth(), padding = 16) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.ContentCopy, size = 40)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("No link copied yet", style = MaterialTheme.typography.titleMedium, color = Vd.Text)
                    Text(
                        if (fromBubble) "Tap Share → Copy link in the app, then tap the button again."
                        else "Copy a video link (Share → Copy link), then paste it here.",
                        style = MaterialTheme.typography.bodySmall, color = Vd.Muted,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Paste a link here", color = Vd.Faint) },
                leadingIcon = { Icon(Icons.Rounded.Link, null, tint = Vd.Muted) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Vd.AccentLight, unfocusedBorderColor = Vd.Line,
                    focusedContainerColor = Vd.Bg, unfocusedContainerColor = Vd.Bg,
                    cursorColor = Vd.Accent2, focusedTextColor = Vd.Text, unfocusedTextColor = Vd.Text,
                ),
            )
            Spacer(Modifier.height(10.dp))
            SoftButton("Paste", Icons.Rounded.ContentPaste, {
                val clip = readClipboard(context)
                if (extractLinks(clip).isEmpty()) Toast.makeText(context, "There is no link on the clipboard yet", Toast.LENGTH_SHORT).show()
                else onTextChange(clip ?: "")
            }, Modifier.fillMaxWidth())
        }
    }
}
