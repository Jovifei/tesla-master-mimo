export type ReadinessStatus =
  | 'available'
  | 'collecting'
  | 'waiting_vehicle'
  | 'pairing_required'
  | 'permission_required'
  | 'telemetry_not_configured'
  | 'telemetry_error'
  | 'billing_blocked'
  | 'unknown'

export type ReadinessView = {
  status: ReadinessStatus
  label: string
  detail: string
  ready: boolean
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
  unknown: '没有足够证据判断数据是否可用。',
}

export function normalizeReadinessStatus(value: unknown): ReadinessStatus {
  if (typeof value !== 'string') return 'unknown'
  const normalized = value.trim().toLowerCase() as ReadinessStatus
  return normalized in labels ? normalized : 'unknown'
}

export function toReadinessView(status: unknown): ReadinessView {
  const normalized = normalizeReadinessStatus(status)
  return {
    status: normalized,
    label: labels[normalized],
    detail: details[normalized],
    ready: normalized === 'available',
  }
}
