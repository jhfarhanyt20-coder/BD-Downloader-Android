package com.vibecoder.vibedownloader.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vibecoder.vibedownloader.data.DownloadRepository
import com.vibecoder.vibedownloader.engine.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps downloads alive while the app is in the background (foreground
 * service, type dataSync) and keeps the notifications up to date. The work
 * itself runs in [DownloadQueue]; this service only stays up while it has work.
 *
 * START_STICKY: if Android ends the process (low memory, swiped away), it
 * starts the service again, which then continues the unfinished downloads.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        running = true
        if (watcher != null && intent?.getBooleanExtra(EXTRA_RESUME, false) == true) {
            scope.launch { DownloadQueue.resumeInterrupted() }
        }
        if (watcher == null || watcher?.isActive != true) {
            watcher = scope.launch {
                // a fresh process (restart after being killed, or after a reboot): read the list first,
                // then continue what was running
                withTimeoutOrNull(10_000) { DownloadRepository.loaded.first { it } }
                if (intent == null || intent.getBooleanExtra(EXTRA_RESUME, false)) {
                    AppLog.i("service", "Continuing unfinished downloads (${if (intent == null) "restarted by Android" else "after restart"})")
                    DownloadQueue.resumeInterrupted()
                }
                DownloadRepository.items.collect { items ->
                    if (!DownloadQueue.hasWork()) {
                        delay(1500)   // a new download may follow right away
                        if (!DownloadQueue.hasWork()) { finish(); throw kotlinx.coroutines.CancellationException("done") }
                    }
                    Notifier.sync(this@DownloadService, DownloadRepository.items.value, service = true)
                    delay(1000)   // at most about one notification round per second
                }
            }
        }
        return START_STICKY
    }

    private fun goForeground() {
        val n = Notifier.foreground(this, DownloadRepository.items.value)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, Notifier.FOREGROUND_ID, n, type)
        } catch (e: Exception) {
            AppLog.e("service", "Could not start in the foreground", e)
        }
    }

    private fun finish() {
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        Notifier.sync(this, DownloadRepository.items.value, service = false)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_RESUME = "resume"
        @Volatile private var running = false

        val isRunning: Boolean get() = running

        fun ensureRunning(context: Context, resume: Boolean = false) {
            if (running && !resume) return
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, DownloadService::class.java).putExtra(EXTRA_RESUME, resume),
                )
                running = true
            } catch (e: Exception) {
                // Android may refuse while the app is in the background; downloads still run
                AppLog.w("service", "Foreground service not started: ${e.message}")
            }
        }
    }
}
