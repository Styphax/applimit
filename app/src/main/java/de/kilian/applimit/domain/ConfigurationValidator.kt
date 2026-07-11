package de.kilian.applimit.domain

import de.kilian.applimit.data.config.GroupAssignment
import de.kilian.applimit.data.config.LimitRuleInput
import de.kilian.applimit.data.config.LimitRuleType
import de.kilian.applimit.data.config.LimitTarget
import de.kilian.applimit.data.config.MonitoredAppInput
import de.kilian.applimit.data.config.PlanInput
import java.time.DayOfWeek
import java.util.Locale

enum class ConfigurationValidationCode {
    BLANK_NAME,
    DUPLICATE_NAME,
    DUPLICATE_PLAN_ID,
    MISSING_WEEKDAY,
    OVERLAPPING_WEEKDAY,
    INVALID_PACKAGE_NAME,
    INVALID_APP_LABEL,
    DUPLICATE_APP_SELECTION,
    INVALID_GROUP_ID,
    DUPLICATE_APP_ASSIGNMENT,
    INVALID_RULE_ID,
    INVALID_PLAN_ID,
    INVALID_LIMIT_VALUE,
    INVALID_TIME_WINDOW,
    UNEXPECTED_END_MINUTE,
}

data class ConfigurationValidationIssue(
    val code: ConfigurationValidationCode,
    val message: String,
    val weekday: DayOfWeek? = null,
)

data class ConfigurationValidationResult(
    val issues: List<ConfigurationValidationIssue>,
) {
    val isValid: Boolean
        get() = issues.isEmpty()

    fun requireValid() {
        require(isValid) { issues.joinToString(separator = "; ") { it.message } }
    }
}

data class WeekdayPartitionValidation(
    val missingWeekdays: Set<DayOfWeek>,
    val overlappingWeekdays: Set<DayOfWeek>,
    val issues: List<ConfigurationValidationIssue>,
) {
    val isValid: Boolean
        get() = issues.isEmpty()

    fun requireValid() {
        require(isValid) { issues.joinToString(separator = "; ") { it.message } }
    }
}

/** Pure validation shared by persistence and Compose UI. */
object ConfigurationValidator {
    fun validateMonitoredApps(
        apps: List<MonitoredAppInput>,
    ): ConfigurationValidationResult {
        val issues = mutableListOf<ConfigurationValidationIssue>()
        apps.filter { it.packageName.isBlank() }.forEach {
            issues += ConfigurationValidationIssue(
                code = ConfigurationValidationCode.INVALID_PACKAGE_NAME,
                message = "Package name must not be blank",
            )
        }
        apps.filter { it.label.isBlank() }.forEach {
            issues += ConfigurationValidationIssue(
                code = ConfigurationValidationCode.INVALID_APP_LABEL,
                message = "App label must not be blank",
            )
        }
        apps
            .groupingBy { it.packageName }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .forEach { packageName ->
                issues += ConfigurationValidationIssue(
                    code = ConfigurationValidationCode.DUPLICATE_APP_SELECTION,
                    message = "App may be selected only once: $packageName",
                )
            }
        return ConfigurationValidationResult(issues)
    }

    fun validateWeekdayPartition(plans: List<PlanInput>): WeekdayPartitionValidation {
        val issues = mutableListOf<ConfigurationValidationIssue>()

        plans.filter { it.name.isBlank() }.forEach {
            issues += ConfigurationValidationIssue(
                code = ConfigurationValidationCode.BLANK_NAME,
                message = "Plan names must not be blank",
            )
        }

        plans
            .groupBy { it.name.trim().lowercase(Locale.ROOT) }
            .filterKeys(String::isNotEmpty)
            .filterValues { it.size > 1 }
            .keys
            .forEach { duplicateName ->
                issues += ConfigurationValidationIssue(
                    code = ConfigurationValidationCode.DUPLICATE_NAME,
                    message = "Plan name is used more than once: $duplicateName",
                )
            }

        plans.mapNotNull(PlanInput::id)
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .forEach { duplicateId ->
                issues += ConfigurationValidationIssue(
                    code = ConfigurationValidationCode.DUPLICATE_PLAN_ID,
                    message = "Plan id is used more than once: $duplicateId",
                )
            }

        plans.mapNotNull(PlanInput::id)
            .filter { it <= 0L }
            .forEach { invalidId ->
                issues += ConfigurationValidationIssue(
                    code = ConfigurationValidationCode.INVALID_PLAN_ID,
                    message = "Plan id must be positive: $invalidId",
                )
            }

        val membershipCounts = DayOfWeek.entries.associateWith { day ->
            plans.count { day in it.weekdays }
        }
        val missing = membershipCounts.filterValues { it == 0 }.keys
        val overlapping = membershipCounts.filterValues { it > 1 }.keys

        missing.forEach { day ->
            issues += ConfigurationValidationIssue(
                code = ConfigurationValidationCode.MISSING_WEEKDAY,
                message = "Weekday is not assigned to a plan: $day",
                weekday = day,
            )
        }
        overlapping.forEach { day ->
            issues += ConfigurationValidationIssue(
                code = ConfigurationValidationCode.OVERLAPPING_WEEKDAY,
                message = "Weekday is assigned to more than one plan: $day",
                weekday = day,
            )
        }

        return WeekdayPartitionValidation(
            missingWeekdays = missing,
            overlappingWeekdays = overlapping,
            issues = issues,
        )
    }

    fun validateGroupAssignments(
        assignments: List<GroupAssignment>,
    ): ConfigurationValidationResult {
        val issues = mutableListOf<ConfigurationValidationIssue>()
        assignments.filter { it.packageName.isBlank() }.forEach {
            issues += ConfigurationValidationIssue(
                code = ConfigurationValidationCode.INVALID_PACKAGE_NAME,
                message = "Package name must not be blank",
            )
        }
        assignments.filter { it.groupId != null && it.groupId <= 0L }.forEach {
            issues += ConfigurationValidationIssue(
                code = ConfigurationValidationCode.INVALID_GROUP_ID,
                message = "Group id must be positive",
            )
        }
        assignments
            .groupingBy { it.packageName }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .forEach { packageName ->
                issues += ConfigurationValidationIssue(
                    code = ConfigurationValidationCode.DUPLICATE_APP_ASSIGNMENT,
                    message = "App may belong to at most one group: $packageName",
                )
            }
        return ConfigurationValidationResult(issues)
    }

    fun validateLimitRule(input: LimitRuleInput): ConfigurationValidationResult {
        val issues = mutableListOf<ConfigurationValidationIssue>()
        if (input.id != null && input.id <= 0L) {
            issues += ConfigurationValidationIssue(
                code = ConfigurationValidationCode.INVALID_RULE_ID,
                message = "Rule id must be positive",
            )
        }
        if (input.planId <= 0L) {
            issues += ConfigurationValidationIssue(
                code = ConfigurationValidationCode.INVALID_PLAN_ID,
                message = "Plan id must be positive",
            )
        }
        when (val target = input.target) {
            is LimitTarget.App -> if (target.packageName.isBlank()) {
                issues += ConfigurationValidationIssue(
                    code = ConfigurationValidationCode.INVALID_PACKAGE_NAME,
                    message = "Target package name must not be blank",
                )
            }

            is LimitTarget.Group -> if (target.groupId <= 0L) {
                issues += ConfigurationValidationIssue(
                    code = ConfigurationValidationCode.INVALID_GROUP_ID,
                    message = "Target group id must be positive",
                )
            }
        }

        when (input.type) {
            LimitRuleType.OPENINGS -> {
                if (input.value <= 0) {
                    issues += invalidAmountIssue("Opening limit must be positive")
                }
                if (input.endMinute != null) {
                    issues += unexpectedEndMinuteIssue()
                }
            }

            LimitRuleType.MINUTES -> {
                if (input.value !in 1..MINUTES_PER_DAY) {
                    issues += invalidAmountIssue("Minute limit must be between 1 and 1440")
                }
                if (input.endMinute != null) {
                    issues += unexpectedEndMinuteIssue()
                }
            }

            LimitRuleType.TIME_WINDOW -> {
                val endMinute = input.endMinute
                if (
                    input.value !in 0 until MINUTES_PER_DAY ||
                    endMinute == null ||
                    endMinute !in 1..MINUTES_PER_DAY ||
                    input.value >= endMinute
                ) {
                    issues += ConfigurationValidationIssue(
                        code = ConfigurationValidationCode.INVALID_TIME_WINDOW,
                        message = "Allowed time window must satisfy 0 <= start < end <= 1440",
                    )
                }
            }
        }
        return ConfigurationValidationResult(issues)
    }

    private fun invalidAmountIssue(message: String) = ConfigurationValidationIssue(
        code = ConfigurationValidationCode.INVALID_LIMIT_VALUE,
        message = message,
    )

    private fun unexpectedEndMinuteIssue() = ConfigurationValidationIssue(
        code = ConfigurationValidationCode.UNEXPECTED_END_MINUTE,
        message = "End minute is only valid for time-window rules",
    )

    private const val MINUTES_PER_DAY = 24 * 60
}
