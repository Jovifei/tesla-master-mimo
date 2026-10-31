import { Picker, Text, View } from '@tarojs/components'
import { useState } from 'react'
import { historyDateTime } from '../domain/history_query'
import { isHistoryQuarantined } from '../domain/history'
import type { Drive } from '../services/types'
import { displayNumber, peakSpeedKmh } from './MetricValue'
import VisualIcon from './VisualIcon'

type Granularity = 'day' | 'week' | 'month'
type Group = { day: string; label: string; count: number; distance: number | null; duration: number | null; speed: number | null; missingDistance: number; missingDuration: number; missingSpeed: number }
const nonnegative = (n: number | null | undefined) => n != null && Number.isFinite(n) && n >= 0 ? n : null
function groupKey(value: string, granularity: Granularity): string {
  const [year, month, day] = value.slice(0, 10).split('-').map(Number)
  if (granularity === 'month') return `${year}-${String(month).padStart(2, '0')}`
  if (granularity === 'day') return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
  const date = new Date(year, month - 1, day)
  date.setDate(date.getDate() - ((date.getDay() + 6) % 7))
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}
function groupLabel(key: string, granularity: Granularity): string {
  if (granularity === 'month') return `${Number(key.slice(5))}月`
  if (granularity === 'day') return key.slice(5)
  const end = new Date(`${key}T00:00:00`)
  end.setDate(end.getDate() + 6)
  return `${key.slice(5).replace('-', '/')}–${String(end.getMonth() + 1).padStart(2, '0')}/${String(end.getDate()).padStart(2, '0')}`
}
export function loadedDriveGroups(items: readonly Drive[], granularity: Granularity = 'day'): Group[] {
  const groups = new Map<string, Group>()
  for (const item of items) {
    if (isHistoryQuarantined(item)) continue
    const date = historyDateTime(item.startDate)
    if (date === '暂无日期') continue
    const day = groupKey(date, granularity)
    const group = groups.get(day) ?? { day, label: groupLabel(day, granularity), count: 0, distance: null, duration: null, speed: null, missingDistance: 0, missingDuration: 0, missingSpeed: 0 }
    group.count += 1
    const distance = nonnegative(item.distanceKm), duration = nonnegative(item.durationMinutes), speed = peakSpeedKmh(item)
    if (distance == null) group.missingDistance += 1
    else group.distance = (group.distance ?? 0) + distance
    if (duration == null) group.missingDuration += 1
    else group.duration = (group.duration ?? 0) + duration
    if (speed == null) group.missingSpeed += 1
    else group.speed = Math.max(group.speed ?? speed, speed)
    groups.set(day, group)
  }
  return [...groups.values()].map(group => ({
    ...group,
    distance: group.distance != null && Number.isFinite(group.distance) ? group.distance : null,
    duration: group.duration != null && Number.isFinite(group.duration) ? group.duration : null,
  }))
    .sort((a, b) => a.day.localeCompare(b.day))
}

export default function LoadedDriveChart({ items, granularity = 'day' }: { items: readonly Drive[]; granularity?: Granularity }) {
  const [kind, setKind] = useState(0)
  const [period, setPeriod] = useState<Granularity>(granularity)
  const groups = loadedDriveGroups(items, period).slice(period === 'day' ? -14 : -12)
  const getValue = (group: Group) => kind === 0 ? group.count : kind === 1 ? group.distance : kind === 2 ? group.speed : group.duration
  const unit = kind === 0 ? '次' : kind === 1 ? 'km' : kind === 2 ? 'km/h' : '分钟'
  const title = `${kind === 0 ? '行程数' : kind === 1 ? '行程距离' : kind === 2 ? '最高速度' : '行程时间'}/${period === 'day' ? '天' : period === 'week' ? '周' : '月'}`
  const max = Math.max(0, ...groups.map(group => getValue(group) ?? 0))
  return <View className="card loaded-chart">
    <View className="visual-heading"><VisualIcon name="chart" /><Text className="section-title">{title}</Text></View>
    <Text className="chart-unit-label">单位：{unit}</Text>
    <Picker mode="selector" range={['行程次数', '已知距离', '观测最高速度', '行驶时间']} value={kind} onChange={event => setKind(Number(event.detail.value))}>
      <View className="chart-selector">{['行程次数', '已知距离', '观测最高速度', '行驶时间'][kind]} · {unit} ▾</View>
    </Picker>
    <View className="chart-period-tabs">{(['day', 'week', 'month'] as const).map(value =>
      <View key={value} className={`chart-period-tab${period === value ? ' chart-period-tab-active' : ''}`} onClick={() => setPeriod(value)}>{value === 'day' ? '日' : value === 'week' ? '周' : '月'}</View>)}</View>
    {groups.length ? <View className="chart-plot"><View className="chart-y-axis"><Text>{displayNumber(max, 0)}</Text><Text>0</Text></View><View className="chart-bars">{groups.map(group => {
      const number = getValue(group)
      return <View className="chart-column" key={group.day}>
        <Text className="chart-value">{number == null ? '暂无数据' : `${displayNumber(number, kind === 0 || (kind === 2 && Number.isInteger(number)) ? 0 : 1)} ${unit}`}</Text>
        <View className="chart-track">{number == null ? null : <View className="chart-bar" style={{ height: max === 0 ? '0%' : `${number / max * 100}%` }} />}</View>
        <Text className="chart-date">{group.label}</Text>
      </View>
    })}</View></View> : <View className="empty-state"><VisualIcon name="chart" /><Text className="muted">暂无图表数据</Text></View>}
    <View className="chart-pagination">{[0, 1, 2, 3].map(index => <View key={index} className={`chart-page-dot${kind === index ? ' chart-page-dot-active' : ''}`} onClick={() => setKind(index)} />)}</View>
  </View>
}
