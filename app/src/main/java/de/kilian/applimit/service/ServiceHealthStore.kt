package de.kilian.applimit.service

import android.content.Context
import androidx.core.content.edit
import de.kilian.applimit.domain.WatchdogPolicy

/** Small cross-component heartbeat store; Room remains the source of truth for usage data. */
class ServiceHealthStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    data class Snapshot(
        val connected: Boolean,
        val connectedAtEpochMillis: Long,
        val lastHeartbeatEpochMillis: Long,
    ) {
        fun toWatchdogInput(
            accessibilityEnabled: Boolean,
            serviceResponsive: Boolean,
            nowEpochMillis: Long,
        ) = WatchdogPolicy.Input(
            accessibilityEnabled = accessibilityEnabled,
            connected = connected,
            connectedAtEpochMillis = connectedAtEpochMillis,
            lastHeartbeatEpochMillis = lastHeartbeatEpochMillis,
            serviceResponsive = serviceResponsive,
            nowEpochMillis = nowEpochMillis,
        )
    }

    fun snapshot(): Snapshot = Snapshot(
        connected = preferences.getBoolean(KEY_CONNECTED, false),
        connectedAtEpochMillis = preferences.getLong(KEY_CONNECTED_AT, 0L),
        lastHeartbeatEpochMillis = preferences.getLong(KEY_LAST_HEARTBEAT, 0L),
    )

    fun markConnected(nowEpochMillis: Long) {
        preferences.edit {
            putBoolean(KEY_CONNECTED, true)
            putLong(KEY_CONNECTED_AT, nowEpochMillis)
            putLong(KEY_LAST_HEARTBEAT, nowEpochMillis)
        }
    }

    fun markHeartbeat(nowEpochMillis: Long) {
        preferences.edit {
            putBoolean(KEY_CONNECTED, true)
            putLong(KEY_LAST_HEARTBEAT, nowEpochMillis)
        }
    }

    fun markCommandProcessed(nowEpochMillis: Long) {
        preferences.edit {
            putBoolean(KEY_CONNECTED, true)
            putLong(KEY_LAST_HEARTBEAT, nowEpochMillis)
        }
    }

    fun markDisconnected(nowEpochMillis: Long) {
        preferences.edit {
            putBoolean(KEY_CONNECTED, false)
            putLong(KEY_LAST_HEARTBEAT, nowEpochMillis)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "service_health"
        const val KEY_CONNECTED = "accessibility_connected"
        const val KEY_CONNECTED_AT = "accessibility_connected_at"
        const val KEY_LAST_HEARTBEAT = "accessibility_last_heartbeat"
    }
}
