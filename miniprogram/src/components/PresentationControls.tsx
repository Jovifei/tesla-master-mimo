import { Button, Switch, Text, View } from '@tarojs/components'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { activityLevel, civilDay, dayCaption, daySwipeTarget, type DayGroup } from '../domain/ui_presentation'
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
    <Text className="muted">默认左右切日期。上下模式仅在日期抬头生效，行程正文始终正常滚动。</Text>
    <View className="presentation-motion"><Text>减少动画</Text><Switch checked={appearance.reducedMotion}
      ariaLabel="减少动画" onChange={event => setSaveFailed(!writeAppearance({ reducedMotion: event.detail.value }))} /></View>
    {saveFailed ? <Text className="error">本次外观已切换，但未保存到本机；重启后可能恢复默认。</Text> : null}
  </View>
}

type TouchEvent = { touches?: readonly { pageX: number; pageY: number }[]; changedTouches?: readonly { pageX: number; pageY: number }[] }
export function VerticalDayStack<T>(props: { groups: DayGroup<T>[]; scopeKey: string; children: (group: DayGroup<T>) => ReactNode }) {
  // Scope changes remount only presentation state; no data is copied between accounts/cars.
  return <DayStackState key={props.scopeKey} groups={props.groups}>{props.children}</DayStackState>
}
function DayStackState<T>({ groups, children }: { groups: DayGroup<T>[]; children: (group: DayGroup<T>) => ReactNode }) {
  const [selected, setSelected] = useState<string | null>(null)
  const touch = useRef<{ x: number; y: number } | null>(null)
  const appearance = useAppearance()
  useEffect(() => {
    if (selected != null && !groups.some(group => group.key === selected)) setSelected(null)
    touch.current = null
  }, [groups, selected, appearance.dayAxis])
  const index = Math.max(0, groups.findIndex(group => group.key === selected))
  if (!groups.length) return null
  const active = groups[index]
  const selectIndex = (target: number) => setSelected(groups[Math.max(0, Math.min(groups.length - 1, target))].key)
  const pageStart = Math.floor(index / 7) * 7
  const rear = groups.slice(pageStart + 1, pageStart + 7).map((group, i) => ({ group, target: pageStart + i + 1, depth: i + 1 })).reverse()
  const toggle = (target: number) => selectIndex(target === index ? 0 : target)
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
    if (from && point && !event.touches?.length) selectIndex(daySwipeTarget(index, groups.length, point.pageX - from.x, point.pageY - from.y, appearance.dayAxis))
  }
  return <View className={`day-stack axis-${appearance.dayAxis}${appearance.reducedMotion ? ' reduce-motion' : ''}`}>
    {rear.map(({ group, target, depth }, order) => <View key={group.key} className={`day-stack-rear depth-${depth}${index === target ? ' is-selected' : ''}`}
      style={{ marginLeft: `${depth * 6}rpx`, marginRight: `${depth * 6}rpx`, zIndex: order + 1 }}>
      <Button className="day-stack-date" ariaLabel={`${index === target ? '收回' : '查看'} ${dayCaption(group.key)}`} onClick={() => toggle(target)}>{dayCaption(group.key)}</Button>
    </View>)}
    <View className="day-stack-front">
      <View className="day-stack-header" catchMove onTouchStart={start} onTouchMove={move} onTouchEnd={end} onTouchCancel={() => { touch.current = null }}>
        <Text className="section-title">{dayCaption(active.key)}</Text>
        <Text className="muted">{appearance.dayAxis === 'horizontal' ? '左右' : '上下'}滑动日期抬头切换 · 正文正常滚动</Text>
      </View>
      <View className="day-stack-navigation">
        <Button disabled={index === 0} onClick={() => selectIndex(index - 1)} ariaLabel="较新日期">{appearance.dayAxis === 'horizontal' ? '‹' : '↑'} 较新</Button>
        <Text className="muted">{index + 1} / {groups.length} 个日期</Text>
        <Button disabled={index === groups.length - 1} onClick={() => selectIndex(index + 1)} ariaLabel="较旧日期">{appearance.dayAxis === 'horizontal' ? '›' : '↓'} 较旧</Button>
      </View>
      <View key={active.key} className="day-stack-body">{active.items.length ? children(active) : <Text className="day-stack-empty">当天尚无已返回记录；不代表没有行程。</Text>}</View>
      {index > 0 ? <Button className="day-stack-close" onClick={() => selectIndex(0)}>收回至 {dayCaption(groups[0].key)}</Button> : null}
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
  const pageSize = 28
  const [page, setPage] = useState(Math.max(0, Math.ceil(cells.length / pageSize) - 1))
  const [selected, setSelected] = useState<string | null>(null)
  const maxPage = Math.max(0, Math.ceil(cells.length / pageSize) - 1)
  const daily = unit === 'kWh' && cells.every(cell => cell.key.length === 10 && civilDay(cell.key) === cell.key)
  const max = daily ? 60 : Math.max(0, ...cells.flatMap(cell => cell.value != null && Number.isFinite(cell.value) && cell.value >= 0 ? [cell.value] : []))
  const visible = cells.slice(page * pageSize, (page + 1) * pageSize)
  const leading = daily && visible.length ? (new Date(`${visible[0].key}T12:00:00Z`).getUTCDay() + 6) % 7 : 0
  const chosen = cells.find(cell => cell.key === selected)
  return <View className="charge-activity">
    <Text className="muted">{daily ? '一格一天 · 60 度电（kWh）及以上为最深色' : '按已记录日期展示'} · {unit} · — 为尚未读到，不代表未充电。</Text>
    {daily ? <View className="charge-activity-weekdays">{['一','二','三','四','五','六','日'].map(day => <Text key={day}>{day}</Text>)}</View> : null}
    <View className="charge-activity-grid">
      {Array.from({ length: leading }, (_, i) => <View key={`pad-${i}`} />)}
      {visible.map(cell => <Button key={cell.key}
        className={`charge-activity-cell level-${activityLevel(cell.value, max)}${selected === cell.key ? ' is-selected' : ''}`}
        ariaLabel={`${cell.key}，${cell.display}`} onClick={() => setSelected(cell.key)}>
        <Text>{daily ? cell.key.slice(8) : cell.label}</Text><Text className="activity-number">{activityNumber(cell, unit)}</Text>
      </Button>)}
    </View>
    <Text className="charge-activity-selection">{chosen ? `${chosen.key} · ${chosen.display}` : '点选小格查看数值与覆盖情况'}</Text>
    {maxPage > 0 ? <View className="day-stack-navigation"><Button disabled={page === 0} onClick={() => setPage(page - 1)}>较早日期</Button>
      <Text>{page + 1} / {maxPage + 1}</Text><Button disabled={page === maxPage} onClick={() => setPage(page + 1)}>较近日期</Button></View> : null}
  </View>
}
