package com.vibecoder.vibedownloader.engine

import android.content.Context
import com.vibecoder.vibedownloader.linkKind
import com.vibecoder.vibedownloader.service.isYouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One choice in the quality picker. */
data class QualityOption(val key: String, val label: String, val detail: String, val size: Long)

/** What a pasted link turned out to be. */
sealed class Analysis {
    data class File(val url: String, val name: String, val size: Long, val mime: String, val site: String) : Analysis()
    data class Media(
        val url: String,
        val title: String,
        val thumbnail: String?,
        val duration: Long,
        val uploader: String,
        val site: String,
        val qualities: List<QualityOption>,
        /** The page address and id the engine reported (for the duplicate check). */
        val webpage: String? = null,
        val mediaId: String? = null,
    ) : Analysis()
    data class Problem(val message: String) : Analysis()
}

/** Looks at a link in the background: a direct file, or a page with a video on it. */
object LinkAnalyzer {
    suspend fun analyze(context: Context, url: String): Analysis = withContext(Dispatchers.IO) {
        if (!Net.online(context)) return@withContext Analysis.Problem("No internet connection. Connect and try again.")
        val host = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("").removePrefix("www.")
        var httpProblem: com.vibecoder.vibedownloader.engine.Problem? = null
        // a stream playlist is a video, not a file to save as-is
        val playlist = runCatching { java.net.URI(url).path ?: "" }.getOrDefault("").lowercase().endsWith(".m3u8")
        if (!playlist && !linkKind(url).startsWith("Video")) {
            try {
                val p = HttpProbe.probe(url)
                if (!p.isHtml) {
                    return@withContext Analysis.File(url, p.fileName, p.total, FileNames.mimeFor(p.fileName), host)
                }
            } catch (e: Exception) {
                AppLog.w("analyze", "File check failed for $host", e)
                httpProblem = com.vibecoder.vibedownloader.engine.Problem.of(e)
            }
        }
        media(context, url, host, httpProblem)
    }

    private fun media(context: Context, url: String, host: String, httpProblem: com.vibecoder.vibedownloader.engine.Problem?): Analysis {
        val sets = try { if (isYouTube(url)) MediaEngine.clientSets() else 1 } catch (e: Throwable) { 1 }
        var last = com.vibecoder.vibedownloader.engine.Problem.NoMedia
        // never keep the user waiting for minutes: stop trying new ways after this
        val deadline = System.currentTimeMillis() + 90_000
        AppLog.i("analyze", "Looking at $host ($sets ways to try)")
        for (round in 0..1) {
            for (client in 0 until sets) {
                if (System.currentTimeMillis() > deadline) { AppLog.w("analyze", "Gave up on $host after 90 s"); break }
                val started = System.currentTimeMillis()
                val r = try {
                    MediaEngine.probe(url, client)
                } catch (t: Throwable) {
                    AppLog.e("analyze", "Engine failed to start", t)
                    JSONObject().put("ok", false).put("error", t.message ?: "")
                }
                val took = System.currentTimeMillis() - started
                if (r.optBoolean("ok")) { AppLog.i("analyze", "Found media on $host with way $client in $took ms"); return toMedia(url, host, r) }
                val p = com.vibecoder.vibedownloader.engine.Problem.ofMedia(r.optString("error"))
                AppLog.w("analyze", "Probe $host client $client ($took ms): $p - ${r.optString("error").take(300)}")
                last = p
                if (!Net.online(context)) return Analysis.Problem("No internet connection. Connect and try again.")
                if (!p.retryable) break
            }
            if (!last.retryable || round == 1 || System.currentTimeMillis() > deadline) break
            if (EngineUpdater.checkAfterFailure(context) != EngineUpdater.Result.Updated) break
        }
        // a page yt-dlp does not know, after the file check also failed: say what the server said
        if (last == com.vibecoder.vibedownloader.engine.Problem.NoMedia && httpProblem != null) return Analysis.Problem(httpProblem.message)
        return Analysis.Problem(last.message)
    }

    private fun toMedia(url: String, host: String, r: JSONObject): Analysis.Media {
        val q = r.optJSONArray("qualities")
        val options = (0 until (q?.length() ?: 0)).mapNotNull { i ->
            val o = q!!.optJSONObject(i) ?: return@mapNotNull null
            QualityOption(o.optString("key"), o.optString("label"), o.optString("detail"), o.optLong("size"))
        }
        val site = r.optString("site").ifBlank { host }.let { if (it.equals("Youtube", true)) "YouTube" else it }
        return Analysis.Media(
            url = url,
            title = r.optString("title").ifBlank { "Video" },
            thumbnail = r.optString("thumbnail").ifBlank { null },
            duration = r.optLong("duration"),
            uploader = r.optString("uploader"),
            site = site,
            qualities = options,
            webpage = r.optString("webpage").ifBlank { null },
            mediaId = r.optString("id").ifBlank { null },
        )
    }
}
