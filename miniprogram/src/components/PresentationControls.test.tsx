import React from 'react'
import TestRenderer, { act } from 'react-test-renderer'
import { afterEach, expect, it, vi } from 'vitest'
import { chargeCellPaint, activityLevel, calendarHistoryGroups, daySwipeTarget, civilDay, historyDayGroups, normalizeAppearance, verticalDayTarget } from '../domain/ui_presentation'

const storage = vi.hoisted(() => ({ values: new Map<string, unknown>(), writes: [] as string[], fail: false }))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Switch: 'switch' }))
vi.mock('@tarojs/taro', () => ({ default: {
  getStorageSync: (key: string) => storage.values.get(key),
  setStorageSync: (key: string, value: unknown) => { if (storage.fail) throw new Error('quota'); storage.writes.push(key); storage.values.set(key, value) },
} }))
import { activityNumber, ChargeActivityGrid, PresentationPreferences, VerticalDayStack } from './PresentationControls'
import { APPEARANCE_KEY, readAppearance, writeAppearance } from '../services/appearance'
let tree: TestRenderer.ReactTestRenderer | null = null
const localDate = (day: number, hour: number) => new Date(2026, 10, day, hour).toISOString()
const groups = historyDayGroups([
  { key: 'd0', date: localDate(10, 12) }, { key: 'p0', date: localDate(10, 11) },
  { key: 'd1', date: localDate(9, 12) }, { key: 'p1', date: localDate(9, 11) },
  { key: 'd2', date: localDate(8, 12) }, { key: 'd3', date: localDate(7, 12) },
], row => row.date)
function render(scope = 'A') { return <VerticalDayStack groups={groups} scopeKey={scope}>{group => <span>{group.items.map(item => item.key).join(',')}</span>}</VerticalDayStack> }
afterEach(async () => { if (tree) await act(async () => tree!.unmount()); tree = null; storage.fail = false; writeAppearance({ palette: 'neutral', reducedMotion: false, dayAxis: 'vertical' }); storage.values.clear(); storage.writes = [] })
it('uses vertical gestures for a legacy preference while retaining an explicit horizontal preference', async () => {
  storage.values.set(APPEARANCE_KEY, { palette: 'cream', reducedMotion: true })
  expect(readAppearance()).toEqual({ palette: 'cream', reducedMotion: true, dayAxis: 'vertical' })
  await act(async () => { tree = TestRenderer.create(render()) })
  const gesture = () => tree!.root.findByProps({ className: 'day-stack-header' })
  expect(tree!.root.findByProps({ className: 'day-stack axis-vertical reduce-motion' })).toBeDefined()
  await act(async () => { gesture().props.onTouchStart({ touches: [{ pageX: 10, pageY: 100 }] }); gesture().props.onTouchEnd({ changedTouches: [{ pageX: 10, pageY: 20 }] }) })
  expect(tree!.root.findByType('span').children.join('')).toBe('d1,p1')
  expect(tree!.root.findByProps({ className: 'day-stack-body' }).props.onTouchEnd).toBeUndefined()
  await act(async () => { writeAppearance({ dayAxis: 'horizontal' }) })
  expect(readAppearance().dayAxis).toBe('horizontal')
  expect(storage.values.get(APPEARANCE_KEY)).toMatchObject({ dayAxis: 'horizontal', palette: 'cream', reducedMotion: true })
  await act(async () => { gesture().props.onTouchStart({ touches: [{ pageX: 100, pageY: 10 }] }); gesture().props.onTouchEnd({ changedTouches: [{ pageX: 20, pageY: 10 }] }) })
  expect(tree!.root.findByType('span').children.join('')).toBe('d2')
})
it('shows only two older date layers after selecting any date and keeps unknown records reachable', async () => {
  const week = calendarHistoryGroups(groups, '2026-11-04', '2026-11-10')
  const dates = [...week, { key: 'unknown', label: '日期待确认', items: [{ key: 'unknown-record', date: 'not-a-date' }] }]
  await act(async () => { tree = TestRenderer.create(<VerticalDayStack groups={dates} scopeKey="older-only">{group => <span>{group.items.map(row => row.key).join(',')}</span>}</VerticalDayStack>) })
  const front = () => tree!.root.findByProps({ className: 'day-stack-heading-row' }).findByProps({ className: 'section-title' }).children.join('')
  const rear = () => tree!.root.findAllByProps({ className: 'day-stack-date' }).map(node => node.children.join(''))
  expect(front()).toContain('2026-11-10')
  expect(rear()).toHaveLength(2)
  expect(rear().join(' ')).toContain('2026-11-09')
  expect(rear().join(' ')).toContain('2026-11-08')
  await act(async () => tree!.root.findAllByProps({ className: 'day-stack-date' }).find(node => node.children.join('').includes('2026-11-09'))!.props.onClick())
  expect(front()).toContain('2026-11-09')
  expect(rear()).toHaveLength(2)
  expect(rear().join(' ')).toContain('2026-11-08')
  expect(rear().join(' ')).toContain('2026-11-07')
  expect(rear().join(' ')).not.toContain('2026-11-09')
  expect(rear().join(' ')).not.toContain('2026-11-10')
  await act(async () => tree!.root.findByProps({ ariaLabel: '较新日期' }).props.onClick())
  expect(front()).toContain('2026-11-10')
  for (let i = 0; i < 6; i++) await act(async () => tree!.root.findByProps({ ariaLabel: '较旧日期' }).props.onClick())
  expect(front()).toContain('2026-11-04')
  expect(rear()).toHaveLength(0)
  expect(tree!.root.findByProps({ ariaLabel: '较旧日期' }).props.disabled).toBe(true)
  await act(async () => tree!.root.findAllByType('button').find(node => node.children.join('') === '查看日期待确认')!.props.onClick())
  expect(front()).toBe('日期待确认')
  expect(tree!.root.findByType('span').children.join('')).toBe('unknown-record')
})
it('starts at the supplied date without reordering future records and returns there from unknown', async () => {
  const dates = [
    { key: '2026-11-11', label: 'future', items: ['future'] },
    { key: '2026-11-10', label: 'today', items: [] as string[] },
    { key: '2026-11-09', label: 'past', items: ['past'] },
    { key: 'unknown', label: '未知', items: ['unknown'] },
  ]
  const scene = (scope = 'A', current = dates) => <VerticalDayStack groups={current} initialKey="2026-11-10" scopeKey={scope}>{group => <span>{group.items.join(',')}</span>}</VerticalDayStack>
  await act(async () => { tree = TestRenderer.create(scene()) })
  const front = () => tree!.root.findByProps({ className: 'day-stack-heading-row' }).findByProps({ className: 'section-title' }).children.join('')
  expect(front()).toContain('2026-11-10')
  expect(JSON.stringify(tree!.toJSON())).toContain('当天尚无已返回记录')
  expect(tree!.root.findByProps({ className: 'day-stack-body' }).props.onTouchEnd).toBeUndefined()
  expect(tree!.root.findAllByProps({ className: 'day-stack-date' }).map(node => node.children.join('')).join('')).not.toContain('2026-11-11')
  await act(async () => tree!.root.findByProps({ ariaLabel: '较新日期' }).props.onClick())
  expect(front()).toContain('2026-11-11')
  expect(tree!.root.findByType('span').children.join('')).toBe('future')
  await act(async () => tree!.root.findByProps({ className: 'day-stack-close' }).props.onClick())
  expect(front()).toContain('2026-11-10')
  await act(async () => tree!.root.findByProps({ ariaLabel: '较旧日期' }).props.onClick())
  expect(front()).toContain('2026-11-09')
  expect(tree!.root.findByProps({ ariaLabel: '较旧日期' }).props.disabled).toBe(true)
  await act(async () => tree!.update(scene('A', [...dates])))
  expect(front()).toContain('2026-11-09')
  const unknown = tree!.root.findAllByType('button').find(node => node.children.join('') === '查看日期待确认')!
  await act(async () => unknown.props.onClick())
  expect(front()).toBe('日期待确认')
  expect(tree!.root.findByType('span').children.join('')).toBe('unknown')
  expect(tree!.root.findByProps({ ariaLabel: '较新日期' }).props.disabled).toBe(true)
  expect(tree!.root.findByProps({ ariaLabel: '较旧日期' }).props.disabled).toBe(true)
  await act(async () => tree!.root.findByProps({ className: 'day-stack-close' }).props.onClick())
  expect(front()).toContain('2026-11-10')
  await act(async () => tree!.update(scene('B')))
  expect(front()).toContain('2026-11-10')
})
it('defaults to C, accepts only A/C and preserves valid zero/unknown levels', () => {
  expect(normalizeAppearance(null)).toEqual({ palette: 'neutral', reducedMotion: false, dayAxis: 'vertical' })
  expect(normalizeAppearance({ palette: 'blue', reducedMotion: 'true' })).toEqual(normalizeAppearance(null))
  expect(normalizeAppearance({ palette: 'cream', reducedMotion: true, dayAxis: 'horizontal' })).toEqual({ palette: 'cream', reducedMotion: true, dayAxis: 'horizontal' })
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
it('renders only current-day content, switches with the saved horizontal direction and resets per scope', async () => {
  writeAppearance({ dayAxis: 'horizontal' })
  await act(async () => { tree = TestRenderer.create(render()) })
  const body = () => tree!.root.findByType('span').children.join('')
  expect(body()).toBe('d0,p0')
  const rear = () => tree!.root.findAllByProps({ className: 'day-stack-date' })
  expect(rear()).toHaveLength(2)
  expect(rear().map(node => node.children.join('')).join('')).not.toContain('d1')
  const gesture = () => tree!.root.findByProps({ className: 'day-stack-header' })
  await act(async () => {
    gesture().props.onTouchStart({ touches: [{ pageX: 100, pageY: 30 }] })
    gesture().props.onTouchEnd({ changedTouches: [{ pageX: 20, pageY: 32 }] })
  })
  expect(body()).toBe('d1,p1')
  await act(async () => {
    gesture().props.onTouchStart({ touches: [{ pageX: 20, pageY: 30 }] })
    gesture().props.onTouchEnd({ changedTouches: [{ pageX: 100, pageY: 32 }] })
  })
  expect(body()).toBe('d0,p0')
  await act(async () => rear()[0].props.onClick())
  expect(body()).toBe('d2')
  await act(async () => tree!.update(render('B')))
  expect(body()).toBe('d0,p0')
  expect(tree!.root.findByProps({ className: 'day-stack-body' }).props.onTouchEnd).toBeUndefined()
  await act(async () => {
    gesture().props.onTouchStart({ touches: [{ pageX: 100, pageY: 30 }] })
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
  const cells = Array.from({ length: 32 }, (_, i) => ({ key: new Date(Date.UTC(2026, 9, i + 1, 12)).toISOString().slice(0, 10), label: String(i), value: i === 30 ? null : i === 29 ? 0 : i, display: i === 30 ? '未知' : `${i === 29 ? 0 : i} kWh` }))
  await act(async () => { tree = TestRenderer.create(<ChargeActivityGrid cells={cells} unit="kWh" />) })
  const buttons = () => tree!.root.findAllByType('button').filter(node => node.props.className?.includes('charge-activity-cell'))
  expect(buttons()).toHaveLength(28)
  expect(buttons()[0].props.ariaLabel).toContain('2026-10-05')
  const zero = buttons().find(node => node.props.ariaLabel.endsWith('，0 kWh'))!
  await act(async () => zero.props.onClick())
  expect(tree!.root.findByProps({ className: 'charge-activity-selection' }).children.join('')).toBe('2026-10-30 · 0 kWh')
  await act(async () => buttons().find(node => node.props.ariaLabel.endsWith('，未知'))!.props.onClick())
  expect(tree!.root.findByProps({ className: 'charge-activity-selection' }).children.join('')).toContain('未知')
  await act(async () => tree!.root.findAllByType('button').find(node => node.children.join('') === '较早日期')!.props.onClick())
  expect(buttons()).toHaveLength(4)
  expect(buttons()[0].props.ariaLabel).toContain('2026-10-01')
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

it('renders the full selected week including empty dates without manufacturing history; raises and collapses a rear date', async () => {
  const week = calendarHistoryGroups(groups, '2026-11-04', '2026-11-10')
  expect(week).toHaveLength(7)
  expect(week.flatMap(group => group.items)).toHaveLength(6)
  await act(async () => { tree = TestRenderer.create(<VerticalDayStack groups={week} scopeKey="week">{group => <span>{group.items.map(row => row.key).join(',')}</span>}</VerticalDayStack>) })
  expect(tree!.root.findAllByProps({ className: 'day-stack-date' })).toHaveLength(2)
  const yesterday = () => tree!.root.findAllByProps({ className: 'day-stack-date' }).find(node => node.children.join('').includes('2026-11-09'))!
  await act(async () => yesterday().props.onClick())
  expect(tree!.root.findByType('span').children.join('')).toBe('d1,p1')
  await act(async () => tree!.root.findByProps({ className: 'day-stack-close' }).props.onClick())
  expect(tree!.root.findByType('span').children.join('')).toBe('d0,p0')
  await act(async () => tree!.root.findAllByProps({ className: 'day-stack-date' })[0].props.onClick())
  await act(async () => tree!.root.findAllByProps({ className: 'day-stack-date' })[0].props.onClick())
  expect(JSON.stringify(tree!.toJSON())).toContain('当天尚无已返回记录')
  expect(calendarHistoryGroups(groups, '2026-02-30', '2026-11-10')).toEqual(groups)
})
it('persists the vertical direction, rejects orthogonal gestures, and retains zero body side effects', async () => {
  expect(daySwipeTarget(0, 7, -80, 3)).toBe(0)
  expect(daySwipeTarget(0, 7, 3, -80)).toBe(1)
  expect(daySwipeTarget(0, 7, 3, -80, 'vertical')).toBe(1)
  expect(daySwipeTarget(0, 7, -80, 3, 'horizontal')).toBe(1)
  expect(daySwipeTarget(0, 7, 80, 0, 'horizontal')).toBe(0)
  expect(daySwipeTarget(6, 7, -80, 0, 'horizontal')).toBe(6)
  await act(async () => { tree = TestRenderer.create(render()) })
  await act(async () => { writeAppearance({ dayAxis: 'vertical' }) })
  const gesture = tree!.root.findByProps({ className: 'day-stack-header' })
  await act(async () => { gesture.props.onTouchStart({ touches: [{ pageX: 10, pageY: 100 }] }); gesture.props.onTouchEnd({ changedTouches: [{ pageX: 10, pageY: 10 }] }) })
  expect(tree!.root.findByType('span').children.join('')).toBe('d1,p1')
  expect(readAppearance().dayAxis).toBe('vertical')
})

it('matches App charge tile fixed-scale palette and readable contrast in both themes', () => {
  expect(chargeCellPaint(0)).toEqual({ background:'#f3f3f5',foreground:'#000000' })
  expect(chargeCellPaint(null,'cream')).toEqual({ background:'#f5f3ed',foreground:'#000000' })
  expect(chargeCellPaint(60)).toEqual({ background:'#424244',foreground:'#ffffff' })
  expect(chargeCellPaint(120)).toEqual(chargeCellPaint(60))
  expect(chargeCellPaint(40).background).toBe('#78787a')
  expect(chargeCellPaint(0,'neutral',true)).toEqual({background:'#242a31',foreground:'#ffffff'})
  expect(chargeCellPaint(60,'neutral',true)).toEqual({background:'#bec0c3',foreground:'#000000'})
})
it('keeps date and two controls in the same App-aligned header row', async () => {
  await act(async () => { tree=TestRenderer.create(render()) })
  const row=tree!.root.findByProps({className:'day-stack-heading-row'})
  expect(row.findByProps({className:'section-title'}).children.join('')).toContain('2026-11-10')
  expect(row.findAllByType('button').map(n=>n.props.ariaLabel)).toEqual(['较新日期','较旧日期'])
})
