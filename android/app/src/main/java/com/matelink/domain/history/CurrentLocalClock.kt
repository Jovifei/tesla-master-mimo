package com.matelink.domain.history

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Refreshes the device timezone on each read, including changes while the app stays alive. */
internal class CurrentLocalClock(
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
    private val instantProvider: () -> Instant = { Instant.now() }
) : Clock() {
    override fun getZone(): ZoneId = zoneProvider()
    override fun instant(): Instant = instantProvider()
    override fun withZone(zone: ZoneId): Clock = CurrentLocalClock({ zone }, instantProvider)
}
