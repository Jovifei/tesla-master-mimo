export type DayAxis = 'horizontal' | 'vertical'
export type Appearance = { palette: 'neutral' | 'cream'; dayAxis: DayAxis; reducedMotion: boolean }
export function normalizeAppearance(raw: unknown): Appearance {
  const x = raw && typeof raw === 'object' ? raw as Partial<Appearance> : {}
  return { palette: x.palette === 'cream' ? 'cream' : 'neutral', dayAxis: x.dayAxis === 'vertical' ? 'vertical' : 'horizontal', reducedMotion: x.reducedMotion === true }
}
export function daySwipeTarget(index: number, count: number, dx: number, dy: number, axis: DayAxis): number {
  if (!count || ![index, dx, dy].every(Number.isFinite)) return index
  const main = axis === 'horizontal' ? dx : dy, cross = axis === 'horizontal' ? dy : dx
  return Math.abs(main) >= 48 && Math.abs(main) > Math.abs(cross) * 1.2 ? Math.max(0, Math.min(count - 1, index + (main < 0 ? 1 : -1))) : index
}
export function civilDay(raw: string | null): string | null {
  if (!raw) return null
  if (/^\d{4}-\d{2}-\d{2}$/.test(raw)) {
    const d = new Date(raw + 'T12:00:00Z')
    return Number.isFinite(d.getTime()) && d.toISOString().slice(0, 10) === raw ? raw : null
  }
  const d = new Date(raw)
  return Number.isFinite(d.getTime()) ? `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` : null
}
export function calendarDays(start: string, end: string): string[] {
  if (civilDay(start) !== start || civilDay(end) !== end) return []
  const first = Date.parse(start + 'T12:00:00Z'), last = Date.parse(end + 'T12:00:00Z')
  if (last < first || last - first > 365 * 86400000) return []
  return Array.from({ length: Math.round((last-first)/86400000)+1 }, (_, i) => new Date(first + i*86400000).toISOString().slice(0,10))
}
export function dayCaption(key: string, today = new Date()): string {
  const end = civilDay(today.toISOString())!
  const distance = Math.round((Date.parse(end+'T12:00:00Z') - Date.parse(key+'T12:00:00Z'))/86400000)
  return `${['今天', '昨天', '前天'][distance] ?? ''}${distance >= 0 && distance < 3 ? ' · ' : ''}${key}`
}
export function chargeShade(value: number | null): number {
  return value != null && Number.isFinite(value) && value > 0 ? Math.min(value/60, 1) : 0
}
export function dailyChargeCells<T extends { startDate: string | null; energyAddedKwh: number | null }>(rows: readonly T[], start: string, end: string) {
  return calendarDays(start, end).map(key => {
    const group = rows.filter(row => civilDay(row.startDate) === key)
    const known = group.flatMap(row => row.energyAddedKwh != null && Number.isFinite(row.energyAddedKwh) && row.energyAddedKwh >= 0 ? [row.energyAddedKwh] : [])
    return { key, count: group.length, known: known.length, value: known.length ? known.reduce((a,b) => a+b, 0) : null }
  })
}
