import type { Charge } from '../services/types'
import type { WechatStorage } from '../services/session'
import { resolveChargeEnergy } from './drive_energy'
export type Tariff = { currency: string; peak: number; flat: number; valley: number; timeZone?: string; enabled?: boolean; defaultPrice?: number; peakHours?: number[][]; flatHours?: number[][]; valleyHours?: number[][] }
export const DEFAULT_TARIFF_PERIODS = { peak: '10-15,18-21', flat: '7-10,15-18,21-23', valley: '0-7,23-24' }
export function parseTariffPeriods(value: string): number[][] | null {
  if (!value.trim()) return []
  const ranges: number[][] = []
  for (const item of value.split(',')) {
    const match = item.trim().match(/^(\d{1,2})\s*-\s*(\d{1,2})$/)
    if (!match) return null
    const start = Number(match[1]), end = Number(match[2])
    if (start < 0 || start >= end || end > 24) return null
    ranges.push([start, end])
  }
  return ranges.length <= 24 ? ranges : null
}
export function validTariffPeriods(peak: number[][], flat: number[][], valley: number[][]): boolean {
  const hours = Array(24).fill(0) as number[]
  for (const ranges of [peak, flat, valley]) for (const range of ranges) {
    if (range.length !== 2 || range.some(number => !Number.isInteger(number)) || range[0] < 0 || range[1] > 24 || range[0] >= range[1]) return false
    for (let hour = range[0]; hour < range[1]; hour++) hours[hour]++
  }
  return hours.every(count => count === 1)
}
export function tariffKey(user: string, origin: string): string {
  const encode = (text: string) => encodeURIComponent(text).replace(/\./g, '%2E')
  return `matelink.tariff.v1.${encode(origin)}.${encode(user)}`
}
export function readTariff(storage: Pick<WechatStorage, 'getStorageSync'>, user: string, origin: string): Tariff | null {
  if (!user || !origin.startsWith('https://')) return null
  try {
    const value = storage.getStorageSync(tariffKey(user, origin)) as Tariff | undefined
    if (!value || 'schema' in value || !/^[A-Z]{3}$/.test(value.currency) || [value.peak, value.flat, value.valley].some(number => typeof number !== 'number' || !Number.isFinite(number) || number < 0 || number > 100)) return null
    const profile: Tariff = { currency: value.currency, peak: value.peak, flat: value.flat, valley: value.valley, ...(typeof value.timeZone === 'string' ? { timeZone: value.timeZone } : {}) }
    if (typeof value.enabled === 'boolean') profile.enabled = value.enabled
    if (typeof value.defaultPrice === 'number' && Number.isFinite(value.defaultPrice) && value.defaultPrice >= 0 && value.defaultPrice <= 100) profile.defaultPrice = value.defaultPrice
    if ([value.peakHours, value.flatHours, value.valleyHours].some(ranges => ranges !== undefined)) {
      if (![value.peakHours, value.flatHours, value.valleyHours].every(Array.isArray) || !validTariffPeriods(value.peakHours!, value.flatHours!, value.valleyHours!)) return null
      profile.peakHours = value.peakHours; profile.flatHours = value.flatHours; profile.valleyHours = value.valleyHours
    }
    return profile
  } catch { return null }
}
/** Uniform input energy over an observed session is an explicit estimate, never an actual bill. */
export function estimateTariffCost(charge: Charge, tariff: Tariff): number | null {
  if ((charge.chargeModeConflict != null && charge.chargeModeConflict !== false) || !charge.startDate || !charge.endDate || !charge.energy?.evidence || !tariff.timeZone || !/^[A-Z]{3}$/.test(tariff.currency) ||
    [tariff.peak, tariff.flat, tariff.valley].some(value => !Number.isFinite(value) || value < 0)) return null
  const energy = resolveChargeEnergy({ source: charge.source, start_date: charge.startDate, end_date: charge.endDate, energy_contract: charge.energy.evidence }).acInputKwh
  if (energy == null || !Number.isFinite(energy) || energy < 0) return null
  const start = Date.parse(charge.startDate), end = Date.parse(charge.endDate), duration = end - start
  if (!Number.isFinite(duration) || duration <= 0 || duration > 30 * 24 * 60 * 60 * 1000) return null
  if (tariff.enabled === false) return tariff.defaultPrice != null && Number.isFinite(tariff.defaultPrice) && tariff.defaultPrice >= 0 ? energy * tariff.defaultPrice : null
  if (tariff.peakHours || tariff.flatHours || tariff.valleyHours) {
    if (!tariff.peakHours || !tariff.flatHours || !tariff.valleyHours || !validTariffPeriods(tariff.peakHours, tariff.flatHours, tariff.valleyHours)) return null
  }
  let formatter: Intl.DateTimeFormat
  try { formatter = new Intl.DateTimeFormat('en', { timeZone: tariff.timeZone, hour: 'numeric', hourCycle: 'h23' }); formatter.formatToParts(start) } catch { return null }
  let total = 0
  for (let time = start; time < end;) {
    const date = new Date(time), hour = Number(formatter.formatToParts(date).find(part => part.type === 'hour')?.value)
    if (!Number.isFinite(hour)) return null
    const next = Math.min(end, time + (60 - date.getSeconds()) * 1000 - date.getMilliseconds())
    const includes = (ranges: number[][]) => ranges.some(([start, end]) => hour >= start && hour < end)
    const peak = tariff.peakHours ? includes(tariff.peakHours) : hour >= 10 && hour < 15 || hour >= 18 && hour < 21
    const flat = tariff.flatHours ? includes(tariff.flatHours) : hour >= 7 && hour < 23
    const price = peak ? tariff.peak : flat ? tariff.flat : tariff.valley
    total += energy * (next - time) / duration * price
    time = next
  }
  return Number.isFinite(total) ? total : null
}
