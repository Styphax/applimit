package de.kilian.applimit.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Active in-process probe for the accessibility service's serialized command processor. */
object AccessibilityServiceHealthProbe {
    @Volatile
    private var enqueueProbe: ((acknowledge: () -> Unit) -> Boolean)? = null

    fun register(enqueuer: (acknowledge: () -> Unit) -> Boolean) {
        enqueueProbe = enqueuer
    }

    fun unregister() {
        enqueueProbe = null
    }

    fun ping(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Boolean {
        val acknowledgement = CountDownLatch(1)
        val accepted = enqueueProbe?.invoke(acknowledgement::countDown) == true
        return accepted && acknowledgement.await(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    private const val DEFAULT_TIMEOUT_MILLIS = 2_000L
}
