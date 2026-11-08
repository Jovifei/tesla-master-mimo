import { describe, expect, it } from 'vitest'
import type { TpmsPoint, TpmsWheel } from './tpms'
import { analyzeTpmsTrend, groupTpmsSamples, type TpmsTrendSample } from './tpms_trend'

const T0 = Date.parse('2026-11-01T08:00:00Z')
const HOUR = 3600_000

function sample(atMs: number, fl: number | null, fr: number | null, rl: number | null, rr: number | null, temp: number | null = null): TpmsTrendSample {
  return { observedAtMs: atMs, pressures: { fl, fr, rl, rr }, outsideTempC: temp }
}

describe('groupTpmsSamples', () => {
  it('regroups per-wheel points by observation instant', () => {
    const at = '2026-11-01T08:00:00.000Z'
    const points: TpmsPoint[] = (['fl', 'fr', 'rl', 'rr'] as TpmsWheel[]).map(wheel => ({
      wheel,
      pressureBar: 2.5,
      observedAt: at,
      source: 'telemetry_mqtt',
      timeBasis: 'collector_received_at',
    }))
    const samples = groupTpmsSamples(points)
    expect(samples).toHaveLength(1)
    expect(samples[0].pressures).toEqual({ fl: 2.5, fr: 2.5, rl: 2.5, rr: 2.5 })
  })
})

describe('analyzeTpmsTrend (App TpmsTrendAnalyzer parity)', () => {
  it('reports coverage and per-wheel deltas, with insufficient evidence by default', () => {
    const analysis = analyzeTpmsTrend([sample(T0, 2.5, 2.5, 2.6, 2.6), sample(T0 + HOUR, 2.4, 2.5, 2.6, 2.6)])
    expect(analysis.sampleCount).toBe(2)
    expect(analysis.coverageByWheel).toEqual({ fl: 2, fr: 2, rl: 2, rr: 2 })
    expect(analysis.deltas.fl).toBeCloseTo(-0.1)
    expect(analysis.deltas.fr).toBe(0)
    expect(analysis.factors).toEqual([{ factor: 'insufficient_evidence' }])
    expect(analysis.conclusion.factor).toBe('insufficient_evidence')
  })

  it('flags the parking factor when one wheel drops alone across a >=24h gap', () => {
    const analysis = analyzeTpmsTrend([
      sample(T0, 2.6, 2.6, 2.6, 2.6),
      sample(T0 + 26 * HOUR, 2.3, 2.58, 2.6, 2.61),
    ])
    expect(analysis.conclusion.factor).toBe('parking')
    expect(analysis.conclusion.recommendation).toBe('manual_cold_check')
  })

  it('does not flag parking when two wheels drop together', () => {
    const analysis = analyzeTpmsTrend([
      sample(T0, 2.6, 2.6, 2.6, 2.6),
      sample(T0 + 26 * HOUR, 2.3, 2.32, 2.6, 2.6),
    ])
    expect(analysis.factors.map(f => f.factor)).not.toContain('parking')
  })

  it('flags the highway factor only with a fast drive ending within 6h before the last sample', () => {
    const samples = [sample(T0, 2.4, 2.4, 2.4, 2.4), sample(T0 + 2 * HOUR, 2.6, 2.6, 2.6, 2.6)]
    const driveEnd = new Date(T0 + HOUR).toISOString()
    expect(analyzeTpmsTrend(samples, [{ endDate: driveEnd, speedMaxKmh: 110 }]).factors.map(f => f.factor)).toContain('highway')
    expect(analyzeTpmsTrend(samples, [{ endDate: driveEnd, speedMaxKmh: 80 }]).factors.map(f => f.factor)).not.toContain('highway')
    expect(analyzeTpmsTrend(samples, []).factors.map(f => f.factor)).not.toContain('highway')
  })

  it('flags the ambient factor when temperature moves and all pressures follow strictly', () => {
    const analysis = analyzeTpmsTrend([
      sample(T0, 2.4, 2.4, 2.5, 2.5, 10),
      sample(T0 + 3 * HOUR, 2.55, 2.55, 2.62, 2.62, 18),
    ])
    expect(analysis.factors[0].factor).toBe('ambient')
  })
})
