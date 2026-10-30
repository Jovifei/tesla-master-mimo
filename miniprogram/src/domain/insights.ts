import type { CarStatus, Charge, Drive, DriveEnergyReceipt } from '../services/types'
import type { WechatStorage } from '../services/session'
import { canStoreCache, cacheBytes } from '../services/cache_budget'
import { aggregateDriveHistory, historyRowKey, isHistoryQuarantined } from './history'
import { driveEnergyReceipt, resolveChargeEnergy, resolveDriveEnergy } from './drive_energy'

const number = (value: unknown, min = -Infinity, max = Infinity): number | null =>
  typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max ? value : null
const text = (value: unknown, max = 160): string | null => typeof value === 'string' && value.trim() && value.length <= max && !/[\u0000-\u001f\u007f]/.test(value) ? value : null
const record = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
export function explicitCurrency(value: unknown): string | null { return typeof value === 'string' && /^[A-Z]{3}$/.test(value.trim().toUpperCase()) ? value.trim().toUpperCase() : null }

/** Re-evaluate the actual parsed proof before each analysis, including persisted trip reload. */
export function insightDrive(drive: Drive): Drive {
  const receipt = drive.energyReceipt ?? { start_date: drive.startDate, end_date: drive.endDate, source: drive.source,
    odometer_details: { odometer_distance: drive.distanceKm }, energy_contract: { version: 1, net_energy: drive.energy?.evidence ?? null } }
  return { ...drive, energy: resolveDriveEnergy(receipt) }
}
export type CostGroup = { currency: string; cost: number | null; known: number; total: number; energyKwh: number | null; pairedCost: number | null; perKwh: number | null; average: number | null;
  months: { month: string; cost: number | null; known: number; ac: number | null; dc: number | null; unknownMode: number | null }[];
  locations: { address: string; cost: number | null; count: number }[] }
export function chargeCosts(charges: readonly Charge[]): { groups: CostGroup[]; unknownCurrency: number; missingCost: number; total: number } {
  const rows = charges.filter(row => !isHistoryQuarantined(row)), grouped = new Map<string, Charge[]>()
  let unknownCurrency = 0, missingCost = 0
  for (const row of rows) {
    if (number(row.cost) == null) { missingCost++; continue }
    const currency = explicitCurrency(row.currencyCode ?? row.currency)
    if (!currency) { unknownCurrency++; continue }
    grouped.set(currency, [...(grouped.get(currency) ?? []), row])
  }
  const groups = [...grouped].map(([currency, known]) => {
    let energyKwh: number | null = null, pairedCost: number | null = null
    for (const row of known) {
      const energy = row.energy?.evidence ? resolveChargeEnergy({ source: row.source, start_date: row.startDate, end_date: row.endDate, energy_contract: row.energy.evidence }) : null
      const battery = row.energy?.evidence ? energy?.batteryInputKwh :
        row.source === 'teslamate_archive' && row.energy?.quality === 'reported' ? row.energy.batteryInputKwh : null
      if (battery != null && battery > 0) { energyKwh = (energyKwh ?? 0) + battery; pairedCost = (pairedCost ?? 0) + row.cost! }
    }
    const monthRows = new Map<string, Charge[]>(), locations = new Map<string, Charge[]>()
    for (const row of known) {
      const at = row.startDate ? new Date(row.startDate) : null
      const month = at && Number.isFinite(at.getTime()) ? `${at.getFullYear()}-${String(at.getMonth() + 1).padStart(2, '0')}` : '日期未知'
      monthRows.set(month, [...(monthRows.get(month) ?? []), row])
      if (row.address?.trim()) locations.set(row.address, [...(locations.get(row.address) ?? []), row])
    }
    const sum = (rows: Charge[]) => rows.length ? number(rows.reduce((total, row) => total + row.cost!, 0)) : null
    const mode = (row: Charge) => row.chargeModeConflict != null && row.chargeModeConflict !== false ? null : row.chargeMode
    const cost = sum(known)
    energyKwh = number(energyKwh); pairedCost = number(pairedCost)
    return { currency, cost, known: known.length, total: rows.length, energyKwh, pairedCost,
      perKwh: energyKwh != null && energyKwh > 0 && pairedCost != null ? number(pairedCost / energyKwh) : null, average: cost != null ? number(cost / known.length) : null,
      months: [...monthRows].map(([month, values]) => ({ month, cost: sum(values), known: values.length,
        ac: sum(values.filter(row => mode(row) === 'ac')), dc: sum(values.filter(row => mode(row) === 'dc')), unknownMode: sum(values.filter(row => mode(row) !== 'ac' && mode(row) !== 'dc')) })).sort((a, b) => a.month.localeCompare(b.month)),
      locations: [...locations].map(([address, values]) => ({ address, cost: sum(values), count: values.length })).sort((a, b) => (b.cost ?? 0) - (a.cost ?? 0)).slice(0, 5) }
  }).sort((a, b) => a.currency.localeCompare(b.currency))
  return { groups, unknownCurrency, missingCost, total: rows.length }
}

export type CapacityEvidence = { currentCapacityKwh?: number | null; source?: string | null; observedAt?: string | null; qualityState?: string | null }
export function personalizedRange(drives: readonly Drive[], capacity: CapacityEvidence | null, status: CarStatus | null, now = Date.now()) {
  const tempBand = (v: number | null | undefined) => v == null || !Number.isFinite(v) ? null : v < 10 ? 'cold' : v <= 25 ? 'mild' : 'hot'
  const speedBand = (v: number | null | undefined) => v == null || !Number.isFinite(v) || v < 0 ? null : v < 50 ? 'low' : v <= 90 ? 'cruise' : 'high'
  const start = new Date(now); start.setHours(0, 0, 0, 0); start.setDate(start.getDate() - 90)
  const end = new Date(now); end.setHours(23, 59, 59, 999)
  const valid = drives.filter(row => !isHistoryQuarantined(row)).map(insightDrive).filter(row => {
    const at = row.startDate ? Date.parse(row.startDate) : NaN
    return at >= start.getTime() && at <= end.getTime() && (number(row.distanceKm, 0) ?? 0) > 0 && (number(row.energy?.energyKwh) ?? 0) > 0
  })
  const temperature = tempBand(status?.outsideTemp), speed = speedBand(status?.speed)
  const grouped = temperature && speed ? valid.filter(row => tempBand(row.outsideTempC) === temperature && speedBand(row.speedAvgKmh) === speed) : []
  const distance = (rows: Drive[]) => rows.reduce((total, row) => total + row.distanceKm!, 0)
  const selected = grouped.length >= 5 && distance(grouped) >= 100 ? grouped : valid.length >= 10 && distance(valid) >= 300 ? valid : []
  const source = selected.length ? selected === grouped ? 'grouped' : 'global' : 'unavailable'
  const km = distance(selected), energy = selected.reduce((total, row) => total + row.energy!.energyKwh!, 0)
  const efficiency = km > 0 ? number(100 * energy / km) : null
  const at = capacity?.observedAt ? Date.parse(capacity.observedAt) : NaN
  const usable = capacity?.source && ['observed', 'reported', 'derived'].includes(capacity.qualityState ?? '') && at > 0 && at <= now ? number(capacity.currentCapacityKwh, Number.MIN_VALUE) : null
  return { source, sampleCount: selected.length, candidateCount: valid.length, distanceKm: km, efficiencyKwh100Km: efficiency,
    rangeKm: efficiency != null && efficiency > 0 && usable != null ? number(100 * usable / efficiency, Number.MIN_VALUE) : null,
    capacityKwh: usable, confidencePercent: selected.length ? Math.min(95, Math.round(50 + Math.min(1, selected.length / 20) * 30 + Math.min(1, km / 1000) * 20)) : null,
    estimated: selected.filter(row => row.energy?.quality === 'estimated').length }
}
export function ratedRangeComparisons(drives: readonly Drive[]) {
  return drives.filter(row => !isHistoryQuarantined(row)).flatMap(row => {
    const start = number(row.startRatedRangeKm, 0), end = number(row.endRatedRangeKm, 0), distance = number(row.distanceKm, 0)
    if (start == null || end == null || start <= end || distance == null) return []
    const ratedDropKm = start - end
    const deviationPercent = number(Math.abs(ratedDropKm - distance) / ratedDropKm * 100)
    return deviationPercent == null ? [] : [{ drive: row, ratedDropKm, distanceKm: distance, deviationPercent }]
  })
}
export function rangeInfluences(drives: readonly Drive[]) {
  const rows = ratedRangeComparisons(drives)
  const average = (selected: typeof rows) => selected.length ? number(selected.reduce((sum, row) => sum + row.deviationPercent, 0) / selected.length) : null
  const month = (row: typeof rows[number]) => row.drive.startDate ? new Date(row.drive.startDate).getMonth() + 1 : NaN
  return { summer: average(rows.filter(row => [6, 7, 8].includes(month(row)))), winter: average(rows.filter(row => [12, 1, 2].includes(month(row)))),
    lowSpeed: average(rows.filter(row => number(row.drive.speedAvgKmh, 0) != null && row.drive.speedAvgKmh! <= 40)),
    highSpeed: average(rows.filter(row => number(row.drive.speedAvgKmh, 0) != null && row.drive.speedAvgKmh! > 40)) }
}

export type TripLeg = { kind: 'drive'; drive: Drive } | { kind: 'charge'; charge: Charge }
export type ManualTrip = { id: string; name: string; createdAt: string; legs: TripLeg[] }
export type TripScope = { userId: string; origin: string; stableId: string }
export const MANUAL_TRIP_MAX_COUNT = 20, MANUAL_TRIP_MAX_LEGS = 50, MANUAL_TRIP_MAX_BYTES = 64 * 1024
const encode = (value: string) => encodeURIComponent(value).replace(/\./g, '%2E')
export function manualTripKey(scope: TripScope): string | null {
  if (!text(scope.userId, 256) || !text(scope.stableId, 256) || !/^https:\/\/[a-z0-9.-]+(?::\d{1,5})?$/i.test(scope.origin)) return null
  return `matelink.manual-trips.v1.${[scope.origin, scope.userId, scope.stableId].map(encode).join('.')}`
}
export function tripLegKey(leg: TripLeg): string { return `${leg.kind}:${historyRowKey(leg.kind === 'drive' ? leg.drive : leg.charge)}` }
function projectDrive(value: unknown): Drive | null {
  const raw = record(value), id = text(raw.id, 256)
  if (!id || raw.qualityState === 'quarantined') return null
  const original = raw as unknown as Drive, proof = insightDrive(original).energy?.evidence
  const receipt: DriveEnergyReceipt = driveEnergyReceipt({ drive_id: id, start_date: text(raw.startDate), end_date: text(raw.endDate), source: text(raw.source),
    odometer_details: { odometer_distance: number(raw.distanceKm, 0) }, energy_contract: { version: 1, net_energy: proof ?? null } }, 'km')
  return insightDrive({ id, sessionId: text(raw.sessionId, 256), startDate: text(raw.startDate), endDate: text(raw.endDate),
    distanceKm: number(raw.distanceKm, 0), durationMinutes: number(raw.durationMinutes, 0), source: text(raw.source),
    sourceInstanceId: text(raw.sourceInstanceId, MANUAL_TRIP_MAX_BYTES), sourceVehicleId: text(raw.sourceVehicleId, MANUAL_TRIP_MAX_BYTES), sourceRecordId: text(raw.sourceRecordId, MANUAL_TRIP_MAX_BYTES), qualityState: text(raw.qualityState), qualityReason: null,
    startBatteryLevel: number(raw.startBatteryLevel, 0, 100), endBatteryLevel: number(raw.endBatteryLevel, 0, 100), energyConsumedKwh: null, energyReceipt: receipt } as Drive)
}
function projectCharge(value: unknown): Charge | null {
  const raw = record(value), id = text(raw.id, 256)
  if (!id || raw.qualityState === 'quarantined') return null
  const original = raw as unknown as Charge
  const source = text(raw.source), legacyEnergy = source === 'teslamate_archive' && original.energy?.quality === 'reported' && original.energy.evidence == null
  const modeConflict = raw.chargeModeConflict != null && raw.chargeModeConflict !== false
  const resolvedEnergy = resolveChargeEnergy({ source, start_date: text(raw.startDate), end_date: text(raw.endDate), energy_contract: original.energy?.evidence ?? null,
    ...(legacyEnergy ? { charge_energy_added: original.energy?.batteryInputKwh, charge_energy_used: original.energy?.inputEnergyKwh } : {}) })
  const energy = modeConflict ? { ...resolvedEnergy, chargeMode: null } : resolvedEnergy
  const suppliedCurrencies = [raw.currencyCode, raw.currency].filter(value => value != null).map(explicitCurrency)
  const currency = suppliedCurrencies.some(value => value === null) || new Set(suppliedCurrencies).size > 1 ? null :
    suppliedCurrencies[0] ?? (raw.currencyCode === null || raw.currency === null ? null : undefined)
  return { id, sessionId: text(raw.sessionId, 256), startDate: text(raw.startDate), endDate: text(raw.endDate), durationMinutes: number(raw.durationMinutes, 0),
    source: text(raw.source), sourceInstanceId: text(raw.sourceInstanceId, MANUAL_TRIP_MAX_BYTES), sourceVehicleId: text(raw.sourceVehicleId, MANUAL_TRIP_MAX_BYTES), sourceRecordId: text(raw.sourceRecordId, MANUAL_TRIP_MAX_BYTES), qualityState: text(raw.qualityState), qualityReason: null,
    energyAddedKwh: energy.batteryInputKwh, energyUsedKwh: energy.inputEnergyKwh, energy, cost: number(raw.cost), currencyCode: currency, currency,
    ...(raw.chargeModeConflict != null ? { chargeModeConflict: modeConflict } : {}),
    startBatteryLevel: number(raw.startBatteryLevel, 0, 100), endBatteryLevel: number(raw.endBatteryLevel, 0, 100), chargerPowerKw: number(raw.chargerPowerKw, 0), chargeMode: !modeConflict && (original.chargeMode === 'ac' || original.chargeMode === 'dc') ? original.chargeMode : null } as Charge
}
export function makeManualTrip(id: string, name: string, legs: TripLeg[], createdAt = new Date().toISOString()): ManualTrip | null {
  if (!text(id, 256) || !text(name, 80) || !Number.isFinite(Date.parse(createdAt)) || !legs.length || legs.length > MANUAL_TRIP_MAX_LEGS) return null
  const selected: TripLeg[] = [], seen = new Set<string>()
  for (const leg of legs) {
    if (!leg || typeof leg !== 'object') continue
    const raw = record(leg.kind === 'drive' ? leg.drive : leg.kind === 'charge' ? leg.charge : null)
    for (const field of ['source', 'sourceInstanceId', 'sourceVehicleId', 'sourceRecordId']) {
      const value = raw[field]
      if (value != null && !(typeof value === 'string' && !value.trim()) && !text(value, field === 'source' ? 160 : MANUAL_TRIP_MAX_BYTES)) return null
    }
    const projected = leg.kind === 'drive' ? projectDrive(leg.drive) : leg.kind === 'charge' ? projectCharge(leg.charge) : null
    if (!projected || !projected.startDate || !Number.isFinite(Date.parse(projected.startDate))) continue
    const item = (leg.kind === 'drive' ? { kind: 'drive', drive: projected } : { kind: 'charge', charge: projected }) as TripLeg
    const key = tripLegKey(item)
    if (!seen.has(key)) { seen.add(key); selected.push(item) }
  }
  if (!selected.some(leg => leg.kind === 'drive')) return null
  selected.sort((a, b) => Date.parse((a.kind === 'drive' ? a.drive : a.charge).startDate!) - Date.parse((b.kind === 'drive' ? b.drive : b.charge).startDate!))
  return { id, name: name.trim(), createdAt, legs: selected }
}
function restoreTrip(value: unknown): ManualTrip | null {
  const raw = record(value)
  return typeof raw.id === 'string' && typeof raw.name === 'string' && typeof raw.createdAt === 'string' && Array.isArray(raw.legs)
    ? makeManualTrip(raw.id, raw.name, raw.legs, raw.createdAt) : null
}
export function readManualTrips(storage: Pick<WechatStorage, 'getStorageSync'>, scope: TripScope): ManualTrip[] {
  const key = manualTripKey(scope)
  if (!key) return []
  try {
    const raw = record(storage.getStorageSync(key))
    if (raw.schema !== 1 || raw.scope !== key || !Array.isArray(raw.trips) || raw.trips.length > MANUAL_TRIP_MAX_COUNT || cacheBytes(raw) > MANUAL_TRIP_MAX_BYTES) return []
    return raw.trips.map(restoreTrip).filter((trip): trip is ManualTrip => trip != null).sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
  } catch { return [] }
}
/** Never replace an unreadable/ambiguous archive with a newly created subset. */
function writableTrips(storage: Pick<WechatStorage, 'getStorageSync'>, scope: TripScope, key: string): ManualTrip[] | null {
  try {
    const value = storage.getStorageSync(key)
    if (value == null || value === '') return []
    const raw = record(value), trips = readManualTrips(storage, scope)
    if (raw.schema !== 1 || raw.scope !== key || !Array.isArray(raw.trips) || trips.length !== raw.trips.length ||
      trips.length > MANUAL_TRIP_MAX_COUNT || new Set(trips.map(trip => trip.id)).size !== trips.length || cacheBytes(value) > MANUAL_TRIP_MAX_BYTES) return null
    return trips
  } catch { return null }
}
export function writeManualTrip(storage: WechatStorage, scope: TripScope, value: ManualTrip): boolean {
  const key = manualTripKey(scope), trip = restoreTrip(value)
  if (!key || !trip) return false
  const existing = writableTrips(storage, scope, key)
  if (!existing) return false
  const trips = [trip, ...existing.filter(row => row.id !== trip.id)]
  const envelope = { schema: 1, scope: key, trips }
  if (trips.length > MANUAL_TRIP_MAX_COUNT || cacheBytes(envelope) > MANUAL_TRIP_MAX_BYTES || !canStoreCache(storage, key, envelope)) return false
  try { storage.setStorageSync(key, envelope); return true } catch { return false }
}
export function removeManualTrip(storage: WechatStorage, scope: TripScope, id: string): boolean {
  const key = manualTripKey(scope)
  if (!key) return false
  const existing = writableTrips(storage, scope, key)
  if (!existing) return false
  const trips = existing.filter(trip => trip.id !== id), envelope = { schema: 1, scope: key, trips }
  if (!canStoreCache(storage, key, envelope)) return false
  try { storage.setStorageSync(key, envelope); return true } catch { return false }
}
export function manualTripSummary(trip: ManualTrip) {
  const drives = trip.legs.flatMap(leg => leg.kind === 'drive' ? [insightDrive(leg.drive)] : []), charges = trip.legs.flatMap(leg => leg.kind === 'charge' ? [leg.charge] : [])
  return { ...aggregateDriveHistory(drives), costs: chargeCosts(charges), driveCount: drives.length, chargeCount: charges.length }
}
