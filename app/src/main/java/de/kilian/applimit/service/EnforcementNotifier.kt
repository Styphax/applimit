package de.kilian.applimit.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import de.kilian.applimit.MainActivity
import de.kilian.applimit.R

/** Notification channels and messages emitted by M4 enforcement. */
class EnforcementNotifier(context: Context) {
    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        notificationManager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    WARNING_CHANNEL_ID,
                    "Limit-Warnungen",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Vorwarnungen für Öffnungs- und Zeitbudgets"
                    setShowBadge(false)
                },
                NotificationChannel(
                    SYSTEM_CHANNEL_ID,
                    "Enforcement-Status",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Technische Hinweise, wenn ein Block nicht angezeigt werden kann"
                    setShowBadge(false)
                },
            ),
        )
    }

    fun notifyMinuteBudgetWarning(key: String, label: String) {
        notifySafely(
            id = stableId("minutes:$key"),
            notification = warningBuilder()
                .setContentTitle("$label: 80 % verbraucht")
                .setContentText("Dein heutiges Zeitbudget nähert sich dem Limit.")
                .build(),
        )
    }

    fun notifyOpeningBudgetWarning(key: String, label: String) {
        notifySafely(
            id = stableId("openings:$key"),
            notification = warningBuilder()
                .setContentTitle("$label: noch eine Öffnung")
                .setContentText("Die vorletzte erlaubte Öffnung ist verbraucht.")
                .build(),
        )
    }

    fun notifyOverlayUnavailable() {
        notifySafely(
            id = OVERLAY_ERROR_NOTIFICATION_ID,
            notification = Notification.Builder(appContext, SYSTEM_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_app_limit)
                .setContentTitle("AppLimit kann den Block nicht anzeigen")
                .setContentText("Bitte die Berechtigung „Über anderen Apps anzeigen“ prüfen.")
                .setContentIntent(contentIntent())
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ERROR)
                .build(),
        )
    }

    private fun warningBuilder(): Notification.Builder =
        Notification.Builder(appContext, WARNING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_app_limit)
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_REMINDER)

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        appContext,
        0,
        Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notifySafely(id: Int, notification: Notification) {
        try {
            notificationManager.notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS is handled by onboarding; detection must keep running without it.
        }
    }

    private fun stableId(key: String): Int = key.hashCode() and Int.MAX_VALUE

    private companion object {
        const val WARNING_CHANNEL_ID = "limit_warnings_v1"
        const val SYSTEM_CHANNEL_ID = "enforcement_status_v1"
        const val OVERLAY_ERROR_NOTIFICATION_ID = 48_001
    }
}
