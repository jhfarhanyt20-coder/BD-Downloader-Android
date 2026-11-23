package com.vibecoder.vibedownloader.util

import java.util.Locale

/** 1.2 MB, 640 KB ... */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.size - 1) { value /= 1024; i++ }
    return if (i == 0) "$bytes B" else String.format(Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[i])
}

fun formatSpeed(bytesPerSecond: Long): String = formatBytes(bytesPerSecond) + "/s"

/** "12 s left", "3 min left", "1 h 5 min left". */
fun formatEta(seconds: Long): String = when {
    seconds <= 0 -> ""
    seconds < 60 -> "$seconds s left"
    seconds < 3600 -> "${(seconds + 30) / 60} min left"
    else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min left"
}

/** 3:25 or 1:02:03. */
fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}
