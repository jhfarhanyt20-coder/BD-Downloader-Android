package com.vibecoder.vibedownloader.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vibecoder.vibedownloader.ui.components.GlassCard
import com.vibecoder.vibedownloader.ui.theme.Vd

private data class Notice(val name: String, val license: String, val note: String)

private val Notices = listOf(
    Notice("yt-dlp", "The Unlicense",
        "yt-dlp is free and unencumbered software released into the public domain. It finds and downloads video and audio from web pages."),
    Notice("Chaquopy", "MIT License",
        "Copyright (c) Chaquo Ltd. Runs Python inside the app. Permission is granted to use, copy, modify and distribute it, provided the copyright and permission notice are included."),
    Notice("Python", "Python Software Foundation License",
        "Copyright (c) Python Software Foundation. The Python runtime included through Chaquopy."),
    Notice("OkHttp", "Apache License 2.0",
        "Copyright Square, Inc. The HTTP client used for fast multi-connection downloads."),
    Notice("AndroidX and Jetpack Compose", "Apache License 2.0",
        "Copyright The Android Open Source Project. The building blocks of the app and its interface."),
    Notice("Kotlin", "Apache License 2.0",
        "Copyright JetBrains s.r.o. and Kotlin contributors. The language and standard library of the app."),
)

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = Vd.Text) }
            Spacer(Modifier.width(4.dp))
            Column {
                Text("Licenses", style = MaterialTheme.typography.headlineMedium, color = Vd.Text)
                Text("Open-source parts inside VibeDownloader.", style = MaterialTheme.typography.bodyMedium, color = Vd.Muted)
            }
        }
        // the app's own license first: proprietary, all rights reserved
        GlassCard(Modifier.fillMaxWidth(), padding = 16) {
            Text("VibeDownloader for Android", style = MaterialTheme.typography.titleMedium, color = Vd.Text)
            Text("Proprietary license · All rights reserved", style = MaterialTheme.typography.labelMedium, color = Vd.Accent2)
            Spacer(Modifier.height(6.dp))
            Text(
                "Copyright (c) 2026 JH Farhan (VibeCoder AI). You may install and use the official app from the official release " +
                    "page or website for your own personal use. You may not copy, modify, decompile, re-sign, repackage, rebrand, " +
                    "upload to any app store or website, sell or redistribute it, or use its code or design to build or train anything " +
                    "else. You are responsible for only downloading content you have the right to download. " +
                    "Full text: github.com/jhfarhanyt20-coder/VibeDownloader-Android/blob/main/LICENSE",
                style = MaterialTheme.typography.bodySmall, color = Vd.Muted,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text("OPEN-SOURCE PARTS", style = MaterialTheme.typography.labelMedium, color = Vd.Faint, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Notices.forEach { n ->
                GlassCard(Modifier.fillMaxWidth(), padding = 16) {
                    Text(n.name, style = MaterialTheme.typography.titleMedium, color = Vd.Text)
                    Text(n.license, style = MaterialTheme.typography.labelMedium, color = Vd.Accent2)
                    Spacer(Modifier.height(6.dp))
                    Text(n.note, style = MaterialTheme.typography.bodySmall, color = Vd.Muted)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
