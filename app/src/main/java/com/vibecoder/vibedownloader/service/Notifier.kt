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
import com.vibecoder.vibedownloader.data.Status
import com.vibecoder.vibedownloader.engine.MediaSaver
import com.vibecoder.vibedownloader.util.formatEta
import com.vibecoder.vibedownloader.util.formatSpeed

/** All notifications: the ongoing progress one, plus "finished" and "failed". */
object Notifier {
    const val CHANNEL_PROGRESS = "downloads"
    const val CHANNEL_RESULT = "finished"
    const val FOREGROUND_ID = 1

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
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(context: Context, action: String, id: String, request: Int): PendingIntent = PendingIntent.getBroadcast(
        context, request,
        Intent(context, NotificationActionReceiver::class.java).setAction(action).putExtra(NotificationActionReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The ongoing notification of the foreground service. */
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
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        when {
            active.size == 1 -> {
                val one = active[0]
                b.setContentTitle(one.title.ifBlank { one.fileName })
                if (one.status == Status.Finishing) {
                    b.setContentText(one.note ?: "Finishing up").setProgress(0, 0, true)
                } else {
                    val pct = (one.progress * 100).toInt()
                    val parts = listOfNotNull(
                        if (one.total > 0) "$pct%" else null,
                        if (one.speed > 0) formatSpeed(one.speed) else null,
                        formatEta(one.etaSeconds).ifBlank { null },
                    )
                    b.setContentText(parts.joinToString("  ·  ").ifBlank { one.note ?: "Starting" })
                    if (one.total > 0) b.setProgress(1000, (one.progress * 1000).toInt(), false) else b.setProgress(0, 0, true)
                    b.addAction(0, "Pause", action(context, NotificationActionReceiver.ACTION_PAUSE, one.id, 1))
                    b.addAction(0, "Cancel", action(context, NotificationActionReceiver.ACTION_CANCEL, one.id, 2))
                }
            }
            active.size > 1 -> {
                val total = active.sumOf { it.total }
                val done = active.sumOf { it.downloaded }
                val speed = active.sumOf { it.speed }
                b.setContentTitle("Downloading ${active.size} files")
                b.setContentText(listOfNotNull(if (speed > 0) formatSpeed(speed) else null,
                    if (waiting.isNotEmpty()) "${waiting.size} waiting" else null).joinToString("  ·  ").ifBlank { "Working" })
                if (total > 0) b.setProgress(1000, (done * 1000 / total).toInt(), false) else b.setProgress(0, 0, true)
                b.addAction(0, "Pause all", action(context, NotificationActionReceiver.ACTION_PAUSE, NotificationActionReceiver.ALL, 3))
                b.addAction(0, "Cancel all", action(context, NotificationActionReceiver.ACTION_CANCEL, NotificationActionReceiver.ALL, 4))
            }
            waiting.isNotEmpty() -> {
                b.setContentTitle(if (waiting.size == 1) waiting[0].title.ifBlank { waiting[0].fileName } else "${waiting.size} downloads waiting")
                b.setContentText(waiting[0].note ?: "Waiting")
                b.addAction(0, "Pause all", action(context, NotificationActionReceiver.ACTION_PAUSE, NotificationActionReceiver.ALL, 3))
            }
            else -> b.setContentTitle("VibeDownloader").setContentText("Getting ready")
        }
        return b.build()
    }

    @SuppressLint("MissingPermission")
    fun update(context: Context, n: Notification) {
        if (canPost(context)) runCatching { NotificationManagerCompat.from(context).notify(FOREGROUND_ID, n) }
    }

    private fun resultId(item: DownloadItem) = 1000 + (item.id.hashCode() and 0x7ffffff)

    @SuppressLint("MissingPermission")
    fun finished(context: Context, item: DownloadItem) {
        if (!canPost(context)) return
        val uri = item.savedUri
        val tap = if (uri != null) PendingIntent.getActivity(
            context, resultId(item), MediaSaver.openIntent(uri, item.mime),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ) else openApp(context)
        val n = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Download complete")
            .setContentText(item.fileName.ifBlank { item.title })
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(resultId(item), n) }
    }

    @SuppressLint("MissingPermission")
    fun failed(context: Context, item: DownloadItem, message: String) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Download didn't finish")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${item.title.ifBlank { item.fileName }}\n$message"))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(resultId(item), n) }
    }

    fun clear(context: Context, item: DownloadItem) {
        runCatching { NotificationManagerCompat.from(context).cancel(resultId(item)) }
    }
}
