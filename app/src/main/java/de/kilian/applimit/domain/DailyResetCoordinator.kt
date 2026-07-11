package de.kilian.applimit.domain

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** LocalDate boundary detector. Clock jumps and DST transitions cannot create extra resets. */
class DailyResetCoordinator(
    private val clock: Clock,
    private val zoneIdProvider: () -> ZoneId = { clock.zone },
) {
    data class Transition(
        val date: LocalDate,
        val previousDate: LocalDate?,
        val zoneId: ZoneId,
        val dateChanged: Boolean,
        val zoneChanged: Boolean,
    ) {
        val changed: Boolean get() = dateChanged || zoneChanged
    }

    private var activeDate: LocalDate? = null
    private var activeZoneId: ZoneId? = null

    fun poll(): Transition {
        val zoneId = zoneIdProvider()
        val date = clock.instant().atZone(zoneId).toLocalDate()
        val previousDate = activeDate
        val previousZone = activeZoneId
        val dateChanged = previousDate != date
        val zoneChanged = previousZone != zoneId
        if (dateChanged) activeDate = date
        if (zoneChanged) activeZoneId = zoneId
        return Transition(
            date = date,
            previousDate = previousDate,
            zoneId = zoneId,
            dateChanged = dateChanged,
            zoneChanged = zoneChanged,
        )
    }
}
