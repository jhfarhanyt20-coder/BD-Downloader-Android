package com.vibecoder.vibedownloader

import android.app.Application
import com.vibecoder.vibedownloader.data.DownloadRepository
import com.vibecoder.vibedownloader.engine.AppLog
import com.vibecoder.vibedownloader.engine.MediaEngine
import com.vibecoder.vibedownloader.service.DownloadQueue
import com.vibecoder.vibedownloader.service.Notifier

/** Sets up the single shared pieces once per process. Nothing slow runs here. */
class VibeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        Notifier.createChannels(this)
        DownloadRepository.init(this)
        MediaEngine.init(this)
        DownloadQueue.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            AppLog.e("crash", "Uncaught on ${t.name}", e)
            AppLog.flushNow()
            previous?.uncaughtException(t, e)
        }
    }
}
