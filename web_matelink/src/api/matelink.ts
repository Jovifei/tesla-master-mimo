import type { WebCar, WebCarStatus, WebCharge, WebChargePoint, WebChargeSummary, WebChargingSite, WebCommandResult, WebDrivePoint, WebDriveSummary, WebParked, WebPlace, WebSession, WebTpms } from './matelink-types'

/**
 * MateLink production API client (web, W1).
 *
 * Contract sources (verified 2026-11-08 against the Go backend and
 * the miniprogram client):
 * - POST /v1/auth/exchange {ticket} -> {access_token, refresh_token,
 *   expires_in, user:{id}}; the ticket is a one-shot login ticket
 *   issued by the Tesla OAuth callback (2-minute lifetime).
 * - GET /v1/auth/tesla/start with X-JourVolt-Terms-Version and
 *   X-JourVolt-Privacy-Version consent headers ->
 *   {authorization_url, transaction_id, expires_at}.
 * - POST /v1/session/refresh {refresh_token} -> rotated session.
 * - POST /v1/session/logout (Bearer access token) -> revokes.
 * - GET /api/v1/cars, GET /api/v1/cars/{id}/status,
 *   GET /api/v1/cars/{id}/drives?page=&show= (Bearer access token).
 *
 * The backend sends no CORS headers, so the web app talks to the API
 * same-origin: in development the Vite proxy forwards these paths to
 * the production API; in production the static host must reverse
 * proxy them (see docs/WEB-PLAN-20261108.md). VITE_MATELINK_API_BASE
 * overrides the base when a deployment really is cross-origin.
 */

export const TERMS_VERSION = '2026-08-21'
export const PRIVACY_VERSION = '2026-08-21'

const viteEnv = (import.meta as unknown as { env?: Record<string, string | undefined> }).env ?? {}
export const API_BASE = viteEnv.VITE_MATELINK_API_BASE ?? ''

const SESSION_KEY = 'matelink-web-session-v1'

export class ApiError extends Error {
  code: string
  status: number
  constructor(message: string, code: string, status = 0) {
    super(message)
    this.code = code
    this.status = status
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function finite(value: unknown, min?: number, max?: number): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value)) return null
  if (min != null && value < min) return null
  if (max != null && value > max) return null
  return value
}

function stringValue(value: unknown): string | null {
  return typeof value === 'string' && value.trim() !== '' ? value : null
}

function boolValue(value: unknown): boolean | null {
  return typeof value === 'boolean' ? value : null
}

/** Responses arrive either bare or wrapped in a {data} envelope. */
function unwrap(payload: unknown): Record<string, unknown> {
  if (isRecord(payload) && isRecord(payload.data)) return payload.data
  return isRecord(payload) ? payload : {}
}

/* ---------------- session storage ---------------- */

export function loadSession(storage: Pick<Storage, 'getItem'> = localStorage): WebSession | null {
  try {
    const raw = storage.getItem(SESSION_KEY)
    if (!raw) return null
    const parsed: unknown = JSON.parse(raw)
    if (!isRecord(parsed)) return null
    const { accessToken, refreshToken, expiresAt, userId } = parsed
    if (typeof accessToken !== 'string' || typeof refreshToken !== 'string' || typeof expiresAt !== 'number' || typeof userId !== 'string') return null
    return { accessToken, refreshToken, expiresAt, userId }
  } catch {
    return null
  }
}

export function saveSession(session: WebSession, storage: Pick<Storage, 'setItem'> = localStorage): void {
  storage.setItem(SESSION_KEY, JSON.stringify(session))
}

export function clearSession(storage: Pick<Storage, 'removeItem'> = localStorage): void {
  storage.removeItem(SESSION_KEY)
}

/** Access token usable for at least `skewMs` longer. */
export function sessionIsUsable(session: WebSession | null, nowMs: number, skewMs = 30_000): boolean {
  return session != null && session.expiresAt - skewMs > nowMs
}

function toSession(payload: unknown, fallbackUserId = ''): WebSession {
  const data = unwrap(payload)
  const accessToken = stringValue(data.access_token)
  const refreshToken = stringValue(data.refresh_token)
  const expiresIn = finite(data.expires_in, 0)
  const user = isRecord(data.user) ? data.user : {}
  const userId = stringValue(user.id) ?? fallbackUserId
  if (!accessToken || !refreshToken || expiresIn == null) {
    throw new ApiError('登录响应不完整，请重试。', 'invalid_session_response')
  }
  return { accessToken, refreshToken, expiresAt: Date.now() + expiresIn * 1000, userId }
}

/* ---------------- HTTP ---------------- */

async function request(path: string, init: RequestInit & { accessToken?: string } = {}): Promise<unknown> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (init.accessToken) headers.Authorization = `Bearer ${init.accessToken}`
  let response: Response
  try {
    response = await fetch(`${API_BASE}${path}`, { ...init, headers: { ...headers, ...(init.headers ?? {}) } })
  } catch {
    throw new ApiError('无法连接服务器，请检查网络后重试。', 'network_error')
  }
  const text = await response.text()
  let payload: unknown = null
  try {
    payload = text ? JSON.parse(text) : null
  } catch {
    payload = null
  }
  if (!response.ok) {
    const code = isRecord(payload) && typeof payload.error === 'string' ? payload.error : `http_${response.status}`
    throw new ApiError(`请求失败（${response.status}）：${code}`, code, response.status)
  }
  return payload
}

/** Authenticated request with one transparent refresh-and-retry on 401. */
async function authedRequest(path: string, init: Omit<RequestInit, 'headers'> = {}): Promise<unknown> {
  let session = loadSession()
  if (!session) throw new ApiError('尚未登录。', 'session_required', 401)
  if (!sessionIsUsable(session, Date.now())) {
    session = await refreshSession()
  }
  try {
    return await request(path, { ...init, accessToken: session.accessToken })
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      const refreshed = await refreshSession()
      return request(path, { ...init, accessToken: refreshed.accessToken })
    }
    throw error
  }
}

/* ---------------- auth flow ---------------- */

export type TeslaAuthorization = { authorizationUrl: string; transactionId: string; expiresAt: string }

export async function startTeslaAuthorization(): Promise<TeslaAuthorization> {
  // Ask the backend to deliver the login ticket straight back to
  // this origin's /auth/complete route. The backend honors it only
  // when this exact URL is on its JOURVOLT_WEB_RETURN_ALLOWLIST
  // (production web origin and the localhost dev origin are); on any
  // other origin it answers invalid_return_to and we retry without
  // it, leaving the paste-to-complete fallback in charge.
  const returnTo = typeof window !== 'undefined' ? `${window.location.origin}/auth/complete` : ''
  const startOnce = (withReturn: boolean) =>
    request(`/v1/auth/tesla/start${withReturn && returnTo ? `?return_to=${encodeURIComponent(returnTo)}` : ''}`, {
      headers: {
        'X-JourVolt-Terms-Version': TERMS_VERSION,
        'X-JourVolt-Privacy-Version': PRIVACY_VERSION,
      },
    })
  let payload: unknown
  try {
    payload = await startOnce(true)
  } catch (error) {
    if (error instanceof ApiError && error.code === 'invalid_return_to') {
      payload = await startOnce(false)
    } else {
      throw error
    }
  }
  const data = unwrap(payload)
  const authorizationUrl = stringValue(data.authorization_url)
  const transactionId = stringValue(data.transaction_id)
  const expiresAt = stringValue(data.expires_at)
  if (!authorizationUrl || !transactionId || !expiresAt) {
    throw new ApiError('授权响应不完整，请重试。', 'invalid_authorization_response')
  }
  return { authorizationUrl, transactionId, expiresAt }
}

export async function exchangeTicket(ticket: string): Promise<WebSession> {
  const payload = await request('/v1/auth/exchange', {
    method: 'POST',
    body: JSON.stringify({ ticket }),
  })
  const session = toSession(payload)
  saveSession(session)
  return session
}

export async function refreshSession(): Promise<WebSession> {
  const current = loadSession()
  if (!current) throw new ApiError('尚未登录。', 'session_required', 401)
  try {
    const payload = await request('/v1/session/refresh', {
      method: 'POST',
      body: JSON.stringify({ refresh_token: current.refreshToken }),
    })
    const session = toSession(payload, current.userId)
    saveSession(session)
    return session
  } catch (error) {
    clearSession()
    throw error
  }
}

export async function logout(): Promise<void> {
  const session = loadSession()
  clearSession()
  if (!session) return
  try {
    await request('/v1/session/logout', { method: 'POST', accessToken: session.accessToken })
  } catch {
    /* The local session is already gone; a failed revoke is retried never. */
  }
}

/**
 * Extract a login ticket from whatever the user brings back: a bare
 * ticket, or the full callback URL they landed on
 * (https://auth.teslalink.joviluma.com/oauth/callback?ticket=...).
 * Returns null when the text carries an error or no ticket.
 */
export function extractTicket(input: string): string | null {
  const text = input.trim()
  if (!text) return null
  if (!text.includes('://') && !text.includes('ticket=')) {
    return /^[A-Za-z0-9._~-]+$/.test(text) ? text : null
  }
  try {
    const url = new URL(text.includes('://') ? text : `https://placeholder.invalid/${text}`)
    if (url.searchParams.get('error')) return null
    const ticket = url.searchParams.get('ticket')
    return ticket && ticket.trim() !== '' ? ticket : null
  } catch {
    const match = text.match(/[?&]ticket=([^&#\s]+)/)
    if (!match) return null
    try {
      const ticket = decodeURIComponent(match[1])
      return ticket.trim() !== '' ? ticket : null
    } catch {
      return null
    }
  }
}

/* ---------------- data ---------------- */

export function normalizeCar(raw: Record<string, unknown>): WebCar | null {
  const idValue = finite(raw.car_id) ?? finite(raw.id)
  if (idValue == null) return null
  const carDetails = isRecord(raw.car_details) ? raw.car_details : {}
  return {
    id: idValue,
    stableId: stringValue(raw.vehicle_uid),
    name: stringValue(raw.display_name) ?? stringValue(raw.name) ?? '我的特斯拉',
    model: stringValue(raw.model) ?? stringValue(carDetails.model),
  }
}

function unitOf(units: unknown, key: string, fallback: string): string {
  if (isRecord(units) && typeof units[key] === 'string') return units[key] as string
  return fallback
}

export function normalizeStatus(payload: unknown): WebCarStatus {
  const data = unwrap(payload)
  const raw = isRecord(data.status) ? data.status : {}
  const length = unitOf(data.units, 'unit_of_length', 'km')
  const temperature = unitOf(data.units, 'unit_of_temperature', 'C')
  const km = (value: unknown): number | null => {
    const scalar = finite(value, 0)
    return scalar == null ? null : scalar * (length === 'mi' ? 1.609344 : 1)
  }
  const celsius = (value: unknown): number | null => {
    const scalar = finite(value)
    return scalar == null ? null : temperature === 'F' ? ((scalar - 32) * 5) / 9 : scalar
  }
  const battery = isRecord(raw.battery_details) ? raw.battery_details : {}
  const carStatus = isRecord(raw.car_status) ? raw.car_status : {}
  const charging = isRecord(raw.charging_details) ? raw.charging_details : {}
  const climate = isRecord(raw.climate_details) ? raw.climate_details : {}
  const geodata = isRecord(raw.car_geodata) ? raw.car_geodata : {}
  const latitude = finite(geodata.latitude, -90, 90)
  const longitude = finite(geodata.longitude, -180, 180)
  const hasLocation = latitude != null && longitude != null && (latitude !== 0 || longitude !== 0)
  return {
    state: stringValue(raw.state),
    batteryLevel: finite(battery.battery_level, 0, 100),
    ratedRangeKm: km(battery.rated_battery_range),
    estimatedRangeKm: km(battery.est_battery_range),
    odometerKm: km(raw.odometer),
    locked: boolValue(carStatus.locked),
    sentryMode: boolValue(carStatus.sentry_mode),
    windowsOpen: boolValue(carStatus.windows_open),
    chargingState: stringValue(charging.charging_state),
    chargeEnergyAddedKwh: finite(charging.charge_energy_added, 0),
    chargerPowerKw: finite(charging.charger_power, 0),
    insideTempC: celsius(climate.inside_temp),
    outsideTempC: celsius(climate.outside_temp),
    latitude: hasLocation ? latitude : null,
    longitude: hasLocation ? longitude : null,
    geofence: stringValue(geodata.geofence)?.slice(0, 256) ?? null,
    observedAt: stringValue(data.observed_at),
    source: stringValue(data.source),
    driverTempC: celsius(climate.driver_temp_setting),
    tpms: parseTpmsReport(raw.tpms_details, data.units),
    commandAuthorization: isRecord(raw.command_authorization)
      ? {
          ready: boolValue(raw.command_authorization.ready),
          missingScopes: Array.isArray(raw.command_authorization.missing_scopes)
            ? raw.command_authorization.missing_scopes.filter((scope): scope is string => typeof scope === 'string')
            : null,
        }
      : null,
  }
}

function speedToKmh(value: unknown, speedUnit: string | null, distanceUnit: string): number | null {
  const scalar = finite(value, 0)
  if (scalar == null) return null
  if (speedUnit === 'mph' || (speedUnit == null && distanceUnit === 'mi')) return scalar * 1.609344
  return scalar
}

function tempToC(value: unknown, temperatureUnit: string): number | null {
  const scalar = finite(value)
  if (scalar == null) return null
  return temperatureUnit === 'F' ? ((scalar - 32) * 5) / 9 : scalar
}

export function normalizeDrive(raw: Record<string, unknown>, distanceUnit: string, temperatureUnit = 'C'): WebDriveSummary {
  const odometer = isRecord(raw.odometer_details) ? raw.odometer_details : {}
  const battery = isRecord(raw.battery_details) ? raw.battery_details : {}
  const distance = finite(odometer.odometer_distance, 0)
  const speedUnit = stringValue(raw.speed_unit)
  return {
    id: String(raw.drive_id ?? raw.session_id ?? ''),
    startDate: stringValue(raw.start_date),
    endDate: stringValue(raw.end_date),
    startAddress: stringValue(raw.start_address),
    endAddress: stringValue(raw.end_address),
    distanceKm: distance == null ? null : distance * (distanceUnit === 'mi' ? 1.609344 : 1),
    durationMinutes: finite(raw.duration_min, 0),
    startBatteryLevel: finite(battery.start_battery_level, 0, 100),
    endBatteryLevel: finite(battery.end_battery_level, 0, 100),
    speedMaxKmh: speedToKmh(raw.speed_max, speedUnit, distanceUnit),
    speedAvgKmh: speedToKmh(raw.speed_avg, speedUnit, distanceUnit),
    outsideTempAvgC: tempToC(raw.outside_temp_avg, temperatureUnit),
    energyConsumedKwh: finite(raw.energy_consumed_net),
    consumptionWhPerKm: finite(raw.consumption_net, 0),
    qualityState: stringValue(raw.quality_state),
  }
}

export async function getCars(): Promise<WebCar[]> {
  const payload = await authedRequest('/api/v1/cars')
  const data = unwrap(payload)
  const rows = Array.isArray(data.cars) ? data.cars : []
  return rows.filter(isRecord).map(normalizeCar).filter((car): car is WebCar => car !== null)
}

export async function getCarStatus(carId: number): Promise<WebCarStatus> {
  const payload = await authedRequest(`/api/v1/cars/${carId}/status`)
  return normalizeStatus(payload)
}

export async function getRecentDrives(carId: number, show = 5): Promise<WebDriveSummary[]> {
  const page = await getDrivesPage(carId, 1, show)
  return page.rows
}

export type HistoryPage<T> = { rows: T[]; totalPages: number }

export async function getDrivesPage(carId: number, page: number, show = 20): Promise<HistoryPage<WebDriveSummary>> {
  const payload = await authedRequest(`/api/v1/cars/${carId}/drives?page=${page}&show=${show}`)
  const data = unwrap(payload)
  const rows = Array.isArray(data.drives) ? data.drives : []
  const meta = isRecord(data.meta) ? data.meta : {}
  const distanceUnit = unitOf(data.units, 'unit_of_length', 'km')
  const temperatureUnit = unitOf(data.units, 'unit_of_temperature', 'C')
  return {
    rows: rows.filter(isRecord).map(raw => normalizeDrive(raw, distanceUnit, temperatureUnit)),
    totalPages: finite(meta.total_pages, 0) ?? page,
  }
}

/* ---------------- charges (W2) ---------------- */

function chargeModeOf(raw: Record<string, unknown>): 'ac' | 'dc' | null {
  const mode = stringValue(raw.charge_type)?.toLowerCase()
  if (mode === 'ac') return raw.fast_charger_present === true ? null : 'ac'
  if (mode === 'dc') return 'dc'
  return mode == null && raw.fast_charger_present === true ? 'dc' : null
}

function chargeCurrencyOf(raw: Record<string, unknown>): string | null {
  return stringValue(raw.currency) ?? stringValue(raw.currency_code) ?? stringValue(raw.cost_currency)
}

export function normalizeCharge(raw: Record<string, unknown>): WebCharge {
  const battery = isRecord(raw.battery_details) ? raw.battery_details : {}
  return {
    id: String(raw.charge_id ?? raw.session_id ?? ''),
    startDate: stringValue(raw.start_date),
    endDate: stringValue(raw.end_date),
    address: stringValue(raw.address),
    durationMinutes: finite(raw.duration_min, 0),
    energyAddedKwh: finite(raw.charge_energy_added, 0),
    cost: finite(raw.cost, 0),
    currency: chargeCurrencyOf(raw),
    chargeMode: chargeModeOf(raw),
    maxPowerKw: finite(raw.charger_power, 0),
    startBatteryLevel: finite(battery.start_battery_level, 0, 100),
    endBatteryLevel: finite(battery.end_battery_level, 0, 100),
    isCharging: boolValue(raw.is_charging),
    qualityState: stringValue(raw.quality_state),
  }
}

export async function getChargesPage(carId: number, page: number, show = 20): Promise<HistoryPage<WebCharge>> {
  const payload = await authedRequest(`/api/v1/cars/${carId}/charges?page=${page}&show=${show}`)
  const data = unwrap(payload)
  const rows = Array.isArray(data.charges) ? data.charges : []
  const meta = isRecord(data.meta) ? data.meta : {}
  return {
    rows: rows.filter(isRecord).map(normalizeCharge),
    totalPages: finite(meta.total_pages, 0) ?? page,
  }
}

export type ChargeDetail = {
  charge: WebCharge
  points: WebChargePoint[]
  summary: WebChargeSummary
  /** 'detail_unavailable' | 'quarantined_history' | 'charge_samples_unavailable' | 'response_samples_only' | ... */
  reason: string
}

export function parseChargeDetail(payload: unknown): ChargeDetail {
  const data = unwrap(payload)
  const rawCharge = isRecord(data.charge) ? data.charge : null
  if (!rawCharge) {
    return {
      charge: normalizeCharge({}),
      points: [],
      summary: { chargerPowerKw: null, chargerVoltageV: null, currentA: null, phases: null, timeToFullHours: null, outsideTempC: null, isCharging: null },
      reason: 'detail_unavailable',
    }
  }
  const charge = normalizeCharge(rawCharge)
  const temperatureUnit = unitOf(data.units, 'unit_of_temperature', 'C')
  const phases = finite(rawCharge.charger_phases, 0, 3)
  const summary: WebChargeSummary = {
    chargerPowerKw: finite(rawCharge.charger_power),
    chargerVoltageV: finite(rawCharge.charger_voltage, 0),
    currentA: finite(rawCharge.charger_actual_current),
    phases: phases != null && Number.isInteger(phases) ? phases : null,
    timeToFullHours: finite(rawCharge.time_to_full_charge, 0),
    outsideTempC: tempToC(rawCharge.outside_temp_avg, temperatureUnit),
    isCharging: boolValue(rawCharge.is_charging),
  }
  if (charge.qualityState === 'quarantined') {
    return { charge, points: [], summary, reason: 'quarantined_history' }
  }
  const samples = Array.isArray(rawCharge.charge_details) ? rawCharge.charge_details : null
  if (!samples) return { charge, points: [], summary, reason: 'charge_samples_unavailable' }
  const start = Date.parse(charge.startDate ?? '')
  const ongoing = rawCharge.end_date == null && rawCharge.is_charging === true
  const end = ongoing ? Date.now() : Date.parse(charge.endDate ?? '')
  const points: WebChargePoint[] = []
  for (const value of samples) {
    if (!isRecord(value)) continue
    const date = stringValue(value.date)
    if (!date) continue
    const at = Date.parse(date)
    if (!Number.isFinite(at) || !Number.isFinite(start) || !Number.isFinite(end) || at < start || at > end) continue
    const charger = isRecord(value.charger_details) ? value.charger_details : {}
    points.push({
      date,
      batteryLevel: finite(value.battery_level, 0, 100),
      powerKw: finite(charger.charger_power),
      voltageV: finite(charger.charger_voltage, 0),
      currentA: finite(charger.charger_actual_current),
      temperatureC: tempToC(value.outside_temp, temperatureUnit),
    })
  }
  points.sort((a, b) => Date.parse(a.date) - Date.parse(b.date))
  return { charge, points, summary, reason: ongoing ? 'ongoing_response_samples_only' : 'response_samples_only' }
}

export async function getChargeDetail(carId: number, chargeId: string): Promise<ChargeDetail> {
  const payload = await authedRequest(`/api/v1/cars/${carId}/charges/${encodeURIComponent(chargeId)}`)
  return parseChargeDetail(payload)
}

/* ---------------- drive detail & parked (W2) ---------------- */

export type DriveDetail = {
  drive: WebDriveSummary
  points: WebDrivePoint[]
  reason: string
}

export function parseDriveDetail(payload: unknown): DriveDetail {
  const data = unwrap(payload)
  const rawDrive = isRecord(data.drive) ? data.drive : null
  const distanceUnit = unitOf(data.units, 'unit_of_length', 'km')
  const temperatureUnit = unitOf(data.units, 'unit_of_temperature', 'C')
  if (!rawDrive) {
    return { drive: normalizeDrive({}, distanceUnit, temperatureUnit), points: [], reason: 'detail_unavailable' }
  }
  const drive = normalizeDrive(rawDrive, distanceUnit, temperatureUnit)
  if (drive.qualityState === 'quarantined') {
    return { drive, points: [], reason: 'quarantined_history' }
  }
  const samples = Array.isArray(rawDrive.drive_details) ? rawDrive.drive_details : null
  if (!samples) return { drive, points: [], reason: 'route_samples_unavailable' }
  const start = Date.parse(drive.startDate ?? '')
  const end = Date.parse(drive.endDate ?? '')
  const speedUnit = stringValue(rawDrive.speed_unit)
  const points: WebDrivePoint[] = []
  for (const value of samples) {
    if (!isRecord(value)) continue
    const date = stringValue(value.date)
    if (!date) continue
    const at = Date.parse(date)
    if (!Number.isFinite(at) || !Number.isFinite(start) || !Number.isFinite(end) || at < start || at > end) continue
    points.push({
      date,
      speedKmh: speedToKmh(value.speed, speedUnit, distanceUnit),
      powerKw: finite(value.power),
      batteryLevel: finite(value.battery_level, 0, 100),
      elevationM: finite(value.elevation, -500, 10000),
    })
  }
  points.sort((a, b) => Date.parse(a.date) - Date.parse(b.date))
  return { drive, points, reason: 'response_samples_only' }
}

export async function getDriveDetail(carId: number, driveId: string): Promise<DriveDetail> {
  const payload = await authedRequest(`/api/v1/cars/${carId}/drives/${encodeURIComponent(driveId)}`)
  return parseDriveDetail(payload)
}

function sameInstant(a: string | null, b: string | null): boolean {
  if (!a || !b) return false
  const ta = Date.parse(a)
  const tb = Date.parse(b)
  return Number.isFinite(ta) && ta === tb
}

/** Parked window between two drives, with the miniprogram's strict energy-proof qualification. */
export function parseParked(payload: unknown): WebParked | null {
  const data = unwrap(payload)
  if (data.older_drive_id == null && data.newer_drive_id == null) return null
  const startDate = stringValue(data.start_date)
  const endDate = stringValue(data.end_date)
  const proof = isRecord(data.energy_contract) ? data.energy_contract : {}
  const stored = isRecord(proof.stored_change) ? proof.stored_change : {}
  const value = finite(stored.value_kwh)
  const ratio = finite(stored.coverage_ratio)
  const seconds = startDate && endDate ? (Date.parse(endDate) - Date.parse(startDate)) / 1000 : NaN
  const source = stringValue(data.source)
  const version = proof.version === undefined ? 1 : proof.version
  const valid =
    version === 1 &&
    value != null &&
    data.linked_charge == null &&
    (source === 'telemetry_mqtt' || source === 'fleet_telemetry') &&
    stored.source === source &&
    stored.source_field === 'EnergyRemaining' &&
    stored.method === 'energy_remaining_delta' &&
    stored.measurement_point === 'nominal_battery_remaining' &&
    stored.quality === 'estimated' &&
    (stored.unit === undefined || stored.unit === 'kWh') &&
    stored.coverage_kind === 'endpoints' &&
    ratio != null && ratio >= 1 - 1e-9 && ratio <= 1 &&
    stored.activity_evidence === 'no_driving_or_charging_during_window' &&
    sameInstant(startDate, stringValue(stored.start_date)) &&
    sameInstant(endDate, stringValue(stored.end_date)) &&
    sameInstant(startDate, stringValue(data.observed_start_at)) &&
    sameInstant(endDate, stringValue(data.observed_end_at)) &&
    Number.isFinite(seconds) && seconds > 0
  const energyKwh = valid ? value : null
  const temperatureUnit = unitOf(data.units, 'unit_of_temperature', 'C')
  return {
    startDate,
    endDate,
    address: stringValue(data.address),
    startBatteryLevel: finite(data.start_battery_level, 0, 100),
    endBatteryLevel: finite(data.end_battery_level, 0, 100),
    batteryDelta: finite(data.battery_delta, -100, 100),
    energyKwh,
    averagePowerW: energyKwh != null && Number.isFinite(seconds) && seconds > 0 ? (energyKwh * 3_600_000) / seconds : null,
    outsideTempC: tempToC(data.outside_temp_average, temperatureUnit),
    insideTempC: tempToC(data.inside_temp_average, temperatureUnit),
  }
}

export async function getParkedBetween(carId: number, olderDriveId: string, newerDriveId: string): Promise<WebParked | null> {
  try {
    const payload = await authedRequest(`/api/matelink/v1/cars/${carId}/parked/${olderDriveId}/${newerDriveId}`)
    return parseParked(payload)
  } catch (error) {
    if (error instanceof ApiError && error.code === 'parked_history_bounds_unavailable') return null
    throw error
  }
}

/**
 * Display-only TPMS report, ported from the miniprogram's
 * parseTpmsReport: values count only when the unit is explicitly
 * bar (from tpms_details.pressure_unit or the payload's
 * unit_of_pressure) and each pressure is a plausible 0-15 bar.
 */
export function parseTpmsReport(tpmsDetails: unknown, unitsRaw: unknown): WebTpms | null {
  if (!isRecord(tpmsDetails)) return null
  const units = isRecord(unitsRaw) ? unitsRaw : {}
  const unit = stringValue(tpmsDetails.pressure_unit) ?? stringValue(units.unit_of_pressure)
  const wheel = (key: string): number | null => {
    const value = tpmsDetails[`tpms_pressure_${key}`]
    return unit === 'bar' && typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 15 ? value : null
  }
  const at = (key: string): string | null => stringValue(tpmsDetails[`tpms_pressure_${key}_observed_at`])
  return { fl: wheel('fl'), fr: wheel('fr'), rl: wheel('rl'), rr: wheel('rr'), observedAt: { fl: at('fl'), fr: at('fr'), rl: at('rl'), rr: at('rr') } }
}

/* ---------------- nearby charging, places, commands (W3) ---------------- */

export type NearbyCharging = { sites: WebChargingSite[]; source: string | null }

export async function getNearbyChargingSites(carId: number): Promise<NearbyCharging> {
  const payload = await authedRequest(`/api/v1/cars/${carId}/nearby-charging-sites`)
  const data = unwrap(payload)
  const rows = Array.isArray(data.sites) ? data.sites : []
  const meta = isRecord(data.meta) ? data.meta : {}
  return {
    sites: rows.filter(isRecord).map(site => ({
      name: stringValue(site.name),
      type: stringValue(site.type),
      latitude: finite(site.latitude, -90, 90),
      longitude: finite(site.longitude, -180, 180),
      distanceKm: finite(site.distance_km, 0),
      availableStalls: finite(site.available_stalls, 0),
      totalStalls: finite(site.total_stalls, 0),
      siteClosed: boolValue(site.site_closed),
      priceText: stringValue(site.price_text),
      parkingPolicy: stringValue(site.parking_policy),
    })),
    source: stringValue(meta.source),
  }
}

function normalizePlace(raw: Record<string, unknown>): WebPlace {
  return {
    id: finite(raw.id, 1),
    name: stringValue(raw.name),
    latitude: finite(raw.latitude, -90, 90),
    longitude: finite(raw.longitude, -180, 180),
    radiusM: finite(raw.radius_m, 1),
  }
}

export async function getVehiclePlaces(carId: number): Promise<WebPlace[]> {
  const payload = await authedRequest(`/api/v1/cars/${carId}/places`)
  const data = unwrap(payload)
  const rows = Array.isArray(data.places) ? data.places : []
  return rows.filter(isRecord).map(normalizePlace)
}

export async function createVehiclePlace(
  carId: number,
  place: { name: string; latitude: number; longitude: number; radius_m: number }
): Promise<WebPlace> {
  const payload = await authedRequest(`/api/v1/cars/${carId}/places`, {
    method: 'POST',
    body: JSON.stringify(place),
  })
  return normalizePlace(unwrap(payload))
}

export async function deleteVehiclePlace(carId: number, placeId: number): Promise<boolean> {
  const payload = await authedRequest(`/api/v1/cars/${carId}/places/${placeId}`, { method: 'DELETE' })
  return Boolean(unwrap(payload).deleted)
}

/** Page through the full history for analysis; capped, with the cap reported honestly. */
export async function getAllHistory(
  carId: number
): Promise<{ drives: WebDriveSummary[]; charges: WebCharge[]; complete: boolean }> {
  const MAX_PAGES = 30
  const drives: WebDriveSummary[] = []
  const charges: WebCharge[] = []
  let complete = true
  for (let page = 1; page <= MAX_PAGES; page++) {
    const result = await getDrivesPage(carId, page)
    drives.push(...result.rows)
    if (page >= result.totalPages) break
    if (page === MAX_PAGES) complete = false
  }
  for (let page = 1; page <= MAX_PAGES; page++) {
    const result = await getChargesPage(carId, page)
    charges.push(...result.rows)
    if (page >= result.totalPages) break
    if (page === MAX_PAGES) complete = false
  }
  return { drives, charges, complete }
}

export async function claimInvite(code: string): Promise<Record<string, unknown>> {
  const payload = await authedRequest('/v1/me/invite/claim', {
    method: 'POST',
    body: JSON.stringify({ code }),
  })
  return unwrap(payload)
}

export async function executeVehicleCommand(
  carId: number,
  command: string,
  params?: Record<string, unknown>,
  confirm = false
): Promise<WebCommandResult> {
  const payload = await authedRequest(`/api/v1/cars/${carId}/commands/${encodeURIComponent(command)}`, {
    method: 'POST',
    body: JSON.stringify({ params: params ?? null, confirm }),
  })
  const data = unwrap(payload)
  return {
    command: stringValue(data.command) ?? command,
    result: stringValue(data.result),
    verified: boolValue(data.verified),
  }
}
