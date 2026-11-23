package com.vibecoder.vibedownloader.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.vibecoder.vibedownloader.Prefs
import com.vibecoder.vibedownloader.data.DownloadItem
import com.vibecoder.vibedownloader.data.DownloadRepository
import com.vibecoder.vibedownloader.data.Kind
import com.vibecoder.vibedownloader.data.Status
import com.vibecoder.vibedownloader.engine.AppLog
import com.vibecoder.vibedownloader.engine.MediaSaver
import com.vibecoder.vibedownloader.engine.Net
import com.vibecoder.vibedownloader.engine.Problem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Why a running download was asked to stop. */
enum class StopReason { Pause, Cancel, Wait, Offline }

/** Lets the queue stop a running job, whatever engine it uses. */
class JobControl {
    @Volatile var reason: StopReason? = null
        private set
    private val stoppers = CopyOnWriteArrayList<() -> Unit>()
    val stopped: Boolean get() = reason != null

    fun onStop(stopper: () -> Unit) {
        stoppers.clear()
        stoppers += stopper
        if (stopped) runCatching { stopper() }
    }

    fun stop(why: StopReason) {
        if (reason == null || why == StopReason.Cancel) reason = why
        stoppers.forEach { runCatching { it() } }
    }
}

sealed class JobResult {
    class Completed(val uri: String, val path: String?, val fileName: String, val mime: String) : JobResult()
    class Stopped(val reason: StopReason) : JobResult()
    object WaitForNetwork : JobResult()
    class Failed(val problem: Problem) : JobResult()
}

/**
 * Runs downloads: at most Prefs.simultaneous at a time, waits for a network
 * (or for Wi-Fi when "Wi-Fi only" is on), and turns every outcome into a
 * status the list shows.
 */
object DownloadQueue {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Running(val control: JobControl, val job: Job)
    private val running = ConcurrentHashMap<String, Running>()

    const val WAIT_LINE = "Waiting in line"
    const val WAIT_INTERNET = "Waiting for network… it will continue by itself"
    const val WAIT_WIFI = "Waiting for Wi-Fi… it will continue by itself"

    fun init(context: Context) {
        app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching {
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = networkChanged()
                override fun onLost(network: Network) = networkChanged()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = networkChanged()
            })
        }.onFailure { AppLog.w("queue", "No network callback", it) }
    }

    fun workDir(id: String): File =
        File(app.getExternalFilesDir(null) ?: app.filesDir, "work/$id")

    fun hasWork(): Boolean = running.isNotEmpty() || DownloadRepository.items.value.any {
        it.status == Status.Queued || it.status == Status.Waiting || it.status.isRunning
    }

    fun isRunning(id: String) = running.containsKey(id)

    // ------------------------------------------------------------ actions
    fun add(item: DownloadItem) {
        DownloadRepository.add(item)
        AppLog.i("queue", "Added ${item.kind} ${item.id} (${item.quality}) from ${item.site}")
        pump()
    }

    /** Many links at once ("Add all to queue"): they start one after another, in this order. */
    fun addAll(list: List<DownloadItem>) {
        list.forEach { DownloadRepository.add(it) }
        AppLog.i("queue", "Added ${list.size} downloads at once")
        pump()
    }

    fun pause(id: String) {
        val r = running[id]
        if (r != null) r.control.stop(StopReason.Pause)
        else {
            DownloadRepository.update(id) {
                if (it.status == Status.Queued || it.status == Status.Waiting) it.copy(status = Status.Paused, note = null, speed = 0) else it
            }
            Notifier.markPaused(id)
            Notifier.refresh(app)
        }
    }

    fun pauseAll() {
        DownloadRepository.items.value.filter { it.status != Status.Done && it.status != Status.Paused && it.status != Status.Failed }
            .forEach { pause(it.id) }
    }

    fun resume(id: String) {
        Notifier.unmarkPaused(id)
        DownloadRepository.update(id) {
            if (it.status == Status.Paused || it.status == Status.Failed) it.copy(status = Status.Queued, note = null, interrupted = false) else it
        }
        pump()
    }

    fun cancel(id: String) {
        val r = running[id]
        if (r != null) {
            r.control.stop(StopReason.Cancel)
        } else {
            DownloadRepository.get(id)?.let { Notifier.clear(app, it) }
            workDir(id).deleteRecursively()
            DownloadRepository.remove(id)
        }
    }

    fun cancelAll() {
        DownloadRepository.items.value.filter { it.status != Status.Done }.forEach { cancel(it.id) }
    }

    /** Removes a finished (or failed) item; optionally deletes the saved file too. */
    fun remove(id: String, deleteFile: Boolean): Boolean {
        val item = DownloadRepository.get(id) ?: return true
        if (running.containsKey(id)) { cancel(id); return true }
        var ok = true
        if (deleteFile && item.status == Status.Done) ok = MediaSaver.delete(app, item.savedUri, item.savedPath)
        workDir(id).deleteRecursively()
        Notifier.clear(app, item)
        DownloadRepository.remove(id)
        return ok
    }

    /** Continues downloads that were running when the app was closed. */
    fun resumeInterrupted() {
        if (!::app.isInitialized) return
        DownloadRepository.items.value.filter { it.interrupted && it.status == Status.Paused }.forEach {
            DownloadRepository.update(it.id) { i -> i.copy(status = Status.Queued, interrupted = false) }
        }
        pump()
    }

    // ------------------------------------------------------------ scheduling
    /** Wi-Fi only or the number of downloads changed in Settings. */
    fun settingsChanged() = networkChanged()

    private fun networkChanged() {
        scope.launch {
            delay(800)
            val prefs = Prefs(app)
            val state = Net.state(app)
            if (prefs.wifiOnly && state == Net.State.Metered) {
                running.values.forEach { it.control.stop(StopReason.Wait) }
            } else if (state == Net.State.None) {
                // the network really dropped: park the downloads (parts are kept) instead of
                // letting them retry for half a minute; they continue when it is back
                delay(2500)
                if (Net.state(app) == Net.State.None) running.values.forEach { it.control.stop(StopReason.Offline) }
            }
            pump()
        }
    }

    // ------------------------------------------------------------ queue order
    /** Waiting items in the order they will start. */
    fun queueOrder(items: List<DownloadItem> = DownloadRepository.items.value): List<DownloadItem> =
        items.filter { (it.status == Status.Queued || it.status == Status.Waiting) && !running.containsKey(it.id) }
            .sortedWith(compareBy({ it.order }, { it.createdAt }))

    /** Moves a waiting download up (-1) or down (+1) in the line; Int.MIN_VALUE = to the front. */
    fun move(id: String, delta: Int) {
        val line = queueOrder()
        val i = line.indexOfFirst { it.id == id }
        if (i < 0) return
        if (delta == Int.MIN_VALUE) {
            val first = line.first()
            if (first.id != id) DownloadRepository.update(id) { it.copy(order = first.order - 1) }
            return
        }
        val j = (i + delta).coerceIn(0, line.size - 1)
        if (j == i) return
        val a = line[i]
        val b = line[j]
        val ao = a.order
        val bo = if (b.order == a.order) b.order + delta else b.order
        DownloadRepository.update(a.id) { it.copy(order = bo) }
        DownloadRepository.update(b.id) { it.copy(order = ao) }
    }

    @Synchronized
    fun pump() {
        val prefs = Prefs(app)
        val limit = prefs.simultaneous
        val net = Net.state(app)
        val candidates = DownloadRepository.items.value
            .filter { (it.status == Status.Queued || it.status == Status.Waiting) && !running.containsKey(it.id) }
            .sortedWith(compareBy({ it.order }, { it.createdAt }))
        for (item in candidates) {
            val blocker = when {
                net == Net.State.None -> WAIT_INTERNET
                prefs.wifiOnly && net == Net.State.Metered -> WAIT_WIFI
                running.size >= limit -> WAIT_LINE
                else -> null
            }
            if (blocker != null) {
                val status = if (blocker == WAIT_LINE) Status.Queued else Status.Waiting
                if (item.status != status || item.note != blocker) {
                    DownloadRepository.update(item.id) { it.copy(status = status, note = blocker, speed = 0) }
                }
                continue
            }
            start(item)
        }
        if (hasWork()) DownloadService.ensureRunning(app) else Notifier.refresh(app)
    }

    private fun start(item: DownloadItem) {
        val control = JobControl()
        val job = scope.launch(start = CoroutineStart.LAZY) { execute(item.id, control) }
        running[item.id] = Running(control, job)
        DownloadRepository.update(item.id) { it.copy(status = Status.Downloading, note = null, speed = 0) }
        job.start()
    }

    private suspend fun execute(id: String, control: JobControl) {
        var result: JobResult = JobResult.Failed(Problem.Unknown)
        try {
            val item = DownloadRepository.get(id) ?: return
            val work = workDir(id).apply { mkdirs() }
            result = when (item.kind) {
                Kind.File -> FileJob(app, item, work, control).run()
                Kind.Media -> MediaJob(app, item, work, control).run()
            }
        } catch (t: Throwable) {
            AppLog.e("queue", "Download $id crashed", t)
            result = if (control.stopped) JobResult.Stopped(control.reason ?: StopReason.Pause) else JobResult.Failed(Problem.Unknown)
        } finally {
            running.remove(id)
            finish(id, if (control.reason == StopReason.Cancel) JobResult.Stopped(StopReason.Cancel) else result)
            pump()
        }
    }

    private fun finish(id: String, result: JobResult) {
        when (result) {
            is JobResult.Completed -> {
                DownloadRepository.update(id) {
                    it.copy(status = Status.Done, note = null, speed = 0, savedUri = result.uri, savedPath = result.path,
                        fileName = result.fileName, mime = result.mime, finishedAt = System.currentTimeMillis(),
                        downloaded = if (it.total > 0) it.total else it.downloaded,
                        total = if (it.total > 0) it.total else it.downloaded)
                }
                workDir(id).deleteRecursively()
                Notifier.unmarkPaused(id)
                DownloadRepository.get(id)?.let { Notifier.finished(app, it) }
                AppLog.i("queue", "Finished $id")
            }
            is JobResult.Stopped -> when (result.reason) {
                StopReason.Pause -> {
                    DownloadRepository.update(id) { it.copy(status = Status.Paused, note = null, speed = 0) }
                    Notifier.markPaused(id)
                    Notifier.refresh(app)
                }
                StopReason.Wait -> DownloadRepository.update(id) { it.copy(status = Status.Waiting, note = WAIT_WIFI, speed = 0) }
                StopReason.Offline -> DownloadRepository.update(id) { it.copy(status = Status.Waiting, note = WAIT_INTERNET, speed = 0) }
                StopReason.Cancel -> {
                    DownloadRepository.get(id)?.let { Notifier.clear(app, it) }
                    workDir(id).deleteRecursively()
                    DownloadRepository.remove(id)
                    AppLog.i("queue", "Cancelled $id")
                }
            }
            JobResult.WaitForNetwork -> DownloadRepository.update(id) { it.copy(status = Status.Waiting, note = WAIT_INTERNET, speed = 0) }
            is JobResult.Failed -> {
                Notifier.unmarkPaused(id)
                DownloadRepository.update(id) { it.copy(status = Status.Failed, note = result.problem.message, speed = 0) }
                DownloadRepository.get(id)?.let { Notifier.failed(app, it, result.problem.message) }
                AppLog.w("queue", "Failed $id: ${result.problem}")
            }
        }
    }

    /** Progress from an engine (called often, from background threads). */
    fun progress(id: String, done: Long, total: Long, speed: Long) {
        DownloadRepository.update(id) {
            if (it.status != Status.Downloading) it
            else it.copy(downloaded = done, total = if (total > 0) total else it.total, speed = speed)
        }
    }

    fun note(id: String, status: Status, text: String?) {
        DownloadRepository.update(id) { it.copy(status = status, note = text, speed = if (status == Status.Finishing) 0 else it.speed) }
    }
}
