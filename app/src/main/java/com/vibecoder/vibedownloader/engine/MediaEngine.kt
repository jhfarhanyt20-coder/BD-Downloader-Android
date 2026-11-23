package com.vibecoder.vibedownloader.engine

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.vibecoder.vibedownloader.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * The object Python calls back into while yt-dlp downloads. Kept by R8
 * (see proguard-rules.pro) because Python finds these methods by name.
 */
class PySink(
    private val cancelled: () -> Boolean,
    private val progress: (done: Long, total: Long, speed: Long) -> Unit,
) {
    @Volatile private var last = 0L

    fun isCancelled(): Boolean = cancelled()

    fun onProgress(done: Long, total: Long, speed: Double) {
        val now = System.currentTimeMillis()
        if (now - last < 400) return
        last = now
        progress(done, total, speed.toLong())
    }

    fun log(message: String) = AppLog.i("yt-dlp", message)
}

/**
 * yt-dlp running in the app's own Python (Chaquopy). All calls block, so they
 * are only made from background threads.
 */
object MediaEngine {
    private lateinit var app: Context
    private var module: PyObject? = null
    private val _version = MutableStateFlow<String?>(null)
    /** "2026.10.26", or null before the engine has started. */
    val version: StateFlow<String?> = _version

    /** Downloads hold the read side; swapping in a new yt-dlp needs the write side. */
    private val swap = ReentrantReadWriteLock()

    fun init(context: Context) { app = context.applicationContext }

    @Synchronized
    fun ensure(): PyObject {
        module?.let { return it }
        val started = System.currentTimeMillis()
        if (!Python.isStarted()) Python.start(AndroidPlatform(app))
        val m = Python.getInstance().getModule("vd_engine")
        module = m
        val zip = Prefs(app).engineZip
        val r = load(zip)
        if (zip != null && r.optString("source") != "update") {
            AppLog.w("engine", "Downloaded yt-dlp could not be used (${r.optString("error")}); using the built-in copy")
            Prefs(app).engineZip = null
        }
        AppLog.i("engine", "Python ready in ${System.currentTimeMillis() - started} ms, yt-dlp ${_version.value}")
        return m
    }

    /** Loads a downloaded copy (or the bundled one when null). Returns the result JSON. */
    private fun load(zip: String?): JSONObject {
        val m = module ?: throw IllegalStateException("engine not started")
        val r = JSONObject(m.callAttr("load", zip).toString())
        _version.value = r.optString("version").ifBlank { null }
        return r
    }

    /** Switches to a newly downloaded yt-dlp when no download is using the engine. */
    fun swapTo(zip: File): Boolean {
        ensure()
        if (!swap.writeLock().tryLock(3, TimeUnit.SECONDS)) return false
        try {
            return synchronized(this) {
                val r = load(zip.path)
                val ok = r.optString("source") == "update"
                if (!ok) {
                    AppLog.w("engine", "New yt-dlp did not load: ${r.optString("error")}")
                    Prefs(app).engineZip?.let { previous -> runCatching { load(previous) } }
                }
                ok
            }
        } finally {
            swap.writeLock().unlock()
        }
    }

    fun clientSets(): Int = runCatching { ensure().callAttr("client_set_count").toInt() }.getOrDefault(1)

    private fun cacheDir(): String = File(app.cacheDir, "engine").apply { mkdirs() }.path

    fun probe(url: String, clientIndex: Int): JSONObject {
        val m = ensure()
        swap.readLock().lock()
        try {
            return JSONObject(m.callAttr("probe", url, clientIndex, cacheDir()).toString())
        } finally {
            swap.readLock().unlock()
        }
    }

    fun download(url: String, quality: String, preferSingle: Boolean, workDir: File, clientIndex: Int, sink: PySink): JSONObject {
        val m = ensure()
        swap.readLock().lock()
        try {
            return JSONObject(
                m.callAttr("download", url, quality, preferSingle, workDir.path, cacheDir(), clientIndex, sink).toString()
            )
        } finally {
            swap.readLock().unlock()
        }
    }
}
