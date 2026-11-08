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
  speedAvgKmh: number | null
  outsideTempAvgC: number | null
  energyConsumedKwh: number | null
  consumptionWhPerKm: number | null
  qualityState: string | null
}

export type WebCharge = {
  id: string
  startDate: string | null
  endDate: string | null
  address: string | null
  durationMinutes: number | null
  energyAddedKwh: number | null
  cost: number | null
  currency: string | null
  /** 'ac' | 'dc' | null, with the miniprogram's conflict rules. */
  chargeMode: 'ac' | 'dc' | null
  maxPowerKw: number | null
  startBatteryLevel: number | null
  endBatteryLevel: number | null
  isCharging: boolean | null
  qualityState: string | null
}

export type WebChargePoint = {
  date: string
  batteryLevel: number | null
  powerKw: number | null
  voltageV: number | null
  currentA: number | null
  temperatureC: number | null
}

export type WebChargeSummary = {
  chargerPowerKw: number | null
  chargerVoltageV: number | null
  currentA: number | null
  phases: number | null
  timeToFullHours: number | null
  outsideTempC: number | null
  isCharging: boolean | null
}

export type WebDrivePoint = {
  date: string
  speedKmh: number | null
  powerKw: number | null
  batteryLevel: number | null
  elevationM: number | null
}

export type WebParked = {
  startDate: string | null
  endDate: string | null
  address: string | null
  startBatteryLevel: number | null
  endBatteryLevel: number | null
  batteryDelta: number | null
  /** Only set when the backend energy proof qualifies (same strict rules as the miniprogram). */
  energyKwh: number | null
  averagePowerW: number | null
  outsideTempC: number | null
  insideTempC: number | null
}
