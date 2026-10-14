import type { WechatStorage } from '../services/session'

export type HistoryQuery = { startDate: string; endDate: string; minDistanceKm: number }
export const emptyHistoryQuery = (): HistoryQuery => ({ startDate: '', endDate: '', minDistanceKm: 0 })
function civilDate(value: unknown): string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return ''
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value ? value : ''
}
export function normalizeHistoryQuery(value: unknown): HistoryQuery {
  const row = value && typeof value === 'object' ? value as Partial<HistoryQuery> : {}
  let startDate = civilDate(row.startDate), endDate = civilDate(row.endDate)
  if (startDate && endDate && startDate > endDate) [startDate, endDate] = [endDate, startDate]
  const minDistanceKm = typeof row.minDistanceKm === 'number' && Number.isFinite(row.minDistanceKm) && row.minDistanceKm >= 0 && row.minDistanceKm <= 10000 ? row.minDistanceKm : 0
  return { startDate, endDate, minDistanceKm }
}
export function readHistoryQuery(storage: Pick<WechatStorage, 'getStorageSync'>, scope: string): HistoryQuery {
  try { return normalizeHistoryQuery(storage.getStorageSync(`${scope}.query`)) } catch { return emptyHistoryQuery() }
}
export function writeHistoryQuery(storage: Pick<WechatStorage, 'setStorageSync'>, scope: string, query: HistoryQuery): boolean {
  try { storage.setStorageSync(`${scope}.query`, normalizeHistoryQuery(query)); return true } catch { return false }
}
export function historyDateTime(value: string | null | undefined): string {
  const date = typeof value === 'string' && value ? new Date(value) : null
  if (!date || !Number.isFinite(date.getTime())) return '暂无日期'
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}
export function filterHistory<T extends { startDate?: string | null; distanceKm?: number | null }>(rows: readonly T[], query: HistoryQuery): T[] {
  return rows.filter(row => {
    if (query.minDistanceKm > 0 && (row.distanceKm == null || !Number.isFinite(row.distanceKm) || row.distanceKm < query.minDistanceKm)) return false
    if (!query.startDate && !query.endDate) return true
    const date = typeof row.startDate === 'string' && row.startDate ? new Date(row.startDate) : null
    if (!date || !Number.isFinite(date.getTime())) return false
    const day = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
    return (!query.startDate || day >= query.startDate) && (!query.endDate || day <= query.endDate)
  })
}
