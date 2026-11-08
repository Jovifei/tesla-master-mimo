import { TPMS_WHEELS, type TpmsPoint, type TpmsWheel } from './tpms'

/**
 * TPMS trend interpretation, ported from the Android App
 * (domain/analytics/TpmsTrendAnalyzer.kt): pure, evidence-only.
 *
 * The miniprogram cache stores per-wheel points, so samples are first
 * regrouped by observation instant (a wheel absent at an instant is a
 * null pressure, never a fabricated zero). The cache carries no
 * outside-temperature series, so the ambient factor simply never
 * fires here — the check is implemented for contract completeness.
 * The highway factor needs recent drives, supplied by the caller.
 */

export type TpmsTrendFactor = 'ambient' | 'highway' | 'parking' | 'insufficient_evidence'

export type TpmsTrendEvidence = {
  factor: TpmsTrendFactor
  recommendation?: 'manual_cold_check'
}

export type TpmsTrendSample = {
  observedAtMs: number
  pressures: Record<TpmsWheel, number | null>
  outsideTempC: number | null
}

export type TpmsTrendAnalysis = {
  sampleCount: number
  coverageByWheel: Record<TpmsWheel, number>
  deltas: Record<TpmsWheel, number | null>
  factors: TpmsTrendEvidence[]
  conclusion: TpmsTrendEvidence
}

export type TpmsTrendDrive = { endDate: string | null; speedMaxKmh: number | null }

const SIX_HOURS_MS = 6 * 60 * 60 * 1000
const TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000
const EPSILON = 1e-9

export function groupTpmsSamples(points: readonly TpmsPoint[]): TpmsTrendSample[] {
  const byInstant = new Map<string, TpmsTrendSample>()
  for (const point of points) {
    const ms = Date.parse(point.observedAt)
    if (!Number.isFinite(ms) || !Number.isFinite(point.pressureBar)) continue
    let sample = byInstant.get(point.observedAt)
    if (!sample) {
      sample = {
        observedAtMs: ms,
        pressures: { fl: null, fr: null, rl: null, rr: null },
        outsideTempC: null,
      }
      byInstant.set(point.observedAt, sample)
    }
    sample.pressures[point.wheel] = point.pressureBar
  }
  return [...byInstant.values()].sort((a, b) => a.observedAtMs - b.observedAtMs)
}

function completePressures(sample: TpmsTrendSample): number[] | null {
  const values = TPMS_WHEELS.map(wheel => sample.pressures[wheel])
  return values.every(value => value != null) ? (values as number[]) : null
}

function sameStrictDirection(values: number[]): boolean {
  if (values.some(value => value === 0)) return false
  return values.every(value => value > 0) || values.every(value => value < 0)
}

function hasAmbientEvidence(samples: TpmsTrendSample[]): boolean {
  const eligible = samples.filter(sample => sample.outsideTempC != null && completePressures(sample) != null)
  if (eligible.length < 2) return false
  const first = eligible[0]
  const last = eligible[eligible.length - 1]
  const temperatureDelta = last.outsideTempC! - first.outsideTempC!
  const firstPressures = completePressures(first)!
  const lastPressures = completePressures(last)!
  const pressureDeltas = lastPressures.map((value, index) => value - firstPressures[index])
  return temperatureDelta !== 0 && sameStrictDirection(pressureDeltas)
}

function hasHighwayEvidence(samples: TpmsTrendSample[], drives: readonly TpmsTrendDrive[]): boolean {
  const complete = samples.filter(sample => completePressures(sample) != null)
  const first = complete[0]
  const last = complete[complete.length - 1]
  if (!first || !last || first === last) return false
  const firstPressures = completePressures(first)!
  const lastPressures = completePressures(last)!
  const rising = lastPressures.every((value, index) => value > firstPressures[index])
  if (!rising) return false
  return drives.some(drive => {
    if (drive.speedMaxKmh == null || drive.speedMaxKmh < 90 || !drive.endDate) return false
    const end = Date.parse(drive.endDate)
    return Number.isFinite(end) && end <= last.observedAtMs && last.observedAtMs - end <= SIX_HOURS_MS
  })
}

function hasParkingEvidence(samples: TpmsTrendSample[]): boolean {
  const complete = samples.filter(sample => completePressures(sample) != null)
  for (let index = 1; index < complete.length; index++) {
    const before = complete[index - 1]
    const after = complete[index]
    if (after.observedAtMs - before.observedAtMs < TWENTY_FOUR_HOURS_MS) continue
    const beforePressures = completePressures(before)!
    const afterPressures = completePressures(after)!
    const deltas = afterPressures.map((value, i) => value - beforePressures[i])
    const candidates = deltas.filter((candidate, candidateIndex) => {
      if (candidate > -0.2 + EPSILON) return false
      return deltas.every((other, otherIndex) => otherIndex === candidateIndex || other - candidate >= 0.2 - EPSILON)
    })
    if (candidates.length === 1) return true
  }
  return false
}

export function analyzeTpmsTrend(
  samples: readonly TpmsTrendSample[],
  drives: readonly TpmsTrendDrive[] = []
): TpmsTrendAnalysis {
  const ordered = [...samples].sort((a, b) => a.observedAtMs - b.observedAtMs)
  const coverageByWheel = Object.fromEntries(
    TPMS_WHEELS.map(wheel => [wheel, ordered.filter(sample => sample.pressures[wheel] != null).length])
  ) as Record<TpmsWheel, number>
  const deltas = Object.fromEntries(
    TPMS_WHEELS.map(wheel => {
      const values = ordered
        .map(sample => sample.pressures[wheel])
        .filter((value): value is number => value != null)
      return [wheel, values.length >= 2 ? values[values.length - 1] - values[0] : null]
    })
  ) as Record<TpmsWheel, number | null>

  const factors: TpmsTrendEvidence[] = []
  if (hasAmbientEvidence(ordered)) factors.push({ factor: 'ambient' })
  if (hasHighwayEvidence(ordered, drives)) factors.push({ factor: 'highway' })
  if (hasParkingEvidence(ordered)) factors.push({ factor: 'parking', recommendation: 'manual_cold_check' })
  const effective = factors.length ? factors : [{ factor: 'insufficient_evidence' as const }]

  return {
    sampleCount: ordered.length,
    coverageByWheel,
    deltas,
    factors: effective,
    conclusion: effective[0],
  }
}

export const TPMS_FACTOR_LABELS: Record<TpmsTrendFactor, string> = {
  ambient: '可能受环境温度影响',
  highway: '可能受高速行驶升温影响',
  parking: '可能与停车后的胎压变化有关',
  insufficient_evidence: '证据不足，无法提示可能因素',
}

export const TPMS_CONCLUSION_TEXTS: Record<TpmsTrendFactor, string> = {
  ambient: '可能受环境温度影响；原因尚未确认。',
  highway: '可能受高速行驶升温影响；原因尚未确认。',
  parking: '可能与停车后的胎压变化有关；原因尚未确认。',
  insufficient_evidence: '已记录证据不足，暂无法判断趋势原因。',
}

export const TPMS_TREND_DISCLAIMER = '这些只是根据已记录样本推断的可能因素，不代表已确认的原因。'
export const TPMS_COLD_CHECK_RECOMMENDATION = '建议人工进行冷态检查。'
