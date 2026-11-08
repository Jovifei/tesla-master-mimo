import { useEffect, useMemo, useState } from 'react'
import { ApiError, getAllHistory, getCarStatus } from '../api/matelink'
import type { WebCarStatus, WebCharge, WebDriveSummary } from '../api/matelink-types'
import { annualSummary, availableYears, historyCsv, historyJson, monthlyRows, type Covered } from '../api/analysis'
import { readTpmsSamples, recordTpmsSample, tpmsTrend, type TpmsSample, type WheelTrend } from '../api/tpms-store'
import { useCar } from './useCar'
import { Icon, SegControl, Skeleton, TpmsCar, TONES } from '../components/viz'
import { Panel, V3 } from '../components/v3'
import { BarsPanel, DATA_COLORS, StatTile, fmtNum } from '../components/panels'

/**
 * Analysis v4 — TeslaMate "stats" composition: an annual big-number
 * tile row first, then monthly bar charts （里程 / 充入电量 / 费用）
 * as the visual body, the monthly list as support. Months whose value
 * is unknown are omitted from a chart, never drawn as zero. TPMS keeps
 * the verdict list as protagonist and adds points-only small
 * multiples (never a line — V3 honesty law for sparse samples).
 */

const num = (value: number | null, digits = 1): string => (value == null ? '--' : value.toFixed(digits))

function coverageNote(covered: Covered): string | null {
  return covered.total > 0 && covered.known < covered.total ? `基于 ${covered.known}/${covered.total} 条有数据的记录` : null
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

const WHEELS = [
  { key: 'fl', label: '左前' },
  { key: 'fr', label: '右前' },
  { key: 'rl', label: '左后' },
  { key: 'rr', label: '右后' },
] as const

/** Points-only TPMS history: four wheels share the time axis and one bar domain. */
function TpmsPoints({ samples }: { samples: TpmsSample[] }) {
  const t0 = samples[0].at
  const t1 = samples[samples.length - 1].at
  const values = samples.flatMap(sample => [sample.fl, sample.fr, sample.rl, sample.rr])
  if (values.length < 2 || t1 <= t0) return null
  const yMin = Math.floor((Math.min(...values) - 0.2) * 10) / 10
  const yMax = Math.ceil((Math.max(...values) + 0.2) * 10) / 10
  const W = 320
  const H = 46
  return (
    <div style={{ marginTop: 16 }}>
      <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 4 }}>历史采样点</div>
      {WHEELS.map(wheel => (
        <div key={wheel.key} style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 6 }}>
          <span style={{ width: 34, fontSize: 12.5, color: V3.label2, flexShrink: 0 }}>{wheel.label}</span>
          <svg viewBox={`0 0 ${W} ${H}`} style={{ flex: 1, height: H, display: 'block' }} role="img" aria-label={`${wheel.label}胎压历史采样点`}>
            <line x1={0} y1={H - 4} x2={W} y2={H - 4} stroke={V3.separator} strokeWidth={1} />
            {samples.map((sample, index) => (
              <circle
                key={index}
                cx={((sample.at - t0) / (t1 - t0)) * (W - 8) + 4}
                cy={4 + (1 - (sample[wheel.key] - yMin) / (yMax - yMin)) * (H - 12)}
                r={3}
                fill={DATA_COLORS.teal}
                opacity={0.85}
              />
            ))}
          </svg>
          <span className="tabular" style={{ width: 92, fontSize: 11.5, color: V3.label3, flexShrink: 0, textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
            {samples.length} 点 · {yMin.toFixed(1)}–{yMax.toFixed(1)} bar
          </span>
        </div>
      ))}
      <p style={{ fontSize: 12, color: V3.label3, marginTop: 8 }}>
        四轮共用同一纵轴；仅绘制采样点、不连线，避免把稀疏采样误读为连续趋势。
      </p>
    </div>
  )
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
  const samples = useMemo(() => (carId != null ? readTpmsSamples(carId) : []), [carId, sampleCount])
  const trend = useMemo(() => tpmsTrend(samples), [samples])
  const tpms = status?.tpms ?? null

  const distanceBars = chronoMonths.flatMap(row => (row.distanceKm != null ? [{ label: monthParts(row.month).mon, value: Math.round(row.distanceKm) }] : []))
  const chargedBars = chronoMonths.flatMap(row => (row.chargedKwh != null ? [{ label: monthParts(row.month).mon, value: Math.round(row.chargedKwh * 10) / 10 }] : []))
  const costRows = chronoMonths.filter(row => row.cost != null)
  const costBars = costRows.map(row => ({ label: monthParts(row.month).mon, value: Math.round((row.cost as number) * 100) / 100 }))
  const costExcluded = chronoMonths.length - costRows.length
  const rangeText = chronoMonths.length
    ? `${monthParts(chronoMonths[0].month).year}年${monthParts(chronoMonths[0].month).mon} – ${monthParts(chronoMonths[chronoMonths.length - 1].month).year}年${monthParts(chronoMonths[chronoMonths.length - 1].month).mon}`
    : ''

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12 }}>
        <h1 style={{ fontSize: 28, fontWeight: 700 }}>分析</h1>
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

      {carError ? <Panel><p style={{ color: V3.errorText, fontSize: 14.5 }}>{carError}</p></Panel> : null}
      {error ? <Panel><p style={{ color: V3.errorText, fontSize: 14.5 }}>{error}</p></Panel> : null}
      {!complete ? (
        <p style={{ fontSize: 12.5, color: V3.label3 }}>记录较多，本次汇总基于最近读取的部分历史（每类上限 600 条），数字可能不完整。</p>
      ) : null}

      {loading ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }} aria-label="正在汇总历史数据">
          <Skeleton height={196} />
          <Skeleton height={220} />
        </div>
      ) : (
        <>
          {annual ? (
            <Panel title={`${annual.year} 年度汇总`}>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 12 }}>
                <StatTile label="总里程" value={num(annual.distance.value)} unit="km" caption={coverageNote(annual.distance) ?? undefined} />
                <StatTile label="行程次数" value={String(annual.tripCount)} unit="次" />
                <StatTile label="驾驶净耗电" value={num(annual.driveEnergy.value)} unit="kWh" accent={V3.teal} caption={coverageNote(annual.driveEnergy) ?? undefined} />
                <StatTile label="平均能耗" value={annual.efficiencyKwhPer100km != null ? fmtNum(annual.efficiencyKwhPer100km, 1) : '--'} unit="kWh/100km" />
                <StatTile label="充电次数" value={String(annual.sessionCount)} unit="次" />
                <StatTile label="充入电量" value={num(annual.chargedEnergy.value)} unit="kWh" accent={V3.teal} caption={coverageNote(annual.chargedEnergy) ?? undefined} />
                {annual.costs.map(cost => (
                  <StatTile
                    key={cost.currency}
                    label="充电费用"
                    value={fmtNum(cost.value, 2)}
                    unit={cost.currency}
                    caption={coverageNote({ value: cost.value, known: cost.count, total: annual.sessionCount }) ?? undefined}
                  />
                ))}
              </div>
              <div style={{ display: 'flex', gap: 10, marginTop: 16, flexWrap: 'wrap', alignItems: 'center' }}>
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
            </Panel>
          ) : null}

          {distanceBars.length ? (
            <Panel title="每月里程">
              <BarsPanel data={distanceBars} unit="km" color={DATA_COLORS.graphite} height={180} summary={<span>{rangeText} · 合计 <span className="tabular" style={{ fontWeight: 600, color: V3.label }}>{fmtNum(chronoMonths.reduce((sum, row) => sum + (row.distanceKm ?? 0), 0))} km</span></span>} />
            </Panel>
          ) : null}
          {chargedBars.length ? (
            <Panel title="每月充入电量">
              <BarsPanel data={chargedBars} unit="kWh" color={DATA_COLORS.teal} height={180} summary={<span>{rangeText} · 合计 <span className="tabular" style={{ fontWeight: 600, color: V3.label }}>{fmtNum(chronoMonths.reduce((sum, row) => sum + (row.chargedKwh ?? 0), 0))} kWh</span></span>} />
            </Panel>
          ) : null}
          {costBars.length ? (
            <Panel title="每月费用">
              <BarsPanel
                data={costBars}
                unit="元"
                digits={2}
                color={DATA_COLORS.graphite}
                height={180}
                summary={
                  <span>
                    合计 <span className="tabular" style={{ fontWeight: 600, color: V3.label }}>{fmtNum(costRows.reduce((sum, row) => sum + (row.cost ?? 0), 0), 2)} 元</span>
                    {costExcluded > 0 ? ` · 另有 ${costExcluded} 个月费用不完整（多币种或缺失），未计入` : ''}
                  </span>
                }
              />
            </Panel>
          ) : null}

          {months.length ? (
            <Panel title="月度概览">
              {months.map((row, index) => {
                const parts = monthParts(row.month)
                return (
                  <div key={row.month} style={{ display: 'flex', alignItems: 'center', gap: 16, padding: '11px 0', borderTop: index === 0 ? 'none' : `0.5px solid ${V3.separator}`, flexWrap: 'wrap' }}>
                    <div style={{ background: 'var(--apple-surface-secondary)', borderRadius: 14, padding: '6px 0', width: 58, textAlign: 'center', flexShrink: 0 }}>
                      <div style={{ fontSize: 15, fontWeight: 700, lineHeight: 1.15 }}>{parts.mon}</div>
                      <div className="text-tertiary tabular" style={{ fontSize: 10.5 }}>{parts.year}</div>
                    </div>
                    <div style={{ display: 'flex', gap: 18, flexWrap: 'wrap', fontSize: 13 }} className="tabular">
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                        <span style={{ color: TONES.teal, display: 'inline-flex' }}><Icon name="route" size={15} /></span>
                        {row.trips} 次 · {num(row.distanceKm)} km
                      </span>
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                        <span style={{ color: DATA_COLORS.teal, display: 'inline-flex' }}><Icon name="plug" size={15} /></span>
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
            </Panel>
          ) : null}
        </>
      )}

      <Panel title="胎压">
        {tpms ? (
          <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap' }}>
            <div style={{ flex: '0 1 220px', minWidth: 190, margin: '0 auto' }}>
              <TpmsCar fl={tpms.fl} fr={tpms.fr} rl={tpms.rl} rr={tpms.rr} />
            </div>
            <div style={{ flex: '1 1 300px', minWidth: 260 }}>
              {WHEELS.map(wheel => (
                <div key={wheel.key} style={{ display: 'flex', alignItems: 'baseline', gap: 10, padding: '7px 0', borderBottom: `0.5px solid ${V3.separator}` }}>
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
                  趋势（基于本浏览器累计的 {sampleCount} 个样本{trend.spanHours > 0 ? `，跨度 ${trend.spanHours.toFixed(1)} 小时` : ''}）
                </div>
                {trend.wheels.map(item => (
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
        {samples.length >= 2 ? <TpmsPoints samples={samples} /> : null}
      </Panel>
    </div>
  )
}
