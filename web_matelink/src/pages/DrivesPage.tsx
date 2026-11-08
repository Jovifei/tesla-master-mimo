import { useCallback, useEffect, useMemo, useState } from 'react'
import { ApiError, getDriveDetail, getDrivesPage, getParkedBetween, type DriveDetail } from '../api/matelink'
import type { WebDriveSummary, WebParked } from '../api/matelink-types'
import { recentDriveModel } from '../api/recent-drive'
import { elevationStats } from '../api/drive-elevation'
import { BatteryPill, Icon, Skeleton, TONES, type IconName, type Tone } from '../components/viz'
import { Panel, V3 } from '../components/v3'
import { DATA_COLORS, StackChart, StatTile, fmtDuration, fmtNum, type ChartRow, type StackSeriesDef } from '../components/panels'
import { useCar } from './useCar'

/**
 * Drives v4 — TeslaMate composition: the detail view leads with a route
 * panel and a big-number tile row, then one stacked chart group
 * （功率/速度/电量/海拔 sharing the time axis, no dual axes) built from
 * the drive's real samples. The list stays the supporting table.
 */

const num = (value: number | null, digits = 0): string => (value == null ? '--' : value.toFixed(digits))

function formatTime(iso: string | null): string {
  if (!iso) return '--'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '--' : date.toLocaleString('zh-CN', { hour12: false })
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

/* ---------------------------- Chips ----------------------------- */

function Chip({ icon, tone, text }: { icon: IconName; tone: Tone; text: string }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, background: 'var(--apple-surface-tertiary)', borderRadius: 999, padding: '4px 10px', fontSize: 12, fontWeight: 500 }}>
      <span style={{ color: TONES[tone], display: 'inline-flex' }}><Icon name={icon} size={13} /></span>
      <span className="tabular">{text}</span>
    </span>
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

/** BatteryPill hosted in a StatTile-shaped cell. */
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
  const points = useMemo(() => detail?.points ?? [], [detail])
  const elevation = elevationStats(points)
  const efficiency =
    current.energyConsumedKwh != null && current.distanceKm != null && current.distanceKm > 0
      ? (current.energyConsumedKwh / current.distanceKm) * 100
      : null
  const parkedMinutes = parked?.startDate && parked?.endDate ? Math.round((Date.parse(parked.endDate) - Date.parse(parked.startDate)) / 60000) : null

  const rows: ChartRow[] = useMemo(
    () =>
      points.map(point => ({
        t: Date.parse(point.date),
        power: point.powerKw,
        speed: point.speedKmh,
        soc: point.batteryLevel,
        elev: point.elevationM,
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
    { key: 'speed', title: '速度', unit: 'km/h', color: DATA_COLORS.graphite, height: 108, domain: [0, 'auto'], emptyReason: insufficient('speed', '速度') },
    { key: 'soc', title: '电量', unit: '%', color: DATA_COLORS.tealDeep, height: 70, domain: [0, 100], emptyReason: insufficient('soc', '电量') },
    { key: 'elev', title: '海拔', unit: 'm', color: DATA_COLORS.graphiteLight, height: 80, emptyReason: insufficient('elev', '海拔') },
  ]

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div>
        <button className="apple-btn-secondary" onClick={onBack} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
          <span style={{ display: 'inline-flex', transform: 'rotate(180deg)' }}><Icon name="chevron" size={14} /></span>
          返回列表
        </button>
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
        <p style={{ fontSize: 12, color: V3.label3, marginTop: 10, lineHeight: 1.6 }}>
          本次行程记录未包含可绘制的路线轨迹坐标，路线以起终点文字呈现。
        </p>
      </Panel>

      {error ? <Panel><p style={{ color: V3.errorText, fontSize: 14.5 }}>{error}</p></Panel> : null}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12 }}>
        <StatTile label="距离" value={num(current.distanceKm, 1)} unit="km" />
        <StatTile label="时长" value={fmtDuration(current.durationMinutes)} />
        <StatTile label="耗电量" value={num(current.energyConsumedKwh, 1)} unit="kWh" accent={V3.teal} />
        <StatTile label="平均能耗" value={efficiency != null ? fmtNum(efficiency, 1) : '--'} unit="kWh/100km" />
        <StatTile label="平均速度" value={num(current.speedAvgKmh)} unit="km/h" />
        <StatTile label="最高速度" value={num(current.speedMaxKmh)} unit="km/h" caption="本段行程记录值" />
        <BatteryTile from={current.startBatteryLevel} to={current.endBatteryLevel} />
        <StatTile label="车外平均温度" value={num(current.outsideTempAvgC, 1)} unit="°C" />
      </div>

      {detail && detail.reason === 'quarantined_history' ? (
        <Panel><p style={{ fontSize: 14, color: V3.label2 }}>这条记录处于隔离状态，曲线不可用。</p></Panel>
      ) : detail && points.length ? (
        <Panel title="采样曲线">
          <StackChart data={rows} series={series} syncKey={`drive-${drive.id}`} />
          {elevation ? (
            <p className="tabular" style={{ fontSize: 12.5, color: V3.label3, marginTop: 10, fontVariantNumeric: 'tabular-nums' }}>
              海拔采样：最高 {fmtNum(elevation.maxM)} m · 最低 {fmtNum(elevation.minM)} m
              {elevation.gainM != null ? ` · 累计爬升 +${fmtNum(elevation.gainM)} m` : ''}
              {elevation.lossM != null ? ` · 累计下降 −${fmtNum(elevation.lossM)} m` : ''}
            </p>
          ) : null}
        </Panel>
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
            驻车地点：{parked.address ?? '未知地点'}。待机能耗仅在后端能量证据完整时给出估算；车辆休眠状态功耗通常低于 50 W。
          </p>
        </Panel>
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
      {carError ? <Panel><p style={{ color: V3.errorText, fontSize: 14.5 }}>{carError}</p></Panel> : null}
      {error ? <Panel><p style={{ color: V3.errorText, fontSize: 14.5 }}>{error}</p></Panel> : null}

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
        <button className="apple-btn-secondary" disabled={loading} onClick={() => carId != null && void loadPage(carId, page + 1, true)}>
          加载更多
        </button>
      ) : null}
    </div>
  )
}
