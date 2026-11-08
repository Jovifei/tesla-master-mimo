import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { getCarStatus, getNearbyChargingSites, getParkedBetween, getRecentDrives, type NearbyCharging } from '../api/matelink'
import type { WebCarStatus, WebDriveSummary, WebParked } from '../api/matelink-types'
import { amapMarkerUrl } from '../api/nearby'
import { Icon, Skeleton } from '../components/viz'
import { AnomalyBand, ChargeRuler, Panel, PremiseStrip, V3 } from '../components/v3'
import { useCar } from './useCar'

/**
 * Dashboard v3 — the triage desk (docs/WEB-DESIGN-V3.md §3.1).
 * Left rail = conclusions (sticky), right zone = evidence. The page
 * answers four questions in ten seconds: is the charge enough, is the
 * data fresh, is the car secure, where is it. Everything else was
 * demoted or moved by the panel's parameter triage.
 */

function fmt(value: number | null | undefined, digits = 0): string {
  if (value == null || !Number.isFinite(value)) return '--'
  return value.toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits })
}

function stateLabel(state: string | null): string {
  switch (state) {
    case 'online': return '在线'
    case 'asleep': return '休眠'
    case 'driving': return '驾驶中'
    case 'charging': return '充电中'
    default: return state ?? ''
  }
}

function StatusLine({ icon, label, value, abnormal }: { icon: 'lock' | 'unlock' | 'eye' | 'window' | 'pin'; label: string; value: ReactNode; abnormal?: boolean }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 9, height: 36 }}>
      <span style={{ display: 'inline-flex', color: abnormal ? V3.errorText : V3.label3 }}><Icon name={icon} size={16} /></span>
      <span style={{ fontSize: 14, color: V3.label2 }}>{label}</span>
      <span style={{ marginLeft: 'auto', fontSize: 14, fontWeight: abnormal ? 600 : 500, color: abnormal ? V3.errorText : V3.label }}>{value}</span>
    </div>
  )
}

export default function WebDashboard() {
  const { cars, car, carId, setCarId, error: carError } = useCar()
  const [status, setStatus] = useState<WebCarStatus | null>(null)
  const [statusError, setStatusError] = useState(false)
  const [drives, setDrives] = useState<WebDriveSummary[] | null>(null)
  const [nearby, setNearby] = useState<NearbyCharging | null>(null)
  const [parked, setParked] = useState<WebParked | null | undefined>(undefined)

  const loadAll = useCallback(async (id: number) => {
    setStatusError(false)
    try {
      setStatus(await getCarStatus(id))
    } catch {
      setStatus(null)
      setStatusError(true)
    }
    try {
      setDrives(await getRecentDrives(id, 5))
    } catch {
      setDrives([])
    }
    try {
      setNearby(await getNearbyChargingSites(id))
    } catch {
      setNearby({ sites: [], source: null })
    }
  }, [])

  useEffect(() => {
    if (carId != null) void loadAll(carId)
  }, [carId, loadAll])

  useEffect(() => {
    setParked(undefined)
    if (carId == null || !drives || drives.length < 2) return
    const newer = drives[0]
    const older = drives[1]
    if (!/^[1-9]\d*$/.test(newer.id) || !/^[1-9]\d*$/.test(older.id)) {
      setParked(null)
      return
    }
    let cancelled = false
    getParkedBetween(carId, older.id, newer.id)
      .then(result => { if (!cancelled) setParked(result) })
      .catch(() => { if (!cancelled) setParked(null) })
    return () => { cancelled = true }
  }, [carId, drives])

  const snapshot = status?.source === 'telemetry_cache'
  const charging = status?.chargingState === 'charging'
  const unlocked = status?.locked === false
  const windowsOpen = status?.windowsOpen === true
  const anomaly = unlocked || windowsOpen

  return (
    <div>
      <div style={{ position: 'sticky', top: 56, zIndex: 30 }}>
        <PremiseStrip source={status?.source ?? null} observedAt={status?.observedAt ?? null} onRefresh={() => { if (carId != null) void loadAll(carId) }} />
      </div>

      <div style={{ maxWidth: 1400, margin: '0 auto', padding: '20px 32px 44px' }}>
        {carError ? (
          <Panel><p style={{ color: V3.errorText, fontSize: 15 }}>{carError}</p></Panel>
        ) : null}

        {anomaly ? (
          <div style={{ marginBottom: 16 }}>
            <AnomalyBand
              title={unlocked ? '车辆未锁定' : '车窗未关闭'}
              detail={[unlocked && windowsOpen ? '车窗同时未关闭。' : null, '请确认车辆停放环境安全。'].filter(Boolean).join('')}
            />
          </div>
        ) : null}

        <div style={{ display: 'grid', gridTemplateColumns: '360px minmax(0, 1fr)', gap: 16, alignItems: 'start' }}>
          {/* ------------------------- Conclusion rail ------------------------- */}
          <div style={{ position: 'sticky', top: 104, display: 'flex', flexDirection: 'column', gap: 16 }}>
            {cars.length > 1 ? (
              <select
                className="apple-input"
                value={carId ?? ''}
                onChange={event => setCarId(Number(event.target.value))}
                aria-label="切换车辆"
              >
                {cars.map(item => (
                  <option key={item.id} value={item.id}>{item.name}</option>
                ))}
              </select>
            ) : null}

            {/* Hero: SOC + estimated range + the Charge Ruler */}
            <section style={{ background: V3.surface, borderRadius: 16, padding: 24, boxShadow: '0 1px 3px rgba(0,0,0,0.05)', border: `0.5px solid ${V3.separator}` }}>
              {statusError ? (
                <>
                  <p style={{ fontSize: 15, fontWeight: 600, marginBottom: 6 }}>读取车辆状态失败</p>
                  <p style={{ fontSize: 13.5, color: V3.label2, lineHeight: 1.6, marginBottom: 12 }}>网络或服务暂时不可用，你可以重试。</p>
                  <button className="apple-btn-primary" onClick={() => { if (carId != null) void loadAll(carId) }}>重试</button>
                </>
              ) : !status ? (
                <Skeleton height={180} radius={12} />
              ) : (
                <>
                  <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between' }}>
                    <span style={{ fontSize: 13, fontWeight: 500, color: V3.label2 }}>{car?.name ?? '我的特斯拉'}</span>
                    <span style={{ fontSize: 12.5, color: V3.label3 }}>
                      {stateLabel(status.state)}{snapshot ? ' · 快照' : ''}
                    </span>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'baseline', gap: 5, marginTop: 10 }}>
                    <span className="tabular" style={{ fontSize: 56, fontWeight: 600, lineHeight: '60px', color: V3.label }}>
                      {status.batteryLevel != null ? Math.round(status.batteryLevel) : '--'}
                    </span>
                    <span style={{ fontSize: 20, fontWeight: 500, color: V3.label2 }}>%</span>
                    <span style={{ marginLeft: 10, fontSize: 13, color: V3.label3 }}>当前电量</span>
                  </div>
                  <div style={{ position: 'relative', marginTop: 18 }}>
                    {status.estimatedRangeKm != null && status.batteryLevel != null ? (
                      <span
                        className="tabular"
                        style={{
                          position: 'absolute', bottom: 40,
                          left: `${Math.max(0, Math.min(100, status.batteryLevel))}%`,
                          transform: status.batteryLevel >= 22 ? 'translateX(-100%)' : 'none',
                          fontSize: 13, color: V3.label2, whiteSpace: 'nowrap',
                        }}
                      >
                        续航约 <span style={{ fontSize: 15, fontWeight: 600, color: V3.label }}>{fmt(status.estimatedRangeKm)}</span> km
                      </span>
                    ) : null}
                    {status.batteryLevel != null ? (
                      <ChargeRuler soc={status.batteryLevel} snapshot={snapshot} />
                    ) : (
                      <p style={{ fontSize: 13, color: V3.label3 }}>暂无电量数据。</p>
                    )}
                  </div>
                </>
              )}
            </section>

            {/* Security + location conclusions */}
            {status ? (
              <section style={{ background: V3.surface, borderRadius: 16, padding: '8px 16px', boxShadow: '0 1px 3px rgba(0,0,0,0.05)', border: `0.5px solid ${V3.separator}` }}>
                <StatusLine icon={status.locked ? 'lock' : 'unlock'} label="车锁" value={status.locked == null ? '未知' : status.locked ? '已锁定' : '未锁定'} abnormal={unlocked} />
                <div style={{ borderTop: `0.5px solid ${V3.separator}` }}>
                  <StatusLine icon="eye" label="哨兵模式" value={status.sentryMode == null ? '未知' : status.sentryMode ? '已开启' : '未开启'} />
                </div>
                <div style={{ borderTop: `0.5px solid ${V3.separator}` }}>
                  <StatusLine icon="window" label="车窗" value={status.windowsOpen == null ? '未知' : status.windowsOpen ? '未关闭' : '已关闭'} abnormal={windowsOpen} />
                </div>
                <div style={{ borderTop: `0.5px solid ${V3.separator}` }}>
                  <StatusLine
                    icon="pin"
                    label="位置"
                    value={
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 10 }}>
                        <span>{status.geofence ?? (status.latitude != null ? '已定位' : '未知')}</span>
                        {status.latitude != null && status.longitude != null ? (
                          <a
                            href={`https://uri.amap.com/marker?position=${status.longitude},${status.latitude}&name=${encodeURIComponent(car?.name ?? '车辆位置')}&coordinate=gaode`}
                            target="_blank"
                            rel="noreferrer"
                            style={{ display: 'inline-flex', alignItems: 'center', gap: 3, color: V3.tint, textDecoration: 'none', fontSize: 13, fontWeight: 600 }}
                          >
                            高德打开 <Icon name="external" size={13} />
                          </a>
                        ) : null}
                      </span>
                    }
                  />
                </div>
              </section>
            ) : null}

            {/* Charging block — exists only while charging */}
            {status && charging ? (
              <section style={{ background: V3.surface, borderRadius: 16, padding: 16, boxShadow: '0 1px 3px rgba(0,0,0,0.05)', border: `0.5px solid ${V3.separator}` }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 7, height: 24, marginBottom: 10 }}>
                  <span style={{ display: 'inline-flex', color: V3.teal }}><Icon name="bolt" size={17} /></span>
                  <h2 style={{ fontSize: 17, fontWeight: 600 }}>正在充电</h2>
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                  <div>
                    <div style={{ fontSize: 12.5, color: V3.label3, marginBottom: 3 }}>当前功率</div>
                    <div className="tabular" style={{ fontSize: 28, fontWeight: 600, color: V3.teal }}>
                      {fmt(status.chargerPowerKw, 1)}<span style={{ fontSize: 13, fontWeight: 500, color: V3.label2, marginLeft: 3 }}>kW</span>
                    </div>
                  </div>
                  <div>
                    <div style={{ fontSize: 12.5, color: V3.label3, marginBottom: 3 }}>已充入</div>
                    <div className="tabular" style={{ fontSize: 28, fontWeight: 600 }}>
                      {fmt(status.chargeEnergyAddedKwh, 1)}<span style={{ fontSize: 13, fontWeight: 500, color: V3.label2, marginLeft: 3 }}>kWh</span>
                    </div>
                  </div>
                </div>
              </section>
            ) : null}

            {/* Spec lines — P2 parameters live here, small and factual */}
            {status ? (
              <div style={{ padding: '2px 6px', fontSize: 12.5, color: V3.label3, lineHeight: 2 }}>
                <div>总里程 {fmt(status.odometerKm)} km</div>
                <div>车内 {fmt(status.insideTempC, 1)} °C · 车外 {fmt(status.outsideTempC, 1)} °C</div>
                {status.ratedRangeKm != null ? <div>额定续航 {fmt(status.ratedRangeKm)} km（额定口径，仅供参考）</div> : null}
              </div>
            ) : null}
          </div>

          {/* --------------------------- Evidence zone --------------------------- */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16, minWidth: 0 }}>
            <Panel
              title="最近行程"
              action={<Link to="/drives" style={{ color: V3.tint, fontSize: 13.5, fontWeight: 600, textDecoration: 'none' }}>查看全部</Link>}
            >
              {drives == null ? (
                <Skeleton height={120} radius={12} />
              ) : !drives.length ? (
                <p style={{ fontSize: 14, color: V3.label3 }}>暂无行程记录。</p>
              ) : (
                drives.slice(0, 3).map((drive, index) => (
                  <Link key={drive.id} to="/drives" style={{ display: 'block', textDecoration: 'none', color: 'inherit', padding: '10px 0', borderTop: index === 0 ? 'none' : `0.5px solid ${V3.separator}` }}>
                    <div style={{ display: 'flex', alignItems: 'baseline', gap: 12 }}>
                      <span style={{ fontSize: 15, fontWeight: 500, flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {drive.startAddress ?? '未知地点'} <span style={{ color: V3.label3 }}>→</span> {drive.endAddress ?? '未知地点'}
                      </span>
                      <span className="tabular" style={{ fontSize: 15, fontWeight: 600, color: V3.graphite }}>{fmt(drive.distanceKm, 1)} km</span>
                    </div>
                    <div style={{ fontSize: 13, color: V3.label3, marginTop: 4 }}>
                      {drive.durationMinutes != null ? `${Math.round(drive.durationMinutes)} 分钟` : ''}
                      {drive.startBatteryLevel != null && drive.endBatteryLevel != null ? ` · 电量 ${Math.round(drive.startBatteryLevel)}% → ${Math.round(drive.endBatteryLevel)}%` : ''}
                      {drive.energyConsumedKwh != null ? ` · 耗电 ${fmt(drive.energyConsumedKwh, 1)} kWh` : ''}
                    </div>
                  </Link>
                ))
              )}
            </Panel>

            <Panel title="附近充电">
              {nearby == null ? (
                <Skeleton height={120} radius={12} />
              ) : !nearby.sites.length ? (
                <p style={{ fontSize: 14, color: V3.label3 }}>附近暂无充电站数据。</p>
              ) : (
                <>
                  {nearby.sites.slice(0, 5).map((site, index) => {
                    const url = amapMarkerUrl(site)
                    return (
                      <div key={index} style={{ padding: '10px 0', borderTop: index === 0 ? 'none' : `0.5px solid ${V3.separator}` }}>
                        <div style={{ display: 'flex', alignItems: 'baseline', gap: 12 }}>
                          <span style={{ fontSize: 15, fontWeight: 500, flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', color: site.siteClosed ? V3.label3 : V3.label }}>
                            {site.name ?? '充电站'}
                          </span>
                          {site.siteClosed ? (
                            <span style={{ fontSize: 12.5, fontWeight: 600, color: V3.errorText }}>已关闭</span>
                          ) : null}
                          <span className="tabular" style={{ fontSize: 15, fontWeight: 600, color: V3.graphite }}>{fmt(site.distanceKm, 1)} km</span>
                          {url ? (
                            <a href={url} target="_blank" rel="noreferrer" style={{ color: V3.tint, fontSize: 13.5, fontWeight: 600, textDecoration: 'none' }}>导航</a>
                          ) : null}
                        </div>
                        <div style={{ fontSize: 13, color: V3.label3, marginTop: 4 }}>
                          {site.availableStalls != null && site.totalStalls != null ? `空闲 ${site.availableStalls}/${site.totalStalls} 桩` : ''}
                          {site.priceText ? ` · ${site.priceText}` : ''}
                          {site.parkingPolicy ? ` · ${site.parkingPolicy}` : ''}
                        </div>
                      </div>
                    )
                  })}
                  {nearby.source ? <div style={{ fontSize: 12, color: V3.label3, marginTop: 8 }}>数据来源：{nearby.source}</div> : null}
                </>
              )}
            </Panel>

            {/* Parked-energy row: the number disappears, never the row */}
            {drives != null && drives.length >= 2 ? (
              parked === undefined ? (
                <Skeleton height={40} radius={12} />
              ) : parked && parked.energyKwh != null ? (
                <section style={{ background: V3.surface, borderRadius: 16, padding: '0 16px', height: 52, display: 'flex', alignItems: 'center', gap: 10, boxShadow: '0 1px 3px rgba(0,0,0,0.05)', border: `0.5px solid ${V3.separator}` }}>
                  <span style={{ display: 'inline-flex', color: V3.teal }}><Icon name="moon" size={16} /></span>
                  <span style={{ fontSize: 14.5, fontWeight: 500 }}>驻车耗电</span>
                  <span className="tabular" style={{ fontSize: 15, fontWeight: 600 }}>
                    {fmt(parked.energyKwh, 2)} kWh
                    {parked.averagePowerW != null ? <span style={{ color: V3.label2, fontWeight: 500 }}> · 平均待机 {fmt(parked.averagePowerW, 0)} W</span> : null}
                  </span>
                  <span style={{ marginLeft: 'auto', fontSize: 13, color: V3.label3 }}>
                    {parked.startDate && parked.endDate
                      ? `驻车 ${Math.floor((new Date(parked.endDate).getTime() - new Date(parked.startDate).getTime()) / 3600000)} 小时 ${Math.round(((new Date(parked.endDate).getTime() - new Date(parked.startDate).getTime()) / 60000) % 60)} 分`
                      : ''}
                  </span>
                </section>
              ) : (
                <div style={{ height: 36, display: 'flex', alignItems: 'center', gap: 8, padding: '0 12px', background: V3.surface2, borderRadius: 12, fontSize: 13, color: V3.label2 }}>
                  <Icon name="info" size={14} />
                  驻车耗电：本次驻车的证据条件不完整，未显示数值。
                </div>
              )
            ) : drives != null ? (
              <div style={{ height: 36, display: 'flex', alignItems: 'center', gap: 8, padding: '0 12px', background: V3.surface2, borderRadius: 12, fontSize: 13, color: V3.label2 }}>
                <Icon name="info" size={14} />
                驻车耗电：尚无相邻行程可供计算。
              </div>
            ) : null}
          </div>
        </div>
      </div>
    </div>
  )
}
