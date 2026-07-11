package de.kilian.applimit.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat

/** Sticky, low-importance foreground anchor for the accessibility enforcement process. */
class ForegroundAnchorService : Service() {
    override fun onCreate() {
        super.onCreate()
        val notifier = RobustnessNotifier(applicationContext)
        notifier.createChannels()
        startForeground(
            RobustnessNotifier.ANCHOR_NOTIFICATION_ID,
            notifier.anchorNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, ForegroundAnchorService::class.java),
            )
        }
    }
}
