package de.kilian.applimit.data.usage

import android.content.Context
import androidx.room.withTransaction
import de.kilian.applimit.domain.UsageEngine
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class UsageRepository private constructor(
    private val database: AppLimitDatabase,
) {
    data class DebugDay(
        val counters: List<DailyCounterEntity> = emptyList(),
        val targetGrants: List<DailyLimitGrantEntity> = emptyList(),
        val frictionEvents: List<FrictionEventEntity> = emptyList(),
        val sessions: List<UsageSessionEntity> = emptyList(),
        val rawEvents: List<RawUsageEventEntity> = emptyList(),
    )

    data class DashboardDay(
        val counters: List<DailyCounterEntity> = emptyList(),
        val targetGrants: List<DailyLimitGrantEntity> = emptyList(),
        val frictionEvents: List<FrictionEventEntity> = emptyList(),
    )

    /** Absolute extension totals for one APP or GROUP budget on one local date. */
    data class TargetGrantSnapshot(
        val date: LocalDate,
        val targetType: String,
        val targetId: String,
        val grantedOpenings: Int = 0,
        val grantedMinutes: Int = 0,
    ) {
        init {
            require(targetType == TARGET_APP || targetType == TARGET_GROUP) {
                "targetType must be APP or GROUP"
            }
            require(targetId.isNotBlank()) { "targetId must not be blank" }
            require(grantedOpenings >= 0) { "grantedOpenings must not be negative" }
            require(grantedMinutes >= 0) { "grantedMinutes must not be negative" }
        }
    }

    /** One confirmed countdown passage; callers generate [id] before showing the overlay. */
    data class FrictionEventRecord(
        val id: String,
        val date: LocalDate,
        val timestampEpochMillis: Long,
        val triggeringPackageName: String,
        /** APP, GROUP or MULTIPLE. */
        val targetType: String,
        /** Target id, or a stable comma-separated APP:/GROUP: list for MULTIPLE. */
        val targetId: String,
        /** OPENINGS, MINUTES or MULTIPLE. */
        val ruleType: String,
        val grantedOpenings: Int = 0,
        val grantedMinutes: Int = 0,
    ) {
        init {
            require(id.isNotBlank()) { "id must not be blank" }
            require(timestampEpochMillis >= 0L) { "timestampEpochMillis must not be negative" }
            require(triggeringPackageName.isNotBlank()) {
                "triggeringPackageName must not be blank"
            }
            require(targetType in EVENT_TARGET_TYPES) { "Unknown targetType: $targetType" }
            require(targetId.isNotBlank()) { "targetId must not be blank" }
            require(ruleType in EVENT_RULE_TYPES) { "Unknown ruleType: $ruleType" }
            require(grantedOpenings >= 0) { "grantedOpenings must not be negative" }
            require(grantedMinutes >= 0) { "grantedMinutes must not be negative" }
            require(grantedOpenings > 0 || grantedMinutes > 0) {
                "A confirmed friction event must grant an extension"
            }
        }
    }

    private val dao = database.usageDao()

    suspend fun restoreCounters(date: LocalDate): List<UsageEngine.DailyCounterSnapshot> =
        dao.getDailyCounters(date.toString()).map { entity ->
            UsageEngine.DailyCounterSnapshot(
                date = LocalDate.parse(entity.date),
                packageName = entity.packageName,
                openings = entity.openings,
                durationMillis = entity.durationMillis,
                grantedMinutes = entity.grantedMinutes,
                grantedOpenings = entity.grantedOpenings,
            )
        }

    suspend fun restoreTargetGrants(date: LocalDate): List<TargetGrantSnapshot> =
        dao.getDailyLimitGrants(date.toString()).map { entity ->
            TargetGrantSnapshot(
                date = LocalDate.parse(entity.date),
                targetType = entity.targetType,
                targetId = entity.targetId,
                grantedOpenings = entity.grantedOpenings,
                grantedMinutes = entity.grantedMinutes,
            )
        }

    suspend fun restoreSessions(): List<UsageEngine.SessionSnapshot> =
        dao.getOpenSessions().map { entity ->
            UsageEngine.SessionSnapshot(
                id = entity.id,
                packageName = entity.packageName,
                startedAtEpochMillis = entity.startedAtEpochMillis,
                endedAtEpochMillis = entity.endedAtEpochMillis,
                durationMillis = entity.durationMillis,
                inactiveSinceEpochMillis = entity.inactiveSinceEpochMillis,
                isFinalized = entity.isFinalized,
            )
        }

    suspend fun insertRawEvent(event: RawUsageEventEntity) {
        dao.insertRawEvent(event)
    }

    suspend fun persist(
        snapshot: UsageEngine.Snapshot,
        finalizedSessions: List<UsageEngine.SessionSnapshot>,
        persistedAtEpochMillis: Long,
        targetGrants: List<TargetGrantSnapshot> = emptyList(),
        frictionEvent: FrictionEventRecord? = null,
    ) {
        require(frictionEvent == null || targetGrants.isNotEmpty()) {
            "A friction event must be persisted with its authoritative target grants"
        }
        require(
            frictionEvent == null || targetGrants.all { it.date == frictionEvent.date },
        ) { "Friction event and target grants must belong to the same local date" }
        val sessions = (snapshot.sessions + finalizedSessions)
            .distinctBy { it.id }
            .map(UsageEngine.SessionSnapshot::toEntity)
        val counters = snapshot.counters.map { counter ->
            DailyCounterEntity(
                date = counter.date.toString(),
                packageName = counter.packageName,
                openings = counter.openings,
                durationMillis = counter.durationMillis,
                updatedAtEpochMillis = persistedAtEpochMillis,
                grantedOpenings = counter.grantedOpenings,
                grantedMinutes = counter.grantedMinutes,
            )
        }
        val grantEntities = targetGrants.map { grant ->
            DailyLimitGrantEntity(
                date = grant.date.toString(),
                targetType = grant.targetType,
                targetId = grant.targetId,
                grantedOpenings = grant.grantedOpenings,
                grantedMinutes = grant.grantedMinutes,
                updatedAtEpochMillis = persistedAtEpochMillis,
            )
        }
        val eventEntity = frictionEvent?.toEntity()

        database.withTransaction {
            if (sessions.isNotEmpty()) {
                dao.upsertSessions(sessions)
            }
            if (counters.isNotEmpty()) {
                dao.upsertDailyCounters(counters)
            }
            if (grantEntities.isNotEmpty()) {
                dao.upsertDailyLimitGrants(grantEntities)
            }
            if (eventEntity != null) {
                dao.insertFrictionEvent(eventEntity)
            }
        }
    }

    fun observeDay(
        date: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Flow<DebugDay> {
        val dayStart = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        return combine(
            dao.observeDailyCounters(date.toString()),
            dao.observeSessionsForDay(dayStart, dayEnd),
            dao.observeRawEventsForDay(dayStart, dayEnd),
            dao.observeDailyLimitGrants(date.toString()),
            dao.observeFrictionEvents(date.toString()),
        ) { counters, sessions, rawEvents, targetGrants, frictionEvents ->
            DebugDay(
                counters = counters,
                targetGrants = targetGrants,
                frictionEvents = frictionEvents,
                sessions = sessions,
                rawEvents = rawEvents,
            )
        }
    }

    /** Dashboard-only day data. Deliberately excludes sessions and raw usage events. */
    fun observeDashboardDay(date: LocalDate): Flow<DashboardDay> = combine(
        dao.observeDailyCounters(date.toString()),
        dao.observeDailyLimitGrants(date.toString()),
        dao.observeFrictionEventsInRange(date.toString(), date.toString()),
    ) { counters, targetGrants, frictionEvents ->
        DashboardDay(
            counters = counters,
            targetGrants = targetGrants,
            frictionEvents = frictionEvents,
        )
    }

    fun observeCounters(
        startDate: LocalDate,
        endDate: LocalDate,
    ): Flow<List<DailyCounterEntity>> {
        require(!endDate.isBefore(startDate)) { "endDate must not be before startDate" }
        return dao.observeDailyCountersInRange(startDate.toString(), endDate.toString())
    }

    fun observeFrictionHistory(
        startDate: LocalDate,
        endDate: LocalDate,
    ): Flow<List<FrictionEventEntity>> {
        require(!endDate.isBefore(startDate)) { "endDate must not be before startDate" }
        return dao.observeFrictionEventsInRange(startDate.toString(), endDate.toString())
    }

    companion object {
        @Volatile
        private var instance: UsageRepository? = null

        fun get(context: Context): UsageRepository = instance ?: synchronized(this) {
            instance ?: UsageRepository(AppLimitDatabase.get(context)).also { instance = it }
        }

        const val TARGET_APP = "APP"
        const val TARGET_GROUP = "GROUP"
        const val TARGET_MULTIPLE = "MULTIPLE"

        const val RULE_OPENINGS = "OPENINGS"
        const val RULE_MINUTES = "MINUTES"
        const val RULE_MULTIPLE = "MULTIPLE"

        private val EVENT_TARGET_TYPES = setOf(TARGET_APP, TARGET_GROUP, TARGET_MULTIPLE)
        private val EVENT_RULE_TYPES = setOf(RULE_OPENINGS, RULE_MINUTES, RULE_MULTIPLE)
    }
}

private fun UsageEngine.SessionSnapshot.toEntity(): UsageSessionEntity = UsageSessionEntity(
    id = id,
    packageName = packageName,
    startedAtEpochMillis = startedAtEpochMillis,
    endedAtEpochMillis = endedAtEpochMillis,
    durationMillis = durationMillis,
    inactiveSinceEpochMillis = inactiveSinceEpochMillis,
    isFinalized = isFinalized,
)

private fun UsageRepository.FrictionEventRecord.toEntity(): FrictionEventEntity =
    FrictionEventEntity(
        id = id,
        date = date.toString(),
        timestampEpochMillis = timestampEpochMillis,
        triggeringPackageName = triggeringPackageName,
        targetType = targetType,
        targetId = targetId,
        ruleType = ruleType,
        grantedOpenings = grantedOpenings,
        grantedMinutes = grantedMinutes,
    )
