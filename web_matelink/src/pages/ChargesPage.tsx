import { useCallback, useEffect, useMemo, useState } from 'react'
import { ApiError, getCarStatus, getChargeDetail, getChargesPage, type ChargeDetail } from '../api/matelink'
import type { WebCarStatus, WebCharge } from '../api/matelink-types'
import { EMPTY_CHARGE_FILTER, chargeAddresses, filterCharges, type ChargeFilter } from '../api/charge-filter'
import { AreaSeries, Bars, BatteryPill, Icon, Kpi, Ring, SegControl, Skeleton, TONES, type IconName } from '../components/viz'
import { useCar } from './useCar'

const num = (value: number | null, digits = 0): string => (value == null ? '--' : value.toFixed(digits))

function formatTime(iso: string | null): string {
  if (!iso) return '--'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '--' : date.toLocaleString('zh-CN', { hour12: false })
}

function formatClock(iso: string): string {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
}

/** Charged kWh per calendar month over the loaded rows, last 6 months, chronological. */
function monthlyEnergy(rows: WebCharge[]): { label: string; value: number }[] {
  const byMonth = new Map<string, { order: number; label: string; value: number }>()
  for (const row of rows) {
    if (!row.startDate) continue
    const date = new Date(row.startDate)
    if (Number.isNaN(date.getTime())) continue
    const key = `${date.getFullYear()}-${date.getMonth()}`
    const entry = byMonth.get(key) ?? { order: date.getFullYear() * 12 + date.getMonth(), label: `${date.getMonth() + 1}月`, value: 0 }
    entry.value += row.energyAddedKwh ?? 0
    byMonth.set(key, entry)
  }
  return [...byMonth.values()]
    .sort((a, b) => a.order - b.order)
    .slice(-6)
    .map(entry => ({ label: entry.label, value: Math.round(entry.value * 10) / 10 }))
}

function modeVisual(mode: WebCharge['chargeMode']): { icon: IconName; color: string } {
  if (mode === 'dc') return { icon: 'zap', color: TONES.blue }
  if (mode === 'ac') return { icon: 'plug', color: TONES.teal }
  return { icon: 'battery', color: TONES.gray }
}

function ChargeDetailView({ carId, charge, onBack }: { carId: number; charge: WebCharge; onBack: () => void }) {
  const [detail, setDetail] = useState<ChargeDetail | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let live = true
    getChargeDetail(carId, charge.id)
      .then(result => {
        if (live) setDetail(result)
      })
      .catch((reason: unknown) => {
        if (live) setError(reason instanceof ApiError ? reason.message : '读取充电详情失败。')
      })
    return () => {
      live = false
    }
  }, [carId, charge.id])

  const current = detail?.charge ?? charge
  const summary = detail?.summary
  const points = detail?.points ?? []

  const curves: { title: string; unit: string; digits: number; tone: 'teal' | 'green' | 'blue' | 'purple' | 'amber' | 'red'; series: { x: string; y: number }[] }[] = [
    { title: '充电电量变化', unit: '%', digits: 0, tone: 'teal', series: [] },
    { title: '充电功率', unit: 'kW', digits: 1, tone: 'blue', series: [] },
    { title: '充电电压', unit: 'V', digits: 0, tone: 'purple', series: [] },
    { title: '充电电流', unit: 'A', digits: 1, tone: 'amber', series: [] },
    { title: '充电温度', unit: '°C', digits: 1, tone: 'red', series: [] },
  ]
  const picks = [
    (point: (typeof points)[number]) => point.batteryLevel,
    (point: (typeof points)[number]) => point.powerKw,
    (point: (typeof points)[number]) => point.voltageV,
    (point: (typeof points)[number]) => point.currentA,
    (point: (typeof points)[number]) => point.temperatureC,
  ]
  curves.forEach((curve, index) => {
    curve.series = points.flatMap(point => {
      const value = picks[index](point)
      return value == null ? [] : [{ x: formatClock(point.date), y: value }]
    })
  })

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div>
        <button className="apple-btn-secondary" onClick={onBack} style={{ display: 'inline-flex', alignItems: 'center', gap: 2 }}>
          <span style={{ display: 'inline-flex', transform: 'rotate(180deg)' }}><Icon name="chevron" size={15} /></span>
          返回列表
        </button>
        <h1 style={{ fontSize: 26, fontWeight: 700, marginTop: 12 }}>{current.address ?? '未知地点'}</h1>
        <p className="text-secondary" style={{ fontSize: 13, marginTop: 4 }}>
          {formatTime(current.startDate)} → {formatTime(current.endDate)}
          {current.chargeMode ? ` · ${current.chargeMode === 'dc' ? '直流快充' : '交流充电'}` : ''}
        </p>
      </div>
      {error ? <div className="apple-card"><p className="text-error">{error}</p></div> : null}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 12 }}>
        <Kpi label="充入电量" value={num(current.energyAddedKwh, 1)} unit="kWh" icon="bolt" tone="teal" />
        <div style={{ background: 'var(--apple-surface-secondary)', borderRadius: 16, padding: '14px 16px', minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--apple-label-secondary)', fontSize: 12.5, fontWeight: 600, marginBottom: 10 }}>
            <span style={{ color: TONES.green, display: 'inline-flex' }}><Icon name="battery" size={15} /></span>
            电量变化
          </div>
          <BatteryPill from={current.startBatteryLevel} to={current.endBatteryLevel} width={130} />
        </div>
        <Kpi label="时长" value={num(current.durationMinutes)} unit="分钟" icon="clock" tone="purple" />
        <Kpi label="费用" value={current.cost != null ? num(current.cost, 2) : '--'} unit={current.cost != null ? (current.currency ?? '元') : undefined} icon="wallet" tone="amber" />
        <Kpi label="最大功率" value={num(summary?.chargerPowerKw ?? current.maxPowerKw, 1)} unit="kW" icon="zap" tone="blue" />
        <Kpi label="电压" value={num(summary?.chargerVoltageV ?? null)} unit="V" icon="plug" tone="purple" />
        <Kpi label="电流" value={num(summary?.currentA ?? null, 1)} unit="A" icon="bolt" tone="amber" />
        <Kpi label="相数" value={num(summary?.phases ?? null)} icon="plug" tone="gray" />
      </div>
      {detail && detail.reason === 'quarantined_history' ? (
        <div className="apple-card"><p className="text-secondary" style={{ fontSize: 14 }}>这条记录处于隔离状态，曲线不可用。</p></div>
      ) : detail && points.length ? (
        curves.map(curve => {
          const max = curve.series.length ? Math.max(...curve.series.map(point => point.y)) : null
          return (
            <div className="apple-card" key={curve.title}>
              <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 6 }}>
                <h3 style={{ fontSize: 15, fontWeight: 600 }}>{curve.title}</h3>
                <span className="text-tertiary tabular" style={{ fontSize: 12.5 }}>
                  最高 {max != null ? `${max.toFixed(curve.digits)} ${curve.unit}` : '--'}
                </span>
              </div>
              <AreaSeries points={curve.series} unit={curve.unit} tone={curve.tone} height={170} />
            </div>
          )
        })
      ) : detail ? (
        <div className="apple-card"><p className="text-secondary" style={{ fontSize: 14 }}>这次充电没有可用的采样曲线。</p></div>
      ) : (
        <>
          <Skeleton height={190} />
          <Skeleton height={190} />
        </>
      )}
    </div>
  )
}

export default function ChargesPage() {
  const { cars, car, carId, setCarId, error: carError } = useCar()
  const [rows, setRows] = useState<WebCharge[]>([])
  const [page, setPage] = useState(1)
  const [totalPages, setTotalPages] = useState(1)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [filter, setFilter] = useState<ChargeFilter>(EMPTY_CHARGE_FILTER)
  const [selected, setSelected] = useState<WebCharge | null>(null)
  const [status, setStatus] = useState<WebCarStatus | null>(null)

  const loadPage = useCallback(
    async (targetCar: number, targetPage: number, append: boolean) => {
      setLoading(true)
      setError(null)
      try {
        const result = await getChargesPage(targetCar, targetPage)
        setRows(previous => (append ? [...previous, ...result.rows] : result.rows))
        setPage(targetPage)
        setTotalPages(result.totalPages)
      } catch (reason) {
        setError(reason instanceof ApiError ? reason.message : '读取充电记录失败。')
      } finally {
        setLoading(false)
      }
    },
    []
  )

  useEffect(() => {
    if (carId == null) return
    setSelected(null)
    setRows([])
    void loadPage(carId, 1, false)
    getCarStatus(carId).then(setStatus).catch(() => setStatus(null))
  }, [carId, loadPage])

  const visible = useMemo(() => filterCharges(rows, filter), [rows, filter])
  const addresses = useMemo(() => chargeAddresses(rows), [rows])
  const monthly = useMemo(() => monthlyEnergy(rows), [rows])

  if (selected && carId != null) {
    return <ChargeDetailView carId={carId} charge={selected} onBack={() => setSelected(null)} />
  }

  const chargingNow = status?.chargingState?.toLowerCase() === 'charging'
  const monthlyTotal = monthly.reduce((sum, entry) => sum + entry.value, 0)

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12 }}>
        <h1 style={{ fontSize: 28, fontWeight: 700 }}>充电</h1>
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

      {chargingNow && status ? (
        <div className="apple-card" style={{ display: 'flex', alignItems: 'center', gap: 22, flexWrap: 'wrap' }}>
          <Ring value={status.batteryLevel ?? 0} size={148} tone="blue" label="正在充电" sub={car?.name ?? undefined} />
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: 12, flex: 1, minWidth: 260 }}>
            <Kpi label="已充入" value={num(status.chargeEnergyAddedKwh, 1)} unit="kWh" icon="bolt" tone="blue" />
            <Kpi label="当前功率" value={num(status.chargerPowerKw, 1)} unit="kW" icon="zap" tone="teal" />
            <Kpi label="续航" value={num(status.ratedRangeKm)} unit="km" icon="gauge" tone="green" />
          </div>
        </div>
      ) : null}

      <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center' }}>
        <SegControl
          options={[
            { value: 'all', label: '全部' },
            { value: 'ac', label: '交流' },
            { value: 'dc', label: '直流' },
          ]}
          value={filter.mode}
          onChange={mode => setFilter(current => ({ ...current, mode }))}
        />
        <select
          className="apple-input"
          style={{ width: 'auto' }}
          value={filter.address ?? ''}
          onChange={event => setFilter(current => ({ ...current, address: event.target.value || null }))}
        >
          <option value="">全部地点</option>
          {addresses.map(address => (
            <option key={address} value={address}>{address}</option>
          ))}
        </select>
        <button
          onClick={() => setFilter(current => ({ ...current, withCostOnly: !current.withCostOnly }))}
          style={{
            display: 'inline-flex', alignItems: 'center', gap: 6, cursor: 'pointer',
            border: filter.withCostOnly ? '1px solid transparent' : '1px solid var(--apple-separator)',
            background: filter.withCostOnly ? 'var(--apple-teal-soft)' : 'var(--apple-surface)',
            color: filter.withCostOnly ? 'var(--apple-teal)' : 'var(--apple-label-secondary)',
            borderRadius: 999, padding: '6px 14px', fontSize: 13, fontWeight: 600,
            transition: 'all 180ms ease',
          }}
        >
          <Icon name="wallet" size={14} />
          仅看有费用
        </button>
      </div>

      {monthly.length ? (
        <div className="apple-card">
          <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', flexWrap: 'wrap', gap: 8, marginBottom: 6 }}>
            <h3 style={{ fontSize: 16, fontWeight: 600 }}>近六个月充入电量</h3>
            <span className="text-tertiary" style={{ fontSize: 12.5 }}>
              基于已加载记录 · 合计 <span className="tabular" style={{ fontWeight: 600, color: 'var(--apple-label-secondary)' }}>{monthlyTotal.toFixed(1)} kWh</span>
            </span>
          </div>
          <Bars data={monthly} unit="kWh" tone="teal" height={170} />
        </div>
      ) : null}

      {loading && !visible.length ? (
        <>
          <Skeleton height={132} />
          <Skeleton height={132} />
          <Skeleton height={132} />
        </>
      ) : (
        <div className="apple-card" style={{ padding: 0, overflow: 'hidden' }}>
          {visible.map(charge => {
            const visual = modeVisual(charge.chargeMode)
            return (
              <button
                key={charge.id}
                onClick={() => setSelected(charge)}
                style={{ display: 'flex', alignItems: 'center', gap: 14, width: '100%', textAlign: 'left', background: 'none', border: 'none', borderTop: '0.5px solid var(--apple-separator)', padding: '14px 16px', cursor: 'pointer', color: 'inherit' }}
              >
                <span style={{ flexShrink: 0, width: 40, height: 40, borderRadius: 20, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', background: `${visual.color}1f`, color: visual.color }}>
                  <Icon name={visual.icon} size={20} />
                </span>
                <span style={{ flex: 1, minWidth: 0 }}>
                  <span style={{ display: 'block', fontSize: 14.5, fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{charge.address ?? '未知地点'}</span>
                  <span className="text-secondary tabular" style={{ display: 'block', fontSize: 12.5, marginTop: 3 }}>
                    {formatTime(charge.startDate)}
                    {charge.chargeMode ? ` · ${charge.chargeMode === 'dc' ? '直流快充' : '交流充电'}` : ''}
                  </span>
                  <span style={{ display: 'block', marginTop: 7 }}>
                    <BatteryPill from={charge.startBatteryLevel} to={charge.endBatteryLevel} />
                  </span>
                </span>
                <span style={{ flexShrink: 0, textAlign: 'right' }}>
                  <span className="tabular" style={{ display: 'block', fontSize: 20, fontWeight: 700, lineHeight: 1.1 }}>
                    {num(charge.energyAddedKwh, 1)}
                    <span className="text-secondary" style={{ fontSize: 12, fontWeight: 500, marginLeft: 3 }}>kWh</span>
                  </span>
                  {charge.cost != null ? (
                    <span style={{ display: 'inline-block', marginTop: 6, background: 'var(--apple-teal-soft)', color: 'var(--apple-teal)', borderRadius: 999, padding: '2px 9px', fontSize: 12, fontWeight: 600 }} className="tabular">
                      {num(charge.cost, 2)} {charge.currency ?? '元'}
                    </span>
                  ) : null}
                </span>
                <span className="text-tertiary" style={{ flexShrink: 0, display: 'inline-flex' }}><Icon name="chevron" size={16} /></span>
              </button>
            )
          })}
          {!visible.length && !loading ? (
            <p className="text-secondary" style={{ fontSize: 14, padding: 16 }}>没有符合条件的充电记录。</p>
          ) : null}
        </div>
      )}
      {loading && visible.length ? <Skeleton height={72} radius={14} /> : null}

      {page < totalPages ? (
        <button className="apple-btn-secondary" disabled={loading} onClick={() => carId != null && void loadPage(carId, page + 1, true)}>
          加载更多
        </button>
      ) : null}
    </div>
  )
}
