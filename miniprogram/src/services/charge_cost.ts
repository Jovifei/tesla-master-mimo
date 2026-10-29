import type { Charge } from './types'
import type { WechatStorage } from './session'
import { cacheBytes, canStoreCache } from './cache_budget'
import { resolveChargeEnergy } from '../domain/drive_energy'

export type ChargeCostScope = { userId: string; origin: string; stableId: string }
export type ChargeCostOverride = { identity: string; source: string; amount: number; currency: string; updatedAt: string }
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
function identity(charge: Charge): { identity: string; source: string } | null {
  if (!text(charge.source, 128) || charge.qualityState === 'quarantined') return null
  const key = text(charge.sessionId) ? `session:${charge.sessionId}` : text(charge.id) ? `id:${charge.id}` : null
  return key ? { identity: key, source: charge.source } : null
}
function validRow(value: unknown): value is ChargeCostOverride {
  if (!value || typeof value !== 'object') return false
  const row = value as Partial<ChargeCostOverride>
  return text(row.identity, 264) && /^(session|id):.+/.test(row.identity) && text(row.source, 128) && finite(row.amount) && row.amount >= 0 &&
    currencyCode(row.currency) === row.currency && typeof row.updatedAt === 'string' && Number.isFinite(Date.parse(row.updatedAt))
}
function rowKey(row: Pick<ChargeCostOverride, 'source' | 'identity'>): string { return JSON.stringify([row.source, row.identity]) }
type CostArchive = { schema: 1; scope: string; rows: ChargeCostOverride[] }
/** Null means unreadable/ambiguous. Never replace that archive with a newly edited subset. */
function archive(storage: Pick<WechatStorage, 'getStorageSync'>, key: string): CostArchive | null {
  try {
    const raw = storage.getStorageSync(key)
    if (raw == null || raw === '') return { schema: 1, scope: key, rows: [] }
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
    const value = raw as Partial<CostArchive>
    if (value.schema !== 1 || value.scope !== key || !Array.isArray(value.rows) || value.rows.length > CHARGE_COST_MAX_ROWS ||
      cacheBytes(raw) > CHARGE_COST_MAX_BYTES || !value.rows.every(validRow) || new Set(value.rows.map(rowKey)).size !== value.rows.length) return null
    return { schema: 1, scope: key, rows: value.rows.map(row => ({ identity: row.identity, source: row.source, amount: row.amount, currency: row.currency, updatedAt: row.updatedAt })) }
  } catch { return null }
}
export function readChargeCostOverride(storage: Pick<WechatStorage, 'getStorageSync'>, scope: ChargeCostScope, charge: Charge): ChargeCostOverride | null {
  const key = chargeCostKey(scope), target = identity(charge)
  if (!key || !target) return null
  return archive(storage, key)?.rows.find(row => rowKey(row) === rowKey(target)) ?? null
}
/** The App's default CNY price estimates a missing bill from admitted battery input, never AC input. */
export function estimateAppDefaultChargeCost(charge: Charge, price = APP_DEFAULT_CHARGE_PRICE_CNY, currency = 'CNY'): number | null {
  const code = currencyCode(currency), reportedCode = currencyCode(charge.currencyCode ?? charge.currency)
  if (charge.qualityState === 'quarantined' || !finite(price) || price < 0 || !code ||
    (reportedCode !== null && reportedCode !== code)) return null
  const battery = charge.energy?.evidence
    ? resolveChargeEnergy({ source: charge.source, start_date: charge.startDate, end_date: charge.endDate, energy_contract: charge.energy.evidence }).batteryInputKwh
    : charge.source === 'teslamate_archive' && charge.energy?.quality === 'reported' ? charge.energy.batteryInputKwh : null
  return finite(battery) && battery >= 0 ? battery * price : null
}
/** Copies only the cost view. Energy, source, quality, receipt and original upstream bill stay unchanged.
 * Priority mirrors the App EffectiveChargeCostResolver: manual → free (free
 * supercharging on DC) → actual upstream bill → estimate → unknown. */
export function overlayChargeCosts(storage: Pick<WechatStorage, 'getStorageSync'>, scope: ChargeCostScope, charges: readonly Charge[], estimatePrice: ChargeEstimatePrice | null = null, freeSupercharging: boolean | null = null): CostedCharge[] {
  const key = chargeCostKey(scope), rows = key ? archive(storage, key)?.rows ?? [] : [], byKey = new Map(rows.map(row => [rowKey(row), row]))
  return charges.map(charge => {
    const target = identity(charge), manual = target ? byKey.get(rowKey(target)) : null
    const reportedCost = finite(charge.cost) ? charge.cost : null, reportedCurrency = currencyCode(charge.currencyCode ?? charge.currency)
    // The App's Charges screen treats TeslaMate zero as an unfilled price before estimating.
    // Other consumers (such as Cost analysis) keep the raw reported zero when no estimate contract is requested.
    const blankTeslaMatePrice = estimatePrice != null && charge.source === 'teslamate_archive' && reportedCost === 0
    const actualCost = reportedCost !== null && reportedCost >= 0 && !blankTeslaMatePrice ? reportedCost : null
    const manuallyFree = freeSupercharging === true && charge.chargeMode === 'dc'
    const estimate = manual || manuallyFree || actualCost != null || !estimatePrice ? null : estimateAppDefaultChargeCost(charge, estimatePrice.amount, estimatePrice.currency)
    return { ...charge, reportedCost, reportedCurrency, manualCostUpdatedAt: manual?.updatedAt ?? null,
      cost: manual ? manual.amount : manuallyFree ? 0 : actualCost ?? estimate, currency: manual?.currency ?? (estimate != null ? estimatePrice!.currency : charge.currency), currencyCode: manual?.currency ?? (estimate != null ? estimatePrice!.currency : charge.currencyCode),
      costProvenance: manual ? 'manual' : manuallyFree ? 'free' : actualCost != null ? 'actual' : estimate != null ? 'estimate' : 'unknown',
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
  return store(storage, key, { schema: 1, scope: key, rows: [row, ...existing.rows.filter(item => rowKey(item) !== rowKey(target))] }, isCurrent)
}
export function clearChargeCostOverride(storage: WechatStorage, scope: ChargeCostScope, charge: Charge, isCurrent: () => boolean): boolean {
  const key = chargeCostKey(scope), target = identity(charge)
  if (!key || !target || !isCurrent()) return false
  const existing = archive(storage, key)
  if (!existing) return false
  return store(storage, key, { schema: 1, scope: key, rows: existing.rows.filter(row => rowKey(row) !== rowKey(target)) }, isCurrent)
}
