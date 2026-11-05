import React, { useState } from 'react'
import renderer, { act } from 'react-test-renderer'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { TpmsSnapshot, TpmsWheel } from '../../domain/tpms'
const platform = vi.hoisted(() => ({ hide: new Set<() => void>(), show: new Set<() => void>(), request: vi.fn(), write: vi.fn() }))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Image: 'image', Canvas: 'canvas' }))
vi.mock('@tarojs/taro', () => ({ default: { request: platform.request, setStorageSync: platform.write,
  onAppHide: (cb: () => void) => platform.hide.add(cb), offAppHide: (cb: () => void) => platform.hide.delete(cb),
  onAppShow: (cb: () => void) => platform.show.add(cb), offAppShow: (cb: () => void) => platform.show.delete(cb),
} }))
import { ConnectionVisual, EmptyHistoryVisual, ParkedChargeTimeline, RouteInteractionVisual, TpmsVehicleVisual, VehicleOpeningsVisual } from './InteractionVisuals'
import TpmsPanel from '../TpmsPanel'
import LoadingIndicator from '../LoadingIndicator'
import { connectionVisualPhase, hasVisualRouteEndpoints, visualMotionAllowed } from '../../domain/interaction_visuals'
import { restoreTpmsSnapshot } from '../../domain/tpms'
const now = Date.parse('2026-11-04T00:00:00Z')
const snapshot = (): TpmsSnapshot => {
  const empty = restoreTpmsSnapshot(null, now)
  empty.wheels.fl = { pressureBar: 0, observedAt: new Date(now).toISOString(), source: 'telemetry_mqtt', timeBasis: 'collector_received_at', state: 'observed', reason: null }
  empty.wheels.fr = { ...empty.wheels.fl, pressureBar: 2.6, observedAt: new Date(now - 3600000).toISOString() }
  return empty
}
let view: renderer.ReactTestRenderer | null = null
const render = (node: React.ReactElement) => { act(() => { view = renderer.create(node) }); return view! }
const text = () => view!.root.findAllByType('text').map(node => node.children.join('')).join('\n')
beforeEach(() => { platform.hide.clear(); platform.show.clear(); platform.request.mockReset(); platform.write.mockReset() })
afterEach(() => { if (view) act(() => view!.unmount()); view = null; expect(platform.request).not.toHaveBeenCalled(); expect(platform.write).not.toHaveBeenCalled() })
it.each(['idle', 'connecting', 'action_required', 'connected', 'unavailable'] as const)('renders actual %s phase without manufacturing success from elapsed animation', phase => {
  render(<ConnectionVisual phase={phase} kind="vehicle" />)
  expect(view!.root.findAllByProps({ 'data-phase': phase })).toHaveLength(1)
  expect(text().includes('✓')).toBe(phase === 'connected')
  expect(view!.root.findByType('image').props.src).not.toMatch(/^https?:/)
  act(() => { platform.hide.forEach(cb => cb()) })
  expect(view!.root.findAllByProps({ 'data-motion': true })).toHaveLength(0)
  act(() => { platform.show.forEach(cb => cb()) })
  expect(view!.root.findAllByProps({ 'data-phase': phase })).toHaveLength(1)
})
it('pauses background, inactive-page and reduced-motion visuals, and removes exactly its listeners', () => {
  render(<ConnectionVisual phase="connecting" />)
  expect(view!.root.findAllByProps({ 'data-motion': true })).toHaveLength(1)
  act(() => view!.update(<ConnectionVisual phase="connecting" active={false} />))
  expect(view!.root.findAllByProps({ 'data-motion': true })).toHaveLength(0)
  act(() => view!.update(<ConnectionVisual phase="connecting" reducedMotion />))
  expect(view!.root.findAllByProps({ 'data-motion': true })).toHaveLength(0)
  act(() => view!.unmount()); view = null
  expect(platform.show.size).toBe(0); expect(platform.hide.size).toBe(0)
  expect(visualMotionAllowed(true, false, true)).toBe(true)
  expect(visualMotionAllowed(false, false, true)).toBe(false)
  expect(visualMotionAllowed(true, true, true)).toBe(false)
  expect(connectionVisualPhase({ verified: true, failed: true })).toBe('unavailable')
  expect(connectionVisualPhase({ verified: true, pending: true })).toBe('connecting')
  expect(connectionVisualPhase({ verified: true, actionRequired: true })).toBe('action_required')
})
it('retains the original loading logo with a paused bounded beacon instead of replacing it with a new brand', () => {
  render(<LoadingIndicator label="Synthetic loading" reducedMotion />)
  expect(view!.root.findByType('image').props.src).toContain('matelink_loading_logo')
  expect(view!.root.findByProps({ className: 'loading-beacon' }).props.style).toMatchObject({ animationPlayState: 'paused', animationIterationCount: '2' })
})
it('renders qualified zero, stale and unknown per wheel and selects/reset the actual chart', () => {
  const data = snapshot()
  render(<TpmsPanel snapshot={data} now={now} history={{ points: [
    { wheel: 'fl', pressureBar: 0, observedAt: new Date(now).toISOString(), source: 'telemetry_mqtt', timeBasis: 'collector_received_at' },
    { wheel: 'fr', pressureBar: 2.6, observedAt: new Date(now - 3600000).toISOString(), source: 'telemetry_mqtt', timeBasis: 'collector_received_at' },
  ], limited: false, oldestAt: new Date(now - 3600000).toISOString(), latestAt: new Date(now).toISOString() }} />)
  expect(text()).toContain('0.00 bar'); expect(text()).toContain('旧观测'); expect(text()).toContain('暂无合格观测')
  act(() => view!.root.findByProps({ 'data-wheel': 'fl' }).props.onClick())
  expect(view!.root.findByProps({ 'data-wheel': 'fl' }).props['data-selected']).toBe(true)
  expect(text()).toContain('左前轮 · 绘制 1/1 点'); expect(text()).toContain('右前轮 · 绘制 0/0 点')
  act(() => view!.root.findByProps({ 'data-wheel': 'fl' }).props.onClick())
  expect(text()).toContain('右前轮 · 绘制 1/1 点')
  act(() => view!.update(<TpmsPanel key="new-account-car" snapshot={null} now={now} />))
  expect(text()).not.toContain('0.00 bar'); expect(text()).not.toContain('2.60 bar')
})
it('does not color unqualified report values as measured and rejects future/source-conflicting wheel receipts', () => {
  const data = snapshot()
  data.wheels.fl.observedAt = new Date(now + 1).toISOString()
  data.wheels.fr = { ...data.wheels.fr, source: null }
  const Wrapper = () => { const [selected, set] = useState<TpmsWheel | null>(null); return <TpmsVehicleVisual snapshot={data} now={now} selected={selected} onSelect={set} /> }
  render(<Wrapper />)
  expect(view!.root.findAllByProps({ 'data-state': 'unknown' })).toHaveLength(4)
  expect(text()).not.toContain('正常'); expect(text()).not.toContain('0.00 bar')
})
it('A/B focus is local selection, missing endpoint stays unknown, and keyed scope changes reset it', () => {
  render(<RouteInteractionVisual key="one" start="Synthetic origin" end="Synthetic destination" />)
  act(() => view!.root.findByProps({ 'data-endpoint': 'B' }).props.onClick())
  expect(view!.root.findByProps({ 'data-endpoint': 'B' }).props.className).toContain('route-endpoint-selected')
  expect(text()).toContain('非实际轨迹'); expect(hasVisualRouteEndpoints('A', null)).toBe(false)
  act(() => view!.update(<RouteInteractionVisual key="two" start={null} end="New destination" />))
  expect(text()).toContain('地点未知'); expect(text()).not.toContain('Synthetic origin')
  expect(view!.root.findByProps({ 'data-endpoint': 'A' }).props.className).toContain('route-endpoint-selected')
  expect(view!.root.findAllByType('map')).toHaveLength(0)
})
it('opens the category diagram without inferring individual door, missing statuses or network commands', () => {
  render(<VehicleOpeningsVisual doors windows={null} frunk={false} trunk />)
  expect(view!.root.findAllByProps({ className: 'opening-position-board' })).toHaveLength(0)
  act(() => view!.root.findByType('button').props.onClick({ stopPropagation() {} }))
  expect(view!.root.findAllByProps({ 'data-opening': 'doors' })).toHaveLength(1)
  expect(view!.root.findAllByProps({ 'data-opening': 'windows' })).toHaveLength(0)
  expect(text()).toContain('车窗：状态未知'); expect(text()).toContain('前备箱：报告关闭')
  expect(text()).toContain('不能据此判断具体哪一扇')
})
it('two recorded charges and an unfinished interval never fabricate a continuous stopped session or energy', () => {
  render(<ParkedChargeTimeline sessions={[
    { id: '1', startDate: '2026-11-03T00:10:00Z', endDate: '2026-11-03T00:30:00Z', energyKwh: 0 },
    { id: '2', startDate: '2026-11-03T01:10:00Z', endDate: null, energyKwh: null },
  ]} />)
  expect(view!.root.findAllByProps({ className: 'recorded-charge-row' })).toHaveLength(2)
  expect(text()).toContain('0.00 kWh'); expect(text()).toContain('结束未记录')
  expect(text()).toContain('不据空白区间推断停充或耗电')
  act(() => view!.root.findAllByType('button')[0].props.onClick())
  expect(text()).toContain('不代表两条会话之间一直未充电')
})
it.each(['drive', 'charge'] as const)('empty %s art never contains synthetic measurements or actions', kind => {
  render(<EmptyHistoryVisual kind={kind} />)
  expect(view!.root.findAllByType('button')).toHaveLength(0)
  expect(text()).not.toMatch(/\d+\s*(kWh|bar|%)/)
})
