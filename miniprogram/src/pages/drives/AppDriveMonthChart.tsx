import { ScrollView, Text, View } from '@tarojs/components'
import type { Drive } from '../../services/types'
import { appDriveMonthCounts } from './drive_parity'

export default function AppDriveMonthChart({ rows, partial }: { rows: readonly Drive[]; partial: boolean }) {
  const months = appDriveMonthCounts(rows)
  if (!months.length) return null
  const max = Math.max(...months.map(item => item.count), 1)
  return <View className="card app-drive-month-chart">
    <Text className="section-title">▣ 行程数/月</Text>
    <Text className="muted">单位：次</Text>
    <ScrollView scrollX className="app-drive-month-scroll" scrollLeft={99999}>
      <View className="app-drive-month-bars">{months.map(({ month, count }) =>
        <View className="app-drive-month-column" key={month}>
          <Text className="app-drive-month-value">{count}</Text>
          <View className="app-drive-month-track"><View className="app-drive-month-bar" style={{ height: `${count / max * 100}%` }} /></View>
          <Text className="app-drive-month-label">{Number(month.slice(5))}月</Text>
        </View>)}</View>
    </ScrollView>
    {partial ? <Text className="muted">仅统计当前已读分页，剩余历史尚未纳入。</Text> : null}
  </View>
}
