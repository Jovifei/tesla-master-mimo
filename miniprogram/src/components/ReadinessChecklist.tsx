import { Text, View } from '@tarojs/components'
import { toReadinessView } from '../domain/readiness'

type Props = {
  status?: string | null
}

export default function ReadinessChecklist({ status }: Props) {
  const readiness = toReadinessView(status)
  return (
    <View className="card">
      <Text className="section-title">数据准备</Text>
      <View className="status">
        <Text className="status-label">微信会话</Text>
        <Text className="status-value">需由小程序服务端确认</Text>
      </View>
      <View className="status">
        <Text className="status-label">Tesla 授权与车辆钥匙</Text>
        <Text className="status-value">需官方页面确认</Text>
      </View>
      <View className="status">
        <Text className="status-label">Fleet Telemetry</Text>
        <Text className="status-value">{readiness.label}</Text>
      </View>
      <Text className="muted">{readiness.detail}</Text>
    </View>
  )
}
