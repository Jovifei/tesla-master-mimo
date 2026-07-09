import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { Car, FlatCar } from './api/types';
import { flattenCar } from './api/types';
import mockData from './mock_data.json';

const cars = (mockData.cars as Car[]).map(flattenCar);

interface Store {
  currentCarId: number;
  cars: FlatCar[];
  theme: 'light' | 'dark' | 'system';
  mockMode: boolean;
  serverUrl: string;
  apiToken: string;
  onboardingDone: boolean;
  setCarId: (id: number) => void;
  setCars: (items: FlatCar[]) => void;
  setTheme: (t: 'light' | 'dark' | 'system') => void;
  setMockMode: (m: boolean) => void;
  setOnboardingDone: (d: boolean) => void;
  setServer: (url: string, token: string) => void;
}

function normalizeServerUrl(url: string): string {
  return url.trim().replace(/\/+$/, '').replace(/\/api\/v1$/, '').replace(/\/api$/, '');
}

export const useStore = create<Store>()(
  persist(
    (set) => ({
      currentCarId: 1,
      cars,
      theme: 'system',
      mockMode: false,
      serverUrl: '',
      apiToken: '',
      onboardingDone: false,
      setCarId: (id) => set({ currentCarId: id }),
      setCars: (items) => set((state) => ({
        cars: items.length > 0 ? items : state.cars,
        currentCarId: items.length > 0 && !items.some(car => car.id === state.currentCarId)
          ? items[0].id
          : state.currentCarId,
      })),
      setTheme: (t) => {
        set({ theme: t });
        document.documentElement.classList.toggle('dark', t === 'dark' || (t === 'system' && window.matchMedia('(prefers-color-scheme:dark)').matches));
      },
      setMockMode: (m) => set((state) => ({
        mockMode: m,
        cars: m ? cars : state.cars,
        onboardingDone: m ? true : Boolean(state.serverUrl),
      })),
      setOnboardingDone: (d) => set({ onboardingDone: d }),
      setServer: (url, token) => set({
        serverUrl: normalizeServerUrl(url),
        apiToken: token,
        mockMode: false,
        onboardingDone: true,
      }),
    }),
    {
      name: 'matelink-web-settings',
      partialize: (state) => ({
        currentCarId: state.currentCarId,
        theme: state.theme,
        mockMode: state.mockMode,
        serverUrl: state.serverUrl,
        apiToken: state.apiToken,
        onboardingDone: state.onboardingDone || state.mockMode || Boolean(state.serverUrl),
      }),
    }
  )
);
