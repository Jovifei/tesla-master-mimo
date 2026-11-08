import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { ApiError, getDriveDetail, getDrivesPage, getParkedBetween, type DriveDetail } from '../api/matelink'
import type { WebDriveSummary, WebParked } from '../api/matelink-types'
import { recentDriveModel } from '../api/recent-drive'
import { elevationStats } from '../api/drive-elevation'
import { AreaSeries, BatteryPill, Icon, Kpi, Skeleton, TONES, type IconName, type Tone } from '../components/viz'
import { useCar } from './useCar'

const num = (value: number | null, digits = 0): string => (value == null ? '--' : value.toFixed(digits))

function formatTime(iso: string | null): string {
  if (!iso) return '--'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '--' : date.toLocaleString('zh-CN', { hour12: false })
}

function timeLabel(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/* ------------------------- Route visual ------------------------- */

/** Start/end dots joined by a line, addresses stacked beside them. */
function RouteVisual({ start, end, large = false }: { start: string; end: string; large?: boolean }) {
  const dot = large ? 13 : 11
  const fontSize = large ? 16.5 : 14
  return (
    <div style={{ display: 'flex', gap: 11, minWidth: 0 }}>
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', paddingTop: 4, flexShrink: 0 }}>
        <span style={{ width: dot, height: dot, borderRadius: '50%', border: `2.5px solid ${TONES.teal}`, background: 'var(--apple-surface)', boxSizing: 'border-box' }} />
        <span style={{ width: 2, flex: 1, minHeight: large ? 22 : 15, margin: '3px 0', borderRadius: 1, background: `linear-gradient(180deg, ${TONES.teal}66, ${TONES.teal})` }} />
        <span style={{ width: dot, height: dot, borderRadius: '50%', background: TONES.teal, boxSizing: 'border-box' }} />
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', justifyContent: 'space-between', gap: large ? 14 : 9, minWidth: 0, padding: '1px 0' }}>
        <span style={{ fontSize, fontWeight: 600, lineHeight: 1.3, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{start}</span>
        <span style={{ fontSize, fontWeight: 600, lineHeight: 1.3, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{end}</span>
      </div>
    </div>
  )
}

/* ---------------------------- Chips ----------------------------- */

function Chip({ icon, tone, text }: { icon: IconName; tone: Tone; text: string }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, background: 'var(--apple-surface-tertiary)', borderRadius: 999, padding: '4px 10px', fontSize: 12, fontWeight: 500 }}>
      <span style={{ color: TONES[tone], display: 'inline-flex' }}><Icon name={icon} size={13} /></span>
      <span className="tabular">{text}</span>
    </span>
  )
}

/* --------------------------- Sections ---------------------------- */

function Section({ title, icon, children }: { title: string; icon: IconName; children: ReactNode }) {
  return (
    <div className="apple-card">
      <h3 style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 16, fontWeight: 600, marginBottom: 14 }}>
        <span style={{ color: TONES.teal, display: 'inline-flex' }}><Icon name={icon} size={17} /></span>
        {title}
      </h3>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 12 }}>{children}</div>
    </div>
  )
}

/** Kpi-shaped tile that hosts the BatteryPill visual instead of a plain number. */
function BatteryTile({ from, to }: { from: number | null; to: number | null }) {
  return (
    <div style={{ background: 'var(--apple-surface-secondary)', borderRadius: 20, padding: '15px 17px', minWidth: 0 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--apple-label-secondary)', fontSize: 12.5, fontWeight: 600 }}>
        <span style={{ color: TONES.teal, display: 'inline-flex' }}><Icon name="battery" size={15} /></span>
        电量变化
      </div>
      <div style={{ marginTop: 12 }}>
        <BatteryPill from={from} to={to} width={104} />
      </div>
    </div>
  )
}

function CurveCard({
  title,
  icon,
  tone,
  unit,
  digits = 0,
  points,
}: {
  title: string
  icon: IconName
  tone: Tone
  unit: string
  digits?: number
  points: { x: string; y: number }[]
}) {
  const max = points.length ? Math.max(...points.map(point => point.y)) : null
  return (
    <div className="apple-card">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, marginBottom: 6 }}>
        <h3 style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 16, fontWeight: 600 }}>
          <span style={{ color: TONES[tone], display: 'inline-flex' }}><Icon name={icon} size={17} /></span>
          {title}
        </h3>
        {max != null ? (
          <span className="tabular text-secondary" style={{ fontSize: 12.5, fontWeight: 600 }}>
            最高 <span style={{ color: 'var(--apple-label)', fontSize: 15 }}>{num(max, digits)}</span> {unit}
          </span>
        ) : null}
      </div>
      <AreaSeries points={points} unit={unit} tone={tone} height={170} />
    </div>
  )
}

/* ------------------------- Parked power bar ------------------------- */

/** Average standby power against the 100 W scale, ticked at the 50 W sleep reference. */
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

/* --------------------------- Detail view --------------------------- */

function DriveDetailView({ carId, drive, newerDriveId, onBack }: { carId: number; drive: WebDriveSummary; newerDriveId: string | null; onBack: () => void }) {
  const [detail, setDetail] = useState<DriveDetail | null>(null)
  const [parked, setParked] = useState<WebParked | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let live = true
    getDriveDetail(carId, drive.id)
      .then(result => {
        if (live) setDetail(result)
      })
      .catch((reason: unknown) => {
        if (live) setError(reason instanceof ApiError ? reason.message : '读取行程详情失败。')
      })
    if (newerDriveId && /^\d+$/.test(drive.id) && /^\d+$/.test(newerDriveId)) {
      getParkedBetween(carId, drive.id, newerDriveId)
        .then(result => {
          if (live) setParked(result)
        })
        .catch(() => {
          if (live) setParked(null)
        })
    }
    return () => {
      live = false
    }
  }, [carId, drive.id, newerDriveId])

  const current = detail?.drive ?? drive
  const points = detail?.points ?? []
  const elevation = elevationStats(points)
  const efficiency =
    current.energyConsumedKwh != null && current.distanceKm != null && current.distanceKm > 0
      ? (current.energyConsumedKwh / current.distanceKm) * 100
      : null
  const series = (pick: (point: (typeof points)[number]) => number | null, digits = 0) =>
    points.flatMap(point => {
      const value = pick(point)
      return value == null ? [] : [{ x: timeLabel(point.date), y: Math.round(value * 10 ** digits) / 10 ** digits }]
    })
  const speedValues = points.flatMap(point => (point.speedKmh == null ? [] : [point.speedKmh]))
  const elevationValues = points.flatMap(point => (point.elevationM == null ? [] : [point.elevationM]))
  const parkedMinutes = parked?.startDate && parked?.endDate ? Math.round((Date.parse(parked.endDate) - Date.parse(parked.startDate)) / 60000) : null

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div>
        <button className="apple-btn-secondary" onClick={onBack} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
          <span style={{ display: 'inline-flex', transform: 'rotate(180deg)' }}><Icon name="chevron" size={14} /></span>
          返回列表
        </button>
      </div>

      <div className="apple-card">
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
      </div>

      {error ? <div className="apple-card"><p className="text-error">{error}</p></div> : null}

      <Section title="行程" icon="route">
        <Kpi label="距离" value={num(current.distanceKm, 1)} unit="km" icon="route" tone="teal" />
        <Kpi label="时长" value={num(current.durationMinutes)} unit="分钟" icon="clock" tone="blue" />
        <Kpi label="平均速度" value={num(current.speedAvgKmh)} unit="km/h" icon="gauge" tone="teal" />
        <Kpi label="最高速度" value={num(current.speedMaxKmh)} unit="km/h" icon="zap" tone="amber" spark={speedValues.length >= 2 ? speedValues : undefined} />
      </Section>
      <Section title="电量与能耗" icon="battery">
        <BatteryTile from={current.startBatteryLevel} to={current.endBatteryLevel} />
        <Kpi label="净耗电" value={num(current.energyConsumedKwh, 1)} unit="kWh" icon="bolt" tone="teal" />
        <Kpi label="平均能耗" value={num(efficiency, 1)} unit="kWh/100km" icon="chart" tone="blue" />
        <Kpi label="车外平均温度" value={num(current.outsideTempAvgC, 1)} unit="°C" icon="thermo" tone="amber" />
      </Section>
      {elevation ? (
        <Section title="海拔" icon="nav">
          <Kpi label="样本最高" value={num(elevation.maxM)} unit="m" icon="nav" tone="purple" spark={elevationValues.length >= 2 ? elevationValues : undefined} />
          <Kpi label="样本最低" value={num(elevation.minM)} unit="m" icon="nav" tone="purple" />
          <Kpi label="累计爬升" value={elevation.gainM != null ? `+${num(elevation.gainM)}` : '--'} unit="m" icon="chart" tone="purple" />
          <Kpi label="累计下降" value={elevation.lossM != null ? `-${num(elevation.lossM)}` : '--'} unit="m" icon="chart" tone="purple" />
        </Section>
      ) : null}

      {detail && detail.reason === 'quarantined_history' ? (
        <div className="apple-card"><p className="text-secondary" style={{ fontSize: 14 }}>这条记录处于隔离状态，曲线不可用。</p></div>
      ) : detail && points.length ? (
        <>
          <CurveCard title="速度" icon="gauge" tone="blue" unit="km/h" points={series(p => p.speedKmh)} />
          <CurveCard title="功率" icon="bolt" tone="teal" unit="kW" digits={1} points={series(p => p.powerKw, 1)} />
          <CurveCard title="电量" icon="battery" tone="green" unit="%" points={series(p => p.batteryLevel)} />
          <CurveCard title="海拔" icon="nav" tone="purple" unit="m" points={series(p => p.elevationM)} />
        </>
      ) : detail ? (
        <div className="apple-card"><p className="text-secondary" style={{ fontSize: 14 }}>这段行程没有可用的采样曲线。</p></div>
      ) : (
        <Skeleton height={220} />
      )}

      {parked ? (
        <div className="apple-card">
          <h3 style={{ display: 'flex', alignItems: 'center', gap: 10, fontSize: 16, fontWeight: 600, marginBottom: 14 }}>
            <span style={{ width: 34, height: 34, borderRadius: 17, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', background: `${TONES.purple}1f`, color: TONES.purple }}>
              <Icon name="clock" size={18} />
            </span>
            之后驻车
          </h3>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 12 }}>
            <Kpi label="驻车时长" value={num(parkedMinutes)} unit="分钟" icon="clock" tone="purple" />
            {parked.startBatteryLevel != null && parked.endBatteryLevel != null ? (
              <BatteryTile from={parked.startBatteryLevel} to={parked.endBatteryLevel} />
            ) : (
              <Kpi label="电量变化" value={parked.batteryDelta != null ? `${num(parked.batteryDelta, 1)}%` : '--'} icon="battery" tone="teal" />
            )}
            <Kpi label="车外温度" value={num(parked.outsideTempC, 1)} unit="°C" icon="thermo" tone="amber" />
          </div>
          {parked.energyKwh != null ? (
            <>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 12, marginTop: 12 }}>
                <Kpi label="待机能耗" value={num(parked.energyKwh, 2)} unit="kWh" icon="bolt" tone="teal" />
                {parked.averagePowerW != null ? <Kpi label="平均待机功率" value={num(parked.averagePowerW)} unit="W" icon="zap" tone="amber" /> : null}
              </div>
              {parked.averagePowerW != null ? <PowerBar watts={parked.averagePowerW} /> : null}
            </>
          ) : null}
        </div>
      ) : null}
      {parked ? (
        <p className="text-tertiary" style={{ fontSize: 12, lineHeight: 1.6 }}>
          驻车地点：{parked.address ?? '未知地点'}。待机能耗仅在后端能量证据完整时给出估算；车辆休眠状态功耗通常低于 50 W。
        </p>
      ) : null}
    </div>
  )
}

/* ---------------------------- List view ---------------------------- */

export default function DrivesPage() {
  const { cars, carId, setCarId, error: carError } = useCar()
  const [rows, setRows] = useState<WebDriveSummary[]>([])
  const [page, setPage] = useState(1)
  const [totalPages, setTotalPages] = useState(1)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [selected, setSelected] = useState<{ drive: WebDriveSummary; newerDriveId: string | null } | null>(null)

  const loadPage = useCallback(async (targetCar: number, targetPage: number, append: boolean) => {
    setLoading(true)
    setError(null)
    try {
      const result = await getDrivesPage(targetCar, targetPage)
      setRows(previous => (append ? [...previous, ...result.rows] : result.rows))
      setPage(targetPage)
      setTotalPages(result.totalPages)
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : '读取行程记录失败。')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (carId == null) return
    setSelected(null)
    setRows([])
    void loadPage(carId, 1, false)
  }, [carId, loadPage])

  if (selected && carId != null) {
    return <DriveDetailView carId={carId} drive={selected.drive} newerDriveId={selected.newerDriveId} onBack={() => setSelected(null)} />
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12 }}>
        <h1 style={{ fontSize: 28, fontWeight: 700 }}>行程</h1>
        {cars.length > 1 ? (
          <select className="apple-input" style={{ width: 'auto' }} value={carId ?? ''} onChange={event => setCarId(Number(event.target.value))}>
            {cars.map(item => (
              <option key={item.id} value={item.id}>{item.name}</option>
            ))}
          </select>
        ) : null}
      </div>
      {carError ? <div className="apple-card"><p className="text-error">{carError}</p></div> : null}
      {error ? <div className="apple-card"><p className="text-error">{error}</p></div> : null}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        {rows.map((drive, index) => {
          const model = recentDriveModel(drive)
          const newerDriveId = index > 0 ? rows[index - 1].id : null
          return (
            <button
              key={drive.id}
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
                </span>
              </div>
            </button>
          )
        })}
        {!rows.length && !loading ? (
          <div className="apple-card"><p className="text-secondary" style={{ fontSize: 14 }}>暂无行程记录。</p></div>
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
        <button className="apple-btn-secondary" disabled={loading} onClick={() => carId != null && void loadPage(carId, page + 1, true)}>
          加载更多
        </button>
      ) : null}
    </div>
  )
}
