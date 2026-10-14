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
}

export type CarStatus = {
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
  }
  battery_details?: {
    battery_level?: number | null
    rated_battery_range?: number | null
  }
  car_geodata?: {
    latitude?: number | null
    longitude?: number | null
  }
  driving_details?: {
    shift_state?: string | null
    speed?: number | null
    power?: number | null
    heading?: number | null
  }
  climate_details?: {
    is_climate_on?: boolean | null
    inside_temp?: number | null
    outside_temp?: number | null
  }
  charging_details?: {
    plugged_in?: boolean | null
    charging_state?: string | null
    charge_energy_added?: number | null
    charger_power?: number | null
  }
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
  web_authorization_url?: string | null
  transaction_id: string | null
  client_proof?: string | null
  channel?: string | null
  expires_at: string | null
}

export type WechatAuthorizationStatus = {
  status: 'pending' | 'ready' | 'claimed' | 'failed' | 'expired' | 'none' | string
  expiresAt: string | null
}

export type Drive = {
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
  drive_id?: number | string | null
  session_id?: string | null
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
  qualityState: string | null
  qualityReason: string | null
  energy?: ChargeEnergy
  currency?: string | null
  currencyCode?: string | null
  chargeMode?: 'ac' | 'dc' | null
}

export type RawCharge = {
  charge_id?: number | string | null
  session_id?: string | null
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
