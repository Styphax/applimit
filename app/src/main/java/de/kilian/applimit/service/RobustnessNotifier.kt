package de.kilian.applimit.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import de.kilian.applimit.MainActivity
import de.kilian.applimit.R
import de.kilian.applimit.domain.WatchdogPolicy

class RobustnessNotifier(context: Context) {
    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        notificationManager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    ANCHOR_CHANNEL_ID,
                    "AppLimit aktiv",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Unauffälliger permanenter Status der App-Überwachung"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
                NotificationChannel(
                    WATCHDOG_CHANNEL_ID,
                    "Überwachung ausgefallen",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Warnt, wenn die AppLimit-Bedienungshilfe nicht zuverlässig läuft"
                    setShowBadge(false)
                },
            ),
        )
    }

    fun anchorNotification(): Notification = Notification.Builder(appContext, ANCHOR_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_app_limit)
        .setContentTitle("AppLimit ist aktiv")
        .setContentText("App-Nutzung und Limits werden lokal überwacht.")
        .setContentIntent(appContentIntent())
        .setCategory(Notification.CATEGORY_SERVICE)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    fun notifyWatchdogIssue(issue: WatchdogPolicy.Issue) {
        val text = when (issue) {
            WatchdogPolicy.Issue.ACCESSIBILITY_DISABLED ->
                "Die AppLimit-Bedienungshilfe ist deaktiviert. Bitte wieder einschalten."
            WatchdogPolicy.Issue.SERVICE_NOT_CONNECTED ->
                "Die AppLimit-Bedienungshilfe ist nicht verbunden. Bitte den Schalter prüfen."
            WatchdogPolicy.Issue.CONNECTION_STALE ->
                "AppLimit meldet kein Lebenszeichen mehr. Bitte die Bedienungshilfe neu aktivieren."
        }
        notifySafely(
            WATCHDOG_NOTIFICATION_ID,
            Notification.Builder(appContext, WATCHDOG_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_app_limit)
                .setContentTitle("AppLimit-Überwachung prüfen")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(accessibilitySettingsIntent())
                .setCategory(Notification.CATEGORY_ERROR)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    fun cancelWatchdogIssue() {
        notificationManager.cancel(WATCHDOG_NOTIFICATION_ID)
    }

    private fun appContentIntent(): PendingIntent = PendingIntent.getActivity(
        appContext,
        0,
        Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun accessibilitySettingsIntent(): PendingIntent = PendingIntent.getActivity(
        appContext,
        1,
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notifySafely(id: Int, notification: Notification) {
        try {
            notificationManager.notify(id, notification)
        } catch (_: SecurityException) {
            // Onboarding requests POST_NOTIFICATIONS; robustness must not crash without it.
        }
    }

    companion object {
        const val ANCHOR_CHANNEL_ID = "foreground_anchor_v1"
        const val ANCHOR_NOTIFICATION_ID = 49_001
        const val WATCHDOG_CHANNEL_ID = "accessibility_watchdog_v1"
        const val WATCHDOG_NOTIFICATION_ID = 49_002
    }
}
