import Taro from '@tarojs/taro'
import type { ApiEnvelope, Car, CarStatus, DataReadiness, RawCar, RawCarStatus, TelemetryPairing } from './types'
import { readAppSession } from './session'

const apiBaseUrl = (process.env.TARO_APP_API_BASE_URL ?? '').replace(/\/$/, '')

export class ApiConfigurationError extends Error {
  constructor() {
    super('TARO_APP_API_BASE_URL is not configured')
    this.name = 'ApiConfigurationError'
  }
}

function requireApiBaseUrl(): string {
  if (!apiBaseUrl) throw new ApiConfigurationError()
  if (!apiBaseUrl.startsWith('https://')) throw new ApiConfigurationError()
  return apiBaseUrl
}

async function requestJson<T>(path: string, options: Omit<Taro.request.Option, 'url'> = {}): Promise<T> {
  const session = readAppSession(Taro)
  const response = await Taro.request<T>({
    ...options,
    url: `${requireApiBaseUrl()}${path}`,
    header: {
      ...(options.header ?? {}),
      ...(session ? { Authorization: `Bearer ${session.accessToken}` } : {}),
    },
  })
  if (response.statusCode < 200 || response.statusCode >= 300) {
    throw new Error(`MateLink API ${response.statusCode}`)
  }
  return response.data
}

function unwrap<T>(value: T | ApiEnvelope<T>): T {
  if (value && typeof value === 'object' && 'data' in value && value.data !== undefined) {
    return value.data as T
  }
  return value as T
}

function normalizeCar(raw: RawCar): Car {
  const model = raw.model ?? raw.car_details?.model ?? null
  const name = raw.name ?? null
  return {
    id: raw.car_id ?? raw.id ?? 0,
    name,
    model,
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
    batteryLevel: raw.battery_details?.battery_level ?? null,
    ratedRange: raw.battery_details?.rated_battery_range ?? null,
    odometer: raw.odometer ?? null,
    location,
    observedAt,
    source,
  }
}

export const matelinkApi = {
  async loginWithWechat(): Promise<never> {
    throw new Error('WeChat session exchange is not implemented in the existing backend')
  },

  async getCars(): Promise<Car[]> {
    const response = await requestJson<ApiEnvelope<{ cars?: RawCar[] }> | { cars?: RawCar[] }>('/api/v1/cars')
    return (unwrap(response).cars ?? []).map(normalizeCar).filter(car => car.id > 0)
  },

  async getCarStatus(carId: number): Promise<CarStatus> {
    const response = await requestJson<ApiEnvelope<{ status?: RawCarStatus; observed_at?: string; source?: string }> | { status?: RawCarStatus; observed_at?: string; source?: string }>(`/api/v1/cars/${carId}/status`)
    const data = unwrap(response)
    return normalizeCarStatus(data.status ?? {}, data.observed_at ?? null, data.source ?? null)
  },

  async getReadiness(carId: number): Promise<DataReadiness> {
    const response = await requestJson<ApiEnvelope<DataReadiness> | DataReadiness>(`/api/v1/cars/${carId}/data-readiness`)
    return unwrap(response)
  },

  async getTelemetryPairing(carId: number): Promise<TelemetryPairing> {
    const response = await requestJson<ApiEnvelope<TelemetryPairing> | TelemetryPairing>(`/api/v1/cars/${carId}/telemetry/pairing`)
    return unwrap(response)
  },
}
