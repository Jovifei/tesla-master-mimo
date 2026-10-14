import type { EnergyMetric } from '../services/types'

function label(values: Record<string, string>, key: string | null | undefined, fallback: string): string {
  return key && Object.prototype.hasOwnProperty.call(values, key) ? values[key] : fallback
}
export function sourceLabel(source: string | null | undefined): string {
  return label({ teslamate: 'TeslaMate', teslamate_archive: 'TeslaMate 归档', telemetry_mqtt: '车辆遥测',
    fleet_telemetry: 'Fleet', fleet_telemetry_native: 'Fleet', fleet_native: 'Fleet', api: 'API 报告', legacy_api: '历史 API 报告' }, source, '来源未标明')
}
export function energyMethodLabel(method: string | null | undefined): string {
  return label({ api_reported_net: '服务端净能耗报告', power_integral: '行驶功率积分估算',
    nominal_energy_remaining_delta: '电量端点估算', soc_capacity_estimate: '电量百分比 × 电池容量估算' }, method, '尚无合格能耗依据')
}
export function capacityLabel(basis: EnergyMetric['capacity_basis']): string {
  return label({ api_reported_current_capacity: '服务端提供的当前容量', provided_capacity: '明确提供的容量',
    model_trim_assumption: '车型配置容量估算', model_assumption: '车型容量估算', default_assumption: '默认容量估算' }, basis, '容量依据未提供')
}
export function qualityLabel(quality: string | null | undefined): string {
  return label({ observed: '真实记录', derived: '计算记录', estimated: '估算记录', incomplete: '记录不完整',
    reported: '报告值', unknown: '数据完整性未确认' }, quality, '数据完整性未确认')
}
export function availabilityLabel(state: string | null | undefined): string {
  return label({ available: '可读取', collecting: '等待采集', empty: '暂无记录', unavailable: '暂不可用',
    not_collected: '尚未采集', blocked: '暂受限', incomplete: '部分数据' }, state, '状态未确认')
}
