package de.kilian.applimit.ui.config

import android.graphics.drawable.Drawable
import java.time.DayOfWeek

data class ConfigurationUiState(
    val monitoredApps: List<MonitoredAppUi> = emptyList(),
    val groups: List<AppGroupUi> = emptyList(),
    val plans: List<UsagePlanUi> = emptyList(),
    val limitRules: List<LimitRuleUi> = emptyList(),
)

data class LauncherAppUi(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
)

data class MonitoredAppUi(
    val packageName: String,
    val label: String,
    val groupId: Long?,
)

data class AppGroupUi(
    val id: Long,
    val name: String,
)

data class UsagePlanUi(
    val id: Long,
    val name: String,
    val weekdays: Set<DayOfWeek>,
)

sealed interface LimitTargetUi {
    data class App(val packageName: String) : LimitTargetUi

    data class Group(val groupId: Long) : LimitTargetUi
}

enum class LimitRuleTypeUi {
    OPENINGS,
    MINUTES,
    TIME_WINDOW,
}

data class LimitRuleUi(
    val id: Long,
    val planId: Long,
    val target: LimitTargetUi,
    val type: LimitRuleTypeUi,
    val value: Int,
    val endMinute: Int?,
)

data class MonitoredAppInputUi(
    val packageName: String,
    val label: String,
)

data class PlanInputUi(
    val id: Long?,
    val name: String,
    val weekdays: Set<DayOfWeek>,
)

data class TargetLimitsInputUi(
    val openingsPerDay: Int?,
    val minutesPerDay: Int?,
    val allowedTimeWindow: AllowedTimeWindowUi?,
)

data class AllowedTimeWindowUi(
    val startMinute: Int,
    val endMinute: Int,
)

data class ConfigurationUiActions(
    val replaceMonitoredApps: (List<MonitoredAppInputUi>) -> Unit,
    val createGroup: (String) -> Unit,
    val renameGroup: (Long, String) -> Unit,
    val deleteGroup: (Long) -> Unit,
    val assignAppToGroup: (String, Long?) -> Unit,
    val replacePlans: (List<PlanInputUi>) -> Unit,
    val saveTargetLimits: (Long, LimitTargetUi, TargetLimitsInputUi) -> Unit,
)
