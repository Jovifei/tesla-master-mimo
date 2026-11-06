import type { CarStatus } from '../services/types'

export type CommandTier = 'LOW' | 'MID' | 'HIGH'

export type CommandUi = {
  name: string
  tier: CommandTier
  needsConfirmation: boolean
}

export const VEHICLE_COMMAND_CATALOG: CommandUi[] = [
  { name: 'lock', tier: 'LOW', needsConfirmation: false },
  { name: 'unlock', tier: 'HIGH', needsConfirmation: true },
  { name: 'flash', tier: 'LOW', needsConfirmation: false },
  { name: 'honk', tier: 'LOW', needsConfirmation: false },
  { name: 'climate_on', tier: 'LOW', needsConfirmation: false },
  { name: 'climate_off', tier: 'LOW', needsConfirmation: false },
  { name: 'set_climate_temp', tier: 'LOW', needsConfirmation: false },
  { name: 'charge_port_open', tier: 'LOW', needsConfirmation: false },
  { name: 'charge_port_close', tier: 'LOW', needsConfirmation: false },
  { name: 'charge_start', tier: 'MID', needsConfirmation: false },
  { name: 'charge_stop', tier: 'MID', needsConfirmation: false },
  { name: 'set_charge_limit', tier: 'MID', needsConfirmation: false },
  { name: 'windows_vent', tier: 'MID', needsConfirmation: false },
  { name: 'windows_close', tier: 'MID', needsConfirmation: false },
  { name: 'sentry_on', tier: 'MID', needsConfirmation: false },
  { name: 'sentry_off', tier: 'MID', needsConfirmation: false },
]

export function offeredCommands(status: CarStatus | null | undefined): CommandUi[] {
  if (!status) return []
  // If authorization not evaluated (null/undefined), default offer full catalog
  if (status.commandAuthorization === undefined || status.commandAuthorization === null) {
    return VEHICLE_COMMAND_CATALOG
  }
  if (status.commandAuthorization.ready !== true) {
    return []
  }
  return VEHICLE_COMMAND_CATALOG
}

export function needsCommandReauthorization(status: CarStatus | null | undefined): boolean {
  if (!status?.commandAuthorization) return false
  return status.commandAuthorization.ready === false
}

export function commandLabel(name: string): string {
  switch (name) {
    case 'lock': return '上锁'
    case 'unlock': return '解锁'
    case 'flash': return '闪灯'
    case 'honk': return '鸣笛'
    case 'climate_on': return '打开空调'
    case 'climate_off': return '关闭空调'
    case 'set_climate_temp': return '设置温度'
    case 'charge_port_open': return '打开充电口'
    case 'charge_port_close': return '关闭充电口'
    case 'charge_start': return '开始充电'
    case 'charge_stop': return '停止充电'
    case 'set_charge_limit': return '设置充电上限'
    case 'windows_vent': return '车窗通风'
    case 'windows_close': return '关闭车窗'
    case 'sentry_on': return '打开哨兵'
    case 'sentry_off': return '关闭哨兵'
    default: return '命令'
  }
}

export function commandErrorMessage(errorClass: string | null | undefined): string {
  switch (errorClass) {
    case 'commands_disabled': return '车辆控制尚未开启。'
    case 'scope_missing': return '授权范围不足，请重新授权。'
    case 'confirmation_required': return '该命令需要先确认。'
    case 'rate_limited': return '今日命令次数已达上限，请明天再试。'
    case 'vehicle_moving': return '车辆行驶中，无法执行该命令。'
    case 'not_plugged_in': return '车辆未连接充电枪。'
    case 'key_not_paired': return '虚拟钥匙尚未与车辆配对。'
    case 'invalid_params': return '命令参数不合法。'
    default: return '命令执行失败，请稍后重试。'
  }
}
