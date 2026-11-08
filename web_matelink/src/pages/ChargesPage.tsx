import { useCallback, useEffect, useMemo, useState } from 'react'
import { ApiError, getCarStatus, getChargeDetail, getChargesPage, type ChargeDetail } from '../api/matelink'
import type { WebCarStatus, WebCharge } from '../api/matelink-types'
import { EMPTY_CHARGE_FILTER, chargeAddresses, filterCharges, type ChargeFilter } from '../api/charge-filter'
import MiniChart from '../components/MiniChart'
import { useCar } from './useCar'

const num = (value: number | null, digits = 0): string => (value == null ? '--' : value.toFixed(digits))

function formatTime(iso: string | null): string {
  if (!iso) return '--'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '--' : date.toLocaleString('zh-CN', { hour12: false })
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
  const chart = (pick: (point: (typeof points)[number]) => number | null) => points.map(point => ({ date: point.date, value: pick(point) }))

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div>
        <button className="apple-btn-secondary" onClick={onBack}>← 返回列表</button>
        <h1 style={{ fontSize: 24, fontWeight: 700, marginTop: 12 }}>{current.address ?? '未知地点'}</h1>
        <p className="text-secondary" style={{ fontSize: 13, marginTop: 4 }}>
          {formatTime(current.startDate)} → {formatTime(current.endDate)}
          {current.chargeMode ? ` · ${current.chargeMode === 'dc' ? '直流快充' : '交流充电'}` : ''}
        </p>
      </div>
      {error ? <div className="apple-card"><p className="text-error">{error}</p></div> : null}
      <div className="apple-card">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(120px, 1fr))', gap: 16 }}>
          <Metric label="充入电量" value={num(current.energyAddedKwh, 1)} unit="kWh" />
          <Metric label="电量变化" value={current.startBatteryLevel != null && current.endBatteryLevel != null ? `${num(current.startBatteryLevel)}% → ${num(current.endBatteryLevel)}%` : '--'} />
          <Metric label="时长" value={num(current.durationMinutes)} unit="分钟" />
          <Metric label="费用" value={current.cost != null ? `${num(current.cost, 2)}` : '--'} unit={current.cost != null ? (current.currency ?? '元') : undefined} />
          <Metric label="最大功率" value={num(summary?.chargerPowerKw ?? current.maxPowerKw, 1)} unit="kW" />
          <Metric label="电压" value={num(summary?.chargerVoltageV ?? null)} unit="V" />
          <Metric label="电流" value={num(summary?.currentA ?? null, 1)} unit="A" />
          <Metric label="相数" value={num(summary?.phases ?? null)} />
        </div>
      </div>
      {detail && detail.reason === 'quarantined_history' ? (
        <div className="apple-card"><p className="text-secondary" style={{ fontSize: 14 }}>这条记录处于隔离状态，曲线不可用。</p></div>
      ) : detail && points.length ? (
        <div className="apple-card" style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
          <div><h3 style={{ fontSize: 15, fontWeight: 600, marginBottom: 8 }}>充电电量变化</h3><MiniChart points={chart(p => p.batteryLevel)} unit="%" /></div>
          <div><h3 style={{ fontSize: 15, fontWeight: 600, marginBottom: 8 }}>充电功率</h3><MiniChart points={chart(p => p.powerKw)} unit="kW" digits={1} /></div>
          <div><h3 style={{ fontSize: 15, fontWeight: 600, marginBottom: 8 }}>充电电压</h3><MiniChart points={chart(p => p.voltageV)} unit="V" /></div>
          <div><h3 style={{ fontSize: 15, fontWeight: 600, marginBottom: 8 }}>充电电流</h3><MiniChart points={chart(p => p.currentA)} unit="A" digits={1} /></div>
          <div><h3 style={{ fontSize: 15, fontWeight: 600, marginBottom: 8 }}>充电温度</h3><MiniChart points={chart(p => p.temperatureC)} unit="°C" digits={1} /></div>
        </div>
      ) : detail ? (
        <div className="apple-card"><p className="text-secondary" style={{ fontSize: 14 }}>这次充电没有可用的采样曲线。</p></div>
      ) : (
        <div className="apple-card"><p className="text-secondary">正在读取曲线…</p></div>
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

  if (selected && carId != null) {
    return <ChargeDetailView carId={carId} charge={selected} onBack={() => setSelected(null)} />
  }

  const chargingNow = status?.chargingState?.toLowerCase() === 'charging'

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
        <div className="apple-card" style={{ borderColor: 'var(--apple-teal)' }}>
          <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 8 }}>正在充电 · {car?.name ?? ''}</div>
          <div className="tabular text-secondary" style={{ fontSize: 13 }}>
            电量 {num(status.batteryLevel)}% · 已充入 {num(status.chargeEnergyAddedKwh, 1)} kWh · 功率 {num(status.chargerPowerKw, 1)} kW
          </div>
        </div>
      ) : null}

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {(['all', 'ac', 'dc'] as const).map(mode => (
          <button
            key={mode}
            className={filter.mode === mode ? 'apple-btn-primary' : 'apple-btn-secondary'}
            style={{ padding: '6px 14px', fontSize: 13 }}
            onClick={() => setFilter(current => ({ ...current, mode }))}
          >
            {mode === 'all' ? '全部' : mode === 'ac' ? '交流' : '直流'}
          </button>
        ))}
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
          className={filter.withCostOnly ? 'apple-btn-primary' : 'apple-btn-secondary'}
          style={{ padding: '6px 14px', fontSize: 13 }}
          onClick={() => setFilter(current => ({ ...current, withCostOnly: !current.withCostOnly }))}
        >
          仅看有费用
        </button>
      </div>

      <div className="apple-card" style={{ padding: 0, overflow: 'hidden' }}>
        {visible.map(charge => (
          <button
            key={charge.id}
            onClick={() => setSelected(charge)}
            style={{ display: 'block', width: '100%', textAlign: 'left', background: 'none', border: 'none', borderTop: '0.5px solid var(--apple-separator)', padding: '14px 16px', cursor: 'pointer', color: 'inherit' }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
              <span style={{ fontSize: 14, fontWeight: 600 }}>{charge.address ?? '未知地点'}</span>
              <span className="tabular" style={{ fontSize: 14, fontWeight: 600 }}>{num(charge.energyAddedKwh, 1)} kWh</span>
            </div>
            <div className="text-secondary tabular" style={{ fontSize: 13, marginTop: 4 }}>
              {formatTime(charge.startDate)}
              {charge.chargeMode ? ` · ${charge.chargeMode === 'dc' ? '直流' : '交流'}` : ''}
              {charge.startBatteryLevel != null && charge.endBatteryLevel != null ? ` · ${num(charge.startBatteryLevel)}% → ${num(charge.endBatteryLevel)}%` : ''}
              {charge.cost != null ? ` · ${num(charge.cost, 2)} ${charge.currency ?? '元'}` : ''}
            </div>
          </button>
        ))}
        {!visible.length && !loading ? (
          <p className="text-secondary" style={{ fontSize: 14, padding: 16 }}>没有符合条件的充电记录。</p>
        ) : null}
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
