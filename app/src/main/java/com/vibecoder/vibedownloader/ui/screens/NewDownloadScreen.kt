package com.vibecoder.vibedownloader.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vibecoder.vibedownloader.extractLink
import com.vibecoder.vibedownloader.linkKind
import com.vibecoder.vibedownloader.ui.components.GlassCard
import com.vibecoder.vibedownloader.ui.components.GradientButton
import com.vibecoder.vibedownloader.ui.components.IconBadge
import com.vibecoder.vibedownloader.ui.components.PageHeader
import com.vibecoder.vibedownloader.ui.components.SectionLabel
import com.vibecoder.vibedownloader.ui.components.SoftButton
import com.vibecoder.vibedownloader.ui.theme.Vd

@Composable
fun NewDownloadScreen(sharedLink: String?, onSharedLinkUsed: () -> Unit) {
    val context = LocalContext.current
    var link by remember { mutableStateOf("") }
    var clipboardLink by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    // a link shared from another app fills the box at once
    LaunchedEffect(sharedLink) {
        if (sharedLink != null) { link = sharedLink; onSharedLinkUsed() }
    }
    // offer a link that is waiting on the clipboard
    LaunchedEffect(Unit) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
        clipboardLink = extractLink(text)
    }

    val valid = extractLink(link) != null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        PageHeader("New download", "Paste a link to a file, a video or a song.")

        GlassCard(Modifier.fillMaxWidth()) {
            SectionLabel("Link")
            OutlinedTextField(
                value = link,
                onValueChange = { link = it; notice = null },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://", color = Vd.Faint) },
                leadingIcon = { Icon(Icons.Rounded.Link, null, tint = Vd.Muted) },
                trailingIcon = {
                    if (link.isNotEmpty()) IconButton(onClick = { link = ""; notice = null }) {
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

            AnimatedVisibility(valid, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                val url = extractLink(link) ?: ""
                val kind = linkKind(url)
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(if (kind.startsWith("Video")) Icons.Rounded.OndemandVideo else Icons.Rounded.InsertDriveFile, size = 36)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(kind, style = MaterialTheme.typography.titleMedium, color = Vd.Text)
                        Text(runCatching { java.net.URI(url).host }.getOrNull() ?: url, style = MaterialTheme.typography.bodySmall, color = Vd.Muted)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        GradientButton(
            "Start download", Icons.Rounded.Download,
            onClick = { notice = "Downloading is added in the next test version. This version shows the new design." },
            modifier = Modifier.fillMaxWidth(), enabled = valid,
        )
        AnimatedVisibility(notice != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(notice ?: "", style = MaterialTheme.typography.bodySmall, color = Vd.Amber, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}
