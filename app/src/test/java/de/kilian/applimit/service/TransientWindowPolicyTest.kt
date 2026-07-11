package de.kilian.applimit.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientWindowPolicyTest {
    @Test
    fun evidenceBackedSamsungWalletFrameLayoutIsIgnored() {
        assertTrue(
            TransientWindowPolicy.isOverlay(
                "com.samsung.android.spay",
                "android.widget.FrameLayout",
            ),
        )
    }

    @Test
    fun realWalletActivitiesAndOtherFrameLayoutsRemainTrackable() {
        assertFalse(
            TransientWindowPolicy.isOverlay(
                "com.samsung.android.spay",
                "com.samsung.android.spay.ui.SpayMainActivity",
            ),
        )
        assertFalse(
            TransientWindowPolicy.isOverlay("example.app", "android.widget.FrameLayout"),
        )
    }
}
