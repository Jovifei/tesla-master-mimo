import React from 'react'
import renderer from 'react-test-renderer'
import { expect, it, vi } from 'vitest'
vi.mock('@tarojs/components', () => ({ Text: 'text', View: 'view', Image: 'image', Picker: 'picker' }))
import MetricValue, { displayNumber, formatSocRange, peakSpeedKmh } from './MetricValue'
import LoadedDriveChart, { loadedDriveGroups } from './LoadedDriveChart'
import type { Drive } from '../services/types'

it('renders large value and complete smaller unit independently, including zero/negative/unknown', () => {
  for (const value of [0, -16.4, null, undefined]) {
    const tree = renderer.create(<MetricValue label="平均能耗" value={value} unit="kWh/100km" icon="energy" negative={value != null && value < 0} />)
    expect(tree.root.findByProps({ className: 'metric-value' }).children.join('')).toBe(value == null ? '暂无数据' : String(value))
    expect(tree.root.findByProps({ className: 'metric-unit' }).children.join('')).toBe('kWh/100km')
    expect(tree.root.findByProps({ className: 'metric' }).props.ariaLabel).toContain('kWh/100km')
    tree.unmount()
  }
  expect(displayNumber(0.000001)).not.toBe('0')
  expect(displayNumber(-0.000001)).not.toBe('0')
})

it('formats SOC ranges cleanly with arrows and dashes', () => {
  expect(formatSocRange(90, 80)).toBe('90% → 80%')
  expect(formatSocRange(90, null)).toBe('90% → —')
  expect(formatSocRange(null, null)).toBe('暂无数据')
})

it('loaded date groups retain zero and partial known distances without inventing unknown peaks or dates', () => {
  const row = { startDate: '2026-10-14T00:00:00Z', distanceKm: 0, speedMaxKmh: 0,
    speedSampleCount: 1, speedMetricsSource: 'observed_route_samples', speedUnit: 'km/h' } as Drive
  const groups = loadedDriveGroups([row, { ...row, distanceKm: null, speedSampleCount: 0 },
    { ...row, distanceKm: 5, speedMaxKmh: 80, speedUnit: 'mph' }, { ...row, startDate: null }, { ...row, qualityState: 'quarantined', speedMaxKmh: 999 }])
  expect(groups).toHaveLength(1)
  expect(groups[0]).toMatchObject({ count: 3, distance: 5, speed: 0, missingDistance: 1, missingSpeed: 2 })
  expect(loadedDriveGroups([{ ...row, distanceKm: null, speedSampleCount: null }])[0]).toMatchObject({ distance: null, speed: null })
  expect(peakSpeedKmh({ ...row, speedMetricsSource: 'unknown' })).toBeNull()
})
it('uses one-decimal presentation without converting tiny non-zero energy into zero', () => {
  for (const [value, expected] of [[16.46, '16.5'], [0, '0'], [-0.00001, '-0.00001']] as const) {
    const tree = renderer.create(<MetricValue label="平均能耗" value={value} unit="kWh/100km" digits={1} />)
    expect(tree.root.findByProps({ className: 'metric-value' }).children.join('')).toBe(expected)
    expect(tree.root.findByProps({ className: 'metric-unit' }).children.join('')).toBe('kWh/100km')
    tree.unmount()
  }
})

it('puts the selected unit on each actual chart value, including genuine zero', () => {
  const tree = renderer.create(<LoadedDriveChart items={[{ startDate: '2026-10-14T00:00:00Z', distanceKm: 0 } as Drive]} />)
  expect(tree.root.findByProps({ className: 'chart-value' }).children.join('')).toBe('1 次')
  renderer.act(() => { tree.root.findByType('picker' as React.ElementType).props.onChange({ detail: { value: 1 } }) })
  expect(tree.root.findByProps({ className: 'chart-value' }).children.join('')).toBe('0 km')
  renderer.act(() => { tree.root.findByType('picker' as React.ElementType).props.onChange({ detail: { value: 2 } }) })
  expect(tree.root.findByProps({ className: 'chart-value' }).children.join('')).toBe('暂无 km/h')
  expect(tree.root.findAllByProps({ className: 'chart-bar' })).toHaveLength(0)
  tree.unmount()
})

it('matches the App telemetry cell hierarchy: icon, number/unit, then label', () => {
  const tree = renderer.create(<MetricValue label="距离" value={10} unit="km" icon="distance" presentation="telemetry" />)
  const root = tree.root.findByProps({ className: 'metric' })
  expect(root.children.filter(child => typeof child !== 'string').map(child => child.props.className)).toEqual([
    'metric-heading', 'metric-reading ', 'metric-label',
  ])
  expect(tree.root.findByProps({ className: 'metric-heading' }).findAllByProps({ className: 'metric-label' })).toHaveLength(0)
  tree.unmount()
})
