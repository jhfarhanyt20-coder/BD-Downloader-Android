package com.vibecoder.vibedownloader.engine

import android.webkit.MimeTypeMap
import java.net.URLDecoder
import java.util.Locale

/** File names from servers and pages, made safe for Android storage. */
object FileNames {
    private val illegal = Regex("""[\\/:*?"<>|\u0000-\u001F\u007F]""")

    /** filename*=UTF-8''name.zip  or  filename="name.zip" */
    fun fromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(header)?.let { m ->
            val charset = m.groupValues[1].ifBlank { "UTF-8" }
            val raw = m.groupValues[2].trim().trim('"')
            runCatching { URLDecoder.decode(raw.replace("+", "%2B"), charset) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        Regex("""filename\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(header)?.let { m ->
            m.groupValues[1].takeIf { it.isNotBlank() }?.let { return it }
        }
        Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(header)?.let { m ->
            m.groupValues[1].trim().takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    fun fromUrl(url: String): String? {
        val path = runCatching { java.net.URI(url).rawPath }.getOrNull() ?: return null
        val last = path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
        return runCatching { URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last)
    }

    /** Removes characters Android or FAT storage refuse and keeps the name short. */
    fun sanitize(name: String?, fallback: String = "download"): String {
        var n = (name ?: "").replace(illegal, " ").replace(Regex("\\s+"), " ").trim().trim('.', ' ')
        if (n.isEmpty()) n = fallback
        if (n.length > 120) {
            val dot = n.lastIndexOf('.')
            val ext = if (dot > 0 && n.length - dot <= 10) n.substring(dot) else ""
            n = n.take(120 - ext.length).trim() + ext
        }
        return n
    }

    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1) name.substring(dot + 1).lowercase(Locale.US) else ""
    }

    /** Adds an extension from the server's type when the name has none. */
    fun withExtension(name: String, contentType: String?): String {
        if (extension(name).isNotEmpty()) return name
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.US) ?: return name
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: return name
        return "$name.$ext"
    }

    fun mimeFor(name: String): String {
        val ext = extension(name)
        if (ext.isEmpty()) return "application/octet-stream"
        return when (ext) {
            "m4a" -> "audio/mp4"
            "mp4", "m4v" -> "video/mp4"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
