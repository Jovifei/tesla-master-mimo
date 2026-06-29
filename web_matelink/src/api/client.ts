import mockData from '../mock_data.json';
import type { Car, CarStatus, Drive, Charge, BatteryHealth, SoftwareUpdate } from './types';

function delay<T>(v: T, ms = 300): Promise<T> {
  return new Promise(r => setTimeout(() => r(v), ms));
}

export const api = {
  getCars: async (): Promise<Car[]> => delay(mockData.cars as Car[]),
  getCarStatus: async (carId: number): Promise<CarStatus> => {
    const status = mockData.statuses[String(carId) as keyof typeof mockData.statuses];
    // Simulate small battery fluctuation
    return delay({
      ...status,
      battery_level: Math.min(100, Math.max(10, status.battery_level + (Math.random() - 0.5) * 2 | 0)),
    } as CarStatus, 200);
  },
  getDrives: async (carId: number): Promise<Drive[]> => delay(mockData.drives as Drive[]),
  getCharges: async (carId: number): Promise<Charge[]> => delay(mockData.charges as Charge[]),
  getBatteryHealth: async (carId: number): Promise<BatteryHealth[]> =>
    delay(mockData.battery_health.filter(h => h.car_id === carId) as BatteryHealth[]),
  getUpdates: async (carId: number): Promise<SoftwareUpdate[]> => delay(mockData.software_updates as SoftwareUpdate[]),
  getSentryEvents: async (carId: number) => delay(mockData.sentry_events),
  getVisitedRegions: async (carId: number) => delay(mockData.visited_regions),
};
