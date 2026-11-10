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
