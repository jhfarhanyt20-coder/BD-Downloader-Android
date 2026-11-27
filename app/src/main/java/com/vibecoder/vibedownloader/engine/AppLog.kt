package com.vibecoder.vibedownloader.engine

import android.content.Context
import android.os.Build
import com.vibecoder.vibedownloader.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A small rotating log on the phone (two files of 256 KB). It holds the
 * technical details the user never sees, and "Send a report" shares it.
 */
object AppLog {
    private const val MAX_BYTES = 256 * 1024
    private var dir: File? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "vd-log").apply { isDaemon = true } }
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        dir = File(context.filesDir, "logs").apply { mkdirs() }
        i("app", "Started ${BuildConfig.VERSION_NAME} on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        // also to logcat, so a test run on an emulator shows what the app did (no keys or personal data are logged)
        runCatching {
            val prio = when (level) { "E" -> android.util.Log.ERROR; "W" -> android.util.Log.WARN; else -> android.util.Log.INFO }
            android.util.Log.println(prio, "VibeDownloader", "$tag: $msg" + (if (t != null) " | ${t.javaClass.simpleName}: ${t.message}" else ""))
        }
        val d = dir ?: return
        val line = buildString {
            append(synchronized(stamp) { stamp.format(Date()) }).append(' ').append(level).append(' ').append(tag).append(": ").append(msg)
            if (t != null) append('\n').append(t.stackTraceToString().take(4000))
            append('\n')
        }
        runCatching {
            writer.execute {
                runCatching {
                    val f = File(d, "app.log")
                    if (f.length() > MAX_BYTES) {
                        val old = File(d, "app.1.log")
                        old.delete()
                        f.renameTo(old)
                    }
                    File(d, "app.log").appendText(line)
                }
            }
        }
    }

    /** Waits briefly for pending lines (used right before a crash). */
    fun flushNow() {
        runCatching {
            val done = java.util.concurrent.CountDownLatch(1)
            writer.execute { done.countDown() }
            done.await(500, TimeUnit.MILLISECONDS)
        }
    }

    /** Builds one text file for "Send a report". */
    fun buildReport(context: Context): File {
        flushNow()
        val out = File(context.cacheDir, "report").apply { mkdirs() }
        val f = File(out, "VibeDownloader-report.txt")
        val d = dir
        val text = buildString {
            append("BD Downloader for Android ").append(BuildConfig.VERSION_NAME).append('\n')
            append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Engine: yt-dlp ").append(MediaEngine.version.value ?: "not started").append('\n')
            append("\n--- log ---\n")
            if (d != null) {
                listOf("app.1.log", "app.log").map { File(d, it) }.filter { it.exists() }.forEach { append(it.readText()) }
            }
        }
        f.writeText(text)
        return f
    }
}
