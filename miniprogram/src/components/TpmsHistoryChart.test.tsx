import React from 'react'
import renderer, { act } from 'react-test-renderer'
import { beforeEach, expect, it, vi } from 'vitest'
import type { TpmsPoint } from '../domain/tpms'
import type { TpmsHistory } from '../services/tpms_cache'
const canvas = vi.hoisted(() => ({ draws: [] as unknown[][][] }))
vi.mock('@tarojs/components', () => ({ Text: 'text', View: 'view', Button: 'button', Canvas: 'canvas' }))
vi.mock('@tarojs/taro', () => ({ default: {
  createSelectorQuery: () => ({ select: () => ({ boundingClientRect: (callback: (rect: unknown) => void) => ({ exec: () => callback({ width: 375, height: 200 }) }) }) }),
  createCanvasContext: () => {
    const commands: unknown[][] = []
    return Object.fromEntries(['setFillStyle', 'setStrokeStyle', 'setFontSize', 'fillRect', 'beginPath', 'moveTo', 'lineTo', 'stroke', 'fillText', 'arc', 'fill', 'draw'].map(name => [name, (...args: unknown[]) => { commands.push([name, ...args]); if (name === 'draw') canvas.draws.push(commands.slice()) }]))
  },
} }))
import TpmsHistoryChart, { tpmsChartData, TPMS_CHART_MAX_POINTS } from './TpmsHistoryChart'
import TpmsPanel from './TpmsPanel'
const now = Date.parse('2026-10-16T00:00:00Z')
const sample = (wheel: TpmsPoint['wheel'], pressureBar: number, at = now): TpmsPoint => ({ wheel, pressureBar, observedAt: new Date(at).toISOString(), source: 'telemetry_mqtt', timeBasis: 'collector_received_at' })
const history = (points: TpmsPoint[]): TpmsHistory => ({ points, limited: false, oldestAt: points[points.length - 1]?.observedAt ?? null, latestAt: points[0]?.observedAt ?? null })
beforeEach(() => { canvas.draws.length = 0 })
it('draws20k real source points within600-point native bridge budget while preserving every wheel extrema/first/last', () => {
  const wheels = ['fl', 'fr', 'rl', 'rr'] as const
  const source = Array.from({ length: 20_000 }, (_, index) => sample(wheels[index % 4], index === 1000 ? 0 : index === 1111 ? 15 : 2.7 + index % 13 / 100, now - index * 1000))
  const data = tpmsChartData(source, '30d', now)
  expect(data.source).toHaveLength(20_000); expect(data.plotted).toBe(TPMS_CHART_MAX_POINTS); expect(data.thinned).toBe(true)
  for (const group of data.groups) {
    const original = data.source.filter(point => point.wheel === group.wheel)
    expect(group.points[0]).toEqual(original[0]); expect(group.points[group.points.length - 1]).toEqual(original[original.length - 1])
    expect(Math.min(...group.points.map(point => point.pressureBar))).toBe(Math.min(...original.map(point => point.pressureBar)))
    expect(Math.max(...group.points.map(point => point.pressureBar))).toBe(Math.max(...original.map(point => point.pressureBar)))
  }
  let tree: renderer.ReactTestRenderer
  act(() => { tree = renderer.create(<TpmsHistoryChart history={history(source)} now={now} />) })
  const calls = canvas.draws[canvas.draws.length - 1]
  expect(calls.filter(call => call[0] === 'arc')).toHaveLength(600)
  expect(calls.length).toBeLessThan(2500)
  expect(calls.filter(call => call[0] === 'lineTo')).toHaveLength(2) // axes only, never interpolated measurements
  expect(JSON.stringify(tree!.root.findByType('canvas').props).length).toBeLessThan(500)
  expect(JSON.stringify(tree!.toJSON()).length).toBeLessThan(12_000)
  expect(JSON.stringify(tree!.toJSON())).toContain('20000')
  act(() => tree!.root.findAllByType('button').find(button => button.children.join('') === '前一个观测')!.props.onClick())
  expect(JSON.stringify(tree!.toJSON())).toContain(source[1].observedAt)
  tree!.unmount()
})
it('plots one genuine0 without synthetic samples and exposes original time/stale label after window selection', () => {
  const point = sample('fl', 0, now - 2 * 24 * 60 * 60 * 1000)
  let tree: renderer.ReactTestRenderer
  act(() => { tree = renderer.create(<TpmsHistoryChart history={history([point])} now={now} />) })
  expect(tree!.root.findAllByType('canvas')).toHaveLength(0)
  act(() => tree!.root.findAllByType('button').find(button => button.children.join('') === '7天')!.props.onClick())
  expect(tree!.root.findAllByType('canvas')).toHaveLength(1)
  const text = tree!.root.findAllByType('text').map(node => node.children.join('')).join('\n')
  expect(text).toContain('左前轮 · 0 bar'); expect(text).toContain(point.observedAt); expect(text).toContain('旧观测')
  const dots = canvas.draws[canvas.draws.length - 1].filter(call => call[0] === 'arc')
  expect(dots).toHaveLength(1); expect(dots[0].slice(1).every(value => typeof value === 'number' && Number.isFinite(value))).toBe(true)
  tree!.unmount()
})
it('rejects future/invalid/source-less/older-than30d props and renders no-history state without fake zero', () => {
  const invalid = [sample('fl', 1, now + 1), sample('fr', 2, now - 31 * 24 * 60 * 60 * 1000), { ...sample('rl', 3), source: 'phone_poll' }, { ...sample('rr', 3), timeBasis: null }, sample('fl', NaN)]
  expect(tpmsChartData(invalid, '30d', now).source).toEqual([])
  let tree: renderer.ReactTestRenderer
  act(() => { tree = renderer.create(<TpmsHistoryChart history={history([])} now={now} />) })
  expect(tree!.root.findAllByType('canvas')).toHaveLength(0); expect(canvas.draws).toHaveLength(0)
  expect(JSON.stringify(tree!.toJSON())).toContain('当前窗口没有合格本机离散观测')
  tree!.unmount()
})
it('keeps truncation and failed-save status visible while detailed evidence stays collapsed', () => {
  const tree = renderer.create(<TpmsPanel history={{ ...history([]), limited: true }} cacheReason="cache_budget" now={now} />)
  expect(tree.root.findAllByProps({ className: 'tpms-evidence' })).toHaveLength(0)
  const text = tree.root.findAllByType('text').map(node => node.children.join('')).join('\n')
  expect(text).toContain('本机胎压记录已按保留上限截取')
  expect(text).toContain('本次胎压观测未保存')
  tree.unmount()
})
