package com.vibecoder.vibedownloader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.vibecoder.vibedownloader.BuildConfig
import com.vibecoder.vibedownloader.Prefs
import com.vibecoder.vibedownloader.ui.components.GlassCard
import com.vibecoder.vibedownloader.ui.components.IconBadge
import com.vibecoder.vibedownloader.ui.components.PageHeader
import com.vibecoder.vibedownloader.ui.components.SectionLabel
import com.vibecoder.vibedownloader.ui.theme.Vd
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(prefs: Prefs) {
    var connections by remember { mutableFloatStateOf(prefs.connections.toFloat()) }
    var simultaneous by remember { mutableFloatStateOf(prefs.simultaneous.toFloat()) }
    var wifiOnly by remember { mutableStateOf(prefs.wifiOnly) }
    var autoUpdate by remember { mutableStateOf(prefs.autoUpdate) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        PageHeader("Settings", "Speed, network and updates.")

        SectionLabel("Speed")
        GlassCard(Modifier.fillMaxWidth()) {
            SliderRow(Icons.Rounded.Lan, "Connections per file", "More parts, faster downloads.",
                connections, 1f..16f, 14) { connections = it; prefs.connections = it.roundToInt() }
            Spacer(Modifier.height(16.dp))
            SliderRow(Icons.Rounded.Layers, "Downloads at the same time", "Others wait in the queue.",
                simultaneous, 1f..5f, 3) { simultaneous = it; prefs.simultaneous = it.roundToInt() }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Network and files")
        GlassCard(Modifier.fillMaxWidth()) {
            SwitchRow(Icons.Rounded.Wifi, "Download on Wi-Fi only", "Saves your mobile data.", wifiOnly) { wifiOnly = it; prefs.wifiOnly = it }
            Spacer(Modifier.height(14.dp))
            InfoRow(Icons.Rounded.Folder, "Saved to", "Download / VibeDownloader")
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("App")
        GlassCard(Modifier.fillMaxWidth()) {
            SwitchRow(Icons.Rounded.SystemUpdate, "Check for updates", "You choose when to install.", autoUpdate) { autoUpdate = it; prefs.autoUpdate = it }
            Spacer(Modifier.height(14.dp))
            InfoRow(Icons.Rounded.Info, "VibeDownloader for Android", "Version ${BuildConfig.VERSION_NAME}  ·  by VibeCoder AI")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SliderRow(
    icon: ImageVector, title: String, hint: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, size = 38)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Vd.Text)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = Vd.Muted)
            }
            Spacer(Modifier.width(12.dp))
            Text(value.roundToInt().toString(), style = MaterialTheme.typography.titleLarge, color = Vd.Accent2)
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = Vd.AccentLight, activeTrackColor = Vd.Accent, inactiveTrackColor = Vd.Line,
                activeTickColor = androidx.compose.ui.graphics.Color.Transparent,
                inactiveTickColor = androidx.compose.ui.graphics.Color.Transparent),
        )
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconBadge(icon, size = 38)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Vd.Text)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Vd.Muted)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = androidx.compose.ui.graphics.Color.White, checkedTrackColor = Vd.Accent,
                uncheckedThumbColor = Vd.Muted, uncheckedTrackColor = Vd.CardHi, uncheckedBorderColor = Vd.Line),
        )
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, size = 38)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Vd.Text)
            Text(value, style = MaterialTheme.typography.bodySmall, color = Vd.Muted)
        }
    }
}
