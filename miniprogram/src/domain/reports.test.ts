import { expect, it } from 'vitest'
import type { Charge, Drive } from '../services/types'
import { annualReport, historyCsv, historyJson, reportPdf } from './reports'

const drive = { id: '1', source: 'telemetry_mqtt', qualityState: 'observed', startDate: '2026-01-01T00:00:00Z', endDate: '2026-01-01T01:00:00Z',
  distanceKm: 10, durationMinutes: 60, energy: { quality: 'reported', energyKwh: -0.5 } } as Drive
it('annual export follows qualified paired energy, independent coverage and actual currency groups', () => {
  const charges = [{ id: 'a', startDate: drive.startDate, source: 'telemetry_mqtt', qualityState: 'observed', cost: 0, currency: 'USD' },
    { id: 'b', startDate: drive.startDate, source: 'telemetry_mqtt', qualityState: 'observed', cost: 5, currency: 'CNY' }] as Charge[]
  const report = annualReport([drive, { ...drive, id: '2', energy: undefined }], charges, 2026)
  expect(report.drive.efficiency.value).toBe(-5)
  expect(report.drive.efficiency.known).toBe(1)
  expect(report.costs).toEqual([{ currency: 'USD', value: 0, count: 1 }, { currency: 'CNY', value: 5, count: 1 }])
})
it('CSV uses a fixed projection, preserves signed numbers and blocks formula injection', () => {
  const csv = historyCsv([{ ...drive, source: '=PRIVATE()', vin: 'SECRET', startLatitude: 32 } as Drive], [])
  expect(csv).toContain('"-0.5"')
  expect(csv).toContain('"\'=PRIVATE()"')
  expect(csv).not.toMatch(/SECRET|Latitude|32/)
})
it('JSON exports selected qualified rows and explicit coverage without private DTO fields', () => {
  const data = JSON.parse(historyJson([{ ...drive, vin: 'SECRET', startLatitude: 32 } as Drive], [], true))
  expect(data.coverage.complete).toBe(true)
  expect(data.drives[0].qualified_net_kwh).toBe(-.5)
  expect(data.charges).toEqual([])
  expect(JSON.stringify(data)).not.toMatch(/SECRET|Latitude/)
})
it('PDF has correct byte xref offsets and supports a multi-page Chinese report without network', () => {
  const text = String.fromCharCode(...new Uint8Array(reportPdf(Array.from({ length: 40 }, (_, i) => `年度报告 ${i}`))))
  expect(text).toContain('/Count 2')
  expect(text).toContain('5e745ea662a5544a')
  const entries = text.match(/\d{10} 00000 n /g)!
  entries.forEach((entry, index) => expect(text.slice(Number(entry.slice(0, 10)))).toMatch(new RegExp(`^${index + 1} 0 obj`)))
})
