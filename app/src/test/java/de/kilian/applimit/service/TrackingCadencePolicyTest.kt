package de.kilian.applimit.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackingCadencePolicyTest {
    @Test
    fun monitoredForegroundKeepsOneSecondTicker() {
        assertEquals(
            1_000L,
            TrackingCadencePolicy.intervalMillis(true, true, TARGET, setOf(TARGET)),
        )
    }

    @Test
    fun trackedButUnmonitoredForegroundGetsMaintenanceTicker() {
        assertEquals(
            5_000L,
            TrackingCadencePolicy.intervalMillis(true, true, TARGET, emptySet()),
        )
    }

    @Test
    fun inactiveDebounceSessionKeepsMaintenanceTickerUntilFinalized() {
        assertEquals(
            5_000L,
            TrackingCadencePolicy.intervalMillis(true, true, null, emptySet()),
        )
    }

    @Test
    fun noLiveSessionStopsTicker() {
        assertNull(TrackingCadencePolicy.intervalMillis(false, true, null, emptySet()))
    }

    private companion object {
        const val TARGET = "example.target"
    }
}
