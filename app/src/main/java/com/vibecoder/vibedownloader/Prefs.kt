package com.vibecoder.vibedownloader

import android.content.Context

/** App settings, kept on the phone only (same defaults as the desktop app). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var connections: Int
        get() = sp.getInt("connections", 8).coerceIn(1, 16)
        set(v) = sp.edit().putInt("connections", v.coerceIn(1, 16)).apply()

    var simultaneous: Int
        get() = sp.getInt("simultaneous", 3).coerceIn(1, 5)
        set(v) = sp.edit().putInt("simultaneous", v.coerceIn(1, 5)).apply()

    var wifiOnly: Boolean
        get() = sp.getBoolean("wifiOnly", false)
        set(v) = sp.edit().putBoolean("wifiOnly", v).apply()

    var autoUpdate: Boolean
        get() = sp.getBoolean("autoUpdate", true)
        set(v) = sp.edit().putBoolean("autoUpdate", v).apply()

    /** When the app last looked for a new version of itself (ms). */
    var lastAppUpdateCheck: Long
        get() = sp.getLong("lastAppUpdateCheck", 0L)
        set(v) = sp.edit().putLong("lastAppUpdateCheck", v).apply()

    /** When the app last looked for a new yt-dlp (ms). */
    var lastEngineCheck: Long
        get() = sp.getLong("lastEngineCheck", 0L)
        set(v) = sp.edit().putLong("lastEngineCheck", v).apply()

    /** Path of the verified, downloaded yt-dlp copy in use (null = the bundled one). */
    var engineZip: String?
        get() = sp.getString("engineZip", null)
        set(v) = sp.edit().putString("engineZip", v).apply()

    /** The quality last picked in the share popup ("1080", "audio" ...), picked first next time. */
    var lastQuality: String?
        get() = sp.getString("lastQuality", null)
        set(v) = sp.edit().putString("lastQuality", v).apply()

    /** The friendly "allow notifications" card was shown once. */
    var askedNotifications: Boolean
        get() = sp.getBoolean("askedNotifications", false)
        set(v) = sp.edit().putBoolean("askedNotifications", v).apply()
}

/** Pulls the first http(s) link out of shared or pasted text ("Watch this: https://..."). */
fun extractLink(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val m = Regex("""https?://[^\s<>"']+""").find(text) ?: return null
    return m.value.trimEnd('.', ',', ')', ']', '!', '?')
}

/** What kind of link it is, for the label under the box. */
fun linkKind(url: String): String {
    val host = runCatching { java.net.URI(url).host?.lowercase() ?: "" }.getOrDefault("")
    val videoSites = listOf("youtube.com", "youtu.be", "facebook.com", "fb.watch", "instagram.com", "tiktok.com",
        "x.com", "twitter.com", "vimeo.com", "dailymotion.com", "reddit.com", "twitch.tv", "soundcloud.com")
    return when {
        host.isEmpty() -> "Not a valid link"
        videoSites.any { host == it || host.endsWith(".$it") } -> "Video or audio page"
        else -> "Direct file or web page"
    }
}
