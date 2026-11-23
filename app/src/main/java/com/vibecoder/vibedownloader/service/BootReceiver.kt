package com.vibecoder.vibedownloader.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vibecoder.vibedownloader.data.DownloadItem
import com.vibecoder.vibedownloader.data.DownloadRepository
import com.vibecoder.vibedownloader.engine.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * After the phone restarts (or the app was updated), unfinished downloads
 * continue by themselves, and the smart download button comes back if it was on.
 *
 * Android 14 rules: a dataSync foreground service may be started from
 * BOOT_COMPLETED / MY_PACKAGE_REPLACED (both are exemptions from the
 * background-start limits) as long as the type is declared in the manifest and
 * FOREGROUND_SERVICE_DATA_SYNC is held. The service is started right here,
 * inside the broadcast, so the exemption applies. (Android 15 forbids dataSync
 * from BOOT_COMPLETED for apps targeting 35; this app targets 34.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(8_000) { DownloadRepository.loaded.first { it } }
                val unfinished = DownloadRepository.items.value.count(DownloadItem::interrupted)
                AppLog.i("boot", "$action: $unfinished unfinished downloads")
                if (unfinished > 0) DownloadService.ensureRunning(app, resume = true)
                BubbleService.startIfEnabled(app)
            } catch (t: Throwable) {
                AppLog.w("boot", "Could not continue after restart", t)
            } finally {
                pending.finish()
            }
        }
    }
}
