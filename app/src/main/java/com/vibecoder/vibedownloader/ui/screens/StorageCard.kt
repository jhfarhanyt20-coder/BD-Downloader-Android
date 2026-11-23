package com.vibecoder.vibedownloader.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Storage
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vibecoder.vibedownloader.data.Category
import com.vibecoder.vibedownloader.engine.StorageStats
import com.vibecoder.vibedownloader.ui.components.GlassCard
import com.vibecoder.vibedownloader.ui.components.IconBadge
import com.vibecoder.vibedownloader.ui.components.Skeleton
import com.vibecoder.vibedownloader.ui.components.SoftButton
import com.vibecoder.vibedownloader.ui.theme.Vd
import com.vibecoder.vibedownloader.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Phone space, what VibeDownloader files use per type, and temporary files to clear. */
@Composable
fun StorageCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<StorageStats.Info?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var clearing by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) {
        info = withContext(Dispatchers.IO) { runCatching { StorageStats.read(context) }.getOrNull() }
    }
    GlassCard(Modifier.fillMaxWidth()) {
        AnimatedContent(info == null, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) }, label = "storage") { loading ->
            val i = info
            if (loading || i == null) {
                Column {
                    Skeleton(Modifier.fillMaxWidth(0.6f).height(16.dp))
                    Spacer(Modifier.height(12.dp))
                    Skeleton(Modifier.fillMaxWidth().height(10.dp))
                    Spacer(Modifier.height(18.dp))
                    Skeleton(Modifier.fillMaxWidth(0.5f).height(14.dp))
                }
            } else Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Storage, size = 38)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Phone storage", style = MaterialTheme.typography.titleMedium, color = Vd.Text)
                        Text("${formatBytes(i.used)} used of ${formatBytes(i.total)}  ·  ${formatBytes(i.free)} free",
                            style = MaterialTheme.typography.bodySmall, color = Vd.Muted)
                    }
                }
                Spacer(Modifier.height(12.dp))
                UsageBar(listOf((if (i.total > 0) i.used.toFloat() / i.total else 0f) to null))

                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.DownloadDone, size = 38)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("VibeDownloader files", style = MaterialTheme.typography.titleMedium, color = Vd.Text)
                        Text(
                            if (i.files == 0) "Nothing saved yet" else "${formatBytes(i.downloads)} in ${i.files} file${if (i.files == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall, color = Vd.Muted,
                        )
                    }
                }
                if (i.downloads > 0) {
                    Spacer(Modifier.height(12.dp))
                    val parts = Category.entries.mapNotNull { c ->
                        val s = i.byCategory[c] ?: 0L
                        if (s > 0) (s.toFloat() / i.downloads) to c.color else null
                    }
                    UsageBar(parts)
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Category.entries.forEach { c ->
                            val s = i.byCategory[c] ?: 0L
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(9.dp).clip(CircleShape).background(if (s > 0) c.color else Vd.Line))
                                Spacer(Modifier.width(10.dp))
                                Text(c.label, style = MaterialTheme.typography.bodyMedium, color = if (s > 0) Vd.Text else Vd.Faint, modifier = Modifier.weight(1f))
                                Text(if (s > 0) formatBytes(s) else "-", style = MaterialTheme.typography.bodySmall, color = Vd.Muted)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.CleaningServices, size = 38)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Temporary files", style = MaterialTheme.typography.titleMedium, color = Vd.Text)
                        Text(
                            buildString {
                                append(if (i.clearable > 0) "${formatBytes(i.clearable)} can be cleared" else "Nothing to clear")
                                if (i.kept > 0) append("  ·  ${formatBytes(i.kept)} kept for unfinished downloads")
                            },
                            style = MaterialTheme.typography.bodySmall, color = Vd.Muted,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                SoftButton(if (clearing) "Clearing..." else "Clear temporary files", Icons.Rounded.CleaningServices, {
                    if (clearing) return@SoftButton
                    clearing = true
                    scope.launch {
                        val freed = withContext(Dispatchers.IO) { runCatching { StorageStats.clearTemporary(context) }.getOrDefault(0L) }
                        clearing = false
                        Toast.makeText(context, if (freed > 0) "Freed ${formatBytes(freed)}" else "Already clean", Toast.LENGTH_SHORT).show()
                        refresh++
                    }
                }, Modifier.fillMaxWidth())
            }
        }
    }
}

/** A rounded bar that grows in: one gradient part (color null) or several coloured parts. */
@Composable
private fun UsageBar(parts: List<Pair<Float, Color?>>) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(parts) { grow.snapTo(0f); grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val track = Vd.Line
    val gradient = listOf(Vd.AccentLight, Vd.Accent2)
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        val clip = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, r)) }
        clipPath(clip) {
            var x = 0f
            parts.forEach { (f, color) ->
                val w = size.width * f.coerceIn(0f, 1f) * grow.value
                if (w > 0f) {
                    if (color == null) drawRect(Brush.horizontalGradient(gradient, startX = 0f, endX = size.width), Offset(x, 0f), Size(w, size.height))
                    else drawRect(color, Offset(x, 0f), Size(w, size.height))
                    x += w
                }
            }
        }
    }
}
