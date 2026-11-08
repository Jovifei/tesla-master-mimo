import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeCharge, normalizeDrive, normalizeStatus } from '../src/api/matelink.ts'
import {
  aggregateEfficiency,
  estimateCapacityKwh,
  periodEfficiency,
  powerIntegral,
  resolveDriveEnergy,
} from '../src/api/drive-energy.ts'

const START = '2026-03-01T08:00:00Z'
const END = '2026-03-01T09:00:00Z'

function drive(overrides = {}) {
  return normalizeDrive(
    {
      drive_id: 'd1',
      start_date: START,
      end_date: END,
      duration_min: 60,
      source: 'telemetry_mqtt',
      battery_details: { start_battery_level: 80, end_battery_level: 70 },
      odometer_details: { odometer_start: 1000, odometer_distance: 50, odometer_end: 1050 },
      energy_contract: {
        version: 1,
        net_energy: { value_kwh: null, unit: 'kWh', source: 'telemetry_mqtt', quality: 'unknown', reason: 'no_verified_driving_net_energy' },
      },
      ...overrides,
    },
    'km',
  )
}

test('normalizeDrive reads source and the energy contract; normalizeCharge reads outside_temp_avg', () => {
  const d = drive()
  assert.equal(d.source, 'telemetry_mqtt')
  assert.equal(d.contractNet.quality, 'unknown')
  assert.equal(d.contractNet.valueKwh, null)
  const charge = normalizeCharge({ charge_id: 3, outside_temp_avg: 12.5 })
  assert.equal(charge.outsideTempAvgC, 12.5)
})

test('normalizeStatus reads battery_heater_on from the charging snapshot', () => {
  const on = normalizeStatus({ status: { charging_details: { battery_heater_on: true } } })
  assert.equal(on.batteryHeaterOn, true)
  const absent = normalizeStatus({ status: { charging_details: {} } })
  assert.equal(absent.batteryHeaterOn, null)
})

test('native telemetry drive falls back to the SOC-difference estimate (backend net is nil)', () => {
  const result = resolveDriveEnergy(drive(), { model: 'Model Y' })
  // (80 - 70)% × 60 kWh (Model Y standard-range assumption) = 6 kWh over 50 km.
  assert.equal(result.method, 'soc_capacity_estimate')
  assert.equal(result.energyKwh, 6)
  assert.equal(result.efficiencyWhKm, 120)
})

test('local_import scalar is used but marked unverified', () => {
  const d = drive({
    source: 'local_import',
    energy_consumed_net: 8.25,
    energy_contract: {
      version: 1,
      net_energy: { value_kwh: null, unit: 'kWh', source: 'local_import', quality: 'unknown', reason: 'unverified_import_energy_purpose' },
    },
  })
  const result = resolveDriveEnergy(d, { model: 'Model Y' })
  assert.equal(result.method, 'api_reported_net')
  assert.equal(result.energyKwh, 8.25)
  assert.equal(result.unverified, true)
  assert.equal(result.quality, 'unknown')
})

test('qualified EnergyRemaining contract metric wins over the SOC estimate', () => {
  const d = drive({
    energy_contract: {
      version: 1,
      net_energy: {
        value_kwh: 5.5,
        unit: 'kWh',
        source: 'telemetry_mqtt',
        method: 'energy_remaining_delta',
        quality: 'estimated',
        measurement_point: 'nominal_battery_remaining',
        coverage_kind: 'endpoints',
        coverage_ratio: 1,
        source_field: 'EnergyRemaining',
        start_date: START,
        end_date: END,
        observed_start_at: START,
        observed_end_at: END,
      },
    },
  })
  const result = resolveDriveEnergy(d, { model: 'Model Y' })
  assert.equal(result.method, 'energy_remaining_delta')
  assert.equal(result.energyKwh, 5.5)
})

test('contract metric with a mismatched window is rejected', () => {
  const d = drive({
    energy_contract: {
      version: 1,
      net_energy: {
        value_kwh: 5.5,
        unit: 'kWh',
        source: 'telemetry_mqtt',
        method: 'energy_remaining_delta',
        quality: 'estimated',
        coverage_kind: 'endpoints',
        coverage_ratio: 1,
        source_field: 'EnergyRemaining',
        start_date: '2026-03-01T07:00:00Z',
        end_date: END,
        observed_start_at: START,
        observed_end_at: END,
      },
    },
  })
  const result = resolveDriveEnergy(d, { model: 'Model Y' })
  assert.equal(result.method, 'soc_capacity_estimate')
})

test('powerIntegral integrates a constant-power hour and refuses gapped samples', () => {
  const points = []
  for (let i = 0; i <= 360; i++) {
    points.push({ date: new Date(Date.parse(START) + i * 10_000).toISOString(), powerKw: 10 })
  }
  const complete = powerIntegral(points, START, END)
  assert.ok(Math.abs(complete.energyKwh - 10) < 1e-9)
  assert.equal(complete.reason, 'complete_power_window')

  const gapped = points.filter((_, i) => i < 100 || i > 200)
  const incomplete = powerIntegral(gapped, START, END)
  assert.equal(incomplete.energyKwh, null)
  assert.equal(incomplete.reason, 'incomplete_power_window')
})

test('complete power integral beats the SOC estimate in detail resolution', () => {
  const points = []
  for (let i = 0; i <= 360; i++) {
    points.push({ date: new Date(Date.parse(START) + i * 10_000).toISOString(), powerKw: 20 })
  }
  const result = resolveDriveEnergy(drive(), { points, model: 'Model Y' })
  assert.equal(result.method, 'drive_power_integral')
  assert.ok(Math.abs(result.energyKwh - 20) < 1e-9)
  assert.ok(Math.abs(result.efficiencyWhKm - 400) < 1e-6)
})

test('no source at all leaves the energy empty instead of fabricating it', () => {
  const d = drive({ battery_details: { start_battery_level: 70, end_battery_level: 70 }, energy_contract: undefined })
  const result = resolveDriveEnergy(d, { model: 'Model Y' })
  assert.equal(result.energyKwh, null)
  assert.equal(result.method, null)
})

test('aggregateEfficiency resolves per drive and reports coverage', () => {
  const a = drive()
  const b = drive({ drive_id: 'd2', battery_details: { start_battery_level: 70, end_battery_level: 70 }, energy_contract: undefined })
  const aggregate = aggregateEfficiency([a, b], 'Model Y')
  assert.equal(aggregate.totalCount, 2)
  assert.equal(aggregate.resolvedCount, 1)
  assert.equal(aggregate.kwhPer100km, 12)
})

test('periodEfficiency only counts drives starting inside the window', () => {
  const inside = drive()
  const outside = drive({ drive_id: 'd3', start_date: '2026-02-01T08:00:00Z', end_date: '2026-02-01T09:00:00Z' })
  const period = periodEfficiency([inside, outside], 'Model Y', Date.parse('2026-03-01T00:00:00Z'), Date.parse('2026-03-08T00:00:00Z'))
  assert.equal(period.totalCount, 1)
  assert.equal(period.kwhPer100km, 12)
})

test('estimateCapacityKwh follows the App table', () => {
  assert.equal(estimateCapacityKwh('Model Y').kwh, 60)
  assert.equal(estimateCapacityKwh('Model 3', 'Performance').kwh, 78.4)
  assert.equal(estimateCapacityKwh('Model S').kwh, 100)
  assert.equal(estimateCapacityKwh(null).basis, 'default_assumption')
})
