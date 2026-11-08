import type { DriveNetMetric, WebDrivePoint, WebDriveSummary } from './matelink-types'

/**
 * 行程能耗口径 —— 与 App / 小程序一致的「客户端本机计算」，移植自
 * miniprogram/src/domain/drive_energy.ts（业主 2026-11-08 核查确认：
 * App 的行程能耗是本机积分计算的，后端不给原生遥测行程净能耗）。
 *
 * 数据来源（后端代码实证，deploy/jourvolt-dev-mock）：
 * - 原生遥测行程（telemetry_mqtt / fleet_telemetry）：energy_consumed_net
 *   恒为 null，energy_contract.net_energy 为 { value_kwh: null,
 *   quality: "unknown", reason: "no_verified_driving_net_energy" }，
 *   后端注释明确禁止拿充电电量冒充行程消耗 —— 因此 web 此前只读后端
 *   字段时，原生行程能耗大面积为空。
 * - 本模块按 App 的优先级在本机解析每程能耗：
 *   ① energy_contract.net_energy 通过资格校验（窗口与行程起止严格一致、
 *      方法门控、覆盖率达标）→ 采用，method 原样标注；
 *   ② 后端标量 energy_consumed_net —— 仅 local_import 旧导入携带
 *      （quality=unknown，标注「未核验」）；归档来源按报告值；
 *   ③ 本机功率积分 powerIntegral()：对 drive_details[].power 做梯形
 *      时间积分（采样间隔 > 30 秒断开，须覆盖完整行程窗口）；
 *   ④ EnergyRemaining 起止差 —— 只出现在 contract 指标内，由 ① 覆盖；
 *   ⑤ 电量差 × 估算容量（车型容量表与 App 相同，标注估算依据）。
 * - 效率（Wh/km）一律本地计算：energyKwh ÷ distanceKm × 1000，
 *   不读后端 consumption_net（原生行程恒为 null）。
 * - 积分不完整、且无其他来源时，该行程能耗留空 —— 聚合时以覆盖数
 *   （可计算行程数 / 总行程数）注明，绝不硬凑。
 */

export type DriveEnergyMethod =
  | 'api_reported_net'
  | 'drive_power_integral'
  | 'energy_remaining_delta'
  | 'soc_capacity_estimate'

export type ResolvedDriveEnergy = {
  energyKwh: number | null
  /** Locally computed: energyKwh / distanceKm * 1000. */
  efficiencyWhKm: number | null
  method: DriveEnergyMethod | null
  quality: string | null
  reason: string | null
  /** True when the value is the unverified local_import scalar. */
  unverified: boolean
  coverageRatio: number | null
}

const EMPTY: ResolvedDriveEnergy = {
  energyKwh: null,
  efficiencyWhKm: null,
  method: null,
  quality: null,
  reason: null,
  unverified: false,
  coverageRatio: null,
}

function nonNegative(value: number | null): number | null {
  return value != null && Number.isFinite(value) && value >= 0 ? value : null
}

function sameInstant(a: string | null, b: string | null): boolean {
  if (!a || !b) return false
  const ta = Date.parse(a)
  const tb = Date.parse(b)
  return Number.isFinite(ta) && ta === tb
}

function instantDeltaMs(a: string | null, b: string | null): number | null {
  if (!a || !b) return null
  const ta = Date.parse(a)
  const tb = Date.parse(b)
  return Number.isFinite(ta) && Number.isFinite(tb) ? Math.abs(ta - tb) : null
}

function integerSoc(value: number | null): number | null {
  return value != null && Number.isInteger(value) && value >= 0 && value <= 100 ? value : null
}

/* ------------------------- power integral ------------------------- */

export type PowerIntegralResult = {
  energyKwh: number | null
  /** Seconds of the drive window covered by integrable samples. */
  coveredSeconds: number
  windowSeconds: number
  coverageRatio: number | null
  reason: string
}

/**
 * Trapezoidal time integral of the drive's power samples (kW over
 * time), ported from the miniprogram. Segments longer than 30 s are
 * not integrated; samples are interpolated onto the exact drive
 * window edges. Energy counts only when the samples cover the whole
 * window ('complete_power_window').
 */
export function powerIntegral(
  points: readonly Pick<WebDrivePoint, 'date' | 'powerKw'>[],
  startIso: string | null,
  endIso: string | null,
): PowerIntegralResult {
  const startMs = Date.parse(startIso ?? '')
  const endMs = Date.parse(endIso ?? '')
  const windowSeconds = Number.isFinite(startMs) && Number.isFinite(endMs) ? (endMs - startMs) / 1000 : 0
  const result = (energyKwh: number | null, coveredSeconds: number, reason: string): PowerIntegralResult => ({
    energyKwh,
    coveredSeconds,
    windowSeconds,
    coverageRatio: windowSeconds > 0 ? coveredSeconds / windowSeconds : null,
    reason,
  })
  if (!(windowSeconds > 0)) return result(null, 0, 'invalid_window')

  const samples: { t: number; power: number }[] = []
  for (const point of points) {
    const t = Date.parse(point.date)
    if (!Number.isFinite(t) || point.powerKw == null || !Number.isFinite(point.powerKw)) continue
    samples.push({ t, power: point.powerKw })
  }
  samples.sort((a, b) => a.t - b.t)
  const unique = samples.filter((sample, index) => index === 0 || sample.t !== samples[index - 1].t)
  if (unique.length < 2 || unique[0].t > startMs || unique[unique.length - 1].t < endMs) {
    return result(null, 0, 'incomplete_power_window')
  }
  const interpolate = (t: number): number | null => {
    if (t <= unique[0].t) return unique[0].power
    for (let i = 1; i < unique.length; i++) {
      if (t > unique[i].t) continue
      const prev = unique[i - 1]
      const next = unique[i]
      const span = next.t - prev.t
      if (span <= 0 || span > 30_000) return null
      return prev.power + ((next.power - prev.power) * (t - prev.t)) / span
    }
    return unique[unique.length - 1].power
  }
  const knots = new Set<number>([startMs, endMs])
  for (const sample of unique) if (sample.t > startMs && sample.t < endMs) knots.add(sample.t)
  const ordered = [...knots].sort((a, b) => a - b)
  if (ordered.some(t => interpolate(t) == null)) return result(null, 0, 'incomplete_power_window')
  let energy = 0
  let covered = 0
  for (let i = 1; i < ordered.length; i++) {
    const from = ordered[i - 1]
    const to = ordered[i]
    const seconds = (to - from) / 1000
    if (seconds > 30) return result(null, covered, 'incomplete_power_window')
    const p0 = interpolate(from)
    const p1 = interpolate(to)
    if (p0 == null || p1 == null) return result(null, covered, 'incomplete_power_window')
    energy += ((p0 + p1) / 2) * (seconds / 3600)
    covered += seconds
  }
  if (Math.abs(covered - windowSeconds) > 0.001) return result(null, covered, 'incomplete_power_window')
  return result(energy, covered, 'complete_power_window')
}

/* ------------------------- capacity table ------------------------- */

/**
 * Estimated usable capacity by model/trim — the same table the App
 * and miniprogram use for the SOC-difference estimate. The result is
 * always labelled an estimate with its basis; it is never presented
 * as a measured capacity.
 */
export function estimateCapacityKwh(model: string | null, trim?: string | null): { kwh: number; basis: string } {
  const text = `${model ?? ''} ${trim ?? ''}`.toLocaleLowerCase()
  if (text.includes('model s') || text.includes('model x')) {
    if (text.includes('plaid')) return { kwh: 100, basis: 'model_assumption' }
    if (text.includes('long range') || text.includes('长续航')) return { kwh: 100, basis: 'model_assumption' }
    if (text.includes('performance') || text.includes('高性能')) return { kwh: 90, basis: 'model_assumption' }
    if (text.includes('75')) return { kwh: 75, basis: 'model_assumption' }
    if (text.includes('85')) return { kwh: 85, basis: 'model_assumption' }
    return { kwh: 100, basis: 'model_assumption' }
  }
  if (text.includes('model 3') || text.includes('model y')) {
    if (text.includes('performance') || text.includes('高性能') || text.includes('long range') || text.includes('长续航') || text.includes('all-wheel') || text.includes('双电机')) {
      return { kwh: 78.4, basis: 'model_assumption' }
    }
    return { kwh: 60, basis: 'model_assumption' }
  }
  return { kwh: 60, basis: 'default_assumption' }
}

const KNOWN_CAPACITY_BASES = new Set([
  'reported_nominal_capacity',
  'reported_usable_capacity',
  'trim_specification',
  'model_assumption',
  'owner_configured',
])

/* --------------------- contract qualification --------------------- */

/**
 * Whether an energy_contract.net_energy metric may be used, with the
 * miniprogram's method gates: the metric window must equal the drive
 * window exactly, and each method carries its own evidence rules.
 * Returns the usable value or null.
 */
function qualifiedContractNet(metric: DriveNetMetric, drive: WebDriveSummary, capacityKwh: number): number | null {
  const value = nonNegative(metric.valueKwh)
  if (value == null) return null
  if (metric.quality !== 'reported' && metric.quality !== 'estimated') return null
  if (metric.unit != null && metric.unit !== 'kWh') return null
  if (!sameInstant(drive.startDate, metric.startDate) || !sameInstant(drive.endDate, metric.endDate)) return null
  const startSoc = integerSoc(drive.startBatteryLevel)
  const endSoc = integerSoc(drive.endBatteryLevel)
  switch (metric.method) {
    case 'api_reported_net':
      return metric.quality === 'reported' && metric.measurementPoint === 'reported_net' ? value : null
    case 'drive_power_integral':
      return metric.quality === 'estimated' && metric.coverageKind === 'time' && metric.coverageRatio != null && metric.coverageRatio >= 0.85
        ? value
        : null
    case 'energy_remaining_delta': {
      if (metric.quality !== 'estimated' || metric.coverageKind !== 'endpoints' || metric.sourceField !== 'EnergyRemaining') return null
      if (metric.coverageRatio != null && metric.coverageRatio < 0.9) return null
      const startDelta = instantDeltaMs(drive.startDate, metric.observedStartAt)
      const endDelta = instantDeltaMs(drive.endDate, metric.observedEndAt)
      const windowMs =
        drive.startDate && drive.endDate ? Date.parse(drive.endDate) - Date.parse(drive.startDate) : NaN
      if (startDelta == null || endDelta == null || startDelta > 30_000 || endDelta > 30_000) return null
      if (!Number.isFinite(windowMs)) return null
      const observedSpan = windowMs - startDelta - endDelta
      return observedSpan >= 0 && observedSpan / windowMs >= 0.9 ? value : null
    }
    case 'soc_capacity_estimate': {
      if (metric.quality !== 'estimated' || metric.coverageKind !== 'endpoints') return null
      if (startSoc == null || endSoc == null || startSoc === endSoc) return null
      if (metric.calculationVersion !== 1) return null
      if (metric.capacityBasis == null || !KNOWN_CAPACITY_BASES.has(metric.capacityBasis)) return null
      const expected = ((startSoc - endSoc) / 100) * capacityKwh
      return Math.abs(expected - value) <= 0.011 ? value : null
    }
    default:
      return null
  }
}

/* --------------------------- resolution --------------------------- */

export type DriveEnergyOptions = {
  /** Detail samples for the local power integral (detail views only). */
  points?: readonly Pick<WebDrivePoint, 'date' | 'powerKw'>[]
  model?: string | null
  trim?: string | null
}

function finish(
  energyKwh: number,
  method: DriveEnergyMethod,
  quality: string,
  drive: WebDriveSummary,
  extra?: Partial<ResolvedDriveEnergy>,
): ResolvedDriveEnergy {
  const distanceKm = drive.distanceKm
  return {
    energyKwh,
    efficiencyWhKm: distanceKm != null && distanceKm > 0 ? (energyKwh / distanceKm) * 1000 : null,
    method,
    quality,
    reason: null,
    unverified: false,
    coverageRatio: null,
    ...extra,
  }
}

/**
 * Resolve one drive's consumed energy with the App's local caliber
 * (priority ①–⑤ documented at the top of this file). Returns nulls
 * with a reason when no source qualifies — the value stays empty
 * rather than being fabricated.
 */
export function resolveDriveEnergy(drive: WebDriveSummary, options?: DriveEnergyOptions): ResolvedDriveEnergy {
  if (drive.qualityState === 'quarantined') return { ...EMPTY, reason: 'quarantined' }
  const capacity = estimateCapacityKwh(options?.model ?? null, options?.trim ?? null)

  // ① Qualified contract metric (includes EnergyRemaining deltas).
  const metric = drive.contractNet
  if (metric) {
    const value = qualifiedContractNet(metric, drive, capacity.kwh)
    if (value != null && metric.method != null) {
      return finish(value, metric.method as DriveEnergyMethod, metric.quality ?? 'estimated', drive, {
        coverageRatio: metric.coverageRatio,
      })
    }
  }

  // ② Backend scalar — only legacy imports carry it.
  const scalar = nonNegative(drive.energyConsumedKwh)
  if (scalar != null) {
    if (drive.source === 'local_import') {
      // Backend comment: the import scalar's original field was
      // ambiguously named; surface it but mark it unverified.
      return finish(scalar, 'api_reported_net', 'unknown', drive, {
        unverified: true,
        reason: 'unverified_import_scalar',
      })
    }
    return finish(scalar, 'api_reported_net', 'reported', drive)
  }

  // ③ Local power integral over the detail samples.
  if (options?.points && options.points.length) {
    const integral = powerIntegral(options.points, drive.startDate, drive.endDate)
    if (integral.energyKwh != null) {
      return finish(integral.energyKwh, 'drive_power_integral', 'estimated', drive, {
        coverageRatio: integral.coverageRatio,
      })
    }
    // Short stationary exception, as in the App: unchanged SOC with
    // ≥ 85% coverage means the drive genuinely consumed ~0.
    const startSoc = integerSoc(drive.startBatteryLevel)
    const endSoc = integerSoc(drive.endBatteryLevel)
    if (
      integral.reason === 'incomplete_power_window' &&
      startSoc != null &&
      startSoc === endSoc &&
      integral.coverageRatio != null &&
      integral.coverageRatio >= 0.85
    ) {
      return finish(0, 'drive_power_integral', 'estimated', drive, { coverageRatio: integral.coverageRatio })
    }
  }

  // ⑤ SOC difference × estimated capacity.
  const startSoc = integerSoc(drive.startBatteryLevel)
  const endSoc = integerSoc(drive.endBatteryLevel)
  if (startSoc != null && endSoc != null && startSoc !== endSoc) {
    const estimated = ((startSoc - endSoc) / 100) * capacity.kwh
    if (estimated > 0) {
      return finish(estimated, 'soc_capacity_estimate', 'estimated', drive, { reason: capacity.basis })
    }
  }

  return { ...EMPTY, reason: 'no_qualified_energy_source' }
}

/** Summary-level resolution (lists and aggregates — no detail samples). */
export function resolveSummaryEnergy(drive: WebDriveSummary, model?: string | null): ResolvedDriveEnergy {
  return resolveDriveEnergy(drive, { model })
}

export type EfficiencyAggregate = {
  kwhPer100km: number | null
  whPerKm: number | null
  /** Drives whose energy resolved under the local caliber. */
  resolvedCount: number
  /** All non-quarantined drives considered. */
  totalCount: number
  /** Distance covered by the resolved drives only. */
  distanceKm: number
}

/** Aggregate efficiency over drives: resolve each drive first, then Σenergy / Σdistance. */
export function aggregateEfficiency(
  drives: readonly WebDriveSummary[],
  model?: string | null,
): EfficiencyAggregate {
  let energy = 0
  let distance = 0
  let resolved = 0
  let total = 0
  for (const drive of drives) {
    if (drive.qualityState === 'quarantined') continue
    total++
    const result = resolveSummaryEnergy(drive, model)
    if (result.energyKwh != null && drive.distanceKm != null && drive.distanceKm > 0) {
      energy += result.energyKwh
      distance += drive.distanceKm
      resolved++
    }
  }
  return {
    kwhPer100km: distance > 0 ? (energy / distance) * 100 : null,
    whPerKm: distance > 0 ? (energy / distance) * 1000 : null,
    resolvedCount: resolved,
    totalCount: total,
    distanceKm: distance,
  }
}

/** Efficiency for drives starting inside [fromMs, toMs) — the 月/周平均能耗 tiers. */
export function periodEfficiency(
  drives: readonly WebDriveSummary[],
  model: string | null,
  fromMs: number,
  toMs: number,
): EfficiencyAggregate {
  return aggregateEfficiency(
    drives.filter(drive => {
      const at = Date.parse(drive.startDate ?? '')
      return Number.isFinite(at) && at >= fromMs && at < toMs
    }),
    model,
  )
}

/** Chinese source label for a resolved value (shown in tile captions). */
export function driveEnergyMethodLabel(result: ResolvedDriveEnergy): string {
  switch (result.method) {
    case 'api_reported_net':
      return result.unverified ? '导入数据（未核验）' : '后端报告值'
    case 'drive_power_integral':
      return '本机功率积分'
    case 'energy_remaining_delta':
      return '电量计起止差'
    case 'soc_capacity_estimate':
      return '电量差估算'
    default:
      return '暂不可计算'
  }
}

export type { DriveNetMetric }
