import type { Car, CarStatus, ReadinessItem, TelemetryPairing } from '../services/types'
import type { HistoryCacheEvidence } from '../services/history_cache'
import { readinessViewFor } from './readiness'

export type EvidenceState = 'PASS' | 'UNKNOWN' | 'WARNING' | 'WAITING' | 'UNSUPPORTED'
export type DiagnosticStep = {
  key: 'authorization' | 'identity' | 'receive' | 'qualification' | 'history' | 'display'
  label: string; state: EvidenceState; summary: string; source: string; time: string | null; action: string; detail: string
  freshness?: 'reported' | 'stale_or_skewed' | 'unknown'
}
export type DiagnosticInput = {
  sessionReady?: boolean; car?: Car | null; status?: CarStatus | null; cached?: boolean; onlineError?: boolean
  items?: ReadinessItem[]; pairing?: TelemetryPairing | null; history?: HistoryCacheEvidence | null
  now?: number
}

/** Every step is independent. A server readiness flag cannot qualify or persist client data. */
export function diagnosticSteps(input: DiagnosticInput = {}): DiagnosticStep[] {
  const readiness = readinessViewFor(input.items ?? [])
  const liveReadiness = readinessViewFor(input.items ?? [], 'live_status')
  const permissionBlocked = (input.items ?? []).some(item => item.status?.trim().toLowerCase() === 'permission_required') || input.pairing?.error_class === 'permission_required'
  const history = input.history
  const identity = Boolean(input.sessionReady && input.car?.stableId.trim())
  const remoteIdentity = identity && input.car?.remoteVerified !== false
  const observationKeys: ReadonlyArray<keyof CarStatus> = ['state', 'batteryLevel', 'ratedRange', 'idealRange', 'estimatedRange', 'odometer', 'location', 'geofence', 'locked', 'sentryMode', 'windowsOpen', 'doorsOpen', 'trunkOpen', 'frunkOpen', 'isClimateOn', 'isPreconditioning', 'insideTemp', 'outsideTemp', 'pluggedIn', 'speed', 'power', 'shiftState', 'heading', 'chargingState', 'chargeEnergyAdded', 'chargerPower', 'chargerVoltage', 'chargerActualCurrent', 'timeToFullChargeHours', 'chargeLimitSoc', 'chargerPhases', 'chargeCurrentRequest', 'chargeCurrentRequestMax', 'chargePortDoorOpen']
  const fields = input.status && (observationKeys.some(key => input.status![key] != null) || Object.values(input.status.tpms?.wheels ?? {}).some(row => row.pressureBar != null))
  const received = identity && Boolean(fields)
  const time = input.status?.observedAt ?? null
  const observed = time && Number.isFinite(Date.parse(time))
  const age = observed ? (input.now ?? Date.now()) - Date.parse(time!) : null
  const stale = age !== null && (age > 24 * 60 * 60 * 1000 || age < -5 * 60 * 1000)
  const cache = Boolean(input.cached)
  const permissionRequired = permissionBlocked
  return [
    { key: 'authorization', label: '1 · 授权', state: input.sessionReady && (input.pairing?.status === 'pairing_required' || permissionRequired) ? 'WARNING' : input.sessionReady && input.pairing?.status === 'available' ? 'PASS' : 'UNKNOWN',
      summary: input.sessionReady ? '本机会话可用' : '微信会话尚未确认', source: '本机会话', time: null,
      action: input.sessionReady ? '在“更多”查看关联状态' : '前往“更多”完成登录',
      detail: `本机会话不代表 Tesla 权限或车辆钥匙已确认。${input.pairing?.status === 'pairing_required' ? '车辆钥匙需要确认。' : ''}服务端授权证据：${permissionBlocked ? '需要重新授权' : '仅保留当前服务端独立状态'}。` },
    { key: 'identity', label: '2 · 车辆身份', state: remoteIdentity ? 'PASS' : identity ? 'WARNING' : 'UNKNOWN',
      summary: remoteIdentity ? '当前响应已关联稳定车辆身份' : identity ? '仅有本机缓存车辆身份' : '稳定车辆身份尚未确认',
      source: remoteIdentity ? '当前车辆列表' : identity ? '本机车辆缓存' : '无车辆身份', time: null,
      action: '核对当前账号与所选车辆', detail: '证据按服务地址、账号、稳定车辆身份和会话代次隔离；数值车辆编号不能单独证明身份。' },
    { key: 'receive', label: '3 · 数据接收', state: received ? cache || !observed ? 'WARNING' : 'PASS' : liveReadiness.status === 'waiting_vehicle' ? 'WAITING' : 'UNKNOWN',
      freshness: cache || !observed ? 'unknown' : stale ? 'stale_or_skewed' : 'reported',
      summary: received ? cache ? '读取到缓存快照，在线状态尚未确认' : stale ? '当前响应含状态，报告时间较早或存在时钟偏差' : observed ? '当前响应含状态与报告时间' : '当前响应含状态，报告时间未知' : liveReadiness.status === 'waiting_vehicle' ? '服务端报告等待车辆，尚无观测证据' : '尚无可展示的状态观测',
      source: received ? cache ? '本机状态缓存' : '当前状态响应' : '无状态证据', time,
      action: input.onlineError ? '检查连接后手动刷新车辆' : liveReadiness.status === 'waiting_vehicle' ? '等待车辆下次正常上线' : '查看状态报告时间',
      detail: `${input.onlineError ? '本次在线读取失败；缓存不代表本次在线成功。' : ''}收到状态不证明逐字段新鲜度、质量合格或历史落库。服务端遥测状态：${readiness.label}；实时状态准备：${liveReadiness.label}。` },
    { key: 'qualification', label: '4 · 质量合格', state: identity && history?.qualified ? 'PASS' : identity && history?.quarantined ? 'WARNING' : 'UNKNOWN',
      summary: identity && history?.qualified ? `本机历史摘要含 ${history.qualified} 条带质量标记的记录` : '当前状态缺少独立质量合格证据',
      source: identity && history?.rows ? '本机历史缓存质量标记' : '未提供独立质量证据', time: identity ? history?.savedAt ?? null : null,
      action: '在行程或充电中核对记录质量', detail: '仅 observed / derived 历史摘要参与此处判断；不完整或隔离记录不能当作合格。此项不能证明当前状态合格、Android Room 或服务器持久化。' },
    { key: 'history', label: '5 · 历史保存', state: identity && history?.rows ? 'PASS' : 'UNKNOWN',
      summary: identity && history?.rows ? `当前作用域本机缓存保存 ${history.rows} 条历史摘要` : '当前作用域未发现本机历史摘要',
      source: '本机行程与充电缓存', time: identity ? history?.savedAt ?? null : null,
      action: '查看已有行程或充电历史', detail: '只读取已存在的当前版本缓存，不迁移、不补采。缓存存在不能证明历史完整或服务器已落库；胎压仅有本机前台观测历史，不代表 App 已保存的完整历史。' },
    { key: 'display', label: '6 · 展示与通知', state: received ? 'WARNING' : 'UNKNOWN',
      summary: received ? cache ? '页面展示缓存状态' : '页面展示当前状态响应' : '页面保留未知占位',
      source: '当前车辆页面', time, action: '查看页面中的来源与报告时间',
      detail: '展示证据仅覆盖当前页面。行程与充电有持久去重的前台提示；小程序订阅通知尚未接入（UNSUPPORTED），不能推断 Android 通知权限、送达或告警链路通过。' },
  ]
}
