import { useEffect, useMemo, useState } from 'react'
import { ApiError, getAllHistory, getCarStatus } from '../api/matelink'
import type { WebCarStatus, WebCharge, WebDriveSummary } from '../api/matelink-types'
import { annualSummary, availableYears, historyCsv, historyJson, monthlyRows, type Covered } from '../api/analysis'
import { readTpmsSamples, recordTpmsSample, tpmsTrend } from '../api/tpms-store'
import { useCar } from './useCar'

const num = (value: number | null, digits = 1): string => (value == null ? '--' : value.toFixed(digits))

function Stat({ label, value, unit, covered }: { label: string; value: string; unit?: string; covered?: Covered }) {
  return (
    <div>
      <div className="text-tertiary" style={{ fontSize: 12, marginBottom: 4 }}>{label}</div>
      <div className="tabular" style={{ fontSize: 20, fontWeight: 700 }}>
        {value}
        {unit ? <span className="text-secondary" style={{ fontSize: 12, fontWeight: 500, marginLeft: 4 }}>{unit}</span> : null}
      </div>
      {covered && covered.total > 0 && covered.known < covered.total ? (
        <div className="text-tertiary tabular" style={{ fontSize: 11, marginTop: 2 }}>基于 {covered.known}/{covered.total} 条有数据的记录</div>
      ) : null}
    </div>
  )
}

function download(filename: string, content: string, type: string) {
  const blob = new Blob([content], { type })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}

const WHEELS = [
  { key: 'fl', label: '左前' },
  { key: 'fr', label: '右前' },
  { key: 'rl', label: '左后' },
  { key: 'rr', label: '右后' },
] as const

export default function AnalysisPage() {
  const { cars, carId, setCarId, error: carError } = useCar()
  const [drives, setDrives] = useState<WebDriveSummary[]>([])
  const [charges, setCharges] = useState<WebCharge[]>([])
  const [complete, setComplete] = useState(true)
  const [status, setStatus] = useState<WebCarStatus | null>(null)
  const [year, setYear] = useState<number | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [sampleCount, setSampleCount] = useState(0)

  useEffect(() => {
    if (carId == null) return
    let live = true
    setLoading(true)
    setError(null)
    getAllHistory(carId)
      .then(result => {
        if (!live) return
        setDrives(result.drives)
        setCharges(result.charges)
        setComplete(result.complete)
        const years = availableYears(result.drives, result.charges)
        setYear(current => (current != null && years.includes(current) ? current : years[0] ?? new Date().getFullYear()))
      })
      .catch((reason: unknown) => {
        if (live) setError(reason instanceof ApiError ? reason.message : '读取历史数据失败。')
      })
      .finally(() => {
        if (live) setLoading(false)
      })
    getCarStatus(carId)
      .then(next => {
        if (!live) return
        setStatus(next)
        recordTpmsSample(carId, next.tpms)
        setSampleCount(readTpmsSamples(carId).length)
      })
      .catch(() => {
        if (live) setStatus(null)
      })
    return () => {
      live = false
    }
  }, [carId])

  const years = useMemo(() => availableYears(drives, charges), [drives, charges])
  const annual = useMemo(() => (year != null ? annualSummary(drives, charges, year) : null), [drives, charges, year])
  const months = useMemo(() => monthlyRows(drives, charges), [drives, charges])
  const trend = useMemo(() => (carId != null ? tpmsTrend(readTpmsSamples(carId)) : null), [carId, sampleCount])
  const tpms = status?.tpms ?? null

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12 }}>
        <h1 style={{ fontSize: 28, fontWeight: 700 }}>分析</h1>
        <div style={{ display: 'flex', gap: 8 }}>
          {cars.length > 1 ? (
            <select className="apple-input" style={{ width: 'auto' }} value={carId ?? ''} onChange={event => setCarId(Number(event.target.value))}>
              {cars.map(item => (
                <option key={item.id} value={item.id}>{item.name}</option>
              ))}
            </select>
          ) : null}
          {years.length ? (
            <select className="apple-input" style={{ width: 'auto' }} value={year ?? ''} onChange={event => setYear(Number(event.target.value))}>
              {years.map(item => (
                <option key={item} value={item}>{item} 年</option>
              ))}
            </select>
          ) : null}
        </div>
      </div>

      {carError ? <div className="apple-card"><p className="text-error">{carError}</p></div> : null}
      {error ? <div className="apple-card"><p className="text-error">{error}</p></div> : null}
      {loading ? <div className="apple-card"><p className="text-secondary">正在汇总历史数据…</p></div> : null}
      {!complete ? (
        <p className="text-tertiary" style={{ fontSize: 12 }}>记录较多，本次汇总基于最近读取的部分历史（每类上限 600 条），数字可能不完整。</p>
      ) : null}

      {annual ? (
        <div className="apple-card">
          <h2 style={{ fontSize: 16, fontWeight: 600, marginBottom: 16 }}>{annual.year} 年度汇总</h2>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: 20 }}>
            <Stat label="总里程" value={num(annual.distance.value)} unit="km" covered={annual.distance} />
            <Stat label="行程次数" value={String(annual.tripCount)} unit="次" />
            <Stat label="驾驶净耗电" value={num(annual.driveEnergy.value)} unit="kWh" covered={annual.driveEnergy} />
            <Stat label="平均能耗" value={num(annual.efficiencyKwhPer100km)} unit="kWh/100km" />
            <Stat label="充电次数" value={String(annual.sessionCount)} unit="次" />
            <Stat label="充入电量" value={num(annual.chargedEnergy.value)} unit="kWh" covered={annual.chargedEnergy} />
            {annual.costs.map(cost => (
              <Stat key={cost.currency} label={`充电费用（${cost.currency}）`} value={cost.value.toFixed(2)} covered={{ value: cost.value, known: cost.count, total: annual.sessionCount }} />
            ))}
          </div>
          <div style={{ display: 'flex', gap: 10, marginTop: 20, flexWrap: 'wrap' }}>
            <button className="apple-btn-secondary" onClick={() => download('matelink-history.csv', historyCsv(drives, charges), 'text/csv;charset=utf-8')}>
              导出 CSV
            </button>
            <button className="apple-btn-secondary" onClick={() => download('matelink-history.json', historyJson(drives, charges, complete), 'application/json')}>
              导出 JSON
            </button>
            <span className="text-tertiary" style={{ fontSize: 12, alignSelf: 'center' }}>导出不含地点信息。</span>
          </div>
        </div>
      ) : null}

      {months.length ? (
        <div className="apple-card" style={{ padding: 0, overflow: 'hidden' }}>
          <h2 style={{ fontSize: 16, fontWeight: 600, padding: '16px 16px 8px' }}>月度概览</h2>
          {months.map(row => (
            <div key={row.month} style={{ display: 'flex', justifyContent: 'space-between', gap: 12, padding: '10px 16px', borderTop: '0.5px solid var(--apple-separator)', fontSize: 13 }} className="tabular">
              <span style={{ fontWeight: 600 }}>{row.month}</span>
              <span className="text-secondary">
                {row.trips} 次行程 · {num(row.distanceKm)} km · {row.sessions} 次充电 · {num(row.chargedKwh)} kWh
                {row.cost != null ? ` · ${row.cost.toFixed(2)} ${row.currency ?? ''}` : ''}
              </span>
            </div>
          ))}
        </div>
      ) : null}

      <div className="apple-card">
        <h2 style={{ fontSize: 16, fontWeight: 600, marginBottom: 12 }}>胎压</h2>
        {tpms ? (
          <>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(110px, 1fr))', gap: 16 }}>
              {WHEELS.map(wheel => (
                <div key={wheel.key}>
                  <div className="text-tertiary" style={{ fontSize: 12, marginBottom: 4 }}>{wheel.label}</div>
                  <div className="tabular" style={{ fontSize: 20, fontWeight: 700 }}>
                    {tpms[wheel.key] != null ? tpms[wheel.key]!.toFixed(2) : '--'}
                    <span className="text-secondary" style={{ fontSize: 12, fontWeight: 500, marginLeft: 4 }}>bar</span>
                  </div>
                  <div className="text-tertiary" style={{ fontSize: 11, marginTop: 2 }}>
                    {tpms.observedAt[wheel.key] ? new Date(tpms.observedAt[wheel.key]!).toLocaleString('zh-CN', { hour12: false }) : '无采样时间'}
                  </div>
                </div>
              ))}
            </div>
            <div style={{ borderTop: '0.5px solid var(--apple-separator)', marginTop: 14, paddingTop: 12 }}>
              <div className="text-tertiary" style={{ fontSize: 12, marginBottom: 6 }}>
                趋势（基于本浏览器累计的 {sampleCount} 个样本{trend && trend.spanHours > 0 ? `，跨度 ${trend.spanHours.toFixed(1)} 小时` : ''}）
              </div>
              {trend?.wheels.map(item => (
                <p key={item.wheel} className="text-secondary" style={{ fontSize: 13, lineHeight: 1.8 }}>{item.verdict}。</p>
              ))}
              <p className="text-tertiary" style={{ fontSize: 12, marginTop: 6, lineHeight: 1.7 }}>
                网页端没有胎压历史接口，趋势样本来自你每次打开网页时记录的当前值；样本不足时不下结论。温度对胎压的影响分析需要温度序列，网页端暂不提供。
              </p>
            </div>
          </>
        ) : (
          <p className="text-secondary" style={{ fontSize: 14 }}>当前状态未提供胎压数据（需要明确的 bar 单位才会显示）。</p>
        )}
      </div>
    </div>
  )
}
