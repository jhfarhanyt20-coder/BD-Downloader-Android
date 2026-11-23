package com.vibecoder.vibedownloader.engine

import android.content.Context
import com.vibecoder.vibedownloader.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException

/**
 * Keeps yt-dlp fresh without an app update: fetches the newest official
 * release (the `yt-dlp` zipapp from github.com/yt-dlp/yt-dlp), checks its
 * SHA-256 against the release's SHA2-256SUMS, and loads it ahead of the
 * bundled copy. If the new copy fails to load, the bundled one stays.
 */
object EngineUpdater {
    private const val REPO = "/yt-dlp/yt-dlp/"
    private const val DAY = 24L * 60 * 60 * 1000
    private const val AFTER_FAILURE_GAP = 30L * 60 * 1000
    private const val MAX_SIZE = 60L * 1024 * 1024

    enum class Result { UpToDate, Updated, Failed, Skipped }

    private val _status = MutableStateFlow<String?>(null)
    /** Short text for Settings while checking ("Checking...", "Updated"). */
    val status: StateFlow<String?> = _status

    @Volatile private var lastFailureCheck = 0L

    /** At most once a day. */
    fun checkIfDue(context: Context): Result {
        val prefs = Prefs(context)
        if (System.currentTimeMillis() - prefs.lastEngineCheck < DAY) return Result.Skipped
        return check(context)
    }

    /** Right after a download failed: at most every 30 minutes. */
    fun checkAfterFailure(context: Context): Result {
        val now = System.currentTimeMillis()
        if (now - lastFailureCheck < AFTER_FAILURE_GAP) return Result.Skipped
        lastFailureCheck = now
        return check(context)
    }

    @Synchronized
    fun check(context: Context): Result {
        val prefs = Prefs(context)
        _status.value = "Checking..."
        try {
            MediaEngine.ensure()
            val current = MediaEngine.version.value ?: ""
            val tag = latestTag()
            prefs.lastEngineCheck = System.currentTimeMillis()
            if (!isNewer(tag, current)) {
                _status.value = "Up to date"
                return Result.UpToDate
            }
            _status.value = "Updating..."
            val dir = File(context.filesDir, "engine").apply { mkdirs() }
            val target = File(dir, "yt-dlp-$tag.zip")
            if (!target.exists()) {
                val expected = expectedSha(tag)
                val tmp = File(dir, "yt-dlp-$tag.download")
                tmp.delete()
                SafeFetch.open("https://github.com$REPO" + "releases/download/$tag/yt-dlp", SafeFetch.githubAllow(REPO)).use { resp ->
                    val len = resp.body?.contentLength() ?: -1
                    if (len > MAX_SIZE) throw IOException("Engine file is unexpectedly large")
                    val body = resp.body ?: throw IOException("Empty answer")
                    tmp.outputStream().use { out -> body.byteStream().copyTo(out, 64 * 1024) }
                }
                if (tmp.length() == 0L || tmp.length() > MAX_SIZE) throw IOException("Engine file has a wrong size")
                val actual = tmp.inputStream().use { SafeFetch.sha256(it) }
                if (!actual.equals(expected, ignoreCase = true)) {
                    tmp.delete()
                    throw IOException("Engine file failed the SHA-256 check")
                }
                if (!tmp.renameTo(target)) throw IOException("Could not store the engine file")
            }
            if (!MediaEngine.swapTo(target)) {
                // busy (a download is running) or the new copy did not import
                if (MediaEngine.version.value != null && Prefs(context).engineZip != target.path) {
                    AppLog.w("engine", "yt-dlp $tag downloaded but not switched in yet")
                }
                _status.value = null
                return Result.Failed
            }
            prefs.engineZip = target.path
            dir.listFiles()?.filter { it != target }?.forEach { it.delete() }
            AppLog.i("engine", "yt-dlp updated to $tag")
            _status.value = "Updated"
            return Result.Updated
        } catch (e: Exception) {
            AppLog.w("engine", "yt-dlp update check failed", e)
            _status.value = null
            return Result.Failed
        }
    }

    /** Asks GitHub where /releases/latest points, without downloading anything. */
    private fun latestTag(): String {
        val req = okhttp3.Request.Builder().url("https://github.com${REPO}releases/latest")
            .header("User-Agent", Http.USER_AGENT).build()
        Http.noRedirect.newCall(req).execute().use { resp ->
            val loc = resp.header("Location") ?: throw IOException("No latest release (HTTP ${resp.code})")
            val tag = loc.substringAfterLast("/tag/", "").substringBefore('?').trim('/')
            if (tag.isEmpty() || !tag.matches(Regex("""[0-9][0-9A-Za-z._-]{3,40}"""))) throw IOException("Unexpected release name")
            return tag
        }
    }

    private fun expectedSha(tag: String): String {
        val text = SafeFetch.open("https://github.com$REPO" + "releases/download/$tag/SHA2-256SUMS", SafeFetch.githubAllow(REPO)).use {
            it.body?.string() ?: ""
        }
        for (line in text.lines()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size == 2 && parts[1].removePrefix("*") == "yt-dlp" && parts[0].matches(Regex("[0-9a-fA-F]{64}"))) {
                return parts[0]
            }
        }
        throw IOException("No checksum for yt-dlp in the release")
    }

    /** "2026.11.02" vs "2026.10.26" (numbers compared part by part). */
    fun isNewer(candidate: String, current: String): Boolean {
        if (current.isBlank()) return true
        val a = candidate.split('.', '-').map { it.toLongOrNull() ?: 0L }
        val b = current.split('.', '-').map { it.toLongOrNull() ?: 0L }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0L }
            val y = b.getOrElse(i) { 0L }
            if (x != y) return x > y
        }
        return false
    }
}
