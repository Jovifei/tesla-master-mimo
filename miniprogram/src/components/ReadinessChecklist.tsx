import { Text, View } from '@tarojs/components'
import { readinessViewFor, toReadinessView } from '../domain/readiness'
import { sourceLabel } from '../domain/data_labels'
import type { ReadinessItem, TelemetryPairing } from '../services/types'
import VisualIcon, { type VisualIconName } from './VisualIcon'

const sections: Record<string, { label: string; icon: VisualIconName }> = {
  live_status: { label: '实时车辆状态', icon: 'vehicle' }, location: { label: '位置报告', icon: 'route' },
  tpms: { label: '实时胎压状态', icon: 'speed' }, drives: { label: '行程历史', icon: 'route' },
  charges: { label: '充电历史', icon: 'energy' }, battery_health: { label: '电池健康', icon: 'battery' },
}
function reportTime(value: string | null): string {
  return value && /^\d{4}-\d{2}-\d{2}T/.test(value) && Number.isFinite(Date.parse(value)) ? value : '未提供'
}

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
      <View className="visual-heading"><VisualIcon name="cloud" /><Text className="section-title">数据准备</Text></View>
      <Text className="muted">逐项查看服务端状态，实时可用不代表历史已保存。</Text>
      <View className="status">
        <View className="visual-heading"><VisualIcon name="account" /><Text className="status-label">微信会话</Text></View>
        <Text className="status-value">{sessionReady ? '本机会话可用' : '需要微信登录'}</Text>
      </View>
      <View className="status">
        <View className="visual-heading"><VisualIcon name="cloud" /><Text className="status-label">持续采集配置</Text></View>
        <Text className="status-value">{pairing?.config_synced == null ? '尚未确认' : pairing.config_synced ? '服务端已配置' : '尚未配置'}</Text>
      </View>
      <View className="status">
        <View className="visual-heading"><VisualIcon name="shield" /><Text className="status-label">Tesla 授权与车辆钥匙</Text></View>
        <Text className="status-value">{pairingLabel}</Text>
      </View>
      <View className="status">
        <View className="visual-heading"><VisualIcon name="cloud" /><Text className="status-label">Fleet Telemetry</Text></View>
        <Text className="status-value">{readiness.label}</Text>
      </View>
      <Text className="muted">{readiness.detail}</Text>
      {readiness.source ? <Text className="muted">来源：{sourceLabel(readiness.source)}</Text> : null}
      {readiness.lastObservedAt ? <Text className="muted">最近观测：{reportTime(readiness.lastObservedAt)}</Text> : null}
      {items.length ? <Text className="readiness-count">服务端返回 {items.length} 项 · 数据可用 {items.filter(item => toReadinessView(item.status).ready).length} 项</Text> : null}
      {items.filter(item => item.key !== 'telemetry').map((item, index) => {
        const state = toReadinessView(item.status, item)
        const section = sections[item.key] ?? { label: '其他数据状态', icon: 'cloud' as const }
        return <View className="readiness-item" key={`${item.key}-${index}`}>
          <View className="status"><View className="visual-heading"><VisualIcon name={section.icon} /><Text className="status-label">{section.label}</Text></View><Text className={`state-pill ${state.ready ? 'state-pill-ready' : ''}`}>{state.label}</Text></View>
          <Text className="muted">{state.detail}</Text>
          <Text className="metric-source">来源：{sourceLabel(state.source)}</Text>
          <Text className="metric-source">报告时间：{reportTime(state.lastObservedAt)}</Text>
          {item.key === 'tpms' ? <Text className="muted">此项仅反映服务端实时状态；小程序胎压历史尚未接入。</Text> : null}
        </View>
      })}
    </View>
  )
}
