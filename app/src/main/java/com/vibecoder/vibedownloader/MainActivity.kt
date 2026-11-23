package com.vibecoder.vibedownloader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.vibecoder.vibedownloader.ui.screens.DownloadsScreen
import com.vibecoder.vibedownloader.ui.screens.NewDownloadScreen
import com.vibecoder.vibedownloader.ui.screens.SettingsScreen
import com.vibecoder.vibedownloader.ui.theme.Vd
import com.vibecoder.vibedownloader.ui.theme.VibeTheme

class MainActivity : ComponentActivity() {
    // a link shared from another app; the New download page picks it up
    private val sharedLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the app is always dark, so the clock and status icons are always light
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        readShare(intent)
        val prefs = Prefs(this)
        setContent {
            VibeTheme {
                App(prefs, sharedLink.value) { sharedLink.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readShare(intent)
    }

    private fun readShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            extractLink(intent.getStringExtra(Intent.EXTRA_TEXT))?.let { sharedLink.value = it }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Downloads("Downloads", Icons.Rounded.DownloadForOffline),
    New("New", Icons.Rounded.AddCircle),
    Settings("Settings", Icons.Rounded.Settings),
}

@Composable
private fun App(prefs: Prefs, sharedLink: String?, onSharedLinkUsed: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(Tab.Downloads.ordinal) }
    // a shared link jumps straight to the New download page
    androidx.compose.runtime.LaunchedEffect(sharedLink) { if (sharedLink != null) tab = Tab.New.ordinal }

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
                    Tab.Downloads -> DownloadsScreen(onNew = { tab = Tab.New.ordinal })
                    Tab.New -> NewDownloadScreen(sharedLink, onSharedLinkUsed)
                    Tab.Settings -> SettingsScreen(prefs)
                }
            }
        }
        BottomBar(tab) { tab = it }
    }
}

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Vd.Surface)
            .border(1.dp, Vd.Line, shape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { t ->
            val on = t.ordinal == selected
            val bg by animateColorAsState(if (on) Color(0x336A3DF5) else Color.Transparent, tween(220), label = "tabBg")
            val fg by animateColorAsState(if (on) Vd.Text else Vd.Faint, tween(220), label = "tabFg")
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(bg)
                    .clickable(remember { MutableInteractionSource() }, null) { onSelect(t.ordinal) }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(t.icon, t.label, tint = if (on) Vd.AccentLight else fg, modifier = Modifier.size(22.dp))
                if (on) {
                    Text("  " + t.label, style = MaterialTheme.typography.labelLarge, color = fg)
                }
            }
        }
    }
}
