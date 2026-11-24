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

    /** A folder the user picked for downloads (a document tree URI); null = Download/VibeDownloader. */
    var saveFolder: String?
        get() = sp.getString("saveFolder", null)
        set(v) = sp.edit().putString("saveFolder", v).apply()

    /** The quality last picked in the share popup ("1080", "audio" ...), picked first next time. */
    var lastQuality: String?
        get() = sp.getString("lastQuality", null)
        set(v) = sp.edit().putString("lastQuality", v).apply()

    /** "System", "Light" or "Dark" (see ui/theme/ThemeMode). */
    var themeMode: String?
        get() = sp.getString("themeMode", null)
        set(v) = sp.edit().putString("themeMode", v).apply()

    /** The smart floating download button is turned on. */
    var smartButton: Boolean
        get() = sp.getBoolean("smartButton", false)
        set(v) = sp.edit().putBoolean("smartButton", v).apply()

    /** Where the floating button was left: height on screen (px) and which edge. */
    var bubbleY: Int
        get() = sp.getInt("bubbleY", -1)
        set(v) = sp.edit().putInt("bubbleY", v).apply()

    var bubbleRight: Boolean
        get() = sp.getBoolean("bubbleRight", true)
        set(v) = sp.edit().putBoolean("bubbleRight", v).apply()

    /** The quality picked last time for "Add all to queue". */
    var batchQuality: String
        get() = sp.getString("batchQuality", "best") ?: "best"
        set(v) = sp.edit().putString("batchQuality", v).apply()

    /** The oldest app version the latest release still allows (older apps must update). */
    var minAppVersion: String?
        get() = sp.getString("minAppVersion", null)
        set(v) = sp.edit().putString("minAppVersion", v).apply()

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

/** Every distinct http(s) link in a piece of text, in order (for pasting or sharing many at once). */
fun extractLinks(text: String?): List<String> {
    if (text.isNullOrBlank()) return emptyList()
    return Regex("""https?://[^\s<>"']+""").findAll(text)
        .map { it.value.trimEnd('.', ',', ')', ']', '!', '?', ';') }
        .filter { runCatching { java.net.URI(it).host }.getOrNull()?.isNotBlank() == true }
        .distinct().take(100).toList()
}

/** Video and audio sites the media engine knows well (also used by the browser and the floating button). */
val VIDEO_SITES = listOf("youtube.com", "youtu.be", "youtube-nocookie.com", "facebook.com", "fb.watch", "fb.com",
    "instagram.com", "tiktok.com", "x.com", "twitter.com", "vimeo.com", "dailymotion.com", "dai.ly", "reddit.com",
    "redd.it", "twitch.tv", "soundcloud.com", "bandcamp.com", "bilibili.com", "pinterest.com", "pin.it",
    "threads.net", "linkedin.com", "tumblr.com", "streamable.com", "rumble.com", "ok.ru", "vk.com", "mixcloud.com")

private val MEDIA_EXT = Regex("""\.(mp4|m4a|m4v|mp3|webm|m3u8|mkv|mov|aac|ogg|opus|flac|wav)$""", RegexOption.IGNORE_CASE)

/** True for a page on a known video site, or a direct video/audio file link. */
fun isMediaLink(url: String): Boolean {
    val host = runCatching { java.net.URI(url).host?.lowercase() ?: "" }.getOrDefault("")
    if (host.isEmpty()) return false
    if (VIDEO_SITES.any { host == it || host.endsWith(".$it") }) return true
    val path = runCatching { java.net.URI(url).path ?: "" }.getOrDefault("")
    return MEDIA_EXT.containsMatchIn(path)
}

/** What kind of link it is, for the label under the box. */
fun linkKind(url: String): String {
    val host = runCatching { java.net.URI(url).host?.lowercase() ?: "" }.getOrDefault("")
    val videoSites = VIDEO_SITES
    return when {
        host.isEmpty() -> "Not a valid link"
        videoSites.any { host == it || host.endsWith(".$it") } -> "Video or audio page"
        else -> "Direct file or web page"
    }
}
