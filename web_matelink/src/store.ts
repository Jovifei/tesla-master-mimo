import { create } from 'zustand';
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
  setTheme: (t: 'light' | 'dark' | 'system') => void;
  setMockMode: (m: boolean) => void;
  setOnboardingDone: (d: boolean) => void;
  setServer: (url: string, token: string) => void;
}

export const useStore = create<Store>((set) => ({
  currentCarId: 1,
  cars,
  theme: 'system',
  mockMode: true,
  serverUrl: '',
  apiToken: '',
  onboardingDone: true,
  setCarId: (id) => set({ currentCarId: id }),
  setTheme: (t) => {
    set({ theme: t });
    document.documentElement.classList.toggle('dark', t === 'dark' || (t === 'system' && window.matchMedia('(prefers-color-scheme:dark)').matches));
  },
  setMockMode: (m) => set({ mockMode: m }),
  setOnboardingDone: (d) => set({ onboardingDone: d }),
  setServer: (url, token) => set({ serverUrl: url, apiToken: token, onboardingDone: true }),
}));
