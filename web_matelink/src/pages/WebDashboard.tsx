import { useCallback, useEffect, useState } from 'react'
import { ApiError, getCarStatus, getCars, getRecentDrives } from '../api/matelink'
import type { WebCar, WebCarStatus, WebDriveSummary } from '../api/matelink-types'
import { recentDriveModel } from '../api/recent-drive'
import { NearbySection, PlacesSection } from './DashboardSections'
import { amapCoordinateUrl } from '../api/nearby'

function stateLabel(status: WebCarStatus): string {
  if (status.chargingState && status.chargingState.toLowerCase() === 'charging') return '充电中'
  switch ((status.state ?? '').toLowerCase()) {
    case 'online':
      return '在线'
    case 'asleep':
      return '休眠中'
    case 'driving':
      return '驾驶中'
    case 'charging':
      return '充电中'
    default:
      return status.state ?? '状态未知'
  }
}

function sourceLabel(status: WebCarStatus): string | null {
  if (!status.source) return null
  if (status.source === 'telemetry_cache') return '车辆暂不可达，显示最近一次遥测快照'
  return null
}

function formatObserved(iso: string | null): string {
  if (!iso) return '观测时间未知'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '观测时间未知'
  return `观测于 ${date.toLocaleString('zh-CN', { hour12: false })}`
}

function Metric({ label, value, unit }: { label: string; value: string; unit?: string }) {
  return (
    <div>
      <div className="text-tertiary" style={{ fontSize: 12, marginBottom: 4 }}>{label}</div>
      <div className="tabular" style={{ fontSize: 20, fontWeight: 600 }}>
        {value}
        {unit ? <span className="text-secondary" style={{ fontSize: 13, fontWeight: 500, marginLeft: 4 }}>{unit}</span> : null}
      </div>
    </div>
  )
}

const num = (value: number | null, digits = 0): string => (value == null ? '--' : value.toFixed(digits))

export default function WebDashboard() {
  const [cars, setCars] = useState<WebCar[]>([])
  const [carId, setCarId] = useState<number | null>(null)
  const [status, setStatus] = useState<WebCarStatus | null>(null)
  const [drives, setDrives] = useState<WebDriveSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async (selectedId: number | null, isRefresh: boolean) => {
    if (isRefresh) setRefreshing(true)
    else setLoading(true)
    setError(null)
    try {
      const list = await getCars()
      setCars(list)
      const current = list.find(car => car.id === selectedId) ?? list[0] ?? null
      if (!current) {
        setStatus(null)
        setDrives([])
        setError('当前账号暂无车辆。')
        return
      }
      setCarId(current.id)
      const [nextStatus, nextDrives] = await Promise.all([
        getCarStatus(current.id),
        getRecentDrives(current.id, 5).catch(() => [] as WebDriveSummary[]),
      ])
      setStatus(nextStatus)
      setDrives(nextDrives)
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : '读取车辆数据失败，请稍后重试。')
    } finally {
      setLoading(false)
      setRefreshing(false)
    }
  }, [])

  useEffect(() => {
    void load(null, false)
  }, [load])

  const car = cars.find(item => item.id === carId) ?? null
  const cacheNote = status ? sourceLabel(status) : null

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12 }}>
        <div>
          <h1 style={{ fontSize: 28, fontWeight: 700 }}>{car ? car.name : '仪表盘'}</h1>
          <p className="text-secondary" style={{ fontSize: 13, marginTop: 4 }}>
            {car?.model ?? ''}
            {status ? ` · ${stateLabel(status)} · ${formatObserved(status.observedAt)}` : ''}
          </p>
          {cacheNote ? <p className="text-teal" style={{ fontSize: 13, marginTop: 2 }}>{cacheNote}</p> : null}
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          {cars.length > 1 ? (
            <select
              className="apple-input"
              style={{ width: 'auto' }}
              value={carId ?? ''}
              onChange={event => void load(Number(event.target.value), false)}
            >
              {cars.map(item => (
                <option key={item.id} value={item.id}>{item.name}</option>
              ))}
            </select>
          ) : null}
          <button className="apple-btn-secondary" disabled={refreshing || loading} onClick={() => void load(carId, true)}>
            {refreshing ? '刷新中…' : '刷新'}
          </button>
        </div>
      </div>

      {error ? (
        <div className="apple-card">
          <p className="text-error" style={{ fontSize: 14, lineHeight: 1.6 }}>{error}</p>
          <button className="apple-btn-secondary" style={{ marginTop: 12 }} onClick={() => void load(carId, false)}>重试</button>
        </div>
      ) : null}

      {loading && !status ? <div className="apple-card"><p className="text-secondary">正在读取车辆状态…</p></div> : null}

      {status ? (
        <>
          <div className="apple-card">
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, flexWrap: 'wrap' }}>
              <span className="tabular" style={{ fontSize: 56, fontWeight: 700, lineHeight: 1 }}>{num(status.batteryLevel)}%</span>
              <span className="text-secondary" style={{ fontSize: 15 }}>
                续航 {num(status.ratedRangeKm ?? status.estimatedRangeKm)} km
              </span>
              <span
                style={{
                  background: 'var(--apple-teal-soft)',
                  color: 'var(--apple-teal)',
                  borderRadius: 'var(--apple-radius-pill)',
                  padding: '4px 12px',
                  fontSize: 13,
                  fontWeight: 600,
                }}
              >
                {stateLabel(status)}
              </span>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(130px, 1fr))', gap: 16, marginTop: 20 }}>
              <Metric label="总里程" value={num(status.odometerKm)} unit="km" />
              <Metric label="车内温度" value={num(status.insideTempC, 1)} unit="°C" />
              <Metric label="车外温度" value={num(status.outsideTempC, 1)} unit="°C" />
              <Metric label="车锁" value={status.locked == null ? '--' : status.locked ? '已锁定' : '未锁定'} />
              <Metric label="哨兵模式" value={status.sentryMode == null ? '--' : status.sentryMode ? '开启' : '关闭'} />
              <Metric label="车窗" value={status.windowsOpen == null ? '--' : status.windowsOpen ? '开启' : '关闭'} />
            </div>
            {status.chargingState ? (
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(130px, 1fr))', gap: 16, marginTop: 16, borderTop: '0.5px solid var(--apple-separator)', paddingTop: 16 }}>
                <Metric label="充电状态" value={status.chargingState} />
                <Metric label="已充入" value={num(status.chargeEnergyAddedKwh, 1)} unit="kWh" />
                <Metric label="充电功率" value={num(status.chargerPowerKw, 1)} unit="kW" />
              </div>
            ) : null}
          </div>

          <div className="apple-card">
            <h2 style={{ fontSize: 16, fontWeight: 600, marginBottom: 8 }}>位置</h2>
            {status.geofence ? (
              <p style={{ fontSize: 15 }}>{status.geofence}</p>
            ) : status.latitude != null && status.longitude != null ? (
              <p className="tabular" style={{ fontSize: 15 }}>{status.latitude.toFixed(5)}, {status.longitude.toFixed(5)}</p>
            ) : (
              <p className="text-secondary" style={{ fontSize: 14 }}>暂无位置数据。</p>
            )}
            {status.latitude != null && status.longitude != null ? (
              <p style={{ fontSize: 13, marginTop: 8 }}>
                <a className="text-teal" href={amapCoordinateUrl(status.latitude, status.longitude, car?.name ?? '车辆位置')} target="_blank" rel="noreferrer">
                  在高德地图打开
                </a>
              </p>
            ) : null}
          </div>

          <div className="apple-card">
            <h2 style={{ fontSize: 16, fontWeight: 600, marginBottom: 12 }}>最近行程</h2>
            {drives.length ? (
              <div style={{ display: 'flex', flexDirection: 'column' }}>
                {drives.map(drive => {
                  const model = recentDriveModel(drive)
                  return (
                    <div key={drive.id} style={{ padding: '10px 0', borderTop: '0.5px solid var(--apple-separator)' }}>
                      <div style={{ fontSize: 14, fontWeight: 600 }}>{model.route}</div>
                      <div className="text-secondary tabular" style={{ fontSize: 13, marginTop: 4 }}>
                        {model.distanceText} · {model.durationText} · 电量 {model.socText} · 结束 {model.endText}
                      </div>
                    </div>
                  )
                })}
              </div>
            ) : (
              <p className="text-secondary" style={{ fontSize: 14 }}>暂无行程记录。</p>
            )}
          </div>

          {carId != null ? <NearbySection carId={carId} /> : null}
          {carId != null ? <PlacesSection carId={carId} status={status} /> : null}
        </>
      ) : null}
    </div>
  )
}
