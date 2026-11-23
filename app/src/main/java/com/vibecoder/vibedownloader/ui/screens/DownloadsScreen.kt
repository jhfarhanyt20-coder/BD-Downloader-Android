package com.vibecoder.vibedownloader.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import com.vibecoder.vibedownloader.data.Category
import kotlinx.coroutines.delay
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibecoder.vibedownloader.data.DownloadItem
import com.vibecoder.vibedownloader.data.DownloadRepository
import com.vibecoder.vibedownloader.data.Kind
import com.vibecoder.vibedownloader.data.Status
import com.vibecoder.vibedownloader.engine.FileNames
import com.vibecoder.vibedownloader.engine.MediaSaver
import com.vibecoder.vibedownloader.service.DownloadQueue
import com.vibecoder.vibedownloader.ui.components.GlassCard
import com.vibecoder.vibedownloader.ui.components.GradientButton
import com.vibecoder.vibedownloader.ui.components.GradientProgressBar
import com.vibecoder.vibedownloader.ui.components.IconBadge
import com.vibecoder.vibedownloader.ui.components.IndeterminateBar
import com.vibecoder.vibedownloader.ui.components.PageHeader
import com.vibecoder.vibedownloader.ui.components.Pill
import com.vibecoder.vibedownloader.ui.components.SectionLabel
import com.vibecoder.vibedownloader.ui.components.SoftButton
import com.vibecoder.vibedownloader.ui.components.Thumbnail
import com.vibecoder.vibedownloader.ui.theme.Vd
import com.vibecoder.vibedownloader.update.AppUpdater
import com.vibecoder.vibedownloader.util.formatBytes
import com.vibecoder.vibedownloader.util.formatEta
import com.vibecoder.vibedownloader.util.formatSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Filters = listOf("All", "Active", "Done")

/** Running first, then the line in its order, then everything else newest first. */
private fun displayOrder(items: List<DownloadItem>): List<DownloadItem> {
    val running = items.filter { it.status.isRunning }.sortedWith(compareBy({ it.order }, { it.createdAt }))
    val line = items.filter { it.status == Status.Queued || it.status == Status.Waiting }.sortedWith(compareBy({ it.order }, { it.createdAt }))
    val rest = items.filter { !it.status.isRunning && it.status != Status.Queued && it.status != Status.Waiting }.sortedByDescending { it.createdAt }
    return running + line + rest
}

@Composable
fun DownloadsScreen(onNew: () -> Unit, highlightId: String? = null, onHighlightShown: () -> Unit = {}) {
    val items by DownloadRepository.items.collectAsStateWithLifecycle()
    val loaded by DownloadRepository.loaded.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<DownloadItem?>(null) }
    var glowId by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        val active = items.count { it.status.isActive }
        val done = items.size - active
        PageHeader(
            "Downloads",
            if (items.isEmpty()) "Everything you download shows up here."
            else listOfNotNull(if (active > 0) "$active active" else null, if (done > 0) "$done done" else null).joinToString("  ·  "),
        )
        UpdateBanner()

        AnimatedContent(
            targetState = when { !loaded -> 0; items.isEmpty() -> 1; else -> 2 },
            transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(200)) },
            label = "downloadsBody",
            modifier = Modifier.weight(1f),
        ) { state ->
            when (state) {
                0 -> Box(Modifier.fillMaxSize())
                1 -> EmptyState(onNew)
                else -> Column(Modifier.fillMaxSize()) {
                    // status filter on the first row, file types on a scrollable second row
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                        Filters.forEachIndexed { i, label ->
                            val count = when (i) { 1 -> active; 2 -> done; else -> items.size }
                            Pill("$label  $count", filter == i, { filter = i })
                        }
                    }
                    val byStatus = when (filter) {
                        1 -> items.filter { it.status.isActive }
                        2 -> items.filter { it.status == Status.Done }
                        else -> items
                    }
                    val cats = byStatus.groupingBy { Category.of(it) }.eachCount()
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
                    ) {
                        Pill("All types", category == null, { category = null })
                        Category.entries.forEach { c ->
                            Pill(
                                c.label + (cats[c]?.let { "  $it" } ?: ""), category == c.name,
                                { category = if (category == c.name) null else c.name },
                                icon = c.icon, iconTint = c.color,
                            )
                        }
                    }
                    val shown = displayOrder(byStatus.filter { category == null || Category.of(it).name == category })
                    val line = DownloadQueue.queueOrder(items)
                    val listState = rememberLazyListState()
                    // opened from a notification, the share sheet or a duplicate card: show that download
                    LaunchedEffect(highlightId, items.size) {
                        val id = highlightId ?: return@LaunchedEffect
                        if (items.none { it.id == id }) return@LaunchedEffect
                        filter = 0
                        category = null
                        delay(80)
                        val index = displayOrder(items).indexOfFirst { it.id == id }
                        if (index >= 0) runCatching { listState.animateScrollToItem(index) }
                        glowId = id
                        onHighlightShown()
                        delay(1800)
                        glowId = null
                    }
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (shown.isEmpty()) {
                            item(key = "nothing") {
                                Text(
                                    when {
                                        category != null -> "No " + (Category.entries.firstOrNull { it.name == category }?.label ?: "").lowercase() + " files here yet."
                                        filter == 1 -> "Nothing is downloading right now."
                                        else -> "Nothing finished yet."
                                    },
                                    style = MaterialTheme.typography.bodyMedium, color = Vd.Muted,
                                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp).animateItem(),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        items(shown, key = { it.id }) { item ->
                            val pos = line.indexOfFirst { it.id == item.id }
                            DownloadCard(
                                item, Modifier.animateItem(), onRemove = { removing = item },
                                queuePosition = if (pos >= 0) pos + 1 else 0, queueSize = line.size, glow = glowId == item.id,
                            )
                        }
                    }
                }
            }
        }
    }

    removing?.let { item ->
        RemoveDialog(item, onDismiss = { removing = null })
    }
}

@Composable
private fun EmptyState(onNew: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // empty state with a softly breathing glow
        val glow = rememberInfiniteTransition(label = "glow")
        val pulse by glow.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "pulse")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {
                    Box(Modifier.size(112.dp).scale(pulse).clip(CircleShape).background(Vd.GlowGradient))
                    Box(
                        Modifier.size(72.dp).clip(CircleShape).background(Vd.AccentGradient),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Download, null, tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("No downloads yet", style = MaterialTheme.typography.titleLarge, color = Vd.Text)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Paste a link, or share one from YouTube, Chrome or any app.",
                    style = MaterialTheme.typography.bodyMedium, color = Vd.Muted, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                GradientButton("New download", Icons.Rounded.Add, onNew, Modifier.fillMaxWidth())
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("Good to know")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Tip(Icons.Rounded.Share, "Share to download", "In YouTube or Chrome tap Share, then VibeDownloader.")
            Tip(Icons.Rounded.Bolt, "Fast downloads", "Big files are split into parts that download at the same time.")
            Tip(Icons.Rounded.PauseCircle, "Pause any time", "Paused downloads continue later from where they stopped.")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Tip(icon: ImageVector, title: String, body: String) {
    GlassCard(Modifier.fillMaxWidth(), padding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, size = 40)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Vd.Text)
                Text(body, style = MaterialTheme.typography.bodySmall, color = Vd.Muted)
            }
        }
    }
}

private fun typeIcon(item: DownloadItem): ImageVector {
    val c = Category.of(item)
    if (c != Category.Other) return c.icon
    val mime = item.mime.ifBlank { FileNames.mimeFor(item.fileName) }
    return when {
        mime.startsWith("image/") -> Icons.Rounded.Image
        mime.contains("zip") || mime.contains("compressed") || mime.contains("archive") -> Icons.Rounded.Archive
        else -> Icons.Rounded.InsertDriveFile
    }
}

private enum class CardPhase { Working, Done, Failed }

@Composable
private fun DownloadCard(
    item: DownloadItem, modifier: Modifier, onRemove: () -> Unit,
    queuePosition: Int = 0, queueSize: Int = 0, glow: Boolean = false,
) {
    val cat = Category.of(item)
    val glowLine by animateColorAsState(if (glow) Vd.AccentLight else Color.Transparent, tween(400), label = "glow")
    GlassCard(modifier.fillMaxWidth().border(2.dp, glowLine, RoundedCornerShape(20.dp)), padding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(item.thumbnail, typeIcon(item), Modifier.size(width = 64.dp, height = 52.dp), tint = cat.color)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.fileName.ifBlank { item.title }.ifBlank { "Download" },
                    style = MaterialTheme.typography.titleMedium, color = Vd.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    item.site.ifBlank { null },
                    if (item.total > 0) formatBytes(item.total) else null,
                    if (item.kind == Kind.Media) qualityLabel(item.quality) else null,
                ).joinToString("  ·  ")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(cat.color))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        listOf(cat.label, meta).filter { it.isNotEmpty() }.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall, color = Vd.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (item.status == Status.Queued || item.status == Status.Waiting) {
                QueueMenu(item, queuePosition, queueSize)
            }
        }
        Spacer(Modifier.height(12.dp))
        val phase = when (item.status) {
            Status.Done -> CardPhase.Done
            Status.Failed -> CardPhase.Failed
            else -> CardPhase.Working
        }
        AnimatedContent(
            targetState = phase,
            transitionSpec = { (fadeIn(tween(260)) + expandVertically()) togetherWith (fadeOut(tween(180)) + shrinkVertically()) },
            label = "cardPhase",
        ) { p ->
            when (p) {
                CardPhase.Working -> WorkingBlock(item, queuePosition)
                CardPhase.Done -> DoneBlock(item, onRemove)
                CardPhase.Failed -> FailedBlock(item, onRemove)
            }
        }
    }
}

private fun qualityLabel(q: String) = when (q) {
    "best" -> "Best"
    "audio" -> "Audio only"
    else -> q.toIntOrNull()?.let { "${it}p" } ?: q
}

@Composable
private fun WorkingBlock(item: DownloadItem, queuePosition: Int = 0) {
    Column {
        val statusText = if (item.status == Status.Queued && queuePosition > 0) "#$queuePosition in queue" else when (item.status) {
            Status.Downloading -> if (item.total > 0) "${(item.progress * 100).toInt()}%" else if (item.downloaded > 0) formatBytes(item.downloaded) else (item.note ?: "Starting")
            Status.Finishing -> item.note ?: "Finishing up"
            Status.Paused -> "Paused"
            Status.Queued -> item.note ?: DownloadQueue.WAIT_LINE
            Status.Waiting -> item.note ?: "Waiting"
            else -> ""
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.status == Status.Waiting) {
                Icon(
                    if (item.note == DownloadQueue.WAIT_WIFI) Icons.Rounded.Wifi else Icons.Rounded.WifiOff, null,
                    tint = Vd.Accent2, modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            AnimatedContent(
                statusText, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) }, label = "statusText",
                modifier = Modifier.weight(1f),
            ) { s ->
                Text(s, style = MaterialTheme.typography.labelLarge, color = if (item.status == Status.Downloading) Vd.Text else Vd.Muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (item.status == Status.Downloading) {
                val right = listOfNotNull(
                    if (item.speed > 0) formatSpeed(item.speed) else null,
                    formatEta(item.etaSeconds).ifBlank { null },
                ).joinToString("  ·  ")
                Text(right, style = MaterialTheme.typography.bodySmall, color = Vd.Accent2)
            } else if (item.status == Status.Paused && item.total > 0) {
                Text("${formatBytes(item.downloaded)} of ${formatBytes(item.total)}", style = MaterialTheme.typography.bodySmall, color = Vd.Faint)
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            item.status == Status.Downloading && item.total > 0 -> GradientProgressBar(item.progress)
            item.status == Status.Paused -> GradientProgressBar(item.progress, active = false)
            item.status == Status.Downloading || item.status == Status.Finishing -> IndeterminateBar()
            else -> IndeterminateBar(soft = true)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (item.status == Status.Paused) {
                SoftButton("Resume", Icons.Rounded.PlayArrow, { DownloadQueue.resume(item.id) }, Modifier.weight(1f))
            } else if (item.status != Status.Finishing) {
                SoftButton("Pause", Icons.Rounded.Pause, { DownloadQueue.pause(item.id) }, Modifier.weight(1f))
            }
            SoftButton("Cancel", Icons.Rounded.Close, { DownloadQueue.cancel(item.id) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DoneBlock(item: DownloadItem, onRemove: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun withFile(action: (String) -> Unit) {
        val uri = item.savedUri ?: return
        scope.launch {
            val there = withContext(Dispatchers.IO) { MediaSaver.exists(context, item.savedUri, item.savedPath) }
            if (there) action(uri) else Toast.makeText(context, "This file was moved or deleted", Toast.LENGTH_SHORT).show()
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val apk = Category.of(item) == Category.Apk
        SoftButton(if (apk) "Install" else "Open", if (apk) Icons.Rounded.InstallMobile else Icons.Rounded.OpenInNew, {
            scope.launch { runCatching { openDownloaded(context, item) } }
        }, Modifier.weight(1f))
        SoftButton("Share", Icons.Rounded.Share, {
            withFile { uri ->
                runCatching { context.startActivity(MediaSaver.shareIntent(uri, item.mime, item.fileName)) }
            }
        }, Modifier.weight(1f))
        IconButton(onClick = onRemove) {
            Icon(Icons.Rounded.DeleteOutline, "Remove", tint = Vd.Muted)
        }
    }
}

@Composable
private fun FailedBlock(item: DownloadItem, onRemove: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = Vd.Amber, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(item.note ?: "Something went wrong. Please try again.", style = MaterialTheme.typography.bodyMedium, color = Vd.Amber)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Try again", Icons.Rounded.Refresh, { DownloadQueue.resume(item.id) }, Modifier.weight(1f))
            SoftButton("Remove", Icons.Rounded.Close, onRemove, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RemoveDialog(item: DownloadItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var deleteFile by remember { mutableStateOf(false) }
    val finished = item.status == Status.Done
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Vd.Card,
        title = { Text("Remove from list?", color = Vd.Text) },
        text = {
            Column {
                Text(
                    if (finished) "The file stays in Download / VibeDownloader unless you also delete it."
                    else "The unfinished parts will be deleted.",
                    color = Vd.Muted, style = MaterialTheme.typography.bodyMedium,
                )
                if (finished) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { deleteFile = !deleteFile }.padding(end = 8.dp),
                    ) {
                        Checkbox(
                            checked = deleteFile, onCheckedChange = { deleteFile = it },
                            colors = CheckboxDefaults.colors(checkedColor = Vd.Accent, uncheckedColor = Vd.Faint),
                        )
                        Text("Also delete the file", color = Vd.Text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val del = deleteFile
                onDismiss()
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { DownloadQueue.remove(item.id, del) }
                    if (!ok) Toast.makeText(context, "The file couldn't be deleted. You can delete it in the Files app.", Toast.LENGTH_LONG).show()
                }
            }) { Text("Remove", color = Vd.Red) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep", color = Vd.Text) } },
    )
}

/** A gentle card at the top when a new app version is ready. */
@Composable
private fun UpdateBanner() {
    val state by AppUpdater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val release = when (val s = state) {
        is AppUpdater.State.Available -> s.release
        is AppUpdater.State.Downloading -> s.release
        is AppUpdater.State.Ready -> s.release
        is AppUpdater.State.NeedsPermission -> s.release
        else -> null
    }
    AnimatedVisibility(release != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        GlassCard(Modifier.fillMaxWidth().padding(bottom = 14.dp), padding = 14) {
            val s = state
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.SystemUpdate, size = 38)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (s is AppUpdater.State.Ready || s is AppUpdater.State.NeedsPermission) "Version ${release?.version ?: ""} is ready to install"
                        else "Version ${release?.version ?: ""} is available",
                        style = MaterialTheme.typography.titleMedium, color = Vd.Text,
                    )
                    Text(
                        when (s) {
                            is AppUpdater.State.Downloading -> "Downloading ${(s.progress * 100).toInt()}%"
                            is AppUpdater.State.Ready -> "Downloaded. Your downloads and settings stay."
                            else -> "New features and fixes"
                        },
                        style = MaterialTheme.typography.bodySmall, color = Vd.Muted,
                    )
                }
                when (s) {
                    is AppUpdater.State.Available -> SoftButton("Update now", null, { scope.launch { AppUpdater.download(context) } })
                    is AppUpdater.State.Ready, is AppUpdater.State.NeedsPermission ->
                        GradientButton("Install now", null, { scope.launch { AppUpdater.installNow(context) } }, Modifier.width(140.dp))
                    else -> Unit
                }
            }
            if (s is AppUpdater.State.Downloading) {
                GradientProgressBar(s.progress, Modifier.padding(top = 12.dp))
            }
        }
    }
}

/** The menu on a waiting card: move it in the line. */
@Composable
private fun QueueMenu(item: DownloadItem, position: Int, size: Int) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "Queue options", tint = Vd.Muted) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Vd.Card)) {
            if (position > 1) {
                DropdownMenuItem(
                    text = { Text("Start next", color = Vd.Text) },
                    leadingIcon = { Icon(Icons.Rounded.VerticalAlignTop, null, tint = Vd.AccentLight) },
                    onClick = { open = false; DownloadQueue.move(item.id, Int.MIN_VALUE) },
                )
                DropdownMenuItem(
                    text = { Text("Move up", color = Vd.Text) },
                    leadingIcon = { Icon(Icons.Rounded.KeyboardArrowUp, null, tint = Vd.AccentLight) },
                    onClick = { open = false; DownloadQueue.move(item.id, -1) },
                )
            }
            if (position in 1 until size) {
                DropdownMenuItem(
                    text = { Text("Move down", color = Vd.Text) },
                    leadingIcon = { Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Vd.AccentLight) },
                    onClick = { open = false; DownloadQueue.move(item.id, 1) },
                )
            }
            DropdownMenuItem(
                text = { Text("Pause", color = Vd.Text) },
                leadingIcon = { Icon(Icons.Rounded.Pause, null, tint = Vd.Muted) },
                onClick = { open = false; DownloadQueue.pause(item.id) },
            )
            DropdownMenuItem(
                text = { Text("Cancel", color = Vd.Red) },
                leadingIcon = { Icon(Icons.Rounded.Close, null, tint = Vd.Red) },
                onClick = { open = false; DownloadQueue.cancel(item.id) },
            )
        }
    }
}
