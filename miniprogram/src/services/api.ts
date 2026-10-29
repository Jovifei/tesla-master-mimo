import Taro from '@tarojs/taro'
import { JOURVOLT_PRIVACY_VERSION, JOURVOLT_TERMS_VERSION } from '../domain/legal'
import { parseTpmsDetails, parseTpmsReport } from '../domain/tpms'
import { statusFieldSources, type RawStatusPayload } from './types'
import { clearHistoryCachesForAccount } from './history_cache'
import { clearCarsCacheForAccount, writeCarsCache } from './cars_cache'
import { clearVehicleCachesForAccount } from './vehicle_cache'
import { clearNewAccountCaches } from './account_cache_cleanup'
import { driveDistanceKm, driveEnergyReceipt, resolveDriveEnergy, resolveChargeEnergy } from '../domain/drive_energy'
import { MOCK_FIXTURE_ENABLED, mockRequest } from './mock_fixture'
import type {
  ApiEnvelope,
  AuthSessionResponse,
  Car,
  CarStatus,
  Charge,
  DataReadiness,
  Drive,
  HistoryPage,
  PageMeta,
  RawCar,
  RawCarStatus,
  RawCharge,
  RawDrive,
  TeslaAuthorization,
  TelemetryPairing,
  TeslaAuthorizationStatus,
} from './types'
import {
  clearAppSession,
  clearPendingTeslaAuthorization,
  clearPendingWechatLink,
  clearWechatRecoveryPreference,
  readAppSession,
  readPendingTeslaAuthorization,
  readWechatRecoveryPreference,
  writeAppSession,
  writePendingTeslaAuthorization,
  writeWechatRecoveryPreference,
  type AppSession,
} from './session'

const apiBaseUrl = process.env.TARO_APP_API_BASE_URL ?? ''
const DEFAULT_PAGE_SIZE = 20

const errorMessages: Record<string, string> = {
  api_not_configured: '服务地址尚未配置',
  session_required: '请先完成特斯拉账号登录',
  consent_required: '请先同意服务条款和隐私指引',
  invalid_session_response: '服务端未返回有效会话',
  invalid_account_delete_response: '服务端未确认账号注销，请稍后重试',
  session_changed: '会话已切换，请重新读取数据',
  network_error: '网络暂时不可用，请稍后重试',
  invalid_api_response: '服务返回的数据无效，请稍后重试',
  telemetry_not_configured: '持续采集尚未配置',
  pairing_required: '请在 Tesla 官方 App 中确认车辆钥匙',
  permission_required: '请返回 Tesla 官方页面补充车辆权限',
  billing_blocked: '服务端报告了计费或权限阻断',
  telemetry_error: '持续采集暂时异常，请稍后重试',
  tesla_reauthorization_required: '请重新完成 Tesla 官方授权',
  tesla_temporarily_unavailable: 'Tesla 服务暂时不可用，请稍后重试',
  provider_not_configured: '服务端尚未配置 Tesla 数据源',
  upstream_unavailable: '上游车辆服务暂时不可用，请稍后重试',
  vehicle_not_found: '当前账号没有这辆车的访问权限',
  history_unavailable: '历史数据暂时不可用，请稍后重试',
  authorization_pending: 'Tesla 授权尚未返回，请完成官方页面后再检查',
  authorization_expired: 'Tesla 授权事务已过期，请重新开始授权',
  authorization_failed: 'Tesla 授权未完成，请重新开始授权',
  authorization_consumed: '本次授权已领取，请重新进行特斯拉账号登录',
  authorization_cancelled: 'Tesla 授权已取消，请重新开始授权',
  invalid_authorization_transaction: '授权事务无效，请重新进行特斯拉账号登录',
  account_delete_failed: '账号注销失败，请稍后重试',
  wechat_not_bound: '当前微信尚未绑定，请先登录特斯拉账号',
  wechat_code_invalid: '微信验证已失效，请重试',
  wechat_binding_conflict: '微信或当前账号已绑定其他账号，请先在原账号解绑',
  wechat_bind_expired: '绑定操作已取消，请重新确认',
  wechat_not_configured: '微信快捷进入暂不可用，仍可使用特斯拉账号登录',
  wechat_provider_unavailable: '微信验证暂不可用，请稍后重试',
  tesla_unavailable: 'Tesla 服务暂不可用，请稍后重试',
  wechat_service_failed: '微信快捷进入暂不可用，请稍后重试',
  wechat_recovery_storage_failed: '本机快捷进入设置无法保存，请检查微信存储后重试',
  wechat_account_changed: '当前微信关联的账号已变化，请先使用特斯拉账号登录确认',
  wechat_mock_unavailable: '合成数据模式下无法操作微信绑定',
}

export type ApiErrorOptions = {
  status?: number | null
  code?: string
  messageKey?: string | null
  retryAfter?: string | null
  cause?: unknown
}

export class ApiError extends Error {
  readonly status: number | null
  readonly code: string
  readonly messageKey: string | null
  readonly retryAfter: string | null

  constructor(message: string, options: ApiErrorOptions = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = options.status ?? null
    this.code = options.code ?? 'api_error'
    this.messageKey = options.messageKey ?? null
    this.retryAfter = options.retryAfter ?? null
    if (options.cause !== undefined) this.cause = options.cause
  }

  readonly cause?: unknown
}

export class ApiConfigurationError extends ApiError {
  constructor() {
    super(errorMessages.api_not_configured, {
      code: 'api_not_configured',
      messageKey: 'api_not_configured',
    })
    this.name = 'ApiConfigurationError'
  }
}

type UnknownRecord = Record<string, unknown>

function isRecord(value: unknown): value is UnknownRecord {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function requireApiBaseUrl(): string {
  const parsed = parseHttpsURL(apiBaseUrl)
  if (!parsed || parsed.path !== '/' || /[?#]/.test(apiBaseUrl)) throw new ApiConfigurationError()
  return apiBaseUrl.replace(/\/$/, '')
}

export function getApiOrigin(): string {
  return requireApiBaseUrl()
}

export function isTrustedAuthorizationURL(raw: string): boolean {
  const parsed = parseHttpsURL(raw)
  if (!parsed) return false
  const tesla = parsed.hostname === 'auth.tesla.cn' || parsed.hostname === 'auth.tesla.com'
  if (tesla) return parsed.path === '/oauth2/v3/authorize' || parsed.path === '/authorize' || parsed.path === '/user/revoke/consent'
  return false
}

type ParsedHttpsURL = { hostname: string; path: string }

function parseHttpsURL(raw: string): ParsedHttpsURL | null {
  if (typeof raw !== 'string' || /[\s\u0000-\u001f\u007f\\]/.test(raw) || !raw.startsWith('https://')) return null
  const rest = raw.slice('https://'.length)
  const authorityEnd = rest.search(/[/?#]/)
  const authority = authorityEnd < 0 ? rest : rest.slice(0, authorityEnd)
  if (!authority || authority.includes('@') || authority.includes('\\') || authority.includes(' ')) return null
  const portIndex = authority.lastIndexOf(':')
  const hostname = (portIndex >= 0 ? authority.slice(0, portIndex) : authority).toLowerCase()
  const port = portIndex >= 0 ? authority.slice(portIndex + 1) : ''
  if (!hostname || hostname.length > 253 || (portIndex >= 0 && !port) || (port && port !== '443') ||
      !hostname.split('.').every(label => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label))) return null
  if (/^0x[0-9a-f]+$/i.test(hostname) || (/^[\d.]+$/.test(hostname) &&
      (hostname.split('.').length !== 4 || !hostname.split('.').every(part => /^(0|[1-9]\d{0,2})$/.test(part) && Number(part) <= 255)))) return null
  const pathStart = authorityEnd < 0 ? rest.length : authorityEnd
  const suffix = rest.slice(pathStart)
  const path = suffix.split(/[?#]/)[0] || '/'
  return { hostname, path }
}

function errorBody(value: unknown): UnknownRecord {
  if (!isRecord(value)) return {}
  const nested = isRecord(value.data) ? value.data : null
  return nested && (nested.error !== undefined || nested.code !== undefined) ? nested : value
}

function stringValue(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null
}

function finite(value: unknown, min = -Infinity, max = Infinity): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max ? value : null
}
function bool(value: unknown): boolean | null { return typeof value === 'boolean' ? value : null }
function chargeCurrency(raw: RawCharge): string | null | undefined {
  if (!('cost_currency' in raw) && !('currency_code' in raw) && !('currency' in raw)) return undefined
  const supplied = [raw.cost_currency, raw.currency_code, raw.currency].filter(value => value != null)
    .map(value => stringValue(value)?.toUpperCase())
  if (supplied.some(value => !value) || new Set(supplied).size > 1) return null
  const code = stringValue(raw.cost_currency ?? raw.currency_code ?? raw.currency)?.toUpperCase()
  return code && ['CNY', 'USD', 'EUR', 'GBP', 'HKD', 'JPY', 'CAD', 'AUD', 'NZD', 'KRW', 'SGD', 'TWD', 'CHF',
    'SEK', 'NOK', 'DKK', 'PLN', 'CZK', 'HUF', 'INR', 'THB', 'AED', 'BRL', 'MXN', 'ZAR'].includes(code) ? code : null
}
function chargeMode(raw: RawCharge): 'ac' | 'dc' | null {
  const mode = stringValue(raw.charge_type)?.toLowerCase()
  if (mode === 'ac') return raw.fast_charger_present === true ? null : 'ac'
  if (mode === 'dc') return 'dc'
  return mode == null && raw.fast_charger_present === true ? 'dc' : null
}

export function assertVehicle(raw: unknown, carId: number, car?: Car): void {
  if (!Number.isSafeInteger(carId) || carId <= 0 || (car && (car.id !== carId || car.remoteVerified === false)))
    throw new ApiError(errorMessages.vehicle_not_found, { code: 'vehicle_not_found' })
  if (car?.remoteVerified === true && (verifiedCarsScope !== currentCarsScope() || verifiedCars.get(carId) !== car.stableId))
    throw new ApiError(errorMessages.vehicle_not_found, { code: 'vehicle_not_found' })
  if (!isRecord(raw)) return
  const id = raw.car_id
  const uid = raw.vehicle_uid ?? raw.provider_vehicle_id
  if ((id != null && String(id) !== String(carId)) ||
      (uid != null && car && String(uid) !== car.stableId))
    throw new ApiError(errorMessages.vehicle_not_found, { code: 'vehicle_not_found' })
}

function safeRecordId(id: string): string {
  if (!id || id.length > 256 || /[\s\u0000-\u001f\u007f]/.test(id) || id === '.' || id === '..')
    throw new ApiError(errorMessages.history_unavailable, { code: 'history_unavailable' })
  return encodeURIComponent(id)
}

function headerValue(headers: TaroGeneral.IAnyObject | undefined, name: string): string | null {
  if (!headers) return null
  const wanted = name.toLowerCase()
  for (const [key, value] of Object.entries(headers)) {
    if (key.toLowerCase() === wanted && (typeof value === 'string' || typeof value === 'number')) {
      return String(value)
    }
  }
  return null
}

function toApiError(status: number | null, body: unknown, headers?: TaroGeneral.IAnyObject, fallback = 'MateLink API 请求失败'): ApiError {
  const payload = errorBody(body)
  const code = stringValue(payload.error) ?? stringValue(payload.error_code) ?? stringValue(payload.code) ?? 'api_error'
  const messageKey = stringValue(payload.message_key)
  const message = Object.prototype.hasOwnProperty.call(errorMessages, code) ? errorMessages[code] : fallback
  return new ApiError(message, {
    status,
    code,
    messageKey,
    retryAfter: headerValue(headers, 'Retry-After') ?? stringValue(payload.retry_after),
  })
}

function unwrap<T>(value: T | ApiEnvelope<T>): T {
  if (isRecord(value) && value.data !== undefined) return value.data as T
  return value as T
}

async function rawRequest<T extends TaroGeneral.IAnyObject>(path: string, options: Omit<Taro.request.Option, 'url'> = {}): Promise<Taro.request.SuccessCallbackResult<T>> {
  try {
    const response = await Taro.request<T>({
      ...options,
      url: `${requireApiBaseUrl()}${path}`,
    })
    // Fail closed if a proxy/auth page or visible redirect reaches the client.
    // The platform's redirect transport policy still requires runtime verification.
    const contentType = headerValue(response.header, 'Content-Type')?.split(';')[0].trim().toLowerCase()
    const success = response.statusCode >= 200 && response.statusCode < 300
    if (success && isRecord(response.data) && stringValue(response.data.error)) throw toApiError(response.statusCode, response.data, response.header)
    if ((response.statusCode >= 300 && response.statusCode < 400) || headerValue(response.header, 'Location') !== null ||
      (success && ((contentType != null && contentType !== 'application/json' && !/^application\/[a-z0-9.+-]+\+json$/.test(contentType)) ||
        !isRecord(response.data) || ('data' in response.data && !isRecord(response.data.data))))) {
      throw new ApiError(errorMessages.invalid_api_response, { status: response.statusCode, code: 'invalid_api_response' })
    }
    return response
  } catch (reason) {
    if (reason instanceof ApiError) throw reason
    throw new ApiError(errorMessages.network_error, { code: 'network_error', cause: reason })
  }
}

function sessionHeaders(): TaroGeneral.IAnyObject {
  const session = readAppSession(Taro)
  return session ? { Authorization: `Bearer ${session.accessToken}` } : {}
}

function expiresAt(value: unknown, expiresIn: unknown): string | null {
  const explicit = stringValue(value)
  if (explicit) return explicit
  if (typeof expiresIn === 'number' && Number.isFinite(expiresIn) && expiresIn > 0) {
    return new Date(Date.now() + expiresIn * 1000).toISOString()
  }
  return null
}

function normalizeSession(raw: AuthSessionResponse, previousRefreshToken: string | null = null): AppSession {
  const accessToken = stringValue(raw.access_token)
  if (!accessToken) throw new ApiError('服务端未返回有效会话', { code: 'invalid_session_response' })
  return {
    apiOrigin: getApiOrigin(),
    accessToken,
    refreshToken: stringValue(raw.refresh_token) ?? previousRefreshToken,
    expiresAt: expiresAt(raw.expires_at, raw.expires_in),
    userId: stringValue(raw.user?.id),
    linkRequired: typeof raw.link_required === 'boolean'
      ? raw.link_required
      : typeof raw.user?.link_required === 'boolean' ? raw.user.link_required : null,
  }
}

let refreshPromise: Promise<AppSession> | null = null
let refreshOwnerEpoch = -1
let refreshOwnerUser: string | null = null
const ticketExchangePromises = new Map<string, Promise<AppSession>>()
const teslaClaimPromises = new Map<string, Promise<AppSession>>()
let sessionEpoch = 0
let authorizationEpoch = 0
let accountDeletionInFlight = false
let teslaStartInFlight = 0
let ticketExchangeInFlight = 0
let wechatMutationInFlight = false
let wechatRestoreFlight: { key: string; promise: Promise<AppSession> } | null = null
let bootstrapFlight: { key: string; promise: Promise<void> } | null = null
let wechatRecoveryFailure: ApiError | null = null
const suppressedRecoveryOrigins = new Set<string>()
const automaticRecoveryAttempts = new Set<string>()
const sessionRecoveryListeners = new Set<() => void>()
let verifiedCarsScope: string | null = null
const verifiedCars = new Map<number, string>()
function currentCarsScope(): string {
  return JSON.stringify([getApiOrigin(), readAppSession(Taro)?.userId ?? null, sessionEpoch])
}

// Non-secret generation for page lifecycle guards; never use tokens in cache keys.
export function getApiSessionGeneration(): number { return sessionEpoch }

export function subscribeSessionRecovery(listener: () => void): () => void {
  sessionRecoveryListeners.add(listener)
  return () => { sessionRecoveryListeners.delete(listener) }
}

function notifySessionRecovery(): void {
  for (const listener of sessionRecoveryListeners) { try { listener() } catch { /* Other visible pages can still update. */ } }
}

function sessionChangedError(): ApiError {
  return new ApiError(errorMessages.session_changed, { code: 'session_changed' })
}

function requireSessionIdentity(session: AppSession): AppSession {
  if (!session.userId) throw new ApiError(errorMessages.invalid_session_response, { code: 'invalid_session_response' })
  return session
}

async function performRefresh(): Promise<AppSession> {
  const current = readAppSession(Taro)
  if (!current?.refreshToken) {
    throw new ApiError(errorMessages.session_required, { status: 401, code: 'session_required' })
  }
  const epoch = sessionEpoch
  const response = await rawRequest<AuthSessionResponse>('/v1/session/refresh', {
    method: 'POST',
    data: { refresh_token: current.refreshToken },
    header: { 'Content-Type': 'application/json' },
  })
  const latest = readAppSession(Taro)
  // Reject old failures as well as old successes before they reach the new UI.
  if (epoch !== sessionEpoch || !latest || latest.userId !== current.userId || latest.refreshToken !== current.refreshToken) throw sessionChangedError()
  if (response.statusCode < 200 || response.statusCode >= 300) {
    throw toApiError(response.statusCode, response.data, response.header, '会话刷新失败')
  }
  const next = normalizeSession(unwrap(response.data), current.refreshToken)
  next.userId = next.userId ?? current.userId
  next.linkRequired = next.linkRequired ?? current.linkRequired
  if (current.source === 'wechat') next.source = 'wechat'
  requireSessionIdentity(next)
  if (next.userId !== current.userId) throw sessionChangedError()
  writeAppSession(Taro, next)
  return next
}

export function refreshSession(): Promise<AppSession> {
  const ownerUser = readAppSession(Taro)?.userId ?? null
  if (!refreshPromise || refreshOwnerEpoch !== sessionEpoch || refreshOwnerUser !== ownerUser) {
    refreshOwnerEpoch = sessionEpoch
    refreshOwnerUser = ownerUser
    const next = performRefresh().finally(() => {
      // A previous account's finally must not clear the next account's flight.
      if (refreshPromise === next) refreshPromise = null
    })
    refreshPromise = next
  }
  return refreshPromise
}

type RequestControl = { allowRefresh?: boolean }

export async function requestJson<T extends TaroGeneral.IAnyObject>(path: string, options: Omit<Taro.request.Option, 'url'> = {}, control: RequestControl = {}): Promise<T> {
  const epoch = sessionEpoch, authEpoch = authorizationEpoch
  const ownerUser = readAppSession(Taro)?.userId ?? null
  const assertCurrent = () => {
    if (epoch !== sessionEpoch || authEpoch !== authorizationEpoch || (readAppSession(Taro)?.userId ?? null) !== ownerUser) throw sessionChangedError()
  }
  let clearedOwnSession = false
  const attempt = async (allowRefresh: boolean): Promise<T> => {
    assertCurrent()
    if (MOCK_FIXTURE_ENABLED) {
      // Review fixture: wire-shaped synthetic data through the real normalize
      // pipeline; rows carry source mock_fixture and never pass as real records.
      const hit = mockRequest(path)
      if (hit !== null) return hit as T
    }
    const sentSession = readAppSession(Taro)
    const response = await rawRequest<T>(path, {
      ...options,
      header: {
        ...(options.header ?? {}),
        ...(sentSession ? { Authorization: `Bearer ${sentSession.accessToken}` } : {}),
      },
    })
    assertCurrent()
    if (response.statusCode >= 200 && response.statusCode < 300) return response.data
    const failure = toApiError(response.statusCode, response.data, response.header)
    if (response.statusCode === 401 && allowRefresh && path !== '/v1/session/refresh' && !teslaStartInFlight && !readPendingTeslaAuthorization(Taro)) {
      const current = readAppSession(Taro)
      if (current?.refreshToken) {
        // Another request may already have rotated this account's token.
        if (current.accessToken !== sentSession?.accessToken) return attempt(false)
        try {
          await refreshSession()
        } catch (refreshError) {
          assertCurrent()
          if (refreshError instanceof ApiError && refreshError.status === 401) {
            const latest = readAppSession(Taro)
            if (latest?.refreshToken === current.refreshToken && !readPendingTeslaAuthorization(Taro) && !teslaStartInFlight &&
                !ticketExchangeInFlight && !accountDeletionInFlight && teslaClaimPromises.size === 0) {
              sessionEpoch += 1
              clearAppSession(Taro)
              clearedOwnSession = true
              notifySessionRecovery()
              void ensureWechatSession()
            }
          }
          throw refreshError
        }
        assertCurrent()
        return attempt(false)
      }
    }
    throw failure
  }
  try { return await attempt(control.allowRefresh !== false) }
  catch (reason) {
    // Keep a genuine refresh rejection observable after this request cleared the
    // same expired session; otherwise never publish errors from a replaced user.
    if (!clearedOwnSession) assertCurrent()
    throw reason
  }
}

function normalizeCar(raw: RawCar): Car | null {
  if (!isRecord(raw)) return null
  const idValue = raw.car_id ?? raw.id
  const id = typeof idValue === 'number' ? idValue : typeof idValue === 'string' && /^[1-9]\d*$/.test(idValue) ? Number(idValue) : NaN
  const stableId = stringValue(raw.vehicle_uid) ?? stringValue(raw.provider_vehicle_id)
  if (!Number.isSafeInteger(id) || id <= 0 || !stableId) return null
  const model = stringValue(raw.model) ?? stringValue(raw.car_details?.model)
  const name = stringValue(raw.display_name) ?? stringValue(raw.name)
  return {
    id,
    stableId,
    name,
    model,
    trim: stringValue(raw.car_details?.trim_badging),
    exteriorColor: stringValue(raw.car_exterior?.exterior_color),
    wheelType: stringValue(raw.car_exterior?.wheel_type),
    displayName: name?.trim() || (model ? `Model ${model}` : 'Tesla'),
    freeSupercharging: raw.car_settings?.free_supercharging === true ? true : raw.car_settings?.free_supercharging === false ? false : null,
    capacityKwh: finite(raw.current_capacity ?? raw.battery_health?.current_capacity, Number.MIN_VALUE),
    capacityBasis: finite(raw.current_capacity ?? raw.battery_health?.current_capacity, Number.MIN_VALUE) != null
      ? 'api_reported_current_capacity' : undefined,
    capacitySourceField: finite(raw.current_capacity ?? raw.battery_health?.current_capacity, Number.MIN_VALUE) != null
      ? raw.current_capacity != null ? 'current_capacity' : 'battery_health.current_capacity' : null,
    capacityCalculationVersion: 1,
  }
}

function normalizeCarStatus(raw: RawCarStatus, observedAt: string | null, source: string | null, units?: unknown, fieldSources?: unknown): CarStatus {
  // Absent units retain the existing API's metric contract; explicit unknown units do not.
  const unit = (key: string, fallback: string): string | null => units === undefined ? fallback : isRecord(units) ? stringValue(units[key]) : null
  const length = unit('unit_of_length', 'km'), temperature = unit('unit_of_temperature', 'C')
  const km = (value: unknown): number | null => {
    const scalar = finite(value, 0)
    return scalar !== null && (length === 'km' || length === 'mi') ? finite(scalar * (length === 'mi' ? 1.609344 : 1), 0) : null
  }
  const celsius = (value: unknown): number | null => {
    const scalar = finite(value)
    return scalar !== null && (temperature === 'C' || temperature === 'F') ? finite(temperature === 'F' ? (scalar - 32) * 5 / 9 : scalar, -100, 100) : null
  }
  const latitude = finite(raw.car_geodata?.latitude, -90, 90)
  const longitude = finite(raw.car_geodata?.longitude, -180, 180)
  const location = latitude != null && longitude != null && (latitude !== 0 || longitude !== 0)
    ? { latitude, longitude }
    : null
  const shift = stringValue(raw.driving_details?.shift_state)?.toUpperCase()
  const phases = finite(raw.charging_details?.charger_phases, 0, 3)
  const limitSoc = finite(raw.charging_details?.charge_limit_soc, 0, 100)
  const originalText = (value: unknown) => typeof value === 'string' && value.trim() && value.length <= 128 && !/[\u0000-\u001f\u007f]/.test(value) ? value : null
  return {
    ...(raw.tpms_details !== undefined ? { tpms: parseTpmsDetails(raw.tpms_details),
      tpmsReport: parseTpmsReport(raw.tpms_details, isRecord(units) ? stringValue(units.unit_of_pressure) : null) } : {}),
    state: raw.state ?? null,
    model: stringValue(raw.model),
    trim: stringValue(raw.trim_badging),
    exteriorColor: stringValue(raw.exterior_color),
    wheelType: stringValue(raw.wheel_type),
    batteryLevel: finite(raw.battery_details?.battery_level, 0, 100),
    ratedRange: km(raw.battery_details?.rated_battery_range),
    idealRange: km(raw.battery_details?.ideal_battery_range),
    estimatedRange: km(raw.battery_details?.est_battery_range),
    odometer: km(raw.odometer),
    location,
    geofence: stringValue(raw.car_geodata?.geofence)?.slice(0, 256) ?? null,
    locked: bool(raw.car_status?.locked),
    sentryMode: bool(raw.car_status?.sentry_mode),
    windowsOpen: bool(raw.car_status?.windows_open),
    doorsOpen: bool(raw.car_status?.doors_open),
    trunkOpen: bool(raw.car_status?.trunk_open),
    frunkOpen: bool(raw.car_status?.frunk_open),
    isClimateOn: bool(raw.climate_details?.is_climate_on),
    isPreconditioning: bool(raw.climate_details?.is_preconditioning),
    insideTemp: celsius(raw.climate_details?.inside_temp),
    outsideTemp: celsius(raw.climate_details?.outside_temp),
    pluggedIn: bool(raw.charging_details?.plugged_in),
    speed: km(raw.driving_details?.speed),
    power: finite(raw.driving_details?.power),
    shiftState: shift && ['P', 'R', 'N', 'D'].includes(shift) ? shift : null,
    heading: finite(raw.driving_details?.heading, 0, 360),
    chargingState: raw.charging_details?.charging_state ?? null,
    chargeEnergyAdded: finite(raw.charging_details?.charge_energy_added, 0),
    chargerPower: finite(raw.charging_details?.charger_power, 0),
    chargerVoltage: finite(raw.charging_details?.charger_voltage, 0),
    chargerActualCurrent: finite(raw.charging_details?.charger_actual_current),
    timeToFullChargeHours: finite(raw.charging_details?.time_to_full_charge, 0),
    chargeLimitSoc: limitSoc !== null && Number.isInteger(limitSoc) ? limitSoc : null,
    chargerPhases: phases !== null && Number.isInteger(phases) ? phases : null,
    chargeCurrentRequest: finite(raw.charging_details?.charge_current_request, 0),
    chargeCurrentRequestMax: finite(raw.charging_details?.charge_current_request_max, 0),
    chargePortDoorOpen: bool(raw.charging_details?.charge_port_door_open),
    observedAt: originalText(observedAt),
    source: originalText(source),
    fieldSources: statusFieldSources(fieldSources),
  }
}

function pageMeta(meta: PageMeta | undefined, page: number, show: number, length: number): HistoryPage<never>['meta'] {
  const total = meta?.total ?? null
  const totalPages = meta?.total_pages ?? (total == null ? null : Math.max(1, Math.ceil(total / show)))
  return {
    page: meta?.page ?? page,
    show: meta?.show ?? show,
    total,
    totalPages,
    availability: meta?.availability ?? null,
    source: meta?.source ?? null,
    qualityState: meta?.quality_state ?? null,
    qualityReason: meta?.quality_reason ?? null,
    hasMore: totalPages == null ? length >= show : page < totalPages,
  }
}

function responseDistanceUnit(response: unknown): string | null | undefined {
  const data = unwrap(response)
  if (!isRecord(data) || !Object.prototype.hasOwnProperty.call(data, 'units')) return undefined
  const units = data.units
  if (!isRecord(units) || !Object.prototype.hasOwnProperty.call(units, 'unit_of_length')) return null
  return stringValue(units.unit_of_length)
}

function responseTemperatureUnit(response: unknown): string | null | undefined {
  const data = unwrap(response)
  if (!isRecord(data) || !Object.prototype.hasOwnProperty.call(data, 'units')) return undefined
  return isRecord(data.units) ? stringValue(data.units.unit_of_temperature) : null
}

export function normalizeDrive(raw: RawDrive, car?: Car, distanceUnit?: string | null, temperatureUnit?: string | null): Drive | null {
  if (!isRecord(raw)) return null
  const rawId = raw.drive_id ?? raw.session_id
  if ((typeof rawId !== 'string' && typeof rawId !== 'number') || String(rawId).trim() === '' ||
      (typeof rawId === 'number' && (!Number.isSafeInteger(rawId) || rawId <= 0))) return null
  const receipt = driveEnergyReceipt(raw, distanceUnit)
  const energy = resolveDriveEnergy(receipt, car)
  // Existing telemetry_mqtt route contract normalizes VehicleSpeed mph to km/h
  // before storage (telemetry_normalization.go -> telemetry_core.go -> history_sample_metrics.go).
  // Imported archive speeds have no proven implicit unit contract here.
  const speedUnit = raw.speed_unit == null && (distanceUnit === 'km' || distanceUnit === 'mi')
    ? (distanceUnit === 'mi' ? 'mph' : 'km/h') : raw.speed_unit == null && raw.source === 'telemetry_mqtt' &&
    (raw.quality_state === 'observed' || raw.quality_state === 'derived') ? 'km/h' : stringValue(raw.speed_unit)
  const speed = finite(raw.speed_max, 0)
  const speedKmh = speed !== null && (speedUnit === 'km/h' || speedUnit === 'kph' || speedUnit === 'mph')
    ? finite(speed * (speedUnit === 'mph' ? 1.609344 : 1), 0) : null
  const length = (value: unknown) => driveDistanceKm({ odometer_details: { odometer_distance: finite(value, 0) }, distance_unit: distanceUnit })
  const averageSpeed = finite(raw.speed_avg, 0)
  const outside = finite(raw.outside_temp_avg)
  const temp = temperatureUnit === undefined ? 'C' : temperatureUnit
  return {
    id: String(rawId),
    sessionId: stringValue(raw.session_id),
    startDate: raw.start_date ?? null,
    endDate: raw.end_date ?? null,
    startAddress: raw.start_address ?? null,
    endAddress: raw.end_address ?? null,
    durationMinutes: finite(raw.duration_min, 0),
    startRatedRangeKm: length(raw.range_rated?.start_range), endRatedRangeKm: length(raw.range_rated?.end_range),
    speedAvgKmh: averageSpeed !== null && ['km/h', 'kph', 'mph'].includes(speedUnit ?? '')
      ? finite(averageSpeed * (speedUnit === 'mph' ? 1.609344 : 1), 0) : null,
    outsideTempC: outside !== null && (temp === 'C' || temp === 'F') ? finite(temp === 'F' ? (outside - 32) * 5 / 9 : outside, -100, 100) : null,
    // The App displays a reported peak when its unit is known. The sample source
    // and count remain evidence metadata and must not hide a valid archive summary.
    speedMaxKmh: distanceUnit === undefined || distanceUnit === 'km' || distanceUnit === 'mi' ? speedKmh : null,
    speedSampleCount: Number.isSafeInteger(raw.speed_sample_count) && (raw.speed_sample_count ?? -1) >= 0 ? raw.speed_sample_count! : null,
    speedMetricsSource: stringValue(raw.speed_metrics_source),
    speedUnit: speedKmh !== null ? 'km/h' : speedUnit,
    distanceKm: driveDistanceKm(receipt),
    energyConsumedKwh: energy.energyKwh,
    energy, energyReceipt: receipt, efficiencyWhKm: energy.efficiencyWhKm,
    startBatteryLevel: finite(raw.battery_details?.start_battery_level, 0, 100),
    endBatteryLevel: finite(raw.battery_details?.end_battery_level, 0, 100),
    startLatitude: finite(raw.start_latitude, -90, 90),
    startLongitude: finite(raw.start_longitude, -180, 180),
    endLatitude: finite(raw.end_latitude, -90, 90),
    endLongitude: finite(raw.end_longitude, -180, 180),
    source: raw.source ?? null,
    qualityState: raw.quality_state ?? null,
    qualityReason: raw.quality_reason ?? null,
  }
}

export function normalizeCharge(raw: RawCharge): Charge | null {
  if (!isRecord(raw)) return null
  const rawId = raw.charge_id ?? raw.session_id
  if ((typeof rawId !== 'string' && typeof rawId !== 'number') || String(rawId).trim() === '' ||
      (typeof rawId === 'number' && (!Number.isSafeInteger(rawId) || rawId <= 0))) return null
  const energy = resolveChargeEnergy(raw)
  return {
    id: String(rawId),
    sessionId: stringValue(raw.session_id),
    startDate: raw.start_date ?? null,
    endDate: raw.end_date ?? null,
    address: raw.address ?? null,
    durationMinutes: finite(raw.duration_min, 0),
    energyAddedKwh: energy.batteryInputKwh,
    energyUsedKwh: energy.inputEnergyKwh,
    energy,
    cost: finite(raw.cost, 0),
    currency: chargeCurrency(raw), currencyCode: chargeCurrency(raw), chargeMode: energy.chargeMode ?? chargeMode(raw),
    reportedChargeMode: typeof raw.charge_type === 'string' ? raw.charge_type : null,
    startBatteryLevel: finite(raw.battery_details?.start_battery_level, 0, 100),
    endBatteryLevel: finite(raw.battery_details?.end_battery_level, 0, 100),
    latitude: finite(raw.latitude, -90, 90),
    longitude: finite(raw.longitude, -180, 180),
    chargerPowerKw: finite(raw.charger_power, 0),
    source: raw.source ?? null,
    qualityState: raw.quality_state ?? null,
    qualityReason: raw.quality_reason ?? null,
  }
}

function historyPath(carId: number, kind: 'drives' | 'charges', page: number, show: number): string {
  if (!Number.isSafeInteger(page) || page < 1 || !Number.isSafeInteger(show) || show < 1 || show > 100)
    throw new ApiError(errorMessages.history_unavailable, { code: 'history_unavailable' })
  const params = `page=${encodeURIComponent(String(page))}&show=${encodeURIComponent(String(show))}`
  return `/api/v1/cars/${carId}/${kind}?${params}`
}

function historyResult<T>(raw: unknown, key: 'drives' | 'charges', page: number, show: number, mapper: (item: unknown) => T | null): HistoryPage<T> {
  const data = unwrap(raw as ApiEnvelope<UnknownRecord> | UnknownRecord)
  const record = isRecord(data) ? data : {}
  const rows = record[key]
  if (!Array.isArray(rows)) throw new ApiError(errorMessages.invalid_api_response, { code: 'invalid_api_response' })
  const mapped = rows.map(mapper).filter((item): item is T => item !== null)
  return {
    items: mapped,
    meta: pageMeta(isRecord(record.meta) ? record.meta as PageMeta : undefined, page, show, mapped.length),
  }
}

function detailResult<T>(raw: unknown, key: string, mapper: (item: unknown) => T | null): T | null {
  const data = unwrap(raw as ApiEnvelope<UnknownRecord> | UnknownRecord)
  const value = isRecord(data) ? data[key] : undefined
  return mapper(value)
}

export type AuthConsent = {
  termsVersion: string
  privacyVersion: string
}

export { JOURVOLT_PRIVACY_VERSION, JOURVOLT_TERMS_VERSION }

async function cancelTeslaAuthorizationRecord(pending: { transactionId: string; clientProof: string }, strict = false): Promise<void> {
  const response = await rawRequest('/v1/auth/miniprogram/cancel', {
    method: 'POST', data: { transaction_id: pending.transactionId, client_proof: pending.clientProof },
    header: { 'Content-Type': 'application/json' },
  })
  if (strict) {
    const terminalCode = isRecord(response.data) ? response.data.error : null
    if ((response.statusCode === 410 && terminalCode === 'authorization_expired') ||
        (response.statusCode === 409 && terminalCode === 'authorization_consumed')) return
    if (response.statusCode >= 200 && response.statusCode < 300 && isRecord(response.data) && response.data.status === 'cancelled') return
    throw toApiError(response.statusCode, response.data, response.header, '尚未确认旧 Tesla 授权已取消，请重试注销')
  }
  if (response.statusCode >= 300 && response.statusCode !== 401 && response.statusCode !== 410)
    throw toApiError(response.statusCode, response.data, response.header, '取消 Tesla 授权失败')
}

function recoveryStorageError(): ApiError {
  return new ApiError(errorMessages.wechat_recovery_storage_failed, { code: 'wechat_recovery_storage_failed' })
}

// Disable in memory first. If an update fails, deleting the preference is a
// safe fallback; failure of both operations must remain visible to the user.
function disableWechatRecovery(origin: string): void {
  suppressedRecoveryOrigins.add(origin)
  const previous = readWechatRecoveryPreference(Taro, origin)
  try {
    if (previous) writeWechatRecoveryPreference(Taro, { ...previous, enabled: false })
    else clearWechatRecoveryPreference(Taro, origin)
  } catch {
    try { clearWechatRecoveryPreference(Taro, origin) } catch { throw recoveryStorageError() }
  }
}

function enableWechatRecovery(origin: string, userId: string): void {
  try { writeWechatRecoveryPreference(Taro, { apiOrigin: origin, boundUserId: userId, enabled: true }) }
  catch {
    suppressedRecoveryOrigins.add(origin)
    try { clearWechatRecoveryPreference(Taro, origin) } catch { /* the error is still reported */ }
    throw recoveryStorageError()
  }
  suppressedRecoveryOrigins.delete(origin)
  wechatRecoveryFailure = null
}

export function isWechatRecoveryEnabledForAccount(origin: string, userId: string): boolean {
  const preference = readWechatRecoveryPreference(Taro, origin)
  return !suppressedRecoveryOrigins.has(origin) && Boolean(preference?.enabled && preference.boundUserId === userId)
}

function fenceRecoveryForTeslaAccount(origin: string, userId: string): void {
  const preference = readWechatRecoveryPreference(Taro, origin)
  if (!preference || preference.boundUserId !== userId) disableWechatRecovery(origin)
}

function assertWechatAvailable(): void {
  if (MOCK_FIXTURE_ENABLED) throw new ApiError(errorMessages.wechat_mock_unavailable, { code: 'wechat_mock_unavailable' })
}

async function freshWechatCode(): Promise<string> {
  try {
    const result = await Taro.login({ timeout: 10000 })
    if (typeof result.code === 'string' && result.code.length <= 512 && result.code.length > 0 && !/[\s\u0000-\u001f\u007f]/.test(result.code)) return result.code
  } catch { /* Do not retain a platform error that could contain a code. */ }
  throw new ApiError(errorMessages.wechat_code_invalid, { code: 'wechat_code_invalid' })
}

async function wechatRequest<T extends TaroGeneral.IAnyObject>(path: string, options: Omit<Taro.request.Option, 'url'>): Promise<Taro.request.SuccessCallbackResult<T>> {
  try { return await rawRequest<T>(path, { timeout: 10000, ...options }) }
  catch (reason) {
    if (reason instanceof ApiError) throw new ApiError(reason.message, { code: reason.code, status: reason.status })
    throw new ApiError(errorMessages.network_error, { code: 'network_error' })
  }
}

function parseWechatBinding(value: { bound?: unknown; binding_version?: unknown }): { bound: boolean; bindingVersion: string } {
  if (typeof value.bound !== 'boolean' || typeof value.binding_version !== 'string' || !/^\d+$/.test(value.binding_version))
    throw new ApiError(errorMessages.invalid_api_response, { code: 'invalid_api_response' })
  return { bound: value.bound, bindingVersion: value.binding_version }
}

function restoreWechatSession(automatic: boolean): Promise<AppSession> {
  assertWechatAvailable()
  const origin = getApiOrigin()
  const preference = readWechatRecoveryPreference(Taro, origin)
  if (readAppSession(Taro) || readPendingTeslaAuthorization(Taro) || accountDeletionInFlight || teslaStartInFlight || ticketExchangeInFlight ||
      (automatic && (!preference?.enabled || suppressedRecoveryOrigins.has(origin)))) throw sessionChangedError()
  const ownerEpoch = sessionEpoch, authEpoch = authorizationEpoch
  const key = JSON.stringify([origin, ownerEpoch, authEpoch])
  if (wechatRestoreFlight?.key === key) return wechatRestoreFlight.promise
  const assertCurrent = () => {
    const currentPreference = readWechatRecoveryPreference(Taro, origin)
    if (origin !== getApiOrigin() || ownerEpoch !== sessionEpoch || authEpoch !== authorizationEpoch || readAppSession(Taro) ||
        readPendingTeslaAuthorization(Taro) || accountDeletionInFlight || teslaStartInFlight || ticketExchangeInFlight ||
        (automatic && (suppressedRecoveryOrigins.has(origin) || !currentPreference?.enabled || currentPreference.boundUserId !== preference?.boundUserId))) throw sessionChangedError()
  }
  const promise = (async () => {
    const code = await freshWechatCode()
    assertCurrent()
    const response = await wechatRequest<AuthSessionResponse>('/v1/auth/wechat/restore', {
      method: 'POST', data: { code }, header: { 'Content-Type': 'application/json' },
    })
    assertCurrent()
    if (response.statusCode < 200 || response.statusCode >= 300) {
      const failure = toApiError(response.statusCode, response.data, response.header, '微信快捷进入暂不可用')
      if (failure.code === 'wechat_not_bound') disableWechatRecovery(origin)
      throw failure
    }
    const next = requireSessionIdentity(normalizeSession(unwrap(response.data), null))
    if (automatic && next.userId !== preference?.boundUserId) throw new ApiError(errorMessages.wechat_account_changed, { code: 'wechat_account_changed' })
    if (!automatic) enableWechatRecovery(origin, next.userId!)
    next.source = 'wechat'
    writeAppSession(Taro, next)
    sessionEpoch += 1
    authorizationEpoch += 1
    return next
  })()
  wechatRestoreFlight = { key, promise }
  void promise.finally(() => { if (wechatRestoreFlight?.promise === promise) wechatRestoreFlight = null }).catch(() => {})
  return promise
}

/** Shared launch barrier: pages await it before reading session or account caches. */
export function ensureWechatSession(): Promise<void> {
  if (MOCK_FIXTURE_ENABLED) return Promise.resolve()
  let origin: string
  try { origin = getApiOrigin() } catch { return Promise.resolve() }
  const key = JSON.stringify([origin, sessionEpoch, authorizationEpoch])
  // A restore may already have started under this generation after an expired
  // session was cleared, or may be an explicit manual restore after logout.
  if (wechatRestoreFlight?.key === key) return wechatRestoreFlight.promise.then(() => {}, () => {})
  if (bootstrapFlight?.key === key) return bootstrapFlight.promise
  const promise = (async () => {
    let clearedSession = false
    try {
      if (readPendingTeslaAuthorization(Taro) || accountDeletionInFlight || teslaStartInFlight || ticketExchangeInFlight) return
      const current = readAppSession(Taro)
      if (current) {
        const expiry = current.expiresAt ? Date.parse(current.expiresAt) : NaN
        if (!Number.isFinite(expiry) || expiry > Date.now()) return
        const ownerEpoch = sessionEpoch, authEpoch = authorizationEpoch
        if (current.refreshToken) {
          try { await refreshSession(); return }
          catch (reason) {
            if (!(reason instanceof ApiError) || reason.status !== 401) return
          }
        }
        const latest = readAppSession(Taro)
        if (ownerEpoch !== sessionEpoch || authEpoch !== authorizationEpoch || readPendingTeslaAuthorization(Taro) ||
            latest?.accessToken !== current.accessToken || latest?.refreshToken !== current.refreshToken || latest?.userId !== current.userId) return
        sessionEpoch += 1
        clearAppSession(Taro)
        clearedSession = true
      }
      const preference = readWechatRecoveryPreference(Taro, origin)
      if (!preference?.enabled || suppressedRecoveryOrigins.has(origin)) return
      const attempt = JSON.stringify([origin, sessionEpoch, authorizationEpoch])
      if (automaticRecoveryAttempts.has(attempt)) return
      automaticRecoveryAttempts.add(attempt)
      try {
        const restoration = restoreWechatSession(true)
        // Publish the flight before notifying visible pages, so each reload
        // joins this same attempt under the new session generation.
        if (clearedSession) { clearedSession = false; notifySessionRecovery() }
        await restoration
        wechatRecoveryFailure = null
      }
      catch (reason) { if (reason instanceof ApiError && reason.code !== 'session_changed') wechatRecoveryFailure = reason }
    } catch { /* Login remains available; a later explicit attempt uses a fresh code. */ }
    finally { if (clearedSession) notifySessionRecovery() }
  })()
  bootstrapFlight = { key, promise }
  void promise.finally(() => { if (bootstrapFlight?.promise === promise) bootstrapFlight = null }).catch(() => {})
  return promise
}

export function getWechatRecoveryFailure(): ApiError | null { return wechatRecoveryFailure }

export const matelinkApi = {
  async logout(): Promise<void> {
    const session = readAppSession(Taro)
    const pending = readPendingTeslaAuthorization(Taro)
    const capturedHeaders = session ? { Authorization: `Bearer ${session.accessToken}` } : {}
    sessionEpoch += 1
    authorizationEpoch += 1
    wechatRecoveryFailure = null
    let preferenceFailure: unknown = null
    try { disableWechatRecovery(getApiOrigin()) } catch (reason) { preferenceFailure = reason }
    clearAppSession(Taro)
    let pendingOriginMatches = false
    try { pendingOriginMatches = Boolean(pending && pending.apiOrigin === getApiOrigin()) } catch { pendingOriginMatches = false }
    if (pending && pendingOriginMatches) {
      try { await cancelTeslaAuthorizationRecord(pending) } catch { /* local logout must still complete */ }
    }
    if (!session) { if (preferenceFailure) throw preferenceFailure; return }
    try {
      const response = await rawRequest('/v1/session/logout', {
        method: 'POST',
        header: { ...capturedHeaders, 'Content-Type': 'application/json' },
      })
      if (response.statusCode >= 300 && response.statusCode !== 401) {
        throw toApiError(response.statusCode, response.data, response.header, '退出登录失败')
      }
    } catch (reason) {
      if (preferenceFailure) throw new ApiError('本机自动进入未能关闭，重启后可能重新进入；退出请求未确认，请检查微信存储。', { code: 'wechat_recovery_storage_failed' })
      throw reason
    }
    if (preferenceFailure) throw new ApiError('本机自动进入未能关闭，重启后可能重新进入，请检查微信存储。', { code: 'wechat_recovery_storage_failed' })
  },

  async getWechatBinding(): Promise<{ bound: boolean; bindingVersion: string }> {
    assertWechatAvailable()
    if (!readAppSession(Taro)?.userId) throw new ApiError(errorMessages.session_required, { status: 401, code: 'session_required' })
    // A status read must not rotate a session underneath a Tesla authorization page.
    const response = unwrap(await requestJson<{ bound?: unknown; binding_version?: unknown }>('/v1/auth/wechat/binding', {}, { allowRefresh: false }))
    return parseWechatBinding(response)
  },

  async prepareWechatBinding(): Promise<{ bound: boolean; bindingVersion: string }> {
    assertWechatAvailable()
    const owner = readAppSession(Taro), origin = getApiOrigin(), ownerEpoch = sessionEpoch, authEpoch = authorizationEpoch
    if (!owner?.userId) throw new ApiError(errorMessages.session_required, { status: 401, code: 'session_required' })
    if (wechatMutationInFlight || accountDeletionInFlight || teslaStartInFlight || ticketExchangeInFlight || readPendingTeslaAuthorization(Taro)) throw sessionChangedError()
    const result = unwrap(await requestJson<{ bound?: unknown; binding_version?: unknown }>('/v1/auth/wechat/binding'))
    if (ownerEpoch !== sessionEpoch || authEpoch !== authorizationEpoch || getApiOrigin() !== origin ||
        readAppSession(Taro)?.userId !== owner.userId || readPendingTeslaAuthorization(Taro)) throw sessionChangedError()
    return parseWechatBinding(result)
  },

  async bindWechat(preparedBindingVersion: string): Promise<void> {
    assertWechatAvailable()
    if (typeof preparedBindingVersion !== 'string' || !/^\d+$/.test(preparedBindingVersion)) throw new ApiError(errorMessages.invalid_api_response, { code: 'invalid_api_response' })
    const owner = readAppSession(Taro), origin = getApiOrigin()
    if (!owner?.userId) throw new ApiError(errorMessages.session_required, { status: 401, code: 'session_required' })
    if (wechatMutationInFlight || accountDeletionInFlight || teslaStartInFlight || ticketExchangeInFlight || readPendingTeslaAuthorization(Taro)) throw sessionChangedError()
    wechatMutationInFlight = true
    try {
    const ownerEpoch = sessionEpoch, authEpoch = authorizationEpoch
    const assertOwner = () => {
      if (ownerEpoch !== sessionEpoch || authEpoch !== authorizationEpoch || getApiOrigin() !== origin ||
          readAppSession(Taro)?.userId !== owner.userId || accountDeletionInFlight || readPendingTeslaAuthorization(Taro)) throw sessionChangedError()
    }
    for (let attempt = 0; attempt < 2; attempt += 1) {
      if (attempt > 0) {
      const before = readAppSession(Taro)!
      const bindingResponse = await wechatRequest<{ bound?: unknown; binding_version?: unknown }>('/v1/auth/wechat/binding', {
        method: 'GET', header: { Authorization: `Bearer ${before.accessToken}` },
      })
      assertOwner()
      if (bindingResponse.statusCode < 200 || bindingResponse.statusCode >= 300) {
        const failure = toApiError(bindingResponse.statusCode, bindingResponse.data, bindingResponse.header, '微信绑定状态暂不可用')
        if (attempt === 0 && failure.status === 401 && failure.code === 'session_required' && before.refreshToken) {
          await refreshSession()
          assertOwner()
          continue
        }
        throw failure
      }
      const bindingVersion = parseWechatBinding(unwrap(bindingResponse.data)).bindingVersion
      if (preparedBindingVersion !== bindingVersion)
        throw new ApiError(errorMessages.wechat_bind_expired, { status: 409, code: 'wechat_bind_expired' })
      }
      const code = await freshWechatCode()
      assertOwner()
      const current = readAppSession(Taro)!
      const response = await wechatRequest<{ status?: unknown }>('/v1/auth/wechat/bind', {
        method: 'POST', data: { code, binding_version: preparedBindingVersion }, header: { Authorization: `Bearer ${current.accessToken}`, 'Content-Type': 'application/json' },
      })
      assertOwner()
      if (response.statusCode < 200 || response.statusCode >= 300) {
        const failure = toApiError(response.statusCode, response.data, response.header, '微信绑定暂不可用')
        // A consumed code must never be replayed, even after session refresh.
        if (attempt === 0 && failure.status === 401 && failure.code === 'session_required' && current.refreshToken) {
          await refreshSession()
          assertOwner()
          continue
        }
        throw failure
      }
      if (unwrap(response.data).status !== 'bound') throw new ApiError(errorMessages.invalid_api_response, { code: 'invalid_api_response' })
      enableWechatRecovery(origin, owner.userId)
      return
    }
    } finally { wechatMutationInFlight = false }
  },

  async unbindWechat(): Promise<void> {
    assertWechatAvailable()
    const owner = readAppSession(Taro), origin = getApiOrigin()
    if (!owner?.userId) throw new ApiError(errorMessages.session_required, { status: 401, code: 'session_required' })
    if (wechatMutationInFlight || accountDeletionInFlight) throw sessionChangedError()
    wechatMutationInFlight = true
    try {
    const ownerEpoch = sessionEpoch, authEpoch = ++authorizationEpoch
    const result = unwrap(await requestJson<{ status?: unknown }>('/v1/auth/wechat/unbind', { method: 'POST' }))
    const current = readAppSession(Taro)
    if (ownerEpoch !== sessionEpoch || authEpoch !== authorizationEpoch || current?.userId !== owner.userId || getApiOrigin() !== origin) throw sessionChangedError()
    if (result.status !== 'unbound') throw new ApiError(errorMessages.invalid_api_response, { code: 'invalid_api_response' })
    let preferenceFailure: unknown = null
    try { disableWechatRecovery(origin) } catch (reason) { preferenceFailure = reason }
    if (current.source === 'wechat') {
      sessionEpoch += 1
      clearAppSession(Taro)
    }
    if (preferenceFailure) throw preferenceFailure
    } finally { wechatMutationInFlight = false }
  },

  restoreWechatSession(): Promise<AppSession> { return restoreWechatSession(false) },

  async deleteAccount(): Promise<{ teslaConsentRevokeUrl: string | null; recoveryStorageWarning?: boolean }> {
    const owner = readAppSession(Taro)
    if (!owner?.userId) throw new ApiError(errorMessages.session_required, { status: 401, code: 'session_required' })
    if (accountDeletionInFlight) throw sessionChangedError()
    const ownerEpoch = sessionEpoch, ownerOrigin = getApiOrigin(), epoch = ++authorizationEpoch
    accountDeletionInFlight = true
    const assertOwner = () => {
      if (ownerEpoch !== sessionEpoch || epoch !== authorizationEpoch || readAppSession(Taro)?.userId !== owner.userId || ownerOrigin !== getApiOrigin()) throw sessionChangedError()
    }
    try {
      const pending = readPendingTeslaAuthorization(Taro)
      // Cancel before DELETE, so a browser callback cannot recreate a deleted
      // account. Unknown/network failures leave the account intact for retry.
      if (pending && pending.apiOrigin === ownerOrigin) {
        await cancelTeslaAuthorizationRecord(pending, true)
        assertOwner()
        const current = readPendingTeslaAuthorization(Taro)
        if (current?.transactionId !== pending.transactionId || current.clientProof !== pending.clientProof || current.apiOrigin !== pending.apiOrigin) throw sessionChangedError()
        clearPendingTeslaAuthorization(Taro)
      }
      assertOwner()
      const response = await requestJson<{ status?: string; tesla_consent_revoke_url?: string | null }>('/v1/account', {
        method: 'DELETE', header: { 'Content-Type': 'application/json' },
      })
      assertOwner()
      if (response.status !== 'deleted') throw new ApiError(errorMessages.invalid_account_delete_response, { code: 'invalid_account_delete_response' })
      clearHistoryCachesForAccount(Taro, owner.userId, ownerOrigin)
      clearCarsCacheForAccount(Taro, owner.userId, ownerOrigin)
      clearVehicleCachesForAccount(Taro, owner.userId, ownerOrigin)
      clearNewAccountCaches(Taro, owner.userId, ownerOrigin)
      let preferenceFailure: unknown = null
      try { disableWechatRecovery(ownerOrigin) } catch (reason) { preferenceFailure = reason }
      sessionEpoch += 1
      authorizationEpoch += 1
      clearAppSession(Taro)
      const revokeUrl = stringValue(response.tesla_consent_revoke_url)
      return { teslaConsentRevokeUrl: revokeUrl && isTrustedAuthorizationURL(revokeUrl) ? revokeUrl : null,
        ...(preferenceFailure ? { recoveryStorageWarning: true } : {}) }
    } finally { accountDeletionInFlight = false }
  },

  async getCars(): Promise<Car[]> {
    const response = await requestJson<ApiEnvelope<{ cars?: RawCar[] }> | { cars?: RawCar[] }>('/api/v1/cars')
    const cars = unwrap(response).cars
    if (!Array.isArray(cars)) throw new ApiError(errorMessages.invalid_api_response, { code: 'invalid_api_response' })
    const verified = cars.map(normalizeCar).filter((car): car is Car => car !== null)
      .map(car => ({ ...car, remoteVerified: true }))
    if (new Set(verified.map(car => car.id)).size !== verified.length || new Set(verified.map(car => car.stableId)).size !== verified.length)
      throw new ApiError(errorMessages.vehicle_not_found, { code: 'vehicle_not_found' })
    verifiedCarsScope = currentCarsScope()
    verifiedCars.clear()
    verified.forEach(car => verifiedCars.set(car.id, car.stableId))
    const owner = readAppSession(Taro)?.userId
    if (owner) writeCarsCache(Taro, owner, getApiOrigin(), verified)
    return verified
  },

  async getCarStatus(carId: number, car?: Car): Promise<CarStatus> {
    assertVehicle(null, carId, car)
    const response = await requestJson<ApiEnvelope<RawStatusPayload> | RawStatusPayload>(`/api/v1/cars/${carId}/status`)
    const data = unwrap(response)
    assertVehicle(data, carId, car)
    assertVehicle(data.status, carId, car)
    return normalizeCarStatus(data.status ?? {}, data.observed_at ?? null, data.source ?? null, data.units, data.field_sources)
  },

  async getCarSnapshot(carId: number, car?: Car): Promise<CarStatus> {
    assertVehicle(null, carId, car)
    const ownerEpoch = sessionEpoch, owner = readAppSession(Taro)?.userId ?? null
    const checkOwner = () => { if (ownerEpoch !== sessionEpoch || (readAppSession(Taro)?.userId ?? null) !== owner) throw sessionChangedError() }
    let data: RawStatusPayload
    try {
      const response = await requestJson<ApiEnvelope<RawStatusPayload> | RawStatusPayload>(`/api/matelink/v1/cars/${carId}/snapshot`)
      checkOwner(); data = unwrap(response)
    } catch (error) {
      if (!(error instanceof ApiError) || (error.status !== 404 && error.status !== 501)) throw error
      checkOwner()
      const fallback = await matelinkApi.getCarStatus(carId, car)
      checkOwner(); return fallback
    }
    assertVehicle(data, carId, car); assertVehicle(data.status, carId, car)
    if (!isRecord(data.status)) throw new ApiError(errorMessages.invalid_api_response, { code: 'invalid_api_response' })
    return normalizeCarStatus(data.status, data.observed_at ?? null, data.source ?? null, data.units, data.field_sources)
  },

  async getReadiness(carId: number, car?: Car): Promise<DataReadiness> {
    assertVehicle(null, carId, car)
    const response = await requestJson<ApiEnvelope<DataReadiness> | DataReadiness>(`/api/v1/cars/${carId}/data-readiness`)
    const data = unwrap(response)
    assertVehicle(data, carId, car)
    return { ...data, items: Array.isArray(data.items) ? data.items : [] }
  },

  async getTelemetryPairing(carId: number, car?: Car): Promise<TelemetryPairing> {
    assertVehicle(null, carId, car)
    const response = await requestJson<ApiEnvelope<TelemetryPairing> | TelemetryPairing>(`/api/v1/cars/${carId}/telemetry/pairing`)
    assertVehicle(unwrap(response), carId, car)
    return unwrap(response)
  },

  async configureTelemetry(carId: number, car?: Car): Promise<{ status: string | null }> {
    assertVehicle(null, carId, car)
    const response = await requestJson<ApiEnvelope<{ status?: string | null }> | { status?: string | null }>(`/api/v1/cars/${carId}/telemetry/configure`, {
      method: 'POST',
      header: { 'Content-Type': 'application/json' },
      data: {},
    })
    const data = unwrap(response)
    return { status: data.status ?? null }
  },

  async startTeslaAuthorization(consent: AuthConsent): Promise<TeslaAuthorization> {
    if (accountDeletionInFlight) throw sessionChangedError()
    const apiOrigin = getApiOrigin()
    if (consent.termsVersion !== JOURVOLT_TERMS_VERSION || consent.privacyVersion !== JOURVOLT_PRIVACY_VERSION)
      throw new ApiError(errorMessages.consent_required, { code: 'consent_required' })
    teslaStartInFlight += 1
    try {
    wechatRecoveryFailure = null
    const epoch = ++authorizationEpoch, ownerEpoch = sessionEpoch
    const ownerUser = readAppSession(Taro)?.userId ?? null
    const previous = readPendingTeslaAuthorization(Taro)
    const alreadyRefreshing = refreshPromise
    if (alreadyRefreshing) {
      try { await alreadyRefreshing } catch { /* Tesla authorization remains available. */ }
      if (epoch !== authorizationEpoch || ownerEpoch !== sessionEpoch || (readAppSession(Taro)?.userId ?? null) !== ownerUser) throw sessionChangedError()
    }
    const response = await rawRequest<ApiEnvelope<TeslaAuthorization> | TeslaAuthorization>('/v1/auth/tesla/start', {
      header: {
        'X-JourVolt-Terms-Version': consent.termsVersion,
        'X-JourVolt-Privacy-Version': consent.privacyVersion,
        'X-MiniProgram-Channel': 'wechat',
      },
    })
    if (epoch !== authorizationEpoch || ownerEpoch !== sessionEpoch || (readAppSession(Taro)?.userId ?? null) !== ownerUser) throw sessionChangedError()
    if (response.statusCode < 200 || response.statusCode >= 300) throw toApiError(response.statusCode, response.data, response.header, 'Tesla 授权入口暂时不可用')
    const value = unwrap(response.data), parsed = parseHttpsURL(value.authorization_url)
    if (!parsed || !isTrustedAuthorizationURL(value.authorization_url) || parsed.path === '/user/revoke/consent')
      throw new ApiError('服务端未返回可信的 Tesla 官方授权入口', { code: 'invalid_authorization_response' })
    const transactionId = stringValue(value.transaction_id), clientProof = stringValue(value.client_proof)
    if (!transactionId || !clientProof) throw new ApiError('服务端未返回有效的授权事务凭证', { code: 'invalid_authorization_response' })
    writePendingTeslaAuthorization(Taro, { transactionId, clientProof, apiOrigin, expiresAt: expiresAt(value.expires_at, null) })
    // Replacement fences late results immediately; old server transactions also expire.
    if (previous && previous.apiOrigin === apiOrigin && previous.transactionId !== transactionId)
      void cancelTeslaAuthorizationRecord(previous).catch(() => {})
    return { authorization_url: value.authorization_url, transaction_id: transactionId,
      client_proof: clientProof, expires_at: value.expires_at ?? null }
    } finally { teslaStartInFlight -= 1 }
  },

  async getTeslaAuthorizationStatus(): Promise<TeslaAuthorizationStatus> {
    const pending = readPendingTeslaAuthorization(Taro)
    if (!pending) return { status: 'none', expiresAt: null }
    if (pending.apiOrigin !== getApiOrigin()) throw sessionChangedError()
    const epoch = authorizationEpoch, ownerEpoch = sessionEpoch, ownerUser = readAppSession(Taro)?.userId ?? null
    const response = await rawRequest<ApiEnvelope<{ status?: string; expires_at?: string | null }> | { status?: string; expires_at?: string | null }>('/v1/auth/miniprogram/status', {
      method: 'POST', data: { transaction_id: pending.transactionId, client_proof: pending.clientProof },
      header: { 'Content-Type': 'application/json' },
    })
    const current = readPendingTeslaAuthorization(Taro)
    if (epoch !== authorizationEpoch || ownerEpoch !== sessionEpoch || ownerUser !== (readAppSession(Taro)?.userId ?? null) ||
        current?.transactionId !== pending.transactionId || current?.clientProof !== pending.clientProof || current?.apiOrigin !== pending.apiOrigin) throw sessionChangedError()
    if (response.statusCode < 200 || response.statusCode >= 300) throw toApiError(response.statusCode, response.data, response.header, 'Tesla 授权状态查询失败')
    const payload = unwrap(response.data), status = stringValue(payload.status)
    if (!status || !['pending', 'ready', 'expired', 'cancelled', 'consumed', 'failed'].includes(status))
      throw new ApiError(errorMessages.invalid_api_response, { code: 'invalid_api_response' })
    return { status, expiresAt: expiresAt(payload.expires_at, null) ?? pending.expiresAt }
  },

  async claimTeslaAuthorization(): Promise<AppSession> {
    if (accountDeletionInFlight) throw sessionChangedError()
    const pending = readPendingTeslaAuthorization(Taro)
    if (!pending) throw new ApiError(errorMessages.authorization_expired, { code: 'authorization_expired' })
    const ownerEpoch = sessionEpoch, epoch = authorizationEpoch
    const ownerUser = readAppSession(Taro)?.userId ?? null, apiOrigin = getApiOrigin()
    if (pending.apiOrigin !== apiOrigin) throw sessionChangedError()
    const ownerKey = JSON.stringify([apiOrigin, ownerUser, ownerEpoch, epoch, pending.transactionId, pending.clientProof])
    const existing = teslaClaimPromises.get(ownerKey)
    if (existing) return existing
    const claim = (async () => {
      const response = await rawRequest<AuthSessionResponse>('/v1/auth/miniprogram/claim', {
        method: 'POST', data: { transaction_id: pending.transactionId, client_proof: pending.clientProof },
        header: { 'Content-Type': 'application/json' },
      })
      const currentPending = readPendingTeslaAuthorization(Taro)
      if (epoch !== authorizationEpoch || ownerEpoch !== sessionEpoch || (readAppSession(Taro)?.userId ?? null) !== ownerUser ||
          !currentPending || currentPending.transactionId !== pending.transactionId || currentPending.clientProof !== pending.clientProof || currentPending.apiOrigin !== apiOrigin) throw sessionChangedError()
      if (response.statusCode < 200 || response.statusCode >= 300) throw toApiError(response.statusCode, response.data, response.header, 'Tesla 授权领取失败')
      const session = requireSessionIdentity(normalizeSession(unwrap(response.data), null))
      fenceRecoveryForTeslaAccount(apiOrigin, session.userId!)
      writeAppSession(Taro, session)
      clearPendingTeslaAuthorization(Taro)
      clearPendingWechatLink(Taro)
      sessionEpoch += 1
      authorizationEpoch += 1
      return session
    })()
    teslaClaimPromises.set(ownerKey, claim)
    try { return await claim } finally { if (teslaClaimPromises.get(ownerKey) === claim) teslaClaimPromises.delete(ownerKey) }
  },

  async cancelTeslaAuthorization(): Promise<void> {
    const pending = readPendingTeslaAuthorization(Taro)
    if (!pending) return
    if (pending.apiOrigin !== getApiOrigin()) throw sessionChangedError()
    const epoch = ++authorizationEpoch, ownerEpoch = sessionEpoch
    await cancelTeslaAuthorizationRecord(pending)
    if (epoch === authorizationEpoch && ownerEpoch === sessionEpoch) {
      const current = readPendingTeslaAuthorization(Taro)
      if (current?.transactionId === pending.transactionId && current.clientProof === pending.clientProof && current.apiOrigin === pending.apiOrigin) clearPendingTeslaAuthorization(Taro)
    }
  },

  async exchangeTeslaTicket(ticket: string): Promise<AppSession> {
    if (accountDeletionInFlight) throw sessionChangedError()
    if (!ticket.trim()) throw new ApiError('授权凭证为空', { code: 'invalid_login_ticket' })
    const key = ticket.trim()
    const existing = ticketExchangePromises.get(key)
    if (existing) return existing
    const epoch = ++sessionEpoch
    ticketExchangeInFlight += 1
    const exchange = (async () => {
      const response = await rawRequest<AuthSessionResponse>('/v1/auth/exchange', {
        method: 'POST',
        data: { ticket: key },
        header: { 'Content-Type': 'application/json' },
      })
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw toApiError(response.statusCode, response.data, response.header, 'Tesla 授权交换失败')
      }
      if (epoch !== sessionEpoch) throw sessionChangedError()
      const session = requireSessionIdentity(normalizeSession(unwrap(response.data), null))
      fenceRecoveryForTeslaAccount(getApiOrigin(), session.userId!)
      clearPendingTeslaAuthorization(Taro)
      clearPendingWechatLink(Taro)
      writeAppSession(Taro, session)
      return session
    })()
    ticketExchangePromises.set(key, exchange)
    try {
      return await exchange
    } finally {
      ticketExchangePromises.delete(key)
      ticketExchangeInFlight -= 1
    }
  },

  async getDrives(carId: number, page = 1, show = DEFAULT_PAGE_SIZE, car?: Car): Promise<HistoryPage<Drive>> {
    assertVehicle(null, carId, car)
    const response = await requestJson<ApiEnvelope<{ drives?: RawDrive[]; meta?: PageMeta }> | { drives?: RawDrive[]; meta?: PageMeta }>(historyPath(carId, 'drives', page, show))
    assertVehicle(unwrap(response), carId, car)
    return historyResult(response, 'drives', page, show, value => { assertVehicle(value, carId, car); return normalizeDrive(value as RawDrive, car, responseDistanceUnit(response), responseTemperatureUnit(response)) })
  },

  async getDriveDetail(carId: number, driveId: string, car?: Car): Promise<Drive | null> {
    assertVehicle(null, carId, car)
    const response = await requestJson<ApiEnvelope<{ drive?: RawDrive }> | { drive?: RawDrive }>(`/api/v1/cars/${carId}/drives/${safeRecordId(driveId)}`)
    assertVehicle(unwrap(response), carId, car)
    const result = detailResult(response, 'drive', value => { assertVehicle(value, carId, car); return value ? normalizeDrive(value as RawDrive, car, responseDistanceUnit(response), responseTemperatureUnit(response)) : null })
    if (result && result.id !== driveId) throw new ApiError(errorMessages.history_unavailable, { code: 'history_unavailable' })
    return result
  },

  async getCharges(carId: number, page = 1, show = DEFAULT_PAGE_SIZE, car?: Car): Promise<HistoryPage<Charge>> {
    assertVehicle(null, carId, car)
    const response = await requestJson<ApiEnvelope<{ charges?: RawCharge[]; meta?: PageMeta }> | { charges?: RawCharge[]; meta?: PageMeta }>(historyPath(carId, 'charges', page, show))
    assertVehicle(unwrap(response), carId, car)
    return historyResult(response, 'charges', page, show, value => { assertVehicle(value, carId, car); return normalizeCharge(value as RawCharge) })
  },

  async getChargeDetail(carId: number, chargeId: string, car?: Car): Promise<Charge | null> {
    assertVehicle(null, carId, car)
    const response = await requestJson<ApiEnvelope<{ charge?: RawCharge }> | { charge?: RawCharge }>(`/api/v1/cars/${carId}/charges/${safeRecordId(chargeId)}`)
    assertVehicle(unwrap(response), carId, car)
    const result = detailResult(response, 'charge', value => { assertVehicle(value, carId, car); return value ? normalizeCharge(value as RawCharge) : null })
    if (result && result.id !== chargeId) throw new ApiError(errorMessages.history_unavailable, { code: 'history_unavailable' })
    return result
  },

  async getCurrentCharge(carId: number, car?: Car): Promise<Charge | null> {
    assertVehicle(null, carId, car)
    const response = await requestJson<ApiEnvelope<{ charge?: RawCharge | null }> | { charge?: RawCharge | null }>(`/api/v1/cars/${carId}/charges/current`)
    assertVehicle(unwrap(response), carId, car)
    return detailResult(response, 'charge', value => { assertVehicle(value, carId, car); return value ? normalizeCharge(value as RawCharge) : null })
  },

  async validateInviteCode(code: string): Promise<{ valid: boolean; code?: string; remaining?: number; reason?: string }> {
    const response = await requestJson<{ valid: boolean; code?: string; remaining?: number; reason?: string }>(
      '/v1/auth/invite/validate',
      {
        method: 'POST',
        data: { code: code.trim().toUpperCase() },
      },
    )
    return response
  },

  async claimInviteCode(code: string, clientType = 'miniprogram'): Promise<{ success: boolean; code?: string; error?: string }> {
    const response = await requestJson<{ success: boolean; code?: string; error?: string }>(
      '/v1/auth/invite/claim',
      {
        method: 'POST',
        data: { code: code.trim().toUpperCase(), client_type: clientType },
      },
    )
    return response
  },
}

export function resetApiSessionForTests(): void {
  verifiedCarsScope = null
  verifiedCars.clear()
  refreshPromise = null
  refreshOwnerEpoch = -1
  refreshOwnerUser = null
  ticketExchangePromises.clear()
  teslaClaimPromises.clear()
  sessionEpoch = 0
  authorizationEpoch = 0
  accountDeletionInFlight = false
  teslaStartInFlight = 0
  ticketExchangeInFlight = 0
  wechatMutationInFlight = false
  wechatRestoreFlight = null
  bootstrapFlight = null
  wechatRecoveryFailure = null
  suppressedRecoveryOrigins.clear()
  automaticRecoveryAttempts.clear()
}
