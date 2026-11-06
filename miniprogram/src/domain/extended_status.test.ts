import { describe, expect, it } from 'vitest'
import {
  climateTargets,
  mediaPlayback,
  scheduledEvents,
  softwareUpdateUi,
  vehicleModes,
} from './extended_status'
import type { CarStatus } from '../services/types'

const baseStatus: CarStatus = {
  state: 'online',
  model: 'Y',
  trim: null,
  exteriorColor: null,
  wheelType: null,
  batteryLevel: 80,
  ratedRange: 400,
  odometer: 12000,
  location: null,
  locked: true,
  sentryMode: false,
  windowsOpen: false,
  doorsOpen: false,
  trunkOpen: false,
  frunkOpen: false,
  isClimateOn: false,
  insideTemp: 22,
  outsideTemp: 18,
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

describe('extended_status', () => {
  it('shapes media playback correctly', () => {
    expect(mediaPlayback(baseStatus)).toBeNull()

    const withMedia = {
      ...baseStatus,
      mediaInfo: { mediaState: 'playing', title: 'Starboy', artist: 'The Weeknd', album: 'Starboy' },
    }
    expect(mediaPlayback(withMedia)).toEqual({
      title: 'Starboy',
      artist: 'The Weeknd',
      displayText: 'Starboy — The Weeknd',
    })

    const titleOnly = {
      ...baseStatus,
      mediaInfo: { mediaState: 'playing', title: 'Radio 1', artist: null, album: null },
    }
    expect(mediaPlayback(titleOnly)).toEqual({
      title: 'Radio 1',
      artist: null,
      displayText: 'Radio 1',
    })
  })

  it('shapes software update progress', () => {
    expect(softwareUpdateUi(baseStatus)).toBeNull()

    const downloading = {
      ...baseStatus,
      softwareUpdate: { status: 'downloading', downloadPercent: 45, installPercent: null },
    }
    expect(softwareUpdateUi(downloading)).toEqual({
      phase: 'downloading',
      percent: 45,
    })

    const installing = {
      ...baseStatus,
      softwareUpdate: { status: 'installing', downloadPercent: 100, installPercent: 82 },
    }
    expect(softwareUpdateUi(installing)).toEqual({
      phase: 'installing',
      percent: 82,
    })

    const otherStatus = {
      ...baseStatus,
      softwareUpdate: { status: 'available', downloadPercent: null, installPercent: null },
    }
    expect(softwareUpdateUi(otherStatus)).toBeNull()
  })

  it('shapes scheduled events in order', () => {
    expect(scheduledEvents(baseStatus)).toEqual([])

    const withSchedule = {
      ...baseStatus,
      scheduledChargingStartTime: '2026-11-06T22:00:00Z',
      scheduledDepartureTime: '2026-11-07T07:30:00Z',
    }
    const events = scheduledEvents(withSchedule)
    expect(events.length).toBe(2)
    expect(events[0].kind).toBe('charging_start')
    expect(events[1].kind).toBe('departure')
  })

  it('shapes vehicle modes (valet and speed limit)', () => {
    expect(vehicleModes(baseStatus)).toBeNull()

    const valetOnly = { ...baseStatus, valetMode: true }
    expect(vehicleModes(valetOnly)).toEqual({ valetOn: true, speedLimitKmh: null })

    const speedLimitOnly = {
      ...baseStatus,
      speedLimitMode: { active: true, currentLimitKmh: 100, pinCodeRequired: false },
    }
    expect(vehicleModes(speedLimitOnly)).toEqual({ valetOn: false, speedLimitKmh: 100 })
  })

  it('shapes climate targets', () => {
    expect(climateTargets(baseStatus)).toBeNull()

    const withTargets = {
      ...baseStatus,
      driverTempSetting: 21.5,
      passengerTempSetting: 22.0,
    }
    expect(climateTargets(withTargets)).toEqual({ driver: 21.5, passenger: 22.0 })
  })
})
