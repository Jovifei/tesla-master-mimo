import { describe, expect, it } from 'vitest'
import type { Charge, Drive } from '../services/types'
import { analysisGroups, buildParkedWindows, calendarCells, distanceCurveEstimates, efficiencyDistribution, efficiencySpeedGroups, selectCurveSamples } from './analysis'

const drive = (id: string, distanceKm: number | null, energyKwh: number | null, startDate = '2026-10-14T08:00:00+08:00'): Drive => ({
  id, sessionId: id, startDate, endDate: new Date(Date.parse(startDate) + 10 * 60000).toISOString(), durationMinutes: 10,
  distanceKm, energy: { energyKwh, quality: energyKwh == null ? 'unknown' : 'reported' },
  source: 'telemetry_mqtt', qualityState: 'observed', startBatteryLevel: 80, endBatteryLevel: 79,
} as Drive)

describe('advanced App analysis consumers', () => {
  it('limits a 20,000 sample plot while preserving signed extrema/observed zero and the unmodified source', () => {
    const samples = Array.from({ length: 20_000 }, (_, index) => ({ time: index * 1000, value: index === 12345 ? -999 : index === 17777 ? 888 : index === 9999 ? 0 : 20 + index % 10 }))
    const original = JSON.stringify(samples)
    const plot = selectCurveSamples(samples)
    expect(plot.points.length).toBeLessThanOrEqual(600)
    expect(plot.thinned).toBe(true)
    expect(plot.points.some(point => point.value === -999)).toBe(true)
    expect(plot.points.some(point => point.value === 888)).toBe(true)
    expect(plot.points.some(point => point.value === 0)).toBe(true)
    expect(plot.points[0].index).toBe(0)
    expect(plot.points[plot.points.length - 1].index).toBe(19999)
    expect(JSON.stringify(samples)).toBe(original)
  })

  it('never connects selected points across unknown gaps even when hundreds of fragments are omitted', () => {
    const source = Array.from({ length: 20_000 }, (_, index) => ({ time: index, value: index % 2 ? null : index % 4 ? -5 : 0 }))
    const plot = selectCurveSamples(source)
    expect(plot.points.length).toBeLessThanOrEqual(600)
    expect(plot.points.every(point => point.breakBefore)).toBe(true)
    expect(plot.points.some(point => point.value < 0)).toBe(true)
    expect(plot.points.some(point => point.value === 0)).toBe(true)
    expect(plot.segmentCount).toBe(10000)
  })
  it('uses actual uneven sampling times for an explicitly estimated distance distribution and refuses unknown speed', () => {
    const points = [{ date: '2026-10-14T00:00:00Z', speedKmh: 0 }, { date: '2026-10-14T00:01:00Z', speedKmh: 60 }, { date: '2026-10-14T00:10:00Z', speedKmh: 60 }]
    const values = distanceCurveEstimates(points, 10)
    expect(values[0]).toBe(0)
    expect(values[1]).toBeCloseTo(10 / 19)
    expect(values[2]).toBe(10)
    expect(distanceCurveEstimates([{ ...points[0], speedKmh: null }, points[1]], 10)).toEqual([null, null])
    expect(distanceCurveEstimates(points, null)).toEqual([null, null, null])
  })
  it('groups actual dates with independent coverage and a paired weighted denominator', () => {
    const grouped = analysisGroups([drive('a', 10, 2), drive('b', 10, null), drive('c', 0, 0), drive('d', 5, -0.5)], 'month')
    expect(grouped).toHaveLength(1)
    expect(grouped[0]).toMatchObject({ key: '2026-10', count: 4, distance: { value: 25, known: 4, total: 4 },
      energy: { value: 1.5, known: 3, total: 4 }, efficiency: { value: 10, known: 2, total: 4 } })
    expect(analysisGroups([drive('unknown', null, null)], 'year')[0].distance.value).toBeNull()
  })

  it('builds a real civil calendar and keeps unrecorded dates unknown rather than fabricating zero mileage', () => {
    const cells = calendarCells('2026-02', [drive('feb', 0, 0, new Date(2026, 1, 14, 12).toISOString())])
    const days = cells.filter(cell => cell.day != null)
    expect(days).toHaveLength(28)
    expect(days.find(day => day.day?.endsWith('-14'))).toMatchObject({ count: 1, distance: { value: 0 } })
    expect(days.find(day => day.day?.endsWith('-15'))).toMatchObject({ count: 0, distance: { value: null } })
  })

  it('excludes charge-overlapping and cross-source gaps from standby, preserving SOC-only unknown energy', () => {
    const older = { ...drive('old', 1, 0, '2026-10-14T08:00:00+08:00'), sourceInstanceId: 'archive-A', sourceVehicleId: 'car-A' }
    const newer = { ...drive('new', 1, 0, '2026-10-14T10:00:00+08:00'), sourceInstanceId: 'archive-A', sourceVehicleId: 'car-A', startBatteryLevel: 78 }
    const window = buildParkedWindows([newer, older], [], true)[0]
    expect(window).toMatchObject({ olderId: 'old', newerId: 'new', minutes: 110, socDelta: -1, energyKwh: null, chargeOverlap: false, chargeCoverageComplete: true })
    const charge = { id: 'charge', startDate: '2026-10-14T09:00:00+08:00', endDate: '2026-10-14T09:30:00+08:00' } as Charge
    expect(buildParkedWindows([newer, older], [charge], true)[0].chargeOverlap).toBe(true)
    expect(buildParkedWindows([newer, older], [], false)[0].chargeCoverageComplete).toBe(false)
    expect(buildParkedWindows([newer, older], [], false)[0].chargeTimesValid).toBe(true)
    expect(buildParkedWindows([{ ...newer, source: 'teslamate_archive' }, older], [], true)).toEqual([])
    expect(buildParkedWindows([newer, older], [{ ...charge, startDate: null }], true)[0].chargeCoverageComplete).toBe(false)
  })

  it('keeps negative and genuine zero efficiencies, excludes unknown energy, and never uses peak speed as average speed', () => {
    const rows = [drive('a', 10, 2), drive('b', 10, -0.5), drive('zero', 10, 0), drive('unknown', 10, null)]
    const distribution = efficiencyDistribution(rows)
    expect(distribution).toMatchObject({ count: 3, min: -5, median: 0, max: 20 })
    expect(efficiencySpeedGroups(rows)[0]).toMatchObject({ label: '30–80 km/h', known: 3, value: 5 })
    expect(efficiencySpeedGroups([{ ...rows[0], durationMinutes: null, speedMaxKmh: 999 }])).toEqual([])
  })

  it('does not generate a parked window bridging across a quarantined drive', () => {
    const drives = [
      drive('d1', 10, 2, '2026-10-14T08:00:00Z'),
      { ...drive('d2', 10, 2, '2026-10-14T10:00:00Z'), qualityState: 'quarantined' },
      drive('d3', 10, 2, '2026-10-14T12:00:00Z'),
    ]
    const windows = buildParkedWindows(drives, [], false)
    expect(windows).toHaveLength(0)
  })

  it('does not infer parked adjacency from unidentified collectors or different source vehicles', () => {
    const older = drive('old', 1, 0, '2026-10-14T08:00:00+08:00')
    const newer = drive('new', 1, 0, '2026-10-14T10:00:00+08:00')
    expect(buildParkedWindows([newer, older], [], true)).toEqual([])
    expect(buildParkedWindows([
      { ...newer, sourceInstanceId: 'archive-A', sourceVehicleId: 'car-B' },
      { ...older, sourceInstanceId: 'archive-A', sourceVehicleId: 'car-A' },
    ], [], true)).toEqual([])
  })

  it('does not guess adjacency when any drive has an unknown timeline position', () => {
    const older = { ...drive('old', 1, 0, '2026-10-14T08:00:00+08:00'), sourceInstanceId: 'archive-A' }
    const newer = { ...drive('new', 1, 0, '2026-10-14T10:00:00+08:00'), sourceInstanceId: 'archive-A' }
    const unknown = { ...drive('middle', 1, 0), startDate: null, endDate: null, sourceInstanceId: 'archive-A' }
    expect(buildParkedWindows([newer, unknown, older], [], true)).toEqual([])
  })

  it('does not infer adjacency when a drive has only a date or an invalid calendar instant', () => {
    const older = { ...drive('old', 1, 0, '2026-10-14T08:00:00+08:00'), sourceInstanceId: 'archive-A', sourceVehicleId: 'car-A' }
    const newer = { ...drive('new', 1, 0, '2026-10-14T10:00:00+08:00'), sourceInstanceId: 'archive-A', sourceVehicleId: 'car-A' }
    const middle = { ...drive('middle', 1, 0, '2026-10-14T09:00:00+08:00'), sourceInstanceId: 'archive-A', sourceVehicleId: 'car-A' }
    for (const change of [
      { startDate: '2026-10-14' }, { endDate: '2026-10-14' }, { startDate: '2026-10-14T09:00:00' },
      { startDate: '2026-02-30T09:00:00Z' }, { endDate: '2026-02-30T09:00:00Z' },
    ]) {
      expect(buildParkedWindows([newer, { ...middle, ...change }, older], [], true), JSON.stringify(change)).toEqual([])
    }
  })

  it('marks imprecise charge coverage incomplete without inventing an overlap', () => {
    const older = { ...drive('old', 1, 0, '2026-10-14T08:00:00+08:00'), sourceInstanceId: 'archive-A', sourceVehicleId: 'car-A' }
    const newer = { ...drive('new', 1, 0, '2026-10-14T10:00:00+08:00'), sourceInstanceId: 'archive-A', sourceVehicleId: 'car-A' }
    const exact = { id: 'charge', startDate: '2026-10-14T09:00:00+08:00', endDate: '2026-10-14T09:30:00+08:00' } as Charge
    for (const change of [
      { startDate: '2026-10-14', endDate: null }, { endDate: null }, { endDate: '2026-10-14' }, { startDate: '2026-10-14T09:00:00' },
      { startDate: '2026-02-30T09:00:00Z' }, { endDate: '2026-02-30T09:00:00Z' },
      { endDate: '2026-10-14T08:30:00+08:00' },
    ]) {
      const uncertain = { ...exact, ...change }
      expect(buildParkedWindows([newer, older], [uncertain], true)[0], JSON.stringify(change))
        .toMatchObject({ chargeOverlap: false, chargeCoverageComplete: false, chargeTimesValid: false })
      expect(buildParkedWindows([newer, older], [uncertain, exact], true)[0])
        .toMatchObject({ chargeOverlap: true, chargeCoverageComplete: false, chargeTimesValid: false })
    }
  })
})


it('marks only cross-channel gaps as inference-required, retaining hard breaks and original indexes', () => {
  const source = [
    { time: 0, value: 0, fieldState: 'reported' as const },
    { time: 1, value: null, fieldState: 'absent' as const, otherChannel: true },
    { time: 2, value: -2, fieldState: 'reported' as const },
    { time: 3, value: null, fieldState: 'invalid' as const, otherChannel: true },
    { time: 4, value: 5, fieldState: 'reported' as const },
    { time: 5, value: null, fieldState: 'absent' as const, otherChannel: false },
    { time: 6, value: 6, fieldState: 'reported' as const },
    { time: NaN, value: null, fieldState: 'absent' as const, otherChannel: true },
    { time: 8, value: 8, fieldState: 'reported' as const },
    { time: 9, value: 9, fieldState: 'reported' as const, sourceConflict: true },
    { time: 10, value: 10, fieldState: 'reported' as const },
  ]
  const plot = selectCurveSamples(source, { inferAcrossOtherChannels: true })
  expect(plot.points.map(p => [p.index, p.value, p.breakBefore, !!p.inferredBefore])).toEqual([
    [0, 0, true, false], [2, -2, true, true], [4, 5, true, false], [6, 6, true, false], [8, 8, true, false], [10, 10, true, false],
  ])
  expect(selectCurveSamples(source).points.find(p => p.index === 2)?.breakBefore).toBe(true)
  expect(selectCurveSamples(source, { inferAcrossOtherChannels: true, forceDisconnected: true }).points.every(p => p.breakBefore && !p.inferredBefore)).toBe(true)
})

it('preserves inferred and hard gaps when 20,000 samples are reduced for rendering', () => {
  const samples = Array.from({ length: 20_000 }, (_, index) => index % 2
    ? { time: index, value: null, fieldState: index === 9999 ? 'invalid' as const : 'absent' as const, otherChannel: true }
    : { time: index, value: index === 0 ? 0 : index === 10002 ? -99 : index, fieldState: 'reported' as const })
  const plot = selectCurveSamples(samples, { inferAcrossOtherChannels: true })
  expect(plot.points.length).toBeLessThanOrEqual(600)
  expect(plot.points[0]).toMatchObject({ index: 0, value: 0 })
  expect(plot.points[plot.points.length - 1]?.index).toBe(19998)
  expect(plot.points.some(p => p.value === -99)).toBe(true)
  expect(plot.points.some(p => p.inferredBefore)).toBe(true)
  const right = plot.points.find(p => p.index > 9999)!
  expect(right.breakBefore).toBe(true)
  expect(right.inferredBefore).not.toBe(true)
})


it('breaks inferred paths at duplicate or reversed times and contradictory field states', () => {
  const source = [
    { time: 5, value: 0, fieldState: 'reported' as const },
    { time: 5, value: null, fieldState: 'absent' as const, otherChannel: true },
    { time: 6, value: 1, fieldState: 'reported' as const },
    { time: 7, value: null, fieldState: 'absent' as const, otherChannel: true },
    { time: 6, value: 2, fieldState: 'reported' as const },
    { time: 8, value: 3, fieldState: 'invalid' as const },
    { time: 9, value: 4, fieldState: 'reported' as const },
  ]
  expect(selectCurveSamples(source, { inferAcrossOtherChannels: true }).points.map(p => [p.index, p.breakBefore, !!p.inferredBefore]))
    .toEqual([[0, true, false], [2, true, false], [4, true, false], [6, true, false]])
})
