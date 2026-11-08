/**
 * MateLink production API types for the web client (W1 subset).
 * Field semantics mirror the miniprogram's services/types.ts; the raw
 * wire shapes are the backend's snake_case records, normalized here.
 */

export type WebSession = {
  accessToken: string
  refreshToken: string
  /** Epoch milliseconds when the access token expires. */
  expiresAt: number
  userId: string
}

export type WebCar = {
  id: number
  stableId: string | null
  name: string
  model: string | null
}

export type WebCarStatus = {
  state: string | null
  batteryLevel: number | null
  ratedRangeKm: number | null
  estimatedRangeKm: number | null
  odometerKm: number | null
  locked: boolean | null
  sentryMode: boolean | null
  windowsOpen: boolean | null
  chargingState: string | null
  chargeEnergyAddedKwh: number | null
  chargerPowerKw: number | null
  insideTempC: number | null
  outsideTempC: number | null
  latitude: number | null
  longitude: number | null
  geofence: string | null
  observedAt: string | null
  /** e.g. live telemetry, or telemetry_cache when the backend served a snapshot while the car was unreachable. */
  source: string | null
}

export type WebDriveSummary = {
  id: string
  startDate: string | null
  endDate: string | null
  startAddress: string | null
  endAddress: string | null
  distanceKm: number | null
  durationMinutes: number | null
  startBatteryLevel: number | null
  endBatteryLevel: number | null
  speedMaxKmh: number | null
}
