/** Presentation-only policy. Does not fetch, infer records, or change history. */
export type Palette = 'neutral' | 'cream'
export type Appearance = { palette: Palette; reducedMotion: boolean }
export function normalizeAppearance(value: unknown): Appearance {
  const v = value && typeof value === 'object' ? value as Record<string, unknown> : {}
  return { palette: v.palette === 'cream' ? 'cream' : 'neutral', reducedMotion: v.reducedMotion === true }
}
export function verticalDayTarget(index: number, count: number, dx: number, dy: number): number {
  if (!Number.isInteger(count) || count <= 0) return 0
  const current = Math.max(0, Math.min(count - 1, Number.isInteger(index) ? index : 0))
  if (!Number.isFinite(dx) || !Number.isFinite(dy)) return current
  if (Math.abs(dy) < 48 || Math.abs(dy) <= Math.abs(dx) * 1.2) return current
  return Math.max(0, Math.min(count - 1, current + (dy < 0 ? 1 : -1)))
}
export function civilDay(value: string | null | undefined): string | null {
  if (typeof value !== 'string' || !value) return null
  // A civil date is not UTC midnight: keep it on the same day in every timezone.
  const trimmed = value.trim()
  const datePart = trimmed.replace(/(?:T|\s)\d{1,2}:\d{2}.*$/i, '').trim()
  if (/^\d{4}(?:[-/]\d{1,2})?$/.test(datePart) || /^\d{1,2}[-/]\d{4}$/.test(datePart) ||
      /^(?:[a-z]+\s+\d{4}|\d{4}\s+[a-z]+)$/i.test(datePart)) return null
  const pureIsoDate = /^(\d{4})-(\d{2})-(\d{2})$/.exec(trimmed)
  const isoDate = pureIsoDate ?? /^([+-]\d{6}|\d{4})-(\d{2})-(\d{2})(?:$|[Tt])/.exec(value)
  const yearFirst = /^(\d{4,})[-/](\d+)[-/](\d+)(?=$|[TtZz\s])/.exec(trimmed)
  // Small leading numbers in legacy strings may mean a month rather than a year.
  const date = isoDate ?? (yearFirst && Number(yearFirst[1]) > 31 ? yearFirst : null)
  const monthFirst = /^(\d+)[-/](\d+)[-/](\d+)(?=$|[TtZz\s])/.exec(trimmed)
  const monthDayYear = monthFirst && Number(monthFirst[1]) >= 1 && Number(monthFirst[1]) <= 12 ? monthFirst : null
  const monthNames = ['january', 'february', 'march', 'april', 'may', 'june', 'july', 'august', 'september', 'october', 'november', 'december']
  const wordFirst = /^(?:(?:mon(?:day)?|tue(?:sday)?|wed(?:nesday)?|thu(?:rsday)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?),?\s+)?([a-z]+)\s+(\d{1,2}),?\s+(\d{4})(?=$|[\s,])/i.exec(trimmed)
  const dayFirst = /^(?:(?:mon(?:day)?|tue(?:sday)?|wed(?:nesday)?|thu(?:rsday)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?),?\s+)?(\d{1,2})\s+([a-z]+),?\s+(\d{4})(?=$|[\s,])/i.exec(trimmed)
  const monthWord = (wordFirst?.[1] ?? dayFirst?.[2])?.toLowerCase()
  const wordMonth = monthWord ? monthNames.findIndex(name => name === monthWord || name.slice(0, 3) === monthWord) + 1 : 0
  const wordCalendar = wordMonth ? [Number((wordFirst ?? dayFirst)![3]), wordMonth, Number(wordFirst?.[2] ?? dayFirst?.[1])] : null
  const calendar = date ? [Number(date[1]), Number(date[2]), Number(date[3])] :
    monthDayYear ? [Number(monthDayYear[3]), Number(monthDayYear[1]), Number(monthDayYear[2])] : wordCalendar
  if (calendar) {
    const [year, month, day] = calendar
    const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
    const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
    if (!Number.isSafeInteger(year) || month < 1 || month > 12 || day < 1 || day > days[month - 1]) return null
    if (pureIsoDate) return trimmed
    if (calendar === wordCalendar && !/(?:T|\s)\d{1,2}:\d{2}/i.test(trimmed)) {
      return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
    }
    if (date === yearFirst && year >= 100 && /^\d{4,}[-/]\d+[-/]\d+$/.test(trimmed)) {
      return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
    }
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
