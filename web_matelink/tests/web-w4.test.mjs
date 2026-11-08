import test from 'node:test'
import assert from 'node:assert/strict'
import { parseTpmsReport } from '../src/api/matelink.ts'
import { annualSummary, availableYears, historyCsv, historyJson, monthlyRows } from '../src/api/analysis.ts'
import { tpmsTrend } from '../src/api/tpms-store.ts'

const drive = (overrides = {}) => ({
  id: '1', startDate: '2026-03-05T08:00:00Z', endDate: '2026-03-05T08:30:00Z', durationMinutes: 30,
  distanceKm: 20, startBatteryLevel: 80, endBatteryLevel: 74, speedMaxKmh: 90, speedAvgKmh: 40,
  outsideTempAvgC: 12, energyConsumedKwh: 4, consumptionWhPerKm: 200, qualityState: null, ...overrides,
})
const charge = (overrides = {}) => ({
  id: '1', startDate: '2026-03-06T22:00:00Z', endDate: '2026-03-07T01:00:00Z', durationMinutes: 180,
  address: '家', startBatteryLevel: 30, endBatteryLevel: 90, energyAddedKwh: 40, maxPowerKw: 7,
  cost: 20, currency: 'CNY', chargeMode: 'ac', qualityState: null, ...overrides,
})

test('annualSummary excludes quarantined rows and reports coverage', () => {
  const summary = annualSummary(
    [drive(), drive({ id: '2', distanceKm: null, energyConsumedKwh: null }), drive({ id: '3', qualityState: 'quarantined', distanceKm: 999 })],
    [charge(), charge({ id: '2', cost: 5, currency: 'USD', energyAddedKwh: null })],
    2026
  )
  assert.equal(summary.tripCount, 2)
  assert.equal(summary.distance.value, 20)
  assert.equal(summary.distance.known, 1)
  assert.equal(summary.distance.total, 2)
  assert.equal(summary.efficiencyKwhPer100km, 20) // 4 kWh over 20 km paired
  assert.equal(summary.chargedEnergy.value, 40)
  assert.deepEqual(summary.costs.map(c => c.currency).sort(), ['CNY', 'USD'])
  assert.equal(annualSummary([drive()], [], 2025).tripCount, 0)
})

test('monthlyRows groups by month and keeps single-currency cost only', () => {
  const rows = monthlyRows([drive(), drive({ id: '2', startDate: '2026-02-01T08:00:00Z' })], [charge(), charge({ id: '2', currency: 'USD', startDate: '2026-03-09T22:00:00Z' })])
  assert.equal(rows[0].month, '2026-03')
  assert.equal(rows[0].cost, null) // mixed currencies -> no single total
  assert.equal(rows[0].sessions, 2)
  assert.equal(rows[1].month, '2026-02')
  assert.deepEqual(availableYears([drive()], [charge({ startDate: '2024-06-01T00:00:00Z' })]), [2026, 2024])
})

test('history exports exclude locations and quarantined rows', () => {
  const csv = historyCsv([drive(), drive({ id: '9', qualityState: 'quarantined' })], [charge()])
  assert.ok(csv.startsWith('\uFEFF'))
  assert.equal(csv.trim().split('\r\n').length, 3) // header + 1 drive + 1 charge
  const json = JSON.parse(historyJson([drive()], [charge()], true))
  assert.equal(json.coverage.includesPrivateLocations, false)
  assert.equal(json.drives.length, 1)
  assert.equal(json.charges[0].battery_added_kwh, 40)
})

test('parseTpmsReport requires an explicit bar unit', () => {
  const details = { tpms_pressure_fl: 2.9, tpms_pressure_fr: 2.8, tpms_pressure_rl: 2.9, tpms_pressure_rr: 3.0, pressure_unit: 'bar', tpms_pressure_fl_observed_at: '2026-11-08T02:00:00Z' }
  const report = parseTpmsReport(details, null)
  assert.equal(report.fl, 2.9)
  assert.equal(report.observedAt.fl, '2026-11-08T02:00:00Z')
  assert.equal(parseTpmsReport({ ...details, pressure_unit: 'psi' }, null).fl, null)
  assert.equal(parseTpmsReport(details, { unit_of_pressure: 'bar' }).fr, 2.8) // unit falls back to payload units when details omit it? details carry bar here
  assert.equal(parseTpmsReport(null, null), null)
  assert.equal(parseTpmsReport({ tpms_pressure_fl: 99, pressure_unit: 'bar' }, null).fl, null)
})

test('tpmsTrend stays honest with insufficient evidence and flags real drops', () => {
  const one = tpmsTrend([{ at: 0, fl: 2.9, fr: 2.9, rl: 2.9, rr: 2.9 }])
  assert.ok(one.wheels[0].verdict.includes('样本不足'))
  const short = tpmsTrend([
    { at: 0, fl: 2.9, fr: 2.9, rl: 2.9, rr: 2.9 },
    { at: 3_600_000, fl: 2.6, fr: 2.9, rl: 2.9, rr: 2.9 },
  ])
  assert.ok(short.wheels[0].verdict.includes('不足 24 小时'))
  const week = tpmsTrend([
    { at: 0, fl: 2.9, fr: 2.9, rl: 2.9, rr: 2.9 },
    { at: 7 * 24 * 3_600_000, fl: 2.65, fr: 2.88, rl: 2.9, rr: 3.15 },
  ])
  assert.ok(week.wheels[0].verdict.includes('缓慢漏气'))
  assert.ok(week.wheels[1].verdict.includes('基本稳定'))
  assert.ok(week.wheels[3].verdict.includes('上升'))
})
