package de.kilian.applimit.domain

import de.kilian.applimit.data.config.ConfigurationSnapshot
import de.kilian.applimit.data.config.LimitRule
import de.kilian.applimit.data.config.LimitRuleType
import de.kilian.applimit.data.config.LimitTarget
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Android-independent evaluation of today's configured limits. */
class EnforcementEvaluator(
    private val zoneId: ZoneId,
) {
    data class GrantTotals(
        val grantedMinutes: Int = 0,
        val grantedOpenings: Int = 0,
    ) {
        init {
            require(grantedMinutes >= 0) { "grantedMinutes must not be negative" }
            require(grantedOpenings >= 0) { "grantedOpenings must not be negative" }
        }
    }

    enum class WarningKind {
        MINUTES_EIGHTY_PERCENT,
        ONE_OPENING_REMAINING,
    }

    data class Warning(
        val target: LimitTarget,
        val kind: WarningKind,
        /** Includes every confirmed extension for this exact target. */
        val effectiveLimit: Long,
    )

    data class Breach(
        val target: LimitTarget,
        val type: LimitRuleType,
        val used: Long,
        val effectiveLimit: Long,
    )

    data class HardBlock(
        val blockedTargets: Set<LimitTarget>,
        val nextAllowedAt: Instant?,
    )

    data class Evaluation(
        val monitored: Boolean,
        val date: LocalDate,
        val activePlanId: Long?,
        val warnings: List<Warning> = emptyList(),
        /** Smallest remaining time budget while it is in the final minute. */
        val countdownSeconds: Int? = null,
        val breaches: List<Breach> = emptyList(),
        val hardBlock: HardBlock? = null,
    ) {
        val requiresFriction: Boolean get() = hardBlock == null && breaches.isNotEmpty()
    }

    data class GrantDelta(
        val target: LimitTarget,
        val grantedMinutes: Int,
        val grantedOpenings: Int,
    )

    /** The two extension sizes offered on the friction screen after the countdown. */
    enum class GrantVariant(val minutes: Int, val openings: Int) {
        SMALL(minutes = 1, openings = 1),
        LARGE(minutes = 5, openings = 3),
    }

    fun evaluate(
        now: Instant,
        packageName: String,
        configuration: ConfigurationSnapshot,
        counters: Collection<UsageEngine.DailyCounterSnapshot>,
        targetGrants: Map<LimitTarget, GrantTotals> = emptyMap(),
    ): Evaluation {
        val localNow = now.atZone(zoneId)
        val date = localNow.toLocalDate()
        val monitoredApp = configuration.apps.firstOrNull { it.packageName == packageName }
            ?: return Evaluation(monitored = false, date = date, activePlanId = null)
        val plan = configuration.plans.firstOrNull { localNow.dayOfWeek in it.weekdays }
            ?: return Evaluation(monitored = true, date = date, activePlanId = null)
        val targets = linkedSetOf<LimitTarget>(LimitTarget.App(packageName)).apply {
            monitoredApp.groupId?.let { add(LimitTarget.Group(it)) }
        }
        val rules = configuration.limitRules.filter { it.planId == plan.id && it.target in targets }

        val blockedWindows = rules
            .filter { it.type == LimitRuleType.TIME_WINDOW }
            .filterNot { it.containsMinute(localNow.hour * 60 + localNow.minute) }
        if (blockedWindows.isNotEmpty()) {
            return Evaluation(
                monitored = true,
                date = date,
                activePlanId = plan.id,
                hardBlock = HardBlock(
                    blockedTargets = blockedWindows.mapTo(linkedSetOf(), LimitRule::target),
                    nextAllowedAt = findNextAllowedAt(localNow, packageName, configuration),
                ),
            )
        }

        val todayCounters = counters.filter { it.date == date }
        val warnings = mutableListOf<Warning>()
        val breaches = mutableListOf<Breach>()
        var smallestCountdownSeconds: Int? = null

        rules.forEach { rule ->
            val usage = usageFor(rule.target, configuration, todayCounters)
            val grants = targetGrants[rule.target] ?: GrantTotals()
            when (rule.type) {
                LimitRuleType.OPENINGS -> {
                    val effectiveLimit = rule.value.toLong() + grants.grantedOpenings
                    // N openings are usable. The attempt that would create N+1 is blocked.
                    if (usage.openings > effectiveLimit) {
                        breaches += Breach(
                            target = rule.target,
                            type = rule.type,
                            used = usage.openings,
                            effectiveLimit = effectiveLimit,
                        )
                    } else if (effectiveLimit >= 2L && usage.openings == effectiveLimit - 1L) {
                        warnings += Warning(
                            target = rule.target,
                            kind = WarningKind.ONE_OPENING_REMAINING,
                            effectiveLimit = effectiveLimit,
                        )
                    }
                }

                LimitRuleType.MINUTES -> {
                    val effectiveLimitMillis =
                        (rule.value.toLong() + grants.grantedMinutes) * MILLIS_PER_MINUTE
                    if (usage.durationMillis >= effectiveLimitMillis) {
                        breaches += Breach(
                            target = rule.target,
                            type = rule.type,
                            used = usage.durationMillis,
                            effectiveLimit = effectiveLimitMillis,
                        )
                    } else {
                        val warningThreshold = (effectiveLimitMillis * 4L + 4L) / 5L
                        if (usage.durationMillis >= warningThreshold) {
                            warnings += Warning(
                                target = rule.target,
                                kind = WarningKind.MINUTES_EIGHTY_PERCENT,
                                effectiveLimit = effectiveLimitMillis,
                            )
                        }
                        val remaining = effectiveLimitMillis - usage.durationMillis
                        if (remaining in 1L..MILLIS_PER_MINUTE) {
                            val seconds = ((remaining + 999L) / 1_000L).toInt()
                            smallestCountdownSeconds = smallestCountdownSeconds
                                ?.let { minOf(it, seconds) }
                                ?: seconds
                        }
                    }
                }

                LimitRuleType.TIME_WINDOW -> Unit
            }
        }

        return Evaluation(
            monitored = true,
            date = date,
            activePlanId = plan.id,
            warnings = warnings,
            countdownSeconds = smallestCountdownSeconds,
            breaches = breaches,
        )
    }

    /** One confirmed passage extends every exact rule target that currently blocks. */
    fun frictionGrantDeltas(
        breaches: Collection<Breach>,
        variant: GrantVariant,
    ): List<GrantDelta> = breaches
        .groupBy(Breach::target)
        .map { (target, targetBreaches) ->
            GrantDelta(
                target = target,
                grantedMinutes = if (targetBreaches.any { it.type == LimitRuleType.MINUTES }) {
                    variant.minutes
                } else {
                    0
                },
                grantedOpenings = if (
                    targetBreaches.any { it.type == LimitRuleType.OPENINGS }
                ) {
                    variant.openings
                } else {
                    0
                },
            )
        }
        .sortedBy { it.target.storageKey() }

    fun applyGrantDeltas(
        current: Map<LimitTarget, GrantTotals>,
        deltas: Collection<GrantDelta>,
    ): Map<LimitTarget, GrantTotals> = current.toMutableMap().apply {
        deltas.forEach { delta ->
            val previous = get(delta.target) ?: GrantTotals()
            put(
                delta.target,
                GrantTotals(
                    grantedMinutes = previous.grantedMinutes + delta.grantedMinutes,
                    grantedOpenings = previous.grantedOpenings + delta.grantedOpenings,
                ),
            )
        }
    }

    private fun findNextAllowedAt(
        now: ZonedDateTime,
        packageName: String,
        configuration: ConfigurationSnapshot,
    ): Instant? {
        var candidate = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1L)
        val searchEnd = now.plusDays(8L)
        while (!candidate.isAfter(searchEnd)) {
            if (isInsideAllWindows(candidate, packageName, configuration)) {
                return candidate.toInstant()
            }
            candidate = candidate.plusMinutes(1L)
        }
        return null
    }

    private fun isInsideAllWindows(
        at: ZonedDateTime,
        packageName: String,
        configuration: ConfigurationSnapshot,
    ): Boolean {
        val monitoredApp = configuration.apps.firstOrNull { it.packageName == packageName }
            ?: return true
        val plan = configuration.plans.firstOrNull { at.dayOfWeek in it.weekdays }
            ?: return true
        val targets = linkedSetOf<LimitTarget>(LimitTarget.App(packageName)).apply {
            monitoredApp.groupId?.let { add(LimitTarget.Group(it)) }
        }
        val minuteOfDay = at.hour * 60 + at.minute
        return configuration.limitRules
            .asSequence()
            .filter {
                it.planId == plan.id &&
                    it.target in targets &&
                    it.type == LimitRuleType.TIME_WINDOW
            }
            .all { it.containsMinute(minuteOfDay) }
    }

    private data class TargetUsage(
        val openings: Long,
        val durationMillis: Long,
    )

    private fun usageFor(
        target: LimitTarget,
        configuration: ConfigurationSnapshot,
        counters: Collection<UsageEngine.DailyCounterSnapshot>,
    ): TargetUsage {
        val packages = when (target) {
            is LimitTarget.App -> setOf(target.packageName)
            is LimitTarget.Group -> configuration.apps
                .asSequence()
                .filter { it.groupId == target.groupId }
                .mapTo(linkedSetOf()) { it.packageName }
        }
        return TargetUsage(
            openings = counters
                .asSequence()
                .filter { it.packageName in packages }
                .sumOf { it.openings.toLong() },
            durationMillis = counters
                .asSequence()
                .filter { it.packageName in packages }
                .sumOf { it.durationMillis },
        )
    }

    private fun LimitRule.containsMinute(minuteOfDay: Int): Boolean =
        minuteOfDay >= value && minuteOfDay < requireNotNull(endMinute)

    private fun LimitTarget.storageKey(): String = when (this) {
        is LimitTarget.App -> "APP:$packageName"
        is LimitTarget.Group -> "GROUP:$groupId"
    }

    companion object {
        private const val MILLIS_PER_MINUTE = 60_000L
    }
}
