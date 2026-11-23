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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Keeps downloads alive while the app is in the background (foreground
 * service, type dataSync) and shows the progress notification. The work
 * itself runs in [DownloadQueue]; this service only stays up while it has work.
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
        if (watcher == null) {
            watcher = scope.launch {
                DownloadRepository.items.conflate().collect { list ->
                    if (!DownloadQueue.hasWork()) {
                        delay(1500)   // a new download may follow right away
                        if (!DownloadQueue.hasWork()) { finish(); return@collect }
                    }
                    Notifier.update(this@DownloadService, Notifier.foreground(this@DownloadService, DownloadRepository.items.value))
                    delay(900)   // at most about one notification update per second
                }
            }
        }
        return START_NOT_STICKY
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
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile private var running = false

        fun ensureRunning(context: Context) {
            if (running) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
                running = true
            } catch (e: Exception) {
                // Android may refuse while the app is in the background; downloads still run
                AppLog.w("service", "Foreground service not started: ${e.message}")
            }
        }
    }
}
