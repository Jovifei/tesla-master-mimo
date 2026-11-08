import { useCallback, useEffect, useState } from 'react'
import { ApiError, getCarStatus, getCars, getRecentDrives } from '../api/matelink'
import type { WebCar, WebCarStatus, WebDriveSummary } from '../api/matelink-types'
import { recentDriveModel } from '../api/recent-drive'
import { NearbySection, PlacesSection } from './DashboardSections'
import { amapCoordinateUrl } from '../api/nearby'
import { BatteryPill, Icon, Kpi, Ring, Skeleton, TONES, type Tone } from '../components/viz'

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

function stateTone(status: WebCarStatus): Tone {
  const label = stateLabel(status)
  if (label === '充电中') return 'blue'
  if (label === '在线') return 'green'
  if (label === '驾驶中') return 'teal'
  return 'gray'
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

const num = (value: number | null, digits = 0): string => (value == null ? '--' : value.toFixed(digits))

const UNKNOWN_PLACE = '未知地点'

function placeLabel(value: string | null): string {
  return value && value.trim() !== '' ? value.trim() : UNKNOWN_PLACE
}

function StateChip({ status }: { status: WebCarStatus }) {
  const tone = stateTone(status)
  const color = TONES[tone]
  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 6,
        background: `${color}1c`,
        color,
        borderRadius: 'var(--apple-radius-pill)',
        padding: '4px 12px',
        fontSize: 13,
        fontWeight: 600,
      }}
    >
      <span style={{ width: 7, height: 7, borderRadius: 4, background: color }} />
      {stateLabel(status)}
    </span>
  )
}

function RouteCard({ drive }: { drive: WebDriveSummary }) {
  const model = recentDriveModel(drive)
  const start = placeLabel(drive.startAddress)
  const end = placeLabel(drive.endAddress)
  const samePlace = start === end
  return (
    <div
      className="hover-lift"
      style={{ background: 'var(--apple-surface-secondary)', borderRadius: 16, padding: '14px 16px', display: 'flex', gap: 14 }}
    >
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', paddingTop: 5 }}>
        <span style={{ width: 9, height: 9, borderRadius: 5, background: TONES.teal, flexShrink: 0 }} />
        {!samePlace ? (
          <>
            <span style={{ flex: 1, width: 2, minHeight: 14, background: 'var(--apple-separator)', margin: '3px 0' }} />
            <span style={{ width: 9, height: 9, borderRadius: 5, border: `2px solid ${TONES.green}`, background: 'var(--apple-surface)', flexShrink: 0 }} />
          </>
        ) : null}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        {samePlace ? (
          <div style={{ fontSize: 14, fontWeight: 600 }}>{start}</div>
        ) : (
          <>
            <div style={{ fontSize: 14, fontWeight: 600 }}>{start}</div>
            <div className="text-secondary" style={{ fontSize: 13.5, marginTop: 3 }}>{end}</div>
          </>
        )}
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', marginTop: 8 }}>
          <BatteryPill from={drive.startBatteryLevel} to={drive.endBatteryLevel} />
          <span className="chip" style={{ padding: '3px 9px', fontSize: 12 }}>
            <Icon name="clock" size={13} />
            {model.durationText}
          </span>
          <span className="text-tertiary tabular" style={{ fontSize: 12.5 }}>结束 {model.endText}</span>
        </div>
      </div>
      <div style={{ textAlign: 'right', flexShrink: 0 }}>
        <div className="tabular" style={{ fontSize: 20, fontWeight: 700, lineHeight: 1.1 }}>
          {drive.distanceKm != null ? drive.distanceKm.toFixed(1) : '--'}
          {drive.distanceKm != null ? <span className="text-secondary" style={{ fontSize: 12, fontWeight: 500, marginLeft: 3 }}>km</span> : null}
        </div>
        <div className="text-tertiary" style={{ fontSize: 11.5, marginTop: 2 }}>距离</div>
      </div>
    </div>
  )
}

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
  const rangeKm = status ? status.ratedRangeKm ?? status.estimatedRangeKm : null
  const charging = Boolean(status?.chargingState)

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
            <Icon name="refresh" size={15} />
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

      {loading && !status ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <Skeleton height={248} />
          <Skeleton height={132} />
          <Skeleton height={180} />
        </div>
      ) : null}

      {status ? (
        <>
          <div className="apple-card">
            <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap', alignItems: 'flex-start' }}>
              <div style={{ flexShrink: 0, display: 'flex', justifyContent: 'center' }}>
                {status.batteryLevel != null ? (
                  <Ring
                    value={status.batteryLevel}
                    label="电量"
                    sub={rangeKm != null ? `续航 ${rangeKm.toFixed(0)} km` : undefined}
                    tone={charging && stateLabel(status) === '充电中' ? 'blue' : 'teal'}
                  />
                ) : (
                  <div
                    style={{
                      width: 168, height: 168, borderRadius: '50%',
                      border: '13px solid var(--apple-surface-tertiary)',
                      display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
                    }}
                  >
                    <span className="tabular text-tertiary" style={{ fontSize: 34, fontWeight: 700 }}>--%</span>
                    <span className="text-secondary" style={{ fontSize: 13, fontWeight: 600, marginTop: 4 }}>电量</span>
                  </div>
                )}
              </div>
              <div style={{ flex: 1, minWidth: 260 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', marginBottom: 14 }}>
                  <span style={{ fontSize: 19, fontWeight: 700 }}>{car?.name ?? '我的特斯拉'}</span>
                  <StateChip status={status} />
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(148px, 1fr))', gap: 10 }}>
                  <Kpi label="总里程" value={num(status.odometerKm)} unit="km" icon="gauge" tone="teal" />
                  <Kpi label="车内温度" value={num(status.insideTempC, 1)} unit="°C" icon="thermo" tone="amber" />
                  <Kpi label="车外温度" value={num(status.outsideTempC, 1)} unit="°C" icon="thermo" tone="blue" />
                  <Kpi
                    label="车锁"
                    value={status.locked == null ? '--' : status.locked ? '已锁定' : '未锁定'}
                    icon={status.locked ? 'lock' : 'unlock'}
                    tone={status.locked ? 'green' : 'amber'}
                  />
                  <Kpi
                    label="哨兵模式"
                    value={status.sentryMode == null ? '--' : status.sentryMode ? '开启' : '关闭'}
                    icon="shield"
                    tone={status.sentryMode ? 'teal' : 'gray'}
                  />
                  <Kpi
                    label="车窗"
                    value={status.windowsOpen == null ? '--' : status.windowsOpen ? '开启' : '关闭'}
                    icon="window"
                    tone={status.windowsOpen ? 'amber' : 'gray'}
                  />
                </div>
                {charging ? (
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(148px, 1fr))', gap: 10, marginTop: 10 }}>
                    <Kpi label="充电状态" value={status.chargingState ?? '--'} icon="plug" tone="blue" />
                    <Kpi label="已充入" value={num(status.chargeEnergyAddedKwh, 1)} unit="kWh" icon="bolt" tone="green" />
                    <Kpi label="充电功率" value={num(status.chargerPowerKw, 1)} unit="kW" icon="zap" tone="blue" />
                  </div>
                ) : null}
              </div>
            </div>
          </div>

          <div className="apple-card">
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: 14, flexWrap: 'wrap' }}>
              <span
                style={{
                  width: 40, height: 40, borderRadius: 20, flexShrink: 0,
                  background: 'var(--apple-teal-soft)', color: 'var(--apple-teal)',
                  display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                }}
              >
                <Icon name="pin" size={21} />
              </span>
              <div style={{ flex: 1, minWidth: 200 }}>
                <h2 style={{ fontSize: 16, fontWeight: 600 }}>位置</h2>
                {status.geofence ? (
                  <p style={{ fontSize: 17, fontWeight: 600, marginTop: 5 }}>{status.geofence}</p>
                ) : status.latitude != null && status.longitude != null ? (
                  <p className="tabular" style={{ fontSize: 17, fontWeight: 600, marginTop: 5 }}>
                    {status.latitude.toFixed(5)}, {status.longitude.toFixed(5)}
                  </p>
                ) : (
                  <p className="text-secondary" style={{ fontSize: 14, marginTop: 5 }}>暂无位置数据。</p>
                )}
                {status.latitude != null && status.longitude != null ? (
                  <p className="text-tertiary tabular" style={{ fontSize: 12, marginTop: 3 }}>
                    {status.geofence ? `${status.latitude.toFixed(5)}, ${status.longitude.toFixed(5)}` : formatObserved(status.observedAt)}
                  </p>
                ) : null}
              </div>
              {status.latitude != null && status.longitude != null ? (
                <a
                  className="apple-btn-secondary"
                  href={amapCoordinateUrl(status.latitude, status.longitude, car?.name ?? '车辆位置')}
                  target="_blank"
                  rel="noreferrer"
                  style={{ textDecoration: 'none' }}
                >
                  <Icon name="nav" size={15} />
                  在高德地图打开
                </a>
              ) : null}
            </div>
          </div>

          <div className="apple-card">
            <h2 style={{ fontSize: 16, fontWeight: 600, marginBottom: 12, display: 'flex', alignItems: 'center', gap: 8 }}>
              <span style={{ color: 'var(--apple-teal)', display: 'inline-flex' }}><Icon name="route" size={18} /></span>
              最近行程
            </h2>
            {drives.length ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                {drives.map(drive => (
                  <RouteCard key={drive.id} drive={drive} />
                ))}
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
