import type { ReadinessItem } from '../services/types'

export type ReadinessStatus =
  | 'available'
  | 'collecting'
  | 'waiting_vehicle'
  | 'pairing_required'
  | 'permission_required'
  | 'telemetry_not_configured'
  | 'telemetry_error'
  | 'billing_blocked'
  | 'unsupported'
  | 'unknown'

export type ReadinessView = {
  status: ReadinessStatus
  label: string
  detail: string
  ready: boolean
  messageKey: string | null
  action: string | null
  source: string | null
  lastObservedAt: string | null
}

const labels: Record<ReadinessStatus, string> = {
  available: '数据可用',
  collecting: '正在采集',
  waiting_vehicle: '等待车辆',
  pairing_required: '需要车辆钥匙确认',
  permission_required: '需要重新授权',
  telemetry_not_configured: '尚未配置遥测',
  telemetry_error: '遥测配置异常',
  billing_blocked: '服务端计费状态阻断',
  unsupported: '暂不支持',
  unknown: '状态未知',
}

const details: Record<ReadinessStatus, string> = {
  available: '已收到服务端可用的最新观测。',
  collecting: '车辆已配置，等待新的真实观测。',
  waiting_vehicle: '车辆需要在线或唤醒后才能采用配置。',
  pairing_required: '请在 Tesla 官方 App 中确认车辆钥匙。',
  permission_required: '请返回 Tesla 官方页面补充缺失权限。',
  telemetry_not_configured: '服务端尚未完成 Fleet Telemetry 配置。',
  telemetry_error: '保留错误分类，稍后可安全重试。',
  billing_blocked: '服务端报告了计费或权限阻断。',
  unsupported: '服务端没有提供这项能力。',
  unknown: '没有足够证据判断数据是否可用。',
}

export function normalizeReadinessStatus(value: unknown): ReadinessStatus {
  if (typeof value !== 'string') return 'unknown'
  const normalized = value.trim().toLowerCase() as ReadinessStatus
  return normalized in labels ? normalized : 'unknown'
}

export function findReadinessItem(items: readonly ReadinessItem[], key: string): ReadinessItem | null {
  return items.find(item => item.key === key) ?? null
}

export function readinessStatusFor(items: readonly ReadinessItem[], key = 'telemetry'): ReadinessStatus {
  return normalizeReadinessStatus(findReadinessItem(items, key)?.status)
}

export function toReadinessView(status: unknown, item?: ReadinessItem | null): ReadinessView {
  const normalized = normalizeReadinessStatus(status)
  return {
    status: normalized,
    label: labels[normalized],
    detail: item?.message || details[normalized],
    ready: normalized === 'available',
    messageKey: item?.message_key ?? null,
    action: item?.action ?? null,
    source: item?.source ?? null,
    lastObservedAt: item?.last_observed_at ?? null,
  }
}

export function readinessViewFor(items: readonly ReadinessItem[], key = 'telemetry'): ReadinessView {
  const item = findReadinessItem(items, key)
  return toReadinessView(item?.status, item)
}
