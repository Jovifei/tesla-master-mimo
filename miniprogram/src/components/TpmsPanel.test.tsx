import React from 'react'
import renderer, { act } from 'react-test-renderer'
import { expect, it, vi } from 'vitest'
vi.mock('@tarojs/components', () => ({ Text: 'text', View: 'view', Button: 'button' }))
vi.mock('@tarojs/taro', () => ({ default: {} }))
import TpmsPanel from './TpmsPanel'
import { parseTpmsDetails } from '../domain/tpms'
import { observeTpmsCache, readTpmsCache } from '../services/tpms_cache'

it('renders real parsed evidence through persistence rebuild with zero, stale, missing wheel and full units', () => {
  const now = Date.parse('2026-10-15T02:00:00Z')
  const snapshot = parseTpmsDetails({ pressure_unit: 'bar', source: 'telemetry_mqtt', time_basis: 'collector_received_at',
    tpms_pressure_fl: 0, tpms_pressure_fl_observed_at: '2026-10-15T01:59:00Z',
    tpms_pressure_fr: 2.9, tpms_pressure_fr_observed_at: '2026-10-14T01:00:00Z',
    tpms_pressure_rl: 3.1 }, now)
  const data = new Map<string, unknown>()
  const storage = { getStorageSync: (key: string) => data.get(key), setStorageSync: (key: string, value: unknown) => { data.set(key, JSON.parse(JSON.stringify(value))) }, getStorageInfoSync: () => ({ keys: [...data.keys()] }) }
  observeTpmsCache(storage, 'owner', 'https://api.test', 'uid', snapshot, now)
  const history = readTpmsCache({ getStorageSync: key => data.get(key) }, 'owner', 'https://api.test', 'uid', now)
  const tree = renderer.create(<TpmsPanel snapshot={snapshot} history={history} now={now} />)
  expect(tree.root.findAllByProps({ className: 'tpms-grid' })).toHaveLength(1)
  expect(tree.root.findAllByProps({ className: 'tpms-wheel-card' })).toHaveLength(4)
  expect(tree.root.findAllByProps({ className: 'tpms-evidence' })).toHaveLength(0)
  expect(tree.root.findAllByProps({ className: 'metric-value' }).map(node => node.children.join(''))).toEqual(['0', '2.9', '暂无数据', '暂无数据'])
  expect(tree.root.findAllByProps({ className: 'metric-unit' }).map(node => node.children.join(''))).toEqual(['bar', 'bar'])
  act(() => tree.root.findByType('button').props.onClick())
  expect(tree.root.findAllByProps({ className: 'tpms-evidence' })).toHaveLength(1)
  const text = JSON.stringify(tree.toJSON())
  expect(text).toContain('旧观测')
  expect(text).toContain('2026-10-14T01:00:00.000Z')
  expect(text).toContain('缺少有效逐轮观测时间')
  expect(text).toContain('本机前台观测历史')
  expect(tree.root.findAllByType('text').some(node => node.children.join('').includes('2 点'))).toBe(true)
  act(() => tree.root.findAllByType('button').find(button => button.children.join('') === '查看本机记录')!.props.onClick())
  expect(tree.root.findAllByType('text').some(node => node.children.join('') === '左前轮 · 0 bar')).toBe(true)
  expect(tree.root.findAllByType('text').some(node => node.children.join('').includes('1/1 页'))).toBe(true)
  act(() => tree.root.findAllByType('button').find(button => button.children.join('') === '收起观测依据与本机历史')!.props.onClick())
  expect(tree.root.findAllByProps({ className: 'tpms-evidence' })).toHaveLength(0)
  tree.unmount()
})

it('paginates actual retained foreground records without loading them into current wheel values', () => {
  const now = Date.parse('2026-10-15T02:00:00Z')
  const points = Array.from({ length: 25 }, (_, index) => ({ wheel: 'fl' as const, pressureBar: 2 + index / 100,
    observedAt: new Date(now - index * 1000).toISOString(), source: 'telemetry_mqtt' as const, timeBasis: 'collector_received_at' as const }))
  const tree = renderer.create(<TpmsPanel history={{ points, oldestAt: points[24].observedAt, latestAt: points[0].observedAt, limited: true }} now={now} />)
  act(() => tree.root.findByType('button').props.onClick())
  act(() => tree.root.findAllByType('button').find(button => button.children.join('') === '查看本机记录')!.props.onClick())
  expect(tree.root.findAllByProps({ className: 'readiness-item' })).toHaveLength(20)
  act(() => tree.root.findAllByType('button').find(button => button.children.join('') === '较早记录')!.props.onClick())
  expect(tree.root.findAllByProps({ className: 'readiness-item' })).toHaveLength(5)
  expect(tree.root.findAllByType('text').some(node => node.children.join('').includes('2/2 页'))).toBe(true)
  expect(tree.root.findAllByProps({ className: 'metric-value' }).every(node => node.children.join('') === '暂无数据')).toBe(true)
  tree.unmount()
})

it('shows display-only report values in details while keeping the evidence history empty', () => {
  const snapshot = parseTpmsDetails({ tpms_pressure_fl: 2.6 })
  const tree = renderer.create(<TpmsPanel snapshot={snapshot} report={{ fl: 2.6, fr: null, rl: null, rr: null }} history={{ points: [], oldestAt: null, latestAt: null, limited: false }} />)
  expect(tree.root.findAllByProps({ className: 'metric-value' }).map(node => node.children.join(''))).toEqual(['2.6', '暂无数据', '暂无数据', '暂无数据'])
  expect(tree.root.findAllByProps({ className: 'tpms-age' }).map(node => node.children.join(''))).toEqual(['观测未核验'])
  act(() => tree.root.findByType('button').props.onClick())
  expect(JSON.stringify(tree.toJSON())).toContain('另有仅供显示的胎压报告值')
  expect(JSON.stringify(tree.toJSON())).toContain('暂无合格的本机前台历史')
  tree.unmount()
})
