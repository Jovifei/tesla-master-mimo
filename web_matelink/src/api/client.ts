import mockData from '../mock_data.json';
import { useStore } from '../store';
import type { Car, CarStatus, Drive, Charge, BatteryHealth, SoftwareUpdate } from './types';

function delay<T>(v: T, ms = 300): Promise<T> {
  return new Promise(r => setTimeout(() => r(v), ms));
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

async function fetchJson<T>(path: string, fallback: T): Promise<T> {
  const base = getBaseUrl();
  if (!base) return fallback;
  try {
    const res = await fetch(`${base}${path}`, { headers: getAuthHeaders() });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return await res.json();
  } catch (err) {
    console.warn(`[api] fetch ${path} failed, using mock:`, err);
    return fallback;
  }
}

export const api = {
  getCars: async (): Promise<Car[]> => fetchJson('/api/cars', mockData.cars as Car[]),

  getCarStatus: async (carId: number): Promise<CarStatus> => {
    const mockStatus = mockData.statuses[String(carId) as keyof typeof mockData.statuses];
    const mock: CarStatus = {
      ...mockStatus,
      battery_level: Math.min(100, Math.max(10, mockStatus.battery_level + ((Math.random() - 0.5) * 2 | 0))),
    } as CarStatus;
    return fetchJson(`/api/cars/${carId}/status`, mock);
  },

  getDrives: async (carId: number): Promise<Drive[]> =>
    fetchJson(`/api/cars/${carId}/drives`, mockData.drives as Drive[]),

  getCharges: async (carId: number): Promise<Charge[]> =>
    fetchJson(`/api/cars/${carId}/charges`, mockData.charges as Charge[]),

  getBatteryHealth: async (carId: number): Promise<BatteryHealth[]> =>
    fetchJson(`/api/cars/${carId}/battery_health`, mockData.battery_health.filter(h => h.car_id === carId) as BatteryHealth[]),

  getUpdates: async (carId: number): Promise<SoftwareUpdate[]> =>
    fetchJson(`/api/cars/${carId}/updates`, mockData.software_updates as SoftwareUpdate[]),

  getSentryEvents: async (carId: number) =>
    fetchJson(`/api/cars/${carId}/sentry_events`, mockData.sentry_events),

  getVisitedRegions: async (carId: number) =>
    fetchJson(`/api/cars/${carId}/visited_regions`, mockData.visited_regions),
};
