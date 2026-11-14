import { describe, expect, it, vi } from 'vitest'
import React from 'react'

vi.mock('@tarojs/components', () => ({
  Text: 'text',
  View: 'view',
  Image: 'image',
  Button: 'button',
  Input: 'input',
  Slider: 'slider',
}))

import ReactTestRenderer, { act } from 'react-test-renderer'
import VehicleRemoteSections from './VehicleRemoteSections'
import type { CarStatus, ChargingSite, VehiclePlace } from '../../services/types'

const testStatus: CarStatus = {
  state: 'online',
  model: '3',
  trim: null,
  exteriorColor: null,
  wheelType: null,
  batteryLevel: 85,
  ratedRange: 420,
  odometer: 15000,
  location: { latitude: 30.27, longitude: 120.15 },
  locked: true,
  sentryMode: false,
  windowsOpen: false,
  doorsOpen: false,
  trunkOpen: false,
  frunkOpen: false,
  isClimateOn: false,
  insideTemp: 21.0,
  outsideTemp: 19.0,
  pluggedIn: true,
  speed: 0,
  power: 0,
  shiftState: 'P',
  heading: 0,
  chargingState: 'Disconnected',
  chargeEnergyAdded: 0,
  chargerPower: 0,
  chargeLimitSoc: 80,
  driverTempSetting: 21.5,
  passengerTempSetting: 21.5,
  observedAt: '2026-11-06T00:00:00Z',
  source: 'telemetry',
  commandAuthorization: { ready: true, missingScopes: null },
  }

describe('VehicleRemoteSections', () => {
  it('renders automotive control groups and executes quick action commands', async () => {
    const onExecute = vi.fn().mockResolvedValue(undefined)
    let renderer: ReactTestRenderer.ReactTestRenderer | null = null

    await act(async () => {
      renderer = ReactTestRenderer.create(
        <VehicleRemoteSections
          carId={1}
          status={testStatus}
          onExecuteCommand={onExecute}
          busyCommand={null}
          commandOutcome={null}
          onDismissOutcome={() => {}}
          nearbySites={null}
          nearbySource={null}
          nearbyLoading={false}
          nearbyError={false}
          onLoadNearby={() => {}}
          places={null}
          placesLoading={false}
          placesError={false}
          onLoadPlaces={() => {}}
          onCreatePlace={async () => {}}
          onDeletePlace={async () => {}}
        />
      )
    })

    const root = renderer!.root
    expect(root.findByProps({ className: 'card vehicle-remote-card' })).toBeDefined()

    // Test flash lights execution
    const flashBtn = root.findAllByProps({ className: 'remote-action-btn' }).find(btn => {
      return btn.findAllByProps({ className: 'btn-label' }).some(node => node.children.includes('闪灯'))
    })
    expect(flashBtn).toBeDefined()

    await act(async () => {
      flashBtn!.props.onClick()
    })
    expect(onExecute).toHaveBeenCalledWith('flash')
  })

  it('renders re-authorization panel when command authorization ready is false', async () => {
    const unreadyStatus: CarStatus = {
      ...testStatus,
      commandAuthorization: { ready: false, missingScopes: ['vehicle_cmds'] },
    }
    const onReauth = vi.fn()

    let renderer: ReactTestRenderer.ReactTestRenderer | null = null
    await act(async () => {
      renderer = ReactTestRenderer.create(
        <VehicleRemoteSections
          carId={1}
          status={unreadyStatus}
          onReauthorize={onReauth}
          onExecuteCommand={async () => {}}
          busyCommand={null}
          commandOutcome={null}
          onDismissOutcome={() => {}}
          nearbySites={null}
          nearbySource={null}
          nearbyLoading={false}
          nearbyError={false}
          onLoadNearby={() => {}}
          places={null}
          placesLoading={false}
          placesError={false}
          onLoadPlaces={() => {}}
          onCreatePlace={async () => {}}
          onDeletePlace={async () => {}}
        />
      )
    })

    const root = renderer!.root
    const panel = root.findByProps({ className: 'card vehicle-reauth-panel' })
    expect(panel).toBeDefined()
    const reauthBtn = panel.findByProps({ className: 'button' })
    await act(async () => {
      reauthBtn.props.onClick()
    })
    expect(onReauth).toHaveBeenCalled()
  })

  it('renders nearby charging sites with distance and stalls when loaded', async () => {
    const sites: ChargingSite[] = [
      {
        name: '未来科技城超级充电站',
        type: 'supercharger',
        latitude: 30.28,
        longitude: 120.16,
        distance_km: 1.2,
        available_stalls: 6,
        total_stalls: 8,
        site_closed: false,
        price_text: '¥0.95~1.65/度',
        parking_policy: '超充免费停车2小时',
      },
      {
        name: '余杭目的地充电站',
        type: 'destination_charging',
        latitude: 30.29,
        longitude: 120.17,
        distance_km: 3.5,
        available_stalls: null,
        total_stalls: null,
        site_closed: false,
        price_text: null,
        parking_policy: null,
      },
    ]

    let renderer: ReactTestRenderer.ReactTestRenderer | null = null
    await act(async () => {
      renderer = ReactTestRenderer.create(
        <VehicleRemoteSections
          carId={1}
          status={testStatus}
          onExecuteCommand={async () => {}}
          busyCommand={null}
          commandOutcome={null}
          onDismissOutcome={() => {}}
          nearbySites={sites}
          nearbySource="fleet_api"
          nearbyLoading={false}
          nearbyError={false}
          onLoadNearby={() => {}}
          places={null}
          placesLoading={false}
          placesError={false}
          onLoadPlaces={() => {}}
          onCreatePlace={async () => {}}
          onDeletePlace={async () => {}}
        />
      )
    })

    const root = renderer!.root
    const rows = root.findAllByProps({ className: 'nearby-site-row' })
    expect(rows.length).toBe(2)
    expect(rows[0].findByProps({ className: 'site-name' }).children.join('')).toContain('未来科技城超级充电站')
    expect(rows[0].findByProps({ className: 'site-distance' }).children.join('')).toContain('1.2 km')
    // App parity: price + parking row, backend values win over defaults.
    expect(rows[0].findByProps({ className: 'site-price' }).children.join('')).toContain('¥0.95~1.65/度')
    expect(rows[0].findByProps({ className: 'site-parking' }).children.join('')).toContain('超充免费停车2小时')
    expect(rows[1].findByProps({ className: 'site-price' }).children.join('')).toContain('¥1.15/度')
    expect(rows[1].findByProps({ className: 'site-parking' }).children.join('')).toContain('前2小时免费停车')
  })

  it('navigates to a nearby site when its row is tapped', async () => {
    const sites: ChargingSite[] = [
      {
        name: '未来科技城超级充电站',
        type: 'supercharger',
        latitude: 30.28,
        longitude: 120.16,
        distance_km: 1.2,
        available_stalls: 6,
        total_stalls: 8,
        site_closed: false,
        price_text: null,
        parking_policy: null,
      },
    ]
    const onNavigateSite = vi.fn()
    let renderer: ReactTestRenderer.ReactTestRenderer | null = null
    await act(async () => {
      renderer = ReactTestRenderer.create(
        <VehicleRemoteSections
          carId={1}
          status={testStatus}
          onExecuteCommand={async () => {}}
          busyCommand={null}
          commandOutcome={null}
          onDismissOutcome={() => {}}
          nearbySites={sites}
          nearbySource="fleet_api"
          nearbyLoading={false}
          nearbyError={false}
          onLoadNearby={() => {}}
          onNavigateSite={onNavigateSite}
          places={null}
          placesLoading={false}
          placesError={false}
          onLoadPlaces={() => {}}
          onCreatePlace={async () => {}}
          onDeletePlace={async () => {}}
        />
      )
    })
    const row = renderer!.root.findByProps({ className: 'nearby-site-row' })
    await act(async () => {
      row.props.onClick()
    })
    expect(onNavigateSite).toHaveBeenCalledTimes(1)
    expect(onNavigateSite.mock.calls[0][0].name).toBe('未来科技城超级充电站')
  })

  it('renders vehicle places list and handles deletion', async () => {
    const places: VehiclePlace[] = [
      { id: 10, name: '家', latitude: 30.27, longitude: 120.15, radius_m: 200 },
      { id: 11, name: '公司', latitude: 30.30, longitude: 120.20, radius_m: 300 },
    ]
    const onDelete = vi.fn().mockResolvedValue(undefined)

    let renderer: ReactTestRenderer.ReactTestRenderer | null = null
    await act(async () => {
      renderer = ReactTestRenderer.create(
        <VehicleRemoteSections
          carId={1}
          status={testStatus}
          onExecuteCommand={async () => {}}
          busyCommand={null}
          commandOutcome={null}
          onDismissOutcome={() => {}}
          nearbySites={null}
          nearbySource={null}
          nearbyLoading={false}
          nearbyError={false}
          onLoadNearby={() => {}}
          places={places}
          placesLoading={false}
          placesError={false}
          onLoadPlaces={() => {}}
          onCreatePlace={async () => {}}
          onDeletePlace={onDelete}
        />
      )
    })

    const root = renderer!.root
    const rows = root.findAllByProps({ className: 'place-row' })
    expect(rows.length).toBe(2)
    expect(rows[0].findByProps({ className: 'place-name' }).children.join('')).toContain('家')

    const deleteBtn = rows[0].findByProps({ className: 'place-delete-btn' })
    await act(async () => {
      deleteBtn.props.onClick()
    })
    // App parity: deletion requires the confirmation dialog first.
    expect(onDelete).not.toHaveBeenCalled()
    expect(root.findByProps({ className: 'modal-title' }).children.join('')).toContain('删除地点「家」')
    const confirmBtn = root.findByProps({ className: 'button place-delete-confirm-btn' })
    await act(async () => {
      confirmBtn.props.onClick()
    })
    expect(onDelete).toHaveBeenCalledWith(10)
  })
})
