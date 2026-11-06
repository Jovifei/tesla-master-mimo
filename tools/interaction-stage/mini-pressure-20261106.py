#!/usr/bin/env python3
"""Reviewed one-shot patch: current scoped settings -> existing comparator -> real Mini consumers."""
from pathlib import Path
import subprocess
root=Path(__file__).resolve().parents[2]
assert subprocess.check_output(['git','branch','--show-current'],cwd=root,text=True).strip()=='codex/miniprogram-interaction-complete-20261106'
def replace(path,pairs):
    p=root/path; text=p.read_text()
    for old,new in pairs:
        assert text.count(old)==1,(path,old[:100],text.count(old))
        text=text.replace(old,new)
    p.write_text(text)
def create(path,text):
    p=root/path; assert not p.exists(),path
    p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
replace('miniprogram/src/components/interaction/InteractionVisuals.tsx',[
("import { historyDetailDateTime }", "import { evaluateTpmsAlerts, validTpmsProfile, type TpmsProfile } from '../../domain/tpms_alerts'\nimport type { TpmsPoint } from '../../domain/tpms'\nimport { historyDetailDateTime }"),
('export function TpmsVehicleVisual({ snapshot, selected, onSelect, now = Date.now() }:', 'export function TpmsVehicleVisual({ snapshot, selected, onSelect, now = Date.now(), profile = null, history = [] }:'),
('{ snapshot?: TpmsSnapshot | null; selected: TpmsWheel | null; onSelect: (wheel: TpmsWheel | null) => void; now?: number }) {', '{ snapshot?: TpmsSnapshot | null; selected: TpmsWheel | null; onSelect: (wheel: TpmsWheel | null) => void; now?: number; profile?: TpmsProfile | null; history?: readonly TpmsPoint[] }) {'),
('  const current = restoreTpmsSnapshot(snapshot, now)\n  return', '  const current = restoreTpmsSnapshot(snapshot, now)\n  const comparison = evaluateTpmsAlerts(profile, current, history, now)\n  const latest = TPMS_WHEELS.map(wheel => current.wheels[wheel].observedAt).filter((value): value is string => value != null).sort().pop() ?? null\n  const at = selected ? current.wheels[selected].observedAt : latest\n  return'),
("      const status = reading.state === 'observed' ? '已观测' : reading.state === 'stale' ? '旧观测' : '暂无合格观测'", "      const rule = comparison.wheels.find(row => row.wheel === wheel)!\n      const alert = rule.state === 'low' || rule.state === 'high' ? rule.state : rule.changed ? 'change' : 'none'\n      const status = rule.state === 'low' ? '低于设置下限' : rule.state === 'high' ? '高于设置上限' : rule.changed ? '变化规则提醒' : rule.state === 'normal' ? '设置范围内' : reading.state === 'observed' ? '已观测' : reading.state === 'stale' ? '旧观测' : '暂无合格观测'"),
('tpms-reading-${reading.state} ${selected === wheel', 'tpms-reading-${reading.state} tpms-alert-${alert} ${selected === wheel'),
('data-wheel={wheel} data-state={reading.state} data-selected=', 'data-wheel={wheel} data-state={reading.state} data-alert={alert} data-selected='),
("    {selected ? <Text className=\"interaction-caption\">{VISUAL_WHEEL_NAMES[selected]} · {current.wheels[selected].observedAt ? historyDetailDateTime(current.wheels[selected].observedAt) : '独立观测时间未知'}</Text> : null}", "    {validTpmsProfile(profile) && (profile.enabled || profile.changeEnabled) ? <Text className=\"interaction-caption\">按你保存的阈值比较，不代表车辆原厂告警。</Text> : null}\n    <Text className=\"interaction-caption\" data-observation-time>{selected ? `${VISUAL_WHEEL_NAMES[selected]} · ` : '最近一轮独立观测：'}{at ? historyDetailDateTime(at) : '独立观测时间未知'}</Text>"),
])
replace('miniprogram/src/components/interaction/interaction.scss',[
('.tpms-reading-stale .tpms-position-status', '.tpms-alert-low .tpms-position-status,.tpms-alert-high .tpms-position-status,.tpms-alert-change .tpms-position-status { color:var(--iv-warning); font-weight:600; }\n.interaction-visual .tpms-alert-low,.interaction-visual .tpms-alert-high,.interaction-visual .tpms-alert-change { border-color:var(--iv-warning); }\n.tpms-reading-stale .tpms-position-status'),
])
replace('miniprogram/src/components/TpmsPanel.tsx',[
("import type { TpmsWheel } from '../domain/tpms'", "import type { TpmsWheel } from '../domain/tpms'\nimport type { TpmsProfile } from '../domain/tpms_alerts'"),
('cacheReason?: string | null; now?: number }', 'cacheReason?: string | null; now?: number; profile?: TpmsProfile | null }'),
('history, cacheReason, now = Date.now() }: Props)', 'history, cacheReason, now = Date.now(), profile = null }: Props)'),
('<TpmsVehicleVisual snapshot={snapshot} now={now}', '<TpmsVehicleVisual snapshot={snapshot} now={now} profile={profile} history={history?.points}'),
])
replace('miniprogram/src/pages/tools/index.tsx',[
("import { readTpmsCache } from '../../services/tpms_cache'", "import { readTpmsCache } from '../../services/tpms_cache'\nimport { readTpmsProfile } from '../../services/tpms_profile'"),
('<TpmsPanel key={`${owner.current ?? \'\'}:${car?.stableId ?? \'\'}`} snapshot={status?.tpms}', '<TpmsPanel key={`${owner.current ?? \'\'}:${car?.stableId ?? \'\'}`} profile={costScope && owner.current === scopeKey() ? readTpmsProfile(Taro, costScope) : null} snapshot={status?.tpms}'),
])
replace('miniprogram/src/components/interaction/InteractionReceiptRegression.test.tsx',[
('sessions={[{ ...sessions[0], energyKwh: value }, sessions[0]]}', 'sessions={[{ ...sessions[0], energyKwh: value }, { ...sessions[1], energyKwh: 0 }]}'),
])
replace('tools/interaction-stage/browser/fixture.tsx',[
("const titles = {", "const warningSnapshot = restoreTpmsSnapshot(qualified, now)\nwarningSnapshot.wheels.fl = { ...qualified.wheels.fl, pressureBar: 2.1 }\nwarningSnapshot.wheels.fr = { ...qualified.wheels.fl, pressureBar: 3.6 }\nwarningSnapshot.wheels.rl = { ...qualified.wheels.fl, pressureBar: 2.9 }\nconst savedProfile = { enabled: true, targetBar: 2.9, lowBar: 2.6, highBar: 3.4, changeEnabled: false, deltaBar: .3, windowHours: 24 }\nconst titles = { 'tpms-warning': '自定义胎压规则',"),
("      {scene === 'route'", "      {scene === 'tpms-warning' && <TpmsVehicleVisual key={cleared ? 'scope-reset' : 'configured'} snapshot={cleared ? null : warningSnapshot} selected={wheel} onSelect={select} now={now} profile={cleared ? null : savedProfile} />}\n      {scene === 'route'"),
])
replace('tools/interaction-stage/browser/capture.mjs',[
("'wechat','tpms','route'", "'wechat','tpms','tpms-warning','route'"),
("    if(scene==='parking')assert", "    if(scene==='tpms-warning'){\n      await verifyWheels()\n      assert(await evaluate(\"document.querySelector('[data-wheel=\\\"fl\\\"]').dataset.alert==='low' && document.querySelector('[data-wheel=\\\"fr\\\"]').dataset.alert==='high' && document.querySelector('[data-wheel=\\\"rl\\\"]').innerText.includes('设置范围内') && document.querySelector('[data-wheel=\\\"rr\\\"]').dataset.state==='unknown'\"))\n    }\n    if(scene==='parking')assert"),
("if(scene==='tpms'&&width===320)", "if((scene==='tpms'||scene==='tpms-warning')&&width===320)"),
('await save(`mini-${theme}-${width}-tpms-large-text`)', 'await save(`mini-${theme}-${width}-${scene}-large-text`)'),
('assert.equal(captures.length,46)', 'assert.equal(captures.length,52)'),
])
create('miniprogram/src/components/interaction/ConfiguredTpmsVisual.test.tsx', '''import React from 'react'
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
function text() { return view!.root.findAllByType('text').map(n => n.children.join('')).join('\\n') }
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
''')
print('Applied scoped profile -> existing rules -> actual Mini wheel consumer; added native bundle and 52 browser render gates')
