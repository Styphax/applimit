package de.kilian.applimit.service

/** Keeps debounce sessions progressing without turning every tracked app into a 1-second poll. */
object TrackingCadencePolicy {
    fun intervalMillis(
        hasLiveSessions: Boolean,
        screenOn: Boolean,
        foregroundPackage: String?,
        monitoredPackages: Set<String>,
    ): Long? {
        if (!hasLiveSessions) return null
        return if (screenOn && foregroundPackage != null && foregroundPackage in monitoredPackages) {
            MONITORED_INTERVAL_MILLIS
        } else {
            MAINTENANCE_INTERVAL_MILLIS
        }
    }

    const val MONITORED_INTERVAL_MILLIS = 1_000L
    const val MAINTENANCE_INTERVAL_MILLIS = 5_000L
}
