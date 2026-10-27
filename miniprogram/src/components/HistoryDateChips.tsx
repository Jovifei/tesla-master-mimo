import { Button, Picker, View } from '@tarojs/components'
import type { HistoryQuery } from '../domain/history_query'

function civil(date: Date) { return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}` }

/** Same inclusive local-calendar presets as App history; never re-stamps observations. */
export default function HistoryDateChips({ kind, query, onChange }: {
  kind: 'drives' | 'charges'; query: HistoryQuery; onChange: (patch: Partial<HistoryQuery>) => void
}) {
  const choices = kind === 'drives' ? [7, 30, null, 0] : [30, 7, 90, null]
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
    : '选择日期'

  return <View className="history-date-chips">
    {choices.map(days => {
      const start = new Date(today.getFullYear(), today.getMonth(), today.getDate())
      if (days != null && days > 0) start.setDate(start.getDate() - (days - 1))
      const startDate = days == null ? '' : civil(start)
      const selected = query.startDate === startDate && query.endDate === (days == null ? '' : endDate)
      return <Button key={String(days)} className={`history-chip ${selected ? 'history-chip-selected' : ''}`} onClick={() => onChange({ startDate, endDate: days == null ? '' : endDate })}>
        {days == null ? '全部' : days === 0 ? '今天' : days === 365 ? '近1年' : `近${days}天`}
      </Button>
    })}
    <Picker mode="date" value={query.startDate || endDate} onChange={event => onChange({ startDate: event.detail.value, endDate: query.endDate || event.detail.value })}>
      <Button className={`history-chip history-chip-date ${isCustom ? 'history-chip-selected' : ''}`}>
        {customLabel}
      </Button>
    </Picker>
  </View>
}
