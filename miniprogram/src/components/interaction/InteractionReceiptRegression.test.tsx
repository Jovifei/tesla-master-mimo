import React from 'react'
import renderer, { act } from 'react-test-renderer'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { RecordedChargeInterval } from '../../services/analysis_types'
const platform = vi.hoisted(() => ({ hide: new Set<() => void>(), show: new Set<() => void>(), request: vi.fn(), write: vi.fn() }))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Image: 'image' }))
vi.mock('@tarojs/taro', () => ({ default: {
  request: platform.request, setStorageSync: platform.write,
  onAppHide: (cb: () => void) => platform.hide.add(cb), offAppHide: (cb: () => void) => platform.hide.delete(cb),
  onAppShow: (cb: () => void) => platform.show.add(cb), offAppShow: (cb: () => void) => platform.show.delete(cb),
} }))
import { ConnectionVisual, EmptyHistoryVisual, ParkedChargeTimeline, RouteInteractionVisual, VehicleOpeningsVisual } from './InteractionVisuals'
let view: renderer.ReactTestRenderer | null = null
function render(node: React.ReactElement) { act(() => { view = renderer.create(node) }) }
function update(node: React.ReactElement) { act(() => view!.update(node)) }
function text() { return view!.root.findAllByType('text').map(node => node.children.join('')).join('\n') }
function endpoint(name: 'A' | 'B') { return view!.root.findByProps({ 'data-endpoint': name }) }
function intervals() { return view!.root.findAllByProps({ className: 'recorded-charge-select' }) }
const disclosure = '本条已保存会话的时间区间；不代表两条会话之间一直未充电。'
const sessions: RecordedChargeInterval[] = [
  { id: 'same-id', startDate: '2026-11-06T01:00:00Z', endDate: '2026-11-06T01:20:00Z', energyKwh: 0 },
  { id: 'same-id', startDate: '2026-11-06T02:00:00Z', endDate: null, energyKwh: null },
]
beforeEach(() => { platform.hide.clear(); platform.show.clear(); platform.request.mockReset(); platform.write.mockReset() })
afterEach(() => {
  if (view) act(() => view!.unmount())
  view = null
  expect(platform.request).not.toHaveBeenCalled(); expect(platform.write).not.toHaveBeenCalled()
  expect(platform.hide.size).toBe(0); expect(platform.show.size).toBe(0)
})
it('resets endpoint focus synchronously on a changed receipt without a parent key', () => {
  render(<RouteInteractionVisual start="Synthetic A" end="Synthetic B" />)
  act(() => endpoint('B').props.onClick())
  expect(endpoint('B').props['data-selected']).toBe(true)
  update(<RouteInteractionVisual start="Replacement" end={null} />)
  expect(endpoint('A').props['data-selected']).toBe(true)
  expect(endpoint('B').props['data-selected']).toBe(false)
  expect(text()).not.toContain('Synthetic B'); expect(text()).toContain('地点未知')
  update(<RouteInteractionVisual start="Synthetic A" end="Synthetic B" />)
  expect(endpoint('A').props['data-selected']).toBe(true)
})
it('keeps focus through a same-receipt rerender, rather than resetting on every object allocation', () => {
  render(<RouteInteractionVisual start="A" end="B" />)
  act(() => endpoint('B').props.onClick())
  update(<RouteInteractionVisual start="A" end="B" />)
  expect(endpoint('B').props['data-selected']).toBe(true)
  expect(endpoint('B').props.ariaLabel).toContain('已选中')
})
it('suspends endpoint transitions when backgrounded, inactive or reduced-motion', () => {
  render(<RouteInteractionVisual start="A" end="B" />)
  expect(view!.root.findAllByProps({ 'data-motion': true })).toHaveLength(1)
  act(() => platform.hide.forEach(cb => cb()))
  expect(view!.root.findAllByProps({ 'data-motion': true })).toHaveLength(0)
  act(() => platform.show.forEach(cb => cb()))
  expect(view!.root.findAllByProps({ 'data-motion': true })).toHaveLength(1)
  update(<RouteInteractionVisual start="A" end="B" reducedMotion />)
  expect(view!.root.findAllByProps({ 'data-motion': true })).toHaveLength(0)
  update(<RouteInteractionVisual start="A" end="B" active={false} />)
  expect(view!.root.findAllByProps({ 'data-motion': true })).toHaveLength(0)
  expect(view!.root.findAllByProps({ className: 'interaction-visual route-interaction route-motion-paused' })).toHaveLength(1)
})
it('expands only the selected interval when numeric provider IDs are reused', () => {
  render(<ParkedChargeTimeline sessions={sessions} />)
  act(() => intervals()[0].props.onClick())
  expect(intervals().map(node => node.props['data-selected'])).toEqual([true, false])
  expect(text().split(disclosure).length - 1).toBe(1)
  act(() => intervals()[1].props.onClick())
  expect(intervals().map(node => node.props['data-selected'])).toEqual([false, true])
  expect(text().split(disclosure).length - 1).toBe(1)
  expect(text()).toContain('0.00 kWh'); expect(text()).toContain('结束未记录')
})
it('clears an expanded interval when receipt boundaries change, disappear or return', () => {
  render(<ParkedChargeTimeline sessions={sessions} />)
  act(() => intervals()[0].props.onClick())
  update(<ParkedChargeTimeline sessions={[{ ...sessions[0], endDate: '2026-11-06T01:30:00Z' }]} />)
  expect(text()).not.toContain(disclosure)
  act(() => intervals()[0].props.onClick())
  update(<ParkedChargeTimeline sessions={[]} />)
  expect(intervals()).toHaveLength(0)
  update(<ParkedChargeTimeline sessions={sessions} />)
  expect(text()).not.toContain(disclosure)
})
it.each([Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY])('never formats non-finite charge evidence %s as an energy measurement', value => {
  render(<ParkedChargeTimeline sessions={[{ ...sessions[0], energyKwh: value }, { ...sessions[1], energyKwh: 0 }]} />)
  expect(text()).toContain('充入电量未记录'); expect(text()).toContain('0.00 kWh')
  expect(text()).not.toMatch(/NaN|Infinity/)
})
it('keeps Tesla session establishment and optional WeChat confirmation distinct', () => {
  render(<ConnectionVisual phase="connected" kind="tesla" />)
  expect(text()).toContain('MateLink 会话已建立'); expect(text()).not.toContain('微信绑定已确认')
  update(<ConnectionVisual phase="connected" kind="wechat" />)
  expect(text()).toContain('微信绑定已确认'); expect(text()).not.toContain('Tesla')
  update(<ConnectionVisual phase="unavailable" kind="wechat" />)
  expect(text()).not.toContain('✓'); expect(text()).not.toContain('微信绑定已确认')
})
it('does not turn unknown openings into closed or a failed history read into an empty history', () => {
  render(<VehicleOpeningsVisual doors={null} windows={false} frunk={true} trunk={undefined} />)
  act(() => view!.root.findByType('button').props.onClick({ stopPropagation() {} }))
  expect(text()).toContain('车门：状态未知'); expect(text()).toContain('车窗：报告关闭')
  expect(view!.root.findAllByProps({ 'data-opening': 'doors' })).toHaveLength(0)
  expect(view!.root.findAllByProps({ 'data-opening': 'frunk' })).toHaveLength(1)
  update(<EmptyHistoryVisual kind="drive" failed />)
  expect(view!.root.findAllByProps({ 'data-status': 'unavailable' })).toHaveLength(1)
  expect(view!.root.findAllByProps({ 'data-status': 'empty' })).toHaveLength(0)
})
