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
import type { ChargingSite, VehiclePlace } from '../../services/types'

const baseProps = {
  carId: 1,
  hasLocation: true,
  nearbySites: null as ChargingSite[] | null,
  nearbyLoading: false,
  nearbyError: false,
  onLoadNearby: vi.fn(),
  onNavigateSite: undefined,
  places: null as VehiclePlace[] | null,
  placesLoading: false,
  placesError: false,
  onLoadPlaces: vi.fn(),
  onCreatePlace: vi.fn(async () => {}),
  onDeletePlace: vi.fn(async () => {}),
}

function site(overrides: Partial<ChargingSite>): ChargingSite {
  return {
    name: '杭州未来科技城宝龙广场',
    type: 'supercharger',
    latitude: 30.27,
    longitude: 120.15,
    distance_km: 1.3,
    available_stalls: 4,
    total_stalls: 8,
    site_closed: false,
    price_text: '¥1.25/度 (分时¥0.95~1.65)',
    parking_policy: '超充免费停车2小时',
    ...overrides,
  } as ChargingSite
}

function place(overrides: Partial<VehiclePlace> = {}): VehiclePlace {
  return { id: 7, name: '家', latitude: 30.27, longitude: 120.15, radius_m: 200, ...overrides } as VehiclePlace
}

function renderSection(props: Partial<typeof baseProps> = {}) {
  let renderer: ReactTestRenderer.ReactTestRenderer | null = null
  const merged = { ...baseProps, ...props }
  const view = (
    <VehicleRemoteSections
      carId={merged.carId}
      hasLocation={merged.hasLocation}
      nearbySites={merged.nearbySites}
      nearbyLoading={merged.nearbyLoading}
      nearbyError={merged.nearbyError}
      onLoadNearby={merged.onLoadNearby}
      onNavigateSite={merged.onNavigateSite}
      places={merged.places}
      placesLoading={merged.placesLoading}
      placesError={merged.placesError}
      onLoadPlaces={merged.onLoadPlaces}
      onCreatePlace={merged.onCreatePlace}
      onDeletePlace={merged.onDeletePlace}
    />
  )
  return { merged, view, done: async () => { await act(async () => { renderer = ReactTestRenderer.create(view) }); return renderer! } }
}

describe('VehicleRemoteSections (v2.1.87 parity)', () => {
  it('auto-loads nearby sites and places on mount like the App LaunchedEffect blocks', async () => {
    const { merged, done } = renderSection()
    await done()
    expect(merged.onLoadNearby).toHaveBeenCalled()
    expect(merged.onLoadPlaces).toHaveBeenCalled()
  })

  it('renders at most five site rows with 公里 distance and the App price/parking row', async () => {
    const sites = Array.from({ length: 8 }, (_, index) => site({ name: `站 ${index}`, distance_km: index + 0.5 }))
    const { done } = renderSection({ nearbySites: sites })
    const renderer = await done()
    const root = renderer.root
    const rows = root.findAllByProps({ className: 'nearby-site-row' })
    expect(rows).toHaveLength(5)
    const distances = root.findAllByProps({ className: 'site-distance' }).map(node => node.children.join(''))
    expect(distances).toEqual(['0.5 公里', '1.5 公里', '2.5 公里', '3.5 公里', '4.5 公里'])
    expect(root.findAllByProps({ className: 'site-price' })[0].children).toEqual(['¥1.25/度 (分时¥0.95~1.65)'])
    const parking = root.findAllByProps({ className: 'site-parking' })[0].children.join('')
    expect(parking).toBe('超充免费停车2小时')
    expect(parking).not.toContain('🅿')
    // Supercharger subtitle keeps the App wording and stall occupancy.
    const desc = root.findAllByProps({ className: 'site-desc' })[0].children.join('')
    expect(desc).toBe('超级充电站 · 空闲 4/8')
  })

  it('renders destination-charging wording for non-supercharger sites', async () => {
    const { done } = renderSection({ nearbySites: [site({ type: 'destination', name: '商场目的地充电', price_text: null, parking_policy: null, available_stalls: null, total_stalls: null })] })
    const renderer = await done()
    expect(renderer.root.findByProps({ className: 'site-desc' }).children.join('')).toBe('目的地充电')
    // App NearbyChargingModels fallbacks for missing price/parking.
    expect(renderer.root.findByProps({ className: 'site-price' }).children).toEqual(['¥1.15/度'])
    expect(renderer.root.findByProps({ className: 'site-parking' }).children.join('')).toBe('前2小时免费停车')
  })

  it('shows the App failure copy with a retry control and never a command card', async () => {
    const { done } = renderSection({ nearbyError: true })
    const renderer = await done()
    const root = renderer.root
    expect(root.findAllByProps({ className: 'muted' }).some(node => node.children.join('') === '附近充电站加载失败，请重试。')).toBe(true)
    expect(root.findAllByProps({ className: 'card vehicle-remote-card' })).toHaveLength(0)
    expect(root.findAllByProps({ className: 'command-group' })).toHaveLength(0)
  })

  it('keeps the App empty copy when no sites are returned', async () => {
    const { done } = renderSection({ nearbySites: [] })
    const renderer = await done()
    expect(renderer.root.findAllByProps({ className: 'muted' }).some(node => node.children.join('') === '车辆附近暂无可显示的充电站。')).toBe(true)
  })

  it('lists saved places with App radius wording and opens the App add-place dialog', async () => {
    const { merged, done } = renderSection({ places: [place()] })
    const renderer = await done()
    const root = renderer.root
    expect(root.findByProps({ className: 'place-name' }).children).toEqual(['家'])
    expect(root.findByProps({ className: 'place-radius' }).children.join('')).toBe('半径 200 米')
    const trigger = root.findAllByType('button').find(btn => btn.props.children === '把当前位置存为地点')
    expect(trigger).toBeDefined()
    await act(async () => {
      trigger!.props.onClick()
    })
    // App dialog title is 把当前位置存为地点 — not an invented caption.
    expect(root.findByProps({ className: 'modal-title' }).children.join('')).toBe('把当前位置存为地点')
    const labels = root.findAllByProps({ className: 'field-label' }).map(node => node.children.join(''))
    expect(labels).toEqual(['地点名称', '半径 200 米'])
  })

  it('saves a place through the App dialog fields', async () => {
    const { merged, done } = renderSection({ places: [] })
    const renderer = await done()
    const root = renderer.root
    const trigger = root.findAllByType('button').find(btn => btn.props.children === '把当前位置存为地点')
    await act(async () => {
      trigger!.props.onClick()
    })
    const input = root.findByType('input')
    await act(async () => {
      input.props.onInput({ detail: { value: '公司' } })
    })
    const save = root.findAllByType('button').find(btn => btn.props.children === '保存')
    expect(save).toBeDefined()
    await act(async () => {
      save!.props.onClick()
    })
    expect(merged.onCreatePlace).toHaveBeenCalledWith('公司', 200)
  })

  it('confirms deletion with the App 删除地点「name」？ dialog', async () => {
    const { merged, done } = renderSection({ places: [place()] })
    const renderer = await done()
    const root = renderer.root
    await act(async () => {
      root.findByProps({ className: 'place-delete-btn' }).props.onClick()
    })
    expect(root.findByProps({ className: 'modal-title' }).children.join('')).toBe('删除地点「家」？')
    const confirm = root.findAllByType('button').find(btn => btn.props.children === '确认执行')
    await act(async () => {
      confirm!.props.onClick()
    })
    expect(merged.onDeletePlace).toHaveBeenCalledWith(7)
  })

  it('asks to save the current position only when the vehicle reports a location', async () => {
    const { done } = renderSection({ hasLocation: false })
    const renderer = await done()
    const texts = renderer.root.findAllByProps({ className: 'muted' }).map(node => node.children.join(''))
    expect(texts).toContain('还没有保存的地点。把车辆当前位置存下来即可命名。')
    expect(renderer.root.findAllByProps({ className: 'muted place-no-pos' }).map(node => node.children.join(''))).toEqual(['暂无车辆位置，暂时无法添加地点。'])
  })
})
