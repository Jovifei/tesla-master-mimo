import type {
  Car, Charge, ChargeEnergy, Drive, DriveEnergy, DriveEnergyReceipt, DrivePowerIntegral,
  EnergyContract, EnergyMetric, RawCharge, RawDrive,
} from '../services/types'

type Row = Record<string, unknown>
type Instant = { seconds: number; nanos: number }
const own = (row: object, key: string): boolean => Object.prototype.hasOwnProperty.call(row, key)
const row = (value: unknown): Row => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Row : {}
const number = (value: unknown): number | null => typeof value === 'number' && Number.isFinite(value) ? value : null
const text = (value: unknown): string | null => typeof value === 'string' && value.length <= 160 && !/[\u0000-\u001f\u007f]/.test(value) ? value : null
const soc = (value: unknown): number | null => { const v = number(value); return v !== null && Number.isInteger(v) && v >= 0 && v <= 100 ? v : null }
const reason = (value: unknown): string | null => typeof value === 'string' && /^[a-z][a-z0-9_]{0,95}$/.test(value) ? value : null

/** Seconds/nanoseconds retain exact submillisecond identity without BigInt on older WeChat engines. */
function instant(value: unknown): Instant | null {
  if (typeof value !== 'string') return null
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}:\d{2})?$/i.exec(value)
  if (!m) return null
  const [year, month, day, hour, minute, second] = [m[1], m[2], m[3], m[4], m[5], m[6] ?? '0'].map(Number)
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  if (month < 1 || month > 12 || day < 1 || day > days[month - 1] || hour > 23 || minute > 59 || second > 59) return null
  const date = new Date(0)
  let offset = 0
  if (m[8]) {
    date.setUTCFullYear(year, month - 1, day); date.setUTCHours(hour, minute, second, 0)
    if (m[8].toUpperCase() !== 'Z') {
      const hours = Number(m[8].slice(1, 3)), minutes = Number(m[8].slice(4, 6))
      if (hours > 18 || minutes > 59 || (hours === 18 && minutes !== 0)) return null
      offset = (hours * 3600 + minutes * 60) * (m[8][0] === '+' ? 1 : -1)
    }
  } else {
    // Like Android's legacy LocalDateTime fallback, timezone-less timestamps use the device zone.
    date.setFullYear(year, month - 1, day); date.setHours(hour, minute, second, 0)
  }
  const seconds = date.getTime() / 1000 - offset
  return Number.isFinite(seconds) ? { seconds, nanos: Number((m[7] ?? '').padEnd(9, '0')) } : null
}

const compare = (a: Instant, b: Instant): number => a.seconds === b.seconds ? a.nanos - b.nanos : a.seconds - b.seconds
const elapsed = (a: Instant, b: Instant): number => b.seconds - a.seconds + (b.nanos - a.nanos) / 1e9
export function sameEnergyInstant(a: unknown, b: unknown): boolean {
  const first = instant(a), last = instant(b)
  return first !== null && last !== null && compare(first, last) === 0
}
function sameWindow(a: RawDrive, b: RawDrive): boolean {
  return sameEnergyInstant(a.start_date, b.start_date) && sameEnergyInstant(a.end_date, b.end_date)
}

function metric(value: unknown): EnergyMetric | null {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null
  const input = row(value), result: Row = {}
  for (const key of ['unit', 'method', 'measurement_point', 'source', 'source_field', 'quality', 'start_date', 'end_date',
    'observed_start_at', 'observed_end_at', 'time_basis', 'coverage_kind', 'activity_evidence', 'capacity_basis',
    'capacity_source_field', 'capacity_model', 'capacity_trim']) if (own(input, key)) result[key] = text(input[key])
  for (const key of ['value_kwh', 'coverage_seconds', 'coverage_ratio', 'covered_energy_kwh', 'capacity_kwh',
    'calculation_version', 'start_soc', 'end_soc']) if (own(input, key)) result[key] = number(input[key])
  if (own(input, 'reason')) result.reason = reason(input.reason)
  return result as EnergyMetric
}

function contract(value: unknown): EnergyContract {
  const input = row(value), result: EnergyContract = {}
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return { version: 0 }
  if (own(input, 'version')) result.version = number(input.version)
  for (const key of ['net_energy', 'battery_input', 'ac_input', 'stored_change', 'ac_loss'] as const)
    if (own(input, key)) result[key] = metric(input[key])
  if (own(input, 'ac_efficiency')) result.ac_efficiency = number(input.ac_efficiency)
  if (own(input, 'charge_mode')) result.charge_mode = text(input.charge_mode)
  if (own(input, 'charge_mode_evidence')) result.charge_mode_evidence = text(input.charge_mode_evidence)
  return result
}

function validVersion(value: EnergyContract): boolean { return (value.version === undefined ? 1 : value.version) === 1 }
function qualified(metric: EnergyMetric | null | undefined, start: unknown, end: unknown): number | null {
  if (!metric || number(metric.value_kwh) === null || (metric.unit === undefined ? 'kWh' : metric.unit) !== 'kWh' ||
    !metric.source?.trim() || !['reported', 'estimated'].includes(metric.quality ?? 'unknown') ||
    !sameEnergyInstant(start, metric.start_date) || !sameEnergyInstant(end, metric.end_date)) return null
  const first = instant(start), last = instant(end)
  if (!first || !last || compare(last, first) <= 0) return null
  const ratio = number(metric.coverage_ratio)
  const full = ratio !== null && ratio >= 1 - 1e-9 && ratio <= 1
  let valid = false
  switch (metric.method) {
    case 'api_reported_net': valid = metric.quality === 'reported' && metric.measurement_point === 'reported_net'; break
    case 'drive_power_integral': valid = metric.quality === 'estimated' && metric.measurement_point === 'drive_power' &&
      metric.coverage_kind === 'time' && ratio !== null && ratio >= 0.85 && ratio <= 1 + 1e-6; break
    case 'energy_remaining_delta': valid = metric.quality === 'estimated' && metric.measurement_point === 'nominal_battery_remaining' &&
      metric.coverage_kind === 'endpoints' && full && nominalEndpoints(metric, first, last); break
    case 'soc_capacity_estimate': {
      const capacity = number(metric.capacity_kwh), from = soc(metric.start_soc), to = soc(metric.end_soc)
      const expected = capacity !== null && from !== null && to !== null ? (from - to) / 100 * capacity : null
      valid = metric.quality === 'estimated' && metric.measurement_point === 'battery_soc' && metric.coverage_kind === 'endpoints' && full &&
        capacity !== null && capacity > 0 && from !== null && to !== null && from !== to && metric.calculation_version === 1 &&
        ['model_assumption', 'model_trim_assumption', 'provided_capacity', 'api_reported_current_capacity'].includes(metric.capacity_basis ?? '') &&
        expected !== null && Math.abs(metric.value_kwh! - expected) <= 1e-9 * Math.max(1, Math.abs(expected)); break
    }
    case 'session_counter_delta': valid = metric.quality === 'reported' && ['battery_input', 'ac_charger_input'].includes(metric.measurement_point ?? '') && metric.coverage_kind === 'endpoints' && full; break
  }
  return valid ? metric.value_kwh! : null
}

function nominalEndpoints(m: EnergyMetric, start: Instant, end: Instant): boolean {
  const first = instant(m.observed_start_at), last = instant(m.observed_end_at)
  return m.source === 'telemetry_mqtt' && m.source_field === 'EnergyRemaining' && m.time_basis === 'collector_received_at' &&
    m.activity_evidence === 'continuous_drive_no_charging_with_valid_endpoints' && first !== null && last !== null &&
    compare(last, first) > 0 && compare(first, start) >= 0 && compare(last, end) <= 0 &&
    elapsed(start, first) <= 30 && elapsed(last, end) <= 30 && elapsed(first, last) >= elapsed(start, end) * 0.9
}

function powerIntegral(raw: RawDrive): DrivePowerIntegral {
  const start = instant(raw.start_date), end = instant(raw.end_date)
  const result: DrivePowerIntegral = { energyKwh: null, coverageSeconds: 0, windowSeconds: null, complete: false,
    reason: 'missing_window', startDate: text(raw.start_date), endDate: text(raw.end_date) }
  if (!start || !end || compare(end, start) <= 0) return { ...result, reason: 'invalid_window' }
  result.windowSeconds = elapsed(start, end)
  const samples = raw.drive_details
  if (!Array.isArray(samples) || samples.length > 20000) return { ...result, reason: Array.isArray(samples) ? 'power_samples_limit' : 'no_covered_interval' }
  const groups = new Map<string, { time: Instant; power: number | null }>()
  let invalid = false, failed = false, energy = 0
  for (const sample of samples) {
    const time = instant(row(sample).date), value = number(row(sample).power)
    if (!time) { invalid = true; continue }
    const key = `${time.seconds}:${time.nanos}`, previous = groups.get(key)
    groups.set(key, { time, power: previous ? (previous.power === value ? value : null) : value })
  }
  const points = [...groups.values()].sort((a, b) => compare(a.time, b.time))
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1], b = points[i], seconds = elapsed(a.time, b.time)
    if (a.power === null || b.power === null || seconds <= 0 || seconds > 30) continue
    const from = compare(start, a.time) > 0 ? start : a.time, to = compare(end, b.time) < 0 ? end : b.time
    if (compare(to, from) <= 0) continue
    const f = elapsed(a.time, from) / seconds, t = elapsed(a.time, to) / seconds
    const p0 = a.power * (1 - f) + b.power * f, p1 = a.power * (1 - t) + b.power * t
    const part = (p0 / 2 + p1 / 2) * elapsed(from, to) / 3600
    if (!Number.isFinite(part) || !Number.isFinite(energy + part)) { failed = true; continue }
    energy += part; result.coverageSeconds += elapsed(from, to)
  }
  result.energyKwh = result.coverageSeconds > 0 && !failed ? energy : null
  result.complete = result.coverageSeconds > 0 && !invalid && !failed && Math.abs(result.coverageSeconds - result.windowSeconds) <= 1e-6
  result.reason = failed ? 'non_finite_integral' : invalid ? 'invalid_sample_timestamp' : result.coverageSeconds === 0 ? 'no_covered_interval' : result.complete ? 'complete_power_window' : 'incomplete_power_coverage'
  return result
}

/** Keeps only parsed energy input; addresses, coordinates, credentials and route arrays are excluded. */
export function driveEnergyReceipt(raw: RawDrive, distanceUnit?: string | null): DriveEnergyReceipt {
  const input = row(raw), result: Row = { parsedEnergyVersion: 1, distance_unit: distanceUnit === undefined ? 'km' : distanceUnit }
  for (const key of ['start_date', 'end_date', 'source']) if (own(input, key)) result[key] = text(input[key])
  if (own(input, 'drive_id')) result.drive_id = typeof input.drive_id === 'number' ? number(input.drive_id) : text(input.drive_id)
  for (const key of ['duration_min', 'energy_consumed_net', 'consumption_net']) if (own(input, key)) result[key] = number(input[key])
  if (own(input, 'odometer_details')) {
    const odometer = row(input.odometer_details)
    result.odometer_details = own(odometer, 'odometer_distance') ? { odometer_distance: number(odometer.odometer_distance) } : {}
  }
  if (own(input, 'battery_details')) {
    const battery = row(input.battery_details), parsed: Row = {}
    for (const key of ['start_battery_level', 'end_battery_level']) if (own(battery, key)) parsed[key] = soc(battery[key])
    result.battery_details = parsed
  }
  if (input.energy_contract != null) result.energy_contract = contract(input.energy_contract)
  else if (own(input, 'energy_contract')) result.energy_contract = null
  if (own(input, 'drive_details')) result.power_integral = powerIntegral(raw)
  return result as DriveEnergyReceipt
}

/** Mini displays km; keep the provider scalar/unit intact and convert only the projection. */
export function driveDistanceKm(input: DriveEnergyReceipt): number | null {
  const distance = number(input.odometer_details?.odometer_distance)
  const unit = input.distance_unit === undefined ? 'km' : input.distance_unit
  const km = distance !== null && distance >= 0 && (unit === 'km' || unit === 'mi')
    ? distance * (unit === 'mi' ? 1.609344 : 1) : null
  return number(km)
}

function capacity(car?: Car): { value: number; basis: string; field: string; model: string | null; trim: string | null } | null {
  const modelText = text(car?.model), trimText = text(car?.trim)
  const model = modelText?.trim().toUpperCase() ?? '', trim = trimText?.trim().toUpperCase() ?? ''
  const provided = number(car?.capacityKwh)
  if (provided !== null && provided > 0) return { value: provided, basis: car?.capacityBasis == null ? 'provided_capacity' : text(car.capacityBasis) ?? 'unsupported_capacity_basis',
    field: text(car?.capacitySourceField) ?? 'provided_capacity_kwh', model: modelText, trim: trimText }
  if (!['S', 'X', '3', 'Y'].includes(model)) return null
  let value = 60
  if (model === 'S' || model === 'X') value = trim.includes('100') || trim.includes('PLAID') ? 100 : trim.includes('90') ? 90 : trim.includes('85') ? 85 : trim.includes('75') ? 75 : 100
  else if (model === '3' || model === 'Y') value = trim.includes('PERFORMANCE') || trim.startsWith('P') || ['LONG', 'LR', 'AWD', 'DUAL'].some(x => trim.includes(x)) ? 78.4 : 60
  return { value, basis: trim ? 'model_trim_assumption' : 'model_assumption',
    field: trim ? 'car_details.model+trim_badging' : 'car_details.model', model: modelText, trim: trimText }
}

function projection(value: number | null, distance: unknown, evidence: EnergyMetric | null, why: string | null): DriveEnergy {
  const km = number(distance), usable = number(value)
  const source: DriveEnergy['source'] = usable === null ? 'unavailable' : evidence?.method === 'drive_power_integral' ? 'power_samples' :
    evidence?.method === 'energy_remaining_delta' ? 'energy_remaining_delta' : evidence?.method === 'soc_capacity_estimate' ? 'soc_capacity_estimate' : 'api'
  const wh = usable !== null && km !== null && km > 0 ? usable / km * 1000 : null
  const ratio = number(evidence?.coverage_ratio), seconds = number(evidence?.coverage_seconds)
  return { energyKwh: usable, efficiencyWhKm: number(wh), source, quality: usable === null ? 'unknown' : source === 'api' ? 'reported' : 'estimated',
    reason: why, coverageRatio: ratio !== null && ratio >= 0 && ratio <= 1 ? ratio : null,
    coverageSeconds: seconds !== null && seconds >= 0 ? seconds : null, observedEnergyKwh: number(evidence?.covered_energy_kwh), evidence }
}

export function resolveDriveEnergy(raw: RawDrive | DriveEnergyReceipt, car?: Car): DriveEnergy {
  const input = (raw as DriveEnergyReceipt).parsedEnergyVersion === 1 ? raw as DriveEnergyReceipt : driveEnergyReceipt(raw)
  const distance = driveDistanceKm(input)
  if (input.energy_contract != null) {
    const proof = contract(input.energy_contract), m = proof.net_energy ?? null
    const value = validVersion(proof) && (!input.source || m?.source === input.source) &&
      ['api_reported_net', 'drive_power_integral', 'energy_remaining_delta', 'soc_capacity_estimate'].includes(m?.method ?? '') ? qualified(m, input.start_date, input.end_date) : null
    return projection(value, distance, m, reason(m?.reason) ?? (value === null ? 'unqualified_energy_contract' : null))
  }
  const legacy = input.source == null || input.source === 'teslamate_archive'
  const scalar = legacy ? number(input.energy_consumed_net) : null
  const basis: EnergyMetric = { unit: 'kWh', source: input.source ?? 'legacy_api', start_date: input.start_date, end_date: input.end_date }
  if (scalar !== null) return projection(scalar, distance, { ...basis, value_kwh: scalar, method: 'api_reported_net', measurement_point: 'reported_net',
    quality: 'reported', time_basis: 'source_report', coverage_kind: 'provider_report' }, 'api_reported_net_energy')
  const power = input.source === 'local_import' || input.source === 'local_history' ? undefined : input.power_integral
  const first = instant(input.start_date), last = instant(input.end_date)
  const windowSeconds = first && last && compare(last, first) > 0 ? elapsed(first, last) : null
  const compatiblePower = power && windowSeconds !== null && number(power.coverageSeconds) !== null && power.coverageSeconds >= 0 &&
    number(power.windowSeconds) !== null && Math.abs(power.windowSeconds! - windowSeconds) <= 1e-6 && power.coverageSeconds <= windowSeconds + 1e-6 &&
    sameEnergyInstant(power.startDate, input.start_date) && sameEnergyInstant(power.endDate, input.end_date)
  const integral = compatiblePower ? number(power.energyKwh) : null
  const ratio = compatiblePower && power.windowSeconds !== null && power.windowSeconds > 0 ? power.coverageSeconds / power.windowSeconds : null
  const from = soc(input.battery_details?.start_battery_level), to = soc(input.battery_details?.end_battery_level)
  const complete = compatiblePower && power?.complete === true && power.reason === 'complete_power_window' && Math.abs(power.coverageSeconds - windowSeconds!) <= 1e-6
  if (integral !== null && (complete || (from !== null && from === to && ratio !== null && ratio >= 0.85))) {
    const m: EnergyMetric = { ...basis, value_kwh: integral, method: 'drive_power_integral', measurement_point: 'drive_power', source_field: 'drive_details.power',
      quality: 'estimated', time_basis: input.source === 'telemetry_mqtt' ? 'collector_received_at' : input.source === 'teslamate_archive' ? 'source_sample_time' : 'route_timestamp_unverified',
      coverage_kind: 'time', coverage_seconds: power!.coverageSeconds, coverage_ratio: ratio, covered_energy_kwh: integral }
    return projection(integral, distance, m, complete ? 'complete_power_window' : 'power_integral_short_trip')
  }
  if (from !== null && to !== null && from !== to && from - to >= -30) {
    const c = capacity(car)
    if (!c) return projection(null, distance, integral === null ? null : { ...basis, covered_energy_kwh: integral,
      coverage_seconds: power!.coverageSeconds, coverage_ratio: ratio }, 'capacity_basis_unavailable')
    const value = (from - to) / 100 * c.value
    const m: EnergyMetric = { ...basis, value_kwh: number(value), method: 'soc_capacity_estimate', measurement_point: 'battery_soc', source_field: 'battery_details',
      quality: 'estimated', time_basis: 'source_report', coverage_kind: 'endpoints', coverage_ratio: 1, covered_energy_kwh: integral,
      capacity_kwh: c.value, capacity_basis: c.basis, capacity_source_field: c.field, capacity_model: c.model, capacity_trim: c.trim,
      calculation_version: 1, start_soc: from, end_soc: to }
    const admitted = qualified(m, input.start_date, input.end_date)
    return projection(admitted, distance, m, admitted === null ? 'invalid_window_or_capacity_basis' : 'soc_capacity_estimate')
  }
  return projection(null, distance, integral === null ? null : { ...basis, covered_energy_kwh: integral, coverage_seconds: power!.coverageSeconds, coverage_ratio: ratio },
    from !== null && from === to ? 'soc_unchanged_resolution_limit' : power?.reason ?? 'no_verified_driving_net_energy')
}

function capacityRank(m: EnergyMetric | null | undefined): number {
  return ['api_reported_current_capacity', 'provided_capacity'].includes(m?.capacity_basis ?? '') ? 3 : m?.capacity_basis === 'model_trim_assumption' ? 2 : m?.capacity_basis === 'model_assumption' ? 1 : 0
}

/** Generic admission remains authoritative; a missing summary is not a fresh detail proof. */
export function mergeDriveEnergyRecord(prior: Drive, incoming: Drive, admittedBase: Drive): Drive {
  const sameShownWindow = prior.id === admittedBase.id && prior.source === admittedBase.source &&
    sameEnergyInstant(prior.startDate, admittedBase.startDate) && sameEnergyInstant(prior.endDate, admittedBase.endDate)
  if (!incoming.energyReceipt) {
    const retained = sameShownWindow && prior.energyReceipt ? prior.energyReceipt : undefined
    const proof = retained ? prior.energy?.evidence : null
    const energy = retained ? (proof ? projection(qualified(proof, admittedBase.startDate, admittedBase.endDate), admittedBase.distanceKm, proof, reason(proof.reason)) : resolveDriveEnergy(retained)) : projection(null, admittedBase.distanceKm, null, 'legacy_cache_without_energy_receipt')
    return { ...admittedBase, energy, energyReceipt: retained, energyConsumedKwh: energy.energyKwh, efficiencyWhKm: energy.efficiencyWhKm }
  }
  const old = prior.energyReceipt ?? {}, fresh = incoming.energyReceipt
  if (incoming.source !== admittedBase.source && incoming.source !== null) {
    const retained = sameShownWindow && prior.energyReceipt !== undefined
    const energy = retained ? prior.energy ?? resolveDriveEnergy(old) : projection(null, admittedBase.distanceKm, null, 'history_source_window_changed')
    return { ...admittedBase, energy, energyReceipt: retained ? old : undefined, energyConsumedKwh: energy.energyKwh, efficiencyWhKm: energy.efficiencyWhKm }
  }
  const sameSource = prior.energyReceipt !== undefined && prior.id === incoming.id && (prior.source === incoming.source || !own(fresh, 'source'))
  let input: DriveEnergyReceipt = sameSource ? { ...old, ...fresh, parsedEnergyVersion: 1 } : { ...fresh, parsedEnergyVersion: 1 }
  if (sameSource) {
    input.odometer_details = { ...old.odometer_details, ...fresh.odometer_details }
    input.battery_details = { ...old.battery_details, ...fresh.battery_details }
  }
  const compatible = sameSource && sameWindow(old, input)
  if (!compatible) input = { ...fresh, parsedEnergyVersion: 1 }
  else {
    // A sparse list row may retain a detail odometer only with that odometer's unit.
    if (fresh.odometer_details?.odometer_distance == null && (fresh.distance_unit === 'km' || fresh.distance_unit === 'mi'))
      input.distance_unit = old.distance_unit
    if (fresh.energy_contract == null && old.energy_contract != null) input.energy_contract = old.energy_contract
    input.odometer_details = { ...input.odometer_details,
      odometer_distance: input.odometer_details?.odometer_distance ?? old.odometer_details?.odometer_distance }
    input.battery_details = { ...input.battery_details,
      start_battery_level: input.battery_details?.start_battery_level ?? old.battery_details?.start_battery_level,
      end_battery_level: input.battery_details?.end_battery_level ?? old.battery_details?.end_battery_level }
  }
  const shown = { ...admittedBase, startDate: own(input, 'start_date') ? input.start_date ?? null : compatible ? admittedBase.startDate : null,
    endDate: own(input, 'end_date') ? input.end_date ?? null : compatible ? admittedBase.endDate : null,
    source: own(input, 'source') ? input.source ?? null : compatible ? admittedBase.source : incoming.source,
    distanceKm: driveDistanceKm(input),
    startBatteryLevel: input.battery_details?.start_battery_level ?? null, endBatteryLevel: input.battery_details?.end_battery_level ?? null }
  const explicit = fresh.energy_contract != null
  const unchangedInputs = old.odometer_details?.odometer_distance === input.odometer_details?.odometer_distance &&
    old.battery_details?.start_battery_level === input.battery_details?.start_battery_level &&
    old.battery_details?.end_battery_level === input.battery_details?.end_battery_level
  // A weak same-input list scalar cannot replace a genuine earlier report with a SOC guess.
  if (compatible && !explicit && unchangedInputs && old.energy_contract == null && number(old.energy_consumed_net) !== null &&
    number(fresh.energy_consumed_net) === null) input.energy_consumed_net = old.energy_consumed_net
  if (compatible && !explicit && input.energy_contract?.net_energy?.method === 'soc_capacity_estimate' &&
    qualified(input.energy_contract.net_energy, old.start_date, old.end_date) !== null) delete input.energy_contract
  const previous = compatible ? prior.energy ?? resolveDriveEnergy(old) : null
  const retained = previous?.energyKwh !== null && previous?.evidence &&
    qualified(previous.evidence, old.start_date, old.end_date) !== null ? previous.evidence : null
  const proposed = incoming.energy?.evidence
  const qualifiedProposal = proposed?.method === 'soc_capacity_estimate' && qualified(proposed, input.start_date, input.end_date) !== null &&
    (!input.source || proposed.source === input.source) && proposed.start_soc === input.battery_details?.start_battery_level && proposed.end_soc === input.battery_details?.end_battery_level
  const chosen = qualifiedProposal && (!retained || capacityRank(proposed) > capacityRank(retained)) ? proposed : retained
  const car: Car | undefined = chosen?.method === 'soc_capacity_estimate' && number(chosen.capacity_kwh) !== null ? {
    id: 0, stableId: '', name: null, model: chosen.capacity_model ?? null, trim: chosen.capacity_trim ?? null, exteriorColor: null, wheelType: null, displayName: null,
    capacityKwh: chosen.capacity_kwh, capacityBasis: chosen.capacity_basis, capacitySourceField: chosen.capacity_source_field, capacityCalculationVersion: chosen.calculation_version,
  } : undefined
  let energy = resolveDriveEnergy(input, car)
  if (!explicit && compatible && retained && retained.method !== 'soc_capacity_estimate' && energy.source === 'unavailable') {
    const value = retained.source === (input.source ?? 'legacy_api') ? qualified(retained, input.start_date, input.end_date) : null
    energy = projection(value, shown.distanceKm, retained, reason(retained.reason))
  }
  const validLengthUnit = input.distance_unit === undefined || input.distance_unit === 'km' || input.distance_unit === 'mi'
  return { ...shown, speedMaxKmh: validLengthUnit ? incoming.speedMaxKmh ?? shown.speedMaxKmh : null,
    ...(incoming.speedMaxKmh != null ? { speedUnit: incoming.speedUnit, speedSampleCount: incoming.speedSampleCount,
      speedMetricsSource: incoming.speedMetricsSource } : {}),
    energy, energyReceipt: input, energyConsumedKwh: energy.energyKwh, efficiencyWhKm: energy.efficiencyWhKm }
}

export function resolveChargeEnergy(raw: RawCharge): ChargeEnergy {
  const proof = raw.energy_contract != null ? contract(raw.energy_contract) : null
  const value = (m: EnergyMetric | null | undefined, point: string, scalar: unknown): number | null => {
    if (!proof) { const n = number(scalar); return (raw.source == null || raw.source === 'teslamate_archive') && n !== null && n >= 0 ? n : null }
    if (!validVersion(proof) || m?.method !== 'session_counter_delta' || m.measurement_point !== point || (raw.source && m.source !== raw.source)) return null
    if (point === 'ac_charger_input' && (proof.charge_mode !== 'ac' || proof.charge_mode_evidence !== 'observed_boundary_modes_no_conflict')) return null
    const n = qualified(m, raw.start_date, raw.end_date)
    return n !== null && n >= 0 ? n : null
  }
  const batteryInputKwh = value(proof?.battery_input, 'battery_input', raw.charge_energy_added)
  const acInputKwh = proof ? value(proof.ac_input, 'ac_charger_input', null) : null
  const inputEnergyKwh = proof ? acInputKwh : value(null, 'legacy_input', raw.charge_energy_used)
  const chargeMode = proof && validVersion(proof) && proof.charge_mode_evidence === 'observed_boundary_modes_no_conflict' && (proof.charge_mode === 'ac' || proof.charge_mode === 'dc') ? proof.charge_mode : null
  return { batteryInputKwh, acInputKwh, inputEnergyKwh, chargeMode, quality: batteryInputKwh !== null || inputEnergyKwh !== null ? 'reported' : 'unknown',
    reason: batteryInputKwh === null && inputEnergyKwh === null ? 'no_verified_charging_energy' : null, evidence: proof }
}

export function mergeChargeEnergyRecord(prior: Charge, incoming: Charge, admittedBase: Charge): Charge {
  if (incoming.source !== admittedBase.source && incoming.source !== null) return admittedBase
  const money = { ...(incoming.currency !== undefined ? { currency: incoming.currency } : {}),
    ...(incoming.currencyCode !== undefined ? { currencyCode: incoming.currencyCode } : {}) }
  const compatible = prior.id === incoming.id && prior.source === incoming.source &&
    sameEnergyInstant(prior.startDate, incoming.startDate) && sameEnergyInstant(prior.endDate, incoming.endDate)
  if (!compatible || incoming.energy?.evidence) return { ...admittedBase, ...money, source: incoming.source,
    startDate: incoming.startDate, endDate: incoming.endDate, energy: incoming.energy,
    energyAddedKwh: incoming.energy?.batteryInputKwh ?? null, energyUsedKwh: incoming.energy?.inputEnergyKwh ?? null,
    chargeMode: incoming.energy?.chargeMode ?? incoming.chargeMode ?? null }
  const proof = prior.energy?.evidence
  const previous = proof ? resolveChargeEnergy({ source: admittedBase.source, start_date: admittedBase.startDate, end_date: admittedBase.endDate, energy_contract: proof }) : prior.energy
  const battery = incoming.energy?.batteryInputKwh ?? previous?.batteryInputKwh ?? null
  const input = incoming.energy?.inputEnergyKwh ?? previous?.inputEnergyKwh ?? null
  const energy: ChargeEnergy | undefined = proof ? previous : (incoming.energy || previous ? { ...(incoming.energy ?? previous!),
    batteryInputKwh: battery, acInputKwh: null, inputEnergyKwh: input,
    quality: battery !== null || input !== null ? 'reported' : 'unknown',
    reason: battery !== null || input !== null ? null : 'no_verified_charging_energy',
    evidence: incoming.energy?.evidence ?? previous?.evidence ?? null, chargeMode: incoming.energy?.chargeMode ?? previous?.chargeMode ?? null } : undefined)
  return { ...admittedBase, ...money, energy, energyAddedKwh: energy?.batteryInputKwh ?? null, energyUsedKwh: energy?.inputEnergyKwh ?? null,
    chargeMode: energy?.chargeMode ?? incoming.chargeMode ?? admittedBase.chargeMode }
}
