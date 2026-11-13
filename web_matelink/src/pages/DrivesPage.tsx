import { DayCards } from '../components/DayCards'
import { ParkingBetween } from '../components/ParkingBetween'
import { calendarDays, civilDay } from '../components/presentation-policy'
import { usePagedHistory } from './usePagedHistory'
import { useEffect, useMemo, useState } from 'react'
import { ApiError, getDriveDetail, getDrivesPage, getParkedBetween, type DriveDetail } from '../api/matelink'
import type { WebCharge, WebDriveSummary, WebParked } from '../api/matelink-types'
import { recentDriveModel } from '../api/recent-drive'
import { elevationStats } from '../api/drive-elevation'
import { driveEnergyMethodLabel, resolveDriveEnergy, resolveSummaryEnergy } from '../api/drive-energy.ts'
import { pairedEfficiency, stateSegments } from '../api/panel-data'
import { socSegments, speedHistogram } from '../api/tm-data'
import { BatteryPill, Icon, Skeleton, TONES, type IconName, type Tone } from '../components/viz'
import { Panel, V3 } from '../components/v3'
import { BarsPanel, DATA_COLORS, StackChart, StatesBar, StatTile, TILE_COLORS, fmtDuration, fmtNum, type ChartRow, type StackSeriesDef } from '../components/panels'
import { DataTable, DonutShares, GapPanel, MapPanel, SocStitchChart } from '../components/tm'
import { useCar } from './useCar'
import { useHistory } from './useHistory'

/**
 * 行程记录 — TeslaMate Drives + Drive Details + Trip 的复刻（所有者
 * 2026-11-08 18:39 定版）：详情页上半是 Drive Details（轨迹地图、
 * 速度图、参数瓷砖做全：时长/里程/电量变化/最高速度/最大功率/
 * 能耗/效率），下半是 Trip。能耗走本机口径（api/drive-energy.ts，
 * 与 App 一致：功率积分/电量差估算等，瓷砖标注来源）；最大功率
 * 仅采样存在时显示。海拔后端未记录 → 待补面板。
 */

const num = (value: number | null, digits = 0): string => (value == null ? '--' : value.toFixed(digits))

function formatTime(iso: string | null): string {
  if (!iso) return '--'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '--' : date.toLocaleString('zh-CN', { hour12: false })
}

function RouteVisual({ start, end, large = false }: { start: string; end: string; large?: boolean }) {
  const dot = large ? 13 : 11
  const fontSize = large ? 16.5 : 14
  return (
    <div style={{ display: 'flex', gap: 11, minWidth: 0 }}>
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', paddingTop: 4, flexShrink: 0 }}>
        <span style={{ width: dot, height: dot, borderRadius: '50%', border: `2.5px solid ${TONES.teal}`, background: 'var(--apple-surface)', boxSizing: 'border-box' }} />
        <span style={{ width: 2, flex: 1, minHeight: large ? 22 : 15, margin: '3px 0', borderRadius: 1, background: TONES.teal, opacity: 0.55 }} />
        <span style={{ width: dot, height: dot, borderRadius: '50%', background: TONES.teal, boxSizing: 'border-box' }} />
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', justifyContent: 'space-between', gap: large ? 14 : 9, minWidth: 0, padding: '1px 0' }}>
        <span style={{ fontSize, fontWeight: 600, lineHeight: 1.3, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{start}</span>
        <span style={{ fontSize, fontWeight: 600, lineHeight: 1.3, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{end}</span>
      </div>
    </div>
  )
}

function Chip({ icon, tone, text }: { icon: IconName; tone: Tone; text: string }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, background: 'var(--apple-surface-tertiary)', borderRadius: 999, padding: '4px 10px', fontSize: 12, fontWeight: 500 }}>
      <span style={{ color: TONES[tone], display: 'inline-flex' }}><Icon name={icon} size={13} /></span>
      <span className="tabular">{text}</span>
    </span>
  )
}

function PowerBar({ watts }: { watts: number }) {
  const pct = Math.max(0, Math.min(100, watts))
  const color = watts < 50 ? TONES.green : TONES.amber
  return (
    <div style={{ marginTop: 14 }}>
      <div style={{ position: 'relative', height: 10, borderRadius: 5, background: 'var(--apple-surface-tertiary)' }}>
        <div style={{ position: 'absolute', inset: 0, width: `${pct}%`, borderRadius: 5, background: color, transition: 'width 600ms ease' }} />
        <span style={{ position: 'absolute', top: -3, bottom: -3, left: '50%', width: 2, borderRadius: 1, background: 'var(--apple-label-secondary)', opacity: 0.55 }} />
      </div>
      <div className="text-tertiary tabular" style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11.5, marginTop: 5 }}>
        <span>0 W</span>
        <span>休眠参考 50 W</span>
        <span>100 W</span>
      </div>
    </div>
  )
}

function BatteryTile({ from, to }: { from: number | null; to: number | null }) {
  return (
    <StatTile label="电量变化">
      <div style={{ marginTop: 12 }}>
        <BatteryPill from={from} to={to} width={104} />
      </div>
    </StatTile>
  )
}

function countOf(rows: ChartRow[], key: string): number {
  return rows.reduce((count, row) => (row[key] != null ? count + 1 : count), 0)
}

/* --------------------------- Detail view --------------------------- */

function DriveDetailView({
  carId,
  carModel,
  drive,
  newerDriveId,
  allDrives,
  allCharges,
  onBack,
}: {
  carId: number
  carModel: string | null
  drive: WebDriveSummary
  newerDriveId: string | null
  allDrives: readonly WebDriveSummary[]
  allCharges: readonly WebCharge[]
  onBack: () => void
}) {
  const [detail, setDetail] = useState<DriveDetail | null>(null)
  const [parked, setParked] = useState<WebParked | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [fullCurves,setFullCurves]=useState(false)

  useEffect(() => {
    let live = true
    const controller=new AbortController();setDetail(null);setParked(null);setError(null)
    getDriveDetail(carId, drive.id,controller.signal)
      .then(result => {
        if (live) setDetail(result)
      })
      .catch((reason: unknown) => {
        if (live) setError(reason instanceof ApiError ? reason.message : '读取行程详情失败。')
      })
    if (newerDriveId && /^\d+$/.test(drive.id) && /^\d+$/.test(newerDriveId)) {
      getParkedBetween(carId, drive.id, newerDriveId,controller.signal)
        .then(result => {
          if (live) setParked(result)
        })
        .catch(() => {
          if (live) setParked(null)
        })
    }
    return () => {
      live = false
      controller.abort()
    }
  }, [carId, drive.id, newerDriveId])

  const current = detail?.drive ?? drive
  const points = useMemo(() => detail?.points ?? [], [detail])
  const elevation = elevationStats(points)
  // 能耗：本机口径解析（详情带逐点采样，可走功率积分）。
  const energy = useMemo(
    () => resolveDriveEnergy(current, { points, model: carModel }),
    [current, points, carModel]
  )
  const efficiency = energy.efficiencyWhKm != null ? energy.efficiencyWhKm / 10 : null
  // 最大功率：仅当逐点采样带功率值时才有（生产遥测通常无此字段）。
  const maxPowerKw = useMemo(() => {
    const values = points.flatMap(p => (p.powerKw != null ? [p.powerKw] : []))
    return values.length ? Math.max(...values) : null
  }, [points])
  const parkedMinutes = parked?.startDate && parked?.endDate ? Math.round((Date.parse(parked.endDate) - Date.parse(parked.startDate)) / 60000) : null

  const rows: ChartRow[] = useMemo(
    () =>
      points.map(point => ({
        t: Date.parse(point.date),
        power: point.powerKw,
        speed: point.speedKmh,
        soc: point.batteryLevel,
        elev: point.elevationM,
        inside: point.insideTempC,
        outside: point.outsideTempC,
      })),
    [points]
  )
  const insufficient = (key: string, name: string): string | undefined => {
    const count = countOf(rows, key)
    if (count >= 2) return undefined
    return count === 1 ? `仅 1 个采样点，无法绘制${name}曲线` : `无${name}采样数据`
  }
  const series: StackSeriesDef[] = [
    { key: 'power', title: '功率', unit: 'kW', color: DATA_COLORS.teal, height: 148, digits: 1, emptyReason: insufficient('power', '功率') },
    { key: 'speed', title: '速度', unit: 'km/h', color: DATA_COLORS.graphite, height: 130, domain: [0, 'auto'], emptyReason: insufficient('speed', '速度') },
    { key: 'soc', title: '电量', unit: '%', color: DATA_COLORS.tealDeep, height: 70, domain: [0, 100], emptyReason: insufficient('soc', '电量') },
  ]
  const tempSeries: StackSeriesDef[] = [
    { key: 'outside', title: '车外温度', unit: '°C', color: DATA_COLORS.graphite, height: 80, digits: 1, emptyReason: insufficient('outside', '车外温度') },
    { key: 'inside', title: '车内温度', unit: '°C', color: DATA_COLORS.tealLight, height: 80, digits: 1, emptyReason: insufficient('inside', '车内温度') },
  ]
  const hasTemp = tempSeries.some(def => def.emptyReason == null)
  const hasElevation = countOf(rows, 'elev') >= 2

  const routePoints = useMemo(
    () => points.flatMap(p => (p.latitude != null && p.longitude != null ? [{ lat: p.latitude, lng: p.longitude }] : [])),
    [points]
  )
  const startPoint = routePoints[0] ?? (current.startLatitude != null && current.startLongitude != null ? { lat: current.startLatitude, lng: current.startLongitude } : null)
  const endPoint = routePoints[routePoints.length - 1] ?? (current.endLatitude != null && current.endLongitude != null ? { lat: current.endLatitude, lng: current.endLongitude } : null)

  const speedBuckets = useMemo(
    () => speedHistogram(points.flatMap(p => (p.speedKmh != null ? [p.speedKmh] : []))),
    [points]
  )
  const hasSpeedSamples = speedBuckets.some(b => b.count > 0)

  // ---- Trip: the calendar day of this drive ----
  const day = useMemo(() => {
    const start = Date.parse(current.startDate ?? '')
    if (!Number.isFinite(start)) return null
    const d = new Date(start)
    const dayStart = new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
    const dayEnd = dayStart + 86_400_000
    const dayDrives = allDrives.filter(x => {
      const t = Date.parse(x.startDate ?? '')
      return Number.isFinite(t) && t >= dayStart && t < dayEnd
    })
    const dayCharges = allCharges.filter(x => {
      const t = Date.parse(x.startDate ?? '')
      return Number.isFinite(t) && t >= dayStart && t < dayEnd
    })
    const drivingMin = dayDrives.reduce((sum, x) => sum + (x.durationMinutes ?? 0), 0)
    const chargingMin = dayCharges.reduce((sum, x) => sum + (x.durationMinutes ?? 0), 0)
    const parkedMin = Math.max(0, 1440 - drivingMin - chargingMin)
    return {
      label: d.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' }),
      dayStart,
      dayEnd,
      dayDrives,
      dayCharges,
      drivingMin,
      chargingMin,
      parkedMin,
      states: stateSegments(allDrives, allCharges, dayStart, dayEnd),
      soc: socSegments(dayCharges, dayDrives, new Map(), dayStart, dayEnd),
    }
  }, [current.startDate, allDrives, allCharges])

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div>
        <button className="apple-btn-secondary" onClick={onBack} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
          <span style={{ display: 'inline-flex', transform: 'rotate(180deg)' }}><Icon name="chevron" size={14} /></span>
          返回列表
        </button><button className="apple-btn-secondary" style={{float:'right'}} onClick={()=>{setFullCurves(true);requestAnimationFrame(()=>document.getElementById('full-curves')?.scrollIntoView({block:'start'}))}}>详细曲线</button>
      </div>

      <Panel style={{ borderRadius: 24 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16, flexWrap: 'wrap' }}>
          <div style={{ flex: '1 1 320px', minWidth: 0 }}>
            <RouteVisual start={current.startAddress ?? '未知地点'} end={current.endAddress ?? '未知地点'} large />
          </div>
          <div style={{ textAlign: 'right', flexShrink: 0 }}>
            <div className="tabular" style={{ fontSize: 34, fontWeight: 700, lineHeight: 1 }}>
              {num(current.distanceKm, 1)}
              <span className="text-secondary" style={{ fontSize: 14, fontWeight: 500, marginLeft: 4 }}>km</span>
            </div>
            <div className="text-tertiary" style={{ fontSize: 12, marginTop: 5 }}>行程距离</div>
          </div>
        </div>
        <p className="text-secondary tabular" style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13, marginTop: 16 }}>
          <span style={{ color: TONES.blue, display: 'inline-flex' }}><Icon name="clock" size={14} /></span>
          {formatTime(current.startDate)} → {formatTime(current.endDate)}
        </p>
      </Panel>

      {startPoint || endPoint ? (
        <MapPanel
          title="行程轨迹"
          points={[
            ...(startPoint ? [{ ...startPoint, label: '起点' }] : []),
            ...(endPoint ? [{ ...endPoint, label: '终点' }] : []),
          ]}
          polyline={routePoints}
          height={300}
        />
      ) : null}

      {error ? <Panel><p style={{ color: V3.errorText, fontSize: 14.5 }}>{error}</p></Panel> : null}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12 }}>
        <StatTile label="距离" value={num(current.distanceKm, 1)} unit="km" />
        <StatTile label="时长" value={fmtDuration(current.durationMinutes)} accent={TILE_COLORS.blue} />
        {energy.energyKwh != null ? (
          <StatTile label="耗电量" value={num(energy.energyKwh, 1)} unit="kWh" accent={TILE_COLORS.green} caption={`本机口径 · ${driveEnergyMethodLabel(energy)}`} />
        ) : (
          <StatTile label="耗电量" value="--" caption="本机口径暂不可计算（无合格来源）" />
        )}
        <StatTile label="平均能耗" value={efficiency != null ? fmtNum(efficiency, 1) : '--'} unit="kWh/100km" accent={TILE_COLORS.blue} caption={energy.method ? `本机口径 · ${driveEnergyMethodLabel(energy)}` : undefined} />
        <BatteryTile from={current.startBatteryLevel} to={current.endBatteryLevel} />

        <StatTile
          label="里程表"
          value={current.odometerStartKm != null && current.odometerEndKm != null ? `${Math.round(current.odometerStartKm).toLocaleString('zh-CN')} → ${Math.round(current.odometerEndKm).toLocaleString('zh-CN')}` : '--'}
          unit={current.odometerStartKm != null ? 'km' : undefined}
        />
      </div>

      {detail && detail.reason === 'quarantined_history' ? (
        <Panel><p style={{ fontSize: 14, color: V3.label2 }}>这条记录处于隔离状态，曲线不可用。</p></Panel>
      ) : detail && points.length ? (
        <>
          <Panel title="速度"><div className="ml-day-nav"><span>平均 {num(current.speedAvgKmh)} km/h</span><span>最高 {num(current.speedMaxKmh)} km/h</span></div><StackChart data={rows} series={[series[1]]} syncKey={`drive-speed-${drive.id}`} /></Panel>
          <Panel title="功率"><p className="text-secondary" style={{fontSize:13,marginBottom:12}}>峰值 {maxPowerKw==null?'—':fmtNum(maxPowerKw,1)} kW · 仅使用实际采样</p><StackChart data={rows} series={[series[0]]} syncKey={`drive-power-${drive.id}`} /></Panel>
          <p className="text-secondary" style={{fontSize:12}}>孤立的点表示少量实际采样；断开部分缺少观测，不补画连续曲线。</p>
          <details open={fullCurves} className="ml-disclosure" id="full-curves" onToggle={e=>setFullCurves(e.currentTarget.open)}><summary>详细曲线</summary><div>
          <StackChart data={rows} series={[series[2]]} syncKey={`drive-soc-${drive.id}`} />
          {hasTemp ? (
            <Panel title="温度（逐点采样）">
              <StackChart data={rows} series={tempSeries} syncKey={`drive-temp-${drive.id}`} />
            </Panel>
          ) : null}
          {hasElevation ? (
            <Panel title="海拔">
              <StackChart data={rows} series={[{ key: 'elev', title: '海拔', unit: 'm', color: DATA_COLORS.graphiteLight, height: 110, emptyReason: undefined }]} syncKey={`drive-elev-${drive.id}`} />
              {elevation ? (
                <p className="tabular" style={{ fontSize: 12.5, color: V3.label3, marginTop: 10, fontVariantNumeric: 'tabular-nums' }}>
                  海拔采样：最高 {fmtNum(elevation.maxM)} m · 最低 {fmtNum(elevation.minM)} m
                  {elevation.gainM != null ? ` · 累计爬升 +${fmtNum(elevation.gainM)} m` : ''}
                  {elevation.lossM != null ? ` · 累计下降 −${fmtNum(elevation.lossM)} m` : ''}
                </p>
              ) : null}
            </Panel>
          ) : (
            <GapPanel title="海拔" meaning="行程沿途的海拔变化与累计爬升/下降。" need="在行程轨迹点中记录海拔（当前轨迹点无海拔字段）" />
          )}
          {hasSpeedSamples ? (
            <Panel title="速度分布">
              <BarsPanel data={speedBuckets.map(b => ({ label: b.label, value: b.count }))} unit="次" color={DATA_COLORS.graphite} height={150} />
            </Panel>
          ) : null}
          </div></details>
        </>
      ) : detail ? (
        <Panel><p style={{ fontSize: 14, color: V3.label2 }}>这段行程没有可用的采样曲线。</p></Panel>
      ) : (
        <Skeleton height={420} />
      )}

      {parked ? (
        <Panel title="之后驻车">
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12 }}>
            <StatTile label="驻车时长" value={fmtDuration(parkedMinutes)} />
            {parked.startBatteryLevel != null && parked.endBatteryLevel != null ? (
              <BatteryTile from={parked.startBatteryLevel} to={parked.endBatteryLevel} />
            ) : (
              <StatTile label="电量变化" value={parked.batteryDelta != null ? `${num(parked.batteryDelta, 1)}%` : '--'} />
            )}
            <StatTile label="车外温度" value={num(parked.outsideTempC, 1)} unit="°C" />
            {parked.energyKwh != null ? <StatTile label="待机能耗" value={num(parked.energyKwh, 2)} unit="kWh" accent={V3.teal} /> : null}
            {parked.averagePowerW != null ? <StatTile label="平均待机功率" value={num(parked.averagePowerW)} unit="W" /> : null}
          </div>
          {parked.averagePowerW != null ? <PowerBar watts={parked.averagePowerW} /> : null}
          <p className="text-tertiary" style={{ fontSize: 12, lineHeight: 1.6, marginTop: 12 }}>
            驻车地点：{parked.address ?? '未知地点'}。待机能耗仅在后端能量证据完整时给出；原生记录的驻车耗电量见「驻车掉电」页的口径说明。
          </p>
        </Panel>
      ) : null}

      {day ? (
        <>
          <h2 style={{ fontSize: 20, fontWeight: 700, marginTop: 8 }}>{day.label} · 当日概览（行程回顾）</h2>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 16 }}>
            <Panel title="当日时间分配">
              <DonutShares
                parts={[
                  { label: '行驶', value: day.drivingMin, color: DATA_COLORS.teal },
                  { label: '充电', value: day.chargingMin, color: "#d97706" },
                  { label: '驻车', value: day.parkedMin, color: DATA_COLORS.graphiteLight },
                ]}
                unit="分钟"
              />
            </Panel>
            <Panel title="当日状态线">
              <StatesBar segments={day.states} windowStart={day.dayStart} windowEnd={day.dayEnd} />
            </Panel>
          </div>
          <Panel title="当日电量">
            {day.soc.length ? <SocStitchChart segments={day.soc} height={180} /> : <p style={{ fontSize: 13.5, color: V3.label3 }}>当日暂无可拼接的电量记录。</p>}
          </Panel>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(340px, 1fr))', gap: 16 }}>
            <Panel title="当日行程">
              <DataTable
                columns={[{ label: '时间' }, { label: '起点 → 终点' }, { label: '距离' }]}
                rows={day.dayDrives.map(d => [
                  formatTime(d.startDate),
                  `${d.startAddress ?? '未知'} → ${d.endAddress ?? '未知'}`,
                  d.distanceKm != null ? `${fmtNum(d.distanceKm, 1)} km` : '--',
                ])}
                emptyText="当日无其他行程。"
              />
            </Panel>
            <Panel title="当日充电">
              <DataTable
                columns={[{ label: '时间' }, { label: '地点' }, { label: '充入电量' }]}
                rows={day.dayCharges.map(c => [
                  formatTime(c.startDate),
                  c.address ?? '未知地点',
                  c.energyAddedKwh != null ? `${fmtNum(c.energyAddedKwh, 1)} kWh` : '--',
                ])}
                emptyText="当日无充电。"
              />
            </Panel>
          </div>
        </>
      ) : null}
    </div>
  )
}

/* ---------------------------- List view ---------------------------- */

export default function DrivesPage() {
  const { cars, car, carId, setCarId, error: carError } = useCar()
  const {rows, page, totalPages, loading, error, reload, loadMore} = usePagedHistory<WebDriveSummary>(carId,getDrivesPage)
  const [selected, setSelected] = useState<{ drive: WebDriveSummary; newerDriveId: string | null } | null>(null)
  const history = useHistory(carId)
  const [windowDays,setWindowDays]=useState(7)
  const groups=useMemo(()=>{
    const byDay=new Map<string,WebDriveSummary[]>()
    for(const row of rows){const key=civilDay(row.startDate)??'日期未知';byDay.set(key,[...(byDay.get(key)??[]),row])}
    if(!windowDays)return [...byDay].sort((a,b)=>b[0].localeCompare(a[0])).map(([key,items])=>({key,items}))
    const today=new Date(), end=civilDay(today.toISOString())!
    today.setDate(today.getDate()-windowDays+1)
    const start=civilDay(today.toISOString())!
    return calendarDays(start,end).reverse().map(key=>({key,items:byDay.get(key)??[]}))
  },[rows,windowDays])


  useEffect(() => { setSelected(null) },[carId])

  const summary = useMemo(() => {
    const all = history.drives
    return {
      count: all.length,
      distance: all.reduce((sum, d) => sum + (d.distanceKm ?? 0), 0),
      durationMin: all.reduce((sum, d) => sum + (d.durationMinutes ?? 0), 0),
    }
  }, [history.drives])
  const efficiency = useMemo(() => pairedEfficiency(history.drives, car?.model ?? null), [history.drives, car?.model])
  const incomplete = useMemo(() => history.drives.filter(d => d.endDate == null), [history.drives])

  if (selected && carId != null) {
    return (
      <DriveDetailView
        carId={carId}
        carModel={car?.model ?? null}
        drive={selected.drive}
        newerDriveId={selected.newerDriveId}
        allDrives={history.drives}
        allCharges={history.charges}
        onBack={() => setSelected(null)}
      />
    )
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12 }}>
        <button className="apple-btn-secondary" disabled={loading} onClick={() => {void reload();void history.reload()}}>刷新记录</button>
        <h1 style={{ fontSize: 28, fontWeight: 700 }}>行程记录</h1>
        {cars.length > 1 ? (
          <select className="apple-input" style={{ width: 'auto' }} value={carId ?? ''} onChange={event => setCarId(Number(event.target.value))}>
            {cars.map(item => (
              <option key={item.id} value={item.id}>{item.name}</option>
            ))}
          </select>
        ) : null}
      </div>
      {carError ? <Panel><p style={{ color: V3.errorText, fontSize: 14.5 }}>{carError}</p></Panel> : null}
      {error ? <Panel><p style={{ color: V3.errorText, fontSize: 14.5 }}>{error}</p></Panel> : null}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 12 }}>
        <StatTile label="总里程" value={fmtNum(summary.distance, 0)} unit="km" caption={history.complete ? '全部已记录行程' : '已加载行程'} />
        <StatTile label="行程次数" value={summary.count.toLocaleString('zh-CN')} />
        <StatTile label="总时长" value={fmtDuration(summary.durationMin)} accent={TILE_COLORS.blue} />
        <StatTile
          label="平均能耗"
          value={efficiency.kwhPer100km != null ? fmtNum(efficiency.kwhPer100km, 1) : '--'}
          unit="kWh/100km"
          accent={TILE_COLORS.blue}
          caption={efficiency.totalCount > 0 ? `本机口径 · 基于 ${efficiency.pairedCount}/${efficiency.totalCount} 次可计算行程` : '暂无行程'}
        />
      </div>

      {incomplete.length ? (
        <Panel title="未完成行程">
          <DataTable
            columns={[{ label: '开始时间' }, { label: '起点' }, { label: '当前电量' }]}
            rows={incomplete.map(d => [
              formatTime(d.startDate),
              d.startAddress ?? '未知地点',
              d.endBatteryLevel != null ? `${Math.round(d.endBatteryLevel)}%` : '--',
            ])}
          />
        </Panel>
      ) : null}

      <div className="ml-day-nav" aria-label="行程日期范围"><button aria-pressed={windowDays===7} onClick={()=>setWindowDays(7)}>近一周</button><button aria-pressed={windowDays===30} onClick={()=>setWindowDays(30)}>近一月</button><button aria-pressed={windowDays===0} onClick={()=>setWindowDays(0)}>全部已加载</button></div>
      <Panel title="按日行程次数"><BarsPanel data={groups.filter(g=>g.key!=='日期未知').slice().reverse().map(g=>({label:g.key.slice(5),value:g.items.length}))} unit="次" color={DATA_COLORS.graphite} height={140}/><p className="text-secondary" style={{fontSize:12}}>按已返回记录统计；空柱不证明当天没有行程。</p></Panel>
      <DayCards key={`${carId}:${windowDays}`} groups={groups}>{group => <>{group.items.map(drive => {
          const index=rows.findIndex(row=>row===drive)
          const model = recentDriveModel(drive)
          const newerDriveId = index > 0 ? rows[index - 1].id : null
          const driveEnergy = resolveSummaryEnergy(drive, car?.model ?? null)
          return (
            <div key={drive.id}>
            {index>0 && carId!=null ? <ParkingBetween key={`${carId}:${drive.id}:${rows[index-1].id}`} carId={carId} older={drive} newer={rows[index-1]} /> : null}
            <button
              className="apple-card hover-lift"
              onClick={() => setSelected({ drive, newerDriveId })}
              style={{ display: 'block', width: '100%', textAlign: 'left', cursor: 'pointer', color: 'inherit', font: 'inherit' }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16 }}>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <RouteVisual start={drive.startAddress ?? '未知地点'} end={drive.endAddress ?? '未知地点'} />
                </div>
                <div style={{ textAlign: 'right', flexShrink: 0 }}>
                  <div className="tabular" style={{ fontSize: 28, fontWeight: 700, lineHeight: 1 }}>
                    {drive.distanceKm != null ? drive.distanceKm.toFixed(1) : '--'}
                    <span className="text-secondary" style={{ fontSize: 13, fontWeight: 500, marginLeft: 3 }}>km</span>
                  </div>
                  <div className="text-tertiary tabular" style={{ fontSize: 12, marginTop: 5 }}>结束 {model.endText}</div>
                </div>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap', marginTop: 14 }}>
                <BatteryPill from={drive.startBatteryLevel} to={drive.endBatteryLevel} />
                <span style={{ display: 'inline-flex', gap: 6, flexWrap: 'wrap' }}>
                  <Chip icon="clock" tone="blue" text={model.durationText} />
                  {drive.speedAvgKmh != null ? <Chip icon="gauge" tone="teal" text={`平均 ${num(drive.speedAvgKmh)} km/h`} /> : null}
                  {drive.speedMaxKmh != null ? <Chip icon="zap" tone="amber" text={`最高 ${num(drive.speedMaxKmh)} km/h`} /> : null}
                  {driveEnergy.efficiencyWhKm != null ? <Chip icon="chart" tone="blue" text={`能耗 ${(driveEnergy.efficiencyWhKm / 10).toFixed(1)} kWh/100km`} /> : null}
                </span>
              </div>
            </button></div>
          )
        })}</>}</DayCards>
      <div>
        {!rows.length && !loading ? (
          <Panel><p style={{ fontSize: 14, color: V3.label2 }}>暂无行程记录。</p></Panel>
        ) : null}
        {loading ? (
          <>
            <Skeleton height={118} />
            {rows.length === 0 ? <Skeleton height={118} /> : null}
            {rows.length === 0 ? <Skeleton height={118} /> : null}
          </>
        ) : null}
      </div>

      {page < totalPages ? (
        <button className="apple-btn-secondary" disabled={loading} onClick={() => void loadMore()}>
          加载更多
        </button>
      ) : null}
    </div>
  )
}
