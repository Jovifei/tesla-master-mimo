import { useCallback, useEffect, useState } from 'react'
import { ApiError, getDriveDetail, getDrivesPage, getParkedBetween, type DriveDetail } from '../api/matelink'
import type { WebDriveSummary, WebParked } from '../api/matelink-types'
import { recentDriveModel } from '../api/recent-drive'
import { elevationStats } from '../api/drive-elevation'
import MiniChart from '../components/MiniChart'
import { useCar } from './useCar'

const num = (value: number | null, digits = 0): string => (value == null ? '--' : value.toFixed(digits))

function formatTime(iso: string | null): string {
  if (!iso) return '--'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '--' : date.toLocaleString('zh-CN', { hour12: false })
}

function Group({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="apple-card">
      <h3 style={{ fontSize: 15, fontWeight: 600, marginBottom: 12 }}>{title}</h3>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(120px, 1fr))', gap: 16 }}>{children}</div>
    </div>
  )
}

function Metric({ label, value, unit }: { label: string; value: string; unit?: string }) {
  return (
    <div>
      <div className="text-tertiary" style={{ fontSize: 12, marginBottom: 4 }}>{label}</div>
      <div className="tabular" style={{ fontSize: 17, fontWeight: 600 }}>
        {value}
        {unit ? <span className="text-secondary" style={{ fontSize: 12, fontWeight: 500, marginLeft: 4 }}>{unit}</span> : null}
      </div>
    </div>
  )
}

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
  const chart = (pick: (point: (typeof points)[number]) => number | null) => points.map(point => ({ date: point.date, value: pick(point) }))
  const parkedMinutes = parked?.startDate && parked?.endDate ? Math.round((Date.parse(parked.endDate) - Date.parse(parked.startDate)) / 60000) : null

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div>
        <button className="apple-btn-secondary" onClick={onBack}>← 返回列表</button>
        <h1 style={{ fontSize: 22, fontWeight: 700, marginTop: 12 }}>
          {(current.startAddress ?? '未知地点')} → {(current.endAddress ?? '未知地点')}
        </h1>
        <p className="text-secondary" style={{ fontSize: 13, marginTop: 4 }}>
          {formatTime(current.startDate)} → {formatTime(current.endDate)}
        </p>
      </div>
      {error ? <div className="apple-card"><p className="text-error">{error}</p></div> : null}

      <Group title="行程">
        <Metric label="距离" value={num(current.distanceKm, 1)} unit="km" />
        <Metric label="时长" value={num(current.durationMinutes)} unit="分钟" />
        <Metric label="平均速度" value={num(current.speedAvgKmh)} unit="km/h" />
        <Metric label="最高速度" value={num(current.speedMaxKmh)} unit="km/h" />
      </Group>
      <Group title="电量与能耗">
        <Metric label="电量变化" value={current.startBatteryLevel != null && current.endBatteryLevel != null ? `${num(current.startBatteryLevel)}% → ${num(current.endBatteryLevel)}%` : '--'} />
        <Metric label="净耗电" value={num(current.energyConsumedKwh, 1)} unit="kWh" />
        <Metric label="平均能耗" value={num(efficiency, 1)} unit="kWh/100km" />
        <Metric label="车外平均温度" value={num(current.outsideTempAvgC, 1)} unit="°C" />
      </Group>
      {elevation ? (
        <Group title="海拔">
          <Metric label="样本最高" value={num(elevation.maxM)} unit="m" />
          <Metric label="样本最低" value={num(elevation.minM)} unit="m" />
          <Metric label="累计爬升" value={elevation.gainM != null ? `+${num(elevation.gainM)}` : '--'} unit="m" />
          <Metric label="累计下降" value={elevation.lossM != null ? `-${num(elevation.lossM)}` : '--'} unit="m" />
        </Group>
      ) : null}

      {detail && detail.reason === 'quarantined_history' ? (
        <div className="apple-card"><p className="text-secondary" style={{ fontSize: 14 }}>这条记录处于隔离状态，曲线不可用。</p></div>
      ) : detail && points.length ? (
        <div className="apple-card" style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
          <div><h3 style={{ fontSize: 15, fontWeight: 600, marginBottom: 8 }}>速度</h3><MiniChart points={chart(p => p.speedKmh)} unit="km/h" /></div>
          <div><h3 style={{ fontSize: 15, fontWeight: 600, marginBottom: 8 }}>功率</h3><MiniChart points={chart(p => p.powerKw)} unit="kW" digits={1} /></div>
          <div><h3 style={{ fontSize: 15, fontWeight: 600, marginBottom: 8 }}>电量</h3><MiniChart points={chart(p => p.batteryLevel)} unit="%" /></div>
          <div><h3 style={{ fontSize: 15, fontWeight: 600, marginBottom: 8 }}>海拔</h3><MiniChart points={chart(p => p.elevationM)} unit="m" /></div>
        </div>
      ) : detail ? (
        <div className="apple-card"><p className="text-secondary" style={{ fontSize: 14 }}>这段行程没有可用的采样曲线。</p></div>
      ) : (
        <div className="apple-card"><p className="text-secondary">正在读取曲线…</p></div>
      )}

      {parked ? (
        <Group title="之后驻车">
          <Metric label="驻车时长" value={num(parkedMinutes)} unit="分钟" />
          <Metric label="电量变化" value={parked.batteryDelta != null ? `${num(parked.batteryDelta, 1)}%` : '--'} />
          <Metric label="待机能耗" value={num(parked.energyKwh, 2)} unit="kWh" />
          <Metric label="平均待机功率" value={num(parked.averagePowerW)} unit="W" />
          <Metric label="车外温度" value={num(parked.outsideTempC, 1)} unit="°C" />
        </Group>
      ) : null}
      {parked ? (
        <p className="text-tertiary" style={{ fontSize: 12, lineHeight: 1.6 }}>
          驻车地点：{parked.address ?? '未知地点'}。待机能耗仅在后端能量证据完整时给出估算；车辆休眠状态功耗通常低于 50 W。
        </p>
      ) : null}
    </div>
  )
}

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

      <div className="apple-card" style={{ padding: 0, overflow: 'hidden' }}>
        {rows.map((drive, index) => {
          const model = recentDriveModel(drive)
          const newerDriveId = index > 0 ? rows[index - 1].id : null
          return (
            <button
              key={drive.id}
              onClick={() => setSelected({ drive, newerDriveId })}
              style={{ display: 'block', width: '100%', textAlign: 'left', background: 'none', border: 'none', borderTop: '0.5px solid var(--apple-separator)', padding: '14px 16px', cursor: 'pointer', color: 'inherit' }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
                <span style={{ fontSize: 14, fontWeight: 600 }}>{model.route}</span>
                <span className="tabular" style={{ fontSize: 14, fontWeight: 600 }}>{model.distanceText}</span>
              </div>
              <div className="text-secondary tabular" style={{ fontSize: 13, marginTop: 4 }}>
                {model.durationText} · 电量 {model.socText} · 结束 {model.endText}
                {drive.speedMaxKmh != null ? ` · 最高 ${num(drive.speedMaxKmh)} km/h` : ''}
              </div>
            </button>
          )
        })}
        {!rows.length && !loading ? <p className="text-secondary" style={{ fontSize: 14, padding: 16 }}>暂无行程记录。</p> : null}
        {loading ? <p className="text-secondary" style={{ fontSize: 14, padding: 16 }}>加载中…</p> : null}
      </div>

      {page < totalPages ? (
        <button className="apple-btn-secondary" disabled={loading} onClick={() => carId != null && void loadPage(carId, page + 1, true)}>
          加载更多
        </button>
      ) : null}
    </div>
  )
}
