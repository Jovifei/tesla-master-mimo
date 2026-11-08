import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { getCarStatus, getChargeDetail, type ChargeDetail } from '../api/matelink'
import type { WebCarStatus, WebChargePoint } from '../api/matelink-types'
import { pairedEfficiency, stateSegments } from '../api/panel-data'
import { socSegments } from '../api/tm-data'
import { AnomalyBand, ChargeRuler, Panel, PremiseStrip, V3 } from '../components/v3'
import { DATA_COLORS, StackChart, StatTile, StatesBar, type ChartRow } from '../components/panels'
import { CarPicker, ErrorNote, GapTile, GaugeArc, LoadingNote, MapPanel, PageTitle, SocStitchChart } from '../components/tm'
import { useCar } from './useCar'
import { useHistory } from './useHistory'

/**
 * 总览 — TeslaMate Overview 的复刻构成：电量/电压/功率三仪表 + 电量
 * 曲线大图 + 能耗与里程瓷砖行 + 温度仪表 + 状态时间线，表面为我方
 * 暖纸面。缺失参数（毛能耗等）按待补数据呈现（docs/TESLAMATE-MAPPING.md）。
 */

const DAY = 86_400_000

export default function WebDashboard() {
  const { cars, car, carId, setCarId, error: carError } = useCar()
  const [status, setStatus] = useState<WebCarStatus | null>(null)
  const [statusError, setStatusError] = useState<string | null>(null)
  const [windowDays, setWindowDays] = useState(2)
  const history = useHistory(carId)
  const [chargePoints, setChargePoints] = useState<ReadonlyMap<string, readonly WebChargePoint[]>>(new Map())
  const [liveDetail, setLiveDetail] = useState<ChargeDetail | null>(null)

  const charging = status?.chargingState === 'Charging'

  const loadStatus = useCallback(async () => {
    if (carId == null) return
    try {
      const next = await getCarStatus(carId)
      setStatus(next)
      setStatusError(null)
    } catch {
      setStatusError('读取车辆状态失败，请稍后重试。')
    }
  }, [carId])

  useEffect(() => {
    void loadStatus()
  }, [loadStatus])

  // 动态（所有者选项 A）：充电中每 20 秒刷新状态。
  useEffect(() => {
    if (!charging) return
    const timer = window.setInterval(() => void loadStatus(), 20_000)
    return () => window.clearInterval(timer)
  }, [charging, loadStatus])

  const windowStart = useMemo(() => Date.now() - windowDays * DAY, [windowDays])
  const windowEnd = Date.now()

  // Fetch real in-charge samples for the stitch (bounded, most recent first).
  useEffect(() => {
    if (carId == null || !history.charges.length) return
    const inWindow = history.charges
      .filter(c => {
        const end = Date.parse(c.endDate ?? c.startDate ?? '')
        return Number.isFinite(end) && end >= windowStart
      })
      .slice(0, 6)
    let cancelled = false
    void (async () => {
      const map = new Map<string, readonly WebChargePoint[]>()
      for (const charge of inWindow) {
        try {
          const detail = await getChargeDetail(carId, charge.id)
          if (detail.points.length >= 2) map.set(charge.id, detail.points)
        } catch {
          /* boundary stitch still applies */
        }
      }
      if (!cancelled) setChargePoints(map)
    })()
    return () => {
      cancelled = true
    }
  }, [carId, history.charges, windowStart])

  const liveCharge = useMemo(() => history.charges.find(c => c.isCharging) ?? null, [history.charges])
  useEffect(() => {
    if (carId == null || !liveCharge) {
      setLiveDetail(null)
      return
    }
    let cancelled = false
    const fetchDetail = async () => {
      try {
        const detail = await getChargeDetail(carId, liveCharge.id)
        if (!cancelled) setLiveDetail(detail)
      } catch {
        /* keep last good detail */
      }
    }
    void fetchDetail()
    if (!charging) return () => { cancelled = true }
    const timer = window.setInterval(() => void fetchDetail(), 20_000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [carId, liveCharge, charging])

  const segments = useMemo(
    () => socSegments(history.charges, history.drives, chargePoints, windowStart, windowEnd),
    [history.charges, history.drives, chargePoints, windowStart, windowEnd]
  )
  const states = useMemo(
    () => stateSegments(history.drives, history.charges, windowStart, windowEnd),
    [history.drives, history.charges, windowStart, windowEnd]
  )
  const efficiency = useMemo(() => pairedEfficiency(history.drives), [history.drives])
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

  return (
    <div>
      <div style={{ margin: '-20px -32px 16px' }}>
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
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 16 }}>
            <GaugeArc label="电量" value={status.batteryLevel} unit="%" />
            <GaugeArc
              label="交流输入电压"
              value={charging ? status.liveVoltageV : null}
              max={400}
              unit="V"
              gapReason={charging ? '当前快照未提供电压' : '仅充电时有实时值；历史电压未记录'}
            />
            <GaugeArc label="充电功率" value={status.chargerPowerKw} max={250} unit="kW" digits={1} />
            <div style={{ gridColumn: 'span 2', minWidth: 0 }}>
              <Panel title="电量曲线" action={<span style={{ fontSize: 12, color: V3.label3 }}>近 {windowDays} 天 · 拼接自会话边界与充电采样</span>}>
                {segments.length ? <SocStitchChart segments={segments} height={158} /> : <div style={{ fontSize: 13, color: V3.label3 }}>该时段暂无可拼接的电量记录。</div>}
              </Panel>
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 16 }}>
            {efficiency.kwhPer100km != null ? (
              <StatTile label="平均能耗（净）" value={efficiency.kwhPer100km.toFixed(1)} unit="kWh/100km" caption={`基于 ${efficiency.pairedCount} 次有完整能耗的行程`} />
            ) : (
              <GapTile label="平均能耗（净）" need="行程净能耗的可信计量（后端当前明确不提供）" />
            )}
            <GapTile label="平均能耗（毛）" need="净能耗与全量交流输入电量" />
            <StatTile label="已记录总里程" value={totalDistance.toLocaleString('zh-CN', { maximumFractionDigits: 0 })} unit="km" caption={history.complete ? '全部已记录行程合计' : '历史较多，仅统计已加载部分'} />
            <StatTile label="续航（预计）" value={status.estimatedRangeKm != null ? Math.round(status.estimatedRangeKm).toString() : '--'} unit="km" caption="按当前电量估算" />
            {status.firmware ? (
              <StatTile label="固件版本" value={status.firmware} />
            ) : (
              <GapTile label="固件版本" need="在车辆状态中提供软件版本（car_version）" />
            )}
            <StatTile label="里程表" value={status.odometerKm != null ? Math.round(status.odometerKm).toLocaleString('zh-CN') : '--'} unit="km" />
          </div>

          <Panel title="当前电量">
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, flexWrap: 'wrap' }}>
              <span className="tabular" style={{ fontSize: 34, fontWeight: 700, color: V3.label }}>{status.batteryLevel != null ? Math.round(status.batteryLevel) : '--'}%</span>
              <span style={{ fontSize: 13, color: V3.label2 }}>
                {status.chargingState === 'Charging' ? `正在充电 · 已充入 ${status.chargeEnergyAddedKwh ?? '--'} 度` : status.state ?? '车辆状态未知'}
              </span>
              <span style={{ marginLeft: 'auto', fontSize: 12.5 }}>
                <Link to="/charge-level" style={{ color: V3.tint, textDecoration: 'none', fontWeight: 600 }}>查看电量历史 →</Link>
              </span>
            </div>
            {status.batteryLevel != null ? <ChargeRuler soc={status.batteryLevel} snapshot={status.source === 'telemetry_cache'} /> : null}
          </Panel>

          {liveDetail && liveRows.length >= 2 ? (
            <Panel title="充电详情（实时）" action={<span style={{ fontSize: 12, color: V3.label3 }}>充电中 · 每 20 秒刷新</span>}>
              <StackChart
                data={liveRows}
                series={[
                  { key: 'power', title: '功率', unit: 'kW', color: DATA_COLORS.teal, height: 150, digits: 1, domain: [0, 'auto'] },
                  { key: 'soc', title: '电量', unit: '%', color: DATA_COLORS.graphite, height: 80, domain: [0, 100] },
                ]}
              />
              <div style={{ display: 'flex', gap: 18, flexWrap: 'wrap', marginTop: 10, fontSize: 13, color: V3.label2 }}>
                <span>当前电压 <strong>{liveDetail.summary.chargerVoltageV ?? status.liveVoltageV ?? '--'} V</strong></span>
                <span>当前电流 <strong>{liveDetail.summary.currentA ?? status.liveCurrentA ?? '--'} A</strong></span>
                <span>预计充满还需 <strong>{liveDetail.summary.timeToFullHours != null ? `${liveDetail.summary.timeToFullHours.toFixed(1)} 小时` : '--'}</strong></span>
              </div>
            </Panel>
          ) : null}

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 16 }}>
            <GaugeArc label="驾驶员设定温度" value={status.driverTempC} min={15} max={28} unit="°C" digits={1} />
            <GaugeArc label="车外温度" value={status.outsideTempC} min={-20} max={45} unit="°C" digits={1} />
            <GaugeArc label="车内温度" value={status.insideTempC} min={-20} max={45} unit="°C" digits={1} />
            <Panel title="安全与位置">
              <div style={{ fontSize: 13.5, color: V3.label2, lineHeight: 1.9 }}>
                <div>车锁：{status.locked == null ? '未知' : status.locked ? '已上锁' : '未上锁'} · 哨兵模式：{status.sentryMode == null ? '未知' : status.sentryMode ? '开启' : '关闭'} · 车窗：{status.windowsOpen == null ? '未知' : status.windowsOpen ? '未关' : '已关'}</div>
                <div>位置：{status.geofence ?? '未知'}{status.latitude != null && status.longitude != null ? `（${status.latitude.toFixed(5)}, ${status.longitude.toFixed(5)}）` : ''}</div>
              </div>
            </Panel>
          </div>

          {status.latitude != null && status.longitude != null ? (
            <MapPanel title="车辆位置" points={[{ lat: status.latitude, lng: status.longitude, label: car?.name ?? '车辆' }]} height={240} />
          ) : null}

          <Panel
            title="状态时间线"
            action={
              <span style={{ display: 'inline-flex', gap: 6 }}>
                {[2, 7].map(days => (
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
          </Panel>
        </div>
      ) : null}
    </div>
  )
}
