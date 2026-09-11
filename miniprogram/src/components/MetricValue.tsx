import { Text, View } from '@tarojs/components'

type Props = {
  label: string
  value: string
  source?: string | null
}

export default function MetricValue({ label, value, source }: Props) {
  return (
    <View className="metric">
      <Text className="metric-label">{label}</Text>
      <Text className="metric-value">{value}</Text>
      {source ? <Text className="metric-source">来源：{source}</Text> : null}
    </View>
  )
}
