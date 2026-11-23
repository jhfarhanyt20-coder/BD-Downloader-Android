package com.vibecoder.vibedownloader.data

import android.content.Context
import com.vibecoder.vibedownloader.engine.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.io.File

/**
 * The single source of truth for the download list. The UI, the service
 * notification and the queue all read [items]. History lives in a small
 * JSON file in app storage, written in the background.
 */
object DownloadRepository {
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private var file: File? = null
    private var pendingSave: Job? = null
    private val saveGuard = Any()

    fun init(context: Context) {
        val f = File(context.filesDir, "history.json")
        file = f
        scope.launch {
            val loaded = runCatching {
                if (!f.exists()) emptyList() else {
                    val arr = JSONArray(f.readText())
                    (0 until arr.length()).mapNotNull { DownloadItem.fromJson(arr.getJSONObject(it)) }
                }
            }.onFailure { AppLog.w("history", "Could not read history", it) }.getOrDefault(emptyList())
            // anything that was running when the app closed is paused and continues on the next start
            val fixed = loaded.map {
                when (it.status) {
                    Status.Downloading, Status.Finishing, Status.Queued, Status.Waiting ->
                        it.copy(status = Status.Paused, speed = 0, note = null, interrupted = true)
                    else -> it.copy(speed = 0)
                }
            }
            _items.update { current ->
                val ids = current.map { it.id }.toSet()
                (current + fixed.filter { it.id !in ids }).sortedByDescending { it.createdAt }
            }
            _loaded.value = true
            save(now = true)
        }
    }

    fun get(id: String): DownloadItem? = _items.value.firstOrNull { it.id == id }

    fun add(item: DownloadItem) {
        _items.update { list -> (listOf(item) + list.filter { it.id != item.id }).sortedByDescending { it.createdAt } }
        save(now = true)
    }

    /** Changes one item. Progress ticks save lazily; status changes save at once. */
    fun update(id: String, transform: (DownloadItem) -> DownloadItem) {
        var statusChanged = false
        _items.update { list ->
            list.map { item ->
                if (item.id == id) transform(item).also { statusChanged = it.status != item.status } else item
            }
        }
        save(now = statusChanged)
    }

    fun remove(id: String) {
        _items.update { list -> list.filter { it.id != id } }
        save(now = true)
    }

    private fun save(now: Boolean) {
        synchronized(saveGuard) {
            if (!now && pendingSave?.isActive == true) return
            pendingSave?.cancel()
            pendingSave = scope.launch {
                if (!now) delay(2000)
                writeLock.withLock { write() }
            }
        }
    }

    private fun write() {
        val f = file ?: return
        if (!_loaded.value) return   // never overwrite history before it was read
        runCatching {
            val arr = JSONArray()
            _items.value.forEach { arr.put(it.toJson()) }
            val tmp = File(f.path + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { AppLog.w("history", "Could not save history", it) }
    }
}
