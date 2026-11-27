package com.vibecoder.vibedownloader.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibecoder.vibedownloader.data.Category
import com.vibecoder.vibedownloader.data.DownloadItem
import com.vibecoder.vibedownloader.data.Kind
import com.vibecoder.vibedownloader.engine.Duplicates
import com.vibecoder.vibedownloader.engine.FileNames
import com.vibecoder.vibedownloader.engine.MediaSaver
import com.vibecoder.vibedownloader.linkKind
import com.vibecoder.vibedownloader.service.isYouTube
import com.vibecoder.vibedownloader.ui.components.GlassCard
import com.vibecoder.vibedownloader.ui.components.GradientButton
import com.vibecoder.vibedownloader.ui.components.IconBadge
import com.vibecoder.vibedownloader.ui.components.Pill
import com.vibecoder.vibedownloader.ui.components.SectionLabel
import com.vibecoder.vibedownloader.ui.components.SoftButton
import com.vibecoder.vibedownloader.ui.theme.Vd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// ------------------------------------------------------------------ opening a saved file

/** Opens a finished download. APK files go to the system installer (after "Install unknown apps" is allowed). */
suspend fun openDownloaded(context: Context, item: DownloadItem) {
    val uri = item.savedUri
    val there = uri != null && withContext(Dispatchers.IO) { MediaSaver.exists(context, item.savedUri, item.savedPath) }
    if (!there || uri == null) {
        Toast.makeText(context, "This file was moved or deleted", Toast.LENGTH_SHORT).show()
        return
    }
    val apk = Category.of(item) == Category.Apk
    if (apk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        Toast.makeText(context, "Allow BD Downloader to install apps, then tap Open again", Toast.LENGTH_LONG).show()
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return
    }
    try {
        context.startActivity(MediaSaver.openIntent(uri, if (apk) Category.APK_MIME else item.mime))
    } catch (e: Exception) {
        Toast.makeText(context, if (apk) "This phone could not open the installer" else "No app on this phone can open this file", Toast.LENGTH_SHORT).show()
    }
}

// ------------------------------------------------------------------ duplicates

/** "Already downloaded" (Open / Download again) or "Already in your downloads" (Show it). */
@Composable
fun DuplicateCard(match: Duplicates.Match, onShow: (String) -> Unit, onDownloadAgain: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val item = match.item
    val finished = match is Duplicates.Match.Finished
    GlassCard(Modifier.fillMaxWidth(), padding = 16) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (finished) Icons.Rounded.CheckCircle else Icons.Rounded.FormatListBulleted, size = 40,
                tint = if (finished) Vd.Green else Vd.AccentLight)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (finished) "Already downloaded" else "Already in your downloads", style = MaterialTheme.typography.titleMedium, color = Vd.Text)
                Text(
                    item.fileName.ifBlank { item.title }.ifBlank { "This link" },
                    style = MaterialTheme.typography.bodySmall, color = Vd.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        if (finished) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("Open", Icons.Rounded.OpenInNew, { scope.launch { runCatching { openDownloaded(context, item) } } }, Modifier.weight(1f))
                SoftButton("Download again", Icons.Rounded.Replay, onDownloadAgain, Modifier.weight(1f))
            }
        } else {
            GradientButton("Show it", Icons.Rounded.Visibility, { onShow(item.id) }, Modifier.fillMaxWidth())
        }
    }
}

/** Looks for the same download in the list (in the background). Null while checking or when it is new. */
@Composable
fun rememberDuplicate(item: DownloadItem?): Duplicates.Match? {
    val context = LocalContext.current
    var match by remember(item?.url, item?.quality, item?.keys) { mutableStateOf<Duplicates.Match?>(null) }
    LaunchedEffect(item?.url, item?.quality, item?.keys) {
        match = if (item == null) null else withContext(Dispatchers.IO) { runCatching { Duplicates.find(context, item) }.getOrNull() }
    }
    return match
}

// ------------------------------------------------------------------ many links at once

val BATCH_QUALITIES = listOf("best" to "Best", "1080" to "1080p", "720" to "720p", "audio" to "Audio only")

private fun isPlaylistFile(url: String) =
    runCatching { java.net.URI(url).path ?: "" }.getOrDefault("").lowercase().endsWith(".m3u8")

/** A queue entry for a link that was not looked at yet: the engine finds the rest when it starts. */
fun batchItem(url: String, quality: String, index: Int, now: Long): DownloadItem {
    val host = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: ""
    val media = isYouTube(url) || linkKind(url).startsWith("Video") || isPlaylistFile(url)
    return DownloadItem(
        id = UUID.randomUUID().toString(), url = url,
        kind = if (media) Kind.Media else Kind.File,
        title = if (media) "Video from $host" else (FileNames.fromUrl(url) ?: host),
        site = host, quality = quality, createdAt = now + index, order = now + index,
    )
}

/** State of the "N links found" card. */
class BatchState {
    val checked: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    val dup: SnapshotStateMap<String, Duplicates.Match?> = mutableStateMapOf()
    val touched = mutableSetOf<String>()
    var quality by mutableStateOf("best")

    fun selected(links: List<String>) = links.filter { checked[it] ?: (dup[it] == null) }

    fun items(links: List<String>): List<DownloadItem> {
        val now = System.currentTimeMillis()
        return selected(links).mapIndexed { i, u -> batchItem(u, quality, i, now) }
    }
}

@Composable
fun rememberBatchState(initialQuality: String): BatchState = remember { BatchState().apply { quality = initialQuality } }

@Composable
fun MultiLinkCard(links: List<String>, state: BatchState, onAdd: () -> Unit, adding: Boolean = false) {
    val context = LocalContext.current
    // check every link against the list (quality matters for YouTube)
    LaunchedEffect(links, state.quality) {
        links.forEach { u ->
            val probe = batchItem(u, state.quality, 0, 0)
            val m = withContext(Dispatchers.IO) { runCatching { Duplicates.find(context, probe) }.getOrNull() }
            state.dup[u] = m
            if (u !in state.touched) state.checked[u] = m == null
        }
    }
    GlassCard(Modifier.fillMaxWidth(), padding = 16) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.FormatListBulleted, size = 40)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${links.size} links found", style = MaterialTheme.typography.titleMedium, color = Vd.Text)
                Text("They download one after another.", style = MaterialTheme.typography.bodySmall, color = Vd.Muted)
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            links.forEach { u -> LinkRow(u, state) }
        }
        Spacer(Modifier.height(14.dp))
        SectionLabel("Quality for all")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            BATCH_QUALITIES.forEach { (key, label) ->
                Pill(label, state.quality == key, { state.quality = key }, Modifier.weight(1f), compact = true)
            }
        }
        Text(
            "If a video has no such quality, the closest one is used.",
            style = MaterialTheme.typography.bodySmall, color = Vd.Faint, modifier = Modifier.padding(top = 6.dp, start = 4.dp),
        )
        Spacer(Modifier.height(14.dp))
        val count = state.selected(links).size
        GradientButton(
            if (count == 0) "Pick at least one link" else if (count == links.size) "Add all to queue" else "Add $count to queue",
            Icons.Rounded.Download, onAdd, Modifier.fillMaxWidth(), enabled = count > 0 && !adding,
        )
    }
}

@Composable
private fun LinkRow(url: String, state: BatchState) {
    val on = state.checked[url] ?: (state.dup[url] == null)
    val bg by animateColorAsState(if (on) Vd.AccentSoft else androidx.compose.ui.graphics.Color.Transparent, tween(200), label = "rowBg")
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(bg).border(1.dp, Vd.Line, shape)
            .clickable { state.touched += url; state.checked[url] = !on }
            .padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = on, onCheckedChange = { state.touched += url; state.checked[url] = it },
            colors = CheckboxDefaults.colors(checkedColor = Vd.Accent, uncheckedColor = Vd.Faint, checkmarkColor = androidx.compose.ui.graphics.Color.White),
        )
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            val host = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url
            val rest = runCatching { java.net.URI(url).rawPath ?: "" }.getOrDefault("").trim('/')
            Text(host, style = MaterialTheme.typography.bodyMedium, color = Vd.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (rest.isNotEmpty()) Text(rest, style = MaterialTheme.typography.bodySmall, color = Vd.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val d = state.dup[url]
        AnimatedVisibility(d != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Vd.CardHi).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(
                    if (d is Duplicates.Match.Finished) "Downloaded" else "In your list",
                    style = MaterialTheme.typography.labelMedium, color = if (d is Duplicates.Match.Finished) Vd.Green else Vd.Accent2,
                )
            }
        }
    }
}
