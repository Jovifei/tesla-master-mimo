import { Text, View } from '@tarojs/components'
import { sourceLabel } from '../domain/data_labels'

type Props = {
  label: string
  value: string | number | null
  source?: string | null
}

export default function MetricValue({ label, value, source }: Props) {
  return (
    <View className="metric">
      <Text className="metric-label">{label}</Text>
      <Text className="metric-value">{value == null || value === '' ? '暂无数据' : value}</Text>
      <Text className="metric-source">{sourceLabel(source)}</Text>
    </View>
  )
}
