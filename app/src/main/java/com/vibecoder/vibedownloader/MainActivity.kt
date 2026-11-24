package com.vibecoder.vibedownloader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.vibecoder.vibedownloader.data.DownloadRepository
import com.vibecoder.vibedownloader.engine.Analysis
import com.vibecoder.vibedownloader.engine.AppLog
import com.vibecoder.vibedownloader.engine.EngineUpdater
import com.vibecoder.vibedownloader.engine.MediaEngine
import com.vibecoder.vibedownloader.service.BubbleService
import com.vibecoder.vibedownloader.service.DownloadQueue
import com.vibecoder.vibedownloader.service.Notifier
import com.vibecoder.vibedownloader.ui.components.GradientButton
import com.vibecoder.vibedownloader.ui.components.SoftButton
import com.vibecoder.vibedownloader.ui.screens.BrowserScreen
import com.vibecoder.vibedownloader.ui.screens.BrowserState
import com.vibecoder.vibedownloader.ui.screens.DownloadsScreen
import com.vibecoder.vibedownloader.ui.screens.LicensesScreen
import com.vibecoder.vibedownloader.ui.screens.NewDownloadScreen
import com.vibecoder.vibedownloader.ui.screens.SettingsScreen
import com.vibecoder.vibedownloader.ui.sheet.DownloadSheet
import com.vibecoder.vibedownloader.ui.theme.Vd
import com.vibecoder.vibedownloader.ui.theme.VibeTheme
import com.vibecoder.vibedownloader.ui.theme.applySystemBars
import com.vibecoder.vibedownloader.update.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    // a link (or several) shared from another app; the New download page picks it up
    private val sharedLink = mutableStateOf<String?>(null)
    // a download to show (from a notification, the share sheet or a duplicate card)
    private val showId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // colours and bar icons are ready before the first frame, so nothing flickers
        Vd.prepare(this)
        applySystemBars(Vd.isDark)
        readShare(intent)
        val prefs = Prefs(this)
        // a version more than three releases old works only to update itself
        AppUpdater.loadRequired(this)
        lifecycleScope.launch { AppUpdater.checkRequired(this@MainActivity) }
        setContent {
            VibeTheme {
                val required by AppUpdater.required.collectAsStateWithLifecycle()
                val oldest = required
                if (oldest != null) com.vibecoder.vibedownloader.update.UpdateRequiredScreen(oldest)
                else App(prefs, sharedLink.value, { sharedLink.value = null }, showId.value) { showId.value = null }
            }
        }
        BubbleService.startIfEnabled(this)
    }

    override fun onResume() {
        super.onResume()
        // back from the "Install unknown apps" page: finish a waiting update
        lifecycleScope.launch { AppUpdater.continueAfterPermission(this@MainActivity) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readShare(intent)
    }

    private fun readShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            // many links stay together, so the New page can offer "Add all to queue"
            if (extractLinks(text).size > 1) sharedLink.value = text
            else extractLink(text)?.let { sharedLink.value = it }
        }
        intent?.getStringExtra(EXTRA_SHOW_ID)?.let { showId.value = it }
    }

    companion object {
        const val EXTRA_SHOW_ID = "show_id"
    }
}

/** A download sheet opened inside the app (from the browser). */
private data class InAppSheet(val text: String, val file: Analysis.File? = null, val nonce: Long = System.nanoTime())

private enum class Tab(val label: String, val icon: ImageVector) {
    Downloads("Downloads", Icons.Rounded.DownloadForOffline),
    New("New", Icons.Rounded.AddCircle),
    Browser("Browser", Icons.Rounded.Public),
    Settings("Settings", Icons.Rounded.Settings),
}

@Composable
private fun App(prefs: Prefs, sharedLink: String?, onSharedLinkUsed: () -> Unit, showId: String?, onShowIdUsed: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(Tab.Downloads.ordinal) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    var highlight by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<InAppSheet?>(null) }
    val context = LocalContext.current
    val browser = remember { BrowserState() }
    DisposableEffect(Unit) { onDispose { browser.destroy() } }
    browser.onFile = { f -> sheet = InAppSheet(f.url, f) }
    val showItem: (String) -> Unit = { id -> highlight = id; tab = Tab.Downloads.ordinal }
    // a shared link jumps straight to the New download page
    LaunchedEffect(sharedLink) { if (sharedLink != null) tab = Tab.New.ordinal }
    LaunchedEffect(showId) { if (showId != null) { showItem(showId); onShowIdUsed() } }

    // background start-up work, never on the main thread
    LaunchedEffect(Unit) {
        DownloadRepository.loaded.first { it }
        DownloadQueue.resumeInterrupted()
        delay(1500)
        AppUpdater.checkIfDue(context)
        withContext(Dispatchers.IO) {
            // warm up the video engine so the first link opens fast, then a daily yt-dlp check
            runCatching { MediaEngine.ensure() }.onFailure { AppLog.e("engine", "Engine start failed", it) }
            EngineUpdater.checkIfDue(context)
        }
    }

    // permissions are asked kindly, only when the first download starts
    var pendingStart by remember { mutableStateOf<(() -> Unit)?>(null) }
    var askNotifications by remember { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingStart?.invoke()
        pendingStart = null
    }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val next = pendingStart
        pendingStart = null
        if (granted) next?.let { startWithPermissions(context, prefs, it, { a -> pendingStart = a }, { askNotifications = true }, null) }
        else Toast.makeText(context, "VibeDownloader needs storage access to save files in Downloads", Toast.LENGTH_LONG).show()
    }
    val requestStart: (() -> Unit) -> Unit = { action ->
        startWithPermissions(context, prefs, action, { a -> pendingStart = a }, { askNotifications = true }) {
            storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Vd.Bg).statusBarsPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp)) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(tween(280)) { it / 8 * dir } + fadeIn(tween(280)))
                            .togetherWith(slideOutHorizontally(tween(200)) { -it / 8 * dir } + fadeOut(tween(200)))
                    },
                    label = "page",
                ) { page ->
                    when (Tab.entries[page]) {
                        Tab.Downloads -> DownloadsScreen(onNew = { tab = Tab.New.ordinal }, highlightId = highlight) { highlight = null }
                        Tab.New -> NewDownloadScreen(sharedLink, onSharedLinkUsed, requestStart, onShowItem = showItem) { tab = Tab.Downloads.ordinal }
                        Tab.Browser -> BrowserScreen(browser) { url -> sheet = InAppSheet(url) }
                        Tab.Settings -> AnimatedContent(
                            targetState = showLicenses,
                            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(180)) },
                            label = "settingsPage",
                        ) { licenses ->
                            if (licenses) LicensesScreen { showLicenses = false }
                            else SettingsScreen(prefs) { showLicenses = true }
                        }
                    }
                }
            }
            BottomBar(tab) { tab = it }
        }

        // the same quality sheet as the share popup, as a bottom sheet inside the app
        sheet?.let { s ->
            var text by remember(s) { mutableStateOf(s.text) }
            DownloadSheet(
                text = text, onTextChange = { text = it }, prefs = prefs,
                presetFile = s.file, inApp = true,
                onClose = { sheet = null },
                onShowItem = { id -> sheet = null; showItem(id) },
            )
        }
    }

    if (askNotifications) {
        NotificationAsk(
            onAllow = {
                askNotifications = false
                prefs.askedNotifications = true
                if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else { pendingStart?.invoke(); pendingStart = null }
            },
            onLater = {
                askNotifications = false
                prefs.askedNotifications = true
                pendingStart?.invoke()
                pendingStart = null
            },
        )
    }
    InstallPermissionSheet()
}

/**
 * Android 8-9: the storage permission first. Android 13+: a friendly note
 * about notifications once. Then the download starts.
 */
private fun startWithPermissions(
    context: Context,
    prefs: Prefs,
    action: () -> Unit,
    park: (() -> Unit) -> Unit,
    askNotifications: () -> Unit,
    askStorage: (() -> Unit)?,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && askStorage != null &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    ) {
        park(action)
        askStorage()
        return
    }
    if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications && !Notifier.canPost(context)) {
        park(action)
        askNotifications()
        return
    }
    action()
}

@Composable
private fun NotificationAsk(onAllow: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        containerColor = Vd.Card,
        icon = { Icon(Icons.Rounded.NotificationsActive, null, tint = Vd.AccentLight) },
        title = { Text("Stay in the loop", color = Vd.Text) },
        text = {
            Text(
                "Allow notifications to see download progress, pause from anywhere, and know when your files are ready.",
                color = Vd.Muted, style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { TextButton(onClick = onAllow) { Text("Allow", color = Vd.AccentLight) } },
        dismissButton = { TextButton(onClick = onLater) { Text("Not now", color = Vd.Muted) } },
    )
}

/** Shown when Android first needs "Install unknown apps" allowed for the update. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallPermissionSheet() {
    val state by AppUpdater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dismissed by remember { mutableStateOf<Any?>(null) }
    val s = state
    if (s is AppUpdater.State.NeedsPermission && dismissed !== s) {
        ModalBottomSheet(
            onDismissRequest = { dismissed = s },
            containerColor = Vd.Surface,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
                Icon(Icons.Rounded.SystemUpdate, null, tint = Vd.AccentLight, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(12.dp))
                Text("One quick step", style = MaterialTheme.typography.titleLarge, color = Vd.Text)
                Spacer(Modifier.height(6.dp))
                Text(
                    "To install version ${s.release.version}, Android asks you to allow VibeDownloader to install apps. " +
                        "Turn on \"Allow from this source\", then come back - the update continues by itself.",
                    style = MaterialTheme.typography.bodyMedium, color = Vd.Muted,
                )
                Spacer(Modifier.height(20.dp))
                GradientButton("Open settings", Icons.Rounded.Settings, {
                    runCatching { context.startActivity(AppUpdater.permissionIntent(context)) }
                }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                SoftButton("Not now", null, { dismissed = s }, Modifier.fillMaxWidth())
            }
        }
    }
}

/** The pill bar: the selected tab grows smoothly and shows its name. */
@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Vd.Surface)
            .border(1.dp, Vd.Line, shape)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Tab.entries.forEach { t ->
            val on = t.ordinal == selected
            val bg by animateColorAsState(if (on) Vd.AccentSelected else Color.Transparent, tween(220), label = "tabBg")
            val fg by animateColorAsState(if (on) Vd.Text else Vd.Faint, tween(220), label = "tabFg")
            val w by animateFloatAsState(if (on) 2f else 1f, tween(260), label = "tabW")
            Row(
                Modifier
                    .weight(w)
                    .clip(RoundedCornerShape(20.dp))
                    .background(bg)
                    .clickable(remember { MutableInteractionSource() }, null) { onSelect(t.ordinal) }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(t.icon, t.label, tint = if (on) Vd.AccentLight else fg, modifier = Modifier.size(22.dp))
                AnimatedVisibility(
                    on,
                    enter = fadeIn(tween(200)) + expandHorizontally(tween(240)),
                    exit = fadeOut(tween(120)) + shrinkHorizontally(tween(200)),
                ) {
                    Text("  " + t.label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
