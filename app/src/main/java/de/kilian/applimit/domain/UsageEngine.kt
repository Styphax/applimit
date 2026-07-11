package de.kilian.applimit.domain

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Android-independent state machine for foreground usage.
 *
 * A logical session remains resumable for [debounceMillis] after the app leaves
 * the foreground or the screen turns off. Time in that grace period is never
 * counted. A re-entry after strictly more than the debounce interval starts a
 * new session and opening.
 */
class UsageEngine(
    private val clock: Clock,
    zoneId: ZoneId = clock.zone,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    init {
        require(debounceMillis >= 0L) { "debounceMillis must not be negative" }
    }

    data class SessionSnapshot(
        val id: String,
        val packageName: String,
        val startedAtEpochMillis: Long,
        val endedAtEpochMillis: Long,
        val durationMillis: Long,
        val inactiveSinceEpochMillis: Long?,
        val isFinalized: Boolean,
    )

    data class DailyCounterSnapshot(
        val date: LocalDate,
        val packageName: String,
        val openings: Int,
        val durationMillis: Long,
        /** Extensions confirmed while this package triggered a friction screen. */
        val grantedMinutes: Int = 0,
        /** Extensions confirmed while this package triggered a friction screen. */
        val grantedOpenings: Int = 0,
    )

    data class Snapshot(
        val sessions: List<SessionSnapshot>,
        val counters: List<DailyCounterSnapshot>,
        val foregroundPackage: String?,
        val screenOn: Boolean,
    )

    data class Change(
        val finalizedSessions: List<SessionSnapshot> = emptyList(),
        val sessionStarted: Boolean = false,
    )

    private data class CounterKey(
        val date: LocalDate,
        val packageName: String,
    )

    private data class MutableCounter(
        var openings: Int = 0,
        var durationMillis: Long = 0L,
        var grantedMinutes: Int = 0,
        var grantedOpenings: Int = 0,
    )

    private data class MutableSession(
        val id: String,
        val packageName: String,
        val startedAtEpochMillis: Long,
        var endedAtEpochMillis: Long,
        var durationMillis: Long = 0L,
        var activeSinceEpochMillis: Long? = null,
        var inactiveSinceEpochMillis: Long? = null,
    ) {
        fun snapshot(isFinalized: Boolean): SessionSnapshot = SessionSnapshot(
            id = id,
            packageName = packageName,
            startedAtEpochMillis = startedAtEpochMillis,
            endedAtEpochMillis = endedAtEpochMillis,
            durationMillis = durationMillis,
            inactiveSinceEpochMillis = inactiveSinceEpochMillis,
            isFinalized = isFinalized,
        )
    }

    private val liveSessions = linkedMapOf<String, MutableSession>()
    private val counters = linkedMapOf<CounterKey, MutableCounter>()

    private var foregroundPackage: String? = null
    private var screenOn: Boolean = true
    private var lastProcessedEpochMillis: Long? = null
    private var zoneId: ZoneId = zoneId

    /** Restores persisted daily totals before the first event is processed. */
    fun restoreCounters(restoredCounters: Collection<DailyCounterSnapshot>) {
        check(lastProcessedEpochMillis == null && liveSessions.isEmpty()) {
            "Counters can only be restored before usage processing starts"
        }
        restoredCounters.forEach { restored ->
            counters[CounterKey(restored.date, restored.packageName)] = MutableCounter(
                openings = restored.openings,
                durationMillis = restored.durationMillis,
                grantedMinutes = restored.grantedMinutes,
                grantedOpenings = restored.grantedOpenings,
            )
        }
    }

    /**
     * Merges the authoritative counters for a newly active local date. This is needed when
     * a timezone change moves the device onto a date that already has persisted usage.
     */
    fun synchronizeCounters(restoredCounters: Collection<DailyCounterSnapshot>) {
        restoredCounters.forEach { restored ->
            val counter = counters.getOrPut(
                CounterKey(restored.date, restored.packageName),
                ::MutableCounter,
            )
            counter.openings = maxOf(counter.openings, restored.openings)
            counter.durationMillis = maxOf(counter.durationMillis, restored.durationMillis)
            counter.grantedMinutes = maxOf(counter.grantedMinutes, restored.grantedMinutes)
            counter.grantedOpenings = maxOf(counter.grantedOpenings, restored.grantedOpenings)
        }
    }

    /**
     * Restores sessions from the last Room flush after a process restart.
     * They resume as dormant at their last persisted end timestamp, so a quick
     * reconnect preserves the 60-second debounce without inventing usage time.
     */
    fun restoreSessions(restoredSessions: Collection<SessionSnapshot>) {
        check(lastProcessedEpochMillis == null && liveSessions.isEmpty()) {
            "Sessions can only be restored before usage processing starts"
        }
        restoredSessions
            .filterNot(SessionSnapshot::isFinalized)
            .forEach { restored ->
                liveSessions[restored.packageName] = MutableSession(
                    id = restored.id,
                    packageName = restored.packageName,
                    startedAtEpochMillis = restored.startedAtEpochMillis,
                    endedAtEpochMillis = restored.endedAtEpochMillis,
                    durationMillis = restored.durationMillis,
                    activeSinceEpochMillis = null,
                    inactiveSinceEpochMillis = restored.inactiveSinceEpochMillis
                        ?: restored.endedAtEpochMillis,
                )
            }
    }

    /**
     * Applies a resolved foreground window together with device state captured
     * for that same event. When the device is unavailable, the window is
     * treated as keyguard/lock-screen noise and the underlying app is retained.
     */
    fun onForegroundChanged(
        packageName: String?,
        deviceState: DeviceStateSnapshot,
    ): Change {
        val now = now()
        val finalized = advanceTo(now)
        val screenAvailable = ScreenAvailabilityPolicy.isScreenAvailable(deviceState)

        // Accessibility can report keyguard, wallet or biometric windows while
        // the panel is on but the device is still locked. Keep the underlying
        // app as foreground until usage becomes possible again.
        if (!screenAvailable) {
            if (screenOn) {
                screenOn = false
                foregroundPackage?.let { pauseSession(it, now) }
            }
            return Change(finalizedSessions = finalized)
        }

        val resumedFromUnavailableState = !screenOn
        screenOn = true

        if (foregroundPackage == packageName) {
            val sessionStarted = if (resumedFromUnavailableState && packageName != null) {
                activateSession(packageName, now)
            } else {
                false
            }
            return Change(
                finalizedSessions = finalized,
                sessionStarted = sessionStarted,
            )
        }

        foregroundPackage?.let { pauseSession(it, now) }
        foregroundPackage = packageName

        var sessionStarted = false
        if (packageName != null) {
            sessionStarted = activateSession(packageName, now)
        }

        return Change(
            finalizedSessions = finalized,
            sessionStarted = sessionStarted,
        )
    }

    /** Reconciles an event that does not replace the resolved foreground app. */
    fun onDeviceStateChanged(deviceState: DeviceStateSnapshot): Change {
        val now = now()
        val finalized = advanceTo(now)
        val isScreenAvailable = ScreenAvailabilityPolicy.isScreenAvailable(deviceState)

        if (screenOn == isScreenAvailable) {
            return Change(finalizedSessions = finalized)
        }

        screenOn = isScreenAvailable
        var sessionStarted = false
        foregroundPackage?.let { packageName ->
            if (isScreenAvailable) {
                sessionStarted = activateSession(packageName, now)
            } else {
                pauseSession(packageName, now)
            }
        }

        return Change(
            finalizedSessions = finalized,
            sessionStarted = sessionStarted,
        )
    }

    fun tick(): Change = Change(finalizedSessions = advanceTo(now()))

    /** Advances in the old zone before adopting a new device timezone. */
    fun updateZone(newZoneId: ZoneId): Change {
        if (newZoneId == zoneId) return Change()
        val finalized = advanceTo(now())
        zoneId = newZoneId
        return Change(finalizedSessions = finalized)
    }

    fun snapshot(): Snapshot = Snapshot(
        sessions = liveSessions.values.map { it.snapshot(isFinalized = false) },
        counters = counters.map { (key, counter) ->
            DailyCounterSnapshot(
                date = key.date,
                packageName = key.packageName,
                openings = counter.openings,
                durationMillis = counter.durationMillis,
                grantedMinutes = counter.grantedMinutes,
                grantedOpenings = counter.grantedOpenings,
            )
        },
        foregroundPackage = foregroundPackage,
        screenOn = screenOn,
    )

    fun requiresTicking(): Boolean = liveSessions.isNotEmpty()

    /**
     * Records the user-facing extension totals on the package counter. Rule-target-specific
     * grant accounting lives in Room because one package can be constrained by both an app
     * rule and a group rule.
     */
    fun recordGrantedExtensions(
        packageName: String,
        date: LocalDate,
        grantedMinutes: Int,
        grantedOpenings: Int,
    ) {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(grantedMinutes >= 0) { "grantedMinutes must not be negative" }
        require(grantedOpenings >= 0) { "grantedOpenings must not be negative" }
        val counter = counters.getOrPut(CounterKey(date, packageName), ::MutableCounter)
        counter.grantedMinutes += grantedMinutes
        counter.grantedOpenings += grantedOpenings
    }

    /**
     * Removes a just-created opening when enforcement immediately rejects the launch. This
     * keeps blocked attempts out of the user's allowance; a later confirmed friction grant
     * therefore makes exactly one real opening available.
     */
    fun rejectJustStartedOpening(packageName: String) {
        require(foregroundPackage == packageName) {
            "Only the current foreground package can be rejected"
        }
        val session = liveSessions[packageName]
            ?: error("No live session exists for $packageName")
        require(session.durationMillis == 0L) {
            "Only an opening without recorded foreground time can be rejected"
        }

        liveSessions.remove(packageName)
        foregroundPackage = null
        val key = CounterKey(dateAt(session.startedAtEpochMillis), packageName)
        val counter = counters[key] ?: error("No daily counter exists for $packageName")
        require(counter.openings > 0) { "Opening counter is already zero" }
        counter.openings -= 1
    }

    private fun activateSession(packageName: String, now: Long): Boolean {
        val existing = liveSessions[packageName]
        val session = if (existing == null) {
            incrementOpening(packageName, now)
            MutableSession(
                id = sessionIdFactory(),
                packageName = packageName,
                startedAtEpochMillis = now,
                endedAtEpochMillis = now,
            ).also { liveSessions[packageName] = it }
        } else {
            existing
        }

        session.inactiveSinceEpochMillis = null
        session.activeSinceEpochMillis = now
        session.endedAtEpochMillis = maxOf(session.endedAtEpochMillis, now)
        return existing == null
    }

    private fun pauseSession(packageName: String, now: Long) {
        val session = liveSessions[packageName] ?: return
        session.activeSinceEpochMillis = null
        if (session.inactiveSinceEpochMillis == null) {
            session.inactiveSinceEpochMillis = now
        }
        session.endedAtEpochMillis = maxOf(session.endedAtEpochMillis, now)
    }

    private fun advanceTo(requestedNow: Long): List<SessionSnapshot> {
        val previous = lastProcessedEpochMillis
        val now = if (previous == null) requestedNow else maxOf(previous, requestedNow)

        if (screenOn) {
            foregroundPackage?.let { packageName ->
                val session = liveSessions[packageName]
                val activeSince = session?.activeSinceEpochMillis
                if (session != null && activeSince != null && now > activeSince) {
                    val elapsed = now - activeSince
                    session.durationMillis += elapsed
                    session.endedAtEpochMillis = now
                    session.activeSinceEpochMillis = now
                    addDuration(packageName, activeSince, now)
                }
            }
        }

        val finalized = mutableListOf<SessionSnapshot>()
        val iterator = liveSessions.iterator()
        while (iterator.hasNext()) {
            val (_, session) = iterator.next()
            val inactiveSince = session.inactiveSinceEpochMillis ?: continue
            if (now - inactiveSince > debounceMillis) {
                session.activeSinceEpochMillis = null
                session.endedAtEpochMillis = inactiveSince
                finalized += session.snapshot(isFinalized = true)
                iterator.remove()
            }
        }

        lastProcessedEpochMillis = now
        return finalized
    }

    private fun incrementOpening(packageName: String, atEpochMillis: Long) {
        val key = CounterKey(dateAt(atEpochMillis), packageName)
        counters.getOrPut(key, ::MutableCounter).openings += 1
    }

    private fun addDuration(
        packageName: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
    ) {
        var segmentStart = startEpochMillis
        while (segmentStart < endEpochMillis) {
            val date = dateAt(segmentStart)
            val nextDayStart = date
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
            val segmentEnd = minOf(endEpochMillis, nextDayStart)
            val key = CounterKey(date, packageName)
            counters.getOrPut(key, ::MutableCounter).durationMillis += segmentEnd - segmentStart
            segmentStart = segmentEnd
        }
    }

    private fun dateAt(epochMillis: Long): LocalDate = Instant
        .ofEpochMilli(epochMillis)
        .atZone(zoneId)
        .toLocalDate()

    private fun now(): Long = clock.millis()

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS: Long = 60_000L
    }
}
