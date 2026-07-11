package de.kilian.applimit.domain

import de.kilian.applimit.data.config.ConfigurationSnapshot
import de.kilian.applimit.data.config.LimitRuleType
import de.kilian.applimit.data.config.LimitTarget
import java.time.LocalDate

enum class DashboardTargetType {
    APP,
    GROUP,
}

data class DashboardTargetKey(
    val type: DashboardTargetType,
    val id: String,
) {
    init {
        require(id.isNotBlank()) { "Target id must not be blank" }
    }
}

data class DailyUsageRecord(
    val date: LocalDate,
    val packageName: String,
    val openings: Int,
    val durationMillis: Long,
) {
    init {
        require(packageName.isNotBlank()) { "Package name must not be blank" }
        require(openings >= 0) { "Openings must not be negative" }
        require(durationMillis >= 0L) { "Duration must not be negative" }
    }
}

data class DailyGrantRecord(
    val date: LocalDate,
    val target: DashboardTargetKey,
    val grantedOpenings: Int,
    val grantedMinutes: Int,
) {
    init {
        require(grantedOpenings >= 0) { "Granted openings must not be negative" }
        require(grantedMinutes >= 0) { "Granted minutes must not be negative" }
    }
}

data class DashboardFrictionRecord(
    val date: LocalDate,
    val triggeringPackageName: String,
    val targetType: String,
    val targetId: String,
) {
    init {
        require(triggeringPackageName.isNotBlank()) { "Triggering package must not be blank" }
        require(targetId.isNotBlank()) { "Target id must not be blank" }
    }
}

data class DashboardTargetSummary(
    val target: DashboardTargetKey,
    val label: String,
    val memberPackages: Set<String>,
    val openings: Int,
    val durationMillis: Long,
    val openingLimit: Int?,
    val minuteLimit: Int?,
    val grantedOpenings: Int,
    val grantedMinutes: Int,
    val frictionPassages: Int,
    val limitHits: Int,
) {
    val effectiveOpeningLimit: Int? get() = openingLimit?.plus(grantedOpenings)
    val effectiveMinuteLimit: Int? get() = minuteLimit?.plus(grantedMinutes)
}

data class UnmonitoredAppSummary(
    val packageName: String,
    val label: String,
    val openings: Int,
    val durationMillis: Long,
)

data class DashboardDaySummary(
    val date: LocalDate,
    val activePlanName: String?,
    val monitoredTargets: List<DashboardTargetSummary>,
    val unmonitoredApps: List<UnmonitoredAppSummary>,
)

data class WeeklyDaySummary(
    val date: LocalDate,
    val openings: Int,
    val durationMillis: Long,
)

data class WeeklyDashboardSummary(
    val days: List<WeeklyDaySummary>,
    val totalOpenings: Int,
    val totalDurationMillis: Long,
    val openingsChangeFromPreviousDay: Int,
    val durationChangeFromPreviousDayMillis: Long,
)

object DashboardAggregator {
    fun aggregateDay(
        date: LocalDate,
        configuration: ConfigurationSnapshot,
        usage: List<DailyUsageRecord>,
        grants: List<DailyGrantRecord> = emptyList(),
        frictionEvents: List<DashboardFrictionRecord> = emptyList(),
        trackedLabels: Map<String, String> = emptyMap(),
    ): DashboardDaySummary {
        val dayUsage = usage.filter { it.date == date }
        val dayGrants = grants.filter { it.date == date }.associateBy(DailyGrantRecord::target)
        val dayEvents = frictionEvents.filter { it.date == date }
        val activePlan = configuration.plans.firstOrNull { date.dayOfWeek in it.weekdays }
        val rules = activePlan?.let { plan ->
            configuration.limitRules.filter { it.planId == plan.id }
        }.orEmpty()
        val monitoredPackages = configuration.apps.mapTo(linkedSetOf()) { it.packageName }

        val groupTargets = configuration.groups.mapNotNull { group ->
            val members = configuration.apps
                .filter { it.groupId == group.id }
                .mapTo(linkedSetOf()) { it.packageName }
            if (members.isEmpty()) null else targetSummary(
                key = DashboardTargetKey(DashboardTargetType.GROUP, group.id.toString()),
                label = group.name,
                memberPackages = members,
                usage = dayUsage,
                rules = rules,
                grant = dayGrants[DashboardTargetKey(DashboardTargetType.GROUP, group.id.toString())],
                frictionEvents = dayEvents,
            )
        }.sortedBy { it.label.lowercase() }

        val appTargets = configuration.apps.map { app ->
            val key = DashboardTargetKey(DashboardTargetType.APP, app.packageName)
            targetSummary(
                key = key,
                label = app.label,
                memberPackages = setOf(app.packageName),
                usage = dayUsage,
                rules = rules,
                grant = dayGrants[key],
                frictionEvents = dayEvents,
            )
        }.sortedBy { it.label.lowercase() }

        val unmonitored = dayUsage
            .filterNot { it.packageName in monitoredPackages }
            .groupBy(DailyUsageRecord::packageName)
            .map { (packageName, records) ->
                UnmonitoredAppSummary(
                    packageName = packageName,
                    label = trackedLabels[packageName] ?: packageName,
                    openings = records.sumOf(DailyUsageRecord::openings),
                    durationMillis = records.sumOf(DailyUsageRecord::durationMillis),
                )
            }
            .sortedWith(
                compareByDescending<UnmonitoredAppSummary> { it.durationMillis }
                    .thenByDescending { it.openings }
                    .thenBy { it.label.lowercase() },
            )

        return DashboardDaySummary(
            date = date,
            activePlanName = activePlan?.name,
            monitoredTargets = groupTargets + appTargets,
            unmonitoredApps = unmonitored,
        )
    }

    fun aggregateWeek(
        endDate: LocalDate,
        configuration: ConfigurationSnapshot,
        usage: List<DailyUsageRecord>,
        target: DashboardTargetKey? = null,
    ): WeeklyDashboardSummary {
        val startDate = endDate.minusDays(WEEK_DAYS - 1L)
        val packages = packagesForTarget(target, configuration)
        val filtered = usage.filter { record ->
            record.date in startDate..endDate && (packages == null || record.packageName in packages)
        }
        val byDate = filtered.groupBy(DailyUsageRecord::date)
        val days = (0L until WEEK_DAYS).map { offset ->
            val date = startDate.plusDays(offset)
            val records = byDate[date].orEmpty()
            WeeklyDaySummary(
                date = date,
                openings = records.sumOf(DailyUsageRecord::openings),
                durationMillis = records.sumOf(DailyUsageRecord::durationMillis),
            )
        }
        val latest = days.last()
        val previous = days[days.lastIndex - 1]
        return WeeklyDashboardSummary(
            days = days,
            totalOpenings = days.sumOf(WeeklyDaySummary::openings),
            totalDurationMillis = days.sumOf(WeeklyDaySummary::durationMillis),
            openingsChangeFromPreviousDay = latest.openings - previous.openings,
            durationChangeFromPreviousDayMillis = latest.durationMillis - previous.durationMillis,
        )
    }

    fun sumGroupUsage(
        date: LocalDate,
        groupId: Long,
        configuration: ConfigurationSnapshot,
        usage: List<DailyUsageRecord>,
    ): WeeklyDaySummary {
        val members = configuration.apps
            .filter { it.groupId == groupId }
            .mapTo(hashSetOf()) { it.packageName }
        val records = usage.filter { it.date == date && it.packageName in members }
        return WeeklyDaySummary(
            date = date,
            openings = records.sumOf(DailyUsageRecord::openings),
            durationMillis = records.sumOf(DailyUsageRecord::durationMillis),
        )
    }

    private fun targetSummary(
        key: DashboardTargetKey,
        label: String,
        memberPackages: Set<String>,
        usage: List<DailyUsageRecord>,
        rules: List<de.kilian.applimit.data.config.LimitRule>,
        grant: DailyGrantRecord?,
        frictionEvents: List<DashboardFrictionRecord>,
    ): DashboardTargetSummary {
        val records = usage.filter { it.packageName in memberPackages }
        val openings = records.sumOf(DailyUsageRecord::openings)
        val durationMillis = records.sumOf(DailyUsageRecord::durationMillis)
        val limitTarget = key.toLimitTarget()
        val openingLimit = rules.firstOrNull {
            it.target == limitTarget && it.type == LimitRuleType.OPENINGS
        }?.value
        val minuteLimit = rules.firstOrNull {
            it.target == limitTarget && it.type == LimitRuleType.MINUTES
        }?.value
        val grantedOpenings = grant?.grantedOpenings ?: 0
        val grantedMinutes = grant?.grantedMinutes ?: 0
        val passages = frictionEvents.count { event ->
            event.appliesTo(key)
        }
        val currentlyAtLimit =
            (openingLimit != null && openings > openingLimit + grantedOpenings) ||
                (minuteLimit != null && durationMillis >=
                    (minuteLimit + grantedMinutes).toLong() * MILLIS_PER_MINUTE)

        return DashboardTargetSummary(
            target = key,
            label = label,
            memberPackages = memberPackages,
            openings = openings,
            durationMillis = durationMillis,
            openingLimit = openingLimit,
            minuteLimit = minuteLimit,
            grantedOpenings = grantedOpenings,
            grantedMinutes = grantedMinutes,
            frictionPassages = passages,
            limitHits = passages + if (currentlyAtLimit) 1 else 0,
        )
    }

    private fun packagesForTarget(
        target: DashboardTargetKey?,
        configuration: ConfigurationSnapshot,
    ): Set<String>? = when (target?.type) {
        null -> null
        DashboardTargetType.APP -> setOf(target.id)
        DashboardTargetType.GROUP -> {
            val groupId = target.id.toLongOrNull() ?: return emptySet()
            configuration.apps.filter { it.groupId == groupId }
                .mapTo(linkedSetOf()) { it.packageName }
        }
    }

    private fun DashboardTargetKey.toLimitTarget(): LimitTarget = when (type) {
        DashboardTargetType.APP -> LimitTarget.App(id)
        DashboardTargetType.GROUP -> LimitTarget.Group(id.toLong())
    }

    private fun DashboardFrictionRecord.appliesTo(
        key: DashboardTargetKey,
    ): Boolean {
        if (key.type == DashboardTargetType.APP && triggeringPackageName == key.id) return true
        val storageKey = "${key.type.name}:${key.id}"
        return when (targetType) {
            key.type.name -> targetId == key.id
            "MULTIPLE" -> targetId.split(',').any { it == storageKey }
            else -> false
        }
    }

    private const val WEEK_DAYS = 7L
    private const val MILLIS_PER_MINUTE = 60_000L
}
