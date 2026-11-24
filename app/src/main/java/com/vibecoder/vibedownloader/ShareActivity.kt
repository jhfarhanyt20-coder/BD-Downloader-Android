package com.vibecoder.vibedownloader

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.vibecoder.vibedownloader.ui.sheet.DownloadSheet
import com.vibecoder.vibedownloader.ui.sheet.readClipboard
import com.vibecoder.vibedownloader.ui.theme.Vd
import com.vibecoder.vibedownloader.ui.theme.VibeTheme
import com.vibecoder.vibedownloader.ui.theme.applySystemBars

/**
 * Opens as a small sheet ON TOP of the app the link was shared from (YouTube,
 * Chrome...). The user picks a quality and taps Download; the sheet closes and
 * the download runs in the background. Text with many links becomes a checklist.
 * Opened from the smart floating button, it reads the clipboard itself (it has
 * focus then, which Android 10+ requires).
 */
class ShareActivity : ComponentActivity() {
    private val text = mutableStateOf("")
    private var fromBubble = false
    private var clipChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Vd.prepare(this)
        applySystemBars(Vd.isDark)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        fromBubble = intent?.getBooleanExtra(EXTRA_FROM_BUBBLE, false) == true
        text.value = textFrom(intent) ?: ""
        if (extractLink(text.value) == null && !fromBubble) {
            Toast.makeText(this, "No link to download was shared", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // too old to keep working: open the app, which shows only "Update required"
        com.vibecoder.vibedownloader.update.AppUpdater.loadRequired(this)
        if (com.vibecoder.vibedownloader.update.AppUpdater.required.value != null) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finishQuietly()
            return
        }
        val prefs = Prefs(this)
        setContent {
            VibeTheme(translucent = true) {
                DownloadSheet(
                    text = text.value,
                    onTextChange = { text.value = it },
                    prefs = prefs,
                    fromBubble = fromBubble,
                    onClose = { finishQuietly() },
                    onShowItem = { id ->
                        startActivity(Intent(this, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_SHOW_ID, id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finishQuietly()
                    },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).apply {
                            val link = extractLink(text.value)
                            if (link != null) {
                                action = Intent.ACTION_SEND
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, link)
                            }
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                        finishQuietly()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tryClipboard()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android 10+ lets only the focused app read the clipboard: now we are
        if (hasFocus) tryClipboard()
    }

    private fun tryClipboard() {
        if (!fromBubble || clipChecked || extractLink(text.value) != null) return
        val clip = readClipboard(this)
        if (extractLink(clip) != null) {
            clipChecked = true
            text.value = clip ?: ""
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        textFrom(intent)?.let { text.value = it }
    }

    private fun textFrom(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { extractLink(it) != null } else null

    private fun finishQuietly() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_FROM_BUBBLE = "from_bubble"
    }
}
