import type { TpmsReport, TpmsSnapshot } from '../domain/tpms'

export type ApiEnvelope<T> = {
  data?: T
  error?: string
  message?: string
  message_key?: string
  meta?: PageMeta
}

export type PageMeta = {
  page?: number | null
  show?: number | null
  total?: number | null
  total_pages?: number | null
  availability?: 'available' | 'collecting' | 'unsupported' | string | null
  source?: string | null
  quality_state?: string | null
  quality_reason?: string | null
}

export type HistoryPageMeta = {
  page: number
  show: number
  total: number | null
  totalPages: number | null
  availability: string | null
  source: string | null
  qualityState: string | null
  qualityReason: string | null
  hasMore: boolean
  /** Raw rows rejected by normalization; a nonzero count makes adjacency incomplete. */
  discardedRows?: number
}

export type HistoryPage<T> = {
  items: T[]
  meta: HistoryPageMeta
}

export type Car = {
  id: number
  stableId: string
  name: string | null
  model: string | null
  trim: string | null
  exteriorColor: string | null
  wheelType: string | null
  displayName: string | null
  remoteVerified?: boolean
  freeSupercharging?: boolean | null
  capacityKwh?: number | null
  capacityBasis?: string | null
  capacitySourceField?: string | null
  capacityCalculationVersion?: number | null
}

export type RawCar = {
  car_id?: number
  id?: number
  vehicle_uid?: string | null
  provider_vehicle_id?: string | null
  name?: string | null
  model?: string | null
  display_name?: string | null
  current_capacity?: number | null
  capacity_basis?: string | null
  capacity_source_field?: string | null
  calculation_version?: number | null
  battery_health?: { current_capacity?: number | null } | null
  car_details?: { model?: string | null; trim_badging?: string | null }
  car_exterior?: { exterior_color?: string | null; wheel_type?: string | null }
  car_settings?: { free_supercharging?: boolean | null } | null
}

/** Speed-limit mode as reported by the backend (limit already in km/h). */
export type SpeedLimitState = {
  active: boolean | null
  currentLimitKmh: number | null
  pinCodeRequired: boolean | null
}

/** Now-playing media as reported by the backend. */
export type MediaState = {
  state: string | null
  title: string | null
  artist: string | null
  album: string | null
}

/** Pending software update as reported by the backend. */
export type SoftwareUpdateState = {
  status: string | null
  downloadPercent: number | null
  installPercent: number | null
  expectedDurationSec: number | null
}

/**
 * Command authorization evaluated by the backend from the user's Tesla
 * token (design 2026-11-05 §6). Absent/null means "not evaluated" and
 * must never be treated as "needs re-authorization".
 */
export type CommandAuthorization = {
  ready: boolean
  missingScopes: string[]
}

export type CarStatus = {
  idealRange?: number | null
  estimatedRange?: number | null
  /** Extended Fleet display fields (design 2026-11-05 §4); absent = not reported. */
  valetMode?: boolean | null
  speedLimit?: SpeedLimitState | null
  media?: MediaState | null
  softwareVersion?: string | null
  softwareUpdate?: SoftwareUpdateState | null
  driverTempSetting?: number | null
  passengerTempSetting?: number | null
  fanStatus?: number | null
  frontDefrosterOn?: boolean | null
  rearDefrosterOn?: boolean | null
  seatHeaterLeft?: number | null
  seatHeaterRight?: number | null
  seatHeaterRearLeft?: number | null
  seatHeaterRearCenter?: number | null
  seatHeaterRearRight?: number | null
  steeringWheelHeater?: boolean | null
  defrostMode?: number | null
  climateKeeperMode?: string | null
  cabinOverheatProtection?: string | null
  cabinOverheatProtectionCooling?: boolean | null
  sideMirrorHeaters?: boolean | null
  chargeCable?: string | null
  fastChargerBrand?: string | null
  fastChargerType?: string | null
  chargePortLatch?: string | null
  batteryHeaterOn?: boolean | null
  scheduledChargingStartTime?: string | null
  scheduledDepartureTime?: string | null
  minutesToFullCharge?: number | null
  chargeRangeAddedRated?: number | null
  chargeRangeAddedIdeal?: number | null
  commandAuthorization?: CommandAuthorization | null
  chargerVoltage?: number | null
  chargerActualCurrent?: number | null
  /** Tesla's time_to_full_charge is hours, not minutes. */
  timeToFullChargeHours?: number | null
  chargeLimitSoc?: number | null
  chargerPhases?: number | null
  chargeCurrentRequest?: number | null
  chargeCurrentRequestMax?: number | null
  chargePortDoorOpen?: boolean | null
  isPreconditioning?: boolean | null
  /** Online-only location label; never persisted in the vehicle snapshot cache. */
  geofence?: string | null
  /** Source identifiers only; package observedAt is not a field observation clock. */
  fieldSources?: Record<string, string>
  tpms?: TpmsSnapshot
  /** Online-only reported pressure; missing wheel time keeps it out of alerts and history. */
  tpmsReport?: TpmsReport
  state: string | null
  model: string | null
  trim: string | null
  exteriorColor: string | null
  wheelType: string | null
  batteryLevel: number | null
  ratedRange: number | null
  odometer: number | null
  location: { latitude: number; longitude: number } | null
  locked: boolean | null
  sentryMode: boolean | null
  windowsOpen: boolean | null
  doorsOpen: boolean | null
  trunkOpen: boolean | null
  frunkOpen: boolean | null
  isClimateOn: boolean | null
  insideTemp: number | null
  outsideTemp: number | null
  pluggedIn: boolean | null
  speed: number | null
  power: number | null
  shiftState: string | null
  heading: number | null
  chargingState: string | null
  chargeEnergyAdded: number | null
  chargerPower: number | null
  observedAt: string | null
  source: string | null
}

export type RawCarStatus = {
  tpms_details?: RawTpmsDetails | null
  display_name?: string | null
  state?: string | null
  model?: string | null
  trim_badging?: string | null
  exterior_color?: string | null
  wheel_type?: string | null
  odometer?: number | null
  car_status?: {
    locked?: boolean | null
    sentry_mode?: boolean | null
    windows_open?: boolean | null
    doors_open?: boolean | null
    trunk_open?: boolean | null
    frunk_open?: boolean | null
    valet_mode?: boolean | null
    speed_limit_mode?: {
      active?: boolean | null
      current_limit_kmh?: number | null
      pin_code_required?: boolean | null
    } | null
    media_info?: {
      media_state?: string | null
      title?: string | null
      artist?: string | null
      album?: string | null
    } | null
  }
  car_versions?: {
    version?: string | null
    software_update?: {
      status?: string | null
      download_percent?: number | null
      install_percent?: number | null
      expected_duration_sec?: number | null
    } | null
  }
  command_authorization?: {
    ready?: boolean | null
    missing_scopes?: string[] | null
  } | null
  battery_details?: {
    battery_level?: number | null
    rated_battery_range?: number | null
    ideal_battery_range?: number | null
    est_battery_range?: number | null
  }
  car_geodata?: {
    latitude?: number | null
    longitude?: number | null
    geofence?: string | null
  }
  driving_details?: {
    shift_state?: string | null
    speed?: number | null
    power?: number | null
    heading?: number | null
  }
  climate_details?: {
    is_climate_on?: boolean | null
    is_preconditioning?: boolean | null
    inside_temp?: number | null
    outside_temp?: number | null
    driver_temp_setting?: number | null
    passenger_temp_setting?: number | null
    fan_status?: number | null
    is_front_defroster_on?: boolean | null
    is_rear_defroster_on?: boolean | null
    seat_heater_left?: number | null
    seat_heater_right?: number | null
    seat_heater_rear_left?: number | null
    seat_heater_rear_center?: number | null
    seat_heater_rear_right?: number | null
    steering_wheel_heater?: boolean | null
    defrost_mode?: number | null
    climate_keeper_mode?: string | null
    cabin_overheat_protection?: string | null
    cabin_overheat_protection_actively_cooling?: boolean | null
    side_mirror_heaters?: boolean | null
  }
  charging_details?: {
    plugged_in?: boolean | null
    charging_state?: string | null
    charge_energy_added?: number | null
    charger_power?: number | null
    charger_voltage?: number | null
    charger_actual_current?: number | null
    time_to_full_charge?: number | null
    charge_limit_soc?: number | null
    charger_phases?: number | null
    charge_current_request?: number | null
    charge_current_request_max?: number | null
    charge_port_door_open?: boolean | null
    conn_charge_cable?: string | null
    fast_charger_brand?: string | null
    fast_charger_type?: string | null
    charge_port_latch?: string | null
    battery_heater_on?: boolean | null
    scheduled_charging_start_time?: string | null
    scheduled_departure_time?: string | null
    minutes_to_full_charge?: number | null
    charge_range_added_rated?: number | null
    charge_range_added_ideal?: number | null
  }
}

/** One site from GET /api/v1/cars/{carId}/nearby-charging-sites (§5). */
export type NearbyChargingSite = {
  name: string | null
  type: string | null
  latitude: number | null
  longitude: number | null
  distanceKm: number | null
  availableStalls: number | null
  totalStalls: number | null
  siteClosed: boolean | null
}

export type NearbyCharging = {
  sites: NearbyChargingSite[]
  /** "fleet_api" | "cache" | "unsupported" */
  source: string | null
}

/** Outcome of POST /api/v1/cars/{carId}/commands/{command} (§6). */
export type VehicleCommandOutcome = {
  command: string | null
  /** "accepted" | "unverified" */
  result: string | null
  verified: boolean | null
}

/** One named place from /api/v1/cars/{carId}/places (geofence-lite, §7). */
export type VehiclePlace = {
  id: number
  name: string | null
  latitude: number | null
  longitude: number | null
  radiusM: number | null
}

export type RawStatusPayload = { status?: RawCarStatus | null; observed_at?: string | null; source?: string | null; units?: unknown; field_sources?: unknown }

/** Bounded provenance map projection, shared by HTTP parsing and cache reopening. */
export function statusFieldSources(value: unknown): Record<string, string> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  return Object.fromEntries(Object.entries(value).filter(([key, source]) =>
    /^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(key) && !['constructor', 'prototype', '__proto__'].includes(key) &&
    typeof source === 'string' && /^[a-z][a-z0-9_]{0,63}$/.test(source)).slice(0, 64))
}

export type RawTpmsDetails = {
  tpms_pressure_fl?: number | null; tpms_pressure_fr?: number | null
  tpms_pressure_rl?: number | null; tpms_pressure_rr?: number | null
  tpms_pressure_fl_observed_at?: string | null; tpms_pressure_fr_observed_at?: string | null
  tpms_pressure_rl_observed_at?: string | null; tpms_pressure_rr_observed_at?: string | null
  pressure_unit?: string | null; source?: string | null; time_basis?: string | null
  tpms_soft_warning_fl?: boolean | null; tpms_soft_warning_fr?: boolean | null
  tpms_soft_warning_rl?: boolean | null; tpms_soft_warning_rr?: boolean | null
  tpms_soft_warning_fl_observed_at?: string | null; tpms_soft_warning_fr_observed_at?: string | null
  tpms_soft_warning_rl_observed_at?: string | null; tpms_soft_warning_rr_observed_at?: string | null
  warning_source?: string | null; warning_time_basis?: string | null
}

export type ReadinessItem = {
  key: string
  status: string | null
  message: string | null
  action: string | null
  message_key?: string | null
  source?: string | null
  last_observed_at?: string | null
}

export type DataReadiness = {
  items: ReadinessItem[]
}

export type TelemetryPairing = {
  status: string | null
  virtual_key_url: string | null
  config_synced: boolean | null
  updated_at: string | null
  error_class: string | null
}

export type AuthUser = {
  id?: string | null
  link_required?: boolean | null
}

export type AuthSessionResponse = {
  status?: 'authenticated' | 'link_required' | string | null
  access_token?: string | null
  refresh_token?: string | null
  expires_in?: number | null
  expires_at?: string | null
  link_token?: string | null
  user?: AuthUser | null
  link_required?: boolean | null
}

export type TeslaAuthorization = {
  authorization_url: string
  transaction_id: string | null
  client_proof?: string | null
  expires_at: string | null
}

export type TeslaAuthorizationStatus = {
  status: 'pending' | 'ready' | 'consumed' | 'cancelled' | 'failed' | 'expired' | 'none' | string
  expiresAt: string | null
}

export type Drive = {
  startRatedRangeKm?: number | null
  endRatedRangeKm?: number | null
  speedAvgKmh?: number | null
  outsideTempC?: number | null
  id: string
  sessionId: string | null
  startDate: string | null
  endDate: string | null
  startAddress: string | null
  endAddress: string | null
  durationMinutes: number | null
  distanceKm: number | null
  energyConsumedKwh: number | null
  startBatteryLevel: number | null
  endBatteryLevel: number | null
  startLatitude: number | null
  startLongitude: number | null
  endLatitude: number | null
  endLongitude: number | null
  source: string | null
  sourceInstanceId?: string | null
  sourceVehicleId?: string | null
  sourceRecordId?: string | null
  qualityState: string | null
  qualityReason: string | null
  energy?: DriveEnergy
  energyReceipt?: DriveEnergyReceipt
  efficiencyWhKm?: number | null
  speedMaxKmh?: number | null
  speedSampleCount?: number | null
  speedMetricsSource?: string | null
  speedUnit?: string | null
}

export type RawDrive = {
  range_rated?: { start_range?: number | null; end_range?: number | null; range_diff?: number | null } | null
  speed_avg?: number | null
  outside_temp_avg?: number | null
  drive_id?: number | string | null
  session_id?: string | null
  source_instance_id?: string | null
  source_vehicle_id?: string | null
  source_record_id?: string | null
  source_id?: string | null
  start_date?: string | null
  end_date?: string | null
  start_address?: string | null
  end_address?: string | null
  duration_min?: number | null
  speed_max?: number | null
  speed_sample_count?: number | null
  speed_metrics_source?: string | null
  speed_unit?: string | null
  energy_consumed_net?: number | null
  consumption_net?: number | null
  energy_contract?: EnergyContract | null
  drive_details?: { date?: string | null; power?: number | null }[] | null
  start_latitude?: number | null
  start_longitude?: number | null
  end_latitude?: number | null
  end_longitude?: number | null
  source?: string | null
  quality_state?: string | null
  quality_reason?: string | null
  odometer_details?: {
    odometer_start?: number | null
    odometer_end?: number | null
    odometer_distance?: number | null
  } | null
  battery_details?: {
    start_battery_level?: number | null
    end_battery_level?: number | null
  } | null
}

export type Charge = {
  id: string
  sessionId: string | null
  startDate: string | null
  endDate: string | null
  address: string | null
  durationMinutes: number | null
  energyAddedKwh: number | null
  energyUsedKwh: number | null
  cost: number | null
  startBatteryLevel: number | null
  endBatteryLevel: number | null
  latitude: number | null
  longitude: number | null
  chargerPowerKw: number | null
  source: string | null
  sourceInstanceId?: string | null
  sourceVehicleId?: string | null
  sourceRecordId?: string | null
  currentBatteryLevel?: number | null
  isCharging?: boolean | null
  /** Live session readings are not qualified completed-history energy. */
  currentEnergyAddedKwh?: number | null
  currentObservedAt?: string | null
  currentIsSnapshot?: boolean
  currentVehicleIdentityVerified?: boolean
  qualityState: string | null
  qualityReason: string | null
  energy?: ChargeEnergy
  currency?: string | null
  currencyCode?: string | null
  chargeMode?: 'ac' | 'dc' | null
  /** Conflicting upstream AC/DC evidence; must survive cache and block mode-derived metrics. */
  chargeModeConflict?: boolean
  /** Exact provider charge_type, kept separate from the evidence-derived display mode. */
  reportedChargeMode?: string | null
}

export type RawCharge = {
  charge_id?: number | string | null
  session_id?: string | null
  source_instance_id?: string | null
  source_vehicle_id?: string | null
  source_record_id?: string | null
  source_id?: string | null
  current_battery_level?: number | null
  is_charging?: boolean | null
  observed_at?: string | null
  start_date?: string | null
  end_date?: string | null
  address?: string | null
  duration_min?: number | null
  charge_energy_added?: number | null
  charge_energy_used?: number | null
  cost?: number | null
  latitude?: number | null
  longitude?: number | null
  charger_power?: number | null
  energy_contract?: EnergyContract | null
  currency?: string | null
  currency_code?: string | null
  cost_currency?: string | null
  charge_type?: string | null
  fast_charger_present?: boolean | null
  charger_phases?: number | null
  source?: string | null
  quality_state?: string | null
  quality_reason?: string | null
  battery_details?: {
    start_battery_level?: number | null
    end_battery_level?: number | null
    current_battery_level?: number | null
  } | null
}

export type EnergyMetric = {
  value_kwh?: number | null; unit?: string | null; method?: string | null
  measurement_point?: string | null; source?: string | null; source_field?: string | null
  quality?: string | null; reason?: string | null
  start_date?: string | null; end_date?: string | null
  observed_start_at?: string | null; observed_end_at?: string | null; time_basis?: string | null
  coverage_kind?: string | null; coverage_seconds?: number | null; coverage_ratio?: number | null
  covered_energy_kwh?: number | null; activity_evidence?: string | null
  capacity_kwh?: number | null; capacity_basis?: string | null; capacity_source_field?: string | null
  capacity_model?: string | null; capacity_trim?: string | null; calculation_version?: number | null
  start_soc?: number | null; end_soc?: number | null
}

export type EnergyContract = {
  version?: number | null; net_energy?: EnergyMetric | null
  battery_input?: EnergyMetric | null; ac_input?: EnergyMetric | null; stored_change?: EnergyMetric | null
  ac_loss?: EnergyMetric | null; ac_efficiency?: number | null
  charge_mode?: string | null; charge_mode_evidence?: string | null
}

export type DriveEnergy = {
  energyKwh: number | null; efficiencyWhKm: number | null
  source: 'api' | 'power_samples' | 'energy_remaining_delta' | 'soc_capacity_estimate' | 'unavailable'
  quality: 'reported' | 'estimated' | 'unknown'; reason: string | null
  coverageRatio: number | null; coverageSeconds: number | null; observedEnergyKwh: number | null
  evidence: EnergyMetric | null
}

export type DrivePowerIntegral = {
  energyKwh: number | null; coverageSeconds: number; windowSeconds: number | null
  complete: boolean; reason: string; startDate: string | null; endDate: string | null
}

/** Bounded parsed input plus labeled integral; never original HTTP bytes or a stored route. */
export type DriveEnergyReceipt = Omit<RawDrive, 'drive_details'> & {
  parsedEnergyVersion?: 1; power_integral?: DrivePowerIntegral
  /** Response unit for the unchanged provider odometer value, not vehicle provenance. */
  distance_unit?: string | null
}

export type ChargeEnergy = {
  batteryInputKwh: number | null; acInputKwh: number | null; inputEnergyKwh: number | null
  quality: 'reported' | 'unknown'; reason: string | null; evidence: EnergyContract | null
  chargeMode: 'ac' | 'dc' | null
}

export function carSelectionStorageKey(userId: string | null): string {
  return `matelink.selected.car.v1.${userId || 'anonymous'}`
}

export function stableCarId(raw: RawCar): string | null {
  const value = raw.vehicle_uid ?? raw.provider_vehicle_id
  if (value === undefined || value === null || String(value).trim() === '') return null
  return String(value).trim()
}
