import { describe, expect, it } from 'vitest'
import { MOCK_CAR_ID, mockFixtureStats, mockIso, mockPlanned, mockRequest } from './mock_fixture'

it('routes nothing while the build switch is off', () => {
  expect(mockRequest('/api/v1/cars')).toBeNull()
  expect(mockRequest('/api/v1/cars', false)).toBeNull()
})

describe('fixture routing (enabled)', () => {
  it('plans a 76-day timeline with hundreds of drives and dozens of charges', () => {
    expect(mockFixtureStats.days).toBe(76)
    expect(mockFixtureStats.drives).toBeGreaterThan(150)
    expect(mockFixtureStats.drives).toBeLessThan(400)
    expect(mockFixtureStats.charges).toBeGreaterThan(20)
    expect(mockFixtureStats.charges).toBeLessThan(90)
  })

  it('keeps the drive timeline strictly ordered and chained', () => {
    const rows = mockPlanned.drives
    for (let index = 0; index + 1 < rows.length; index++) {
      expect(Date.parse(mockIso(rows[index].start))).toBeGreaterThan(Date.parse(mockIso(rows[index + 1].end)))
      expect(rows[index].km).toBeGreaterThan(0)
    }
  })

  it('covers free DC, TeslaMate zero-bill and paid sessions for the cost chain', () => {
    expect(mockPlanned.charges.some(row => row.free && row.dc)).toBe(true)
    expect(mockPlanned.charges.some(row => row.teslamateZero)).toBe(true)
    expect(mockPlanned.charges.some(row => !row.free && !row.teslamateZero)).toBe(true) // billed rows: cost computed in rawCharge
  })

  it('serves wire-shaped pages and details through the router', () => {
      const cars = mockRequest('/api/v1/cars', true) as { data: { cars: unknown[] } }
      expect(cars.data.cars).toHaveLength(1)
    const firstPage = mockRequest(`/api/v1/cars/${MOCK_CAR_ID}/drives?page=1&show=20`, true) as { data: { drives: { drive_id: number; source: string }[]; meta: { total: number } } }
    expect(firstPage.data.drives).toHaveLength(20)
    expect(firstPage.data.drives.every(row => row.source === 'mock_fixture')).toBe(true)
    expect(firstPage.data.meta.total).toBe(mockFixtureStats.drives)
    const driveId = firstPage.data.drives[0].drive_id
    const detail = mockRequest(`/api/v1/cars/${MOCK_CAR_ID}/drives/${driveId}`, true) as { data: { drive: { drive_details: { speed: number }[] } } }
    expect(detail.data.drive.drive_details.length).toBeGreaterThan(8)
    expect(detail.data.drive.drive_details.some(point => point.speed > 0)).toBe(true)
    const unknown = mockRequest(`/api/v1/cars/${MOCK_CAR_ID}/drives/99999999`, true)
    expect(unknown).toMatchObject({ error: 'history_unavailable' })
  })

  it('serves battery health with the degradation series and vehicle keys', () => {
    const health = mockRequest(`/api/v1/cars/${MOCK_CAR_ID}/battery-health`, true) as { data: { car_id: number; battery_health: { max_capacity: number; current_capacity: number; rated_efficiency: number; max_range: number } } }
    expect(health.data.car_id).toBe(MOCK_CAR_ID)
    expect(health.data.battery_health.max_capacity).toBe(60)
    expect(health.data.battery_health.current_capacity).toBe(59.2)
    expect(health.data.battery_health.rated_efficiency).toBe(142)
    expect(health.data.battery_health.max_range).toBe(298)
  })

  it('serves software updates with an empty second page', () => {
    const first = mockRequest(`/api/v1/cars/${MOCK_CAR_ID}/updates?page=1&show=20`, true) as { data: { updates: { version: string }[] } }
    expect(first.data.updates.map(row => row.version)).toEqual(['2026.38.2', '2026.32.1', '2026.26.3'])
    const second = mockRequest(`/api/v1/cars/${MOCK_CAR_ID}/updates?page=2&show=20`, true) as { data: { updates: unknown[] } }
    expect(second.data.updates).toEqual([])
  })

  it('serves history context pinned to the mock vehicle', () => {
    const context = mockRequest(`/api/v1/cars/${MOCK_CAR_ID}/history-context`.replace('/api/v1', '/api/matelink/v1'), true) as { data: { car_id: number; vehicle_uid: string; capability_version: number } }
    expect(context.data).toMatchObject({ car_id: MOCK_CAR_ID, vehicle_uid: 'mock-vehicle-uid-7', capability_version: 1 })
  })

  it('serves standby windows that honestly stay unqualified for energy', () => {
    const standby = mockRequest(`/api/matelink/v1/cars/${MOCK_CAR_ID}/standby`, true) as { data: { windows: { start_date: string; end_date: string; source: string; energy_contract: { stored_change: { reason: string } } }[] } }
    expect(standby.data.windows.length).toBeGreaterThan(3)
    for (const win of standby.data.windows) {
      expect(Date.parse(win.end_date) - Date.parse(win.start_date)).toBeGreaterThanOrEqual(60 * 60_000)
      expect(win.source).toBe('mock_fixture')
      expect(win.energy_contract.stored_change.reason).toBe('no_qualified_parked_energy')
    }
  })

  it('covers winter sub-zero temperatures without clamping', () => {
    const cold = mockPlanned.drives.filter(row => row.outsideTempAvg != null)
    expect(cold.length).toBeGreaterThanOrEqual(12)
    expect(Math.min(...cold.map(row => row.outsideTempAvg!))).toBeGreaterThanOrEqual(-8.2)
    expect(Math.max(...cold.map(row => row.outsideTempAvg!))).toBeLessThanOrEqual(-0.4)
  })

  it('includes genuine net-regeneration drives with negative drain', () => {
    const regen = mockPlanned.drives.filter(row => (row.netKwh ?? 0) < 0)
    expect(regen.length).toBe(2)
    expect(regen.every(row => row.netKwh! >= -0.9 && row.netKwh! <= -0.2)).toBe(true)
  })

  it('keeps every month boundary day populated for chart grouping', () => {
    for (const day of [21, 22, 51, 52]) {
      const dayStart = Date.UTC(2026, 9, 25, 14, 0, 0) - (76 - day) * 86_400_000
      const dayEnd = dayStart + 86_400_000
      expect(mockPlanned.drives.some(row => Date.parse(mockIso(row.start)) >= dayStart && Date.parse(mockIso(row.start)) < dayEnd)).toBe(true)
    }
  })

  it('carries exactly one quarantined row for skip-behaviour regression', () => {
    expect(mockPlanned.drives.filter(row => row.quarantined)).toHaveLength(1)
  })
})
