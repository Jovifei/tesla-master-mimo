import Taro from '@tarojs/taro'
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
  WechatAuthorizationStatus,
} from './types'
import {
  clearAppSession,
  clearPendingTeslaAuthorization,
  clearPendingWechatLink,
  readAppSession,
  readPendingTeslaAuthorization,
  readPendingWechatLink,
  writeAppSession,
  writePendingTeslaAuthorization,
  writePendingWechatLink,
  type AppSession,
} from './session'

const apiBaseUrl = (process.env.TARO_APP_API_BASE_URL ?? '').replace(/\/$/, '')
const DEFAULT_PAGE_SIZE = 20

const errorMessages: Record<string, string> = {
  api_not_configured: '服务地址尚未配置',
  wechat_auth_not_configured: '服务端尚未配置微信登录，请联系管理员',
  session_required: '请先完成微信登录',
  consent_required: '请先同意服务条款和隐私指引',
  privacy_required: '请先完成微信隐私授权',
  wechat_login_failed: '微信登录凭证获取失败，请重试',
  wechat_link_expired: '微信关联已过期，请重新开始登录',
  invalid_link_response: '服务端未返回有效的关联凭证',
  invalid_session_response: '服务端未返回有效会话',
  session_changed: '会话已切换，请重新读取数据',
  network_error: '网络暂时不可用，请稍后重试',
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
  wechat_authorization_pending: 'Tesla 授权尚未返回，请完成官方页面后再检查',
  wechat_authorization_expired: 'Tesla 授权事务已过期，请重新开始授权',
  wechat_authorization_failed: 'Tesla 授权未完成，请重新开始授权',
  wechat_authorization_conflict: 'Tesla 账号与当前微信账号不匹配',
  wechat_authorization_claimed: '授权已领取，请重新登录微信恢复会话',
  wechat_authorization_cancelled: 'Tesla 授权已取消，请重新开始授权',
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
  if (!apiBaseUrl || !apiBaseUrl.startsWith('https://')) throw new ApiConfigurationError()
  return apiBaseUrl
}

export function getApiOrigin(): string {
  return requireApiBaseUrl()
}

export function isTrustedAuthorizationURL(raw: string): boolean {
  const parsed = parseHttpsURL(raw)
  if (!parsed) return false
  const apiHost = parseHttpsURL(requireApiBaseUrl())?.hostname
  const owned = parsed.hostname === apiHost || parsed.hostname === 'auth.teslalink.joviluma.com'
  const tesla = parsed.hostname === 'auth.tesla.cn' || parsed.hostname === 'auth.tesla.com'
  if (owned) return parsed.path === '/oauth/wechat/authorize' || parsed.path === '/oauth/callback'
  if (tesla) return parsed.path === '/oauth2/v3/authorize' || parsed.path === '/authorize'
  return false
}

type ParsedHttpsURL = { hostname: string; path: string }

function parseHttpsURL(raw: string): ParsedHttpsURL | null {
  if (typeof raw !== 'string' || raw.trim() !== raw || !raw.startsWith('https://')) return null
  const rest = raw.slice('https://'.length)
  const authorityEnd = rest.search(/[/?#]/)
  const authority = authorityEnd < 0 ? rest : rest.slice(0, authorityEnd)
  if (!authority || authority.includes('@') || authority.includes('\\') || authority.includes(' ')) return null
  const portIndex = authority.lastIndexOf(':')
  const hostname = (portIndex >= 0 ? authority.slice(0, portIndex) : authority).toLowerCase()
  const port = portIndex >= 0 ? authority.slice(portIndex + 1) : ''
  if (!hostname || (portIndex >= 0 && !port) || (port && port !== '443') || (port && !/^\d+$/.test(port)) || !/^[a-z0-9.-]+$/.test(hostname)) return null
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
  const message = stringValue(payload.message) ?? errorMessages[code] ?? fallback
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
    return await Taro.request<T>({
      ...options,
      url: `${requireApiBaseUrl()}${path}`,
    })
  } catch (reason) {
    if (reason instanceof ApiError) throw reason
    throw new ApiError(errorMessages.network_error, { code: 'network_error', cause: reason })
  }
}

async function requireWechatPrivacyAuthorization(): Promise<void> {
  type PrivacyCapableTaro = typeof Taro & {
    requirePrivacyAuthorize?: (options: { success: () => void; fail: (reason: unknown) => void }) => void
  }
  const api = Taro as PrivacyCapableTaro
  if (typeof api.requirePrivacyAuthorize !== 'function') return
  await new Promise<void>((resolve, reject) => {
    api.requirePrivacyAuthorize?.({ success: resolve, fail: reject })
  })
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
const wechatClaimPromises = new Map<string, Promise<AppSession>>()
let sessionEpoch = 0

// Non-secret generation for page lifecycle guards; never use tokens in cache keys.
export function getApiSessionGeneration(): number { return sessionEpoch }

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
  const epoch = sessionEpoch
  const ownerUser = readAppSession(Taro)?.userId ?? null
  const assertCurrent = () => {
    if (epoch !== sessionEpoch || (readAppSession(Taro)?.userId ?? null) !== ownerUser) throw sessionChangedError()
  }
  let clearedOwnSession = false
  const attempt = async (allowRefresh: boolean): Promise<T> => {
    assertCurrent()
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
    if (response.statusCode === 401 && allowRefresh && path !== '/v1/session/refresh') {
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
            if (latest?.refreshToken === current.refreshToken) {
              sessionEpoch += 1
              clearAppSession(Taro)
              clearedOwnSession = true
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
  const idValue = raw.car_id ?? raw.id
  const id = typeof idValue === 'number' ? idValue : Number(idValue)
  const stableId = stringValue(raw.vehicle_uid) ?? stringValue(raw.provider_vehicle_id)
  if (!Number.isInteger(id) || id <= 0 || !stableId) return null
  const model = raw.model ?? raw.car_details?.model ?? null
  const name = raw.display_name ?? raw.name ?? null
  return {
    id,
    stableId,
    name,
    model,
    trim: raw.car_details?.trim_badging ?? null,
    exteriorColor: raw.car_exterior?.exterior_color ?? null,
    wheelType: raw.car_exterior?.wheel_type ?? null,
    displayName: name?.trim() || (model ? `Model ${model}` : 'Tesla'),
  }
}

function normalizeCarStatus(raw: RawCarStatus, observedAt: string | null, source: string | null): CarStatus {
  const latitude = raw.car_geodata?.latitude
  const longitude = raw.car_geodata?.longitude
  const location = typeof latitude === 'number' && typeof longitude === 'number'
    ? { latitude, longitude }
    : null
  return {
    state: raw.state ?? null,
    model: stringValue(raw.model),
    trim: stringValue(raw.trim_badging),
    exteriorColor: stringValue(raw.exterior_color),
    wheelType: stringValue(raw.wheel_type),
    batteryLevel: raw.battery_details?.battery_level ?? null,
    ratedRange: raw.battery_details?.rated_battery_range ?? null,
    odometer: raw.odometer ?? null,
    location,
    locked: raw.car_status?.locked ?? null,
    sentryMode: raw.car_status?.sentry_mode ?? null,
    windowsOpen: raw.car_status?.windows_open ?? null,
    doorsOpen: raw.car_status?.doors_open ?? null,
    trunkOpen: raw.car_status?.trunk_open ?? null,
    frunkOpen: raw.car_status?.frunk_open ?? null,
    isClimateOn: raw.climate_details?.is_climate_on ?? null,
    insideTemp: raw.climate_details?.inside_temp ?? null,
    outsideTemp: raw.climate_details?.outside_temp ?? null,
    pluggedIn: raw.charging_details?.plugged_in ?? null,
    speed: raw.driving_details?.speed ?? null,
    power: raw.driving_details?.power ?? null,
    shiftState: raw.driving_details?.shift_state ?? null,
    heading: raw.driving_details?.heading ?? null,
    chargingState: raw.charging_details?.charging_state ?? null,
    chargeEnergyAdded: raw.charging_details?.charge_energy_added ?? null,
    chargerPower: raw.charging_details?.charger_power ?? null,
    observedAt,
    source,
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

function normalizeDrive(raw: RawDrive): Drive | null {
  const rawId = raw.drive_id ?? raw.session_id
  if (rawId === undefined || rawId === null || String(rawId).trim() === '') return null
  return {
    id: String(rawId),
    sessionId: stringValue(raw.session_id),
    startDate: raw.start_date ?? null,
    endDate: raw.end_date ?? null,
    startAddress: raw.start_address ?? null,
    endAddress: raw.end_address ?? null,
    durationMinutes: raw.duration_min ?? null,
    distanceKm: raw.odometer_details?.odometer_distance ?? null,
    energyConsumedKwh: raw.energy_consumed_net ?? null,
    startBatteryLevel: raw.battery_details?.start_battery_level ?? null,
    endBatteryLevel: raw.battery_details?.end_battery_level ?? null,
    startLatitude: raw.start_latitude ?? null,
    startLongitude: raw.start_longitude ?? null,
    endLatitude: raw.end_latitude ?? null,
    endLongitude: raw.end_longitude ?? null,
    source: raw.source ?? null,
    qualityState: raw.quality_state ?? null,
    qualityReason: raw.quality_reason ?? null,
  }
}

function normalizeCharge(raw: RawCharge): Charge | null {
  const rawId = raw.charge_id ?? raw.session_id
  if (rawId === undefined || rawId === null || String(rawId).trim() === '') return null
  return {
    id: String(rawId),
    sessionId: stringValue(raw.session_id),
    startDate: raw.start_date ?? null,
    endDate: raw.end_date ?? null,
    address: raw.address ?? null,
    durationMinutes: raw.duration_min ?? null,
    energyAddedKwh: raw.charge_energy_added ?? null,
    energyUsedKwh: raw.charge_energy_used ?? null,
    cost: raw.cost ?? null,
    startBatteryLevel: raw.battery_details?.start_battery_level ?? null,
    endBatteryLevel: raw.battery_details?.end_battery_level ?? null,
    latitude: raw.latitude ?? null,
    longitude: raw.longitude ?? null,
    chargerPowerKw: raw.charger_power ?? null,
    source: raw.source ?? null,
    qualityState: raw.quality_state ?? null,
    qualityReason: raw.quality_reason ?? null,
  }
}

function historyPath(carId: number, kind: 'drives' | 'charges', page: number, show: number): string {
  const params = `page=${encodeURIComponent(String(page))}&show=${encodeURIComponent(String(show))}`
  return `/api/v1/cars/${carId}/${kind}?${params}`
}

function historyResult<T>(raw: unknown, key: 'drives' | 'charges', page: number, show: number, mapper: (item: unknown) => T | null): HistoryPage<T> {
  const data = unwrap(raw as ApiEnvelope<UnknownRecord> | UnknownRecord)
  const record = isRecord(data) ? data : {}
  const rows = Array.isArray(record[key]) ? record[key] : []
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

export type WechatConsent = {
  termsVersion: string
  privacyVersion: string
}

export const JOURVOLT_TERMS_VERSION = '2026-08-21'
export const JOURVOLT_PRIVACY_VERSION = '2026-08-21'

export type WechatLoginResult =
  | { status: 'authenticated'; session: AppSession }
  | { status: 'link_required'; expiresAt: string | null }

async function cancelWechatAuthorizationRecord(pending: { transactionId: string; clientProof: string }, headers: TaroGeneral.IAnyObject): Promise<void> {
  const response = await rawRequest('/v1/auth/wechat/cancel', {
    method: 'POST',
    data: { transaction_id: pending.transactionId, client_proof: pending.clientProof },
    header: { ...headers, 'Content-Type': 'application/json' },
  })
  if (response.statusCode >= 300 && response.statusCode !== 401 && response.statusCode !== 410) {
    throw toApiError(response.statusCode, response.data, response.header, '取消 Tesla 授权失败')
  }
}

export const matelinkApi = {
  async loginWithWechat(consent: WechatConsent): Promise<WechatLoginResult> {
    requireApiBaseUrl()
    const epoch = ++sessionEpoch
    clearPendingTeslaAuthorization(Taro)
    try {
      await requireWechatPrivacyAuthorization()
    } catch (reason) {
      throw new ApiError(errorMessages.privacy_required, { code: 'privacy_required', cause: reason })
    }
    let code: string
    try {
      const result = await Taro.login()
      code = stringValue(result.code) ?? ''
    } catch (reason) {
      throw new ApiError(errorMessages.wechat_login_failed, { code: 'wechat_login_failed', cause: reason })
    }
    if (!code) throw new ApiError(errorMessages.wechat_login_failed, { code: 'wechat_login_failed' })
    const response = await rawRequest<AuthSessionResponse>('/v1/auth/wechat/session', {
      method: 'POST',
      data: {
        code,
        ...(consent ? { terms_version: consent.termsVersion, privacy_version: consent.privacyVersion } : {}),
      },
      header: { 'Content-Type': 'application/json' },
    })
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw toApiError(response.statusCode, response.data, response.header, '微信会话建立失败')
    }
    const payload = unwrap(response.data)
    if (payload.status === 'link_required' || (!payload.access_token && payload.link_token)) {
      if (epoch !== sessionEpoch) throw sessionChangedError()
      const linkToken = stringValue(payload.link_token)
      if (!linkToken) throw new ApiError('服务端未返回有效的账号关联凭证', { code: 'invalid_link_response' })
      clearAppSession(Taro)
      writePendingWechatLink(Taro, {
        linkToken,
        expiresAt: expiresAt(payload.expires_at, null),
      })
      return { status: 'link_required', expiresAt: expiresAt(payload.expires_at, null) }
    }
    if (epoch !== sessionEpoch) throw sessionChangedError()
    const session = requireSessionIdentity(normalizeSession(payload, null))
    clearPendingWechatLink(Taro)
    writeAppSession(Taro, session)
    return { status: 'authenticated', session }
  },

  async logout(): Promise<void> {
    const session = readAppSession(Taro)
    const pending = readPendingTeslaAuthorization(Taro)
    const capturedHeaders = session ? { Authorization: `Bearer ${session.accessToken}` } : {}
    sessionEpoch += 1
    clearAppSession(Taro)
    let pendingOriginMatches = false
    try { pendingOriginMatches = Boolean(pending && pending.apiOrigin === getApiOrigin()) } catch { pendingOriginMatches = false }
    if (pending && pendingOriginMatches) {
      try { await cancelWechatAuthorizationRecord(pending, capturedHeaders) } catch { /* local logout must still complete */ }
    }
    if (!session) return
    const response = await rawRequest('/v1/session/logout', {
      method: 'POST',
      header: { ...capturedHeaders, 'Content-Type': 'application/json' },
    })
    if (response.statusCode >= 300 && response.statusCode !== 401) {
      throw toApiError(response.statusCode, response.data, response.header, '退出登录失败')
    }
  },

  async getCars(): Promise<Car[]> {
    const response = await requestJson<ApiEnvelope<{ cars?: RawCar[] }> | { cars?: RawCar[] }>('/api/v1/cars')
    const cars = unwrap(response).cars
    return (Array.isArray(cars) ? cars : []).map(normalizeCar).filter((car): car is Car => car !== null)
  },

  async getCarStatus(carId: number): Promise<CarStatus> {
    const response = await requestJson<ApiEnvelope<{ status?: RawCarStatus; observed_at?: string; source?: string }> | { status?: RawCarStatus; observed_at?: string; source?: string }>(`/api/v1/cars/${carId}/status`)
    const data = unwrap(response)
    return normalizeCarStatus(data.status ?? {}, data.observed_at ?? null, data.source ?? null)
  },

  async getReadiness(carId: number): Promise<DataReadiness> {
    const response = await requestJson<ApiEnvelope<DataReadiness> | DataReadiness>(`/api/v1/cars/${carId}/data-readiness`)
    const data = unwrap(response)
    return { ...data, items: Array.isArray(data.items) ? data.items : [] }
  },

  async getTelemetryPairing(carId: number): Promise<TelemetryPairing> {
    const response = await requestJson<ApiEnvelope<TelemetryPairing> | TelemetryPairing>(`/api/v1/cars/${carId}/telemetry/pairing`)
    return unwrap(response)
  },

  async configureTelemetry(carId: number): Promise<{ status: string | null }> {
    const response = await requestJson<ApiEnvelope<{ status?: string | null }> | { status?: string | null }>(`/api/v1/cars/${carId}/telemetry/configure`, {
      method: 'POST',
      header: { 'Content-Type': 'application/json' },
      data: {},
    })
    const data = unwrap(response)
    return { status: data.status ?? null }
  },

  async startTeslaAuthorization(consent: WechatConsent): Promise<TeslaAuthorization> {
    const apiOrigin = getApiOrigin()
    const pendingLink = readPendingWechatLink(Taro)
    const response = await requestJson<ApiEnvelope<TeslaAuthorization> | TeslaAuthorization>('/v1/auth/tesla/start', {
      header: {
        'X-JourVolt-Terms-Version': consent.termsVersion,
        'X-JourVolt-Privacy-Version': consent.privacyVersion,
        'X-WeChat-Channel': 'wechat',
        ...(pendingLink ? { 'X-WeChat-Link-Token': pendingLink.linkToken } : {}),
      },
    })
    const value = unwrap(response)
    if (!stringValue(value.authorization_url) || !isTrustedAuthorizationURL(value.authorization_url)) throw new ApiError('服务端未返回可信的 Tesla 官方授权入口', { code: 'invalid_authorization_response' })
    const transactionId = stringValue(value.transaction_id)
    const clientProof = stringValue(value.client_proof)
    if (!transactionId || !clientProof) throw new ApiError('服务端未返回有效的授权事务凭证', { code: 'invalid_authorization_response' })
    if (value.web_authorization_url && !isTrustedAuthorizationURL(value.web_authorization_url)) throw new ApiError('服务端未返回可信的微信授权桥接入口', { code: 'invalid_authorization_response' })
    writePendingTeslaAuthorization(Taro, {
      transactionId,
      clientProof,
      apiOrigin,
      expiresAt: expiresAt(value.expires_at, null),
    })
    return {
      authorization_url: value.authorization_url,
      web_authorization_url: value.web_authorization_url ?? null,
      transaction_id: transactionId,
      client_proof: clientProof,
      channel: 'wechat',
      expires_at: value.expires_at ?? null,
    }
  },

  async getWechatAuthorizationStatus(): Promise<WechatAuthorizationStatus> {
    const pending = readPendingTeslaAuthorization(Taro)
    if (!pending) return { status: 'none', expiresAt: null }
    if (pending.apiOrigin !== getApiOrigin()) throw sessionChangedError()
    const response = await rawRequest<ApiEnvelope<{ status?: string; expires_at?: string | null }> | { status?: string; expires_at?: string | null }>('/v1/auth/wechat/status', {
      method: 'POST',
      data: { transaction_id: pending.transactionId, client_proof: pending.clientProof },
      header: { ...sessionHeaders(), 'Content-Type': 'application/json' },
    })
    if (response.statusCode < 200 || response.statusCode >= 300) throw toApiError(response.statusCode, response.data, response.header, 'Tesla 授权状态查询失败')
    const payload = unwrap(response.data)
    return { status: stringValue(payload.status) ?? 'unknown', expiresAt: expiresAt(payload.expires_at, null) ?? pending.expiresAt }
  },

  async claimWechatAuthorization(callbackRef = ''): Promise<AppSession> {
    const pending = readPendingTeslaAuthorization(Taro)
    if (!pending) throw new ApiError(errorMessages.wechat_authorization_expired, { code: 'wechat_authorization_expired' })
    const ownerEpoch = sessionEpoch
    const ownerUser = readAppSession(Taro)?.userId ?? null
    const apiOrigin = getApiOrigin()
    if (pending.apiOrigin !== apiOrigin) throw sessionChangedError()
    const ownerKey = `${apiOrigin}|${ownerUser || 'anonymous'}|${ownerEpoch}|${pending.transactionId}`
    const existing = wechatClaimPromises.get(ownerKey)
    if (existing) return existing
    const claim = (async () => {
      const response = await rawRequest<AuthSessionResponse>('/v1/auth/wechat/claim', {
        method: 'POST',
        data: {
          transaction_id: pending.transactionId,
          client_proof: pending.clientProof,
          ...(callbackRef.trim() ? { callback_ref: callbackRef.trim() } : {}),
        },
        header: { ...sessionHeaders(), 'Content-Type': 'application/json' },
      })
      if (response.statusCode < 200 || response.statusCode >= 300) throw toApiError(response.statusCode, response.data, response.header, 'Tesla 授权领取失败')
      const currentPending = readPendingTeslaAuthorization(Taro)
      if (ownerEpoch !== sessionEpoch || (readAppSession(Taro)?.userId ?? null) !== ownerUser || !currentPending || currentPending.transactionId !== pending.transactionId || currentPending.clientProof !== pending.clientProof) throw sessionChangedError()
      const session = requireSessionIdentity(normalizeSession(unwrap(response.data), null))
      writeAppSession(Taro, session)
      clearPendingTeslaAuthorization(Taro)
      clearPendingWechatLink(Taro)
      sessionEpoch += 1
      return session
    })()
    wechatClaimPromises.set(ownerKey, claim)
    try { return await claim } finally { if (wechatClaimPromises.get(ownerKey) === claim) wechatClaimPromises.delete(ownerKey) }
  },

  async cancelWechatAuthorization(): Promise<void> {
    const pending = readPendingTeslaAuthorization(Taro)
    if (!pending) return
    if (pending.apiOrigin !== getApiOrigin()) throw sessionChangedError()
    const ownerEpoch = sessionEpoch
    const capturedHeaders = sessionHeaders()
    await cancelWechatAuthorizationRecord(pending, capturedHeaders)
    if (ownerEpoch === sessionEpoch) {
      const current = readPendingTeslaAuthorization(Taro)
      if (current?.transactionId === pending.transactionId && current.clientProof === pending.clientProof) clearPendingTeslaAuthorization(Taro)
    }
  },

  async exchangeTeslaTicket(ticket: string): Promise<AppSession> {
    if (!ticket.trim()) throw new ApiError('授权凭证为空', { code: 'invalid_login_ticket' })
    const key = ticket.trim()
    const existing = ticketExchangePromises.get(key)
    if (existing) return existing
    const epoch = ++sessionEpoch
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
    }
  },

  async getDrives(carId: number, page = 1, show = DEFAULT_PAGE_SIZE): Promise<HistoryPage<Drive>> {
    const response = await requestJson<ApiEnvelope<{ drives?: RawDrive[]; meta?: PageMeta }> | { drives?: RawDrive[]; meta?: PageMeta }>(historyPath(carId, 'drives', page, show))
    return historyResult(response, 'drives', page, show, value => normalizeDrive(value as RawDrive))
  },

  async getDriveDetail(carId: number, driveId: string): Promise<Drive | null> {
    const response = await requestJson<ApiEnvelope<{ drive?: RawDrive }> | { drive?: RawDrive }>(`/api/v1/cars/${carId}/drives/${encodeURIComponent(driveId)}`)
    return detailResult(response, 'drive', value => value ? normalizeDrive(value as RawDrive) : null)
  },

  async getCharges(carId: number, page = 1, show = DEFAULT_PAGE_SIZE): Promise<HistoryPage<Charge>> {
    const response = await requestJson<ApiEnvelope<{ charges?: RawCharge[]; meta?: PageMeta }> | { charges?: RawCharge[]; meta?: PageMeta }>(historyPath(carId, 'charges', page, show))
    return historyResult(response, 'charges', page, show, value => normalizeCharge(value as RawCharge))
  },

  async getChargeDetail(carId: number, chargeId: string): Promise<Charge | null> {
    const response = await requestJson<ApiEnvelope<{ charge?: RawCharge }> | { charge?: RawCharge }>(`/api/v1/cars/${carId}/charges/${encodeURIComponent(chargeId)}`)
    return detailResult(response, 'charge', value => value ? normalizeCharge(value as RawCharge) : null)
  },

  async getCurrentCharge(carId: number): Promise<Charge | null> {
    const response = await requestJson<ApiEnvelope<{ charge?: RawCharge | null }> | { charge?: RawCharge | null }>(`/api/v1/cars/${carId}/charges/current`)
    return detailResult(response, 'charge', value => value ? normalizeCharge(value as RawCharge) : null)
  },
}

export function resetApiSessionForTests(): void {
  refreshPromise = null
  refreshOwnerEpoch = -1
  refreshOwnerUser = null
  ticketExchangePromises.clear()
  sessionEpoch = 0
}
