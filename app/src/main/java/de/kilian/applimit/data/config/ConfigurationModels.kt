package de.kilian.applimit.data.config

import java.time.DayOfWeek

data class ConfigurationSnapshot(
    val apps: List<MonitoredApp> = emptyList(),
    val groups: List<AppGroup> = emptyList(),
    val plans: List<UsagePlan> = emptyList(),
    val limitRules: List<LimitRule> = emptyList(),
)

data class MonitoredApp(
    val packageName: String,
    val label: String,
    val iconReference: String?,
    val groupId: Long?,
)

data class MonitoredAppInput(
    val packageName: String,
    val label: String,
    val iconReference: String? = null,
)

data class AppGroup(
    val id: Long,
    val name: String,
)

data class UsagePlan(
    val id: Long,
    val name: String,
    val weekdays: Set<DayOfWeek>,
)

data class PlanInput(
    val id: Long? = null,
    val name: String,
    val weekdays: Set<DayOfWeek>,
)

data class GroupAssignment(
    val packageName: String,
    val groupId: Long?,
)

sealed interface LimitTarget {
    data class App(val packageName: String) : LimitTarget

    data class Group(val groupId: Long) : LimitTarget
}

enum class LimitRuleType {
    OPENINGS,
    MINUTES,
    TIME_WINDOW,
}

data class LimitRule(
    val id: Long,
    val planId: Long,
    val target: LimitTarget,
    val type: LimitRuleType,
    /**
     * OPENINGS/MINUTES: the daily amount. TIME_WINDOW: inclusive start minute of day.
     */
    val value: Int,
    /** Exclusive end minute of day for TIME_WINDOW, otherwise null. */
    val endMinute: Int?,
)

data class LimitRuleInput(
    val id: Long? = null,
    val planId: Long,
    val target: LimitTarget,
    val type: LimitRuleType,
    val value: Int,
    val endMinute: Int? = null,
)
