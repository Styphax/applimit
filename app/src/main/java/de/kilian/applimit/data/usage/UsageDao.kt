package de.kilian.applimit.data.usage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageDao {
    @Upsert
    suspend fun upsertSessions(sessions: List<UsageSessionEntity>)

    @Upsert
    suspend fun upsertDailyCounters(counters: List<DailyCounterEntity>)

    @Upsert
    suspend fun upsertDailyLimitGrants(grants: List<DailyLimitGrantEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRawEvent(event: RawUsageEventEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFrictionEvent(event: FrictionEventEntity): Long

    @Query(
        """
        SELECT * FROM daily_counters
        WHERE date = :date
        ORDER BY durationMillis DESC, openings DESC, packageName ASC
        """,
    )
    fun observeDailyCounters(date: String): Flow<List<DailyCounterEntity>>

    @Query(
        """
        SELECT * FROM daily_counters
        WHERE date >= :startDate AND date <= :endDate
        ORDER BY date ASC, durationMillis DESC, openings DESC, packageName ASC
        """,
    )
    fun observeDailyCountersInRange(
        startDate: String,
        endDate: String,
    ): Flow<List<DailyCounterEntity>>

    @Query("SELECT * FROM daily_counters WHERE date = :date")
    suspend fun getDailyCounters(date: String): List<DailyCounterEntity>

    @Query(
        """
        SELECT * FROM daily_limit_grants
        WHERE date = :date
        ORDER BY targetType, targetId
        """,
    )
    suspend fun getDailyLimitGrants(date: String): List<DailyLimitGrantEntity>

    @Query(
        """
        SELECT * FROM daily_limit_grants
        WHERE date = :date
        ORDER BY targetType, targetId
        """,
    )
    fun observeDailyLimitGrants(date: String): Flow<List<DailyLimitGrantEntity>>

    @Query(
        """
        SELECT * FROM daily_limit_grants
        WHERE date >= :startDate AND date <= :endDate
        ORDER BY date ASC, targetType ASC, targetId ASC
        """,
    )
    fun observeDailyLimitGrantsInRange(
        startDate: String,
        endDate: String,
    ): Flow<List<DailyLimitGrantEntity>>

    @Query("SELECT * FROM usage_sessions WHERE isFinalized = 0")
    suspend fun getOpenSessions(): List<UsageSessionEntity>

    @Query(
        """
        SELECT * FROM usage_sessions
        WHERE startedAtEpochMillis < :dayEndEpochMillis
          AND endedAtEpochMillis >= :dayStartEpochMillis
        ORDER BY startedAtEpochMillis DESC
        LIMIT :limit
        """,
    )
    fun observeSessionsForDay(
        dayStartEpochMillis: Long,
        dayEndEpochMillis: Long,
        limit: Int = 300,
    ): Flow<List<UsageSessionEntity>>

    @Query(
        """
        SELECT * FROM raw_usage_events
        WHERE timestampEpochMillis >= :dayStartEpochMillis
          AND timestampEpochMillis < :dayEndEpochMillis
        ORDER BY timestampEpochMillis DESC, id DESC
        LIMIT :limit
        """,
    )
    fun observeRawEventsForDay(
        dayStartEpochMillis: Long,
        dayEndEpochMillis: Long,
        limit: Int = 300,
    ): Flow<List<RawUsageEventEntity>>

    @Query(
        """
        SELECT * FROM friction_events
        WHERE date = :date
        ORDER BY timestampEpochMillis DESC, id DESC
        LIMIT :limit
        """,
    )
    fun observeFrictionEvents(
        date: String,
        limit: Int = 300,
    ): Flow<List<FrictionEventEntity>>

    @Query(
        """
        SELECT * FROM friction_events
        WHERE date >= :startDate AND date <= :endDate
        ORDER BY timestampEpochMillis DESC, id DESC
        """,
    )
    fun observeFrictionEventsInRange(
        startDate: String,
        endDate: String,
    ): Flow<List<FrictionEventEntity>>

    @Query(
        """
        SELECT * FROM friction_events
        WHERE date = :date
        ORDER BY timestampEpochMillis ASC, id ASC
        """,
    )
    suspend fun getFrictionEvents(date: String): List<FrictionEventEntity>

}
