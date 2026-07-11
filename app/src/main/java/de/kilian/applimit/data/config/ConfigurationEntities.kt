package de.kilian.applimit.data.config

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "app_groups",
    indices = [Index(value = ["name"], unique = true)],
)
data class AppGroupEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
)

@Entity(
    tableName = "monitored_apps",
    foreignKeys = [
        ForeignKey(
            entity = AppGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["groupId"])],
)
data class MonitoredAppEntity(
    @PrimaryKey
    val packageName: String,
    val label: String,
    val iconReference: String?,
    val groupId: Long?,
)

@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    /** ISO weekday bit mask: Monday is bit 0, Sunday is bit 6. */
    val weekdayMask: Int,
)

@Entity(
    tableName = "limit_rules",
    foreignKeys = [
        ForeignKey(
            entity = PlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["planId"]),
        Index(
            value = ["planId", "targetType", "targetId", "ruleType"],
            unique = true,
        ),
    ],
)
data class LimitRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val planId: Long,
    /** APP or GROUP. Kept as text so migrations remain explicit and inspectable. */
    val targetType: String,
    /** Package name for APP; decimal group id for GROUP. */
    val targetId: String,
    /** OPENINGS, MINUTES or TIME_WINDOW. */
    val ruleType: String,
    /** Daily amount, or TIME_WINDOW start minute. */
    val value: Int,
    /** TIME_WINDOW exclusive end minute; null for amount rules. */
    val endMinute: Int?,
)

@Entity(
    tableName = "pending_changes",
    indices = [Index(value = ["applyOnDate"])],
)
data class LegacyPendingChangeEntity(
    /** Compatibility-only row from M5; no new rows are written after schema v5. */
    @PrimaryKey
    val id: String,
    val kind: String,
    val payload: String,
    val applyOnDate: String,
    val createdAtEpochMillis: Long,
    val summary: String,
)
