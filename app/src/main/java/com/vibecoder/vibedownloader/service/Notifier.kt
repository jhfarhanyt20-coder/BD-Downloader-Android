package com.vibecoder.vibedownloader.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vibecoder.vibedownloader.MainActivity
import com.vibecoder.vibedownloader.R
import com.vibecoder.vibedownloader.data.DownloadItem
import com.vibecoder.vibedownloader.data.DownloadRepository
import com.vibecoder.vibedownloader.data.Status
import com.vibecoder.vibedownloader.engine.MediaSaver
import com.vibecoder.vibedownloader.util.formatBytes
import com.vibecoder.vibedownloader.util.formatEta
import com.vibecoder.vibedownloader.util.formatSpeed
import java.util.concurrent.ConcurrentHashMap

/**
 * All notifications, grouped under one summary:
 *  - running: progress with Pause and Cancel
 *  - paused: stays, with Resume and Cancel
 *  - finished: Open and Share
 *  - failed: Retry
 * The foreground service's notification is the group summary while it runs;
 * afterwards a quiet summary keeps the finished ones together.
 */
object Notifier {
    const val CHANNEL_PROGRESS = "downloads"
    const val CHANNEL_RESULT = "finished"
    const val CHANNEL_BUBBLE = "bubble"
    const val FOREGROUND_ID = 1
    const val SUMMARY_ID = 2
    const val BUBBLE_ID = 3
    const val GROUP = "com.vibecoder.vibedownloader.DOWNLOADS"

    /** Items paused while the app was running: only these keep a "Paused" notification. */
    private val pausedHere: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** What each progress notification shows now, so unchanged ones are not posted again. */
    private val posted = ConcurrentHashMap<String, String>()
    @Volatile private var serviceUp = false

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Downloads in progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress, speed, pause and cancel"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a download is ready, or could not finish"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BUBBLE, "Smart download button", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while the floating download button is turned on"
                setShowBadge(false)
            }
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun markPaused(id: String) { pausedHere += id }
    fun unmarkPaused(id: String) { pausedHere -= id }

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun openApp(context: Context, showId: String? = null): PendingIntent = PendingIntent.getActivity(
        context, if (showId == null) 0 else code(showId, 7),
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (showId != null) putExtra(MainActivity.EXTRA_SHOW_ID, showId) },
        FLAGS,
    )

    private fun code(id: String, slot: Int) = ((id.hashCode() and 0x0fffffff) * 8 + slot) and 0x7fffffff

    private fun action(context: Context, action: String, id: String, request: Int): PendingIntent = PendingIntent.getBroadcast(
        context, request,
        Intent(context, NotificationActionReceiver::class.java).setAction(action).putExtra(NotificationActionReceiver.EXTRA_ID, id),
        FLAGS,
    )

    private fun itemAction(context: Context, act: String, item: DownloadItem, slot: Int) = action(context, act, item.id, code(item.id, slot))

    private fun itemId(item: DownloadItem) = itemId(item.id)
    private fun itemId(id: String) = 1000 + (id.hashCode() and 0x7ffffff)

    private fun name(item: DownloadItem) = item.fileName.ifBlank { item.title }.ifBlank { "Download" }

    // ------------------------------------------------------------ summary (foreground)
    /** The ongoing notification of the foreground service, also the group summary. */
    fun foreground(context: Context, items: List<DownloadItem>): Notification {
        val active = items.filter { it.status == Status.Downloading || it.status == Status.Finishing }
        val waiting = items.filter { it.status == Status.Queued || it.status == Status.Waiting }
        val b = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(context))
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val title = when {
            active.size == 1 -> name(active[0])
            active.size > 1 -> "Downloading ${active.size} files"
            waiting.size == 1 -> name(waiting[0])
            waiting.isNotEmpty() -> "${waiting.size} downloads waiting"
            else -> "VibeDownloader"
        }
        val speed = active.sumOf { it.speed }
        val text = listOfNotNull(
            if (speed > 0) formatSpeed(speed) else null,
            if (waiting.isNotEmpty() && active.isNotEmpty()) "${waiting.size} waiting" else null,
            if (active.isEmpty()) waiting.firstOrNull()?.note ?: (if (waiting.isNotEmpty()) "Waiting" else "Getting ready") else null,
        ).joinToString("  ·  ").ifBlank { "Working" }
        b.setContentTitle(title).setContentText(text)
        val total = active.sumOf { it.total }
        val done = active.sumOf { it.downloaded }
        if (active.isNotEmpty()) {
            if (total > 0 && active.all { it.total > 0 }) b.setProgress(1000, (done * 1000 / total).toInt().coerceIn(0, 1000), false)
            else b.setProgress(0, 0, true)
        }
        if (active.size + waiting.size > 1) {
            val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
            (active + waiting).take(6).forEach { style.addLine(name(it) + "  ·  " + shortState(it)) }
            if (active.size + waiting.size > 6) style.setSummaryText("+${active.size + waiting.size - 6} more")
            b.setStyle(style)
        }
        if (active.size + waiting.size > 0) {
            b.addAction(0, "Pause all", action(context, NotificationActionReceiver.ACTION_PAUSE, NotificationActionReceiver.ALL, 3))
            b.addAction(0, "Cancel all", action(context, NotificationActionReceiver.ACTION_CANCEL, NotificationActionReceiver.ALL, 4))
        }
        return b.build()
    }

    private fun shortState(it: DownloadItem): String = when (it.status) {
        Status.Downloading -> if (it.total > 0) "${(it.progress * 100).toInt()}%" else formatBytes(it.downloaded)
        Status.Finishing -> "Finishing"
        Status.Paused -> "Paused"
        Status.Waiting -> if (it.note == DownloadQueue.WAIT_WIFI) "Waiting for Wi-Fi" else "Waiting for network"
        else -> "In line"
    }

    // ------------------------------------------------------------ one per download
    private fun progressNotification(context: Context, item: DownloadItem): Notification {
        val b = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(name(item))
            .setGroup(GROUP)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context, item.id))
            .setSortKey(String.format(java.util.Locale.US, "%015d", item.order.coerceAtLeast(0)))
        when (item.status) {
            Status.Paused -> {
                b.setOngoing(false)
                b.setContentText(listOfNotNull("Paused", if (item.total > 0) "${(item.progress * 100).toInt()}%" else null).joinToString("  ·  "))
                if (item.total > 0) b.setProgress(1000, (item.progress * 1000).toInt(), false)
                b.addAction(0, "Resume", itemAction(context, NotificationActionReceiver.ACTION_RESUME, item, 1))
                b.addAction(0, "Cancel", itemAction(context, NotificationActionReceiver.ACTION_CANCEL, item, 2))
            }
            Status.Finishing -> {
                b.setOngoing(true).setContentText(item.note ?: "Finishing up").setProgress(0, 0, true)
            }
            else -> {
                b.setOngoing(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
                val text = when (item.status) {
                    Status.Downloading -> listOfNotNull(
                        if (item.total > 0) "${(item.progress * 100).toInt()}%" else if (item.downloaded > 0) formatBytes(item.downloaded) else null,
                        if (item.speed > 0) formatSpeed(item.speed) else null,
                        formatEta(item.etaSeconds).ifBlank { null },
                    ).joinToString("  ·  ").ifBlank { item.note ?: "Starting" }
                    else -> item.note ?: DownloadQueue.WAIT_LINE
                }
                b.setContentText(text)
                if (item.status == Status.Downloading && item.total > 0) b.setProgress(1000, (item.progress * 1000).toInt(), false)
                else b.setProgress(0, 0, true)
                b.addAction(0, "Pause", itemAction(context, NotificationActionReceiver.ACTION_PAUSE, item, 1))
                b.addAction(0, "Cancel", itemAction(context, NotificationActionReceiver.ACTION_CANCEL, item, 2))
            }
        }
        return b.build()
    }

    private fun signature(item: DownloadItem): String {
        // speed is only refreshed every 3 seconds, so many downloads never flood the system
        val slot = System.currentTimeMillis() / 3000
        return "${item.status}|${(item.progress * 100).toInt()}|${item.note}|${if (item.status == Status.Downloading) slot else 0}|${item.title}"
    }

    /** Which downloads get their own progress notification (at most a handful). */
    private fun wanted(items: List<DownloadItem>): List<DownloadItem> {
        val running = items.filter { it.status == Status.Downloading || it.status == Status.Finishing }
        val waiting = items.filter { it.status == Status.Waiting || it.status == Status.Queued }
            .sortedWith(compareBy({ it.order }, { it.createdAt })).take(3)
        val paused = items.filter { it.status == Status.Paused && it.id in pausedHere }.take(5)
        return (running + waiting + paused).take(10)
    }

    /**
     * Brings the per-download notifications in line with the list. Called about
     * once a second by the service, and after pause / finish when it is not running.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun sync(context: Context, items: List<DownloadItem>, service: Boolean) {
        serviceUp = service
        if (!canPost(context)) return
        val nm = NotificationManagerCompat.from(context)
        val want = wanted(items)
        val ids = want.map { it.id }.toSet()
        // gone or no longer shown: remove (finished/failed ones are posted by finished()/failed())
        posted.keys.filter { it !in ids }.forEach { id ->
            posted.remove(id)
            val now = items.firstOrNull { it.id == id }
            if (now == null || now.status != Status.Done && now.status != Status.Failed) runCatching { nm.cancel(itemId(id)) }
        }
        want.forEach { item ->
            val sig = signature(item)
            if (posted[item.id] != sig) {
                posted[item.id] = sig
                runCatching { nm.notify(itemId(item), progressNotification(context, item)) }
            }
        }
        if (service) {
            runCatching { nm.cancel(SUMMARY_ID) }
            runCatching { nm.notify(FOREGROUND_ID, foreground(context, items)) }
        } else {
            quietSummary(context)
        }
    }

    /** After a pause or finish while the service may be stopped. */
    fun refresh(context: Context) {
        if (DownloadService.isRunning) return  // the service loop catches up within a second
        sync(context, DownloadRepository.items.value, service = false)
    }

    /** With the service gone, a plain summary keeps 2+ notifications neatly together. */
    @SuppressLint("MissingPermission")
    private fun quietSummary(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val children = runCatching {
            nm.activeNotifications.count { it.notification.group == GROUP && it.id != FOREGROUND_ID && it.id != SUMMARY_ID }
        }.getOrDefault(0)
        if (children < 2) { runCatching { nm.cancel(SUMMARY_ID) }; return }
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("VibeDownloader")
            .setContentText("$children downloads")
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(SUMMARY_ID, n) }
    }

    @SuppressLint("MissingPermission")
    fun update(context: Context, n: Notification) {
        if (canPost(context)) runCatching { NotificationManagerCompat.from(context).notify(FOREGROUND_ID, n) }
    }

    @SuppressLint("MissingPermission")
    fun finished(context: Context, item: DownloadItem) {
        posted.remove(item.id)
        if (!canPost(context)) return
        val uri = item.savedUri
        val b = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Download complete")
            .setContentText(name(item))
            .setGroup(GROUP)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
        if (uri != null) {
            val open = PendingIntent.getActivity(context, code(item.id, 3), MediaSaver.openIntent(uri, item.mime), FLAGS)
            val share = PendingIntent.getActivity(context, code(item.id, 4), MediaSaver.shareIntent(uri, item.mime, name(item)), FLAGS)
            b.setContentIntent(open)
            b.addAction(0, "Open", open)
            b.addAction(0, "Share", share)
        } else b.setContentIntent(openApp(context, item.id))
        runCatching { NotificationManagerCompat.from(context).notify(itemId(item), b.build()) }
        if (!DownloadService.isRunning) quietSummary(context)
    }

    @SuppressLint("MissingPermission")
    fun failed(context: Context, item: DownloadItem, message: String) {
        posted.remove(item.id)
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Download didn't finish")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${name(item)}\n$message"))
            .setContentIntent(openApp(context, item.id))
            .setGroup(GROUP)
            .setAutoCancel(true)
            .addAction(0, "Retry", itemAction(context, NotificationActionReceiver.ACTION_RETRY, item, 5))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(itemId(item), n) }
        if (!DownloadService.isRunning) quietSummary(context)
    }

    fun clear(context: Context, item: DownloadItem) = clearId(context, item.id)

    fun clearId(context: Context, id: String) {
        posted.remove(id)
        pausedHere -= id
        runCatching { NotificationManagerCompat.from(context).cancel(itemId(id)) }
    }

    // ------------------------------------------------------------ smart button
    fun bubble(context: Context, hint: String): Notification {
        val off = PendingIntent.getBroadcast(
            context, 9,
            Intent(context, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_BUBBLE_OFF)
                .putExtra(NotificationActionReceiver.EXTRA_ID, "bubble"),
            FLAGS,
        )
        return NotificationCompat.Builder(context, CHANNEL_BUBBLE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Smart download button is on")
            .setContentText(hint)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp(context))
            .addAction(0, "Turn off", off)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
