import mockData from '../mock_data.json';
import { useStore } from '../store';
import type { Car, CarStatus, Drive, Charge, BatteryHealth, SoftwareUpdate } from './types';

function delay<T>(v: T, ms = 300): Promise<T> {
  return new Promise(r => setTimeout(() => r(v), ms));
}

export class ApiModeError extends Error {
  readonly status?: number;

  constructor(message: string, status?: number) {
    super(message);
    this.name = 'ApiModeError';
    this.status = status;
  }
}

export function getApiErrorMessage(error: unknown): string {
  if (error instanceof ApiModeError) return error.message;
  if (error instanceof Error) return error.message;
  return 'TeslaMate API request failed';
}

function getAuthHeaders(): Record<string, string> {
  const { apiToken } = useStore.getState();
  return apiToken ? { Authorization: `Bearer ${apiToken}`, 'Content-Type': 'application/json' } : { 'Content-Type': 'application/json' };
}

function getBaseUrl(): string | null {
  const { serverUrl, mockMode } = useStore.getState();
  if (mockMode || !serverUrl) return null;
  return serverUrl.replace(/\/+$/, '');
}

async function fetchJson<T>(path: string, fallback: T, pick?: (body: any) => T): Promise<T> {
  const base = getBaseUrl();
  const { mockMode, serverUrl } = useStore.getState();
  if (mockMode) return delay(fallback, 150);
  if (!base || !serverUrl) {
    throw new ApiModeError('TeslaMate server is not configured. Open Settings or Onboarding to connect.');
  }
  const res = await fetch(`${base}${path}`, { headers: getAuthHeaders() });
  if (!res.ok) {
    const hint = res.status === 401 || res.status === 403
      ? ' Check the API token.'
      : res.status === 404
        ? ' Check that the server URL is the TeslaMate root address, without /api/v1.'
        : '';
    throw new ApiModeError(`TeslaMate API returned HTTP ${res.status}.${hint}`, res.status);
  }
  const body = await res.json();
  return pick ? pick(body) : body;
}

export async function testTeslaMateConnection(serverUrl: string, token: string): Promise<{ carCount: number; firstCarName?: string; warning?: string }> {
  const base = serverUrl.trim().replace(/\/+$/, '').replace(/\/api\/v1$/, '').replace(/\/api$/, '');
  if (!base) throw new ApiModeError('Please enter a TeslaMate root URL.');
  if (!base.startsWith('http://') && !base.startsWith('https://')) {
    throw new ApiModeError('URL must start with http:// or https://.');
  }

  const headers: Record<string, string> = token
    ? { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
    : { 'Content-Type': 'application/json' };

  const ping = await fetch(`${base}/api/ping`, { headers });
  if (!ping.ok) throw new ApiModeError(`Ping failed with HTTP ${ping.status}.`, ping.status);

  let warning: string | undefined;
  try {
    const ready = await fetch(`${base}/api/readyz`, { headers });
    if (!ready.ok) {
      warning = ready.status === 404
        ? 'Readiness endpoint is unavailable; continuing with vehicle check.'
        : `Readiness check returned HTTP ${ready.status}; continuing with vehicle check.`;
    }
  } catch {
    warning = 'Readiness check failed; continuing with vehicle check.';
  }

  const carsResponse = await fetch(`${base}/api/v1/cars`, { headers });
  if (!carsResponse.ok) {
    const hint = carsResponse.status === 401 || carsResponse.status === 403
      ? ' Check the API token.'
      : carsResponse.status === 404
        ? ' Enter the TeslaMate root URL, not /api/v1.'
        : '';
    throw new ApiModeError(`Vehicle check failed with HTTP ${carsResponse.status}.${hint}`, carsResponse.status);
  }

  const body = await carsResponse.json();
  const carsList = body?.data?.cars ?? body?.cars ?? [];
  if (!Array.isArray(carsList) || carsList.length === 0) {
    throw new ApiModeError('TeslaMate returned no cars. Check API permissions and data availability.');
  }

  return {
    carCount: carsList.length,
    firstCarName: carsList[0]?.name ?? carsList[0]?.display_name,
    warning,
  };
}

export const api = {
  getCars: async (): Promise<Car[]> =>
    fetchJson('/api/v1/cars', mockData.cars as Car[], body => body?.data?.cars ?? body?.cars ?? []),

  getCarStatus: async (carId: number): Promise<CarStatus> => {
    const mockStatus = mockData.statuses[String(carId) as keyof typeof mockData.statuses];
    const mock: CarStatus = {
      ...mockStatus,
      battery_level: Math.min(100, Math.max(10, mockStatus.battery_level + ((Math.random() - 0.5) * 2 | 0))),
    } as CarStatus;
    return fetchJson(`/api/v1/cars/${carId}/status`, mock, body => body?.data?.status ?? body);
  },

  getDrives: async (carId: number): Promise<Drive[]> =>
    fetchJson(`/api/v1/cars/${carId}/drives`, mockData.drives as Drive[], body => body?.data?.drives ?? body?.drives ?? []),

  getCharges: async (carId: number): Promise<Charge[]> =>
    fetchJson(`/api/v1/cars/${carId}/charges`, mockData.charges as Charge[], body => body?.data?.charges ?? body?.charges ?? []),

  getBatteryHealth: async (carId: number): Promise<BatteryHealth[]> =>
    fetchJson(`/api/v1/cars/${carId}/battery-health`, mockData.battery_health.filter(h => h.car_id === carId) as BatteryHealth[], body => body?.data?.battery_health ?? body?.data?.batteryHealth ?? []),

  getUpdates: async (carId: number): Promise<SoftwareUpdate[]> =>
    fetchJson(`/api/v1/cars/${carId}/updates`, mockData.software_updates as SoftwareUpdate[], body => body?.data?.updates ?? body?.updates ?? []),

  getSentryEvents: async (carId: number) =>
    fetchJson(`/api/v1/cars/${carId}/sentry-events`, mockData.sentry_events, body => body?.data?.sentry_events ?? body?.sentry_events ?? []),

  getVisitedRegions: async (carId: number) =>
    fetchJson(`/api/v1/cars/${carId}/visited-regions`, mockData.visited_regions, body => body?.data?.visited_regions ?? body?.visited_regions ?? []),
};
