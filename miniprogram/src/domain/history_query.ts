import type { WechatStorage } from '../services/session'
import { isHistoryQuarantined } from './history'

export type HistoryQuery = { startDate: string; endDate: string; minDistanceKm: number; maxDistanceKm?: number }
export const emptyHistoryQuery = (): HistoryQuery => ({ startDate: '', endDate: '', minDistanceKm: 0 })

function civilFromDate(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

/** Android parity: DrivesViewModel defaults to LAST_7_DAYS (7 days inclusive) */
export function defaultDriveHistoryQuery(now = new Date()): HistoryQuery {
  const endDate = civilFromDate(now)
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 6)
  return { startDate: civilFromDate(start), endDate, minDistanceKm: 0 }
}

/** Android parity: ChargesViewModel defaults to LAST_30_DAYS (30 days inclusive) */
export function defaultChargeHistoryQuery(now = new Date()): HistoryQuery {
  const endDate = civilFromDate(now)
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 29)
  return { startDate: civilFromDate(start), endDate, minDistanceKm: 0 }
}

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
  const maxDistanceKm = typeof row.maxDistanceKm === 'number' && Number.isFinite(row.maxDistanceKm) && row.maxDistanceKm > minDistanceKm && row.maxDistanceKm <= 10000 ? row.maxDistanceKm : undefined
  return { startDate, endDate, minDistanceKm, ...(maxDistanceKm === undefined ? {} : { maxDistanceKm }) }
}
/** A newly chosen endpoint wins if it crosses the other endpoint. */
export function selectHistoryDate(query: HistoryQuery, field: 'startDate' | 'endDate', value: string): HistoryQuery {
  const next = { ...query, [field]: civilDate(value) }
  if (next.startDate && next.endDate && next.startDate > next.endDate) {
    if (field === 'startDate') next.endDate = next.startDate
    else next.startDate = next.endDate
  }
  return normalizeHistoryQuery(next)
}
export function readHistoryQuery(storage: Pick<WechatStorage, 'getStorageSync'>, scope: string, fallback: HistoryQuery = emptyHistoryQuery()): HistoryQuery {
  try {
    const raw = storage.getStorageSync(`${scope}.query`)
    if (raw === undefined || raw === null || raw === '') return fallback
    return normalizeHistoryQuery(raw)
  } catch { return fallback }
}
export function writeHistoryQuery(storage: Pick<WechatStorage, 'setStorageSync'>, scope: string, query: HistoryQuery): boolean {
  try { storage.setStorageSync(`${scope}.query`, normalizeHistoryQuery(query)); return true } catch { return false }
}
export function historyDateTime(value: string | null | undefined): string {
  const date = typeof value === 'string' && value ? new Date(value) : null
  if (!date || !Number.isFinite(date.getTime())) return '暂无日期'
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}
/** Compact timestamp used inside App detail cards and chart cursors. */
export function historyDetailDateTime(value: string | null | undefined): string {
  const date = typeof value === 'string' && value ? new Date(value) : null
  if (!date || !Number.isFinite(date.getTime())) return '暂无日期'
  const pad = (number: number) => String(number).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
/** Android formatEditorialDate: the card date follows the device's local calendar. */
export function historyEditorialDate(value: string | null | undefined, is24Hour = true): string {
  const date = typeof value === 'string' && value ? new Date(value) : null
  if (!date || !Number.isFinite(date.getTime())) return '暂无日期'
  const pad = (number: number) => String(number).padStart(2, '0')
  const day = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][date.getDay()]
  const time = is24Hour ? `${pad(date.getHours())}:${pad(date.getMinutes())}`
    : `${pad(date.getHours() % 12 || 12)}:${pad(date.getMinutes())} ${date.getHours() < 12 ? '上午' : '下午'}`
  return `${day} · ${date.getMonth() + 1}月${date.getDate()}日 · ${time}`
}
export function filterHistory<T extends { startDate?: string | null; distanceKm?: number | null }>(rows: readonly T[], query: HistoryQuery): T[] {
  return rows.filter(row => {
    if (isHistoryQuarantined(row)) return false
    if (query.minDistanceKm > 0 && (row.distanceKm == null || !Number.isFinite(row.distanceKm) || row.distanceKm < query.minDistanceKm)) return false
    if (query.maxDistanceKm != null && (row.distanceKm == null || !Number.isFinite(row.distanceKm) || row.distanceKm < 0 || row.distanceKm >= query.maxDistanceKm)) return false
    if (!query.startDate && !query.endDate) return true
    const date = typeof row.startDate === 'string' && row.startDate ? new Date(row.startDate) : null
    if (!date || !Number.isFinite(date.getTime())) return false
    const day = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
    return (!query.startDate || day >= query.startDate) && (!query.endDate || day <= query.endDate)
  })
}
