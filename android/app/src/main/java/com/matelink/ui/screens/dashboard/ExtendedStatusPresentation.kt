package com.matelink.ui.screens.dashboard

import com.matelink.data.api.models.CarStatus
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Presentation shaping for the extended Fleet display fields added by
 * design 2026-11-05 §4 (backend pass-through in jourvolt status payload).
 *
 * Kept free of Compose and Android resources so the shaping rules are
 * unit-testable on the JVM: every helper returns structured data and the
 * dashboard screen only localizes and renders it. A helper returns null
 * (or an empty list) when the vehicle did not report the underlying
 * fields — absence must stay invisible, never rendered as a zero value.
 */

internal data class MediaPlayback(
    val title: String,
    val artist: String?
) {
    /** "Title — Artist" when an artist is known, otherwise just the title. */
    val displayText: String
        get() = artist?.takeIf { it.isNotBlank() }?.let { "$title — $it" } ?: title
}

/** Now-playing line, present only when the car reports a non-blank title. */
internal fun mediaPlayback(status: CarStatus): MediaPlayback? {
    val info = status.mediaInfo ?: return null
    val title = info.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return MediaPlayback(title = title, artist = info.artist?.trim()?.takeIf { it.isNotEmpty() })
}

internal enum class SoftwareUpdatePhase {
    DOWNLOADING,
    INSTALLING
}

internal data class SoftwareUpdateUi(
    val phase: SoftwareUpdatePhase,
    /** 0-100 when the car reports progress for the active phase, else null. */
    val percent: Int?
)

/**
 * Active software update, if any. Tesla reports lifecycle states such as
 * "scheduled", "available" and "wifi_wait" that carry no progress; only
 * downloading/installing are surfaced.
 */
internal fun softwareUpdateUi(status: CarStatus): SoftwareUpdateUi? {
    val update = status.softwareUpdate ?: return null
    return when (update.status?.trim()?.lowercase(Locale.ROOT)) {
        "downloading" -> SoftwareUpdateUi(
            SoftwareUpdatePhase.DOWNLOADING,
            update.downloadPercent?.coerceIn(0, 100)
        )
        "installing" -> SoftwareUpdateUi(
            SoftwareUpdatePhase.INSTALLING,
            update.installPercent?.coerceIn(0, 100)
        )
        else -> null
    }
}

internal enum class ScheduledKind {
    CHARGING_START,
    DEPARTURE
}

internal data class ScheduledEvent(
    val kind: ScheduledKind,
    /** Local "HH:mm" rendering of the scheduled instant. */
    val timeText: String
)

/** Upcoming scheduled charging start / departure, charging first. */
internal fun scheduledEvents(status: CarStatus): List<ScheduledEvent> = buildList {
    formatClockTime(status.scheduledChargingStartTime)?.let {
        add(ScheduledEvent(ScheduledKind.CHARGING_START, it))
    }
    formatClockTime(status.scheduledDepartureTime)?.let {
        add(ScheduledEvent(ScheduledKind.DEPARTURE, it))
    }
}

private val clockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

private fun formatClockTime(value: String?): String? {
    val parsed = value?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return null
    return parsed.atZoneSameInstant(ZoneId.systemDefault()).format(clockFormatter)
}

/**
 * Vehicle restriction modes worth a dashboard line: valet mode on, or an
 * active speed limit (with the limit already converted to km/h by the
 * backend). Null when neither applies.
 */
internal data class VehicleModes(
    val valetOn: Boolean,
    val speedLimitKmh: Int?
)

internal fun vehicleModes(status: CarStatus): VehicleModes? {
    val valetOn = status.valetMode == true
    val limit = status.speedLimitMode?.takeIf { it.active == true }?.currentLimitKmh
    if (!valetOn && limit == null) return null
    return VehicleModes(valetOn = valetOn, speedLimitKmh = limit)
}

/** Climate target temperatures, present when either side reports one. */
internal data class ClimateTargets(
    val driver: Double?,
    val passenger: Double?
)

internal fun climateTargets(status: CarStatus): ClimateTargets? {
    val driver = status.driverTempSetting
    val passenger = status.passengerTempSetting
    if (driver == null && passenger == null) return null
    return ClimateTargets(driver = driver, passenger = passenger)
}
