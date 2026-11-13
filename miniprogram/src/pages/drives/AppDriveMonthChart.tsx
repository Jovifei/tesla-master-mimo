import { ScrollView, Text, View } from '@tarojs/components'
import type { Drive } from '../../services/types'
import { appDriveMonthCounts } from './drive_parity'

export default function AppDriveMonthChart({ rows, partial }: { rows: readonly Drive[]; partial: boolean }) {
  const months = appDriveMonthCounts(rows)
  if (!months.length) return null
  const max = Math.max(...months.map(item => item.count), 1)
  return <View className="card app-drive-month-chart">
    <Text className="section-title">行程数/月</Text>
    {partial ? <Text className="muted">数据未完整加载；≥ 表示已知最少值，— 表示未知。</Text> : null}
    <ScrollView scrollX className="app-drive-month-scroll" scrollLeft={99999}>
      <View className="app-drive-month-bars">{months.map(({ month, count }) => {
        const value = partial ? (count > 0 ? `≥${count}` : '—') : String(count)
        const description = partial ? (count > 0 ? `至少 ${count} 次` : '未知') : `${count} 次`
        return <View className="app-drive-month-column" key={month} ariaLabel={`${month} 行程数 ${description}`}>
          <Text className="app-drive-month-value">{value}</Text>
          <View className="app-drive-month-track"><View className="app-drive-month-bar" style={{ height: `${count / max * 100}%` }} /></View>
          <Text className="app-drive-month-label">{Number(month.slice(5))}月</Text>
        </View>
      })}</View>
    </ScrollView>
  </View>
}
