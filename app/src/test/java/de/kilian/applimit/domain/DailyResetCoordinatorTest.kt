package de.kilian.applimit.domain

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyResetCoordinatorTest {
    @Test
    fun springDstJumpDoesNotCreateAnotherReset() {
        val clock = MutableClock(Instant.parse("2026-03-28T23:30:00Z"))
        val coordinator = DailyResetCoordinator(clock) { BERLIN }
        assertTrue(coordinator.poll().dateChanged)

        clock.instant = Instant.parse("2026-03-29T01:30:00Z") // 03:30 CEST
        assertFalse(coordinator.poll().dateChanged)

        clock.instant = Instant.parse("2026-03-29T22:00:00Z") // 00:00 CEST next day
        assertTrue(coordinator.poll().dateChanged)
    }

    @Test
    fun autumnRepeatedHourDoesNotCreateAnotherReset() {
        val clock = MutableClock(Instant.parse("2026-10-24T22:30:00Z"))
        val coordinator = DailyResetCoordinator(clock) { BERLIN }
        assertTrue(coordinator.poll().dateChanged)

        clock.instant = Instant.parse("2026-10-25T00:30:00Z") // 02:30 CEST
        assertFalse(coordinator.poll().dateChanged)
        clock.instant = Instant.parse("2026-10-25T01:30:00Z") // 02:30 CET again
        assertFalse(coordinator.poll().dateChanged)

        clock.instant = Instant.parse("2026-10-25T23:00:00Z") // 00:00 CET next day
        assertTrue(coordinator.poll().dateChanged)
    }

    @Test
    fun timezoneSwitchUsesTheNewDeviceLocalDateExactlyOnce() {
        val clock = MutableClock(Instant.parse("2026-07-10T22:30:00Z"))
        var zone = ZoneId.of("Europe/Berlin")
        val coordinator = DailyResetCoordinator(clock) { zone }
        assertTrue(coordinator.poll().dateChanged) // 11 July in Berlin

        zone = ZoneId.of("America/New_York")
        val switched = coordinator.poll() // 10 July in New York
        assertTrue(switched.zoneChanged)
        assertTrue(switched.dateChanged)
        assertFalse(coordinator.poll().changed)
    }

    private class MutableClock(var instant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = instant
    }

    private companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
    }
}
