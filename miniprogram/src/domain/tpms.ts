export const TPMS_WHEELS = ['fl', 'fr', 'rl', 'rr'] as const
export type TpmsWheel = typeof TPMS_WHEELS[number]
export const TPMS_RETENTION_MS = 30 * 24 * 60 * 60 * 1000
/** Presentation freshness only; this never changes the collector evidence timestamp. */
export const TPMS_LIVE_MAX_AGE_MS = 15 * 60 * 1000
export type TpmsReading = {
  pressureBar: number | null
  observedAt: string | null
  source: 'telemetry_mqtt' | null
  timeBasis: 'collector_received_at' | null
  state: 'observed' | 'stale' | 'unknown'
  reason: string | null
}
export type TpmsSnapshot = { wheels: Record<TpmsWheel, TpmsReading>; capturedAt: number }
export type TpmsPoint = { wheel: TpmsWheel; pressureBar: number; observedAt: string; source: 'telemetry_mqtt'; timeBasis: 'collector_received_at' }
/** Display-only values from an API report with an explicit bar unit; never alert or cache evidence. */
export type TpmsReport = Record<TpmsWheel, number | null>

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
}
/** Require an explicit timezone and valid calendar date, rather than device-local parsing. */
export function tpmsInstant(value: unknown): number | null {
  if (typeof value !== 'string' || value.length > 40) return null
  const parts = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(?:Z|[+-](?:0\d|1[0-4]):[0-5]\d)$/.exec(value)
  if (!parts) return null
  const [, y, m, d, h, minute, second] = parts.map(Number)
  const calendar = new Date(Date.UTC(y, m - 1, d))
  if (calendar.getUTCFullYear() !== y || calendar.getUTCMonth() !== m - 1 || calendar.getUTCDate() !== d || h > 23 || minute > 59 || second > 59) return null
  const at = Date.parse(value)
  return Number.isFinite(at) && at > 0 ? at : null
}

function reading(pressure: unknown, timestamp: unknown, unit: unknown, source: unknown, timeBasis: unknown, now: number): TpmsReading {
  const unknown = (reason: string): TpmsReading => ({ pressureBar: null, observedAt: null, source: null, timeBasis: null, state: 'unknown', reason })
  if (typeof pressure !== 'number' || !Number.isFinite(pressure) || pressure < 0 || pressure > 15) return unknown('missing_or_invalid_pressure')
  if (unit !== 'bar') return unknown('missing_or_unverified_unit')
  if (source !== 'telemetry_mqtt') return unknown('missing_or_unverified_source')
  if (timeBasis !== 'collector_received_at') return unknown('missing_or_unverified_time_basis')
  const at = tpmsInstant(timestamp)
  if (at == null) return unknown('missing_or_invalid_wheel_time')
  if (!Number.isFinite(now) || at > now) return unknown('future_wheel_time')
  if (at < now - TPMS_RETENTION_MS) return unknown('outside_retention')
  return { pressureBar: pressure, observedAt: new Date(at).toISOString(), source, timeBasis,
    state: now - at > TPMS_LIVE_MAX_AGE_MS ? 'stale' : 'observed', reason: null }
}

/** Parses only the API's field-specific receipt contract. Never uses global status time. */
export function parseTpmsDetails(value: unknown, now = Date.now()): TpmsSnapshot {
  const raw = record(value)
  const wheels = {} as Record<TpmsWheel, TpmsReading>
  for (const wheel of TPMS_WHEELS) wheels[wheel] = reading(raw[`tpms_pressure_${wheel}`], raw[`tpms_pressure_${wheel}_observed_at`], raw.pressure_unit, raw.source, raw.time_basis, now)
  return { wheels, capturedAt: now }
}

export function parseTpmsReport(value: unknown, pressureUnit: unknown): TpmsReport {
  const raw = record(value)
  const unit = raw.pressure_unit ?? pressureUnit
  return Object.fromEntries(TPMS_WHEELS.map(wheel => {
    const pressure = raw[`tpms_pressure_${wheel}`]
    return [wheel, unit === 'bar' && typeof pressure === 'number' && Number.isFinite(pressure) && pressure >= 0 && pressure <= 15 ? pressure : null]
  })) as TpmsReport
}

/** Rebuilds qualification at reopen while preserving each original receipt instant. */
export function restoreTpmsSnapshot(value: unknown, now = Date.now()): TpmsSnapshot {
  const raw = record(record(value).wheels)
  const wheels = {} as Record<TpmsWheel, TpmsReading>
  for (const wheel of TPMS_WHEELS) {
    const row = record(raw[wheel])
    wheels[wheel] = reading(row.pressureBar, row.observedAt, 'bar', row.source, row.timeBasis, now)
    if (row.state === 'unknown' && row.pressureBar == null && typeof row.reason === 'string' &&
      ['missing_or_invalid_pressure', 'missing_or_unverified_unit', 'missing_or_unverified_source', 'missing_or_unverified_time_basis',
        'missing_or_invalid_wheel_time', 'future_wheel_time', 'outside_retention'].includes(row.reason)) wheels[wheel].reason = row.reason
  }
  return { wheels, capturedAt: now }
}

export function tpmsPoints(snapshot: TpmsSnapshot, now = Date.now()): TpmsPoint[] {
  const qualified = restoreTpmsSnapshot(snapshot, now)
  return TPMS_WHEELS.flatMap(wheel => {
    const row = qualified.wheels[wheel]
    return row.pressureBar == null || !row.observedAt ? [] : [{ wheel, pressureBar: row.pressureBar, observedAt: row.observedAt, source: 'telemetry_mqtt' as const, timeBasis: 'collector_received_at' as const }]
  })
}

export function tpmsMissingLabel(reason: string | null): string {
  const labels: Record<string, string> = {
    missing_or_invalid_pressure: '缺少有效逐轮胎压值', missing_or_unverified_unit: '缺少明确 bar 单位',
    missing_or_unverified_source: '缺少逐轮遥测来源资格', missing_or_unverified_time_basis: '缺少采集接收时间依据',
    missing_or_invalid_wheel_time: '缺少有效逐轮观测时间', future_wheel_time: '观测时间晚于本机时间', outside_retention: '观测已超过30天保留范围',
  }
  return reason && labels[reason] || '等待逐轮有效观测'
}
