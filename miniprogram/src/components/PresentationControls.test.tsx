import React from 'react'
import TestRenderer, { act } from 'react-test-renderer'
import { afterEach, expect, it, vi } from 'vitest'
import { activityLevel, civilDay, historyDayGroups, normalizeAppearance, verticalDayTarget } from '../domain/ui_presentation'

const storage = vi.hoisted(() => ({ values: new Map<string, unknown>(), writes: [] as string[], fail: false }))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Switch: 'switch' }))
vi.mock('@tarojs/taro', () => ({ default: {
  getStorageSync: (key: string) => storage.values.get(key),
  setStorageSync: (key: string, value: unknown) => { if (storage.fail) throw new Error('quota'); storage.writes.push(key); storage.values.set(key, value) },
} }))
import { activityNumber, ChargeActivityGrid, PresentationPreferences, VerticalDayStack } from './PresentationControls'
import { APPEARANCE_KEY, readAppearance, writeAppearance } from '../services/appearance'
let tree: TestRenderer.ReactTestRenderer | null = null
const groups = historyDayGroups([
  { key: 'd0', date: '2026-11-10T12:00:00+09:00' }, { key: 'p0', date: '2026-11-10T11:00:00+09:00' },
  { key: 'd1', date: '2026-11-09T12:00:00+09:00' }, { key: 'p1', date: '2026-11-09T11:00:00+09:00' },
  { key: 'd2', date: '2026-11-08T12:00:00+09:00' }, { key: 'd3', date: '2026-11-07T12:00:00+09:00' },
], row => row.date)
function render(scope = 'A') { return <VerticalDayStack groups={groups} scopeKey={scope}>{group => <span>{group.items.map(item => item.key).join(',')}</span>}</VerticalDayStack> }
afterEach(async () => { if (tree) await act(async () => tree!.unmount()); tree = null; storage.fail = false; writeAppearance({ palette: 'neutral', reducedMotion: false }); storage.values.clear(); storage.writes = [] })
it('defaults to C, accepts only A/C and preserves valid zero/unknown levels', () => {
  expect(normalizeAppearance(null)).toEqual({ palette: 'neutral', reducedMotion: false })
  expect(normalizeAppearance({ palette: 'blue', reducedMotion: 'true' })).toEqual(normalizeAppearance(null))
  expect(normalizeAppearance({ palette: 'cream', reducedMotion: true })).toEqual({ palette: 'cream', reducedMotion: true })
  expect(activityLevel(0, 50)).toBe(0); expect(activityLevel(null, 50)).toBe(0); expect(activityLevel(50, 50)).toBe(4)
  expect(activityLevel(NaN, 5)).toBe(0); expect(activityLevel(-1, 5)).toBe(0)
  expect(civilDay('not-a-date')).toBeNull()
  expect(historyDayGroups([{ date: null }], row => row.date)[0].key).toBe('unknown')
})
it('vertical policy is bounded, axis-aware and rejects nonfinite input', () => {
  expect(verticalDayTarget(0, 4, 0, -70)).toBe(1)
  expect(verticalDayTarget(1, 4, 0, 70)).toBe(0)
  expect(verticalDayTarget(0, 4, 0, 80)).toBe(0)
  expect(verticalDayTarget(3, 4, 0, -80)).toBe(3)
  expect(verticalDayTarget(1, 4, 80, -60)).toBe(1)
  expect(verticalDayTarget(1, 4, 0, -20)).toBe(1)
  expect(verticalDayTarget(1, 4, NaN, 80)).toBe(1)
})
it('renders only current-day content, exposes two rear dates, switches vertically and resets per scope', async () => {
  await act(async () => { tree = TestRenderer.create(render()) })
  const body = () => tree!.root.findByType('span').children.join('')
  expect(body()).toBe('d0,p0')
  const rear = () => tree!.root.findAllByProps({ className: 'day-stack-date' })
  expect(rear()).toHaveLength(2)
  expect(rear().map(node => node.children.join('')).join('')).not.toContain('d1')
  const gesture = () => tree!.root.findByProps({ className: 'day-stack-header' })
  await act(async () => {
    gesture().props.onTouchStart({ touches: [{ pageX: 30, pageY: 100 }] })
    gesture().props.onTouchEnd({ changedTouches: [{ pageX: 32, pageY: 20 }] })
  })
  expect(body()).toBe('d1,p1')
  await act(async () => {
    gesture().props.onTouchStart({ touches: [{ pageX: 30, pageY: 20 }] })
    gesture().props.onTouchEnd({ changedTouches: [{ pageX: 32, pageY: 100 }] })
  })
  expect(body()).toBe('d0,p0')
  await act(async () => rear()[0].props.onClick())
  expect(body()).toBe('d2')
  await act(async () => tree!.update(render('B')))
  expect(body()).toBe('d0,p0')
  expect(tree!.root.findByProps({ className: 'day-stack-body' }).props.onTouchEnd).toBeUndefined()
  await act(async () => {
    gesture().props.onTouchStart({ touches: [{ pageX: 30, pageY: 100 }] })
    gesture().props.onTouchCancel()
    gesture().props.onTouchEnd({ changedTouches: [{ pageX: 30, pageY: 0 }] })
  })
  expect(body()).toBe('d0,p0')
})
it('stores only presentation preferences and reports write failure without clearing user data', async () => {
  storage.values.set('account', 'keep')
  await act(async () => { tree = TestRenderer.create(<PresentationPreferences />) })
  const cream = () => tree!.root.findAllByType('button').find(node => node.props.ariaLabel.startsWith('A ·'))!
  await act(async () => cream().props.onClick())
  expect(readAppearance().palette).toBe('cream')
  expect(storage.writes).toEqual([APPEARANCE_KEY])
  expect(storage.values.get('account')).toBe('keep')
  expect(cream().props.ariaLabel).toContain('已选择')
  storage.fail = true
  await act(async () => tree!.root.findByType('switch' as React.ElementType).props.onChange({ detail: { value: true } }))
  expect(JSON.stringify(tree!.toJSON())).toContain('未保存到本机')
  expect(readAppearance().reducedMotion).toBe(true)
})
it('charge cells retain zero, unknown and full-date identity with bounded paging', async () => {
  const cells = Array.from({ length: 32 }, (_, i) => ({ key: `2026-10-${String(i + 1).padStart(2, '0')}`, label: String(i), value: i === 30 ? null : i === 29 ? 0 : i, display: i === 30 ? '未知' : `${i === 29 ? 0 : i} kWh` }))
  await act(async () => { tree = TestRenderer.create(<ChargeActivityGrid cells={cells} unit="kWh" />) })
  const buttons = () => tree!.root.findAllByType('button').filter(node => node.props.className?.includes('charge-activity-cell'))
  expect(buttons()).toHaveLength(4)
  const zero = buttons().find(node => node.props.ariaLabel.endsWith('，0 kWh'))!
  await act(async () => zero.props.onClick())
  expect(tree!.root.findByProps({ className: 'charge-activity-selection' }).children.join('')).toBe('2026-10-30 · 0 kWh')
  await act(async () => buttons().find(node => node.props.ariaLabel.endsWith('，未知'))!.props.onClick())
  expect(tree!.root.findByProps({ className: 'charge-activity-selection' }).children.join('')).toContain('未知')
})

it('preserves cost cents and estimate labels while rejecting invalid numeric cells', () => {
  const cell = { key: '2026-11-09', label: '11/09', value: 1.25, display: '含估算 1.25 CNY', precision: 2, approximate: true }
  expect(activityNumber(cell, 'CNY')).toBe('≈1.25')
  expect(activityNumber({ ...cell, value: 0, approximate: false }, 'CNY')).toBe('0.00')
  expect(activityNumber({ ...cell, value: -1 }, 'CNY')).toBe('—')
  expect(activityNumber({ ...cell, value: Infinity }, 'CNY')).toBe('—')
  expect(civilDay('2026-11-09')).toBe('2026-11-09')
  expect(civilDay('2026-02-30')).toBeNull()
  expect(civilDay('2026-02-30T01:00:00Z')).toBeNull()
})
it('validates recognizable calendar prefixes and preserves local parsing for valid dates', () => {
  const localDay = (value: string) => {
    const local = new Date(value)
    return `${local.getFullYear()}-${String(local.getMonth() + 1).padStart(2, '0')}-${String(local.getDate()).padStart(2, '0')}`
  }
  expect(civilDay('2026-02-28')).toBe('2026-02-28')
  for (const value of [
    '2026-02-28 12:00:00', '2026-02-28T12:00:00Z', '2026-02-28t12:00:00Z',
    '2024-02-29T12:00:00Z', '2026-2-3', '2026/02/28', '2024/2/29',
    ' 2026/2/28 12:00:00 ', '2026-002-028', '002026/02/28', '0001/02/29',
    '2/28/2026', '02-29-2024', '2/29/2000', '2/29/24', '32/2/29', '50/2/28',
    '2026-03-01T00:30:00+08:00',
  ]) expect(civilDay(value)).toBe(localDay(value))
  for (const value of [
    '2026-02-30 12:00:00', '2026-02-30T12:00:00Z', '2026-02-30t12:00:00Z',
    '2026-02-29T12:00:00Z', '2026-2-30', '2026/02/30', '2025/2/29',
    ' 2026/2/30 12:00:00 ', '2026-002-030', '002026/02/30',
    '2/30/2026', ' 2/30/2026 12:00:00 ', '2/29/1900', '2/29/50', 'not-a-date',
  ]) expect(civilDay(value)).toBeNull()
})
it('keeps year-first pure dates on their stated calendar day across time zones', () => {
  for (const value of ['2026-02-03', ' 2026-02-03 ', '2026-2-3', ' 2026/02/03 ', '2026/2/3']) {
    expect(civilDay(value)).toBe('2026-02-03')
  }
  expect(civilDay(' 2026-02-30 ')).toBeNull()
})
it('refreshes the selected day, forgets a removed selection and cancels multi-touch', async () => {
  const scene = (data = groups) => <VerticalDayStack groups={data} scopeKey="stable">{group => <span>{group.items.map(item => item.key).join(',')}</span>}</VerticalDayStack>
  await act(async () => { tree = TestRenderer.create(scene()) })
  const body = () => tree!.root.findByType('span').children.join('')
  const gesture = () => tree!.root.findByProps({ className: 'day-stack-header' })
  await act(async () => tree!.root.findByProps({ ariaLabel: '较旧日期' }).props.onClick())
  expect(body()).toBe('d1,p1')
  const changed = groups.map((group, index) => index === 1 ? { ...group, items: [{ key: 'corrected', date: group.key }] } : group)
  await act(async () => tree!.update(scene(changed)))
  expect(body()).toBe('corrected')
  await act(async () => {
    gesture().props.onTouchStart({ touches: [{ pageX: 0, pageY: 100 }] })
    gesture().props.onTouchMove({ touches: [{ pageX: 0, pageY: 80 }, { pageX: 10, pageY: 80 }] })
    gesture().props.onTouchEnd({ changedTouches: [{ pageX: 0, pageY: 0 }] })
  })
  expect(body()).toBe('corrected')
  await act(async () => tree!.update(scene(groups.slice(0, 1))))
  expect(body()).toBe('d0,p0')
  await act(async () => tree!.update(scene()))
  expect(body()).toBe('d0,p0')
})
