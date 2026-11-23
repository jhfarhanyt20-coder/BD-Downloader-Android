package com.vibecoder.vibedownloader.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Pause / Cancel buttons in the notification. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        when (intent.action) {
            ACTION_PAUSE -> if (id == ALL) DownloadQueue.pauseAll() else DownloadQueue.pause(id)
            ACTION_CANCEL -> if (id == ALL) DownloadQueue.cancelAll() else DownloadQueue.cancel(id)
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.vibecoder.vibedownloader.PAUSE"
        const val ACTION_CANCEL = "com.vibecoder.vibedownloader.CANCEL"
        const val EXTRA_ID = "id"
        const val ALL = "*"
    }
}
