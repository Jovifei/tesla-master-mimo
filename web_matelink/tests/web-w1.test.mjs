import test from 'node:test'
import assert from 'node:assert/strict'
import {
  extractTicket,
  normalizeCar,
  normalizeDrive,
  normalizeStatus,
  sessionIsUsable,
} from '../src/api/matelink.ts'
import { recentDriveModel } from '../src/api/recent-drive.ts'

test('extractTicket accepts a bare ticket and a full callback URL', () => {
  assert.equal(extractTicket('abc123._-~XYZ'), 'abc123._-~XYZ')
  assert.equal(
    extractTicket('https://auth.teslalink.joviluma.com/oauth/callback?ticket=tok-123_~&foo=bar'),
    'tok-123_~'
  )
  assert.equal(extractTicket('  https://auth.teslalink.joviluma.com/oauth/callback?ticket=t%7E1 '), 't~1')
})

test('extractTicket rejects errors, empty and unrelated text', () => {
  assert.equal(extractTicket('https://auth.teslalink.joviluma.com/oauth/callback?error=access_denied&ticket=x'), null)
  assert.equal(extractTicket(''), null)
  assert.equal(extractTicket('hello world, no ticket here'), null)
  assert.equal(extractTicket('https://example.com/some/page'), null)
})

test('sessionIsUsable honors expiry and skew', () => {
  const session = { accessToken: 'a', refreshToken: 'r', expiresAt: 100_000, userId: 'u' }
  assert.equal(sessionIsUsable(session, 60_000), true)
  assert.equal(sessionIsUsable(session, 75_000), false) // inside the 30s skew
  assert.equal(sessionIsUsable(null, 0), false)
})

test('normalizeStatus converts imperial units and keeps provenance', () => {
  const status = normalizeStatus({
    data: {
      status: {
        state: 'asleep',
        odometer: 100,
        battery_details: { battery_level: 55, rated_battery_range: 200 },
        car_status: { locked: true, sentry_mode: false },
        climate_details: { inside_temp: 77, outside_temp: 50 },
        car_geodata: { latitude: 30.25, longitude: 120.16, geofence: '家' },
      },
      observed_at: '2026-11-08T01:00:00Z',
      source: 'telemetry_cache',
      units: { unit_of_length: 'mi', unit_of_temperature: 'F' },
    },
  })
  assert.equal(status.batteryLevel, 55)
  assert.ok(Math.abs(status.ratedRangeKm - 321.8688) < 1e-6)
  assert.ok(Math.abs(status.odometerKm - 160.9344) < 1e-6)
  assert.equal(status.insideTempC, 25)
  assert.equal(status.outsideTempC, 10)
  assert.equal(status.geofence, '家')
  assert.equal(status.source, 'telemetry_cache')
  assert.equal(status.locked, true)
})

test('normalizeStatus treats 0,0 coordinates as no location', () => {
  const status = normalizeStatus({ status: { car_geodata: { latitude: 0, longitude: 0 } } })
  assert.equal(status.latitude, null)
  assert.equal(status.longitude, null)
})

test('normalizeCar prefers display name and car_id', () => {
  assert.deepEqual(normalizeCar({ car_id: 7, vehicle_uid: 'uid7', display_name: '大鼠标', model: 'Model Y' }), {
    id: 7,
    stableId: 'uid7',
    name: '大鼠标',
    model: 'Model Y',
  })
  assert.equal(normalizeCar({ name: 'x' }), null)
})

test('normalizeDrive converts odometer distance by unit', () => {
  const drive = normalizeDrive(
    {
      drive_id: 3,
      start_date: '2026-11-07T10:00:00Z',
      end_date: '2026-11-07T10:30:00Z',
      start_address: 'A',
      end_address: 'B',
      duration_min: 30,
      odometer_details: { odometer_distance: 10 },
      battery_details: { start_battery_level: 80, end_battery_level: 72 },
    },
    'mi'
  )
  assert.ok(Math.abs(drive.distanceKm - 16.09344) < 1e-6)
  assert.equal(drive.durationMinutes, 30)
})

test('recentDriveModel collapses unknown ends and formats metrics', () => {
  const model = recentDriveModel({
    id: '1',
    startDate: '2026-11-07T10:00:00Z',
    endDate: '2026-11-07T10:30:00Z',
    startAddress: null,
    endAddress: null,
    distanceKm: 12.34,
    durationMinutes: 30,
    startBatteryLevel: 80,
    endBatteryLevel: 72,
    speedMaxKmh: 90,
  })
  assert.equal(model.route, '未知地点')
  assert.equal(model.distanceText, '12.3 km')
  assert.equal(model.socText, '80% → 72%')
  const empty = recentDriveModel({
    id: '2', startDate: null, endDate: null, startAddress: '家', endAddress: '公司',
    distanceKm: null, durationMinutes: null, startBatteryLevel: null, endBatteryLevel: null, speedMaxKmh: null,
  })
  assert.equal(empty.route, '家 → 公司')
  assert.equal(empty.distanceText, '--')
  assert.equal(empty.socText, '--')
})
