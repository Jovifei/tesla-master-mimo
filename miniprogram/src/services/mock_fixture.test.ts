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
})
