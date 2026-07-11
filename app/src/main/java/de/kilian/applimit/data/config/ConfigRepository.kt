package de.kilian.applimit.data.config

import android.content.Context
import androidx.room.withTransaction
import de.kilian.applimit.data.usage.AppLimitDatabase
import de.kilian.applimit.domain.ConfigurationValidator
import java.time.DayOfWeek
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class ConfigRepository private constructor(
    private val database: AppLimitDatabase,
) {
    private val dao = database.configDao()

    /** Active configuration is emitted after any one-time M5 transition has completed. */
    fun observeConfiguration(): Flow<ConfigurationSnapshot> = database.invalidationTracker
        .createFlow(
            "monitored_apps",
            "app_groups",
            "plans",
            "limit_rules",
            "pending_changes",
            emitInitialState = true,
        )
        .map { getConfiguration() }
        .distinctUntilChanged()

    suspend fun getConfiguration(): ConfigurationSnapshot = database.withTransaction {
        applyLegacyPendingChanges()
        readConfiguration()
    }

    suspend fun replaceMonitoredApps(apps: List<MonitoredAppInput>) {
        ConfigurationValidator.validateMonitoredApps(apps).requireValid()
        database.withTransaction {
            applyLegacyPendingChanges()
            val existingByPackage = dao.getMonitoredApps().associateBy { it.packageName }
            val desiredByPackage = apps.associateBy { it.packageName }

            apps.forEach { input ->
                val existing = existingByPackage[input.packageName]
                dao.upsertMonitoredApps(
                    listOf(
                        MonitoredAppEntity(
                            packageName = input.packageName,
                            label = input.label.trim(),
                            iconReference = input.iconReference,
                            groupId = existing?.groupId,
                        ),
                    ),
                )
            }
            existingByPackage.values
                .filterNot { it.packageName in desiredByPackage }
                .forEach { existing ->
                    dao.deleteLimitRulesForTarget(TARGET_APP, existing.packageName)
                    dao.deleteMonitoredApp(existing.packageName)
                }
        }
    }

    suspend fun createGroup(name: String): Long = database.withTransaction {
        applyLegacyPendingChanges()
        dao.insertGroup(AppGroupEntity(name = normalizedName(name, "Group")))
    }

    suspend fun renameGroup(groupId: Long, name: String) {
        require(groupId > 0L) { "Group id must be positive" }
        database.withTransaction {
            applyLegacyPendingChanges()
            check(dao.updateGroupName(groupId, normalizedName(name, "Group")) == 1) {
                "Unknown group id: $groupId"
            }
        }
    }

    suspend fun deleteGroup(groupId: Long) {
        require(groupId > 0L) { "Group id must be positive" }
        database.withTransaction {
            applyLegacyPendingChanges()
            require(dao.groupExists(groupId)) { "Unknown group id: $groupId" }
            dao.deleteLimitRulesForTarget(TARGET_GROUP, groupId.toString())
            check(dao.deleteGroup(groupId) == 1)
        }
    }

    suspend fun assignAppToGroup(packageName: String, groupId: Long?) {
        require(packageName.isNotBlank()) { "Package name must not be blank" }
        require(groupId == null || groupId > 0L) { "Group id must be positive" }
        database.withTransaction {
            applyLegacyPendingChanges()
            require(dao.monitoredAppExists(packageName)) { "Unknown monitored app: $packageName" }
            require(groupId == null || dao.groupExists(groupId)) { "Unknown group id: $groupId" }
            check(dao.updateAppGroup(packageName, groupId) == 1)
        }
    }

    suspend fun replacePlans(plans: List<PlanInput>): List<UsagePlan> {
        ConfigurationValidator.validateWeekdayPartition(plans).requireValid()
        return database.withTransaction {
            applyLegacyPendingChanges()
            applyPlanReplacement(plans)
        }
    }

    suspend fun upsertLimitRule(input: LimitRuleInput): Long {
        ConfigurationValidator.validateLimitRule(input).requireValid()
        return database.withTransaction {
            applyLegacyPendingChanges()
            validateRuleReferences(input)
            applyRule(input)
        }
    }

    suspend fun replaceTargetLimitRules(
        planId: Long,
        target: LimitTarget,
        desiredRules: List<LimitRuleInput>,
    ) {
        require(desiredRules.all { it.id == null }) {
            "Replacement rules must use their natural key, not an id"
        }
        require(desiredRules.all { it.planId == planId && it.target == target }) {
            "Every replacement rule must belong to the requested plan and target"
        }
        require(desiredRules.map { it.type }.distinct().size == desiredRules.size) {
            "Each limit rule type may appear only once"
        }
        desiredRules.forEach { ConfigurationValidator.validateLimitRule(it).requireValid() }

        database.withTransaction {
            applyLegacyPendingChanges()
            require(dao.planExists(planId)) { "Unknown plan id: $planId" }
            validateTarget(target)
            val (targetType, targetId) = target.toStorageTarget()
            val existingByType = dao.getLimitRulesForTarget(planId, targetType, targetId)
                .associateBy { LimitRuleType.valueOf(it.ruleType) }
            val desiredByType = desiredRules.associateBy(LimitRuleInput::type)

            LimitRuleType.entries.forEach { type ->
                val existing = existingByType[type]
                val desired = desiredByType[type]
                when {
                    desired == null && existing != null -> dao.deleteLimitRule(existing.id)
                    desired != null && (
                        existing == null ||
                            existing.value != desired.value ||
                            existing.endMinute != desired.endMinute
                        ) -> applyRule(desired)
                }
            }
        }
    }

    suspend fun deleteLimitRule(ruleId: Long) {
        require(ruleId > 0L) { "Rule id must be positive" }
        database.withTransaction {
            applyLegacyPendingChanges()
            val entity = dao.getLimitRule(ruleId) ?: error("Unknown rule id: $ruleId")
            check(dao.deleteLimitRule(entity.id) == 1)
        }
    }

    private suspend fun readConfiguration() = ConfigurationSnapshot(
        apps = dao.getMonitoredApps().map(MonitoredAppEntity::toModel),
        groups = dao.getGroups().map(AppGroupEntity::toModel),
        plans = dao.getPlans().map(PlanEntity::toModel),
        limitRules = dao.getLimitRules().map(LimitRuleEntity::toModel),
    )

    /**
     * M5 may have left intended edits in the former queue. Apply every row, including
     * future-dated rows, once and clear the table only after the whole transaction succeeds.
     */
    private suspend fun applyLegacyPendingChanges() {
        val changes = dao.getLegacyPendingChanges()
            .map(LegacyPendingChangeEntity::toLegacyChange)
            .sortedWith(
                compareBy<LegacyPendingChange> { it.kind.applyPriority() }
                    .thenBy(LegacyPendingChange::createdAtEpochMillis)
                    .thenBy(LegacyPendingChange::id),
            )
        changes.forEach { change -> applyLegacyPendingOperation(change.operation) }
        if (changes.isNotEmpty()) dao.deleteAllLegacyPendingChanges()
    }

    private suspend fun applyLegacyPendingOperation(operation: LegacyPendingOperation) {
        when (operation) {
            is LegacyPendingOperation.RemoveApp -> {
                dao.deleteLimitRulesForTarget(TARGET_APP, operation.packageName)
                dao.deleteMonitoredApp(operation.packageName)
            }
            is LegacyPendingOperation.DeleteGroup -> {
                dao.deleteLimitRulesForTarget(TARGET_GROUP, operation.groupId.toString())
                dao.deleteGroup(operation.groupId)
            }
            is LegacyPendingOperation.AssignAppGroup -> {
                if (dao.monitoredAppExists(operation.packageName) &&
                    (operation.groupId == null || dao.groupExists(operation.groupId))
                ) {
                    dao.updateAppGroup(operation.packageName, operation.groupId)
                }
            }
            is LegacyPendingOperation.ReplacePlans -> {
                ConfigurationValidator.validateWeekdayPartition(operation.plans).requireValid()
                applyPlanReplacement(operation.plans)
            }
            is LegacyPendingOperation.UpsertLimitRule -> {
                val rule = operation.rule
                if (dao.planExists(rule.planId) && targetExists(rule.target)) applyRule(rule)
            }
            is LegacyPendingOperation.DeleteLimitRule -> {
                val (targetType, targetId) = operation.target.toStorageTarget()
                dao.getLimitRule(
                    operation.planId,
                    targetType,
                    targetId,
                    operation.type.name,
                )?.let { dao.deleteLimitRule(it.id) }
            }
        }
    }

    private suspend fun applyPlanReplacement(plans: List<PlanInput>): List<UsagePlan> {
        val existingById = dao.getPlans().associateBy(PlanEntity::id)
        val retainedIds = plans.mapNotNullTo(mutableSetOf(), PlanInput::id)
        require(retainedIds.all(existingById::containsKey)) { "A plan id does not exist" }
        existingById.keys.filterNot(retainedIds::contains).forEach { dao.deletePlan(it) }
        return plans.map { input ->
            val name = normalizedName(input.name, "Plan")
            val mask = input.weekdays.toWeekdayMask()
            val id = input.id
            if (id == null) {
                val generated = dao.insertPlan(PlanEntity(name = name, weekdayMask = mask))
                UsagePlan(generated, name, input.weekdays)
            } else {
                check(dao.updatePlan(PlanEntity(id, name, mask)) == 1)
                UsagePlan(id, name, input.weekdays)
            }
        }
    }

    private suspend fun applyRule(input: LimitRuleInput): Long {
        val existing = findRule(input)
        val (targetType, targetId) = input.target.toStorageTarget()
        val entity = LimitRuleEntity(
            id = existing?.id ?: 0L,
            planId = input.planId,
            targetType = targetType,
            targetId = targetId,
            ruleType = input.type.name,
            value = input.value,
            endMinute = input.endMinute,
        )
        return if (existing == null) dao.insertLimitRule(entity) else {
            check(dao.updateLimitRule(entity) == 1)
            existing.id
        }
    }

    private suspend fun findRule(input: LimitRuleInput): LimitRuleEntity? {
        val (targetType, targetId) = input.target.toStorageTarget()
        return dao.getLimitRule(input.planId, targetType, targetId, input.type.name)
    }

    private suspend fun validateRuleReferences(input: LimitRuleInput) {
        require(dao.planExists(input.planId)) { "Unknown plan id: ${input.planId}" }
        validateTarget(input.target)
    }

    private suspend fun validateTarget(target: LimitTarget) {
        require(targetExists(target)) { "Unknown limit target: $target" }
    }

    private suspend fun targetExists(target: LimitTarget): Boolean = when (target) {
        is LimitTarget.App -> dao.monitoredAppExists(target.packageName)
        is LimitTarget.Group -> dao.groupExists(target.groupId)
    }

    private suspend fun targetLabel(target: LimitTarget): String = when (target) {
        is LimitTarget.App -> dao.getMonitoredApps()
            .firstOrNull { it.packageName == target.packageName }?.label ?: target.packageName
        is LimitTarget.Group -> dao.getGroups()
            .firstOrNull { it.id == target.groupId }?.name ?: "Gruppe ${target.groupId}"
    }

    companion object {
        @Volatile
        private var instance: ConfigRepository? = null

        fun get(context: Context): ConfigRepository = instance ?: synchronized(this) {
            instance ?: ConfigRepository(AppLimitDatabase.get(context)).also { instance = it }
        }

        private const val TARGET_APP = "APP"
        private const val TARGET_GROUP = "GROUP"
    }
}

private fun LegacyPendingChangeKind.applyPriority(): Int = when (this) {
    LegacyPendingChangeKind.REPLACE_PLANS -> 0
    LegacyPendingChangeKind.UPSERT_LIMIT_RULE, LegacyPendingChangeKind.DELETE_LIMIT_RULE -> 1
    LegacyPendingChangeKind.ASSIGN_APP_GROUP -> 2
    LegacyPendingChangeKind.REMOVE_APP, LegacyPendingChangeKind.DELETE_GROUP -> 3
}

private fun LegacyPendingChangeEntity.toLegacyChange() = LegacyPendingChange(
    id = id,
    kind = LegacyPendingChangeKind.valueOf(kind),
    operation = LegacyPendingOperationCodec.decode(LegacyPendingChangeKind.valueOf(kind), payload),
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun normalizedName(name: String, kind: String): String = name.trim().also {
    require(it.isNotEmpty()) { "$kind name must not be blank" }
}

private fun MonitoredAppEntity.toModel() = MonitoredApp(packageName, label, iconReference, groupId)
private fun AppGroupEntity.toModel() = AppGroup(id, name)
private fun PlanEntity.toModel() = UsagePlan(id, name, weekdayMask.toWeekdays())
private fun LimitRuleEntity.toModel() = LimitRule(
    id = id,
    planId = planId,
    target = when (targetType) {
        "APP" -> LimitTarget.App(targetId)
        "GROUP" -> LimitTarget.Group(targetId.toLong())
        else -> error("Unknown target type: $targetType")
    },
    type = LimitRuleType.valueOf(ruleType),
    value = value,
    endMinute = endMinute,
)

private fun LimitTarget.toStorageTarget(): Pair<String, String> = when (this) {
    is LimitTarget.App -> "APP" to packageName
    is LimitTarget.Group -> "GROUP" to groupId.toString()
}

private fun Set<DayOfWeek>.toWeekdayMask(): Int = fold(0) { mask, day ->
    mask or (1 shl (day.value - 1))
}
private fun Int.toWeekdays(): Set<DayOfWeek> = DayOfWeek.entries.filterTo(linkedSetOf()) { day ->
    this and (1 shl (day.value - 1)) != 0
}
