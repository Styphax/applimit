package de.kilian.applimit.domain

import de.kilian.applimit.data.config.GroupAssignment
import de.kilian.applimit.data.config.LimitRuleInput
import de.kilian.applimit.data.config.LimitRuleType
import de.kilian.applimit.data.config.LimitTarget
import de.kilian.applimit.data.config.MonitoredAppInput
import de.kilian.applimit.data.config.PlanInput
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationValidatorTest {
    @Test
    fun monitoredAppsRequireUniquePackagesAndLabels() {
        val validation = ConfigurationValidator.validateMonitoredApps(
            listOf(
                MonitoredAppInput(packageName = "example.app", label = "Example"),
                MonitoredAppInput(packageName = "example.app", label = ""),
            ),
        )

        assertTrue(
            validation.issues.any {
                it.code == ConfigurationValidationCode.DUPLICATE_APP_SELECTION
            },
        )
        assertTrue(
            validation.issues.any { it.code == ConfigurationValidationCode.INVALID_APP_LABEL },
        )
    }

    @Test
    fun weekdayPartitionAcceptsWeekdaysAndWeekendPlans() {
        val validation = ConfigurationValidator.validateWeekdayPartition(
            listOf(
                PlanInput(name = "Mo-Fr", weekdays = DayOfWeek.entries.take(5).toSet()),
                PlanInput(name = "Sa-So", weekdays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)),
            ),
        )

        assertTrue(validation.isValid)
        assertTrue(validation.missingWeekdays.isEmpty())
        assertTrue(validation.overlappingWeekdays.isEmpty())
    }

    @Test
    fun weekdayPartitionReportsEveryMissingDay() {
        val validation = ConfigurationValidator.validateWeekdayPartition(
            listOf(PlanInput(name = "Werktage", weekdays = DayOfWeek.entries.take(5).toSet())),
        )

        assertFalse(validation.isValid)
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), validation.missingWeekdays)
        assertEquals(
            2,
            validation.issues.count { it.code == ConfigurationValidationCode.MISSING_WEEKDAY },
        )
    }

    @Test
    fun weekdayPartitionReportsOverlap() {
        val validation = ConfigurationValidator.validateWeekdayPartition(
            listOf(
                PlanInput(name = "A", weekdays = DayOfWeek.entries.toSet()),
                PlanInput(name = "B", weekdays = setOf(DayOfWeek.MONDAY)),
            ),
        )

        assertFalse(validation.isValid)
        assertEquals(setOf(DayOfWeek.MONDAY), validation.overlappingWeekdays)
    }

    @Test
    fun weekdayPartitionRejectsDuplicateNamesCaseInsensitively() {
        val validation = ConfigurationValidator.validateWeekdayPartition(
            listOf(
                PlanInput(name = "Alltag", weekdays = DayOfWeek.entries.take(5).toSet()),
                PlanInput(name = " ALLTAG ", weekdays = DayOfWeek.entries.drop(5).toSet()),
            ),
        )

        assertTrue(validation.issues.any { it.code == ConfigurationValidationCode.DUPLICATE_NAME })
    }

    @Test
    fun weekdayPartitionRejectsDuplicatePersistentPlanIds() {
        val validation = ConfigurationValidator.validateWeekdayPartition(
            listOf(
                PlanInput(id = 7L, name = "A", weekdays = DayOfWeek.entries.take(5).toSet()),
                PlanInput(id = 7L, name = "B", weekdays = DayOfWeek.entries.drop(5).toSet()),
            ),
        )

        assertTrue(
            validation.issues.any { it.code == ConfigurationValidationCode.DUPLICATE_PLAN_ID },
        )
    }

    @Test
    fun groupAssignmentsRejectAnAppAppearingTwice() {
        val validation = ConfigurationValidator.validateGroupAssignments(
            listOf(
                GroupAssignment("example.social", 1L),
                GroupAssignment("example.social", 2L),
            ),
        )

        assertFalse(validation.isValid)
        assertTrue(
            validation.issues.any {
                it.code == ConfigurationValidationCode.DUPLICATE_APP_ASSIGNMENT
            },
        )
    }

    @Test
    fun groupAssignmentsAcceptOneGroupPerApp() {
        val validation = ConfigurationValidator.validateGroupAssignments(
            listOf(
                GroupAssignment("example.social", 1L),
                GroupAssignment("example.mail", null),
            ),
        )

        assertTrue(validation.isValid)
    }

    @Test
    fun openingLimitMustBePositive() {
        assertTrue(ConfigurationValidator.validateLimitRule(rule(LimitRuleType.OPENINGS, 1)).isValid)
        assertInvalidValue(rule(LimitRuleType.OPENINGS, 0))
        assertInvalidValue(rule(LimitRuleType.OPENINGS, -1))
    }

    @Test
    fun minuteLimitMustFitInOneDay() {
        assertTrue(ConfigurationValidator.validateLimitRule(rule(LimitRuleType.MINUTES, 1)).isValid)
        assertTrue(ConfigurationValidator.validateLimitRule(rule(LimitRuleType.MINUTES, 1_440)).isValid)
        assertInvalidValue(rule(LimitRuleType.MINUTES, 0))
        assertInvalidValue(rule(LimitRuleType.MINUTES, 1_441))
    }

    @Test
    fun timeWindowRequiresOrderedBoundsWithinDay() {
        assertTrue(
            ConfigurationValidator.validateLimitRule(
                rule(LimitRuleType.TIME_WINDOW, value = 9 * 60, endMinute = 22 * 60),
            ).isValid,
        )
        listOf(
            rule(LimitRuleType.TIME_WINDOW, value = -1, endMinute = 100),
            rule(LimitRuleType.TIME_WINDOW, value = 100, endMinute = null),
            rule(LimitRuleType.TIME_WINDOW, value = 100, endMinute = 100),
            rule(LimitRuleType.TIME_WINDOW, value = 1_439, endMinute = 1_441),
        ).forEach { invalidRule ->
            assertTrue(
                ConfigurationValidator.validateLimitRule(invalidRule).issues.any {
                    it.code == ConfigurationValidationCode.INVALID_TIME_WINDOW
                },
            )
        }
    }

    @Test
    fun amountRulesRejectTimeWindowEndMinute() {
        val validation = ConfigurationValidator.validateLimitRule(
            rule(LimitRuleType.MINUTES, value = 10, endMinute = 20),
        )

        assertTrue(
            validation.issues.any {
                it.code == ConfigurationValidationCode.UNEXPECTED_END_MINUTE
            },
        )
    }

    private fun rule(
        type: LimitRuleType,
        value: Int,
        endMinute: Int? = null,
    ) = LimitRuleInput(
        planId = 1L,
        target = LimitTarget.App("example.app"),
        type = type,
        value = value,
        endMinute = endMinute,
    )

    private fun assertInvalidValue(rule: LimitRuleInput) {
        assertTrue(
            ConfigurationValidator.validateLimitRule(rule).issues.any {
                it.code == ConfigurationValidationCode.INVALID_LIMIT_VALUE
            },
        )
    }
}
