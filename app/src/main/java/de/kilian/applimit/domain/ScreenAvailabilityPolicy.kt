package de.kilian.applimit.domain

/** Broadcast-level screen signals used only for raw-event labels. */
enum class ScreenSignal(val rawEventType: String) {
    SCREEN_OFF("SCREEN_OFF"),
    SCREEN_ON("SCREEN_ON"),
    USER_PRESENT("USER_PRESENT"),
}

/** Android-independent snapshot of the two authoritative device-state signals. */
data class DeviceStateSnapshot(
    val isInteractive: Boolean,
    val isKeyguardLocked: Boolean,
)

/**
 * Resolves whether foreground app usage is possible from current device state.
 * Broadcast ordering is intentionally irrelevant: every event supplies a new
 * snapshot, and no individual signal is trusted as an unlock transition.
 */
object ScreenAvailabilityPolicy {
    fun isScreenAvailable(deviceState: DeviceStateSnapshot): Boolean =
        deviceState.isInteractive && !deviceState.isKeyguardLocked
}
