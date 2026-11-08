import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { ApiError, getAllHistory, getCarStatus } from '../api/matelink'
import type { WebCarStatus, WebCharge, WebDriveSummary } from '../api/matelink-types'
import { annualSummary, availableYears, historyCsv, historyJson, monthlyRows, type Covered } from '../api/analysis'
import { readTpmsSamples, recordTpmsSample, tpmsTrend, type WheelTrend } from '../api/tpms-store'
import { useCar } from './useCar'
import { AreaSeries, Bars, Icon, Kpi, SegControl, Skeleton, TpmsCar, TONES, type IconName, type Tone } from '../components/viz'

const num = (value: number | null, digits = 1): string => (value == null ? '--' : value.toFixed(digits))

function coverageNote(covered: Covered): string | null {
  return covered.total > 0 && covered.known < covered.total ? `基于 ${covered.known}/${covered.total} 条有数据的记录` : null
}

function KpiCell({ label, value, unit, icon, tone = 'teal', spark, caption }: {
  label: string
  value: string
  unit?: string
  icon: IconName
  tone?: Tone
  spark?: number[]
  caption?: string | null
}) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 5, minWidth: 0 }}>
      <Kpi label={label} value={value} unit={unit} icon={icon} tone={tone} spark={spark} />
      {caption ? <div className="text-tertiary tabular" style={{ fontSize: 11, paddingLeft: 3 }}>{caption}</div> : null}
    </div>
  )
}

function CardTitle({ icon, children }: { icon: IconName; children: ReactNode }) {
  return (
    <h2 style={{ fontSize: 16, fontWeight: 600, marginBottom: 14, display: 'flex', alignItems: 'center', gap: 8 }}>
      <span style={{ color: TONES.teal, display: 'inline-flex' }}><Icon name={icon} size={18} /></span>
      {children}
    </h2>
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

function trendDot(item: WheelTrend): string {
  if (item.deltaBar == null) return TONES.gray
  if (item.deltaBar <= -0.2) return TONES.red
  if (item.deltaBar >= 0.2) return TONES.blue
  return TONES.green
}

const monthParts = (month: string): { year: string; mon: string } => {
  const [year, mon] = month.split('-')
  return { year: year ?? '', mon: `${Number(mon)} 月` }
}

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
  const chronoMonths = useMemo(() => [...months].reverse(), [months])
  const distanceBars = useMemo(
    () => chronoMonths.map(row => ({ label: row.month.slice(2), value: row.distanceKm ?? 0 })),
    [chronoMonths],
  )
  const chargedPoints = useMemo(
    () => chronoMonths.map(row => ({ x: row.month.slice(2), y: row.chargedKwh ?? 0 })),
    [chronoMonths],
  )
  const trend = useMemo(() => (carId != null ? tpmsTrend(readTpmsSamples(carId)) : null), [carId, sampleCount])
  const tpms = status?.tpms ?? null

  return (
    <div className="page-in" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12 }}>
        <h1 className="hero-title">分析</h1>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
          {cars.length > 1 ? (
            <select className="apple-input" style={{ width: 'auto' }} value={carId ?? ''} onChange={event => setCarId(Number(event.target.value))}>
              {cars.map(item => (
                <option key={item.id} value={item.id}>{item.name}</option>
              ))}
            </select>
          ) : null}
          {years.length > 4 ? (
            <select className="apple-input" style={{ width: 'auto' }} value={year ?? ''} onChange={event => setYear(Number(event.target.value))}>
              {years.map(item => (
                <option key={item} value={item}>{item} 年</option>
              ))}
            </select>
          ) : years.length ? (
            <SegControl
              options={years.map(item => ({ value: String(item), label: `${item} 年` }))}
              value={year != null ? String(year) : ''}
              onChange={value => setYear(Number(value))}
            />
          ) : null}
        </div>
      </div>

      {carError ? <div className="apple-card"><p className="text-error">{carError}</p></div> : null}
      {error ? <div className="apple-card"><p className="text-error">{error}</p></div> : null}
      {loading ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }} aria-label="正在汇总历史数据">
          <Skeleton height={196} />
          <Skeleton height={150} />
        </div>
      ) : null}
      {!complete ? (
        <p className="text-tertiary" style={{ fontSize: 12 }}>记录较多，本次汇总基于最近读取的部分历史（每类上限 600 条），数字可能不完整。</p>
      ) : null}

      {annual ? (
        <div className="apple-card">
          <CardTitle icon="chart">{annual.year} 年度汇总</CardTitle>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(168px, 1fr))', gap: 12 }}>
            <KpiCell
              label="总里程"
              value={num(annual.distance.value)}
              unit="km"
              icon="route"
              tone="teal"
              spark={chronoMonths.map(row => row.distanceKm ?? 0)}
              caption={coverageNote(annual.distance)}
            />
            <KpiCell label="行程次数" value={String(annual.tripCount)} unit="次" icon="pin" tone="purple" />
            <KpiCell label="驾驶净耗电" value={num(annual.driveEnergy.value)} unit="kWh" icon="bolt" tone="blue" caption={coverageNote(annual.driveEnergy)} />
            <KpiCell label="平均能耗" value={num(annual.efficiencyKwhPer100km)} unit="kWh/100km" icon="gauge" tone="amber" />
            <KpiCell label="充电次数" value={String(annual.sessionCount)} unit="次" icon="plug" tone="green" />
            <KpiCell
              label="充入电量"
              value={num(annual.chargedEnergy.value)}
              unit="kWh"
              icon="battery"
              tone="green"
              spark={chronoMonths.map(row => row.chargedKwh ?? 0)}
              caption={coverageNote(annual.chargedEnergy)}
            />
            {annual.costs.map(cost => (
              <KpiCell
                key={cost.currency}
                label="充电费用"
                value={cost.value.toFixed(2)}
                unit={cost.currency}
                icon="wallet"
                tone="teal"
                caption={coverageNote({ value: cost.value, known: cost.count, total: annual.sessionCount })}
              />
            ))}
          </div>
          <div style={{ display: 'flex', gap: 10, marginTop: 20, flexWrap: 'wrap', alignItems: 'center' }}>
            <button className="apple-btn-secondary" style={{ display: 'inline-flex', alignItems: 'center', gap: 7 }} onClick={() => download('matelink-history.csv', historyCsv(drives, charges), 'text/csv;charset=utf-8')}>
              <Icon name="download" size={15} />
              导出 CSV
            </button>
            <button className="apple-btn-secondary" style={{ display: 'inline-flex', alignItems: 'center', gap: 7 }} onClick={() => download('matelink-history.json', historyJson(drives, charges, complete), 'application/json')}>
              <Icon name="download" size={15} />
              导出 JSON
            </button>
            <span className="text-tertiary" style={{ fontSize: 12 }}>导出不含地点信息。</span>
          </div>
        </div>
      ) : null}

      {months.length ? (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(330px, 1fr))', gap: 16 }}>
          <div className="apple-card">
            <CardTitle icon="route">每月里程</CardTitle>
            <Bars data={distanceBars} unit="km" tone="teal" height={190} />
          </div>
          <div className="apple-card">
            <CardTitle icon="plug">每月充入电量</CardTitle>
            <AreaSeries points={chargedPoints} unit="kWh" tone="blue" height={190} />
          </div>
        </div>
      ) : null}

      {months.length ? (
        <div className="apple-card" style={{ padding: 0, overflow: 'hidden' }}>
          <h2 style={{ fontSize: 16, fontWeight: 600, padding: '16px 16px 8px', display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ color: TONES.teal, display: 'inline-flex' }}><Icon name="calendar" size={18} /></span>
            月度概览
          </h2>
          {months.map(row => {
            const parts = monthParts(row.month)
            return (
              <div key={row.month} style={{ display: 'flex', alignItems: 'center', gap: 16, padding: '11px 16px', borderTop: '0.5px solid var(--apple-separator)', flexWrap: 'wrap' }}>
                <div style={{ background: 'var(--apple-surface-secondary)', borderRadius: 12, padding: '6px 0', width: 58, textAlign: 'center', flexShrink: 0 }}>
                  <div style={{ fontSize: 15, fontWeight: 700, lineHeight: 1.15 }}>{parts.mon}</div>
                  <div className="text-tertiary tabular" style={{ fontSize: 10.5 }}>{parts.year}</div>
                </div>
                <div style={{ display: 'flex', gap: 18, flexWrap: 'wrap', fontSize: 13 }} className="tabular">
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                    <span style={{ color: TONES.teal, display: 'inline-flex' }}><Icon name="route" size={15} /></span>
                    {row.trips} 次 · {num(row.distanceKm)} km
                  </span>
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                    <span style={{ color: TONES.blue, display: 'inline-flex' }}><Icon name="plug" size={15} /></span>
                    {row.sessions} 次 · {num(row.chargedKwh)} kWh
                  </span>
                  {row.cost != null ? (
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                      <span style={{ color: TONES.amber, display: 'inline-flex' }}><Icon name="wallet" size={15} /></span>
                      {row.cost.toFixed(2)} {row.currency ?? ''}
                    </span>
                  ) : null}
                </div>
              </div>
            )
          })}
        </div>
      ) : null}

      <div className="apple-card">
        <CardTitle icon="gauge">胎压</CardTitle>
        {tpms ? (
          <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap' }}>
            <div style={{ flex: '0 1 220px', minWidth: 190, margin: '0 auto' }}>
              <TpmsCar fl={tpms.fl} fr={tpms.fr} rl={tpms.rl} rr={tpms.rr} />
            </div>
            <div style={{ flex: '1 1 300px', minWidth: 260 }}>
              {WHEELS.map(wheel => (
                <div key={wheel.key} style={{ display: 'flex', alignItems: 'baseline', gap: 10, padding: '7px 0', borderBottom: '0.5px solid var(--apple-separator)' }}>
                  <span style={{ fontSize: 13, fontWeight: 600, width: 34 }}>{wheel.label}</span>
                  <span className="tabular" style={{ fontSize: 17, fontWeight: 700 }}>
                    {tpms[wheel.key] != null ? tpms[wheel.key]!.toFixed(2) : '--'}
                    <span className="text-secondary" style={{ fontSize: 11, fontWeight: 500, marginLeft: 3 }}>bar</span>
                  </span>
                  <span className="text-tertiary tabular" style={{ fontSize: 11.5, marginLeft: 'auto', textAlign: 'right' }}>
                    {tpms.observedAt[wheel.key]
                      ? new Date(tpms.observedAt[wheel.key]!).toLocaleString('zh-CN', { hour12: false, month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
                      : '无采样时间'}
                  </span>
                </div>
              ))}
              <div style={{ marginTop: 14 }}>
                <div className="text-tertiary" style={{ fontSize: 12, marginBottom: 8 }}>
                  趋势（基于本浏览器累计的 {sampleCount} 个样本{trend && trend.spanHours > 0 ? `，跨度 ${trend.spanHours.toFixed(1)} 小时` : ''}）
                </div>
                {trend?.wheels.map(item => (
                  <div key={item.wheel} style={{ display: 'flex', alignItems: 'flex-start', gap: 8, padding: '3px 0' }}>
                    <span style={{ width: 8, height: 8, borderRadius: 4, background: trendDot(item), flexShrink: 0, marginTop: 6 }} />
                    <span className="text-secondary" style={{ fontSize: 13, lineHeight: 1.7 }}>{item.verdict}。</span>
                  </div>
                ))}
                <p className="text-tertiary" style={{ fontSize: 12, marginTop: 8, lineHeight: 1.7 }}>
                  网页端没有胎压历史接口，趋势样本来自你每次打开网页时记录的当前值；样本不足时不下结论。温度对胎压的影响分析需要温度序列，网页端暂不提供。
                </p>
              </div>
            </div>
          </div>
        ) : (
          <p className="text-secondary" style={{ fontSize: 14 }}>当前状态未提供胎压数据（需要明确的 bar 单位才会显示）。</p>
        )}
      </div>
    </div>
  )
}
