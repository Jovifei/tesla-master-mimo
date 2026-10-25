import { displayNumber } from '../MetricValue'
import { sameEnergyInstant } from '../../domain/drive_energy'
import type { CarStatus } from '../../services/types'

export const dashboardNumber = (value: number | null | undefined, digits = 1): string => {
 if (value == null || !Number.isFinite(value)) return '暂无数据'
 const [whole, fraction] = displayNumber(value, digits).split('.')
 const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
 return fraction === undefined ? grouped : `${grouped}.${fraction}`
}
export const dashboardMetric = (value: number | null | undefined, unit: string, digits = 1): string => `${dashboardNumber(value, digits)}${unit ? ` ${unit}` : ''}`
/** Missing observation shows the readiness wording, never a fake reason; a real 0 °C still renders. */
export const dashboardTemperature = (value: number | null | undefined): string => value == null || !Number.isFinite(value) ? '等待车辆' : `${value.toFixed(1)}°C`
export function dashboardRange(status: CarStatus) { return status.ratedRange ?? status.idealRange ?? status.estimatedRange ?? null }
export function dashboardShift(value: string | null | undefined): string | null { const shift = value?.trim().toUpperCase(); return shift && ['P', 'R', 'N', 'D'].includes(shift) ? shift : null }
export function dashboardState(value: string | null | undefined): string { return ({ online: '在线', driving: '行驶', charging: '充电', asleep: '休眠', suspended: '休眠', offline: '离线', waking: '连接中', parked: '驻车' } as Record<string, string>)[value?.toLowerCase() ?? ''] ?? '状态未知' }
export function dashboardPower(value: number | null | undefined) { return { label: value != null && value > 0 ? '消耗' : value != null && value < 0 ? '回收' : '功率', value: value == null ? null : Math.abs(value) } }
export function dashboardRemaining(hours: number | null | undefined): string {
  if (hours == null || !Number.isFinite(hours) || hours < 0) return '暂无数据'
  const minutes = Math.floor(hours * 60)
  return minutes >= 60 ? `${Math.floor(minutes / 60)}小时${minutes % 60}分钟` : `${minutes}分钟`
}
/** Charging HUD mirror of App DashboardScreen `Xh Ym` / `Xm`. */
export function hudRemaining(hours: number | null | undefined): string {
  if (hours == null || !Number.isFinite(hours) || hours < 0) return '暂无数据'
  const minutes = Math.floor(hours * 60)
  return minutes >= 60 ? `${Math.floor(minutes / 60)}h ${minutes % 60}m` : `${minutes}m`
}
/** Bottom charging card keeps the App's raw hours rendering (`${it}h`). */
export function rawChargeHours(hours: number | null | undefined): string {
  if (hours == null || !Number.isFinite(hours) || hours < 0) return '暂无数据'
  return `${hours}h`
}
/** App DashboardScreen.readinessDashboardValue wording for missing per-wheel values. */
export function dashboardTpmsPlaceholder(readiness: { status: string; lastObservedAt: string | null }): string {
  const label = (({ available: '可用', collecting: '收集中', waiting_vehicle: '等待车辆', pairing_required: '需要车辆钥匙确认', permission_required: '需要 Tesla 授权', telemetry_not_configured: '暂不可用', telemetry_error: '暂不可用', billing_blocked: '暂不可用', unsupported: '不支持', unknown: '暂不可用' }) as Record<string, string>)[readiness.status] ?? '暂不可用'
  return readiness.lastObservedAt ? `${label} · 最近观测：${readiness.lastObservedAt}` : label
}
export function dashboardSource(source: string | null | undefined): string { return ({ live_mqtt: '车辆遥测', telemetry_mqtt: '车辆遥测', fleet_api: 'Fleet API', fleet_telemetry: 'Fleet 遥测', fleet_telemetry_native: 'Fleet 遥测', fleet_native: 'Fleet 遥测', mqtt_latest: '最近遥测快照', database_latest: '历史数据库', teslamate_api: 'TeslaMate', teslamate: 'TeslaMate', teslamate_archive: 'TeslaMate 归档', mock_fixture: '合成测试数据' } as Record<string, string>)[source?.trim().toLowerCase() ?? ''] ?? '来源未标明' }
function reportMillis(value: string | null | undefined): number | null {
  if (!value || !sameEnergyInstant(value, value)) return null
  const time = Date.parse(value.replace(/(\.\d{3})\d+/, '$1'))
  return Number.isFinite(time) ? time : null
}
export function dashboardReportTime(value: string | null | undefined): string {
  const time = reportMillis(value)
  if (time == null) return '报告时间未提供'
  const date = new Date(time), pad = (number: number) => String(number).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
export function dashboardSnapshot(status: CarStatus | null, cached: boolean, now = Date.now()) {
  if (cached) return { label: '缓存快照', kind: 'cached' }
  const source = status?.source?.trim().toLowerCase(), at = reportMillis(status?.observedAt)
  const fields = Object.values(status?.fieldSources ?? {}).map(value => value.trim().toLowerCase()).filter(Boolean)
  const mixed = new Set(fields).size > 1 || Boolean(source && fields.some(value => value !== source))
  const suffix = mixed ? ' · 混合来源' : ''
  if (!source || dashboardSource(source) === '来源未标明') return { label: `来源未知${suffix}`, kind: 'unknown' }
  if (at == null) return { label: `时间未知${suffix}`, kind: 'unknown' }
  if (status?.state?.toLowerCase() === 'asleep' || status?.state?.toLowerCase() === 'suspended') return { label: `车辆休眠${suffix}`, kind: 'asleep' }
  const live = ['live_mqtt', 'telemetry_mqtt', 'fleet_api', 'fleet_telemetry', 'fleet_telemetry_native', 'fleet_native'].includes(source)
  const fresh = at <= now + 5000 && now - at <= 120000
  if (live && fresh && (!fields.length || fields.includes(source))) return { label: `实时数据${suffix}`, kind: 'live' }
  return { label: `${at > now + 5000 ? '时间待核对' : '历史快照'}${suffix}`, kind: 'history' }
}

/** DashboardScreen.vehicleProfileSubtitle / VehicleStatusPresentation.vehicleTrimFor. */
export function dashboardVehicleSubtitle(model: string | null | undefined, trim: string | null | undefined): string | null {
 const normalizedModel = model?.trim().toUpperCase().replace(/^MODEL\s*/, '')
 const modelLabel = normalizedModel && ['3','Y','S','X'].includes(normalizedModel) ? `Model ${normalizedModel}` : null
 const normalizedTrim = trim?.trim().toUpperCase()
 const trimLabel = !normalizedTrim ? null : normalizedTrim.startsWith('P') || normalizedTrim.includes('PERFORMANCE') ? '性能版' : normalizedTrim === '50' || normalizedTrim.includes('STANDARD') ? '标准续航版' : ['74','74D'].includes(normalizedTrim) || normalizedTrim.includes('LONG') ? '长续航版' : null
 return [modelLabel, trimLabel].filter(Boolean).join(' · ') || null
}
