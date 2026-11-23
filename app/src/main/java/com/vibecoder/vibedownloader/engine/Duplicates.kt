package com.vibecoder.vibedownloader.engine

import android.content.Context
import com.vibecoder.vibedownloader.data.DownloadItem
import com.vibecoder.vibedownloader.data.DownloadRepository
import com.vibecoder.vibedownloader.data.Kind
import com.vibecoder.vibedownloader.data.Status
import com.vibecoder.vibedownloader.service.isYouTube
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/**
 * Finds a download that is already in the list before a new one is queued:
 *  - direct files: the same address once tracking bits (utm_*, fbclid...) and the #fragment are removed
 *  - YouTube: the same video id with the same quality
 *  - other video sites: the same page address or video id the engine reported
 */
object Duplicates {
    sealed class Match(val item: DownloadItem) {
        /** Finished, and the file is still there. */
        class Finished(item: DownloadItem) : Match(item)
        /** Waiting, running or paused in the list. */
        class InList(item: DownloadItem) : Match(item)
    }

    private val TRACKING = setOf("fbclid", "gclid", "dclid", "msclkid", "igshid", "igsh", "mc_cid", "mc_eid", "_ga", "_gl",
        "yclid", "twclid", "ttclid", "si", "feature", "ref_src", "ref_url", "s_cid", "spm", "share_id", "mibextid", "rdid")

    /** Lower-case scheme and host, no "www.", no fragment, no tracking parameters, the rest sorted. */
    fun normalize(url: String): String {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return url.trim().substringBefore('#')
        val host = (u.host ?: return url.trim().substringBefore('#')).lowercase(Locale.US).removePrefix("www.").removePrefix("m.")
        val scheme = (u.scheme ?: "https").lowercase(Locale.US).let { if (it == "http") "https" else it }
        val port = if (u.port == -1 || u.port == 80 || u.port == 443) "" else ":${u.port}"
        val path = (u.rawPath ?: "").ifEmpty { "/" }.let { if (it.length > 1) it.trimEnd('/') else it }
        val query = (u.rawQuery ?: "").split('&').filter { it.isNotBlank() }.filter { p ->
            val name = p.substringBefore('=').lowercase(Locale.US)
            !name.startsWith("utm_") && name !in TRACKING
        }.sorted().joinToString("&")
        return "$scheme://$host$port$path" + (if (query.isNotEmpty()) "?$query" else "")
    }

    /** The 11-character video id of any YouTube address (watch, youtu.be, shorts, embed, live, music). */
    fun youtubeId(url: String): String? {
        if (!isYouTube(url)) return null
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        val host = u.host?.lowercase(Locale.US) ?: return null
        val path = u.path ?: ""
        val id = when {
            host == "youtu.be" -> path.trim('/').substringBefore('/')
            path.startsWith("/watch") -> (u.rawQuery ?: "").split('&').firstOrNull { it.startsWith("v=") }?.substringAfter("v=")
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            else -> Regex("""^/(shorts|embed|live|v|e)/([^/?#]+)""").find(path)?.groupValues?.get(2)
        } ?: return null
        return id.takeIf { Regex("""^[A-Za-z0-9_-]{6,20}$""").matches(it) }
    }

    /** The keys a new or existing download is known by. */
    fun keysOf(url: String, kind: Kind, quality: String, extra: String = ""): Set<String> {
        val yt = youtubeId(url)
        if (yt != null) return setOf("yt:$yt:${if (kind == Kind.File) "file" else quality}")
        val out = mutableSetOf("u:" + normalize(url))
        extra.split(' ').filter { it.isNotBlank() }.forEach { out += it }
        return out
    }

    fun keysOf(item: DownloadItem): Set<String> = keysOf(item.url, item.kind, item.quality, item.keys)

    /** Extra keys for a looked-at video page: its real address and its id on that site. */
    fun mediaKeys(webpage: String?, site: String, id: String?): String = listOfNotNull(
        webpage?.takeIf { it.startsWith("http") }?.let { "u:" + normalize(it) },
        id?.takeIf { it.isNotBlank() }?.let { "m:${site.lowercase(Locale.US)}:$it" },
    ).joinToString(" ")

    /** Blocking (checks the file still exists): call from a background thread. */
    fun find(context: Context, keys: Set<String>): Match? {
        if (keys.isEmpty()) return null
        val hits = DownloadRepository.items.value.filter { keysOf(it).any { k -> k in keys } }
        hits.firstOrNull { it.status != Status.Done }?.let { return Match.InList(it) }
        hits.filter { it.status == Status.Done }.sortedByDescending { it.finishedAt }.forEach {
            if (MediaSaver.exists(context, it.savedUri, it.savedPath)) return Match.Finished(it)
        }
        return null
    }

    fun find(context: Context, item: DownloadItem): Match? = find(context, keysOf(item))
}
