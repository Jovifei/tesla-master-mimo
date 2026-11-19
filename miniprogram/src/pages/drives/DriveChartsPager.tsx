import { Text, View } from '@tarojs/components'
import { useMemo, useRef, useState } from 'react'
import type { Drive } from '../../services/types'
import type { HistoryQuery } from '../../domain/history_query'
import VisualIcon, { type VisualIconName } from '../../components/VisualIcon'

type Granularity = 'daily' | 'weekly' | 'monthly'
type ChartType = 'count' | 'time' | 'distance' | 'speed'

type Point = {
  key: string
  label: string
  count: number
  duration: number | null
  distance: number | null
  speed: number | null
}

function civil(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

function parseDay(value: string | null | undefined): Date | null {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : new Date(date.getFullYear(), date.getMonth(), date.getDate())
}

function startOfWeek(date: Date): Date {
  const copy = new Date(date.getFullYear(), date.getMonth(), date.getDate())
  const offset = (copy.getDay() + 6) % 7
  copy.setDate(copy.getDate() - offset)
  return copy
}

function startOfMonth(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), 1)
}

function granularityFor(query: HistoryQuery, rows: readonly Drive[]): Granularity {
  const start = parseDay(query.startDate)
  const end = parseDay(query.endDate)
  if (start && end) {
    const days = Math.round((end.getTime() - start.getTime()) / 86400000)
    if (days <= 30) return 'daily'
    if (days <= 90) return 'weekly'
    return 'monthly'
  }
  return 'monthly'
}

function buildPoints(rows: readonly Drive[], granularity: Granularity, query: HistoryQuery): Point[] {
  const dated = rows.map(row => ({ row, day: parseDay(row.startDate) })).filter((entry): entry is { row: Drive; day: Date } => entry.day != null)
  const start = parseDay(query.startDate) ?? dated.reduce<Date | null>((min, entry) => !min || entry.day < min ? entry.day : min, null) ?? new Date()
  const end = parseDay(query.endDate) ?? new Date()
  const bucket = (day: Date) => granularity === 'daily' ? day : granularity === 'weekly' ? startOfWeek(day) : startOfMonth(day)
  const keys: Date[] = []
  let cursor = bucket(start)
  const last = bucket(end)
  while (cursor.getTime() <= last.getTime()) {
    keys.push(cursor)
    const next = new Date(cursor)
    if (granularity === 'daily') next.setDate(next.getDate() + 1)
    else if (granularity === 'weekly') next.setDate(next.getDate() + 7)
    else next.setMonth(next.getMonth() + 1)
    cursor = next
  }
  const groups = new Map<string, Drive[]>()
  for (const entry of dated) {
    const key = civil(bucket(entry.day))
    groups.set(key, [...(groups.get(key) ?? []), entry.row])
  }
  const sameYear = start.getFullYear() === end.getFullYear()
  return keys.map(day => {
    const key = civil(day)
    const items = groups.get(key) ?? []
    const durations = items.map(item => item.durationMinutes).filter((value): value is number => value != null && Number.isFinite(value) && value >= 0)
    const distances = items.map(item => item.distanceKm).filter((value): value is number => value != null && Number.isFinite(value) && value >= 0)
    const speeds = items.map(item => item.speedMaxKmh).filter((value): value is number => value != null && Number.isFinite(value) && value >= 0)
    let label: string
    if (granularity === 'daily') label = `${day.getMonth() + 1}/${day.getDate()}`
    else if (granularity === 'weekly') label = `${day.getMonth() + 1}/${day.getDate()} 周`
    else label = sameYear ? `${day.getMonth() + 1}月` : `${day.getFullYear()}/${day.getMonth() + 1}`
    return {
      key,
      label,
      count: items.length,
      duration: durations.length ? durations.reduce((sum, value) => sum + value, 0) : null,
      distance: distances.length ? distances.reduce((sum, value) => sum + value, 0) : null,
      speed: speeds.length ? Math.max(...speeds) : null,
    }
  })
}

const CHART_SPECS: Record<ChartType, { icon: VisualIconName; title: (granularity: Granularity) => string; unit: string }> = {
  count: { icon: 'metricCount', title: g => g === 'daily' ? '行程数/天' : g === 'weekly' ? '行程数/周' : '行程数/月', unit: '次' },
  time: { icon: 'metricTime', title: g => g === 'daily' ? '时间/天' : g === 'weekly' ? '时间/周' : '时间/月', unit: '分钟' },
  distance: { icon: 'metricDistance', title: g => g === 'daily' ? '距离/天' : g === 'weekly' ? '距离/周' : '距离/月', unit: 'km' },
  speed: { icon: 'metricSpeed', title: g => g === 'daily' ? '速度/天' : g === 'weekly' ? '速度/周' : '速度/月', unit: 'km/h' },
}

const CHART_ORDER: ChartType[] = ['count', 'time', 'distance', 'speed']

function chartValue(point: Point, type: ChartType): number | null {
  if (type === 'count') return point.count
  if (type === 'time') return point.duration
  if (type === 'distance') return point.distance
  return point.speed
}

function formatValue(value: number, type: ChartType): string {
  if (type === 'count') return String(Math.round(value))
  if (type === 'time') return String(Math.round(value))
  return value.toFixed(1)
}

export default function DriveChartsPager({ rows, query, partial }: { rows: readonly Drive[]; query: HistoryQuery; partial: boolean }) {
  const granularity = useMemo(() => granularityFor(query, rows), [query, rows])
  const points = useMemo(() => buildPoints(rows, granularity, query), [rows, granularity, query])
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<string | null>(null)
  const touch = useRef<{ x: number; y: number } | null>(null)
  if (!points.length) return null
  const type = CHART_ORDER[page]!
  const spec = CHART_SPECS[type]
  const visible = type === 'count' ? points : points.filter(point => chartValue(point, type) != null)
  const values = visible.map(point => chartValue(point, type)).filter((value): value is number => value != null)
  const max = Math.max(1, ...values)
  const axisMax = type === 'count' ? Math.max(1, Math.ceil(max)) : max >= 100 ? Math.ceil(max / 10) * 10 : Math.ceil(max)
  const labelStep = visible.length <= 7 ? 1 : visible.length <= 30 ? 3 : Math.ceil(visible.length / 6)
  const chosen = visible.find(point => point.key === selected)
  const chosenValue = chosen ? chartValue(chosen, type) : null
  const start = (input: unknown) => {
    const point = (input as { touches?: { pageX: number; pageY: number }[] }).touches
    touch.current = point?.length === 1 ? { x: point[0]!.pageX, y: point[0]!.pageY } : null
  }
  const end = (input: unknown) => {
    const event = input as { changedTouches?: { pageX: number; pageY: number }[]; touches?: unknown[] }
    const from = touch.current
    touch.current = null
    const point = event.changedTouches?.length === 1 ? event.changedTouches[0] : null
    if (!from || !point || event.touches?.length) return
    const dx = point.pageX - from.x, dy = point.pageY - from.y
    if (Math.abs(dx) < 40 || Math.abs(dx) < Math.abs(dy)) return
    setSelected(null)
    setPage(value => Math.max(0, Math.min(CHART_ORDER.length - 1, value + (dx < 0 ? 1 : -1))))
  }
  return <View className="card drive-charts-card">
    <View className="drive-chart-page" onTouchStart={start} onTouchEnd={end}>
      <View className="drive-chart-heading"><VisualIcon name={spec.icon} /><Text>{spec.title(granularity)}</Text></View>
      <Text className="drive-chart-unit">单位：{spec.unit}{partial ? ' · 部分数据' : ''}</Text>
      {chosen && chosenValue != null ? <Text className="drive-chart-tooltip">{chosen.label} · {formatValue(chosenValue, type)} {spec.unit}</Text> : null}
      {visible.length ? <View className="drive-chart-body">
        <View className="drive-chart-yaxis"><Text>{String(Math.round(axisMax))}</Text><Text>0</Text></View>
        <View className="drive-chart-plot">
          <View className="drive-chart-gridline drive-chart-gridline-top" />
          <View className="drive-chart-gridline drive-chart-gridline-bottom" />
          <View className="drive-chart-bars">{visible.map((point, index) => {
            const value = chartValue(point, type)
            return <View key={`${type}-${point.key}`} className="drive-chart-slot" onClick={() => setSelected(selected === point.key ? null : point.key)}>
              <View className="drive-chart-track">
                <View className={`drive-chart-bar${selected === point.key ? ' is-selected' : ''}`} style={{ height: `${value == null || value === 0 ? 0 : Math.max(3, value / axisMax * 100)}%` }} />
              </View>
              {index % labelStep === 0 ? <Text className="drive-chart-label">{point.label}</Text> : <Text className="drive-chart-label" />}
            </View>
          })}</View>
        </View>
      </View> : <Text className="muted drive-chart-empty">当前范围没有可用数据</Text>}
    </View>
    <View className="drive-chart-dots">{CHART_ORDER.map((item, index) =>
      <View key={item} className={`drive-chart-dot${index === page ? ' is-selected' : ''}`} onClick={() => { setSelected(null); setPage(index) }} ariaLabel={`图表 ${index + 1}`} />)}</View>
  </View>
}