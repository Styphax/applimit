package de.kilian.applimit.data.config

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ConfigDao {
    @Query("SELECT * FROM monitored_apps ORDER BY label COLLATE NOCASE, packageName")
    fun observeMonitoredApps(): Flow<List<MonitoredAppEntity>>

    @Query("SELECT * FROM app_groups ORDER BY name COLLATE NOCASE, id")
    fun observeGroups(): Flow<List<AppGroupEntity>>

    @Query("SELECT * FROM plans ORDER BY id")
    fun observePlans(): Flow<List<PlanEntity>>

    @Query("SELECT * FROM limit_rules ORDER BY planId, targetType, targetId, ruleType, id")
    fun observeLimitRules(): Flow<List<LimitRuleEntity>>

    @Query("SELECT * FROM monitored_apps")
    suspend fun getMonitoredApps(): List<MonitoredAppEntity>

    @Query("SELECT * FROM app_groups")
    suspend fun getGroups(): List<AppGroupEntity>

    @Query("SELECT * FROM plans")
    suspend fun getPlans(): List<PlanEntity>

    @Query("SELECT * FROM limit_rules")
    suspend fun getLimitRules(): List<LimitRuleEntity>

    @Query("SELECT * FROM pending_changes ORDER BY applyOnDate, createdAtEpochMillis, id")
    suspend fun getLegacyPendingChanges(): List<LegacyPendingChangeEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM monitored_apps WHERE packageName = :packageName)")
    suspend fun monitoredAppExists(packageName: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM app_groups WHERE id = :groupId)")
    suspend fun groupExists(groupId: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM plans WHERE id = :planId)")
    suspend fun planExists(planId: Long): Boolean

    @Upsert
    suspend fun upsertMonitoredApps(apps: List<MonitoredAppEntity>)

    @Query("DELETE FROM monitored_apps WHERE packageName = :packageName")
    suspend fun deleteMonitoredApp(packageName: String)

    @Insert
    suspend fun insertGroup(group: AppGroupEntity): Long

    @Query("UPDATE app_groups SET name = :name WHERE id = :groupId")
    suspend fun updateGroupName(groupId: Long, name: String): Int

    @Query("DELETE FROM app_groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: Long): Int

    @Query("UPDATE monitored_apps SET groupId = :groupId WHERE packageName = :packageName")
    suspend fun updateAppGroup(packageName: String, groupId: Long?): Int

    @Insert
    suspend fun insertPlan(plan: PlanEntity): Long

    @Update
    suspend fun updatePlan(plan: PlanEntity): Int

    @Query("DELETE FROM plans WHERE id = :planId")
    suspend fun deletePlan(planId: Long): Int

    @Query("SELECT * FROM limit_rules WHERE id = :ruleId")
    suspend fun getLimitRule(ruleId: Long): LimitRuleEntity?

    @Query(
        """
        SELECT * FROM limit_rules
        WHERE planId = :planId
          AND targetType = :targetType
          AND targetId = :targetId
        """,
    )
    suspend fun getLimitRulesForTarget(
        planId: Long,
        targetType: String,
        targetId: String,
    ): List<LimitRuleEntity>

    @Query(
        """
        SELECT * FROM limit_rules
        WHERE planId = :planId
          AND targetType = :targetType
          AND targetId = :targetId
          AND ruleType = :ruleType
        """,
    )
    suspend fun getLimitRule(
        planId: Long,
        targetType: String,
        targetId: String,
        ruleType: String,
    ): LimitRuleEntity?

    @Insert
    suspend fun insertLimitRule(rule: LimitRuleEntity): Long

    @Update
    suspend fun updateLimitRule(rule: LimitRuleEntity): Int

    @Query("DELETE FROM limit_rules WHERE id = :ruleId")
    suspend fun deleteLimitRule(ruleId: Long): Int

    @Query("DELETE FROM limit_rules WHERE targetType = :targetType AND targetId = :targetId")
    suspend fun deleteLimitRulesForTarget(targetType: String, targetId: String)

    @Query("DELETE FROM pending_changes")
    suspend fun deleteAllLegacyPendingChanges(): Int
}
