package com.vibecoder.vibedownloader.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vibecoder.vibedownloader.ui.components.GlassCard
import com.vibecoder.vibedownloader.ui.components.GradientButton
import com.vibecoder.vibedownloader.ui.components.IconBadge
import com.vibecoder.vibedownloader.ui.components.PageHeader
import com.vibecoder.vibedownloader.ui.components.SectionLabel
import com.vibecoder.vibedownloader.ui.theme.Vd

@Composable
fun DownloadsScreen(onNew: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        PageHeader("Downloads", "Everything you download shows up here.")

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
                        Icon(Icons.Rounded.Download, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(36.dp))
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
