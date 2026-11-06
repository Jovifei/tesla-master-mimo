import React from 'react'
import renderer, { act } from 'react-test-renderer'
import { afterEach, expect, it, vi } from 'vitest'
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Image: 'image', Canvas: 'canvas' }))
vi.mock('@tarojs/taro', () => ({ default: {} }))
import TpmsPanel from '../TpmsPanel'
import { restoreTpmsSnapshot, type TpmsPoint } from '../../domain/tpms'
import type { TpmsProfile } from '../../domain/tpms_alerts'
const now = Date.parse('2026-11-06T00:00:00Z')
const profile: TpmsProfile = { enabled: true, targetBar: 2.9, lowBar: 2.6, highBar: 3.4, changeEnabled: false, deltaBar: .3, windowHours: 24 }
function snapshot() {
  const data = restoreTpmsSnapshot(null, now)
  data.wheels.fl = { pressureBar: 0, observedAt: new Date(now).toISOString(), source: 'telemetry_mqtt', timeBasis: 'collector_received_at', state: 'observed', reason: null }
  data.wheels.fr = { ...data.wheels.fl, pressureBar: 3.5 }
  data.wheels.rl = { ...data.wheels.fl, pressureBar: 2.9 }
  return data
}
let view: renderer.ReactTestRenderer | null = null
function render(node: React.ReactElement) { act(() => { view = renderer.create(node) }) }
function wheel(name: string) { return view!.root.findByProps({ 'data-wheel': name }) }
function text() { return view!.root.findAllByType('text').map(n => n.children.join('')).join('\n') }
afterEach(() => { if(view) act(() => view!.unmount()); view = null })
it('wires saved low, high and in-range rules through the actual parent without treating zero as missing', () => {
  render(<TpmsPanel snapshot={snapshot()} profile={profile} now={now} />)
  expect(wheel('fl').props['data-alert']).toBe('low')
  expect(wheel('fr').props['data-alert']).toBe('high')
  expect(wheel('rl').props['data-alert']).toBe('none')
  expect(wheel('rr').props['data-state']).toBe('unknown')
  expect(text()).toContain('0.00 bar'); expect(text()).toContain('低于设置下限'); expect(text()).toContain('高于设置上限'); expect(text()).toContain('设置范围内')
  expect(text()).toContain('不代表车辆原厂告警')
})
it('removes rule classifications immediately when settings become absent or disabled', () => {
  const data=snapshot(); render(<TpmsPanel snapshot={data} profile={profile} now={now} />)
  act(() => view!.update(<TpmsPanel snapshot={data} profile={null} now={now} />))
  expect(wheel('fl').props['data-alert']).toBe('none'); expect(text()).not.toContain('设置范围内')
  act(() => view!.update(<TpmsPanel snapshot={data} profile={{...profile,enabled:false}} now={now} />))
  expect(wheel('fr').props['data-alert']).toBe('none'); expect(text()).toContain('已观测')
})
it('does not classify stale, future, source-conflicting or invalid settings as normal or warning', () => {
  const data=snapshot()
  data.wheels.fl.observedAt=new Date(now-3600000).toISOString()
  data.wheels.fr.observedAt=new Date(now+1).toISOString()
  data.wheels.rl.source=null
  render(<TpmsPanel snapshot={data} profile={profile} now={now} />)
  for(const name of ['fl','fr','rl','rr']) expect(wheel(name).props['data-alert']).toBe('none')
  expect(text()).not.toContain('设置范围内'); expect(text()).toContain('旧观测')
  act(() => view!.update(<TpmsPanel snapshot={snapshot()} profile={{...profile,highBar:Number.NaN}} now={now} />))
  expect(wheel('fl').props['data-alert']).toBe('none')
})
it('uses the existing independent change rule and history instead of inventing a low threshold', () => {
  const data=snapshot(); data.wheels.fl.pressureBar=2.9
  const previous: TpmsPoint={wheel:'fl',pressureBar:2.5,observedAt:new Date(now-3600000).toISOString(),source:'telemetry_mqtt',timeBasis:'collector_received_at'}
  render(<TpmsPanel snapshot={data} profile={{...profile,enabled:false,changeEnabled:true}} now={now} history={{points:[previous],limited:false,oldestAt:previous.observedAt,latestAt:previous.observedAt}} />)
  expect(wheel('fl').props['data-alert']).toBe('change'); expect(text()).toContain('变化规则提醒')
  expect(wheel('fr').props['data-alert']).toBe('none')
})
it('clears selected rules and values on a new scoped parent and retains independent time labels', () => {
  render(<TpmsPanel key="first-scope" snapshot={snapshot()} profile={profile} now={now} />)
  expect(text()).toContain('最近一轮独立观测')
  act(() => wheel('fl').props.onClick())
  expect(wheel('fl').props['data-selected']).toBe(true)
  act(() => view!.update(<TpmsPanel key="new-scope" snapshot={null} profile={null} now={now} />))
  expect(wheel('fl').props['data-selected']).toBe(false); expect(wheel('fl').props['data-alert']).toBe('none')
  expect(text()).not.toContain('0.00 bar'); expect(text()).toContain('独立观测时间未知')
})
