package com.vibecoder.vibedownloader.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibecoder.vibedownloader.data.DownloadItem
import com.vibecoder.vibedownloader.data.Kind
import com.vibecoder.vibedownloader.engine.Analysis
import com.vibecoder.vibedownloader.engine.LinkAnalyzer
import com.vibecoder.vibedownloader.extractLink
import com.vibecoder.vibedownloader.linkKind
import com.vibecoder.vibedownloader.service.DownloadQueue
import com.vibecoder.vibedownloader.ui.components.GlassCard
import com.vibecoder.vibedownloader.ui.components.GradientButton
import com.vibecoder.vibedownloader.ui.components.IconBadge
import com.vibecoder.vibedownloader.ui.components.PageHeader
import com.vibecoder.vibedownloader.ui.components.Pill
import com.vibecoder.vibedownloader.ui.components.SectionLabel
import com.vibecoder.vibedownloader.ui.components.Skeleton
import com.vibecoder.vibedownloader.ui.components.SoftButton
import com.vibecoder.vibedownloader.ui.components.Thumbnail
import com.vibecoder.vibedownloader.ui.theme.Vd
import com.vibecoder.vibedownloader.util.formatBytes
import com.vibecoder.vibedownloader.util.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Paste or share a link. The app looks at it in the background: a direct
 * file shows its name and size, a video page shows the title, thumbnail and
 * the qualities that really exist.
 */
@Composable
fun NewDownloadScreen(
    sharedLink: String?,
    onSharedLinkUsed: () -> Unit,
    requestStart: (start: () -> Unit) -> Unit = { it() },
    onStarted: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by rememberSaveable { mutableStateOf("") }
    var clipboardLink by remember { mutableStateOf<String?>(null) }
    var analysis by remember { mutableStateOf<Analysis?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf("best") }
    var retry by remember { mutableIntStateOf(0) }
    var fromShare by remember { mutableStateOf(false) }

    // a link shared from another app fills the box at once
    LaunchedEffect(sharedLink) {
        if (sharedLink != null) { link = sharedLink; fromShare = true; onSharedLinkUsed() }
    }
    // offer a link that is waiting on the clipboard
    LaunchedEffect(Unit) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
        clipboardLink = extractLink(text)
    }

    val url = extractLink(link)
    // look at the link shortly after typing stops (at once for shared links)
    LaunchedEffect(url, retry) {
        analysis = null
        if (url == null) { analyzing = false; return@LaunchedEffect }
        if (!fromShare) delay(600)
        fromShare = false
        analyzing = true
        val result = LinkAnalyzer.analyze(context, url)
        analyzing = false
        analysis = result
        if (result is Analysis.Media) {
            selected = result.qualities.firstOrNull()?.key ?: "best"
        }
    }

    fun start() {
        val a = analysis ?: return
        val item = when (a) {
            is Analysis.File -> DownloadItem(
                id = UUID.randomUUID().toString(), url = a.url, kind = Kind.File, title = a.name, fileName = a.name,
                site = a.site, total = a.size, mime = a.mime,
            )
            is Analysis.Media -> DownloadItem(
                id = UUID.randomUUID().toString(), url = a.url, kind = Kind.Media, title = a.title, fileName = "",
                site = a.site, thumbnail = a.thumbnail, quality = selected, duration = a.duration,
                total = a.qualities.firstOrNull { it.key == selected }?.size ?: 0,
            )
            is Analysis.Problem -> return
        }
        requestStart {
            scope.launch(Dispatchers.IO) { DownloadQueue.add(item) }
            link = ""
            analysis = null
            onStarted()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        PageHeader("New download", "Paste a link to a file, a video or a song.")

        GlassCard(Modifier.fillMaxWidth()) {
            SectionLabel("Link")
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://", color = Vd.Faint) },
                leadingIcon = { Icon(Icons.Rounded.Link, null, tint = Vd.Muted) },
                trailingIcon = {
                    if (link.isNotEmpty()) IconButton(onClick = { link = "" }) {
                        Icon(Icons.Rounded.Close, "Clear", tint = Vd.Muted)
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Vd.AccentLight, unfocusedBorderColor = Vd.Line,
                    focusedContainerColor = Vd.Bg, unfocusedContainerColor = Vd.Bg,
                    cursorColor = Vd.Accent2, focusedTextColor = Vd.Text, unfocusedTextColor = Vd.Text,
                ),
            )

            AnimatedVisibility(link.isBlank() && clipboardLink != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    SoftButton("Paste copied link", Icons.Rounded.ContentPaste, { link = clipboardLink ?: "" }, Modifier.fillMaxWidth())
                }
            }

            AnimatedVisibility(url != null && analysis == null && !analyzing, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                val u = url ?: ""
                val kind = linkKind(u)
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(if (kind.startsWith("Video")) Icons.Rounded.OndemandVideo else Icons.Rounded.InsertDriveFile, size = 36)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(kind, style = MaterialTheme.typography.titleMedium, color = Vd.Text)
                        Text(runCatching { java.net.URI(u).host }.getOrNull() ?: u, style = MaterialTheme.typography.bodySmall, color = Vd.Muted)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        val stage = when {
            analyzing -> "loading"
            analysis is Analysis.File -> "file"
            analysis is Analysis.Media -> "media"
            analysis is Analysis.Problem -> "problem"
            else -> "none"
        }
        AnimatedContent(
            targetState = stage,
            transitionSpec = { (fadeIn(tween(300)) + expandVertically(tween(300))) togetherWith (fadeOut(tween(160)) + shrinkVertically(tween(220))) },
            label = "analysis",
        ) { s ->
            Column {
                when (s) {
                    "loading" -> LoadingCard()
                    "file" -> (analysis as? Analysis.File)?.let { FileCard(it) }
                    "media" -> (analysis as? Analysis.Media)?.let { MediaCard(it, selected) { k -> selected = k } }
                    "problem" -> (analysis as? Analysis.Problem)?.let { ProblemCard(it.message) { retry++ } }
                }
                if (s != "none") Spacer(Modifier.height(16.dp))
            }
        }

        val ready = analysis is Analysis.File || analysis is Analysis.Media
        GradientButton(
            if (analyzing) "Looking at the link..." else "Start download", Icons.Rounded.Download,
            onClick = { start() },
            modifier = Modifier.fillMaxWidth(), enabled = ready && !analyzing,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LoadingCard() {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Skeleton(Modifier.size(width = 96.dp, height = 60.dp), corner = 12.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Skeleton(Modifier.fillMaxWidth().height(16.dp))
                Spacer(Modifier.height(8.dp))
                Skeleton(Modifier.fillMaxWidth(0.6f).height(12.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Looking at the link...", style = MaterialTheme.typography.bodySmall, color = Vd.Muted)
    }
}

@Composable
private fun FileCard(a: Analysis.File) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.InsertDriveFile, size = 44)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name, style = MaterialTheme.typography.titleMedium, color = Vd.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(if (a.size > 0) formatBytes(a.size) else "Size unknown", a.site.ifBlank { null }).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall, color = Vd.Muted,
                )
            }
        }
    }
}

@Composable
private fun MediaCard(a: Analysis.Media, selected: String, onSelect: (String) -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), padding = 14) {
        Box {
            Thumbnail(a.thumbnail, Icons.Rounded.OndemandVideo, Modifier.fillMaxWidth().aspectRatio(16f / 9f), corner = 14.dp)
            val d = formatDuration(a.duration)
            if (d.isNotEmpty()) {
                Text(
                    d, style = MaterialTheme.typography.labelMedium, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Color(0xB3000000)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(a.title, style = MaterialTheme.typography.titleMedium, color = Vd.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(a.uploader.ifBlank { null }, a.site.ifBlank { null }).joinToString("  ·  ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = Vd.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(14.dp))
        SectionLabel("Quality")
        a.qualities.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                row.forEach { q ->
                    val detail = listOfNotNull(q.detail.ifBlank { null }, if (q.size > 0) formatBytes(q.size) else null).joinToString(" · ")
                    Pill(q.label, q.key == selected, { onSelect(q.key) }, Modifier.weight(1f), detail = detail.ifBlank { " " })
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ProblemCard(message: String, onRetry: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = Vd.Amber, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Vd.Amber)
        }
        Spacer(Modifier.height(12.dp))
        SoftButton("Try again", Icons.Rounded.Refresh, onRetry, Modifier.fillMaxWidth())
    }
}
