import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeCharge, normalizeDrive, parseChargeDetail, parseDriveDetail } from '../src/api/matelink.ts'
import {
  socSegments, mileageSeries, parkedGaps, periodStats, chargeHeatmap, temperatureBuckets,
} from '../src/api/tm-data.ts'

test('normalizeCharge reads coordinates and the energy contract', () => {
  const charge = normalizeCharge({
    charge_id: 7,
    start_date: '2026-03-01T22:00:00Z',
    end_date: '2026-03-02T01:00:00Z',
    address: '家',
    duration_min: 180,
    charge_energy_added: 40,
    charge_energy_used: 44.5,
    cost: 22,
    currency: 'CNY',
    charge_type: 'ac',
    charger_power: 7,
    latitude: 30.1,
    longitude: 120.2,
    battery_details: { start_battery_level: 30, end_battery_level: 90 },
    energy_contract: {
      version: 1,
      ac_efficiency: 89.9,
      ac_loss: { value_kwh: 4.5 },
      ac_input: { quality: 'reported' },
    },
  })
  assert.equal(charge.latitude, 30.1)
  assert.equal(charge.longitude, 120.2)
  assert.equal(charge.energyUsedKwh, 44.5)
  assert.equal(charge.acEfficiencyPct, 89.9)
  assert.equal(charge.acLossKwh, 4.5)
  assert.equal(charge.energyUsedNote, null)
})

test('normalizeCharge explains missing pile-side energy in Chinese', () => {
  const dc = normalizeCharge({ charge_id: 8, charge_type: 'dc', fast_charger_present: true })
  assert.equal(dc.energyUsedKwh, null)
  assert.match(dc.energyUsedNote, /直流/)
  const unqualified = normalizeCharge({
    charge_id: 9,
    charge_type: 'ac',
    energy_contract: { ac_input: { reason: 'unverified_full_ac_session' } },
  })
  assert.match(unqualified.energyUsedNote, /未通过/)
  const zero = normalizeCharge({ charge_id: 10, latitude: 0, longitude: 0 })
  assert.equal(zero.latitude, null)
})

test('normalizeDrive reads route endpoints and odometer range', () => {
  const drive = normalizeDrive(
    {
      drive_id: 3,
      start_date: '2026-03-01T08:00:00Z',
      end_date: '2026-03-01T08:30:00Z',
      start_latitude: 30.0,
      start_longitude: 120.0,
      end_latitude: 30.2,
      end_longitude: 120.3,
      odometer_details: { odometer_start: 1000, odometer_end: 1020, odometer_distance: 20 },
      battery_details: { start_battery_level: 80, end_battery_level: 74 },
    },
    'km'
  )
  assert.equal(drive.startLatitude, 30.0)
  assert.equal(drive.endLongitude, 120.3)
  assert.equal(drive.odometerStartKm, 1000)
  assert.equal(drive.odometerEndKm, 1020)
})

test('parseDriveDetail keeps route coordinates and climate', () => {
  const detail = parseDriveDetail({
    data: {
      units: { unit_of_length: 'km', unit_of_temperature: 'C' },
      drive: {
        drive_id: 3,
        start_date: '2026-03-01T08:00:00Z',
        end_date: '2026-03-01T08:30:00Z',
        odometer_details: { odometer_distance: 20 },
        battery_details: {},
        drive_details: [
          {
            date: '2026-03-01T08:10:00Z',
            speed: 60,
            power: 20,
            battery_level: 78,
            latitude: 30.1,
            longitude: 120.1,
            heading: 90,
            climate_info: { inside_temp: 22, outside_temp: 10 },
          },
        ],
      },
    },
  })
  assert.equal(detail.points.length, 1)
  assert.equal(detail.points[0].latitude, 30.1)
  assert.equal(detail.points[0].heading, 90)
  assert.equal(detail.points[0].insideTempC, 22)
  assert.equal(detail.points[0].outsideTempC, 10)
})

test('parseChargeDetail keeps cumulative energy and point coordinates', () => {
  const detail = parseChargeDetail({
    data: {
      units: { unit_of_temperature: 'C' },
      charge: {
        charge_id: 5,
        start_date: '2026-03-01T22:00:00Z',
        end_date: '2026-03-01T23:00:00Z',
        battery_details: { start_battery_level: 30, end_battery_level: 60 },
        charge_details: [
          { date: '2026-03-01T22:30:00Z', battery_level: 45, charge_energy_added: 10.5, latitude: 30.1, longitude: 120.2, charger_details: { charger_power: 7 } },
        ],
      },
    },
  })
  assert.equal(detail.points.length, 1)
  assert.equal(detail.points[0].energyAddedKwh, 10.5)
  assert.equal(detail.points[0].latitude, 30.1)
})

const driveRec = (overrides = {}) => ({
  id: 'd1', startDate: '2026-03-01T08:00:00Z', endDate: '2026-03-01T08:30:00Z',
  startAddress: '家', endAddress: '公司', distanceKm: 20, durationMinutes: 30,
  startBatteryLevel: 80, endBatteryLevel: 74, speedMaxKmh: 90, speedAvgKmh: 40,
  outsideTempAvgC: 12, energyConsumedKwh: null, consumptionWhPerKm: null, qualityState: null,
  startLatitude: null, startLongitude: null, endLatitude: null, endLongitude: null,
  odometerStartKm: 1000, odometerEndKm: 1020, ...overrides,
})
const chargeRec = (overrides = {}) => ({
  id: 'c1', startDate: '2026-03-01T22:00:00Z', endDate: '2026-03-01T23:00:00Z', address: '家',
  durationMinutes: 60, energyAddedKwh: 20, cost: 10, currency: 'CNY', chargeMode: 'ac',
  maxPowerKw: 7, startBatteryLevel: 50, endBatteryLevel: 80, isCharging: false, qualityState: null,
  latitude: null, longitude: null, energyUsedKwh: null, acEfficiencyPct: null, acLossKwh: null,
  energyUsedNote: null, ...overrides,
})

test('socSegments uses real charge samples and drive boundaries only', () => {
  const charge = chargeRec()
  const points = new Map([
    ['c1', [
      { date: '2026-03-01T22:00:00Z', batteryLevel: 50 },
      { date: '2026-03-01T22:30:00Z', batteryLevel: 65 },
      { date: '2026-03-01T23:00:00Z', batteryLevel: 80 },
    ]],
  ])
  const w0 = Date.parse('2026-03-01T00:00:00Z')
  const w1 = Date.parse('2026-03-02T00:00:00Z')
  const segments = socSegments([charge], [driveRec()], points, w0, w1)
  const chargeSeg = segments.find(s => s.kind === 'charge')
  const driveSeg = segments.find(s => s.kind === 'drive')
  assert.equal(chargeSeg.approx, false)
  assert.equal(chargeSeg.points.length, 3)
  assert.equal(driveSeg.approx, true)
  assert.deepEqual(driveSeg.points.map(p => p.soc), [80, 74])
})

test('mileageSeries stitches odometer boundaries in time order', () => {
  const series = mileageSeries([
    driveRec(),
    driveRec({ id: 'd2', startDate: '2026-03-01T18:00:00Z', endDate: '2026-03-01T18:20:00Z', odometerStartKm: 1020, odometerEndKm: 1035 }),
  ])
  assert.deepEqual(series.map(p => p.value), [1000, 1020, 1035])
})

test('parkedGaps finds the gap between sessions with SOC delta', () => {
  const gaps = parkedGaps([driveRec()], [chargeRec()])
  assert.equal(gaps.length, 1)
  assert.equal(gaps[0].startSoc, 74)
  assert.equal(gaps[0].endSoc, 50)
  assert.equal(gaps[0].deltaSoc, -24)
  assert.ok(gaps[0].durationMinutes > 60)
})

test('periodStats aggregates by month and keeps unknowns null', () => {
  const rows = periodStats([driveRec()], [chargeRec()], 'month')
  assert.equal(rows.length, 1)
  assert.equal(rows[0].distanceKm, 20)
  assert.equal(rows[0].chargedKwh, 20)
  assert.equal(rows[0].cost, 10)
  const empty = periodStats([driveRec({ distanceKm: null })], [], 'month')
  assert.equal(empty[0].distanceKm, null)
})

test('chargeHeatmap buckets by local weekday and hour', () => {
  const grid = chargeHeatmap([chargeRec()])
  const total = grid.flat().reduce((sum, cell) => sum + cell.count, 0)
  assert.equal(total, 1)
  const at = new Date('2026-03-01T22:00:00Z')
  assert.equal(grid[(at.getDay() + 6) % 7][at.getHours()].kwh, 20)
})

test('temperatureBuckets averages consumption only from drives that carry it', () => {
  const buckets = temperatureBuckets([
    driveRec({ outsideTempAvgC: 15, consumptionWhPerKm: 200, distanceKm: 10 }),
    driveRec({ id: 'd2', outsideTempAvgC: 16, consumptionWhPerKm: null, distanceKm: 10 }),
    driveRec({ id: 'd3', outsideTempAvgC: 15, consumptionWhPerKm: null, distanceKm: null }),
  ])
  const bucket = buckets.find(b => b.label === '10 ~ 20°C')
  assert.equal(bucket.driveCount, 3)
  assert.equal(bucket.avgConsumptionWhPerKm, 200)
})
