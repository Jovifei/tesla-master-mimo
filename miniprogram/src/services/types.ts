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
}

export type RawCar = {
  car_id?: number
  id?: number
  vehicle_uid?: string | null
  provider_vehicle_id?: string | null
  name?: string | null
  model?: string | null
  display_name?: string | null
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
}

export type RawDrive = {
  drive_id?: number | string | null
  session_id?: string | null
  start_date?: string | null
  end_date?: string | null
  start_address?: string | null
  end_address?: string | null
  duration_min?: number | null
  energy_consumed_net?: number | null
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
  source?: string | null
  quality_state?: string | null
  quality_reason?: string | null
  battery_details?: {
    start_battery_level?: number | null
    end_battery_level?: number | null
  } | null
}

export function carSelectionStorageKey(userId: string | null): string {
  return `matelink.selected.car.v1.${userId || 'anonymous'}`
}

export function stableCarId(raw: RawCar): string | null {
  const value = raw.vehicle_uid ?? raw.provider_vehicle_id
  if (value === undefined || value === null || String(value).trim() === '') return null
  return String(value).trim()
}
