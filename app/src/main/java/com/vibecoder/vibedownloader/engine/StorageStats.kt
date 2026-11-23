package com.vibecoder.vibedownloader.engine

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import com.vibecoder.vibedownloader.data.Category
import com.vibecoder.vibedownloader.data.DownloadRepository
import com.vibecoder.vibedownloader.data.Kind
import com.vibecoder.vibedownloader.data.Status
import com.vibecoder.vibedownloader.service.DownloadQueue
import java.io.File

/** Phone space, space used by downloads per type, and temporary files that can be cleared. */
object StorageStats {
    data class Info(
        val total: Long,
        val free: Long,
        val byCategory: Map<Category, Long>,
        val files: Int,
        /** Temporary files that are safe to delete now. */
        val clearable: Long,
        /** Parts that paused or running downloads still need (never deleted). */
        val kept: Long,
    ) {
        val used: Long get() = (total - free).coerceAtLeast(0)
        val downloads: Long get() = byCategory.values.sum()
    }

    private fun size(f: File): Long = if (!f.exists()) 0 else if (f.isFile) f.length() else (f.listFiles()?.sumOf { size(it) } ?: 0)

    private fun savedSize(context: Context, uri: String?, path: String?): Long {
        if (path != null) return File(path).takeIf { it.exists() }?.length() ?: -1
        if (uri == null) return -1
        return runCatching {
            context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { it.statSize } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun workRoot(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "work")

    private fun engineCache(context: Context) = File(context.cacheDir, "engine")

    private fun otherCaches(context: Context) = listOf(File(context.cacheDir, "thumbs"), File(context.cacheDir, "report"))

    /** Ids whose parts must stay: anything not finished can still continue from where it stopped. */
    private fun needed(): Set<String> = DownloadRepository.items.value.filter { it.status != Status.Done }.map { it.id }.toSet()

    private fun mediaRunning(): Boolean = DownloadRepository.items.value.any { it.kind == Kind.Media && it.status.isRunning }

    /** Blocking: call from a background thread. */
    fun read(context: Context): Info {
        val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val total = stat?.totalBytes ?: 0L
        val free = stat?.availableBytes ?: 0L
        val by = mutableMapOf<Category, Long>()
        var files = 0
        DownloadRepository.items.value.filter { it.status == Status.Done }.forEach { item ->
            val s = savedSize(context, item.savedUri, item.savedPath)
            if (s >= 0) {
                val c = Category.of(item)
                by[c] = (by[c] ?: 0L) + s
                files++
            }
        }
        val keep = needed()
        var clearable = 0L
        var kept = 0L
        workRoot(context).listFiles()?.forEach { f ->
            val s = size(f)
            if (f.isDirectory && f.name in keep) kept += s else clearable += s
        }
        val engine = size(engineCache(context))
        if (mediaRunning()) kept += engine else clearable += engine
        clearable += otherCaches(context).sumOf { size(it) }
        return Info(total, free, by, files, clearable, kept)
    }

    /** Deletes temporary files no download needs any more. Returns the bytes freed. */
    fun clearTemporary(context: Context): Long {
        val keep = needed()
        var freed = 0L
        workRoot(context).listFiles()?.forEach { f ->
            // a download that is running, paused, waiting or can be retried keeps its parts
            if (f.isDirectory && f.name in keep) return@forEach
            if (DownloadQueue.isRunning(f.name)) return@forEach
            val s = size(f)
            if (f.deleteRecursively()) freed += s
        }
        if (!mediaRunning()) {
            val e = engineCache(context)
            val s = size(e)
            if (e.deleteRecursively()) freed += s
        }
        otherCaches(context).forEach { d ->
            val s = size(d)
            if (d.deleteRecursively()) freed += s
        }
        AppLog.i("storage", "Cleared ${freed / 1024} KB of temporary files")
        return freed
    }
}
