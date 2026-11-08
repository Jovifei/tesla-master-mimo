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
  /** Live charging voltage/current from the status snapshot (charging only; history is not stored). */
  liveVoltageV: number | null
  liveCurrentA: number | null
  /** Vehicle software version when the status payload carries it. */
  firmware: string | null
  insideTempC: number | null
  outsideTempC: number | null
  latitude: number | null
  longitude: number | null
  geofence: string | null
  observedAt: string | null
  /** e.g. live telemetry, or telemetry_cache when the backend served a snapshot while the car was unreachable. */
  source: string | null
  driverTempC: number | null
  /** Present only when the backend evaluated command authorization for this status. */
  commandAuthorization: { ready: boolean | null; missingScopes: string[] | null } | null
  tpms: WebTpms | null
}

export type WebChargingSite = {
  name: string | null
  type: string | null
  latitude: number | null
  longitude: number | null
  distanceKm: number | null
  availableStalls: number | null
  totalStalls: number | null
  siteClosed: boolean | null
  priceText: string | null
  parkingPolicy: string | null
}

export type WebPlace = {
  id: number | null
  name: string | null
  latitude: number | null
  longitude: number | null
  radiusM: number | null
}

export type WebCommandResult = {
  command: string
  result: string | null
  verified: boolean | null
}

/** Display-only tire pressures in bar (unit must be explicitly bar, as in the miniprogram's TPMS report). */
export type WebTpms = {
  fl: number | null
  fr: number | null
  rl: number | null
  rr: number | null
  observedAt: { fl: string | null; fr: string | null; rl: string | null; rr: string | null }
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
  /** Route endpoints (backend publishes start/end coordinates when the route has them). */
  startLatitude: number | null
  startLongitude: number | null
  endLatitude: number | null
  endLongitude: number | null
  /** Odometer at session start/end (mileage-history stitching). */
  odometerStartKm: number | null
  odometerEndKm: number | null
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
  /** Charge location (record-level coordinates when the backend resolved them). */
  latitude: number | null
  longitude: number | null
  /** Pile-side energy used (kWh), published only via the energy contract (AC + qualified counters). */
  energyUsedKwh: number | null
  /** AC charging efficiency in percent from the energy contract, when qualified. */
  acEfficiencyPct: number | null
  /** AC loss in kWh from the energy contract, when qualified. */
  acLossKwh: number | null
  /** Chinese explanation when pile-side energy is unavailable (contract reason), else null. */
  energyUsedNote: string | null
}

export type WebChargePoint = {
  date: string
  batteryLevel: number | null
  powerKw: number | null
  voltageV: number | null
  currentA: number | null
  temperatureC: number | null
  /** Cumulative energy added at this sample, when the backend includes it. */
  energyAddedKwh: number | null
  latitude: number | null
  longitude: number | null
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
  /** Route coordinates (stored in sessions.route_json; previously dropped by the web parser). */
  latitude: number | null
  longitude: number | null
  heading: number | null
  insideTempC: number | null
  outsideTempC: number | null
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
