import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { getCarStatus, getChargeDetail, type ChargeDetail } from '../api/matelink'
import type { WebCarStatus, WebChargePoint } from '../api/matelink-types'
import { periodEfficiency } from '../api/drive-energy.ts'
import { stateSegments } from '../api/panel-data'
import { socSegments } from '../api/tm-data'
import { AnomalyBand, ChargeRuler, Panel, PremiseStrip, V3 } from '../components/v3'
import { DATA_COLORS, StackChart, StatTile, StatesBar, TILE_COLORS, type ChartRow } from '../components/panels'
import { CarPicker, ErrorNote, GaugeArc, LoadingNote, MapPanel, PageTitle, SocStitchChart } from '../components/tm'
import { useCar } from './useCar'
import { useHistory } from './useHistory'

/**
 * 总览（所有者 2026-11-08 18:39 定版）：电量是主角（0–100% 大数字 +
 * 进度条 + 预计续航）；电量曲线按 TeslaMate Charge Level 拼接呈现，
 * 充电时充电曲线同图；平均能耗分月/周两档（本机口径，见
 * api/drive-energy.ts）；温度只留车内/车外；状态时间条直接进总览，
 * 默认最近一周；充电电压/功率只在充电区出现（功率突出，电压、
 * 电池加热为状态快照当前值，有才显示）；固件版本置于页尾。
 */

const DAY = 86_400_000

export default function WebDashboard() {
  const { cars, car, carId, setCarId, error: carError } = useCar()
  const [statusState, setStatusState] = useState<{owner: number; value: WebCarStatus} | null>(null)
  const status = statusState?.owner === carId ? statusState.value : null
  const statusRequest = useRef<AbortController | null>(null)
  const [statusError, setStatusError] = useState<string | null>(null)
  const [windowDays, setWindowDays] = useState(7)
  const history = useHistory(carId)
  const [chargePointState, setChargePointState] = useState<{owner: number; values: ReadonlyMap<string, readonly WebChargePoint[]>} | null>(null)
  const chargePoints = chargePointState?.owner === carId ? chargePointState.values : new Map<string, readonly WebChargePoint[]>()
  const [liveState, setLiveState] = useState<{owner: number; value: ChargeDetail} | null>(null)
  const liveDetail = liveState?.owner === carId ? liveState.value : null
  const location = useLocation()

  const charging = status?.chargingState === 'Charging'

  const loadStatus = useCallback(async () => {
    if (carId == null || statusRequest.current) return
    const controller = new AbortController(); statusRequest.current = controller
    try {
      const next = await getCarStatus(carId, controller.signal)
      if (!controller.signal.aborted) { setStatusState({owner:carId,value:next}); setStatusError(null) }
    } catch { if (!controller.signal.aborted) setStatusError('读取车辆状态失败，请稍后重试。') }
    finally { if(statusRequest.current===controller) statusRequest.current=null }
  }, [carId])
  useEffect(() => {
    statusRequest.current?.abort();statusRequest.current=null;setStatusError(null)
    void loadStatus()
    return () => {statusRequest.current?.abort();statusRequest.current=null}
  }, [loadStatus])

  // 动态（所有者选项 A）：充电中每 20 秒刷新状态。
  useEffect(() => {
    if (!charging) return
    const timer = window.setInterval(() => void loadStatus(), 20_000)
    return () => window.clearInterval(timer)
  }, [charging, loadStatus])

  // /states、/timeline 已并入总览：带 #states 锚点进入时滚到状态条。
  useEffect(() => {
    if (location.hash !== '#states') return
    const timer = window.setTimeout(() => document.getElementById('states')?.scrollIntoView({ block: 'start' }), 350)
    return () => window.clearTimeout(timer)
  }, [location.hash, history.loading, status])

  const windowStart = useMemo(() => Date.now() - windowDays * DAY, [windowDays])
  const windowEnd = Date.now()

  // Fetch real in-charge samples for the stitch (bounded, most recent first).
  useEffect(() => {
    if (carId == null || !history.charges.length) {setChargePointState(null);return}
    const inWindow = history.charges
      .filter(c => {
        const end = Date.parse(c.endDate ?? c.startDate ?? '')
        return Number.isFinite(end) && end >= windowStart
      })
      .slice(0, 6)
    const controller = new AbortController()
    setChargePointState(null)
    void (async () => {
      const map = new Map<string, readonly WebChargePoint[]>()
      for (const charge of inWindow) {
        if(controller.signal.aborted) return
        try {
          const detail = await getChargeDetail(carId, charge.id, controller.signal)
          if (detail.points.length >= 2) map.set(charge.id, detail.points)
        } catch {
          /* boundary stitch still applies */
        }
      }
      if (!controller.signal.aborted) setChargePointState({owner:carId,values:map})
    })()
    return () => {
      controller.abort()
    }
  }, [carId, history.charges, windowStart])

  const liveCharge = useMemo(() => history.charges.find(c => c.isCharging) ?? null, [history.charges])
  useEffect(() => {
    setLiveState(null)
    if (carId == null || !liveCharge) return
    const controller = new AbortController()
    let busy=false
    const fetchDetail = async () => {
      if (busy || controller.signal.aborted) return
      busy=true
      try {
        const detail = await getChargeDetail(carId, liveCharge.id, controller.signal)
        if (!controller.signal.aborted) setLiveState({owner:carId,value:detail})
      } catch { /* do not promote failed requests to new evidence */ }
      finally { busy=false }
    }
    void fetchDetail()
    const timer = charging ? window.setInterval(() => void fetchDetail(), 20_000) : undefined
    return () => {controller.abort();if(timer)window.clearInterval(timer)}
  }, [carId, liveCharge, charging])

  const segments = useMemo(
    () => socSegments(history.charges, history.drives, chargePoints, windowStart, windowEnd),
    [history.charges, history.drives, chargePoints, windowStart, windowEnd]
  )
  const states = useMemo(
    () => stateSegments(history.drives, history.charges, windowStart, windowEnd),
    [history.drives, history.charges, windowStart, windowEnd]
  )
  // 平均能耗三档中的月/周两档：本机口径逐程解析后聚合（与 App 一致）。
  const monthEfficiency = useMemo(() => {
    const now = new Date()
    return periodEfficiency(history.drives, car?.model ?? null, new Date(now.getFullYear(), now.getMonth(), 1).getTime(), Date.now() + 1)
  }, [history.drives, car?.model])
  const weekEfficiency = useMemo(() => {
    const now = new Date()
    const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
    const monday = todayStart - ((now.getDay() + 6) % 7) * DAY
    return periodEfficiency(history.drives, car?.model ?? null, monday, Date.now() + 1)
  }, [history.drives, car?.model])
  const totalDistance = useMemo(
    () => history.drives.reduce((sum, d) => sum + (d.distanceKm ?? 0), 0),
    [history.drives]
  )
  const liveRows: ChartRow[] = useMemo(
    () =>
      (liveDetail?.points ?? []).map(p => ({
        t: Date.parse(p.date),
        power: p.powerKw,
        soc: p.batteryLevel,
      })),
    [liveDetail]
  )

  const anomaly =
    status && (status.locked === false || status.windowsOpen)
      ? {
          title: status.locked === false ? '车辆未上锁' : '车窗未关',
          detail: '这是当前状态快照中的安全提示，请在特斯拉应用中确认车辆状态。',
        }
      : null

  const efficiencyCaption = (agg: { resolvedCount: number; totalCount: number }) =>
    agg.totalCount > 0 ? `本机口径 · 基于 ${agg.resolvedCount}/${agg.totalCount} 次可计算行程` : '本期内暂无行程'

  return (
    <div>
      <div className="ml-premise">
        <PremiseStrip source={status?.source ?? null} observedAt={status?.observedAt ?? null} onRefresh={() => void loadStatus()} />
      </div>
      <PageTitle title={car ? `${car.name} · 总览` : '总览'}>
        <CarPicker cars={cars} carId={carId} onChange={setCarId} />
      </PageTitle>
      {carError ? <ErrorNote text={carError} /> : null}
      {statusError ? <ErrorNote text={statusError} /> : null}
      {anomaly ? (
        <div style={{ marginBottom: 16 }}>
          <AnomalyBand title={anomaly.title} detail={anomaly.detail} />
        </div>
      ) : null}
      {!status && !statusError ? <LoadingNote text="正在读取车辆状态…" /> : null}
      {status ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 300px), 1fr))', gap: 16 }}>
            <Panel
              title="电量"
              action={
                <span style={{ fontSize: 12.5, color: V3.label2 }}>
                  {charging ? '正在充电' : status.state ?? ''}
                  <Link to="/charge-level" style={{ color: V3.tint, textDecoration: 'none', fontWeight: 600, marginLeft: 10 }}>电量历史 →</Link>
                </span>
              }
            >
              <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
                <span className="tabular" style={{ fontSize: 56, fontWeight: 800, lineHeight: 1, color: DATA_COLORS.tealDeep }}>
                  {status.batteryLevel != null ? Math.round(status.batteryLevel) : '--'}
                </span>
                <span style={{ fontSize: 20, fontWeight: 600, color: V3.label2 }}>%</span>
                {charging ? (
                  <span style={{ marginLeft: 'auto', fontSize: 13, color: V3.label2 }}>
                    已充入 <strong className="tabular" style={{ color: V3.label }}>{status.chargeEnergyAddedKwh ?? '--'} 度</strong>
                  </span>
                ) : null}
              </div>
              {status.batteryLevel != null ? <ChargeRuler soc={status.batteryLevel} snapshot={status.source === 'telemetry_cache'} /> : null}
              <div style={{ display: 'flex', gap: 28, marginTop: 12, flexWrap: 'wrap' }}>
                <div>
                  <div style={{ fontSize: 12, color: V3.label3, fontWeight: 600 }}>预计续航</div>
                  <div className="tabular" style={{ fontSize: 28, fontWeight: 700, color: V3.label }}>
                    {status.estimatedRangeKm != null ? Math.round(status.estimatedRangeKm) : '--'}
                    <span style={{ fontSize: 13, fontWeight: 500, color: V3.label3 }}> km</span>
                  </div>
                </div>
                <div>
                  <div style={{ fontSize: 12, color: V3.label3, fontWeight: 600 }}>额定续航</div>
                  <div className="tabular" style={{ fontSize: 28, fontWeight: 700, color: V3.label }}>
                    {status.ratedRangeKm != null ? Math.round(status.ratedRangeKm) : '--'}
                    <span style={{ fontSize: 13, fontWeight: 500, color: V3.label3 }}> km</span>
                  </div>
                </div>
              </div>
            </Panel>
            <div style={{ minWidth: 0 }}>
              <Panel title="电量曲线" action={<span style={{ fontSize: 12, color: V3.label3 }}>近 {windowDays} 天 · 充电采样与行程边界拼接，充电过程同图呈现</span>}>
                {segments.length ? <SocStitchChart segments={segments} height={216} /> : <div style={{ fontSize: 13, color: V3.label3 }}>该时段暂无可拼接的电量记录。</div>}
              </Panel>
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 16 }}>
            <StatTile
              label="本月平均能耗"
              value={monthEfficiency.kwhPer100km != null ? monthEfficiency.kwhPer100km.toFixed(1) : '--'}
              unit="kWh/100km"
              accent={TILE_COLORS.blue}
              caption={efficiencyCaption(monthEfficiency)}
            />
            <StatTile
              label="本周平均能耗"
              value={weekEfficiency.kwhPer100km != null ? weekEfficiency.kwhPer100km.toFixed(1) : '--'}
              unit="kWh/100km"
              accent={TILE_COLORS.blue}
              caption={efficiencyCaption(weekEfficiency)}
            />
            <StatTile label="已记录总里程" value={totalDistance.toLocaleString('zh-CN', { maximumFractionDigits: 0 })} unit="km" caption={history.complete ? '全部已记录行程合计' : '历史较多，仅统计已加载部分'} />
            <StatTile label="里程表" value={status.odometerKm != null ? Math.round(status.odometerKm).toLocaleString('zh-CN') : '--'} unit="km" />
          </div>

          {charging ? (
            <Panel title="充电中（实时）" action={<span style={{ fontSize: 12, color: V3.label3 }}>每 20 秒刷新 · 电压与电池加热为当前快照值</span>}>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: 12, marginBottom: liveRows.length >= 2 ? 14 : 0 }}>
                <StatTile label="当前功率" value={status.chargerPowerKw != null ? status.chargerPowerKw.toFixed(1) : '--'} unit="kW" accent={TILE_COLORS.orange} />
                {status.liveVoltageV != null ? <StatTile label="当前电压" value={Math.round(status.liveVoltageV).toString()} unit="V" accent={TILE_COLORS.purple} /> : null}
                {status.liveCurrentA != null ? <StatTile label="当前电流" value={Math.round(status.liveCurrentA).toString()} unit="A" /> : null}
                {status.batteryHeaterOn != null ? <StatTile label="电池加热" value={status.batteryHeaterOn ? '开' : '关'} accent={status.batteryHeaterOn ? TILE_COLORS.red : undefined} /> : null}
                <StatTile label="已充入" value={status.chargeEnergyAddedKwh != null ? status.chargeEnergyAddedKwh.toFixed(1) : '--'} unit="kWh" accent={TILE_COLORS.green} />
                {liveDetail?.summary.timeToFullHours != null ? (
                  <StatTile label="预计充满还需" value={liveDetail.summary.timeToFullHours.toFixed(1)} unit="小时" accent={TILE_COLORS.blue} />
                ) : null}
              </div>
              {liveRows.length >= 2 ? (
                <StackChart
                  data={liveRows}
                  series={[
                    { key: 'power', title: '功率', unit: 'kW', color: TILE_COLORS.orange, height: 150, digits: 1, domain: [0, 'auto'] },
                    { key: 'soc', title: '电量', unit: '%', color: DATA_COLORS.graphite, height: 80, domain: [0, 100] },
                  ]}
                />
              ) : null}
            </Panel>
          ) : null}

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 16 }}>
            <GaugeArc label="车内温度" value={status.insideTempC} min={-20} max={45} unit="°C" digits={1} />
            <GaugeArc label="车外温度" value={status.outsideTempC} min={-20} max={45} unit="°C" digits={1} />
            <div style={{ minWidth: 0 }}>
              <Panel title="安全与位置">
                <div style={{ fontSize: 13.5, color: V3.label2, lineHeight: 1.9 }}>
                  <div>车锁：{status.locked == null ? '未知' : status.locked ? '已上锁' : '未上锁'} · 哨兵模式：{status.sentryMode == null ? '未知' : status.sentryMode ? '开启' : '关闭'} · 车窗：{status.windowsOpen == null ? '未知' : status.windowsOpen ? '未关' : '已关'}</div>
                  <div>位置：{status.geofence ?? '未知'}{status.latitude != null && status.longitude != null ? `（${status.latitude.toFixed(5)}, ${status.longitude.toFixed(5)}）` : ''}</div>
                </div>
              </Panel>
            </div>
          </div>

          {status.latitude != null && status.longitude != null ? (
            <MapPanel title="车辆位置" points={[{ lat: status.latitude, lng: status.longitude, label: car?.name ?? '车辆' }]} height={240} />
          ) : null}

          <div id="states">
            <Panel
              title="状态"
              action={
                <span style={{ display: 'inline-flex', gap: 6 }}>
                  {[7, 2].map(days => (
                    <button
                      key={days}
                      onClick={() => setWindowDays(days)}
                      style={{
                        padding: '4px 12px', borderRadius: 999, border: 'none', cursor: 'pointer', fontFamily: 'inherit',
                        fontSize: 12.5, fontWeight: windowDays === days ? 600 : 400,
                        background: windowDays === days ? 'rgba(0,113,227,0.10)' : 'transparent',
                        color: windowDays === days ? V3.tint : V3.label2,
                      }}
                    >
                      近 {days} 天
                    </button>
                  ))}
                </span>
              }
            >
              {history.loading && !history.drives.length ? <LoadingNote /> : <StatesBar segments={states} windowStart={windowStart} windowEnd={windowEnd} />}
              <div style={{ fontSize: 12, color: V3.label3, marginTop: 8 }}>默认最近一周；色段由行程与充电区间推导，空缺时段为驻车。</div>
            </Panel>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '2px 4px', fontSize: 12.5, color: V3.label3 }}>
            <span style={{ fontWeight: 600 }}>固件版本</span>
            {status.firmware ? (
              <span className="tabular" style={{ color: V3.label2, fontWeight: 600 }}>{status.firmware}</span>
            ) : (
              <span>待补数据：状态快照未提供 car_version</span>
            )}
          </div>
        </div>
      ) : null}
    </div>
  )
}
