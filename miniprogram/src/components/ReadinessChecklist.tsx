import { Text, View } from '@tarojs/components'
import { readinessViewFor } from '../domain/readiness'
import type { ReadinessItem, TelemetryPairing } from '../services/types'

type Props = {
  items?: ReadinessItem[]
  pairing?: TelemetryPairing | null
  sessionReady?: boolean
}

export default function ReadinessChecklist({ items = [], pairing, sessionReady = false }: Props) {
  const readiness = readinessViewFor(items, 'telemetry')
  const pairingLabel = pairing?.status === 'available' ? '服务端报告就绪' : pairing?.status === 'pairing_required' ? '需要确认' : '待服务端确认'
  return (
    <View className="card">
      <Text className="section-title">数据准备</Text>
      <View className="status">
        <Text className="status-label">微信会话</Text>
        <Text className="status-value">{sessionReady ? '本机会话可用' : '需要微信登录'}</Text>
      </View>
      <View className="status">
        <Text className="status-label">持续采集配置</Text>
        <Text className="status-value">{pairing?.config_synced == null ? '尚未确认' : pairing.config_synced ? '服务端已配置' : '尚未配置'}</Text>
      </View>
      <View className="status">
        <Text className="status-label">Tesla 授权与车辆钥匙</Text>
        <Text className="status-value">{pairingLabel}</Text>
      </View>
      <View className="status">
        <Text className="status-label">Fleet Telemetry</Text>
        <Text className="status-value">{readiness.label}</Text>
      </View>
      <Text className="muted">{readiness.detail}</Text>
      {readiness.source ? <Text className="muted">来源：{readiness.source}</Text> : null}
      {readiness.lastObservedAt ? <Text className="muted">最近观测：{readiness.lastObservedAt}</Text> : null}
    </View>
  )
}
