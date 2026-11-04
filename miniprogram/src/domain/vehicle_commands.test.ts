import { describe, expect, it } from 'vitest'
import type { CarStatus } from '../services/types'
import {
  VEHICLE_COMMANDS,
  buildChargeLimitParams,
  buildClimateTempParams,
  commandErrorMessage,
  needsCommandReauthorization,
  offeredCommands,
  vehicleCommand,
} from './vehicle_commands'

function statusWith(authorization?: CarStatus['commandAuthorization']): CarStatus {
  return {
    state: null, model: null, trim: null, exteriorColor: null, wheelType: null,
    batteryLevel: null, ratedRange: null, odometer: null, location: null,
    locked: null, sentryMode: null, windowsOpen: null, doorsOpen: null,
    trunkOpen: null, frunkOpen: null, isClimateOn: null, insideTemp: null,
    outsideTemp: null, pluggedIn: null, speed: null, power: null,
    shiftState: null, heading: null, chargingState: null, chargeEnergyAdded: null,
    chargerPower: null, observedAt: null, source: null,
    ...(authorization !== undefined ? { commandAuthorization: authorization } : {}),
  }
}

describe('vehicle command catalog (mirrors the backend table)', () => {
  it('has the 16 phase-1 commands with unique names', () => {
    expect(VEHICLE_COMMANDS).toHaveLength(16)
    expect(new Set(VEHICLE_COMMANDS.map(command => command.name)).size).toBe(16)
  })

  it('keeps unlock as the only high-tier, confirmation-gated command', () => {
    const high = VEHICLE_COMMANDS.filter(command => command.tier === 'high')
    expect(high.map(command => command.name)).toEqual(['unlock'])
    expect(high[0].needsConfirmation).toBe(true)
    expect(high[0].parkedOnly).toBe(true)
    expect(VEHICLE_COMMANDS.filter(command => command.needsConfirmation).map(command => command.name)).toEqual(['unlock'])
  })

  it('marks the backend preconditions the UI may hint at', () => {
    expect(vehicleCommand('charge_start')?.requiresPluggedIn).toBe(true)
    expect(vehicleCommand('windows_vent')?.parkedOnly).toBe(true)
    expect(vehicleCommand('windows_close')?.parkedOnly).toBe(true)
    expect(vehicleCommand('lock')?.tier).toBe('low')
    expect(vehicleCommand('sentry_on')?.tier).toBe('mid')
    expect(vehicleCommand('navigation_request')).toBeNull()
  })
})

describe('command offering rules', () => {
  it('offers nothing until the backend reports scopes ready', () => {
    expect(offeredCommands(statusWith(undefined))).toEqual([])
    expect(offeredCommands(statusWith(null))).toEqual([])
    expect(offeredCommands(statusWith({ ready: false, missingScopes: ['vehicle_cmds'] }))).toEqual([])
    expect(offeredCommands(statusWith({ ready: true, missingScopes: [] }))).toHaveLength(16)
  })

  it('prompts re-authorization only when an evaluated block says not ready', () => {
    expect(needsCommandReauthorization(statusWith(undefined))).toBe(false)
    expect(needsCommandReauthorization(statusWith(null))).toBe(false)
    expect(needsCommandReauthorization(statusWith({ ready: true, missingScopes: [] }))).toBe(false)
    expect(needsCommandReauthorization(statusWith({ ready: false, missingScopes: ['vehicle_cmds'] }))).toBe(true)
  })
})

describe('parameter builders mirror backend validation', () => {
  it('builds climate temp params within 15–28 °C with at least one side', () => {
    expect(buildClimateTempParams({ driverTemp: 22.5 })).toEqual({ driver_temp: 22.5 })
    expect(buildClimateTempParams({ driverTemp: 21, passengerTemp: 23 })).toEqual({ driver_temp: 21, passenger_temp: 23 })
    expect(buildClimateTempParams({})).toBeNull()
    expect(buildClimateTempParams({ driverTemp: 14.9 })).toBeNull()
    expect(buildClimateTempParams({ passengerTemp: 28.1 })).toBeNull()
    expect(buildClimateTempParams({ driverTemp: Number.NaN })).toBeNull()
  })

  it('builds charge limit params as an integer within 50–100', () => {
    expect(buildChargeLimitParams(80)).toEqual({ percent: 80 })
    expect(buildChargeLimitParams(50)).toEqual({ percent: 50 })
    expect(buildChargeLimitParams(100)).toEqual({ percent: 100 })
    expect(buildChargeLimitParams(49)).toBeNull()
    expect(buildChargeLimitParams(101)).toBeNull()
    expect(buildChargeLimitParams(80.5)).toBeNull()
  })
})

describe('command error messages', () => {
  it('maps the backend taxonomy and falls back generically', () => {
    expect(commandErrorMessage('vehicle_moving')).toContain('行驶中')
    expect(commandErrorMessage('not_plugged_in')).toContain('充电线')
    expect(commandErrorMessage('scope_missing')).toContain('重新授权')
    expect(commandErrorMessage('rate_limited')).toContain('上限')
    expect(commandErrorMessage('something_new')).toBe('指令执行失败，请稍后重试')
    expect(commandErrorMessage(null)).toBe('指令执行失败，请稍后重试')
    expect(commandErrorMessage(undefined)).toBe('指令执行失败，请稍后重试')
  })
})
