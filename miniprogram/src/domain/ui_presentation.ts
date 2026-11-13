import { mixSrgbInOklab } from './charge_palette'
/** Presentation-only policy. Does not fetch, infer records, or change history. */
export type Palette = 'neutral' | 'cream'
export type DayAxis = 'horizontal' | 'vertical'
export type Appearance = { palette: Palette; reducedMotion: boolean; dayAxis: DayAxis }
export function normalizeAppearance(value: unknown): Appearance {
  const v = value && typeof value === 'object' ? value as Record<string, unknown> : {}
  return { palette: v.palette === 'cream' ? 'cream' : 'neutral', reducedMotion: v.reducedMotion === true, dayAxis: v.dayAxis === 'vertical' ? 'vertical' : 'horizontal' }
}
export function verticalDayTarget(index: number, count: number, dx: number, dy: number): number {
  return daySwipeTarget(index, count, dx, dy, 'vertical')
}
export function daySwipeTarget(index: number, count: number, dx: number, dy: number, axis: DayAxis = 'horizontal'): number {
  if (!Number.isInteger(count) || count <= 0) return 0
  const current = Math.max(0, Math.min(count - 1, Number.isInteger(index) ? index : 0))
  if (!Number.isFinite(dx) || !Number.isFinite(dy)) return current
  const main = axis === 'horizontal' ? dx : dy, cross = axis === 'horizontal' ? dy : dx
  if (Math.abs(main) < 48 || Math.abs(main) <= Math.abs(cross) * 1.2) return current
  return Math.max(0, Math.min(count - 1, current + (main < 0 ? 1 : -1)))
}
export function civilDay(value: string | null | undefined): string | null {
  if (!value) return null
  // A civil date is not UTC midnight: keep it on the same day in every timezone.
  const date = /^(\d{4})-(\d{2})-(\d{2})(?:$|T)/.exec(value)
  if (date) {
    const check = new Date(`${date[1]}-${date[2]}-${date[3]}T12:00:00Z`)
    if (!Number.isFinite(check.getTime()) || check.getUTCFullYear() !== Number(date[1]) ||
        check.getUTCMonth() + 1 !== Number(date[2]) || check.getUTCDate() !== Number(date[3])) return null
    if (value.length === 10) return value
  }
  const d = new Date(value)
  if (!Number.isFinite(d.getTime())) return null
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
export type DayGroup<T> = { key: string; label: string; items: T[] }
export function historyDayGroups<T>(items: readonly T[], dateOf: (item: T) => string | null | undefined): DayGroup<T>[] {
  const groups = new Map<string, T[]>()
  for (const item of items) {
    const key = civilDay(dateOf(item)) ?? 'unknown'
    const list = groups.get(key) ?? []; list.push(item); groups.set(key, list)
  }
  return [...groups].sort(([a], [b]) => a === 'unknown' ? 1 : b === 'unknown' ? -1 : b.localeCompare(a))
    .map(([key, rows]) => ({ key, label: key === 'unknown' ? '日期待确认' : key, items: rows }))
}
export function dayCaption(key: string, now = new Date()): string {
  if (key === 'unknown') return '日期待确认'
  for (let n = 0; n < 3; n++) {
    const day = new Date(now.getFullYear(), now.getMonth(), now.getDate() - n)
    if (civilDay(day.toISOString()) === key) return `${['今天', '昨天', '前天'][n]} · ${key}`
  }
  return key
}
export function activityLevel(value: number | null, max: number): number {
  if (value == null || !Number.isFinite(value) || value <= 0 || !Number.isFinite(max) || max <= 0) return 0
  return Math.max(1, Math.min(4, Math.ceil(value / max * 4)))
}

/** Empty calendar slots are presentation, never manufactured history or zero-energy records. */
export function calendarHistoryGroups<T>(groups: DayGroup<T>[], start: string, end: string): DayGroup<T>[] {
  if (civilDay(start) !== start || civilDay(end) !== end || !start || !end || start > end) return groups
  const first = Date.parse(`${start}T12:00:00Z`), last = Date.parse(`${end}T12:00:00Z`)
  const days = Math.round((last - first) / 86400000) + 1
  if (days < 1 || days > 366) return groups
  const byDate = new Map(groups.map(group => [group.key, group]))
  const calendar = Array.from({ length: days }, (_, index) => {
    const key = new Date(last - index * 86400000).toISOString().slice(0, 10)
    return byDate.get(key) ?? { key, label: key, items: [] as T[] }
  })
  return [...calendar, ...groups.filter(group => group.key === 'unknown' || group.key < start || group.key > end)]
}

/** Matches Android ChargeActivityGrid: surfaceVariant -> primary, 60 kWh at 80% mix. */
export function chargeCellPaint(value: number | null, palette: Palette = 'neutral', dark = false): { background: string; foreground: string } {
  const ratio = value != null && Number.isFinite(value) && value > 0 ? Math.min(value / 60, 1) * 0.8 : 0
  const from = dark ? [36,42,49] : palette === 'cream' ? [245,243,237] : [243,243,245]
  const to = dark ? [234,234,236] : [29,29,31]
  const rgb = mixSrgbInOklab(from, to, ratio)
  const linear = rgb.map(v => { const x=v/255; return x<=0.04045?x/12.92:((x+0.055)/1.055)**2.4 })
  const luminance = linear[0]*.2126+linear[1]*.7152+linear[2]*.0722
  return { background: '#'+rgb.map(v=>v.toString(16).padStart(2,'0')).join(''), foreground: luminance>.179?'#000000':'#ffffff' }
}
