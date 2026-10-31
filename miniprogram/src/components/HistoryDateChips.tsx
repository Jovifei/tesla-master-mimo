import { Button, Picker, View } from '@tarojs/components'
import { selectHistoryDate, type HistoryQuery } from '../domain/history_query'
import { copy } from '../i18n/copy'

function civil(date: Date) { return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}` }

/** Same inclusive local-calendar presets as App history; never re-stamps observations. */
export default function HistoryDateChips({ kind, query, onChange }: {
  kind: 'drives' | 'charges'; query: HistoryQuery; onChange: (patch: Partial<HistoryQuery>) => void
}) {
  const choices = kind === 'drives' ? [7, 30, null, 0] : [7, 30, 90]
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
    {kind === 'charges' ? <Picker mode="date" value={query.startDate || endDate} onChange={event => onChange(selectHistoryDate({ ...query, endDate: query.endDate || event.detail.value }, 'startDate', event.detail.value))}>
      <Button className={`history-chip history-chip-date ${isCustom ? 'history-chip-selected' : ''}`}>{customLabel}</Button>
    </Picker> : null}
    {choices.map(days => {
      const start = new Date(today.getFullYear(), today.getMonth(), today.getDate())
      if (days != null && days > 0) start.setDate(start.getDate() - (days - 1))
      const startDate = days == null ? '' : civil(start)
      const selected = query.startDate === startDate && query.endDate === (days == null ? '' : endDate)
      return <Button key={String(days)} className={`history-chip ${selected ? 'history-chip-selected' : ''}`} onClick={() => onChange({ startDate, endDate: days == null ? '' : endDate })}>
        {days == null ? copy('history.all') : days === 0 ? copy('history.today') : days === 365 ? copy('history.lastYear') : copy('history.lastDays', { days })}
      </Button>
    })}
    {kind === 'drives' ? <Picker mode="date" value={query.startDate || endDate} onChange={event => onChange(selectHistoryDate({ ...query, endDate: query.endDate || event.detail.value }, 'startDate', event.detail.value))}>
      <Button className={`history-chip history-chip-date ${isCustom ? 'history-chip-selected' : ''}`}>{customLabel}</Button>
    </Picker> : null}
    <Picker mode="date" value={query.endDate || endDate} onChange={event => onChange(selectHistoryDate({ ...query, startDate: query.startDate || event.detail.value }, 'endDate', event.detail.value))}>
      <Button className={`history-chip history-chip-date ${isCustom ? 'history-chip-selected' : ''}`}>{copy('history.selectEndDate')}</Button>
    </Picker>
  </View>
}
