import { describe, expect, it } from 'vitest'
import {
  commandErrorMessage,
  commandLabel,
  needsCommandReauthorization,
  offeredCommands,
  VEHICLE_COMMAND_CATALOG,
} from './vehicle_commands'
import type { CarStatus } from '../services/types'

const baseStatus: CarStatus = {
  state: 'online',
  model: '3',
  trim: null,
  exteriorColor: null,
  wheelType: null,
  batteryLevel: 90,
  ratedRange: 450,
  odometer: 10000,
  location: null,
  locked: true,
  sentryMode: false,
  windowsOpen: false,
  doorsOpen: false,
  trunkOpen: false,
  frunkOpen: false,
  isClimateOn: false,
  insideTemp: 20,
  outsideTemp: 15,
  pluggedIn: false,
  speed: 0,
  power: 0,
  shiftState: 'P',
  heading: 0,
  chargingState: 'Disconnected',
  chargeEnergyAdded: 0,
  chargerPower: 0,
  observedAt: '2026-11-06T00:00:00Z',
  source: 'telemetry',
}

describe('vehicle_commands', () => {
  it('offers catalog only when authorization is ready; not-evaluated stays empty (App口径)', () => {
    expect(offeredCommands(baseStatus)).toEqual([])

    const readyStatus = {
      ...baseStatus,
      commandAuthorization: { ready: true, missingScopes: null },
    }
    expect(offeredCommands(readyStatus)).toEqual(VEHICLE_COMMAND_CATALOG)

    const unreadyStatus = {
      ...baseStatus,
      commandAuthorization: { ready: false, missingScopes: ['vehicle_cmds'] },
    }
    expect(offeredCommands(unreadyStatus)).toEqual([])
  })

  it('determines when re-authorization is needed', () => {
    expect(needsCommandReauthorization(baseStatus)).toBe(false)

    const readyStatus = {
      ...baseStatus,
      commandAuthorization: { ready: true, missingScopes: null },
    }
    expect(needsCommandReauthorization(readyStatus)).toBe(false)

    const unreadyStatus = {
      ...baseStatus,
      commandAuthorization: { ready: false, missingScopes: ['vehicle_cmds'] },
    }
    expect(needsCommandReauthorization(unreadyStatus)).toBe(true)
  })

  it('provides localized command labels and error messages', () => {
    expect(commandLabel('lock')).toBe('上锁')
    expect(commandLabel('unlock')).toBe('解锁')
    expect(commandLabel('windows_vent')).toBe('车窗通风')

    expect(commandErrorMessage('commands_disabled')).toBe('车辆控制尚未开启。')
    expect(commandErrorMessage('vehicle_moving')).toBe('车辆行驶中，无法执行该命令。')
    expect(commandErrorMessage('unknown_error')).toBe('命令执行失败，请稍后重试。')
  })
})
