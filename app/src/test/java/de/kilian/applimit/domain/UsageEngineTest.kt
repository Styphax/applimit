package de.kilian.applimit.domain

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageEngineTest {
    private val zoneId = ZoneOffset.UTC

    @Test
    fun reentryAtExactlySixtySecondsKeepsOpeningAndSession() {
        val clock = MutableClock(epochMillis = 0L, zoneId = zoneId)
        val engine = engine(clock)

        engine.onForegroundChanged(APP_A, AVAILABLE)
        clock.advanceBy(10_000L)
        engine.onForegroundChanged(null, AVAILABLE)
        clock.advanceBy(60_000L)
        val change = engine.onForegroundChanged(APP_A, AVAILABLE)

        val snapshot = engine.snapshot()
        assertFalse(change.sessionStarted)
        assertTrue(change.finalizedSessions.isEmpty())
        assertEquals(1, snapshot.sessions.size)
        assertEquals(1, snapshot.counterFor(APP_A).openings)
        assertEquals(10_000L, snapshot.counterFor(APP_A).durationMillis)
    }

    @Test
    fun reentryAfterMoreThanSixtySecondsCreatesNewOpeningAndSession() {
        val clock = MutableClock(epochMillis = 0L, zoneId = zoneId)
        val engine = engine(clock)

        engine.onForegroundChanged(APP_A, AVAILABLE)
        clock.advanceBy(10_000L)
        engine.onForegroundChanged(null, AVAILABLE)
        clock.advanceBy(60_001L)
        val change = engine.onForegroundChanged(APP_A, AVAILABLE)

        val snapshot = engine.snapshot()
        assertTrue(change.sessionStarted)
        assertEquals(1, change.finalizedSessions.size)
        assertEquals(10_000L, change.finalizedSessions.single().durationMillis)
        assertEquals(2, snapshot.counterFor(APP_A).openings)
        assertEquals(1, snapshot.sessions.size)
    }

    @Test
    fun freshOpeningAfterExpiredSessionCountsOpeningAndForegroundTimeTogether() {
        val clock = MutableClock(epochMillis = 0L, zoneId = zoneId)
        val engine = engine(clock)

        engine.onForegroundChanged(APP_A, AVAILABLE)
        clock.advanceBy(10_000L)
        engine.onForegroundChanged(null, AVAILABLE)

        clock.advanceBy(60_001L)
        val finalized = engine.tick()
        val before = engine.snapshot().counterFor(APP_A)

        val reopened = engine.onForegroundChanged(APP_A, AVAILABLE)
        clock.advanceBy(30_000L)
        engine.tick()

        val after = engine.snapshot().counterFor(APP_A)
        assertEquals(1, finalized.finalizedSessions.size)
        assertEquals(10_000L, finalized.finalizedSessions.single().durationMillis)
        assertTrue(reopened.sessionStarted)
        assertEquals(1, after.openings - before.openings)
        assertEquals(30_000L, after.durationMillis - before.durationMillis)
        assertEquals(2, after.openings)
        assertEquals(40_000L, after.durationMillis)
    }

    @Test
    fun briefAppSwitchDoesNotCountBackgroundTimeOrAnotherOpening() {
        val clock = MutableClock(epochMillis = 0L, zoneId = zoneId)
        val engine = engine(clock)

        engine.onForegroundChanged(APP_A, AVAILABLE)
        clock.advanceBy(5_000L)
        engine.onForegroundChanged(APP_B, AVAILABLE)
        clock.advanceBy(30_000L)
        engine.onForegroundChanged(APP_A, AVAILABLE)
        clock.advanceBy(5_000L)
        engine.tick()

        val snapshot = engine.snapshot()
        assertEquals(1, snapshot.counterFor(APP_A).openings)
        assertEquals(10_000L, snapshot.counterFor(APP_A).durationMillis)
        assertEquals(1, snapshot.counterFor(APP_B).openings)
        assertEquals(30_000L, snapshot.counterFor(APP_B).durationMillis)
    }

    @Test
    fun screenOffLongerThanSixtySecondsEndsSessionAndExcludesOffTime() {
        val clock = MutableClock(epochMillis = 0L, zoneId = zoneId)
        val engine = engine(clock)

        engine.onForegroundChanged(APP_A, AVAILABLE)
        clock.advanceBy(12_000L)
        engine.onDeviceStateChanged(NOT_INTERACTIVE_LOCKED)
        clock.advanceBy(60_001L)
        val change = engine.onDeviceStateChanged(AVAILABLE)

        val snapshot = engine.snapshot()
        assertEquals(1, change.finalizedSessions.size)
        assertEquals(12_000L, change.finalizedSessions.single().durationMillis)
        assertEquals(12_000L, change.finalizedSessions.single().endedAtEpochMillis)
        assertEquals(2, snapshot.counterFor(APP_A).openings)
        assertEquals(12_000L, snapshot.counterFor(APP_A).durationMillis)
    }

    @Test
    fun screenOffForExactlySixtySecondsResumesSameSession() {
        val clock = MutableClock(epochMillis = 0L, zoneId = zoneId)
        val engine = engine(clock)

        engine.onForegroundChanged(APP_A, AVAILABLE)
        clock.advanceBy(8_000L)
        engine.onDeviceStateChanged(NOT_INTERACTIVE_LOCKED)
        clock.advanceBy(60_000L)
        val change = engine.onDeviceStateChanged(AVAILABLE)
        clock.advanceBy(2_000L)
        engine.tick()

        val snapshot = engine.snapshot()
        assertTrue(change.finalizedSessions.isEmpty())
        assertFalse(change.sessionStarted)
        assertEquals(1, snapshot.counterFor(APP_A).openings)
        assertEquals(10_000L, snapshot.counterFor(APP_A).durationMillis)
    }

    @Test
    fun recordedLockedScreenOnSequenceStartsSecondOpeningAtUserPresent() {
        val clock = MutableClock(epochMillis = 0L, zoneId = zoneId)
        val engine = engine(clock)

        // Device evidence, 17:20:37.645: Twitter becomes foreground.
        engine.onForegroundChanged(APP_A, AVAILABLE)
        clock.advanceBy(11_514L)
        engine.onDeviceStateChanged(NOT_INTERACTIVE_LOCKED)

        // 17:20:49.426: System UI is ignored. At 17:21:48.973 the panel
        // turns on after 59.814 s, but the keyguard is still active.
        clock.advanceBy(267L)
        clock.advanceBy(59_547L)
        val lockedScreenOn = engine.onDeviceStateChanged(INTERACTIVE_LOCKED)

        // 17:21:49.264: a Samsung Pay lock-screen window is reported.
        // UsageEngine must retain the app below the keyguard.
        clock.advanceBy(291L)
        val lockedWindow = engine.onForegroundChanged(APP_B, INTERACTIVE_LOCKED)

        // 17:21:49.574: biometrics is ignored. At 17:21:51.525 the user is
        // present and Twitter is reported again, 62.366 s after SCREEN_OFF.
        clock.advanceBy(310L)
        clock.advanceBy(1_951L)
        val userPresent = engine.onDeviceStateChanged(AVAILABLE)
        engine.onForegroundChanged(APP_A, AVAILABLE)

        // 17:21:51.526: System UI is ignored. Launcher appears at 17:22:01.604.
        clock.advanceBy(1L)
        clock.advanceBy(10_078L)
        engine.onForegroundChanged(null, AVAILABLE)

        val snapshot = engine.snapshot()
        assertTrue(lockedScreenOn.finalizedSessions.isEmpty())
        assertEquals(1, lockedWindow.finalizedSessions.size)
        assertEquals(11_514L, lockedWindow.finalizedSessions.single().durationMillis)
        assertTrue(userPresent.sessionStarted)
        assertTrue(snapshot.foregroundPackage == null)
        assertEquals(2, snapshot.counterFor(APP_A).openings)
        assertEquals(21_593L, snapshot.counterFor(APP_A).durationMillis)
        assertTrue(snapshot.counters.none { it.packageName == APP_B })
    }

    @Test
    fun lockedScreenOnAfterThirtySecondsResumesSameOpeningAtUserPresent() {
        val clock = MutableClock(epochMillis = 0L, zoneId = zoneId)
        val engine = engine(clock)

        engine.onForegroundChanged(APP_A, AVAILABLE)
        clock.advanceBy(10_000L)
        engine.onDeviceStateChanged(NOT_INTERACTIVE_LOCKED)
        clock.advanceBy(30_000L)
        engine.onDeviceStateChanged(INTERACTIVE_LOCKED)
        clock.advanceBy(2_500L)
        engine.onForegroundChanged(APP_B, INTERACTIVE_LOCKED)
        val userPresent = engine.onDeviceStateChanged(AVAILABLE)
        engine.onForegroundChanged(APP_A, AVAILABLE)
        clock.advanceBy(10_000L)
        engine.onForegroundChanged(null, AVAILABLE)

        val snapshot = engine.snapshot()
        assertFalse(userPresent.sessionStarted)
        assertTrue(userPresent.finalizedSessions.isEmpty())
        assertEquals(1, snapshot.counterFor(APP_A).openings)
        assertEquals(20_000L, snapshot.counterFor(APP_A).durationMillis)
        assertTrue(snapshot.counters.none { it.packageName == APP_B })
    }

    @Test
    fun recordedRegressionSequenceWithoutUserPresentResumesOnUnlockedWindow() {
        val clock = MutableClock(epochMillis = 0L, zoneId = zoneId)
        val engine = engine(clock)

        // 18:05:28.467 to 18:05:38.961: the persisted first Oura phase.
        engine.onForegroundChanged(APP_A, AVAILABLE)
        clock.advanceBy(10_494L)
        engine.onDeviceStateChanged(NOT_INTERACTIVE_LOCKED)

        // System UI, then the first locked SCREEN_ON after 48.871 s.
        clock.advanceBy(215L)
        engine.onDeviceStateChanged(INTERACTIVE_LOCKED)
        clock.advanceBy(48_656L)
        engine.onDeviceStateChanged(INTERACTIVE_LOCKED)

        // Samsung Pay and biometrics remain lock-screen windows.
        clock.advanceBy(257L)
        engine.onForegroundChanged(APP_B, INTERACTIVE_LOCKED)
        clock.advanceBy(291L)
        engine.onDeviceStateChanged(INTERACTIVE_LOCKED)

        // The panel switches off again, then on while still locked. Across both
        // off intervals, 61.832 s are non-interactive.
        clock.advanceBy(862L)
        engine.onDeviceStateChanged(NOT_INTERACTIVE_LOCKED)
        clock.advanceBy(12_961L)
        val secondLockedScreenOn = engine.onDeviceStateChanged(INTERACTIVE_LOCKED)

        clock.advanceBy(280L)
        engine.onForegroundChanged(APP_B, INTERACTIVE_LOCKED)
        clock.advanceBy(260L)
        engine.onDeviceStateChanged(INTERACTIVE_LOCKED)

        // No USER_PRESENT was recorded. At 18:06:44.393 the Oura window itself
        // observes interactive + unlocked and must reactivate the engine.
        clock.advanceBy(1_650L)
        engine.onForegroundChanged(APP_B, INTERACTIVE_LOCKED)
        val unlockedOuraWindow = engine.onForegroundChanged(APP_A, AVAILABLE)
        engine.onDeviceStateChanged(AVAILABLE)

        // Oura remains visible until the next tracked Pay window at 18:06:59.098.
        clock.advanceBy(14_705L)
        engine.onForegroundChanged(APP_B, AVAILABLE)

        val snapshot = engine.snapshot()
        assertEquals(1, secondLockedScreenOn.finalizedSessions.size)
        assertEquals(10_494L, secondLockedScreenOn.finalizedSessions.single().durationMillis)
        assertEquals(10_494L, secondLockedScreenOn.finalizedSessions.single().endedAtEpochMillis)
        assertTrue(unlockedOuraWindow.sessionStarted)
        assertEquals(2, snapshot.counterFor(APP_A).openings)
        assertEquals(25_199L, snapshot.counterFor(APP_A).durationMillis)
    }

    @Test
    fun userPresentBeforeScreenOnStillStartsSecondOpening() {
        assertUnlockPermutation(UnlockOrder.USER_PRESENT_BEFORE_SCREEN_ON)
    }

    @Test
    fun userPresentAfterScreenOnStillStartsSecondOpening() {
        assertUnlockPermutation(UnlockOrder.USER_PRESENT_AFTER_SCREEN_ON)
    }

    @Test
    fun missingUserPresentFallsBackToUnlockedWindowEvent() {
        assertUnlockPermutation(UnlockOrder.MISSING_USER_PRESENT)
    }

    @Test
    fun processRestartWithinDebounceRestoresSessionWithoutNewOpening() {
        val clock = MutableClock(epochMillis = 0L, zoneId = zoneId)
        val beforeRestart = engine(clock)
        beforeRestart.onForegroundChanged(APP_A, AVAILABLE)
        clock.advanceBy(7_000L)
        beforeRestart.tick()
        val persisted = beforeRestart.snapshot()

        clock.advanceBy(5_000L)
        val afterRestart = engine(clock)
        afterRestart.restoreCounters(persisted.counters)
        afterRestart.restoreSessions(persisted.sessions)
        val change = afterRestart.onForegroundChanged(APP_A, AVAILABLE)
        clock.advanceBy(3_000L)
        afterRestart.tick()

        val snapshot = afterRestart.snapshot()
        assertFalse(change.sessionStarted)
        assertEquals(1, snapshot.counterFor(APP_A).openings)
        assertEquals(10_000L, snapshot.counterFor(APP_A).durationMillis)
        assertEquals(persisted.sessions.single().id, snapshot.sessions.single().id)
    }

    @Test
    fun rejectedOpeningIsNotConsumedAndConfirmedExtensionsAreRecorded() {
        val clock = MutableClock(epochMillis = 0L, zoneId = zoneId)
        val engine = engine(clock)

        val change = engine.onForegroundChanged(APP_A, AVAILABLE)
        assertTrue(change.sessionStarted)

        engine.rejectJustStartedOpening(APP_A)
        engine.recordGrantedExtensions(
            packageName = APP_A,
            date = LocalDate.ofEpochDay(0L),
            grantedMinutes = 5,
            grantedOpenings = 1,
        )

        val snapshot = engine.snapshot()
        val counter = snapshot.counterFor(APP_A)
        assertEquals(null, snapshot.foregroundPackage)
        assertTrue(snapshot.sessions.isEmpty())
        assertEquals(0, counter.openings)
        assertEquals(0L, counter.durationMillis)
        assertEquals(5, counter.grantedMinutes)
        assertEquals(1, counter.grantedOpenings)
    }

    @Test
    fun springDstDayIsOneLocalDateWithTwentyThreeHours() {
        val berlin = ZoneId.of("Europe/Berlin")
        val start = Instant.parse("2026-03-28T23:00:00Z")
        val clock = MutableClock(start.toEpochMilli(), berlin)
        val engine = engine(clock, berlin)

        engine.onForegroundChanged(APP_A, AVAILABLE)
        clock.advanceBy(23L * 60L * 60L * 1_000L)
        engine.tick()

        val counter = engine.snapshot().counters.single { it.date == LocalDate.parse("2026-03-29") }
        assertEquals(23L * 60L * 60L * 1_000L, counter.durationMillis)
    }

    @Test
    fun autumnDstDayIsOneLocalDateWithTwentyFiveHours() {
        val berlin = ZoneId.of("Europe/Berlin")
        val start = Instant.parse("2026-10-24T22:00:00Z")
        val clock = MutableClock(start.toEpochMilli(), berlin)
        val engine = engine(clock, berlin)

        engine.onForegroundChanged(APP_A, AVAILABLE)
        clock.advanceBy(25L * 60L * 60L * 1_000L)
        engine.tick()

        val counter = engine.snapshot().counters.single { it.date == LocalDate.parse("2026-10-25") }
        assertEquals(25L * 60L * 60L * 1_000L, counter.durationMillis)
    }

    private fun assertUnlockPermutation(order: UnlockOrder) {
        val clock = MutableClock(epochMillis = 0L, zoneId = zoneId)
        val engine = engine(clock)

        engine.onForegroundChanged(APP_A, AVAILABLE)
        clock.advanceBy(10_000L)
        engine.onDeviceStateChanged(NOT_INTERACTIVE_LOCKED)
        clock.advanceBy(60_001L)

        when (order) {
            UnlockOrder.USER_PRESENT_BEFORE_SCREEN_ON -> {
                engine.onDeviceStateChanged(AVAILABLE)
                engine.onDeviceStateChanged(AVAILABLE)
            }

            UnlockOrder.USER_PRESENT_AFTER_SCREEN_ON -> {
                engine.onDeviceStateChanged(INTERACTIVE_LOCKED)
                engine.onDeviceStateChanged(AVAILABLE)
            }

            UnlockOrder.MISSING_USER_PRESENT -> {
                engine.onDeviceStateChanged(INTERACTIVE_LOCKED)
                engine.onForegroundChanged(APP_A, AVAILABLE)
            }
        }

        engine.onForegroundChanged(APP_A, AVAILABLE)
        clock.advanceBy(10_000L)
        engine.tick()

        val snapshot = engine.snapshot()
        assertEquals(order.name, 2, snapshot.counterFor(APP_A).openings)
        assertEquals(order.name, 20_000L, snapshot.counterFor(APP_A).durationMillis)
    }

    private fun engine(clock: Clock, engineZone: ZoneId = zoneId): UsageEngine {
        var nextId = 0
        return UsageEngine(
            clock = clock,
            zoneId = engineZone,
            sessionIdFactory = { "session-${++nextId}" },
        )
    }

    private fun UsageEngine.Snapshot.counterFor(
        packageName: String,
    ): UsageEngine.DailyCounterSnapshot = counters.single { it.packageName == packageName }

    private class MutableClock(
        private var epochMillis: Long,
        private val zoneId: ZoneId,
    ) : Clock() {
        override fun getZone(): ZoneId = zoneId

        override fun withZone(zone: ZoneId): Clock = MutableClock(epochMillis, zone)

        override fun instant(): Instant = Instant.ofEpochMilli(epochMillis)

        fun advanceBy(millis: Long) {
            epochMillis += millis
        }
    }

    private enum class UnlockOrder {
        USER_PRESENT_BEFORE_SCREEN_ON,
        USER_PRESENT_AFTER_SCREEN_ON,
        MISSING_USER_PRESENT,
    }

    private companion object {
        const val APP_A = "example.app.a"
        const val APP_B = "example.app.b"
        val AVAILABLE = DeviceStateSnapshot(
            isInteractive = true,
            isKeyguardLocked = false,
        )
        val INTERACTIVE_LOCKED = DeviceStateSnapshot(
            isInteractive = true,
            isKeyguardLocked = true,
        )
        val NOT_INTERACTIVE_LOCKED = DeviceStateSnapshot(
            isInteractive = false,
            isKeyguardLocked = true,
        )
    }
}
