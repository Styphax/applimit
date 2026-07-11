package de.kilian.applimit.domain

import de.kilian.applimit.data.config.ConfigurationSnapshot
import de.kilian.applimit.data.config.LimitRule
import de.kilian.applimit.data.config.LimitRuleType
import de.kilian.applimit.data.config.LimitTarget
import de.kilian.applimit.data.config.MonitoredApp
import de.kilian.applimit.data.config.UsagePlan
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnforcementEvaluatorTest {
    private val evaluator = EnforcementEvaluator(ZoneOffset.UTC)

    @Test
    fun openingBudgetAllowsConfiguredCountAndBlocksNextAttempt() {
        val configuration = configuration(
            rules = listOf(rule(LimitTarget.App(APP_A), LimitRuleType.OPENINGS, 2)),
        )

        val atLimit = evaluator.evaluate(
            MONDAY,
            APP_A,
            configuration,
            counters(openingsA = 2),
        )
        val exceeded = evaluator.evaluate(
            MONDAY,
            APP_A,
            configuration,
            counters(openingsA = 3),
        )

        assertFalse(atLimit.requiresFriction)
        assertTrue(exceeded.requiresFriction)
        assertEquals(LimitRuleType.OPENINGS, exceeded.breaches.single().type)
    }

    @Test
    fun increasedLimitImmediatelyChangesRunningEnforcementEvaluation() {
        val counters = counters(openingsA = 3)
        val beforeIncrease = evaluator.evaluate(
            MONDAY,
            APP_A,
            configuration(
                rules = listOf(rule(LimitTarget.App(APP_A), LimitRuleType.OPENINGS, 2)),
            ),
            counters,
        )
        val afterIncrease = evaluator.evaluate(
            MONDAY,
            APP_A,
            configuration(
                rules = listOf(rule(LimitTarget.App(APP_A), LimitRuleType.OPENINGS, 3)),
            ),
            counters,
        )

        assertTrue(beforeIncrease.requiresFriction)
        assertFalse(afterIncrease.requiresFriction)
    }

    @Test
    fun minuteBudgetBlocksAtExactLimitAndBothRulesCanBlockTogether() {
        val configuration = configuration(
            rules = listOf(
                rule(LimitTarget.App(APP_A), LimitRuleType.OPENINGS, 2),
                rule(LimitTarget.App(APP_A), LimitRuleType.MINUTES, 1),
            ),
        )

        val minutesOnly = evaluator.evaluate(
            MONDAY,
            APP_A,
            configuration,
            counters(openingsA = 2, millisA = 60_000L),
        )
        val both = evaluator.evaluate(
            MONDAY,
            APP_A,
            configuration,
            counters(openingsA = 3, millisA = 60_000L),
        )

        assertEquals(listOf(LimitRuleType.MINUTES), minutesOnly.breaches.map { it.type })
        assertEquals(
            setOf(LimitRuleType.OPENINGS, LimitRuleType.MINUTES),
            both.breaches.mapTo(linkedSetOf()) { it.type },
        )
    }

    @Test
    fun groupBudgetAccumulatesAcrossMemberApps() {
        val configuration = configuration(
            groupId = GROUP_ID,
            includeSecondApp = true,
            rules = listOf(rule(LimitTarget.Group(GROUP_ID), LimitRuleType.MINUTES, 2)),
        )

        val result = evaluator.evaluate(
            MONDAY,
            APP_A,
            configuration,
            counters(millisA = 70_000L, millisB = 50_000L),
        )

        assertTrue(result.requiresFriction)
        assertEquals(120_000L, result.breaches.single().used)
        assertEquals(LimitTarget.Group(GROUP_ID), result.breaches.single().target)
    }

    @Test
    fun warningsFireAtEightyPercentAndWhenOneOpeningRemains() {
        val configuration = configuration(
            rules = listOf(
                rule(LimitTarget.App(APP_A), LimitRuleType.OPENINGS, 3),
                rule(LimitTarget.App(APP_A), LimitRuleType.MINUTES, 10),
            ),
        )

        val before = evaluator.evaluate(
            MONDAY,
            APP_A,
            configuration,
            counters(openingsA = 1, millisA = 479_999L),
        )
        val threshold = evaluator.evaluate(
            MONDAY,
            APP_A,
            configuration,
            counters(openingsA = 2, millisA = 480_000L),
        )

        assertTrue(before.warnings.isEmpty())
        assertEquals(
            setOf(
                EnforcementEvaluator.WarningKind.ONE_OPENING_REMAINING,
                EnforcementEvaluator.WarningKind.MINUTES_EIGHTY_PERCENT,
            ),
            threshold.warnings.mapTo(linkedSetOf()) { it.kind },
        )
    }

    @Test
    fun lastMinuteReturnsEdgeCountdown() {
        val configuration = configuration(
            rules = listOf(rule(LimitTarget.App(APP_A), LimitRuleType.MINUTES, 10)),
        )

        val outsideLastMinute = evaluator.evaluate(
            MONDAY,
            APP_A,
            configuration,
            counters(millisA = 539_999L),
        )
        val lastMinute = evaluator.evaluate(
            MONDAY,
            APP_A,
            configuration,
            counters(millisA = 540_000L),
        )

        assertNull(outsideLastMinute.countdownSeconds)
        assertEquals(60, lastMinute.countdownSeconds)
    }

    @Test
    fun frictionGrantsScaleWithVariantAndHaveNoDailyCap() {
        val target = LimitTarget.App(APP_A)
        val configuration = configuration(
            rules = listOf(
                rule(target, LimitRuleType.OPENINGS, 2),
                rule(target, LimitRuleType.MINUTES, 1),
            ),
        )
        val firstBlock = evaluator.evaluate(
            MONDAY,
            APP_A,
            configuration,
            counters(openingsA = 3, millisA = 60_000L),
        )
        val firstGrants = evaluator.applyGrantDeltas(
            emptyMap(),
            evaluator.frictionGrantDeltas(
                firstBlock.breaches,
                EnforcementEvaluator.GrantVariant.SMALL,
            ),
        )

        assertEquals(1, firstGrants.getValue(target).grantedMinutes)
        assertEquals(1, firstGrants.getValue(target).grantedOpenings)
        assertFalse(
            evaluator.evaluate(
                MONDAY,
                APP_A,
                configuration,
                counters(openingsA = 3, millisA = 60_000L),
                firstGrants,
            ).requiresFriction,
        )

        val secondBlock = evaluator.evaluate(
            MONDAY,
            APP_A,
            configuration,
            counters(openingsA = 4, millisA = 120_000L),
            firstGrants,
        )
        assertTrue(secondBlock.requiresFriction)
        val secondGrants = evaluator.applyGrantDeltas(
            firstGrants,
            evaluator.frictionGrantDeltas(
                secondBlock.breaches,
                EnforcementEvaluator.GrantVariant.LARGE,
            ),
        )

        assertEquals(6, secondGrants.getValue(target).grantedMinutes)
        assertEquals(4, secondGrants.getValue(target).grantedOpenings)
        assertFalse(
            evaluator.evaluate(
                MONDAY,
                APP_A,
                configuration,
                counters(openingsA = 4, millisA = 120_000L),
                secondGrants,
            ).requiresFriction,
        )
    }

    @Test
    fun appAndGroupBreachesReceiveSeparateTargetGrants() {
        val appTarget = LimitTarget.App(APP_A)
        val groupTarget = LimitTarget.Group(GROUP_ID)
        val configuration = configuration(
            groupId = GROUP_ID,
            includeSecondApp = true,
            rules = listOf(
                rule(appTarget, LimitRuleType.MINUTES, 1),
                rule(groupTarget, LimitRuleType.MINUTES, 2),
            ),
        )
        val blocked = evaluator.evaluate(
            MONDAY,
            APP_A,
            configuration,
            counters(millisA = 60_000L, millisB = 60_000L),
        )

        val grants = evaluator.applyGrantDeltas(
            emptyMap(),
            evaluator.frictionGrantDeltas(
                blocked.breaches,
                EnforcementEvaluator.GrantVariant.LARGE,
            ),
        )

        assertEquals(5, grants.getValue(appTarget).grantedMinutes)
        assertEquals(5, grants.getValue(groupTarget).grantedMinutes)
        assertEquals(2, grants.size)
    }

    @Test
    fun timeWindowUsesInclusiveStartExclusiveEndAndFindsNextRelease() {
        val configuration = configuration(
            rules = listOf(
                rule(
                    target = LimitTarget.App(APP_A),
                    type = LimitRuleType.TIME_WINDOW,
                    value = 9 * 60,
                    endMinute = 10 * 60,
                ),
            ),
        )

        val before = evaluator.evaluate(
            Instant.parse("2026-07-06T08:59:00Z"),
            APP_A,
            configuration,
            emptyList(),
        )
        val atStart = evaluator.evaluate(
            Instant.parse("2026-07-06T09:00:00Z"),
            APP_A,
            configuration,
            emptyList(),
        )
        val beforeEnd = evaluator.evaluate(
            Instant.parse("2026-07-06T09:59:59Z"),
            APP_A,
            configuration,
            emptyList(),
        )
        val atEnd = evaluator.evaluate(
            Instant.parse("2026-07-06T10:00:00Z"),
            APP_A,
            configuration,
            emptyList(),
        )

        assertEquals(Instant.parse("2026-07-06T09:00:00Z"), before.hardBlock?.nextAllowedAt)
        assertNull(atStart.hardBlock)
        assertNull(beforeEnd.hardBlock)
        assertEquals(Instant.parse("2026-07-07T09:00:00Z"), atEnd.hardBlock?.nextAllowedAt)
    }

    @Test
    fun weekdayChoosesTheMatchingPlan() {
        val weekdayPlan = UsagePlan(PLAN_ID, "Mo-Fr", DayOfWeek.entries.take(5).toSet())
        val weekendPlan = UsagePlan(
            WEEKEND_PLAN_ID,
            "Sa-So",
            setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
        )
        val target = LimitTarget.App(APP_A)
        val configuration = configuration(
            plans = listOf(weekdayPlan, weekendPlan),
            rules = listOf(
                rule(target, LimitRuleType.OPENINGS, 2, planId = PLAN_ID),
                rule(target, LimitRuleType.OPENINGS, 5, planId = WEEKEND_PLAN_ID),
            ),
        )

        val monday = evaluator.evaluate(
            MONDAY,
            APP_A,
            configuration,
            counters(openingsA = 3, date = LocalDate.parse("2026-07-06")),
        )
        val saturday = evaluator.evaluate(
            Instant.parse("2026-07-11T10:00:00Z"),
            APP_A,
            configuration,
            counters(openingsA = 3, date = LocalDate.parse("2026-07-11")),
        )

        assertEquals(PLAN_ID, monday.activePlanId)
        assertTrue(monday.requiresFriction)
        assertEquals(WEEKEND_PLAN_ID, saturday.activePlanId)
        assertFalse(saturday.requiresFriction)
    }

    private fun configuration(
        groupId: Long? = null,
        includeSecondApp: Boolean = false,
        plans: List<UsagePlan> = listOf(
            UsagePlan(PLAN_ID, "Alle Tage", DayOfWeek.entries.toSet()),
        ),
        rules: List<LimitRule>,
    ): ConfigurationSnapshot = ConfigurationSnapshot(
        apps = buildList {
            add(MonitoredApp(APP_A, "App A", null, groupId))
            if (includeSecondApp) add(MonitoredApp(APP_B, "App B", null, groupId))
        },
        plans = plans,
        limitRules = rules,
    )

    private fun rule(
        target: LimitTarget,
        type: LimitRuleType,
        value: Int,
        endMinute: Int? = null,
        planId: Long = PLAN_ID,
    ) = LimitRule(
        id = value.toLong() * 10L + type.ordinal,
        planId = planId,
        target = target,
        type = type,
        value = value,
        endMinute = endMinute,
    )

    private fun counters(
        openingsA: Int = 0,
        millisA: Long = 0L,
        millisB: Long = 0L,
        date: LocalDate = LocalDate.parse("2026-07-06"),
    ): List<UsageEngine.DailyCounterSnapshot> = listOf(
        UsageEngine.DailyCounterSnapshot(date, APP_A, openingsA, millisA),
        UsageEngine.DailyCounterSnapshot(date, APP_B, 0, millisB),
    )

    private companion object {
        const val APP_A = "example.a"
        const val APP_B = "example.b"
        const val GROUP_ID = 42L
        const val PLAN_ID = 1L
        const val WEEKEND_PLAN_ID = 2L
        val MONDAY: Instant = Instant.parse("2026-07-06T10:00:00Z")
    }
}
