package de.kilian.applimit.service

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityServiceHealthProbeTest {
    @After
    fun tearDown() {
        AccessibilityServiceHealthProbe.unregister()
    }

    @Test
    fun `idle connected processor stays healthy across repeated watchdog probes`() {
        AccessibilityServiceHealthProbe.register { acknowledge ->
            acknowledge()
            true
        }

        repeat(3) {
            assertTrue(AccessibilityServiceHealthProbe.ping(timeoutMillis = 10L))
        }
    }

    @Test
    fun `disconnected service cannot acknowledge watchdog probe`() {
        assertFalse(AccessibilityServiceHealthProbe.ping(timeoutMillis = 10L))
    }

    @Test
    fun `accepted but unprocessed probe is unhealthy`() {
        AccessibilityServiceHealthProbe.register { true }

        assertFalse(AccessibilityServiceHealthProbe.ping(timeoutMillis = 1L))
    }
}
