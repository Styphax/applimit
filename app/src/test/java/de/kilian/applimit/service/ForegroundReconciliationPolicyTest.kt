package de.kilian.applimit.service

import de.kilian.applimit.domain.DeviceStateSnapshot
import de.kilian.applimit.domain.UsageEngine
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundReconciliationPolicyTest {
    @Test
    fun missingLauncherEventWithIgnoredWalletStillClearsAndStopsUsage() {
        val clock = MutableClock()
        val engine = UsageEngine(clock = clock, sessionIdFactory = { "session" })
        engine.onForegroundChanged(TARGET, AVAILABLE)
        clock.advanceBy(10_000L)

        val resolution = ForegroundReconciliationPolicy.resolve(
            currentPackage = TARGET,
            candidates = listOf(
                candidate(WALLET, ForegroundReconciliationPolicy.CandidateAction.IGNORE),
                candidate(LAUNCHER, ForegroundReconciliationPolicy.CandidateAction.CLEAR),
            ),
        )
        engine.onForegroundChanged(resolution.packageName, AVAILABLE)
        clock.advanceBy(60_001L)
        val finalized = engine.tick().finalizedSessions.single()

        assertEquals(ForegroundReconciliationPolicy.Action.CLEAR, resolution.action)
        assertNull(engine.snapshot().foregroundPackage)
        assertEquals(10_000L, finalized.durationMillis)
        assertEquals(10_000L, engine.snapshot().counters.single().durationMillis)
    }

    @Test
    fun ignoredOverlayWithoutDecisiveUnderlyingWindowKeepsTrackedApp() {
        val resolution = ForegroundReconciliationPolicy.resolve(
            currentPackage = TARGET,
            candidates = listOf(
                candidate(WALLET, ForegroundReconciliationPolicy.CandidateAction.IGNORE),
            ),
        )

        assertEquals(ForegroundReconciliationPolicy.Action.KEEP, resolution.action)
        assertEquals(TARGET, resolution.packageName)
    }

    @Test
    fun ignoredKeyboardAboveSameTrackedAppDoesNotInterruptSession() {
        val resolution = ForegroundReconciliationPolicy.resolve(
            currentPackage = TARGET,
            candidates = listOf(
                candidate(KEYBOARD, ForegroundReconciliationPolicy.CandidateAction.IGNORE),
                candidate(TARGET, ForegroundReconciliationPolicy.CandidateAction.TRACK),
            ),
        )

        assertEquals(ForegroundReconciliationPolicy.Action.KEEP, resolution.action)
        assertEquals(TARGET, resolution.packageName)
    }

    @Test
    fun decisiveDifferentUserAppCorrectsTrackedForeground() {
        val resolution = ForegroundReconciliationPolicy.resolve(
            currentPackage = TARGET,
            candidates = listOf(
                candidate(OTHER_APP, ForegroundReconciliationPolicy.CandidateAction.TRACK),
            ),
        )

        assertEquals(ForegroundReconciliationPolicy.Action.TRACK, resolution.action)
        assertEquals(OTHER_APP, resolution.packageName)
    }

    private fun candidate(
        packageName: String,
        action: ForegroundReconciliationPolicy.CandidateAction,
    ) = ForegroundReconciliationPolicy.Candidate(
        packageName = packageName,
        className = null,
        action = action,
        reason = action.name,
    )

    private class MutableClock : Clock() {
        private var epochMillis = 0L

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(epochMillis)

        fun advanceBy(millis: Long) {
            epochMillis += millis
        }
    }

    private companion object {
        const val TARGET = "com.sec.android.app.popupcalculator"
        const val OTHER_APP = "example.other"
        const val WALLET = "com.samsung.android.spay"
        const val LAUNCHER = "com.teslacoilsw.launcher"
        const val KEYBOARD = "com.google.android.inputmethod.latin"
        val AVAILABLE = DeviceStateSnapshot(isInteractive = true, isKeyguardLocked = false)
    }
}
