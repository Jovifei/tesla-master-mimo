import { Button, Switch, Text, View } from '@tarojs/components'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { chargeCellPaint, activityLevel, civilDay, dayCaption, daySwipeTarget, type DayGroup } from '../domain/ui_presentation'
import { useAppearance, writeAppearance } from '../services/appearance'

export function PresentationPreferences() {
  const appearance = useAppearance()
  const [saveFailed, setSaveFailed] = useState(false)
  return <View className="card presentation-preferences">
    <Text className="section-title">外观</Text>
    <Text className="muted">中性白默认；奶白可选。深色跟随系统，不改变账号或车辆设置。</Text>
    <View className="presentation-options">
      {([['neutral', 'C · 中性白'], ['cream', 'A · 奶白']] as const).map(([palette, label]) =>
        <Button key={palette} className={`presentation-option ${appearance.palette === palette ? 'is-selected' : ''}`}
          ariaLabel={`${label}${appearance.palette === palette ? '，已选择' : ''}`}
          onClick={() => setSaveFailed(!writeAppearance({ palette }))}>{label}{appearance.palette === palette ? ' ✓' : ''}</Button>)}
    </View>
    <Text className="section-title">日期切换方向</Text>
    <View className="presentation-options">
      {([['horizontal', '左右滑动'], ['vertical', '上下滑动']] as const).map(([dayAxis, label]) =>
        <Button key={dayAxis} className={`presentation-option ${appearance.dayAxis === dayAxis ? 'is-selected' : ''}`}
          ariaLabel={`${label}${appearance.dayAxis === dayAxis ? '，已选择' : ''}`}
          onClick={() => setSaveFailed(!writeAppearance({ dayAxis }))}>{label}{appearance.dayAxis === dayAxis ? ' ✓' : ''}</Button>)}
    </View>
    <Text className="muted">默认上下切日期。切换仅在日期抬头生效，行程正文始终正常滚动。</Text>
    <View className="presentation-motion"><Text>减少动画</Text><Switch checked={appearance.reducedMotion}
      ariaLabel="减少动画" onChange={event => setSaveFailed(!writeAppearance({ reducedMotion: event.detail.value }))} /></View>
    {saveFailed ? <Text className="error">本次外观已切换，但未保存到本机；重启后可能恢复默认。</Text> : null}
  </View>
}

type TouchEvent = { touches?: readonly { pageX: number; pageY: number }[]; changedTouches?: readonly { pageX: number; pageY: number }[] }
export function VerticalDayStack<T>(props: { groups: DayGroup<T>[]; scopeKey: string; initialKey?: string | null; rememberedKey?: string | null; loading?: boolean; onSelection?: (key: string | null) => void; children: (group: DayGroup<T>) => ReactNode }) {
  // Scope changes remount only presentation state; no data is copied between accounts/cars.
  return <DayStackState key={props.scopeKey} {...props}>{props.children}</DayStackState>
}
function DayStackState<T>({ groups, initialKey, rememberedKey, loading, onSelection, children }: { groups: DayGroup<T>[]; initialKey?: string | null; rememberedKey?: string | null; loading?: boolean; onSelection?: (key: string | null) => void; children: (group: DayGroup<T>) => ReactNode }) {
  const [selected, setSelected] = useState<string | null>(rememberedKey ?? null)
  const touch = useRef<{ x: number; y: number } | null>(null)
  const appearance = useAppearance()
  useEffect(() => {
    if (!loading && selected != null && !groups.some(group => group.key === selected)) { setSelected(null); onSelection?.(null) }
    touch.current = null
  }, [groups, selected, appearance.dayAxis, loading])
  const initialIndex = Math.max(0, groups.findIndex(group => group.key === initialKey))
  const selectedIndex = groups.findIndex(group => group.key === selected)
  const index = selectedIndex < 0 ? initialIndex : selectedIndex
  if (!groups.length) return null
  const active = groups[index]
  const selectIndex = (target: number) => { const key = groups[Math.max(0, Math.min(groups.length - 1, target))].key; setSelected(key); onSelection?.(key) }
  const unknownIndex = groups.findIndex(group => group.key === 'unknown')
  const newer = active.key !== 'unknown' && index > 0 && groups[index - 1].key !== 'unknown'
  const older = active.key !== 'unknown' && index + 1 < groups.length && groups[index + 1].key !== 'unknown'
  const activeDate = civilDay(active.key)
  const rear = groups.slice(index + 1).map((group, i) => ({ group, target: index + i + 1 }))
    .filter(({ group }) => activeDate === active.key && civilDay(group.key) === group.key && group.key < active.key)
    .slice(0, 2).map((entry, i) => ({ ...entry, depth: i + 1 })).reverse()
  const start = (input: unknown) => {
    const event = input as TouchEvent
    const point = event.touches?.length === 1 ? event.touches[0] : null
    touch.current = point ? { x: point.pageX, y: point.pageY } : null
  }
  const move = (input: unknown) => {
    if ((input as TouchEvent).touches?.length !== 1) touch.current = null
  }
  const end = (input: unknown) => {
    const event = input as TouchEvent
    const from = touch.current; touch.current = null
    const point = event.changedTouches?.length === 1 ? event.changedTouches[0] : null
    if (from && point && !event.touches?.length) {
      const target = daySwipeTarget(index, groups.length, point.pageX - from.x, point.pageY - from.y, appearance.dayAxis)
      if (target < index && newer || target > index && older) selectIndex(target)
    }
  }
  return <View className={`day-stack axis-${appearance.dayAxis}${appearance.reducedMotion ? ' reduce-motion' : ''}`}>
    {rear.map(({ group, target, depth }, order) => <View key={group.key} className={`day-stack-rear depth-${depth}`}
      style={{ marginLeft: `${depth * 6}rpx`, marginRight: `${depth * 6}rpx`, zIndex: order + 1 }}>
      <Button className="day-stack-date" ariaLabel={`查看 ${dayCaption(group.key)}`} onClick={() => selectIndex(target)}>{dayCaption(group.key)}</Button>
    </View>)}
    <View className="day-stack-front">
      <View className="day-stack-header" catchMove onTouchStart={start} onTouchMove={move} onTouchEnd={end} onTouchCancel={() => { touch.current = null }}>
        <View className="day-stack-heading-row">
          <Text className="section-title">{dayCaption(active.key)}</Text>
          <View className="day-stack-arrows">
            <Button disabled={!newer} onClick={() => selectIndex(index - 1)} ariaLabel="较新日期">{appearance.dayAxis === 'horizontal' ? '‹' : '↑'}</Button>
            <Button disabled={!older} onClick={() => selectIndex(index + 1)} ariaLabel="较旧日期">{appearance.dayAxis === 'horizontal' ? '›' : '↓'}</Button>
          </View>
        </View>
        <Text className="muted">{appearance.dayAxis === 'horizontal' ? '左右' : '上下'}滑动日期抬头切换 · 正文正常滚动</Text>
      </View>
      <View key={active.key} className="day-stack-body">{active.items.length ? children(active) : <Text className="day-stack-empty">当天尚无已返回记录；不代表没有行程。</Text>}</View>
      {unknownIndex >= 0 && active.key !== 'unknown' ? <Button className="button-secondary" onClick={() => selectIndex(unknownIndex)}>查看日期待确认</Button> : null}
      {index !== initialIndex ? <Button className="day-stack-close" onClick={() => { setSelected(null); onSelection?.(null) }}>收回至 {dayCaption(groups[initialIndex].key)}</Button> : null}
    </View>
  </View>
}

export type ActivityCell = { key: string; label: string; value: number | null; display: string; precision?: number; approximate?: boolean }
export function activityNumber(cell: ActivityCell, unit: string): string {
  if (cell.value == null || !Number.isFinite(cell.value) || cell.value < 0) return '—'
  const precision = Number.isInteger(cell.precision) ? Math.max(0, Math.min(2, cell.precision!)) : unit === '次' ? 0 : 1
  return `${cell.approximate ? '≈' : ''}${cell.value.toFixed(precision)}`
}
export function ChargeActivityGrid({ cells, unit }: { cells: ActivityCell[]; unit: string }) {
  return <ChargeGridState key={JSON.stringify(cells.map(cell => cell.key)) + unit} cells={cells} unit={unit} />
}
function ChargeGridState({ cells, unit }: { cells: ActivityCell[]; unit: string }) {
  const appearance = useAppearance()
  const pageSize = 28
  const [page, setPage] = useState(Math.max(0, Math.ceil(cells.length / pageSize) - 1))
  const [selected, setSelected] = useState<string | null>(null)
  const maxPage = Math.max(0, Math.ceil(cells.length / pageSize) - 1)
  const daily = unit === 'kWh' && cells.every(cell => cell.key.length === 10 && civilDay(cell.key) === cell.key)
  const max = daily ? 60 : Math.max(0, ...cells.flatMap(cell => cell.value != null && Number.isFinite(cell.value) && cell.value >= 0 ? [cell.value] : []))
  const end = cells.length - (maxPage - page) * pageSize
  const visible = cells.slice(Math.max(0, end - pageSize), end)
  const leading = daily && visible.length ? (new Date(`${visible[0].key}T12:00:00Z`).getUTCDay() + 6) % 7 : 0
  const chosen = cells.find(cell => cell.key === selected)
  return <View className="charge-activity">
    <Text className="muted">{daily ? '一格一天 · 60 度电（kWh）及以上为最深色' : '按已记录日期展示'} · {unit} · — 为尚未读到，不代表未充电。</Text>
    {daily ? <View className="charge-activity-weekdays">{['一','二','三','四','五','六','日'].map(day => <Text key={day}>{day}</Text>)}</View> : null}
    <View className="charge-activity-grid">
      {Array.from({ length: leading }, (_, i) => <View key={`pad-${i}`} />)}
      {visible.map(cell => <Button key={cell.key}
        className={`charge-activity-cell level-${activityLevel(cell.value, max)}${selected === cell.key ? ' is-selected' : ''}`}
        style={daily ? `--charge-cell-bg:${chargeCellPaint(cell.value, appearance.palette).background};--charge-cell-ink:${chargeCellPaint(cell.value, appearance.palette).foreground};--charge-cell-bg-dark:${chargeCellPaint(cell.value, appearance.palette, true).background};--charge-cell-ink-dark:${chargeCellPaint(cell.value, appearance.palette, true).foreground}` : undefined}
        ariaLabel={`${cell.key}，${cell.display}`} onClick={() => setSelected(cell.key)}>
        <Text>{daily ? cell.key.slice(8) : cell.label}</Text><Text className="activity-number">{activityNumber(cell, unit)}</Text>
      </Button>)}
    </View>
    <Text className="charge-activity-selection">{chosen ? `${chosen.key} · ${chosen.display}` : '点选小格查看数值与覆盖情况'}</Text>
    {maxPage > 0 ? <View className="day-stack-navigation"><Button disabled={page === 0} onClick={() => setPage(page - 1)}>较早日期</Button>
      <Text>{page + 1} / {maxPage + 1}</Text><Button disabled={page === maxPage} onClick={() => setPage(page + 1)}>较近日期</Button></View> : null}
  </View>
}
