import type { WebCar, WebCarStatus, WebDriveSummary, WebSession } from './matelink-types'

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
async function authedRequest(path: string): Promise<unknown> {
  let session = loadSession()
  if (!session) throw new ApiError('尚未登录。', 'session_required', 401)
  if (!sessionIsUsable(session, Date.now())) {
    session = await refreshSession()
  }
  try {
    return await request(path, { accessToken: session.accessToken })
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      const refreshed = await refreshSession()
      return request(path, { accessToken: refreshed.accessToken })
    }
    throw error
  }
}

/* ---------------- auth flow ---------------- */

export type TeslaAuthorization = { authorizationUrl: string; transactionId: string; expiresAt: string }

export async function startTeslaAuthorization(): Promise<TeslaAuthorization> {
  const payload = await request('/v1/auth/tesla/start', {
    headers: {
      'X-JourVolt-Terms-Version': TERMS_VERSION,
      'X-JourVolt-Privacy-Version': PRIVACY_VERSION,
    },
  })
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
  }
}

export function normalizeDrive(raw: Record<string, unknown>, distanceUnit: string): WebDriveSummary {
  const odometer = isRecord(raw.odometer_details) ? raw.odometer_details : {}
  const battery = isRecord(raw.battery_details) ? raw.battery_details : {}
  const distance = finite(odometer.odometer_distance, 0)
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
    speedMaxKmh: finite(raw.speed_max, 0),
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
  const payload = await authedRequest(`/api/v1/cars/${carId}/drives?page=1&show=${show}`)
  const data = unwrap(payload)
  const rows = Array.isArray(data.drives) ? data.drives : []
  const distanceUnit = unitOf(data.units, 'unit_of_length', 'km')
  return rows.filter(isRecord).map(raw => normalizeDrive(raw, distanceUnit))
}
