import { Button, Switch, Text, View } from '@tarojs/components'
import { useRef, useState, type ReactNode } from 'react'
import { activityLevel, dayCaption, verticalDayTarget, type DayGroup } from '../domain/ui_presentation'
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
  const index = Math.max(0, groups.findIndex(group => group.key === selected))
  if (!groups.length) return null
  const active = groups[index]
  const selectIndex = (target: number) => setSelected(groups[Math.max(0, Math.min(groups.length - 1, target))].key)
  const rear = groups.slice(index + 1, index + 3).map((group, i) => ({ group, depth: i + 1 })).reverse()
  const start = (input: unknown) => {
    const event = input as TouchEvent
    const point = event.touches?.length === 1 ? event.touches[0] : null
    touch.current = point ? { x: point.pageX, y: point.pageY } : null
  }
  const end = (input: unknown) => {
    const event = input as TouchEvent
    const from = touch.current; touch.current = null
    const point = event.changedTouches?.length === 1 ? event.changedTouches[0] : null
    if (from && point) selectIndex(verticalDayTarget(index, groups.length, point.pageX - from.x, point.pageY - from.y))
  }
  return <View className={`day-stack${appearance.reducedMotion ? ' reduce-motion' : ''}`}>
    {rear.map(({ group, depth }) => <View key={group.key} className={`day-stack-rear depth-${depth}`}>
      <Button className="day-stack-date" ariaLabel={`查看 ${dayCaption(group.key)}`} onClick={() => setSelected(group.key)}>{dayCaption(group.key)}</Button>
    </View>)}
    <View className="day-stack-front">
      <View className="day-stack-header" catchMove onTouchStart={start} onTouchEnd={end} onTouchCancel={() => { touch.current = null }}>
        <Text className="section-title">{dayCaption(active.key)}</Text>
        <Text className="muted">在日期抬头上下滑动切换 · 正文正常滚动</Text>
      </View>
      <View className="day-stack-navigation">
        <Button disabled={index === 0} onClick={() => selectIndex(index - 1)} ariaLabel="较新日期">↑ 较新</Button>
        <Text className="muted">{index + 1} / {groups.length} 个已记录日期</Text>
        <Button disabled={index === groups.length - 1} onClick={() => selectIndex(index + 1)} ariaLabel="较旧日期">↓ 较旧</Button>
      </View>
      <View key={active.key} className="day-stack-body">{children(active)}</View>
    </View>
  </View>
}

export type ActivityCell = { key: string; label: string; value: number | null; display: string }
export function ChargeActivityGrid({ cells, unit }: { cells: ActivityCell[]; unit: string }) {
  return <ChargeGridState key={JSON.stringify(cells.map(cell => cell.key)) + unit} cells={cells} unit={unit} />
}
function ChargeGridState({ cells, unit }: { cells: ActivityCell[]; unit: string }) {
  const pageSize = 28
  const [page, setPage] = useState(Math.max(0, Math.ceil(cells.length / pageSize) - 1))
  const [selected, setSelected] = useState<string | null>(null)
  const maxPage = Math.max(0, Math.ceil(cells.length / pageSize) - 1)
  const max = Math.max(0, ...cells.flatMap(cell => cell.value != null && Number.isFinite(cell.value) && cell.value >= 0 ? [cell.value] : []))
  const chosen = cells.find(cell => cell.key === selected)
  return <View className="charge-activity">
    <Text className="muted">按已记录日期展示 · {unit} · 深浅表示数值大小；— 为未知，不补造无记录日期。</Text>
    <View className="charge-activity-grid">
      {cells.slice(page * pageSize, (page + 1) * pageSize).map(cell => <Button key={cell.key}
        className={`charge-activity-cell level-${activityLevel(cell.value, max)}${selected === cell.key ? ' is-selected' : ''}`}
        ariaLabel={`${cell.key}，${cell.display}`} onClick={() => setSelected(cell.key)}>
        <Text>{cell.label}</Text><Text className="activity-number">{cell.value == null ? '—' : Number.isFinite(cell.value) ? cell.value.toFixed(unit === '次' ? 0 : 1) : '—'}</Text>
      </Button>)}
    </View>
    <Text className="charge-activity-selection">{chosen ? `${chosen.key} · ${chosen.display}` : '点选小格查看数值与覆盖情况'}</Text>
    {maxPage > 0 ? <View className="day-stack-navigation"><Button disabled={page === 0} onClick={() => setPage(page - 1)}>较早日期</Button>
      <Text>{page + 1} / {maxPage + 1}</Text><Button disabled={page === maxPage} onClick={() => setPage(page + 1)}>较近日期</Button></View> : null}
  </View>
}
