package de.kilian.applimit.ui.config

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kilian.applimit.data.config.ConfigRepository
import de.kilian.applimit.data.config.ConfigurationSnapshot
import de.kilian.applimit.data.config.LimitRuleInput
import de.kilian.applimit.data.config.LimitRuleType
import de.kilian.applimit.data.config.LimitTarget
import de.kilian.applimit.data.config.MonitoredAppInput
import de.kilian.applimit.data.config.PlanInput
import de.kilian.applimit.ui.dashboard.DashboardHost
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Composable
fun ConfigurationHost(
    onOpenOnboarding: () -> Unit,
    onOpenDetectionDebug: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context) { ConfigRepository.get(context.applicationContext) }
    val snapshot by remember(repository) { repository.observeConfiguration() }
        .collectAsStateWithLifecycle(initialValue = ConfigurationSnapshot())
    val scope = rememberCoroutineScope()
    val writeMutex = remember { Mutex() }
    var launcherApps by remember { mutableStateOf<List<LauncherAppUi>>(emptyList()) }
    var launcherAppsLoading by remember { mutableStateOf(true) }
    var launcherAppsError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(context) {
        launcherAppsLoading = true
        launcherAppsError = null
        runCatching { loadLauncherApps(context.applicationContext) }
            .onSuccess { launcherApps = it }
            .onFailure {
                launcherAppsError = "Launcher-Apps konnten nicht geladen werden."
            }
        launcherAppsLoading = false
    }

    fun persist(change: suspend () -> Unit) {
        scope.launch {
            runCatching {
                writeMutex.withLock { change() }
            }.onFailure { throwable ->
                Toast.makeText(
                    context,
                    "Änderung konnte nicht gespeichert werden: ${throwable.message ?: "unbekannter Fehler"}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    AppLimitMainScreen(
        state = snapshot.toUiState(),
        launcherApps = launcherApps,
        launcherAppsLoading = launcherAppsLoading,
        launcherAppsError = launcherAppsError,
        actions = ConfigurationUiActions(
            replaceMonitoredApps = { apps ->
                persist {
                    repository.replaceMonitoredApps(
                        apps.map { app ->
                            MonitoredAppInput(
                                packageName = app.packageName,
                                label = app.label,
                                iconReference = null,
                            )
                        },
                    )
                }
            },
            createGroup = { name -> persist { repository.createGroup(name) } },
            renameGroup = { id, name -> persist { repository.renameGroup(id, name) } },
            deleteGroup = { id -> persist { repository.deleteGroup(id) } },
            assignAppToGroup = { packageName, groupId ->
                persist { repository.assignAppToGroup(packageName, groupId) }
            },
            replacePlans = { plans ->
                persist {
                    repository.replacePlans(
                        plans.map { plan ->
                            PlanInput(
                                id = plan.id,
                                name = plan.name,
                                weekdays = plan.weekdays,
                            )
                        },
                    )
                }
            },
            saveTargetLimits = { planId, target, desiredLimits ->
                persist {
                    val dataTarget = target.toDataTarget()
                    val desiredRules = buildList {
                        desiredLimits.openingsPerDay?.let { openings ->
                            add(
                                LimitRuleInput(
                                    planId = planId,
                                    target = dataTarget,
                                    type = LimitRuleType.OPENINGS,
                                    value = openings,
                                ),
                            )
                        }
                        desiredLimits.minutesPerDay?.let { minutes ->
                            add(
                                LimitRuleInput(
                                    planId = planId,
                                    target = dataTarget,
                                    type = LimitRuleType.MINUTES,
                                    value = minutes,
                                ),
                            )
                        }
                        desiredLimits.allowedTimeWindow?.let { window ->
                            add(
                                LimitRuleInput(
                                    planId = planId,
                                    target = dataTarget,
                                    type = LimitRuleType.TIME_WINDOW,
                                    value = window.startMinute,
                                    endMinute = window.endMinute,
                                ),
                            )
                        }
                    }
                    repository.replaceTargetLimitRules(planId, dataTarget, desiredRules)
                }
            },
        ),
        dashboardContent = { modifier ->
            DashboardHost(
                configuration = snapshot,
                knownAppLabels = launcherApps.associate { it.packageName to it.label },
                modifier = modifier,
            )
        },
        onOpenOnboarding = onOpenOnboarding,
        onOpenDetectionDebug = onOpenDetectionDebug,
    )
}

private fun ConfigurationSnapshot.toUiState() = ConfigurationUiState(
    monitoredApps = apps.map { app ->
        MonitoredAppUi(
            packageName = app.packageName,
            label = app.label,
            groupId = app.groupId,
        )
    },
    groups = groups.map { group -> AppGroupUi(id = group.id, name = group.name) },
    plans = plans.map { plan ->
        UsagePlanUi(
            id = plan.id,
            name = plan.name,
            weekdays = plan.weekdays,
        )
    },
    limitRules = limitRules.map { rule ->
        LimitRuleUi(
            id = rule.id,
            planId = rule.planId,
            target = rule.target.toUiTarget(),
            type = LimitRuleTypeUi.valueOf(rule.type.name),
            value = rule.value,
            endMinute = rule.endMinute,
        )
    },
)

private fun LimitTargetUi.toDataTarget(): LimitTarget = when (this) {
    is LimitTargetUi.App -> LimitTarget.App(packageName)
    is LimitTargetUi.Group -> LimitTarget.Group(groupId)
}

private fun LimitTarget.toUiTarget(): LimitTargetUi = when (this) {
    is LimitTarget.App -> LimitTargetUi.App(packageName)
    is LimitTarget.Group -> LimitTargetUi.Group(groupId)
}
