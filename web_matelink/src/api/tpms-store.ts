import type { WebTpms } from './matelink-types'

/**
 * TPMS trend support for the web client. The backend exposes only
 * the current pressures, so the web accumulates its own display
 * samples in localStorage (per car, capped) whenever a status with
 * all four wheels is seen, and draws an honest conclusion from
 * them. This mirrors the spirit of the miniprogram's trend layer
 * without its ambient/highway factor analysis, which needs a
 * temperature series the web client does not have.
 */

export type TpmsSample = { at: number; fl: number; fr: number; rl: number; rr: number }

const KEY_PREFIX = 'matelink-web-tpms-v1:'
const MAX_SAMPLES = 200
const MIN_GAP_MS = 30 * 60 * 1000

export function recordTpmsSample(carId: number, tpms: WebTpms | null, now = Date.now()): void {
  if (!tpms || tpms.fl == null || tpms.fr == null || tpms.rl == null || tpms.rr == null) return
  try {
    const key = KEY_PREFIX + carId
    const samples = readTpmsSamples(carId)
    const last = samples[samples.length - 1]
    if (last && now - last.at < MIN_GAP_MS) return
    if (last && last.fl === tpms.fl && last.fr === tpms.fr && last.rl === tpms.rl && last.rr === tpms.rr) return
    samples.push({ at: now, fl: tpms.fl, fr: tpms.fr, rl: tpms.rl, rr: tpms.rr })
    localStorage.setItem(key, JSON.stringify(samples.slice(-MAX_SAMPLES)))
  } catch {
    /* storage is best-effort */
  }
}

export function readTpmsSamples(carId: number): TpmsSample[] {
  try {
    const raw = localStorage.getItem(KEY_PREFIX + carId)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter(
      (row): row is TpmsSample =>
        typeof row === 'object' && row !== null &&
        typeof (row as TpmsSample).at === 'number' &&
        ['fl', 'fr', 'rl', 'rr'].every(wheel => typeof (row as unknown as Record<string, unknown>)[wheel] === 'number')
    )
  } catch {
    return []
  }
}

export type WheelTrend = { wheel: 'fl' | 'fr' | 'rl' | 'rr'; deltaBar: number | null; verdict: string }

const WHEEL_LABELS = { fl: '左前', fr: '右前', rl: '左后', rr: '右后' } as const

/**
 * Conclusion per wheel from the accumulated samples: needs at
 * least two samples spanning 24 h. A drop of 0.2 bar or more is
 * flagged; +/-0.1 bar reads as stable.
 */
export function tpmsTrend(samples: TpmsSample[]): { spanHours: number; wheels: WheelTrend[] } {
  const wheels: ('fl' | 'fr' | 'rl' | 'rr')[] = ['fl', 'fr', 'rl', 'rr']
  if (samples.length < 2) {
    return { spanHours: 0, wheels: wheels.map(wheel => ({ wheel, deltaBar: null, verdict: `${WHEEL_LABELS[wheel]}：样本不足` })) }
  }
  const first = samples[0]
  const last = samples[samples.length - 1]
  const spanHours = (last.at - first.at) / 3_600_000
  if (spanHours < 24) {
    return { spanHours, wheels: wheels.map(wheel => ({ wheel, deltaBar: null, verdict: `${WHEEL_LABELS[wheel]}：观察时长不足 24 小时` })) }
  }
  return {
    spanHours,
    wheels: wheels.map(wheel => {
      const delta = last[wheel] - first[wheel]
      const verdict =
        delta <= -0.2 ? `${WHEEL_LABELS[wheel]}：下降 ${Math.abs(delta).toFixed(2)} bar，建议检查是否缓慢漏气` :
        delta >= 0.2 ? `${WHEEL_LABELS[wheel]}：上升 ${delta.toFixed(2)} bar（多为补气或温度变化）` :
        `${WHEEL_LABELS[wheel]}：基本稳定（${delta >= 0 ? '+' : ''}${delta.toFixed(2)} bar）`
      return { wheel, deltaBar: delta, verdict }
    }),
  }
}
