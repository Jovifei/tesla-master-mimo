import type { Charge } from './types'
import type { WechatStorage } from './session'
import { cacheBytes, canStoreCache } from './cache_budget'
import { resolveChargeEnergy, sameEnergyInstant } from '../domain/drive_energy'
import { readTariff } from '../domain/tariff'

export type ChargeCostScope = { userId: string; origin: string; stableId: string }
export type ChargeCostOverride = { identity: string; source: string; sourceInstanceId?: string | null; sourceVehicleId?: string | null; sourceRecordId?: string | null; startDate?: string | null; endDate?: string | null; amount: number; currency: string; updatedAt: string }
export type ChargeEstimatePrice = { amount: number; currency: string; source: 'app_default' | 'saved_default' }
export type CostedCharge = Charge & { costProvenance: 'manual' | 'free' | 'actual' | 'estimate' | 'unknown'; reportedCost: number | null; reportedCurrency: string | null; manualCostUpdatedAt: string | null;
  estimatedUnitPrice: number | null; estimatePriceSource: ChargeEstimatePrice['source'] | null }
export const CHARGE_COST_MAX_ROWS = 200, CHARGE_COST_MAX_BYTES = 64 * 1024
export const APP_DEFAULT_CHARGE_PRICE_CNY = 1.14
const text = (value: unknown, max = 256): value is string => typeof value === 'string' && value.trim().length > 0 && value.length <= max && !/[\u0000-\u001f\u007f]/.test(value)
const currencyCode = (value: unknown): string | null => typeof value === 'string' && /^[A-Z]{3}$/.test(value.trim().toUpperCase()) ? value.trim().toUpperCase() : null
const encode = (value: string) => encodeURIComponent(value).replace(/\./g, '%2E')
const finite = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value)
export function chargeCostKey(scope: ChargeCostScope): string | null {
  return text(scope.userId) && text(scope.stableId) && /^https:\/\/[a-z0-9.-]+(?::\d{1,5})?$/i.test(scope.origin)
    ? `matelink.charge-cost.v1.${[scope.origin, scope.userId, scope.stableId].map(encode).join('.')}` : null
}
type ChargeCostIdentity = Pick<ChargeCostOverride, 'identity' | 'source' | 'sourceInstanceId' | 'sourceVehicleId' | 'sourceRecordId' | 'startDate' | 'endDate'>
function validWindow(start: unknown, end: unknown): start is string {
  return typeof start === 'string' && typeof end === 'string' &&
    /(?:Z|[+-]\d{2}:\d{2})$/i.test(start) && /(?:Z|[+-]\d{2}:\d{2})$/i.test(end) &&
    sameEnergyInstant(start, start) && sameEnergyInstant(end, end) && Date.parse(end) > Date.parse(start)
}
function identity(charge: Charge): ChargeCostIdentity | null {
  if (!text(charge.source, 128) || charge.qualityState === 'quarantined') return null
  if (!validWindow(charge.startDate, charge.endDate)) return null
  if ([charge.sourceInstanceId, charge.sourceVehicleId, charge.sourceRecordId].some(value => value != null && !text(value, 256))) return null
  const key = text(charge.sessionId) ? `session:${charge.sessionId}` : text(charge.id) ? `id:${charge.id}` : null
  return key ? { identity: key, source: charge.source, sourceInstanceId: charge.sourceInstanceId?.trim() ?? null,
    sourceVehicleId: charge.sourceVehicleId?.trim() ?? null, sourceRecordId: charge.sourceRecordId?.trim() ?? null,
    startDate: charge.startDate, endDate: charge.endDate } : null
}
function validRow(value: unknown): value is ChargeCostOverride {
  if (!value || typeof value !== 'object') return false
  const row = value as Partial<ChargeCostOverride>
  return text(row.identity, 264) && /^(session|id):.+/.test(row.identity) && text(row.source, 128) &&
    [row.sourceInstanceId, row.sourceVehicleId, row.sourceRecordId].every(value => value == null || text(value, 256)) && finite(row.amount) && row.amount >= 0 &&
    currencyCode(row.currency) === row.currency && typeof row.updatedAt === 'string' && Number.isFinite(Date.parse(row.updatedAt)) &&
    (row.startDate == null && row.endDate == null || validWindow(row.startDate, row.endDate))
}
function rowKey(row: ChargeCostIdentity): string { return JSON.stringify([row.source, row.identity, row.sourceInstanceId ?? null, row.sourceVehicleId ?? null, row.sourceRecordId ?? null]) }
function sameBoundWindow(a: ChargeCostIdentity, b: ChargeCostIdentity): boolean {
  return rowKey(a) === rowKey(b) && validWindow(a.startDate, a.endDate) && validWindow(b.startDate, b.endDate) &&
    sameEnergyInstant(a.startDate, b.startDate) && sameEnergyInstant(a.endDate, b.endDate)
}
function sameStoredRow(a: ChargeCostIdentity, b: ChargeCostIdentity): boolean {
  return sameBoundWindow(a, b) || (rowKey(a) === rowKey(b) && a.startDate == null && a.endDate == null && b.startDate == null && b.endDate == null)
}
type CostArchive = { schema: 1; scope: string; rows: ChargeCostOverride[] }
/** Null means unreadable/ambiguous. Never replace that archive with a newly edited subset. */
function archive(storage: Pick<WechatStorage, 'getStorageSync'>, key: string): CostArchive | null {
  try {
    const raw = storage.getStorageSync(key)
    if (raw == null || raw === '') return { schema: 1, scope: key, rows: [] }
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
    const value = raw as Partial<CostArchive>
    if (value.schema !== 1 || value.scope !== key || !Array.isArray(value.rows) || value.rows.length > CHARGE_COST_MAX_ROWS ||
      cacheBytes(raw) > CHARGE_COST_MAX_BYTES || !value.rows.every(validRow) || value.rows.some((row, i) => value.rows!.slice(0, i).some(old => sameStoredRow(row, old)))) return null
    return { schema: 1, scope: key, rows: value.rows.map(row => ({ ...row })) }
  } catch { return null }
}
export function readChargeCostOverride(storage: Pick<WechatStorage, 'getStorageSync'>, scope: ChargeCostScope, charge: Charge): ChargeCostOverride | null {
  const key = chargeCostKey(scope), target = identity(charge)
  if (!key || !target) return null
  return archive(storage, key)?.rows.find(row => sameBoundWindow(row, target)) ?? null
}
/** All history consumers use the same account-scoped default estimate. */
export function chargeEstimatePrice(storage: Pick<WechatStorage, 'getStorageSync'>, scope: ChargeCostScope): ChargeEstimatePrice {
  const saved = readTariff(storage, scope.userId, scope.origin)
  return saved?.defaultPrice != null
    ? { amount: saved.defaultPrice, currency: saved.currency, source: 'saved_default' }
    : { amount: APP_DEFAULT_CHARGE_PRICE_CNY, currency: 'CNY', source: 'app_default' }
}
/** The App's default CNY price estimates a missing bill from admitted battery input, never AC input. */
export function estimateAppDefaultChargeCost(charge: Charge, price = APP_DEFAULT_CHARGE_PRICE_CNY, currency = 'CNY'): number | null {
  const code = currencyCode(currency), reportedCode = currencyCode(charge.currencyCode ?? charge.currency)
  if (charge.qualityState === 'quarantined' || !finite(price) || price < 0 || !code ||
    (reportedCode !== null && reportedCode !== code) ||
    ((charge.currencyCode === null || charge.currency === null) && ('currency' in charge || 'currencyCode' in charge))) return null
  const battery = charge.energy?.evidence
    ? resolveChargeEnergy({ source: charge.source, start_date: charge.startDate, end_date: charge.endDate, energy_contract: charge.energy.evidence }).batteryInputKwh
    : charge.source === 'teslamate_archive' && charge.energy?.quality === 'reported' ? charge.energy.batteryInputKwh : null
  return finite(battery) && battery >= 0 ? battery * price : null
}
/** Only a bound manual override, a source bill or an explicitly labelled estimate changes the view. */
export function overlayChargeCosts(storage: Pick<WechatStorage, 'getStorageSync'>, scope: ChargeCostScope, charges: readonly Charge[], estimatePrice: ChargeEstimatePrice | null = null, _freeSupercharging: boolean | null = null): CostedCharge[] {
  const key = chargeCostKey(scope), rows = key ? archive(storage, key)?.rows ?? [] : []
  return charges.map(charge => {
    const target = identity(charge), manual = target ? rows.find(row => sameBoundWindow(row, target)) : null
    const reportedCost = finite(charge.cost) ? charge.cost : null, reportedCurrency = currencyCode(charge.currencyCode ?? charge.currency)
    // The App's Charges screen treats TeslaMate zero as an unfilled price before estimating.
    const blankTeslaMatePrice = charge.source === 'teslamate_archive' && reportedCost === 0
    const actualCost = reportedCost !== null && reportedCost >= 0 && !blankTeslaMatePrice ? reportedCost : null
    const estimate = manual || actualCost != null || !estimatePrice ? null : estimateAppDefaultChargeCost(charge, estimatePrice.amount, estimatePrice.currency)
    return { ...charge, reportedCost, reportedCurrency, manualCostUpdatedAt: manual?.updatedAt ?? null,
      cost: manual ? manual.amount : actualCost ?? estimate, currency: manual?.currency ?? (estimate != null ? estimatePrice!.currency : charge.currency), currencyCode: manual?.currency ?? (estimate != null ? estimatePrice!.currency : charge.currencyCode),
      costProvenance: manual ? 'manual' : actualCost != null ? 'actual' : estimate != null ? 'estimate' : 'unknown',
      estimatedUnitPrice: estimate != null ? estimatePrice!.amount : null, estimatePriceSource: estimate != null ? estimatePrice!.source : null }
  })
}
function store(storage: WechatStorage, key: string, value: CostArchive, isCurrent: () => boolean): boolean {
  if (!isCurrent() || value.rows.length > CHARGE_COST_MAX_ROWS || cacheBytes(value) > CHARGE_COST_MAX_BYTES || !canStoreCache(storage, key, value) || !isCurrent()) return false
  try { storage.setStorageSync(key, value); return true } catch { return false }
}
export function writeChargeCostOverride(storage: WechatStorage, scope: ChargeCostScope, charge: Charge, amount: number, currency: string, isCurrent: () => boolean, now = Date.now()): boolean {
  const key = chargeCostKey(scope), target = identity(charge), code = currencyCode(currency)
  if (!key || !target || !code || !finite(amount) || amount < 0 || !Number.isFinite(now) || now <= 0 || !isCurrent()) return false
  const existing = archive(storage, key)
  if (!existing) return false
  const row: ChargeCostOverride = { ...target, amount, currency: code, updatedAt: new Date(now).toISOString() }
  return store(storage, key, { schema: 1, scope: key, rows: [row, ...existing.rows.filter(item => !sameBoundWindow(item, target))] }, isCurrent)
}
export function clearChargeCostOverride(storage: WechatStorage, scope: ChargeCostScope, charge: Charge, isCurrent: () => boolean): boolean {
  const key = chargeCostKey(scope), target = identity(charge)
  if (!key || !target || !isCurrent()) return false
  const existing = archive(storage, key)
  if (!existing) return false
  return store(storage, key, { schema: 1, scope: key, rows: existing.rows.filter(row => !sameBoundWindow(row, target)) }, isCurrent)
}
