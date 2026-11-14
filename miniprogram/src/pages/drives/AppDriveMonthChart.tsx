import { ScrollView, Text, View } from '@tarojs/components'
import type { Drive } from '../../services/types'
import { appDriveMonthCounts } from './drive_parity'
import { historyDateTime } from '../../domain/history_query'

/** App drives chart (DrivesViewModel.determineGranularity): <=30d renders
 * 行程数/天, <=90d 行程数/周 (Monday-start weeks), else 行程数/月. */
export type DriveChartGranularity = 'day' | 'week' | 'month'
export default function AppDriveMonthChart({ rows, partial, granularity = 'month' }: { rows: readonly Drive[]; partial: boolean; granularity?: DriveChartGranularity }) {
  const buckets = granularity === 'day'
    ? (() => {
      const counts = new Map<string, number>()
      for (const row of rows) {
        const date = historyDateTime(row.startDate)
        if (date === '暂无日期') continue
        counts.set(date.slice(0, 10), (counts.get(date.slice(0, 10)) ?? 0) + 1)
      }
      if (!counts.size) return [] as { month: string; count: number }[]
      const first = [...counts.keys()].sort()[0]!
      const last = new Date(); const pad = (n: number) => String(n).padStart(2, '0')
      const lastKey = `${last.getFullYear()}-${pad(last.getMonth() + 1)}-${pad(last.getDate())}`
      const keys = [...counts.keys(), lastKey].sort()
      const out: { month: string; count: number }[] = []
      let cursor = new Date(`${first}T00:00:00`)
      const end = new Date(`${lastKey}T00:00:00`)
      while (cursor <= end) {
        const pad = (n: number) => String(n).padStart(2, '0')
        const key = `${cursor.getFullYear()}-${pad(cursor.getMonth() + 1)}-${pad(cursor.getDate())}`
        out.push({ month: key, count: counts.get(key) ?? 0 })
        cursor = new Date(cursor.getFullYear(), cursor.getMonth(), cursor.getDate() + 1)
      }
      return out
    })()
    : granularity === 'week'
      ? (() => {
        const counts = new Map<string, number>()
        for (const row of rows) {
          const date = historyDateTime(row.startDate)
          if (date === '暂无日期') continue
          const [year, month, day] = date.slice(0, 10).split('-').map(Number)
          const monday = new Date(year, month - 1, day)
          monday.setDate(monday.getDate() - ((monday.getDay() + 6) % 7))
          const pad = (n: number) => String(n).padStart(2, '0')
          counts.set(`${monday.getFullYear()}-${pad(monday.getMonth() + 1)}-${pad(monday.getDate())}`, (counts.get(`${monday.getFullYear()}-${pad(monday.getMonth() + 1)}-${pad(monday.getDate())}`) ?? 0) + 1)
        }
        if (!counts.size) return [] as { month: string; count: number }[]
        return [...counts.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([month, count]) => ({ month, count }))
      })()
      : appDriveMonthCounts(rows)
  if (!buckets.length) return null
  const max = Math.max(...buckets.map(item => item.count), 1)
  const weekLabel = (key: string) => {
    const start = new Date(`${key}T00:00:00`); start.setDate(start.getDate() + 6)
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${Number(key.slice(5, 7))}/${Number(key.slice(8, 10))}–${start.getMonth() + 1}/${start.getDate()}`
  }
  return <View className="card app-drive-month-chart">
    <Text className="section-title">{granularity === 'day' ? '行程数/天' : granularity === 'week' ? '行程数/周' : '行程数/月'}</Text>
    <Text className="muted">单位： 次</Text>
    {partial ? <Text className="muted">部分记录缺少数据</Text> : null}
    <ScrollView scrollX className="app-drive-month-scroll" scrollLeft={99999}>
      <View className="app-drive-month-bars">{buckets.map(({ month, count }) => {
        const value = partial ? (count > 0 ? `≥${count}` : '—') : String(count)
        const description = partial ? (count > 0 ? `至少 ${count} 次` : '未知') : `${count} 次`
        return <View className="app-drive-month-column" key={month} ariaLabel={`${month} 行程数 ${description}`}>
          <Text className="app-drive-month-value">{value}</Text>
          <View className="app-drive-month-track"><View className="app-drive-month-bar" style={{ height: `${count / max * 100}%` }} /></View>
          <Text className="app-drive-month-label">{granularity === 'day' ? `${Number(month.slice(5, 7))}/${Number(month.slice(8, 10))}` : granularity === 'week' ? weekLabel(month) : `${Number(month.slice(5))}月`}</Text>
        </View>
      })}</View>
    </ScrollView>
  </View>
}
