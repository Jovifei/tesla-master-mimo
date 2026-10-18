import { Picker, Text, View } from '@tarojs/components'
import { useState } from 'react'
import { historyDateTime } from '../domain/history_query'
import { isHistoryQuarantined } from '../domain/history'
import type { Drive } from '../services/types'
import MetricValue, { displayNumber, peakSpeedKmh } from './MetricValue'
import VisualIcon from './VisualIcon'

type Group = { day: string; count: number; distance: number | null; speed: number | null; missingDistance: number; missingSpeed: number }
const nonnegative = (n: number | null | undefined) => n != null && Number.isFinite(n) && n >= 0 ? n : null
export function loadedDriveGroups(items: readonly Drive[]): Group[] {
  const groups = new Map<string, Group>()
  for (const item of items) {
    if (isHistoryQuarantined(item)) continue
    const date = historyDateTime(item.startDate)
    if (date === '暂无日期') continue
    const day = date.slice(0, 10)
    const group = groups.get(day) ?? { day, count: 0, distance: null, speed: null, missingDistance: 0, missingSpeed: 0 }
    group.count += 1
    const distance = nonnegative(item.distanceKm), speed = peakSpeedKmh(item)
    if (distance == null) group.missingDistance += 1
    else group.distance = (group.distance ?? 0) + distance
    if (speed == null) group.missingSpeed += 1
    else group.speed = Math.max(group.speed ?? speed, speed)
    groups.set(day, group)
  }
  return [...groups.values()].map(group => ({ ...group, distance: group.distance != null && Number.isFinite(group.distance) ? group.distance : null }))
    .sort((a, b) => a.day.localeCompare(b.day))
}

export default function LoadedDriveChart({ items }: { items: readonly Drive[] }) {
  const [kind, setKind] = useState(0)
  const groups = loadedDriveGroups(items).slice(-7)
  const getValue = (group: Group) => kind === 0 ? group.count : kind === 1 ? group.distance : group.speed
  const unit = kind === 0 ? '次' : kind === 1 ? 'km' : 'km/h'
  const max = Math.max(0, ...groups.map(group => getValue(group) ?? 0))
  const missingDates = items.filter(item => !isHistoryQuarantined(item) && historyDateTime(item.startDate) === '暂无日期').length
  return <View className="card loaded-chart">
    <View className="visual-heading"><VisualIcon name="chart" /><Text className="section-title">已加载记录统计</Text></View>
    <Text className="muted">当前筛选下的本机已加载行程，包含缓存；不代表全部历史。图表展示最近 7 个有记录日期。</Text>
    <Picker mode="selector" range={['行程次数', '已知距离', '观测最高速度']} value={kind} onChange={event => setKind(Number(event.detail.value))}>
      <View className="chart-selector">{['行程次数', '已知距离', '观测最高速度'][kind]} · {unit} ▾</View>
    </Picker>
    {groups.length ? <View className="chart-bars">{groups.map(group => {
      const number = getValue(group)
      const missing = kind === 1 ? group.missingDistance : kind === 2 ? group.missingSpeed : 0
      return <View className="chart-column" key={group.day}>
        <Text className="chart-value">{`${number == null ? '暂无' : displayNumber(number, kind === 0 || (kind === 2 && Number.isInteger(number)) ? 0 : 1)} ${unit}`}</Text>
        <View className="chart-track">{number == null ? null : <View className="chart-bar" style={{ height: max === 0 ? '0%' : `${number / max * 100}%` }} />}</View>
        <Text className="chart-date">{group.day.slice(5)}</Text>
        {missing ? <Text className="chart-missing">缺 {missing} 条</Text> : null}
      </View>
    })}</View> : <View className="empty-state"><VisualIcon name="chart" /><Text className="muted">暂无合格日期可分组，未生成示例图表。</Text></View>}
    {missingDates ? <Text className="muted">另有 {missingDates} 条日期未知，未放入按日图表。</Text> : null}
    <View className="metric-grid"><MetricValue label="已加载行程" value={items.filter(item => !isHistoryQuarantined(item)).length} unit="次" icon="route" digits={0} /></View>
    {kind === 2 ? <Text className="muted">最高速度仅为行程已观测样本的峰值，缺少样本证明的记录不参与。</Text> : null}
  </View>
}
