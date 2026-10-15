import { expect, it } from 'vitest'
import { estimateTariffCost, parseTariffPeriods, validTariffPeriods, readTariff, tariffKey } from './tariff'
import { resolveChargeEnergy } from './drive_energy'
import type { Charge, EnergyContract } from '../services/types'
const start = '2026-10-16T09:30:00+08:00', end = '2026-10-16T10:30:00+08:00'
const proof: EnergyContract = { version: 1, charge_mode: 'ac', charge_mode_evidence: 'observed_boundary_modes_no_conflict', ac_input: {
  value_kwh: 10, unit: 'kWh', method: 'session_counter_delta', measurement_point: 'ac_charger_input', source: 'telemetry_mqtt', quality: 'reported',
  start_date: start, end_date: end, coverage_kind: 'endpoints', coverage_ratio: 1,
} }
const input = { source: 'telemetry_mqtt', start_date: start, end_date: end, energy_contract: proof }
const charge = { startDate: start, endDate: end, source: input.source, energy: resolveChargeEnergy(input) } as Charge
it('splits exact peak boundary using qualified AC input and labels no actual bill', () => {
  expect(estimateTariffCost(charge, { currency: 'CNY', peak: 1, flat: .7, valley: .3, timeZone: 'Asia/Shanghai' })).toBeCloseTo(8.5, 10)
  expect(estimateTariffCost({ ...charge, energy: undefined }, { currency: 'CNY', peak: 1, flat: .7, valley: .3 })).toBeNull()
  expect(estimateTariffCost(charge, { currency: '', peak: 1, flat: .7, valley: .3 })).toBeNull()
})
it('isolates local tariff ownership and preserves real free rate', () => {
  const values = new Map([[tariffKey('a.b', 'https://api.example.test'), { currency: 'USD', peak: 0, flat: 0, valley: 0 }]])
  const storage = { getStorageSync: (key: string) => values.get(key) }
  expect(readTariff(storage, 'a.b', 'https://api.example.test')?.peak).toBe(0)
  expect(readTariff(storage, 'a', 'https://api.example.test')).toBeNull()
  expect(readTariff(storage, 'a.b', 'https://other.example.test')).toBeNull()
})
it('uses edited left-closed hourly ranges, rejects overlaps and preserves disabled flat price zero', () => {
  const profile = { currency: 'CNY', peak: 1, flat: .7, valley: .3, timeZone: 'Asia/Shanghai', peakHours: [[9, 11]], flatHours: [[0, 9], [11, 24]], valleyHours: [] }
  expect(estimateTariffCost(charge, profile)).toBeCloseTo(10, 10)
  expect(estimateTariffCost(charge, { ...profile, enabled: false, defaultPrice: 0 })).toBe(0)
  expect(estimateTariffCost(charge, { ...profile, valleyHours: [[10, 11]] })).toBeNull()
  expect(parseTariffPeriods('23-7')).toBeNull()
  expect(validTariffPeriods([[0, 12]], [[12, 23]], [[23, 24]])).toBe(true)
})
