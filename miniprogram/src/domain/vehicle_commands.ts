import type { CarStatus } from '../services/types'

/**
 * Vehicle command catalog (design 2026-11-05 §6), mirroring the backend
 * command table in deploy/jourvolt-dev-mock/vehicle_commands.go and the
 * Android VehicleCommandCatalog. The backend remains the authority —
 * this module only shapes what the UI offers and validates parameters
 * client-side with the same rules, so the interface never promises a
 * command the backend would reject for a client-side reason.
 */

export type VehicleCommandTier = 'low' | 'mid' | 'high'

export type VehicleCommand = {
  name: string
  tier: VehicleCommandTier
  /** High-tier commands need the confirm flag (and a confirmation UI). */
  needsConfirmation: boolean
  /** Informational preconditions the backend enforces server-side. */
  requiresPluggedIn?: boolean
  parkedOnly?: boolean
}

export const VEHICLE_COMMANDS: readonly VehicleCommand[] = [
  { name: 'lock', tier: 'low', needsConfirmation: false },
  { name: 'unlock', tier: 'high', needsConfirmation: true, parkedOnly: true },
  { name: 'flash', tier: 'low', needsConfirmation: false },
  { name: 'honk', tier: 'low', needsConfirmation: false },
  { name: 'climate_on', tier: 'low', needsConfirmation: false },
  { name: 'climate_off', tier: 'low', needsConfirmation: false },
  { name: 'set_climate_temp', tier: 'low', needsConfirmation: false },
  { name: 'charge_port_open', tier: 'low', needsConfirmation: false },
  { name: 'charge_port_close', tier: 'low', needsConfirmation: false },
  { name: 'charge_start', tier: 'mid', needsConfirmation: false, requiresPluggedIn: true },
  { name: 'charge_stop', tier: 'mid', needsConfirmation: false },
  { name: 'set_charge_limit', tier: 'mid', needsConfirmation: false },
  { name: 'windows_vent', tier: 'mid', needsConfirmation: false, parkedOnly: true },
  { name: 'windows_close', tier: 'mid', needsConfirmation: false, parkedOnly: true },
  { name: 'sentry_on', tier: 'mid', needsConfirmation: false },
  { name: 'sentry_off', tier: 'mid', needsConfirmation: false },
]

export function vehicleCommand(name: string): VehicleCommand | null {
  return VEHICLE_COMMANDS.find(command => command.name === name) ?? null
}

/**
 * Commands the UI may offer for this status snapshot. The whole section
 * stays hidden unless the backend evaluated the user's Tesla scopes
 * and reported them ready — an unevaluated status (no block) or missing
 * scopes means the re-authorization prompt owns the surface instead.
 */
export function offeredCommands(status: CarStatus): readonly VehicleCommand[] {
  return status.commandAuthorization?.ready === true ? VEHICLE_COMMANDS : []
}

/** True when the UI should surface the incremental re-authorization prompt. */
export function needsCommandReauthorization(status: CarStatus): boolean {
  const authorization = status.commandAuthorization
  return authorization != null && authorization.ready === false
}

/** Params for set_climate_temp: at least one side, each within 15–28 °C. */
export function buildClimateTempParams(input: { driverTemp?: number; passengerTemp?: number }): Record<string, number> | null {
  const params: Record<string, number> = {}
  for (const [key, value] of [['driver_temp', input.driverTemp], ['passenger_temp', input.passengerTemp]] as const) {
    if (value === undefined) continue
    if (typeof value !== 'number' || !Number.isFinite(value) || value < 15 || value > 28) return null
    params[key] = value
  }
  return Object.keys(params).length > 0 ? params : null
}

/** Params for set_charge_limit: an integer percent within 50–100. */
export function buildChargeLimitParams(percent: number): Record<string, number> | null {
  if (typeof percent !== 'number' || !Number.isInteger(percent) || percent < 50 || percent > 100) return null
  return { percent }
}

/**
 * User-facing text for the backend command error taxonomy (§6.3). The
 * API layer surfaces these classes as ApiError.code; unknown classes
 * fall back to a generic failure message.
 */
const COMMAND_ERROR_MESSAGES: Record<string, string> = {
  commands_disabled: '车辆控制尚未开启',
  not_owner: '当前账号不是该车辆的车主',
  scope_missing: '授权缺少车辆控制权限，请重新授权',
  confirmation_required: '该操作需要再次确认',
  rate_limited: '今日控制次数已达上限，请明天再试',
  vehicle_moving: '车辆行驶中，无法执行该操作',
  not_plugged_in: '请先连接充电线',
  key_not_paired: '请先在 Tesla 官方 App 中确认虚拟钥匙',
  vehicle_in_service: '车辆正在维修模式，无法执行该操作',
  tesla_unavailable: '暂时无法连接车辆，请稍后重试',
  tesla_error: '车辆未接受该指令，请稍后重试',
  invalid_command_params: '指令参数不正确',
  unknown_command: '不支持的指令',
}

export function commandErrorMessage(code: string | null | undefined): string {
  return (code && COMMAND_ERROR_MESSAGES[code]) || '指令执行失败，请稍后重试'
}
