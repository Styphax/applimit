package de.kilian.applimit.data.usage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "usage_sessions",
    indices = [
        Index(value = ["packageName", "startedAtEpochMillis"]),
        Index(value = ["isFinalized"]),
    ],
    primaryKeys = ["id"],
)
data class UsageSessionEntity(
    val id: String,
    val packageName: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val durationMillis: Long,
    val inactiveSinceEpochMillis: Long?,
    val isFinalized: Boolean,
)

@Entity(
    tableName = "daily_counters",
    primaryKeys = ["date", "packageName"],
    indices = [Index(value = ["date"])],
)
data class DailyCounterEntity(
    val date: String,
    val packageName: String,
    val openings: Int,
    val durationMillis: Long,
    val updatedAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "0")
    val grantedOpenings: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val grantedMinutes: Int = 0,
)

/**
 * Authoritative extension totals for one rule target on one local date.
 *
 * This is deliberately separate from [DailyCounterEntity]: raw usage is keyed by
 * package, while an extension belongs to the APP or GROUP budget that caused the
 * friction screen. Keeping the target identity prevents an app grant from also
 * extending its group budget (and vice versa).
 */
@Entity(
    tableName = "daily_limit_grants",
    primaryKeys = ["date", "targetType", "targetId"],
    indices = [Index(value = ["date"])],
)
data class DailyLimitGrantEntity(
    val date: String,
    val targetType: String,
    val targetId: String,
    @ColumnInfo(defaultValue = "0")
    val grantedOpenings: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val grantedMinutes: Int = 0,
    val updatedAtEpochMillis: Long,
)

/** Historical record of one confirmed 30-second friction passage. */
@Entity(
    tableName = "friction_events",
    indices = [
        Index(value = ["date"]),
        Index(value = ["timestampEpochMillis"]),
        Index(value = ["targetType", "targetId", "date"]),
    ],
)
data class FrictionEventEntity(
    /** Caller-generated passage id makes a retried persistence request idempotent. */
    @PrimaryKey
    val id: String,
    val date: String,
    val timestampEpochMillis: Long,
    val triggeringPackageName: String,
    val targetType: String,
    val targetId: String,
    /** OPENINGS, MINUTES or MULTIPLE. */
    val ruleType: String,
    val grantedOpenings: Int,
    val grantedMinutes: Int,
)

@Entity(
    tableName = "raw_usage_events",
    indices = [Index(value = ["timestampEpochMillis"])],
)
data class RawUsageEventEntity(
    @androidx.room.PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestampEpochMillis: Long,
    val eventType: String,
    val reportedPackage: String?,
    val resolvedPackage: String?,
    val decision: String,
    val className: String?,
    val screenOn: Boolean,
)
