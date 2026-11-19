import { Button, Picker, Text, View } from '@tarojs/components'
import { selectHistoryDate, type HistoryQuery } from '../domain/history_query'
import { copy } from '../i18n/copy'

function civil(date: Date) { return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}` }

/** Same inclusive local-calendar presets as App history; never re-stamps observations. */
const RANGE_CHOICES = [7, 30, 90, 365, null, 0] as const

function presetStart(days: number | null, today: Date): string {
  if (days == null) return ''
  const start = new Date(today.getFullYear(), today.getMonth(), today.getDate())
  if (days > 0) start.setDate(start.getDate() - (days - 1))
  return civil(start)
}

export function rangePresetLabel(days: number | null): string {
  if (days == null) return '全部时间'
  if (days === 0) return '今天'
  if (days === 7) return '近 1 周'
  if (days === 30) return '近 1 个月'
  if (days === 90) return '近 3 个月'
  if (days === 365) return '近 1 年'
  return `近 ${days} 天`
}

export function historyRangeLabel(query: HistoryQuery): string {
  const today = new Date()
  for (const days of RANGE_CHOICES) {
    if (query.startDate === presetStart(days, today) && query.endDate === (days == null ? '' : civil(today))) {
      return rangePresetLabel(days)
    }
  }
  if (!query.startDate && !query.endDate) return copy('history.all')
  if (query.startDate && query.endDate) return query.startDate === query.endDate ? query.startDate : `${query.startDate} – ${query.endDate}`
  return query.startDate || query.endDate
}

/** Compact App-style range dropdown: one label with a chevron, not a chip row. */
export function HistoryRangePicker({ query, onChange }: { query: HistoryQuery; onChange: (patch: Partial<HistoryQuery>) => void }) {
  const today = new Date(), endDate = civil(today)
  const labels = RANGE_CHOICES.map(days => rangePresetLabel(days))
  const index = RANGE_CHOICES.findIndex(days => query.startDate === presetStart(days, today) && query.endDate === (days == null ? '' : endDate))
  return <Picker mode="selector" range={labels} value={Math.max(0, index)} onChange={event => {
    const index = Number(event.detail.value)
    const days = RANGE_CHOICES[index] === undefined ? 30 : RANGE_CHOICES[index]
    onChange({ startDate: presetStart(days, today), endDate: days == null ? '' : endDate, relativeDays: days == null ? undefined : days === 0 ? 1 : days })
  }}>
    <View className="history-range-picker"><Text>{historyRangeLabel(query)}</Text><Text className="history-range-chevron">▼</Text></View>
  </Picker>
}

export default function HistoryDateChips({ query, onChange }: {
  kind: 'drives' | 'charges'; query: HistoryQuery; onChange: (patch: Partial<HistoryQuery>) => void
}) {
  // App uses the same presets on both history tabs: 7d / 30d / all / today / custom.
  const choices = [7, 30, 90, 365, null, 0]
  const today = new Date(), endDate = civil(today)
  const isPreset = choices.some(days => {
    const start = new Date(today.getFullYear(), today.getMonth(), today.getDate())
    if (days != null && days > 0) start.setDate(start.getDate() - (days - 1))
    const startDate = days == null ? '' : civil(start)
    return query.startDate === startDate && query.endDate === (days == null ? '' : endDate)
  })
  const isCustom = !isPreset && (Boolean(query.startDate) || Boolean(query.endDate))
  const customLabel = isCustom
    ? (query.startDate && query.endDate ? (query.startDate === query.endDate ? query.startDate : `${query.startDate} – ${query.endDate}`) : query.startDate || query.endDate)
    : copy('history.selectDate')

  return <View className="history-date-chips">
    {choices.map(days => {
      const start = new Date(today.getFullYear(), today.getMonth(), today.getDate())
      if (days != null && days > 0) start.setDate(start.getDate() - (days - 1))
      const startDate = days == null ? '' : civil(start)
      const selected = query.startDate === startDate && query.endDate === (days == null ? '' : endDate)
      return <Button key={String(days)} className={`history-chip ${selected ? 'history-chip-selected' : ''}`} onClick={() => onChange({ startDate, endDate: days == null ? '' : endDate, relativeDays: days == null ? undefined : days === 0 ? 1 : days })}>
        {days == null ? copy('history.all') : days === 0 ? copy('history.today') : days === 365 ? copy('history.lastYear') : copy('history.lastDays', { days })}
      </Button>
    })}
    <Picker mode="date" value={query.startDate || endDate} onChange={event => onChange(selectHistoryDate({ ...query, endDate: query.endDate || event.detail.value }, 'startDate', event.detail.value))}>
      <Button className={`history-chip history-chip-date ${isCustom ? 'history-chip-selected' : ''}`}>{customLabel}</Button>
    </Picker>
    <Picker mode="date" value={query.endDate || endDate} onChange={event => onChange(selectHistoryDate({ ...query, startDate: query.startDate || event.detail.value }, 'endDate', event.detail.value))}>
      <Button className={`history-chip history-chip-date ${isCustom ? 'history-chip-selected' : ''}`}>{copy('history.selectEndDate')}</Button>
    </Picker>
  </View>
}
