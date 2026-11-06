import React, { useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { ConnectionVisual, EmptyHistoryVisual, ParkedChargeTimeline, RouteInteractionVisual, TpmsVehicleVisual, VehicleOpeningsVisual } from '../../../miniprogram/src/components/interaction/InteractionVisuals'
import LoadingIndicator from '../../../miniprogram/src/components/LoadingIndicator'
import { restoreTpmsSnapshot, type TpmsWheel } from '../../../miniprogram/src/domain/tpms'
import { foreground } from './platform'
import './fixture.css'
import './loading-source.css'
const now = Date.parse('2026-11-04T00:00:00Z')
const qualified = restoreTpmsSnapshot(null, now)
qualified.wheels.fl = { pressureBar: 0, observedAt: new Date(now).toISOString(), source: 'telemetry_mqtt', timeBasis: 'collector_received_at', state: 'observed', reason: null }
qualified.wheels.fr = { ...qualified.wheels.fl, pressureBar: 2.6 }
qualified.wheels.rl = { ...qualified.wheels.fl, pressureBar: 2.4, observedAt: new Date(now-3600000).toISOString() }
const warningSnapshot = restoreTpmsSnapshot(qualified, now)
warningSnapshot.wheels.fl = { ...qualified.wheels.fl, pressureBar: 2.1 }
warningSnapshot.wheels.fr = { ...qualified.wheels.fl, pressureBar: 3.6 }
warningSnapshot.wheels.rl = { ...qualified.wheels.fl, pressureBar: 2.9 }
const savedProfile = { enabled: true, targetBar: 2.9, lowBar: 2.6, highBar: 3.4, changeEnabled: false, deltaBar: .3, windowHours: 24 }
const titles = { 'tpms-warning': '自定义胎压规则', vehicle: '车辆连接', 'vehicle-pending': '车辆确认中', tesla: 'Tesla 官方授权', 'auth-pending': '正在核对授权', wechat: '微信账号绑定', tpms: '四轮胎压', route: '行程起终点', openings: '开口状态', parking: '驻车充电记录', empty: '历史空态', loading: '正在读取' }
function Fixture() {
  const [scene, setScene] = useState(new URLSearchParams(location.search).get('scene') || 'tpms')
  const [wheel, select] = useState<TpmsWheel | null>(null)
  const [cleared, clear] = useState(false)
  useEffect(() => { (window as any).__visualFixture = { ready: true, foreground, clear: () => { clear(true); select(null) } } }, [])
  return <main className="fixture">
    <div className="fixture-controls"><strong>MateLink · 交互回归</strong><select aria-label="选择演示场景" value={scene} onChange={e => { setScene(e.target.value); clear(false); select(null) }}>{Object.entries(titles).map(([key,title]) => <option key={key} value={key}>{title}</option>)}</select></div>
    <p className="fixture-evidence">合成数据 · 实际 React 组件的浏览器渲染<br />不是微信原生或真车验收</p>
    <section className="fixture-surface" data-scene={scene}>
      <h1>{titles[scene as keyof typeof titles]}</h1>
      {scene === 'vehicle' && <ConnectionVisual kind="vehicle" phase="action_required" />}
      {scene === 'vehicle-pending' && <ConnectionVisual kind="vehicle" phase="connecting" />}
      {scene === 'tesla' && <ConnectionVisual kind="tesla" phase="idle" />}
      {scene === 'auth-pending' && <ConnectionVisual kind="tesla" phase="connecting" />}
      {scene === 'wechat' && <ConnectionVisual kind="wechat" phase="connected" />}
      {scene === 'tpms' && <TpmsVehicleVisual key={cleared ? 'scope-reset' : 'qualified'} snapshot={cleared ? null : qualified} selected={wheel} onSelect={select} now={now} />}
      {scene === 'tpms-warning' && <TpmsVehicleVisual key={cleared ? 'scope-reset' : 'configured'} snapshot={cleared ? null : warningSnapshot} selected={wheel} onSelect={select} now={now} profile={cleared ? null : savedProfile} />}
      {scene === 'route' && <RouteInteractionVisual start="示例起点 A" end="示例终点 B" />}
      {scene === 'openings' && <VehicleOpeningsVisual doors windows={null} frunk={false} trunk />}
      {scene === 'parking' && <ParkedChargeTimeline sessions={[
        {id:'synthetic-1',startDate:'2026-11-04T00:10:00Z',endDate:'2026-11-04T00:30:00Z',energyKwh:0},
        {id:'synthetic-2',startDate:'2026-11-04T01:10:00Z',endDate:null,energyKwh:null}
      ]} complete={false} />}
      {scene === 'empty' && <><EmptyHistoryVisual kind="drive" /><p>当前范围暂无可展示的行程</p><EmptyHistoryVisual kind="charge" /><p>当前范围暂无可展示的充电记录</p></>}
      {scene === 'loading' && <LoadingIndicator label="读取已有记录；未改变账号或本地数据" />}
    </section>
    <p className="fixture-evidence">仅展示新增局部视觉。原导航、数据卡、地图和官方授权操作仍在原页面。</p>
  </main>
}
createRoot(document.getElementById('root')!).render(<Fixture />)
