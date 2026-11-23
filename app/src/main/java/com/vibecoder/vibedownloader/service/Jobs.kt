package com.vibecoder.vibedownloader.service

import android.content.Context
import android.os.StatFs
import com.vibecoder.vibedownloader.Prefs
import com.vibecoder.vibedownloader.data.DownloadItem
import com.vibecoder.vibedownloader.data.DownloadRepository
import com.vibecoder.vibedownloader.data.Kind
import com.vibecoder.vibedownloader.data.Status
import com.vibecoder.vibedownloader.engine.AppLog
import com.vibecoder.vibedownloader.engine.EngineUpdater
import com.vibecoder.vibedownloader.engine.FileNames
import com.vibecoder.vibedownloader.engine.HttpDownloader
import com.vibecoder.vibedownloader.engine.HttpProbe
import com.vibecoder.vibedownloader.engine.MediaEngine
import com.vibecoder.vibedownloader.engine.MediaSaver
import com.vibecoder.vibedownloader.engine.Merger
import com.vibecoder.vibedownloader.engine.Net
import com.vibecoder.vibedownloader.engine.Problem
import com.vibecoder.vibedownloader.engine.PySink
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.util.Locale

private fun freeBytes(dir: File): Long = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

/** Wait before the next try: longer when the site said "too busy". */
private fun backoffMs(attempt: Int, problem: Problem): Long {
    val base = if (problem == Problem.Busy) 10_000L else 2_000L
    return (base * (1L shl (attempt - 1).coerceIn(0, 4))).coerceAtMost(60_000L)
}

fun isYouTube(url: String): Boolean {
    val host = runCatching { java.net.URI(url).host?.lowercase(Locale.US) ?: "" }.getOrDefault("")
    return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") || host.endsWith(".youtube-nocookie.com")
}

/** A direct file: probe, multi-connection download, save to Downloads. */
class FileJob(
    private val app: Context,
    private val item: DownloadItem,
    private val work: File,
    private val control: JobControl,
) {
    suspend fun run(): JobResult {
        var attempt = 0
        var connections = Prefs(app).connections
        while (true) {
            if (control.stopped) return JobResult.Stopped(control.reason ?: StopReason.Pause)
            try {
                val probe = HttpProbe.probe(item.url)
                if (probe.isHtml) {
                    // the link was a web page after all: let yt-dlp look for a video on it
                    AppLog.i("file", "${item.id} is a web page; trying the media engine")
                    DownloadRepository.update(item.id) { it.copy(kind = Kind.Media, quality = "best") }
                    val media = DownloadRepository.get(item.id) ?: return JobResult.Failed(Problem.Unknown)
                    return MediaJob(app, media, work, control).run()
                }
                val name = FileNames.sanitize(item.fileName.ifBlank { probe.fileName })
                DownloadRepository.update(item.id) {
                    it.copy(fileName = name, title = it.title.ifBlank { name }, total = if (probe.total > 0) probe.total else it.total,
                        mime = FileNames.mimeFor(name))
                }
                if (probe.total > 0 && freeBytes(work) < probe.total + MARGIN) return JobResult.Failed(Problem.Storage)
                val part = File(work, "file.part")
                val dl = HttpDownloader(item.url, part, File(work, "file.state.json"), connections) { d, t, s ->
                    DownloadQueue.progress(item.id, d, t, s)
                }
                control.onStop { dl.stop() }
                when (val o = dl.run(probe)) {
                    HttpDownloader.Outcome.Done -> {
                        if (control.stopped) return JobResult.Stopped(control.reason ?: StopReason.Pause)
                        DownloadQueue.note(item.id, Status.Finishing, "Saving to Downloads")
                        val mime = FileNames.mimeFor(name)
                        val saved = MediaSaver.save(app, part, name, mime)
                        return JobResult.Completed(saved.uri, saved.path, saved.displayName, mime)
                    }
                    HttpDownloader.Outcome.Stopped -> return JobResult.Stopped(control.reason ?: StopReason.Pause)
                    is HttpDownloader.Outcome.Failed -> throw o.error
                }
            } catch (t: Throwable) {
                if (control.stopped) return JobResult.Stopped(control.reason ?: StopReason.Pause)
                val problem = Problem.of(t)
                AppLog.w("file", "${item.id} try ${attempt + 1} failed: $problem", t)
                if (!Net.online(app)) return JobResult.WaitForNetwork
                if (!problem.retryable || attempt >= MAX_TRIES) return JobResult.Failed(problem)
                attempt++
                // refused: the server may allow fewer connections, so ask more politely
                if (problem == Problem.Refused || problem == Problem.Busy) connections = (connections / 2).coerceAtLeast(1)
                if (DownloadRepository.get(item.id)?.status == Status.Finishing) {
                    DownloadRepository.update(item.id) { it.copy(status = Status.Downloading, note = null) }
                }
                delay(backoffMs(attempt, problem))
            }
        }
    }

    private companion object {
        const val MAX_TRIES = 5
        const val MARGIN = 20L * 1024 * 1024
    }
}

/** A video or audio page, downloaded through yt-dlp. */
class MediaJob(
    private val app: Context,
    private val item: DownloadItem,
    private val work: File,
    private val control: JobControl,
) {
    suspend fun run(): JobResult {
        val clientSets = if (isYouTube(item.url)) MediaEngine.clientSets() else 1
        val sink = PySink({ control.stopped }) { d, t, s -> DownloadQueue.progress(item.id, d, t, s) }
        var preferSingle = false
        var updatedEngine = false
        var networkTries = 0
        var lastProblem = Problem.Unknown

        while (true) {
            var client = 0
            var otherTries = 0
            while (client < clientSets) {
                if (control.stopped) return stopped()
                val r = try {
                    MediaEngine.download(item.url, item.quality, preferSingle, work, client, sink)
                } catch (t: Throwable) {
                    AppLog.e("media", "Engine call failed", t)
                    org.json.JSONObject().put("ok", false).put("error", t.message ?: "engine error")
                }
                if (control.stopped || r.optBoolean("stopped")) return stopped()

                if (r.optBoolean("ok")) {
                    val outcome = finish(r, preferSingle)
                    if (outcome != null) return outcome
                    // merging failed: fetch a single ready-made file instead
                    preferSingle = true
                    continue
                }

                val error = r.optString("error")
                val problem = Problem.ofMedia(error)
                AppLog.w("media", "${item.id} client set $client: $problem - ${error.take(400)}")
                r.optJSONArray("log")?.let { log -> for (i in 0 until log.length()) AppLog.i("yt-dlp", log.optString(i)) }
                lastProblem = problem
                when {
                    !Net.online(app) -> return JobResult.WaitForNetwork
                    problem == Problem.Network || problem == Problem.Unknown && networkTries < 1 -> {
                        networkTries++
                        if (networkTries > 4) return JobResult.Failed(Problem.Network)
                        delay(backoffMs(networkTries, problem))
                    }
                    !problem.retryable -> return JobResult.Failed(problem)
                    clientSets > 1 -> {
                        if (problem == Problem.Busy) delay(backoffMs(1, problem))
                        client++
                    }
                    else -> {
                        // other sites: a fresh look at the page gives fresh links
                        otherTries++
                        if (otherTries >= 3) client++ else delay(backoffMs(otherTries, problem))
                    }
                }
            }
            if (!updatedEngine) {
                updatedEngine = true
                // a fix may already be out: fetch the newest yt-dlp and try once more
                DownloadQueue.note(item.id, Status.Downloading, "Trying another way")
                val updated = EngineUpdater.checkAfterFailure(app)
                DownloadQueue.note(item.id, Status.Downloading, null)
                if (updated == EngineUpdater.Result.Updated) continue
            }
            return JobResult.Failed(lastProblem)
        }
    }

    private fun stopped() = JobResult.Stopped(control.reason ?: StopReason.Pause)

    /** Merges or tidies the downloaded streams and saves the result. Null = merge failed. */
    private fun finish(r: org.json.JSONObject, preferSingle: Boolean): JobResult? {
        val files = r.optJSONArray("files") ?: return JobResult.Failed(Problem.Unknown)
        val mode = r.optString("mode")
        val title = r.optString("title").ifBlank { item.title }.ifBlank { "video" }
        DownloadQueue.note(item.id, Status.Finishing, "Finishing up")
        val first = File(files.getJSONObject(0).getString("path"))
        val out: File
        val ext: String
        try {
            when (mode) {
                "merge" -> {
                    val video = File(files.getJSONObject(0).getString("path"))
                    val audio = File(files.getJSONObject(1).getString("path"))
                    out = File(work, "result.mp4")
                    if (freeBytes(work) < video.length() + audio.length() + MARGIN) return JobResult.Failed(Problem.Storage)
                    try {
                        Merger.merge(video, audio, out)
                    } catch (e: Exception) {
                        AppLog.w("media", "Merge failed", e)
                        if (preferSingle) return JobResult.Failed(Problem.NoFormats)
                        DownloadQueue.note(item.id, Status.Downloading, null)
                        return null
                    }
                    ext = "mp4"
                }
                "audio" -> {
                    val srcExt = files.getJSONObject(0).optString("ext", "m4a").lowercase(Locale.US)
                    if (srcExt == "m4a" || srcExt == "mp4") {
                        val clean = File(work, "result.m4a")
                        out = if (runCatching { Merger.remux(first, clean, audioOnly = true) }.isSuccess) clean else first
                        ext = "m4a"
                    } else {
                        out = first
                        ext = srcExt
                    }
                }
                else -> {
                    val srcExt = files.getJSONObject(0).optString("ext", "mp4").lowercase(Locale.US)
                    val protocol = files.getJSONObject(0).optString("protocol")
                    if (protocol.startsWith("m3u8") && (srcExt == "mp4" || srcExt == "m4a")) {
                        // HLS parts are joined as-is; rewrite them into a proper MP4 when possible
                        val clean = File(work, "result.$srcExt")
                        out = if (runCatching { Merger.remux(first, clean, audioOnly = false) }.isSuccess) clean else first
                    } else {
                        out = first
                    }
                    ext = srcExt
                }
            }
            val name = FileNames.sanitize(title) + "." + ext
            val mime = FileNames.mimeFor(name)
            DownloadQueue.note(item.id, Status.Finishing, "Saving to Downloads")
            val saved = MediaSaver.save(app, out, name, mime)
            return JobResult.Completed(saved.uri, saved.path, saved.displayName, mime)
        } catch (e: IOException) {
            AppLog.e("media", "Saving failed", e)
            return JobResult.Failed(Problem.of(e).let { if (it == Problem.Network) Problem.Unknown else it })
        }
    }

    private companion object {
        const val MARGIN = 20L * 1024 * 1024
    }
}
