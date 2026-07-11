package de.kilian.applimit.domain

import de.kilian.applimit.data.config.AppGroup
import de.kilian.applimit.data.config.ConfigurationSnapshot
import de.kilian.applimit.data.config.LimitRule
import de.kilian.applimit.data.config.LimitRuleType
import de.kilian.applimit.data.config.LimitTarget
import de.kilian.applimit.data.config.MonitoredApp
import de.kilian.applimit.data.config.UsagePlan
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardAggregatorTest {
    private val monday = LocalDate.of(2026, 7, 6)

    @Test
    fun `day without data still includes monitored targets and active limits`() {
        val summary = DashboardAggregator.aggregateDay(
            date = monday,
            configuration = configuration(),
            usage = emptyList(),
        )

        assertEquals("Jeden Tag", summary.activePlanName)
        assertEquals(listOf("Social", "Alpha", "Beta"), summary.monitoredTargets.map { it.label })
        val alpha = summary.monitoredTargets.single { it.label == "Alpha" }
        assertEquals(0, alpha.openings)
        assertEquals(0L, alpha.durationMillis)
        assertEquals(10, alpha.openingLimit)
        assertEquals(0, alpha.limitHits)
        assertEquals(emptyList<Any>(), summary.unmonitoredApps)
    }

    @Test
    fun `app without limit keeps usage but reports no configured limit`() {
        val summary = DashboardAggregator.aggregateDay(
            date = monday,
            configuration = configuration(),
            usage = listOf(DailyUsageRecord(monday, BETA, 3, 90_000L)),
        )

        val beta = summary.monitoredTargets.single { it.label == "Beta" }
        assertEquals(3, beta.openings)
        assertEquals(90_000L, beta.durationMillis)
        assertNull(beta.openingLimit)
        assertNull(beta.minuteLimit)
    }

    @Test
    fun `group aggregation sums members and applies group grant once`() {
        val groupKey = DashboardTargetKey(DashboardTargetType.GROUP, "1")
        val summary = DashboardAggregator.aggregateDay(
            date = monday,
            configuration = configuration(),
            usage = listOf(
                DailyUsageRecord(monday, ALPHA, 2, 120_000L),
                DailyUsageRecord(monday, BETA, 4, 240_000L),
            ),
            grants = listOf(DailyGrantRecord(monday, groupKey, 0, 5)),
            frictionEvents = listOf(
                DashboardFrictionRecord(monday, BETA, "GROUP", "1"),
            ),
        )

        val group = summary.monitoredTargets.single { it.target == groupKey }
        assertEquals(6, group.openings)
        assertEquals(360_000L, group.durationMillis)
        assertEquals(15, group.minuteLimit)
        assertEquals(5, group.grantedMinutes)
        assertEquals(20, group.effectiveMinuteLimit)
        assertEquals(1, group.frictionPassages)
        assertEquals(1, group.limitHits)
    }

    @Test
    fun `unmonitored tracked apps are sorted by duration`() {
        val summary = DashboardAggregator.aggregateDay(
            date = monday,
            configuration = configuration(),
            usage = listOf(
                DailyUsageRecord(monday, "other.short", 7, 10_000L),
                DailyUsageRecord(monday, "other.long", 1, 70_000L),
            ),
            trackedLabels = mapOf("other.long" to "Long App"),
        )

        assertEquals(
            listOf("other.long", "other.short"),
            summary.unmonitoredApps.map { it.packageName },
        )
        assertEquals("Long App", summary.unmonitoredApps.first().label)
    }

    @Test
    fun `week aggregation fills empty days and supports group selection`() {
        val usage = listOf(
            DailyUsageRecord(monday, ALPHA, 2, 60_000L),
            DailyUsageRecord(monday, BETA, 3, 120_000L),
            DailyUsageRecord(monday.plusDays(6), ALPHA, 4, 300_000L),
            DailyUsageRecord(monday.plusDays(6), "outside", 99, 999_000L),
        )

        val summary = DashboardAggregator.aggregateWeek(
            endDate = monday.plusDays(6),
            configuration = configuration(),
            usage = usage,
            target = DashboardTargetKey(DashboardTargetType.GROUP, "1"),
        )

        assertEquals(7, summary.days.size)
        assertEquals(5, summary.days.first().openings)
        assertEquals(0, summary.days[1].openings)
        assertEquals(4, summary.days.last().openings)
        assertEquals(9, summary.totalOpenings)
        assertEquals(480_000L, summary.totalDurationMillis)
        assertEquals(4, summary.openingsChangeFromPreviousDay)
        assertEquals(300_000L, summary.durationChangeFromPreviousDayMillis)
    }

    @Test
    fun `group sum for group without members is zero`() {
        val result = DashboardAggregator.sumGroupUsage(
            date = monday,
            groupId = 99L,
            configuration = configuration(),
            usage = listOf(DailyUsageRecord(monday, ALPHA, 2, 60_000L)),
        )

        assertEquals(0, result.openings)
        assertEquals(0L, result.durationMillis)
    }

    private fun configuration() = ConfigurationSnapshot(
        apps = listOf(
            MonitoredApp(ALPHA, "Alpha", null, 1L),
            MonitoredApp(BETA, "Beta", null, 1L),
        ),
        groups = listOf(AppGroup(1L, "Social")),
        plans = listOf(UsagePlan(10L, "Jeden Tag", DayOfWeek.entries.toSet())),
        limitRules = listOf(
            LimitRule(100L, 10L, LimitTarget.App(ALPHA), LimitRuleType.OPENINGS, 10, null),
            LimitRule(101L, 10L, LimitTarget.Group(1L), LimitRuleType.MINUTES, 15, null),
        ),
    )

    private companion object {
        const val ALPHA = "app.alpha"
        const val BETA = "app.beta"
    }
}
