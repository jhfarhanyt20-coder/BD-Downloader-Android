package com.vibecoder.vibedownloader.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vibecoder.vibedownloader.Prefs

/** Buttons in the notifications: Pause, Resume, Cancel, Retry, and turning the smart button off. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        when (intent.action) {
            ACTION_PAUSE -> if (id == ALL) DownloadQueue.pauseAll() else DownloadQueue.pause(id)
            ACTION_CANCEL -> if (id == ALL) DownloadQueue.cancelAll() else { Notifier.clearId(context, id); DownloadQueue.cancel(id) }
            ACTION_RESUME, ACTION_RETRY -> { Notifier.clearId(context, id); DownloadQueue.resume(id) }
            ACTION_BUBBLE_OFF -> { Prefs(context).smartButton = false; BubbleService.stop(context) }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.vibecoder.vibedownloader.PAUSE"
        const val ACTION_CANCEL = "com.vibecoder.vibedownloader.CANCEL"
        const val ACTION_RESUME = "com.vibecoder.vibedownloader.RESUME"
        const val ACTION_RETRY = "com.vibecoder.vibedownloader.RETRY"
        const val ACTION_BUBBLE_OFF = "com.vibecoder.vibedownloader.BUBBLE_OFF"
        const val EXTRA_ID = "id"
        const val ALL = "*"
    }
}
