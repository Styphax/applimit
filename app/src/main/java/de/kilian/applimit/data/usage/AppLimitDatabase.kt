package de.kilian.applimit.data.usage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import de.kilian.applimit.data.config.AppGroupEntity
import de.kilian.applimit.data.config.ConfigDao
import de.kilian.applimit.data.config.LimitRuleEntity
import de.kilian.applimit.data.config.MonitoredAppEntity
import de.kilian.applimit.data.config.PlanEntity
import de.kilian.applimit.data.config.LegacyPendingChangeEntity

@Database(
    entities = [
        UsageSessionEntity::class,
        DailyCounterEntity::class,
        DailyLimitGrantEntity::class,
        FrictionEventEntity::class,
        RawUsageEventEntity::class,
        AppGroupEntity::class,
        MonitoredAppEntity::class,
        PlanEntity::class,
        LimitRuleEntity::class,
        LegacyPendingChangeEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppLimitDatabase : RoomDatabase() {
    abstract fun usageDao(): UsageDao
    abstract fun configDao(): ConfigDao

    companion object {
        @Volatile
        private var instance: AppLimitDatabase? = null

        fun get(context: Context): AppLimitDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppLimitDatabase::class.java,
                "app_limit.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
                .also { instance = it }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // M3 is deliberately additive: the three M2 usage tables are untouched.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_groups` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_app_groups_name` " +
                        "ON `app_groups` (`name`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `monitored_apps` (
                        `packageName` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `iconReference` TEXT,
                        `groupId` INTEGER,
                        PRIMARY KEY(`packageName`),
                        FOREIGN KEY(`groupId`) REFERENCES `app_groups`(`id`)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_monitored_apps_groupId` " +
                        "ON `monitored_apps` (`groupId`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `plans` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `weekdayMask` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `limit_rules` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `planId` INTEGER NOT NULL,
                        `targetType` TEXT NOT NULL,
                        `targetId` TEXT NOT NULL,
                        `ruleType` TEXT NOT NULL,
                        `value` INTEGER NOT NULL,
                        `endMinute` INTEGER,
                        FOREIGN KEY(`planId`) REFERENCES `plans`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_limit_rules_planId` " +
                        "ON `limit_rules` (`planId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_limit_rules_planId_targetType_targetId_ruleType` " +
                        "ON `limit_rules` (`planId`, `targetType`, `targetId`, `ruleType`)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // M4 remains additive. In particular, Kilians monitored apps, plans and
                // limit rules in the four v2 configuration tables are not rewritten.
                db.execSQL(
                    "ALTER TABLE `daily_counters` " +
                        "ADD COLUMN `grantedOpenings` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `daily_counters` " +
                        "ADD COLUMN `grantedMinutes` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `daily_limit_grants` (
                        `date` TEXT NOT NULL,
                        `targetType` TEXT NOT NULL,
                        `targetId` TEXT NOT NULL,
                        `grantedOpenings` INTEGER NOT NULL DEFAULT 0,
                        `grantedMinutes` INTEGER NOT NULL DEFAULT 0,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`date`, `targetType`, `targetId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_daily_limit_grants_date` " +
                        "ON `daily_limit_grants` (`date`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `friction_events` (
                        `id` TEXT NOT NULL,
                        `date` TEXT NOT NULL,
                        `timestampEpochMillis` INTEGER NOT NULL,
                        `triggeringPackageName` TEXT NOT NULL,
                        `targetType` TEXT NOT NULL,
                        `targetId` TEXT NOT NULL,
                        `ruleType` TEXT NOT NULL,
                        `grantedOpenings` INTEGER NOT NULL,
                        `grantedMinutes` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_friction_events_date` " +
                        "ON `friction_events` (`date`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_friction_events_timestampEpochMillis` " +
                        "ON `friction_events` (`timestampEpochMillis`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_friction_events_targetType_targetId_date` " +
                        "ON `friction_events` (`targetType`, `targetId`, `date`)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // M5 is additive: all configuration and usage history remains untouched.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pending_changes` (
                        `id` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `payload` TEXT NOT NULL,
                        `applyOnDate` TEXT NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `summary` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_changes_applyOnDate` " +
                        "ON `pending_changes` (`applyOnDate`)",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Keep the M5 table long enough for ConfigRepository to decode and apply every
                // queued intent transactionally on first access. It remains empty and unwritten.
            }
        }
    }
}
