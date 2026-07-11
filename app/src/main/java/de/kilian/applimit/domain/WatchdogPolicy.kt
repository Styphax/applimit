package de.kilian.applimit.domain

/** Pure watchdog decision logic. Android state is read by the worker and passed in here. */
object WatchdogPolicy {
    data class Input(
        val accessibilityEnabled: Boolean,
        val connected: Boolean,
        val connectedAtEpochMillis: Long,
        val lastHeartbeatEpochMillis: Long,
        val serviceResponsive: Boolean,
        val nowEpochMillis: Long,
    )

    enum class Issue {
        ACCESSIBILITY_DISABLED,
        SERVICE_NOT_CONNECTED,
        CONNECTION_STALE,
    }

    data class Evaluation(val issue: Issue? = null) {
        val isHealthy: Boolean get() = issue == null
    }

    fun evaluate(input: Input): Evaluation {
        if (!input.accessibilityEnabled) {
            return Evaluation(Issue.ACCESSIBILITY_DISABLED)
        }
        if (
            !input.connected ||
            input.connectedAtEpochMillis <= 0L ||
            input.lastHeartbeatEpochMillis < input.connectedAtEpochMillis
        ) {
            return Evaluation(Issue.SERVICE_NOT_CONNECTED)
        }
        if (age(input.nowEpochMillis, input.lastHeartbeatEpochMillis) > STALE_AFTER_MILLIS) {
            return Evaluation(Issue.CONNECTION_STALE)
        }
        if (!input.serviceResponsive) {
            return Evaluation(Issue.CONNECTION_STALE)
        }
        return Evaluation()
    }

    private fun age(nowEpochMillis: Long, timestampEpochMillis: Long): Long =
        (nowEpochMillis - timestampEpochMillis).coerceAtLeast(0L)

    const val STALE_AFTER_MILLIS = 3 * 60 * 1_000L
}
