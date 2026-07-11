package de.kilian.applimit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogPolicyTest {
    private val now = 10_000_000L

    @Test
    fun `disabled accessibility is reported first`() {
        assertIssue(
            input(accessibilityEnabled = false, connected = false),
            WatchdogPolicy.Issue.ACCESSIBILITY_DISABLED,
        )
    }

    @Test
    fun `enabled but disconnected service is reported`() {
        assertIssue(input(connected = false), WatchdogPolicy.Issue.SERVICE_NOT_CONNECTED)
    }

    @Test
    fun `heartbeat older than threshold is stale`() {
        assertIssue(
            input(
                connectedAtEpochMillis = now - WatchdogPolicy.STALE_AFTER_MILLIS - 60_000L,
                lastHeartbeatEpochMillis = now - WatchdogPolicy.STALE_AFTER_MILLIS - 1,
            ),
            WatchdogPolicy.Issue.CONNECTION_STALE,
        )
    }

    @Test
    fun `responsive idle service is healthy without accessibility events`() {
        val evaluation = WatchdogPolicy.evaluate(
            input(
                connectedAtEpochMillis = now - 30 * 60_000L,
                serviceResponsive = true,
            ),
        )
        assertTrue(evaluation.isHealthy)
    }

    @Test
    fun `connected store without a responsive service is stale`() {
        assertIssue(input(serviceResponsive = false), WatchdogPolicy.Issue.CONNECTION_STALE)
    }

    @Test
    fun `fresh connected responsive service is healthy`() {
        val evaluation = WatchdogPolicy.evaluate(input())
        assertTrue(evaluation.isHealthy)
    }

    private fun input(
        accessibilityEnabled: Boolean = true,
        connected: Boolean = true,
        connectedAtEpochMillis: Long = now - 60_000L,
        lastHeartbeatEpochMillis: Long = now - 10_000L,
        serviceResponsive: Boolean = true,
    ) = WatchdogPolicy.Input(
        accessibilityEnabled = accessibilityEnabled,
        connected = connected,
        connectedAtEpochMillis = connectedAtEpochMillis,
        lastHeartbeatEpochMillis = lastHeartbeatEpochMillis,
        serviceResponsive = serviceResponsive,
        nowEpochMillis = now,
    )

    private fun assertIssue(input: WatchdogPolicy.Input, issue: WatchdogPolicy.Issue) {
        assertEquals(issue, WatchdogPolicy.evaluate(input).issue)
    }
}
