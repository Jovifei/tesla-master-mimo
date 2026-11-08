import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeCharge, parseChargeDetail, parseDriveDetail, parseParked } from '../src/api/matelink.ts'
import { elevationStats } from '../src/api/drive-elevation.ts'
import { EMPTY_CHARGE_FILTER, chargeAddresses, filterCharges } from '../src/api/charge-filter.ts'

test('normalizeCharge resolves mode with the miniprogram conflict rules', () => {
  assert.equal(normalizeCharge({ charge_id: 1, charge_type: 'dc' }).chargeMode, 'dc')
  assert.equal(normalizeCharge({ charge_id: 2, charge_type: 'ac' }).chargeMode, 'ac')
  // Reported AC with a fast charger present is a conflict -> null.
  assert.equal(normalizeCharge({ charge_id: 3, charge_type: 'ac', fast_charger_present: true }).chargeMode, null)
  assert.equal(normalizeCharge({ charge_id: 4, fast_charger_present: true }).chargeMode, 'dc')
  assert.equal(normalizeCharge({ charge_id: 5 }).chargeMode, null)
  const full = normalizeCharge({
    charge_id: 6, start_date: '2026-11-01T10:00:00Z', address: '家', cost: 12.5, currency_code: 'CNY',
    charge_energy_added: 30, battery_details: { start_battery_level: 20, end_battery_level: 80 },
  })
  assert.equal(full.energyAddedKwh, 30)
  assert.equal(full.currency, 'CNY')
  assert.equal(full.startBatteryLevel, 20)
})

test('parseChargeDetail keeps only in-window samples and reads charger details', () => {
  const detail = parseChargeDetail({
    data: {
      charge: {
        charge_id: 9, start_date: '2026-11-01T10:00:00Z', end_date: '2026-11-01T11:00:00Z',
        charger_power: 7.2,
        charge_details: [
          { date: '2026-11-01T09:59:00Z', battery_level: 19, charger_details: { charger_power: 0 } },
          { date: '2026-11-01T10:30:00Z', battery_level: 50, charger_details: { charger_power: 7.2, charger_voltage: 220, charger_actual_current: 32 } },
          { date: '2026-11-01T11:01:00Z', battery_level: 81, charger_details: { charger_power: 0 } },
        ],
      },
      units: { unit_of_temperature: 'C' },
    },
  })
  assert.equal(detail.points.length, 1)
  assert.equal(detail.points[0].powerKw, 7.2)
  assert.equal(detail.points[0].voltageV, 220)
  assert.equal(detail.summary.chargerPowerKw, 7.2)
  assert.equal(detail.reason, 'response_samples_only')
})

test('parseChargeDetail reports quarantined and missing detail honestly', () => {
  const quarantined = parseChargeDetail({ data: { charge: { charge_id: 1, quality_state: 'quarantined' } } })
  assert.equal(quarantined.reason, 'quarantined_history')
  const missing = parseChargeDetail({ data: { charge: null } })
  assert.equal(missing.reason, 'detail_unavailable')
  assert.deepEqual(missing.points, [])
})

test('parseDriveDetail extracts samples with elevation and speed conversion', () => {
  const detail = parseDriveDetail({
    data: {
      drive: {
        drive_id: 5, start_date: '2026-11-01T08:00:00Z', end_date: '2026-11-01T08:30:00Z', speed_unit: 'mph',
        drive_details: [
          { date: '2026-11-01T08:10:00Z', speed: 60, power: 20, battery_level: 70, elevation: 100 },
          { date: '2026-11-01T08:20:00Z', speed: 30, power: -5, battery_level: 68, elevation: 130 },
        ],
      },
      units: { unit_of_length: 'km', unit_of_temperature: 'C' },
    },
  })
  assert.equal(detail.points.length, 2)
  assert.ok(Math.abs(detail.points[0].speedKmh - 96.56064) < 1e-6)
  const stats = elevationStats(detail.points)
  assert.equal(stats.maxM, 130)
  assert.equal(stats.minM, 100)
  assert.equal(stats.gainM, 30)
  assert.equal(stats.lossM, 0)
})

test('elevationStats skips pairs with missing endpoints', () => {
  const stats = elevationStats([
    { date: 'a', speedKmh: null, powerKw: null, batteryLevel: null, elevationM: 100 },
    { date: 'b', speedKmh: null, powerKw: null, batteryLevel: null, elevationM: null },
    { date: 'c', speedKmh: null, powerKw: null, batteryLevel: null, elevationM: 150 },
  ])
  assert.equal(stats.gainM, null) // no valid consecutive pair
  assert.equal(stats.maxM, 150)
  assert.equal(elevationStats([{ date: 'a', speedKmh: null, powerKw: null, batteryLevel: null, elevationM: null }]), null)
})

test('parseParked only trusts energy when the full proof qualifies', () => {
  const base = {
    data: {
      older_drive_id: 1, newer_drive_id: 2,
      start_date: '2026-11-01T09:00:00Z', end_date: '2026-11-01T11:00:00Z',
      observed_start_at: '2026-11-01T09:00:00Z', observed_end_at: '2026-11-01T11:00:00Z',
      address: '公司', start_battery_level: 70, end_battery_level: 69, battery_delta: -1,
      source: 'telemetry_mqtt', linked_charge: null,
      energy_contract: {
        version: 1,
        stored_change: {
          value_kwh: 0.6, coverage_ratio: 1, coverage_kind: 'endpoints', source: 'telemetry_mqtt',
          source_field: 'EnergyRemaining', method: 'energy_remaining_delta',
          measurement_point: 'nominal_battery_remaining', quality: 'estimated', unit: 'kWh',
          activity_evidence: 'no_driving_or_charging_during_window',
          start_date: '2026-11-01T09:00:00Z', end_date: '2026-11-01T11:00:00Z',
        },
      },
    },
  }
  const parked = parseParked(base)
  assert.equal(parked.energyKwh, 0.6)
  assert.ok(Math.abs(parked.averagePowerW - 300) < 1e-9) // 0.6 kWh over 2 h
  const broken = structuredClone(base)
  broken.data.energy_contract.stored_change.coverage_ratio = 0.5
  assert.equal(parseParked(broken).energyKwh, null)
  assert.equal(parseParked({ data: {} }), null)
})

test('filterCharges applies mode, address and cost filters', () => {
  const charges = [
    normalizeCharge({ charge_id: 1, charge_type: 'ac', address: '家', cost: 10 }),
    normalizeCharge({ charge_id: 2, charge_type: 'dc', address: '公司', cost: 0 }),
    normalizeCharge({ charge_id: 3, charge_type: 'dc', address: '家', cost: null }),
  ]
  assert.equal(filterCharges(charges, EMPTY_CHARGE_FILTER).length, 3)
  assert.deepEqual(filterCharges(charges, { ...EMPTY_CHARGE_FILTER, mode: 'dc' }).map(c => c.id), ['2', '3'])
  assert.deepEqual(filterCharges(charges, { ...EMPTY_CHARGE_FILTER, address: '家' }).map(c => c.id), ['1', '3'])
  assert.deepEqual(filterCharges(charges, { ...EMPTY_CHARGE_FILTER, withCostOnly: true }).map(c => c.id), ['1'])
  assert.deepEqual(chargeAddresses(charges), ['公司', '家'])
})
