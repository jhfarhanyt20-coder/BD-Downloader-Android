package com.vibecoder.vibedownloader.update

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibecoder.vibedownloader.BuildConfig
import com.vibecoder.vibedownloader.ui.components.GradientButton
import com.vibecoder.vibedownloader.ui.components.GradientProgressBar
import com.vibecoder.vibedownloader.ui.theme.Vd
import kotlinx.coroutines.launch

/**
 * Shown instead of the app when this version is more than three releases old.
 * The only thing the user can do is update (download inside the app, then the
 * phone's own installer opens).
 */
@Composable
fun UpdateRequiredScreen(oldestAllowed: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by AppUpdater.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (state is AppUpdater.State.Idle || state is AppUpdater.State.UpToDate) AppUpdater.check(context, quiet = true) }

    Box(Modifier.fillMaxSize().background(Vd.Bg).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(Vd.AccentSoft), contentAlignment = Alignment.Center) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(Vd.Accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SystemUpdate, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("Update required", style = MaterialTheme.typography.headlineMedium, color = Vd.Text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "This version of BD Downloader (${BuildConfig.VERSION_NAME}) is no longer supported. " +
                    "Install the latest version to keep downloading. Your downloads and settings stay.",
                style = MaterialTheme.typography.bodyMedium, color = Vd.Muted, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(26.dp))
            val s = state
            if (s is AppUpdater.State.Downloading) {
                Text("Downloading ${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = Vd.Muted)
                GradientProgressBar(s.progress, Modifier.padding(top = 10.dp))
            } else {
                val label = when (s) {
                    is AppUpdater.State.Ready, is AppUpdater.State.NeedsPermission -> "Install now"
                    is AppUpdater.State.Checking -> "Checking..."
                    is AppUpdater.State.Installing -> "Opening the installer..."
                    else -> "Update now"
                }
                GradientButton(label, Icons.Rounded.SystemUpdate, {
                    scope.launch {
                        when (s) {
                            is AppUpdater.State.Ready, is AppUpdater.State.NeedsPermission -> AppUpdater.installNow(context)
                            else -> if (AppUpdater.download(context)) AppUpdater.installNow(context)
                            else Toast.makeText(context, "Couldn't get the update. Check your internet and try again.", Toast.LENGTH_LONG).show()
                        }
                    }
                }, Modifier.fillMaxWidth(), enabled = s !is AppUpdater.State.Checking && s !is AppUpdater.State.Installing)
            }
            Spacer(Modifier.height(12.dp))
            Text("Oldest supported version: $oldestAllowed", style = MaterialTheme.typography.bodySmall, color = Vd.Faint)
        }
    }
}
