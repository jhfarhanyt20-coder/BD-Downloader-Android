package com.vibecoder.vibedownloader.engine

import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What the server told us about a link before downloading. */
data class Probe(
    val url: String,
    val total: Long,
    val rangesOk: Boolean,
    val etag: String,
    val contentType: String,
    val fileName: String,
) {
    val isHtml: Boolean
        get() = contentType.lowercase(Locale.US).let { "text/html" in it || "application/xhtml" in it }
}

object HttpProbe {
    private val totalFromRange = Regex("""/\s*(\d+)\s*$""")

    private fun request(url: String, range: Boolean): Request = Request.Builder().url(url)
        .header("User-Agent", Http.USER_AGENT)
        .header("Accept", "*/*")
        .header("Accept-Encoding", "identity")
        .apply { if (range) header("Range", "bytes=0-0") }
        .build()

    /** Size, range support and name. Throws [HttpStatusException] for refusals. */
    fun probe(url: String): Probe {
        var resp: Response = Http.download.newCall(request(url, true)).execute()
        // some servers refuse a range probe but serve the file normally
        if (resp.code in setOf(400, 403, 405, 416, 501)) {
            resp.close()
            resp = Http.download.newCall(request(url, false)).execute()
        }
        try {
            var total = 0L
            var ranges = false
            when (resp.code) {
                206 -> {
                    ranges = true
                    total = totalFromRange.find(resp.header("Content-Range") ?: "")?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                }
                200 -> {
                    total = resp.header("Content-Length")?.toLongOrNull() ?: resp.body?.contentLength() ?: -1L
                    if (total < 0) total = 0
                    ranges = "bytes" in (resp.header("Accept-Ranges") ?: "").lowercase(Locale.US) && total > 0
                }
                else -> if (resp.code >= 400) throw HttpStatusException(resp.code)
            }
            val finalUrl = resp.request.url.toString()
            val type = resp.header("Content-Type") ?: ""
            val name = FileNames.fromContentDisposition(resp.header("Content-Disposition"))
                ?: FileNames.fromUrl(finalUrl) ?: FileNames.fromUrl(url) ?: "download"
            val etag = resp.header("ETag") ?: ""
            return Probe(url, total, ranges, etag, type, FileNames.withExtension(FileNames.sanitize(name), type))
        } finally {
            resp.close()
        }
    }
}

/**
 * Multi-connection download of one file over HTTP byte ranges.
 *
 * The file is split into ranges that download in parallel straight into
 * their own place in one `.part` file. A small JSON file next to it records
 * each range, so a paused or killed download continues where it stopped.
 * A connection that finishes early takes over half of the slowest range.
 */
class HttpDownloader(
    private val url: String,
    private val part: File,
    private val stateFile: File,
    connections: Int,
    private val onProgress: (done: Long, total: Long, speed: Long) -> Unit,
) {
    sealed class Outcome {
        object Done : Outcome()
        object Stopped : Outcome()
        class Failed(val error: Throwable) : Outcome()
    }

    private class Seg(val start: Long, @Volatile var end: Long) {
        @Volatile var done = 0L
        @Volatile var complete = false
        @Volatile var error: Throwable? = null
        var retries = 0
        var retryAt = 0L
        var thread: Thread? = null
        val length: Long get() = end - start + 1
    }

    private sealed class Step {
        object Again : Step()
        object Exit : Step()
        class Retry(val error: Throwable) : Step()
    }

    @Volatile private var stopped = false
    private val calls: MutableSet<Call> = Collections.newSetFromMap(ConcurrentHashMap())
    private var wanted = connections.coerceIn(1, MAX_CONNECTIONS)
    private val lock = Any()
    private var total = 0L
    private var etag = ""
    private val downloaded = AtomicLong(0)
    private var speed = 0.0
    private val segments = mutableListOf<Seg>()
    @Volatile private var lastError: Throwable? = null

    fun stop() {
        stopped = true
        calls.toList().forEach { runCatching { it.cancel() } }
    }

    /** Blocking. */
    fun run(probe: Probe): Outcome {
        total = probe.total
        etag = probe.etag
        return try {
            if (total >= MIN_PARALLEL_SIZE && probe.rangesOk) runParallel() else runSingle(probe.rangesOk)
        } catch (t: Throwable) {
            if (stopped) Outcome.Stopped else Outcome.Failed(t)
        }
    }

    // ------------------------------------------------------------ state file
    private fun loadState(): JSONObject? {
        if (!stateFile.exists()) return null
        return runCatching {
            val s = JSONObject(stateFile.readText())
            val sameEtag = etag.isEmpty() || s.optString("etag").isEmpty() || s.optString("etag") == etag
            if (s.optString("url") == url && s.optLong("size", -1) == total && sameEtag) s else null
        }.getOrNull()
    }

    private fun saveState() {
        if (segments.isEmpty()) return
        val arr = JSONArray()
        synchronized(lock) {
            segments.forEach { arr.put(JSONObject().put("start", it.start).put("end", it.end).put("done", it.done)) }
        }
        val json = JSONObject().put("url", url).put("size", total).put("etag", etag)
            .put("connections", wanted).put("segments", arr)
        runCatching {
            val tmp = File(stateFile.path + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(stateFile)) { stateFile.delete(); tmp.renameTo(stateFile) }
        }
    }

    private fun deleteState() {
        stateFile.delete()
        File(stateFile.path + ".tmp").delete()
    }

    private fun restoreSegments(state: JSONObject?, n: Int): List<Seg> {
        val saved = state?.optJSONArray("segments")
        if (saved != null && saved.length() > 0) {
            val out = mutableListOf<Seg>()
            var ok = true
            for (i in 0 until saved.length()) {
                val o = saved.optJSONObject(i)
                if (o == null) { ok = false; break }
                val start = o.optLong("start", -1)
                val end = o.optLong("end", -1)
                if (start < 0 || end < start || end >= total) { ok = false; break }
                val seg = Seg(start, end)
                seg.done = o.optLong("done", 0).coerceIn(0, seg.length)
                seg.complete = seg.done >= seg.length
                out += seg
            }
            if (ok && out.isNotEmpty() && out.first().start == 0L && out.maxOf { it.end } == total - 1) {
                return out.sortedBy { it.start }
            }
        }
        val size = total / n
        return (0 until n).map { i ->
            val start = i * size
            val end = if (i == n - 1) total - 1 else (i + 1) * size - 1
            Seg(start, end)
        }
    }

    // ------------------------------------------------------------ parallel
    private fun runParallel(): Outcome {
        val state = loadState()
        var n = state?.optInt("connections", wanted) ?: wanted
        n = n.coerceIn(1, MAX_CONNECTIONS).coerceAtMost((total / MIN_SEGMENT_SIZE).coerceAtLeast(1).toInt())
        wanted = n
        part.parentFile?.mkdirs()
        if (state == null) part.delete()      // a .part without its state file cannot be trusted
        RandomAccessFile(part, "rw").use { if (it.length() != total) it.setLength(total) }

        synchronized(lock) {
            segments.clear()
            segments += restoreSegments(state, n)
        }
        saveState()
        downloaded.set(segments.sumOf { it.done })
        onProgress(downloaded.get(), total, 0)

        val raf = RandomAccessFile(part, "rw")
        val channel = raf.channel
        try {
            segments.filter { !it.complete }.forEach { spawn(it, channel) }
            var lastTime = System.currentTimeMillis()
            var lastBytes = downloaded.get()
            var lastSave = lastTime
            while (!stopped) {
                val alive = synchronized(lock) { segments.any { it.thread?.isAlive == true } }
                val waiting = recoverRefused(channel)
                if (!alive && !waiting) break
                Thread.sleep(200)
                val now = System.currentTimeMillis()
                if (now - lastTime >= 500) {
                    val measured = (downloaded.get() - lastBytes) * 1000.0 / (now - lastTime)
                    speed = if (speed <= 0) measured else speed * 0.6 + measured * 0.4
                    lastTime = now
                    lastBytes = downloaded.get()
                    onProgress(downloaded.get(), total, speed.toLong())
                }
                if (now - lastSave >= 2000) { saveState(); lastSave = now }
                rebalance(channel)
            }
            synchronized(lock) { segments.toList() }.forEach { it.thread?.join(5000) }
        } finally {
            runCatching { channel.force(false) }
            runCatching { raf.close() }
        }
        saveState()
        speed = 0.0
        onProgress(downloaded.get(), total, 0)
        if (stopped) return Outcome.Stopped

        val errors = segments.mapNotNull { it.error }
        if (segments.all { it.complete }) {
            if (part.length() != total) return Outcome.Failed(IOException("File size does not match"))
            deleteState()
            return Outcome.Done
        }
        if (errors.any { it is RangeMismatchException }) {
            // the server only pretended to support ranges: start again as one stream
            AppLog.w("http", "Range support failed (${errors.first { it is RangeMismatchException }.message}); using one connection")
            part.delete()
            deleteState()
            synchronized(lock) { segments.clear() }
            downloaded.set(0)
            return runSingle(false)
        }
        return Outcome.Failed(errors.firstOrNull() ?: lastError ?: IOException("Download stopped unexpectedly"))
    }

    private fun spawn(seg: Seg, channel: FileChannel) {
        seg.error = null
        val t = Thread({ worker(seg, channel) }, "vd-range")
        t.isDaemon = true
        seg.thread = t
        t.start()
    }

    /**
     * Many servers allow only a few connections and refuse the rest with
     * 403/429/503. That must not fail the file: stay at the number the server
     * accepts and hand the refused ranges to connections as they free up.
     */
    private fun recoverRefused(channel: FileChannel): Boolean {
        if (stopped) return false
        val snapshot = synchronized(lock) { segments.toList() }
        val alive = snapshot.filter { it.thread?.isAlive == true }
        val waiting = snapshot.filter { seg ->
            val err = seg.error
            !seg.complete && err is HttpStatusException && err.code in REFUSED && seg !in alive &&
                seg.retries < MAX_SEGMENT_RETRIES &&
                (seg.done > 0 || snapshot.any { it !== seg && it.done > 0 })
        }
        if (waiting.isEmpty()) return false
        if (alive.isNotEmpty()) wanted = wanted.coerceAtMost(alive.size).coerceAtLeast(1)
        var free = wanted - alive.size
        val now = System.currentTimeMillis()
        for (seg in waiting) {
            if (free <= 0) break
            if (now < seg.retryAt) continue
            seg.retries++
            seg.retryAt = now + (2000L * seg.retries).coerceAtMost(8000L)
            spawn(seg, channel)
            free--
        }
        return true
    }

    private fun rebalance(channel: FileChannel) {
        if (stopped) return
        val stolen = synchronized(lock) {
            if (segments.size >= MAX_CONNECTIONS * 2) return
            val alive = segments.count { it.thread?.isAlive == true }
            if (alive >= wanted) return
            val victim = segments.filter { !it.complete && it.error == null && it.thread?.isAlive == true && it.length - it.done > STEAL_MIN * 2 }
                .maxByOrNull { it.length - it.done } ?: return
            val cursor = victim.start + victim.done
            val splitAt = cursor + maxOf(STEAL_MIN, (victim.end - cursor) / 2)
            if (victim.end - splitAt < STEAL_MIN) return
            val oldEnd = victim.end
            victim.end = splitAt
            Seg(splitAt + 1, oldEnd).also { segments += it }
        }
        spawn(stolen, channel)
    }

    private fun request(start: Long, end: Long?): Request = Request.Builder().url(url)
        .header("User-Agent", Http.USER_AGENT)
        .header("Accept", "*/*")
        .header("Accept-Encoding", "identity")
        .header("Range", if (end != null) "bytes=$start-$end" else "bytes=$start-")
        .apply { if (etag.isNotEmpty() && !etag.trim().startsWith("W/")) header("If-Range", etag) }
        .build()

    private fun worker(seg: Seg, channel: FileChannel) {
        var attempts = 0
        val buf = ByteArray(BUFFER)
        while (!stopped) {
            val finished = synchronized(lock) { seg.done >= seg.length }
            if (finished) { seg.complete = true; return }
            if (attempts >= MAX_ATTEMPTS) {
                seg.error = lastError ?: IOException("Connection failed")
                return
            }
            val before = seg.done
            val step = try {
                fetchRange(seg, channel, buf)
            } catch (e: IOException) {
                if (stopped) return
                Step.Retry(e)
            }
            when (step) {
                Step.Exit -> return
                Step.Again -> attempts = if (seg.done > before) 0 else attempts + 1
                is Step.Retry -> {
                    lastError = step.error
                    attempts = if (seg.done > before) 1 else attempts + 1
                    if (attempts < MAX_ATTEMPTS) sleepQuietly(backoff(attempts))
                }
            }
        }
    }

    private fun fetchRange(seg: Seg, channel: FileChannel, buf: ByteArray): Step {
        val (start, requestedEnd) = synchronized(lock) { (seg.start + seg.done) to seg.end }
        val call = Http.download.newCall(request(start, requestedEnd))
        calls += call
        try {
            val resp = call.execute()
            try {
                if (resp.code != 206) {
                    if (resp.code == 200 || resp.code == 416) {
                        seg.error = RangeMismatchException("Range ignored (HTTP ${resp.code})")
                        return Step.Exit
                    }
                    val e = HttpStatusException(resp.code)
                    if (resp.code in RETRY_CODES) return Step.Retry(e)
                    seg.error = e
                    return Step.Exit
                }
                val respEtag = resp.header("ETag") ?: ""
                if (etag.isNotEmpty() && respEtag.isNotEmpty() && respEtag != etag) {
                    seg.error = RangeMismatchException("The file changed on the server")
                    return Step.Exit
                }
                val m = CONTENT_RANGE.matchEntire((resp.header("Content-Range") ?: "").trim())
                if (m == null) {
                    seg.error = RangeMismatchException("Missing Content-Range")
                    return Step.Exit
                }
                val rStart = m.groupValues[1].toLong()
                val rEnd = m.groupValues[2].toLong()
                val rTotal = m.groupValues[3]
                if (rStart != start || rEnd > requestedEnd) {
                    seg.error = RangeMismatchException("Unexpected byte offsets")
                    return Step.Exit
                }
                if (rTotal != "*" && rTotal.toLongOrNull() != total) {
                    seg.error = RangeMismatchException("The file size changed")
                    return Step.Exit
                }
                val input = resp.body?.byteStream() ?: return Step.Retry(IOException("Empty answer"))
                while (!stopped) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    val remaining = synchronized(lock) { seg.length - seg.done }
                    if (remaining <= 0) break
                    val allowed = minOf(n.toLong(), remaining).toInt()
                    // the range end may shrink (another connection took the tail),
                    // but never below this position + BUFFER, so the write is safe
                    writeFully(channel, buf, allowed, seg.start + seg.done)
                    val done = synchronized(lock) {
                        seg.done += allowed
                        seg.done >= seg.length
                    }
                    downloaded.addAndGet(allowed.toLong())
                    if (done) break
                }
                return Step.Again
            } finally {
                resp.close()
            }
        } finally {
            calls -= call
        }
    }

    private fun writeFully(channel: FileChannel, buf: ByteArray, len: Int, position: Long) {
        val bb = ByteBuffer.wrap(buf, 0, len)
        var pos = position
        while (bb.hasRemaining()) pos += channel.write(bb, pos)
    }

    // ------------------------------------------------------------ single stream
    private fun runSingle(rangesOk: Boolean): Outcome {
        part.parentFile?.mkdirs()
        if (stateFile.exists()) {
            // a .part from a parallel run has holes: its length says nothing
            part.delete()
            deleteState()
        }
        var attempts = 0
        val buf = ByteArray(BUFFER)
        while (true) {
            if (stopped) return Outcome.Stopped
            var offset = if (part.exists()) part.length() else 0L
            if (!rangesOk && offset > 0 && attempts > 0) { part.delete(); offset = 0 }
            if (total > 0 && offset > total) { part.delete(); offset = 0 }
            if (total > 0 && offset == total) return Outcome.Done
            val req = if (offset > 0) request(offset, null) else Request.Builder().url(url)
                .header("User-Agent", Http.USER_AGENT).header("Accept", "*/*").header("Accept-Encoding", "identity").build()
            val call = Http.download.newCall(req)
            calls += call
            try {
                val resp = call.execute()
                try {
                    if (resp.code != 200 && resp.code != 206) throw HttpStatusException(resp.code)
                    var append = offset > 0 && resp.code == 206
                    if (append) {
                        val m = CONTENT_RANGE.matchEntire((resp.header("Content-Range") ?: "").trim())
                        if (m == null || m.groupValues[1].toLong() != offset) append = false
                    }
                    if (!append) offset = 0
                    if (total <= 0) {
                        val len = resp.body?.contentLength() ?: -1L
                        if (len > 0) total = len + offset
                    }
                    downloaded.set(offset)
                    onProgress(offset, total, 0)
                    val input = resp.body?.byteStream() ?: throw IOException("Empty answer")
                    val out = FileOutputStream(part, append)
                    try {
                        var lastTime = System.currentTimeMillis()
                        var lastBytes = offset
                        while (!stopped) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n == 0) continue
                            out.write(buf, 0, n)
                            val now = downloaded.addAndGet(n.toLong())
                            val t = System.currentTimeMillis()
                            if (t - lastTime >= 500) {
                                val measured = (now - lastBytes) * 1000.0 / (t - lastTime)
                                speed = if (speed <= 0) measured else speed * 0.6 + measured * 0.4
                                lastTime = t
                                lastBytes = now
                                onProgress(now, total, speed.toLong())
                            }
                        }
                    } finally {
                        runCatching { out.fd.sync() }
                        out.close()
                    }
                } finally {
                    resp.close()
                }
                if (stopped) return Outcome.Stopped
                if (total > 0 && downloaded.get() < total) throw IOException("Connection closed before the file finished")
                speed = 0.0
                onProgress(downloaded.get(), if (total > 0) total else downloaded.get(), 0)
                return Outcome.Done
            } catch (e: IOException) {
                if (stopped) return Outcome.Stopped
                if (e is HttpStatusException && e.code !in RETRY_CODES) return Outcome.Failed(e)
                attempts++
                if (attempts >= MAX_ATTEMPTS + 1) return Outcome.Failed(e)
                AppLog.w("http", "Single stream retry $attempts: ${e.message}")
                sleepQuietly(backoff(attempts))
            } finally {
                calls -= call
            }
        }
    }

    private fun sleepQuietly(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (!stopped && System.currentTimeMillis() < until) {
            try { Thread.sleep(100) } catch (_: InterruptedException) { return }
        }
    }

    companion object {
        const val MAX_CONNECTIONS = 16
        private const val MIN_PARALLEL_SIZE = 1024L * 1024
        private const val MIN_SEGMENT_SIZE = 256L * 1024
        private const val STEAL_MIN = 768L * 1024
        private const val BUFFER = 128 * 1024
        private const val MAX_ATTEMPTS = 5
        private const val MAX_SEGMENT_RETRIES = 5
        private val REFUSED = setOf(403, 429, 500, 502, 503, 504)
        private val RETRY_CODES = setOf(408, 429, 500, 502, 503, 504)
        private val CONTENT_RANGE = Regex("""^bytes\s+(\d+)-(\d+)/(\d+|\*)$""", RegexOption.IGNORE_CASE)

        private fun backoff(attempt: Int): Long = (1000L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(15000L)
    }
}
