package com.vibecoder.vibedownloader.ui.screens

import android.content.ClipData
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Update
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibecoder.vibedownloader.engine.AppLog
import com.vibecoder.vibedownloader.engine.EngineUpdater
import com.vibecoder.vibedownloader.engine.MediaEngine
import com.vibecoder.vibedownloader.service.DownloadQueue
import com.vibecoder.vibedownloader.ui.components.GradientButton
import com.vibecoder.vibedownloader.ui.components.GradientProgressBar
import com.vibecoder.vibedownloader.ui.components.SoftButton
import com.vibecoder.vibedownloader.update.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.draw.clip
import com.vibecoder.vibedownloader.service.BubbleService
import com.vibecoder.vibedownloader.ui.components.Pill
import com.vibecoder.vibedownloader.ui.theme.ThemeMode

@Composable
fun SettingsScreen(prefs: Prefs, onLicenses: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val update by AppUpdater.state.collectAsStateWithLifecycle()
    val engineVersion by MediaEngine.version.collectAsStateWithLifecycle()
    val engineStatus by EngineUpdater.status.collectAsStateWithLifecycle()
    var engineBusy by remember { mutableStateOf(false) }
    // start the engine quietly so its version can be shown
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { MediaEngine.ensure() } } }
    var connections by remember { mutableFloatStateOf(prefs.connections.toFloat()) }
    var simultaneous by remember { mutableFloatStateOf(prefs.simultaneous.toFloat()) }
    var wifiOnly by remember { mutableStateOf(prefs.wifiOnly) }
    var autoUpdate by remember { mutableStateOf(prefs.autoUpdate) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        PageHeader("Settings", "Look, speed, network and updates.")

        SectionLabel("Appearance")
        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Palette, size = 38)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Theme", style = MaterialTheme.typography.titleMedium, color = Vd.Text)
                    Text("Dark, light, or the same as your phone.", style = MaterialTheme.typography.bodySmall, color = Vd.Muted)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(
                    ThemeMode.System to Icons.Rounded.BrightnessAuto,
                    ThemeMode.Light to Icons.Rounded.LightMode,
                    ThemeMode.Dark to Icons.Rounded.DarkMode,
                ).forEach { (m, icon) ->
                    Pill(m.label, Vd.mode == m, { Vd.setMode(context, m) }, Modifier.weight(1f), icon = icon)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Speed")
        GlassCard(Modifier.fillMaxWidth()) {
            SliderRow(Icons.Rounded.Lan, "Connections per file", "More parts, faster downloads.",
                connections, 1f..16f, 14) { connections = it; prefs.connections = it.roundToInt() }
            Spacer(Modifier.height(16.dp))
            SliderRow(Icons.Rounded.Layers, "Downloads at the same time", "Set 1 to download one by one. Others wait in the queue.",
                simultaneous, 1f..5f, 3) { simultaneous = it; prefs.simultaneous = it.roundToInt(); DownloadQueue.settingsChanged() }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Network and files")
        GlassCard(Modifier.fillMaxWidth()) {
            SwitchRow(Icons.Rounded.Wifi, "Download on Wi-Fi only", "Saves your mobile data.", wifiOnly) { wifiOnly = it; prefs.wifiOnly = it; DownloadQueue.settingsChanged() }
            Spacer(Modifier.height(14.dp))
            // the user can pick any folder (phone storage or SD card); the default is Download/VibeDownloader
            var folder by remember { mutableStateOf(com.vibecoder.vibedownloader.engine.MediaSaver.folderLabel(context)) }
            var custom by remember { mutableStateOf(com.vibecoder.vibedownloader.engine.MediaSaver.usableFolder(context) != null) }
            val picker = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
            ) { tree ->
                if (tree != null) {
                    runCatching { com.vibecoder.vibedownloader.engine.MediaSaver.setFolder(context, tree) }
                        .onFailure { AppLog.w("settings", "Could not use the picked folder", it) }
                    folder = com.vibecoder.vibedownloader.engine.MediaSaver.folderLabel(context)
                    custom = com.vibecoder.vibedownloader.engine.MediaSaver.usableFolder(context) != null
                }
            }
            ActionRow(Icons.Rounded.Folder, "Saved to", folder, "Change") { runCatching { picker.launch(null) } }
            AnimatedVisibility(custom, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        SoftButton("Use the default folder", null, {
                            com.vibecoder.vibedownloader.engine.MediaSaver.setFolder(context, null)
                            folder = com.vibecoder.vibedownloader.engine.MediaSaver.folderLabel(context)
                            custom = false
                        })
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Smart download button")
        SmartButtonCard(prefs)

        Spacer(Modifier.height(20.dp))
        SectionLabel("Storage")
        StorageCard()

        Spacer(Modifier.height(20.dp))
        SectionLabel("App")
        GlassCard(Modifier.fillMaxWidth()) {
            SwitchRow(Icons.Rounded.SystemUpdate, "Automatic update checks", "You choose when to install.", autoUpdate) { autoUpdate = it; prefs.autoUpdate = it }
            Spacer(Modifier.height(14.dp))
            val s = update
            val (updateHint, updateAction) = when (s) {
                is AppUpdater.State.Checking -> "Checking..." to null
                is AppUpdater.State.UpToDate -> "You have the latest version" to "Check"
                is AppUpdater.State.Available -> "Version ${s.release.version} is ready" to "Update now"
                is AppUpdater.State.Downloading -> "Downloading ${(s.progress * 100).toInt()}%" to null
                is AppUpdater.State.NeedsPermission -> "Allow installs to finish the update" to "Update now"
                is AppUpdater.State.Installing -> "Installing..." to null
                is AppUpdater.State.Problem -> s.message to "Try again"
                else -> "Version ${BuildConfig.VERSION_NAME}" to "Check"
            }
            ActionRow(Icons.Rounded.Update, "Check for updates", updateHint, updateAction, primary = s is AppUpdater.State.Available) {
                scope.launch {
                    if (s is AppUpdater.State.Available || s is AppUpdater.State.NeedsPermission) AppUpdater.downloadAndInstall(context)
                    else AppUpdater.check(context)
                }
            }
            AnimatedVisibility(s is AppUpdater.State.Downloading, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                GradientProgressBar((s as? AppUpdater.State.Downloading)?.progress ?: 0f, Modifier.padding(top = 12.dp))
            }
            Spacer(Modifier.height(14.dp))
            ActionRow(
                Icons.Rounded.Memory, "Download engine",
                when {
                    engineBusy -> engineStatus ?: "Checking..."
                    engineVersion != null -> "yt-dlp $engineVersion" + (engineStatus?.let { "  ·  $it" } ?: "")
                    else -> "yt-dlp  ·  starting..."
                },
                if (engineBusy) null else "Update now",
            ) {
                engineBusy = true
                scope.launch {
                    withContext(Dispatchers.IO) { EngineUpdater.check(context) }
                    engineBusy = false
                }
            }
            Spacer(Modifier.height(14.dp))
            InfoRow(Icons.Rounded.Info, "VibeDownloader for Android", "Version ${BuildConfig.VERSION_NAME}  ·  by VibeCoder AI")
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Help")
        GlassCard(Modifier.fillMaxWidth()) {
            ActionRow(Icons.Rounded.BugReport, "Send a report", "Shares a technical log to help fix a problem.", "Send") {
                scope.launch {
                    val file = withContext(Dispatchers.IO) { AppLog.buildReport(context) }
                    runCatching {
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_SUBJECT, "VibeDownloader report")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        send.clipData = ClipData.newRawUri("report", uri)
                        context.startActivity(Intent.createChooser(send, "Send a report"))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            ActionRow(Icons.Rounded.Gavel, "Licenses", "Open-source parts inside this app.", "View", onAction = onLicenses)
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
private fun ActionRow(
    icon: ImageVector, title: String, hint: String, action: String?, primary: Boolean = false, onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, size = 38)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Vd.Text)
            AnimatedContent(hint, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "hint") { h ->
                Text(h, style = MaterialTheme.typography.bodySmall, color = Vd.Muted)
            }
        }
        if (action != null) {
            Spacer(Modifier.width(10.dp))
            if (primary) GradientButton(action, null, onAction, Modifier.width(130.dp))
            else SoftButton(action, null, onAction)
        }
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

/**
 * "Smart download button": a small floating button that appears only when it is
 * useful. Android 8-9: when a video link is copied. Android 10+: while a video
 * app or browser is open (needs "Usage access": only which app is open, never
 * what is on screen). Each permission gets a friendly explanation first.
 */
@Composable
private fun SmartButtonCard(prefs: Prefs) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(prefs.smartButton && BubbleService.ready(context)) }
    var wanted by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<String?>(null) }   // "overlay" or "usage"

    fun next() {
        when {
            !Settings.canDrawOverlays(context) -> ask = "overlay"
            BubbleService.needsUsageAccess() && !BubbleService.hasUsageAccess(context) -> ask = "usage"
            else -> {
                wanted = false
                on = true
                prefs.smartButton = true
                BubbleService.start(context)
            }
        }
    }

    // back from the system settings page: carry on, or quietly stay off
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (wanted && ask == null) {
                    when {
                        !Settings.canDrawOverlays(context) -> wanted = false
                        BubbleService.needsUsageAccess() && !BubbleService.hasUsageAccess(context) -> if (askedUsage) wanted = false else next()
                        else -> next()
                    }
                }
                if (on && !BubbleService.ready(context)) { on = false; prefs.smartButton = false; BubbleService.stop(context) }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    GlassCard(Modifier.fillMaxWidth()) {
        SwitchRow(
            Icons.Rounded.TouchApp, "Smart download button",
            "A small floating button that appears only when it can help.", on,
        ) { v ->
            if (v) { wanted = true; askedUsage = false; next() }
            else { on = false; wanted = false; prefs.smartButton = false; BubbleService.stop(context) }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(Vd.AccentSoft)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            androidx.compose.material3.Icon(Icons.Rounded.Info, null, tint = Vd.AccentLight, modifier = Modifier.size(16.dp).padding(top = 2.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (BubbleService.needsUsageAccess())
                    "Shows while YouTube, Instagram, TikTok, Facebook, X or a browser is open. In the app tap Share \u2192 Copy link, then tap the button. Drag it to \u00D7 to hide it."
                else
                    "Pops up for a few seconds when you copy a video link. Tap it to download. Drag it to \u00D7 to hide it.",
                style = MaterialTheme.typography.bodySmall, color = Vd.Muted,
            )
        }
    }

    when (ask) {
        "overlay" -> ExplainDialog(
            Icons.Rounded.Layers, "Show the button over other apps",
            "To float over YouTube and other apps, Android needs \"Display over other apps\" turned on for VibeDownloader. " +
                "On the next page, find VibeDownloader and switch it on, then come back.",
            onOk = {
                ask = null
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { wanted = false }
            },
            onCancel = { ask = null; wanted = false },
        )
        "usage" -> ExplainDialog(
            Icons.Rounded.Visibility, "Know when a video app is open",
            "VibeDownloader only checks which app is open, never what is on your screen. " +
                "On the next page, find VibeDownloader and allow \"Usage access\", then come back.",
            onOk = {
                ask = null
                askedUsage = true
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { wanted = false }
            },
            onCancel = { ask = null; wanted = false },
        )
    }
}

/** Was the usage page already opened in this attempt (so coming back without it does not ask again). */
private var askedUsage = false

@Composable
private fun ExplainDialog(icon: ImageVector, title: String, body: String, onOk: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Vd.Card,
        icon = { androidx.compose.material3.Icon(icon, null, tint = Vd.AccentLight) },
        title = { Text(title, color = Vd.Text) },
        text = { Text(body, color = Vd.Muted, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onOk) { Text("Continue", color = Vd.AccentLight) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Not now", color = Vd.Muted) } },
    )
}
