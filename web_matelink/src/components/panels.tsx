import { useId, useMemo, useState, type ReactNode } from 'react'
import {
  Bar, BarChart, CartesianGrid, Line, LineChart, ResponsiveContainer, Scatter, ScatterChart,
  Tooltip, XAxis, YAxis,
} from 'recharts'
import { V3 } from './v3'
import type { StateSegment } from '../api/panel-data'

/**
 * Panel-composition library for the v4 rebuild (TeslaMate composition ×
 * the warm 家庭健康台 surface): stat tiles with small label + big number,
 * large stacked charts sharing one time axis, scatter panels and a
 * full-width state timeline. Chart honesty per docs/WEB-DESIGN-V3.md
 * §2.7: no dual axes (stacked panels instead), straight segments only,
 * gaps break the line, points appear when samples are sparse, chart
 * animation is off, and readouts live in a fixed row above the chart —
 * never in a floating tooltip.
 */

export const DATA_COLORS = {
  teal: '#157a86',
  tealDeep: '#0f5c66',
  tealLight: '#2a9db0',
  graphite: '#48484a',
  graphiteLight: '#8e8e93',
} as const

/**
 * Big-number colors in TeslaMate's tile semantics （费用绿、时长蓝、
 * 功率橙、温度紫…), deepened for our light warm surface so the
 * numbers stay legible — the owner's rule: numbers carry color,
 * text does not.
 */
export const TILE_COLORS = {
  green: '#4e9e57',
  amber: '#c08a00',
  blue: '#2f7fc4',
  orange: '#d97a2b',
  purple: '#7d5fc0',
  red: '#d9534f',
} as const

const tabular = { fontVariantNumeric: 'tabular-nums' } as const

export function fmtNum(value: number | null | undefined, digits = 0): string {
  if (value == null || !Number.isFinite(value)) return '--'
  return value.toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits })
}

export function fmtDuration(minutes: number | null | undefined): string {
  if (minutes == null || !Number.isFinite(minutes)) return '--'
  const total = Math.round(minutes)
  if (total < 60) return `${total} 分钟`
  const hours = Math.floor(total / 60)
  const rest = total % 60
  return rest ? `${hours} 小时 ${rest} 分` : `${hours} 小时`
}

function hhmm(epoch: number): string {
  const date = new Date(epoch)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function hhmmss(epoch: number): string {
  const date = new Date(epoch)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/* ----------------------------- StatTile ----------------------------- */

export function StatTile({
  label,
  value,
  unit,
  caption,
  accent,
  children,
}: {
  label: string
  value?: string
  unit?: string
  caption?: string
  accent?: string
  children?: ReactNode
}) {
  return (
    <div
      style={{
        background: V3.surface, borderRadius: 20, padding: '15px 17px', minWidth: 0,
        boxShadow: V3.cardShadow, border: `0.5px solid ${V3.separator}`,
        display: 'flex', flexDirection: 'column', justifyContent: 'center',
      }}
    >
      <div style={{ fontSize: 12.5, fontWeight: 600, color: V3.label3 }}>{label}</div>
      {children ?? (
        <div style={{ marginTop: 5, display: 'flex', alignItems: 'baseline', gap: 3 }}>
          <span className="tabular" style={{ ...tabular, fontSize: 30, fontWeight: 700, lineHeight: 1.1, color: accent ?? V3.label }}>{value}</span>
          {unit ? <span style={{ fontSize: 13, fontWeight: 500, color: V3.label2 }}>{unit}</span> : null}
        </div>
      )}
      {caption ? <div style={{ fontSize: 11.5, color: V3.label3, marginTop: 5, lineHeight: 1.5 }}>{caption}</div> : null}
    </div>
  )
}

/* ---------------------------- StackChart ---------------------------- */

export type ChartRow = { t: number } & Record<string, number | null>

export type StackSeriesDef = {
  key: string
  title: string
  unit: string
  color: string
  height: number
  digits?: number
  domain?: [number | 'auto', number | 'auto']
  /** Set when this series has too few samples to draw: the row stays, the chart goes. */
  emptyReason?: string
}

/** Recharts v3 mouse state: the tooltip index may be a string for category axes, so it stays unknown here and is narrowed at use. */
type ChartMouseState = { activeTooltipIndex?: unknown; activePayload?: { payload?: { x: number; y: number } }[] } | null

export function StackChart({ data, series, syncKey }: { data: ChartRow[]; series: StackSeriesDef[]; syncKey?: string }) {
  const rawId = useId().replace(/[^a-zA-Z0-9]/g, '')
  const syncId = syncKey ?? `stack-${rawId}`
  const [active, setActive] = useState<number | null>(null)
  const drawable = series.filter(def => !def.emptyReason)
  const t0 = data.length ? data[0].t : 0
  const t1 = data.length ? data[data.length - 1].t : 0

  const stats = useMemo(() => {
    const map = new Map<string, { min: number; max: number; count: number }>()
    for (const def of drawable) {
      let min = Infinity
      let max = -Infinity
      let count = 0
      for (const row of data) {
        const value = row[def.key]
        if (value != null && Number.isFinite(value)) {
          count++
          if (value < min) min = value
          if (value > max) max = value
        }
      }
      map.set(def.key, { min, max, count })
    }
    return map
  }, [data, drawable])

  const onMove = (state: ChartMouseState) => {
    const index = state?.activeTooltipIndex
    setActive(typeof index === 'number' && index >= 0 && index < data.length ? index : null)
  }

  const activeRow = active != null ? data[active] : null

  return (
    <div>
      {/* Fixed readout row: hovered sample values, or per-series min/max by default. */}
      <div className="tabular" style={{ ...tabular, minHeight: 20, fontSize: 12.5, color: V3.label2, display: 'flex', gap: 16, flexWrap: 'wrap', marginBottom: 6 }}>
        {activeRow ? (
          <>
            <span style={{ fontWeight: 600, color: V3.label }}>{hhmmss(activeRow.t)}</span>
            {drawable.map(def => (
              <span key={def.key}>
                <span style={{ color: def.color, fontWeight: 600 }}>{def.title}</span>{' '}
                {activeRow[def.key] != null ? `${fmtNum(activeRow[def.key], def.digits ?? 0)} ${def.unit}` : '无采样'}
              </span>
            ))}
          </>
        ) : (
          drawable.map(def => {
            const stat = stats.get(def.key)
            return (
              <span key={def.key}>
                <span style={{ color: def.color, fontWeight: 600 }}>{def.title}</span>{' '}
                {stat && stat.count ? `最低 ${fmtNum(stat.min, def.digits ?? 0)} · 最高 ${fmtNum(stat.max, def.digits ?? 0)} ${def.unit}` : '暂无数据'}
              </span>
            )
          })
        )}
      </div>
      {series.map((def, index) => {
        if (def.emptyReason) {
          return (
            <div
              key={def.key}
              style={{ height: 40, display: 'flex', alignItems: 'center', gap: 8, padding: '0 12px', background: V3.surface2, borderRadius: 14, fontSize: 13, color: V3.label2, marginTop: index === 0 ? 0 : 8 }}
            >
              {def.title}：{def.emptyReason}
            </div>
          )
        }
        const stat = stats.get(def.key)
        const sparse = (stat?.count ?? 0) < 10
        const isLast = index === series.length - 1
        return (
          <div key={def.key} style={{ marginTop: index === 0 ? 0 : 2 }}>
            <div style={{ fontSize: 12.5, fontWeight: 600, color: V3.label2, paddingLeft: 2, marginBottom: 2 }}>
              {def.title}
              <span style={{ color: V3.label3, fontWeight: 500 }}>（{def.unit}）</span>
              {sparse && stat ? <span style={{ color: V3.label3, fontWeight: 500 }}> · 本次仅 {stat.count} 个采样点</span> : null}
            </div>
            <ResponsiveContainer width="100%" height={def.height}>
              <LineChart data={data} syncId={syncId} margin={{ top: 4, right: 8, bottom: 0, left: 0 }} onMouseMove={onMove} onMouseLeave={() => setActive(null)}>
                <CartesianGrid vertical={false} stroke={V3.separator} />
                <XAxis
                  dataKey="t"
                  type="number"
                  domain={[t0, t1]}
                  tickFormatter={(value: number) => hhmm(value)}
                  tick={{ fontSize: 11, fill: V3.label3 }}
                  axisLine={false}
                  tickLine={false}
                  hide={!isLast}
                  height={isLast ? 22 : 0}
                  minTickGap={40}
                />
                <YAxis
                  domain={def.domain ?? ['auto', 'auto']}
                  tick={{ fontSize: 11, fill: V3.label3 }}
                  axisLine={false}
                  tickLine={false}
                  width={46}
                />
                <Tooltip content={() => null} cursor={{ stroke: V3.label3, strokeWidth: 1 }} />
                <Line
                  type="linear"
                  dataKey={def.key}
                  stroke={def.color}
                  strokeWidth={2}
                  dot={sparse ? { r: 2.5, fill: def.color, strokeWidth: 0 } : false}
                  activeDot={{ r: 3.5, fill: def.color, stroke: '#fff', strokeWidth: 1.5 }}
                  connectNulls={false}
                  isAnimationActive={false}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
        )
      })}
    </div>
  )
}

/* ---------------------------- ScatterPanel --------------------------- */

export function ScatterPanel({
  points,
  xLabel,
  yLabel,
  color = DATA_COLORS.teal,
  height = 220,
}: {
  points: { x: number; y: number }[]
  xLabel: string
  yLabel: string
  color?: string
  height?: number
}) {
  const [active, setActive] = useState<{ x: number; y: number } | null>(null)
  const onMove = (state: ChartMouseState) => {
    const payload = state?.activePayload?.[0]?.payload
    setActive(payload ? { x: payload.x, y: payload.y } : null)
  }
  return (
    <div>
      <div className="tabular" style={{ ...tabular, minHeight: 20, fontSize: 12.5, color: V3.label2, marginBottom: 6 }}>
        {active ? (
          <span>
            {xLabel} <span style={{ fontWeight: 600, color: V3.label }}>{fmtNum(active.x)}%</span>
            {' · '}
            {yLabel} <span style={{ fontWeight: 600, color: V3.label }}>{fmtNum(active.y, 1)} kW</span>
          </span>
        ) : (
          <span style={{ color: V3.label3 }}>共 {points.length} 个采样点 · 每个点为一次采样</span>
        )}
      </div>
      <ResponsiveContainer width="100%" height={height}>
        <ScatterChart margin={{ top: 6, right: 12, bottom: 0, left: 0 }} onMouseMove={onMove} onMouseLeave={() => setActive(null)}>
          <CartesianGrid stroke={V3.separator} />
          <XAxis
            type="number"
            dataKey="x"
            domain={[0, 100]}
            tick={{ fontSize: 11, fill: V3.label3 }}
            axisLine={false}
            tickLine={false}
            tickFormatter={(value: number) => `${value}%`}
            height={22}
          />
          <YAxis
            type="number"
            dataKey="y"
            domain={[0, 'auto']}
            tick={{ fontSize: 11, fill: V3.label3 }}
            axisLine={false}
            tickLine={false}
            width={46}
          />
          <Tooltip content={() => null} cursor={{ stroke: V3.label3, strokeDasharray: '4 4' }} />
          <Scatter data={points} fill={color} isAnimationActive={false} />
        </ScatterChart>
      </ResponsiveContainer>
    </div>
  )
}

/* ----------------------------- BarsPanel ----------------------------- */

export function BarsPanel({
  data,
  unit,
  color,
  height = 180,
  digits = 1,
  summary,
}: {
  data: { label: string; value: number }[]
  unit: string
  color: string
  height?: number
  digits?: number
  summary?: ReactNode
}) {
  const [active, setActive] = useState<number | null>(null)
  const onMove = (state: ChartMouseState) => {
    const index = state?.activeTooltipIndex
    setActive(typeof index === 'number' && index >= 0 && index < data.length ? index : null)
  }
  const activeRow = active != null ? data[active] : null
  return (
    <div>
      <div className="tabular" style={{ ...tabular, minHeight: 20, fontSize: 12.5, color: V3.label2, marginBottom: 6 }}>
        {activeRow ? (
          <span>
            <span style={{ fontWeight: 600, color: V3.label }}>{activeRow.label}</span>
            {' · '}
            {fmtNum(activeRow.value, digits)} {unit}
          </span>
        ) : (
          summary ?? <span>&nbsp;</span>
        )}
      </div>
      <ResponsiveContainer width="100%" height={height}>
        <BarChart data={data} margin={{ top: 4, right: 6, bottom: 0, left: 0 }} onMouseMove={onMove} onMouseLeave={() => setActive(null)}>
          <CartesianGrid vertical={false} stroke={V3.separator} />
          <XAxis dataKey="label" tick={{ fontSize: 11, fill: V3.label3 }} axisLine={false} tickLine={false} interval={0} height={22} />
          <YAxis tick={{ fontSize: 11, fill: V3.label3 }} axisLine={false} tickLine={false} width={46} />
          <Tooltip content={() => null} cursor={{ fill: color, fillOpacity: 0.05 }} />
          <Bar dataKey="value" fill={color} radius={[6, 6, 0, 0]} maxBarSize={40} isAnimationActive={false} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

/* ------------------------- DailyActivityChart ------------------------ */

export function DailyActivityChart({ data, syncKey }: { data: { label: string; fullLabel: string; distanceKm: number; chargedKwh: number }[]; syncKey?: string }) {
  const rawId = useId().replace(/[^a-zA-Z0-9]/g, '')
  const syncId = syncKey ?? `daily-${rawId}`
  const [active, setActive] = useState<number | null>(null)
  const onMove = (state: ChartMouseState) => {
    const index = state?.activeTooltipIndex
    setActive(typeof index === 'number' && index >= 0 && index < data.length ? index : null)
  }
  const activeRow = active != null ? data[active] : null
  const totalDistance = data.reduce((sum, row) => sum + row.distanceKm, 0)
  const totalCharged = data.reduce((sum, row) => sum + row.chargedKwh, 0)
  const parts = [
    { key: 'distanceKm', title: '每日里程', unit: 'km', color: DATA_COLORS.graphite, height: 120 },
    { key: 'chargedKwh', title: '每日充入电量', unit: 'kWh', color: DATA_COLORS.teal, height: 100 },
  ] as const
  return (
    <div>
      <div className="tabular" style={{ ...tabular, minHeight: 20, fontSize: 12.5, color: V3.label2, marginBottom: 6 }}>
        {activeRow ? (
          <span>
            <span style={{ fontWeight: 600, color: V3.label }}>{activeRow.fullLabel}</span>
            {' · '}里程 {fmtNum(activeRow.distanceKm, 1)} km{' · '}充入 {fmtNum(activeRow.chargedKwh, 1)} kWh
          </span>
        ) : (
          <span>
            合计里程 <span style={{ fontWeight: 600, color: V3.label }}>{fmtNum(totalDistance, 1)} km</span>
            {' · '}合计充入 <span style={{ fontWeight: 600, color: V3.label }}>{fmtNum(totalCharged, 1)} kWh</span>
          </span>
        )}
      </div>
      {parts.map((part, index) => (
        <div key={part.key} style={{ marginTop: index === 0 ? 0 : 4 }}>
          <div style={{ fontSize: 12.5, fontWeight: 600, color: V3.label2, paddingLeft: 2, marginBottom: 2 }}>
            {part.title}
            <span style={{ color: V3.label3, fontWeight: 500 }}>（{part.unit}）</span>
          </div>
          <ResponsiveContainer width="100%" height={part.height}>
            <BarChart data={data} syncId={syncId} margin={{ top: 4, right: 6, bottom: 0, left: 0 }} onMouseMove={onMove} onMouseLeave={() => setActive(null)}>
              <CartesianGrid vertical={false} stroke={V3.separator} />
              <XAxis
                dataKey="label"
                tick={{ fontSize: 11, fill: V3.label3 }}
                axisLine={false}
                tickLine={false}
                interval={1}
                height={index === parts.length - 1 ? 22 : 0}
                hide={index !== parts.length - 1}
              />
              <YAxis tick={{ fontSize: 11, fill: V3.label3 }} axisLine={false} tickLine={false} width={46} />
              <Tooltip content={() => null} cursor={{ fill: part.color, fillOpacity: 0.05 }} />
              <Bar dataKey={part.key} fill={part.color} radius={[5, 5, 0, 0]} maxBarSize={30} isAnimationActive={false} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      ))}
    </div>
  )
}

/* ----------------------------- StatesBar ----------------------------- */

const SEGMENT_STYLE: Record<StateSegment['kind'], { bg: string; color: string; label: string }> = {
  drive: { bg: '#48484a', color: '#ffffff', label: '行驶' },
  charge: { bg: '#157a86', color: '#ffffff', label: '充电' },
  parked: { bg: '#ddd5c2', color: '#5c5648', label: '驻车' },
}

export function StatesBar({ segments, windowStart, windowEnd }: { segments: StateSegment[]; windowStart: number; windowEnd: number }) {
  const span = Math.max(1, windowEnd - windowStart)
  const totals = useMemo(() => {
    const map: Record<StateSegment['kind'], number> = { drive: 0, charge: 0, parked: 0 }
    for (const segment of segments) map[segment.kind] += segment.end - segment.start
    return map
  }, [segments])
  const hours = (ms: number) => `${(ms / 3_600_000).toFixed(1)} 小时`
  const ticks = useMemo(() => {
    const out: { at: number; label: string }[] = []
    const cursor = new Date(windowStart)
    cursor.setHours(0, 0, 0, 0)
    if (cursor.getTime() < windowStart) cursor.setDate(cursor.getDate() + 1)
    while (cursor.getTime() <= windowEnd && out.length < 16) {
      out.push({ at: cursor.getTime(), label: `${cursor.getMonth() + 1}/${cursor.getDate()}` })
      cursor.setDate(cursor.getDate() + 1)
    }
    return out
  }, [windowStart, windowEnd])
  return (
    <div>
      <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', fontSize: 12.5, color: V3.label2, marginBottom: 10 }}>
        {(Object.keys(SEGMENT_STYLE) as StateSegment['kind'][]).map(kind => (
          <span key={kind} style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
            <span style={{ width: 10, height: 10, borderRadius: 3, background: SEGMENT_STYLE[kind].bg }} />
            {SEGMENT_STYLE[kind].label}
            <span className="tabular" style={tabular}>{hours(totals[kind])}</span>
          </span>
        ))}
      </div>
      <div style={{ position: 'relative', height: 44, borderRadius: 12, overflow: 'hidden', background: SEGMENT_STYLE.parked.bg }}>
        {segments.map((segment, index) => {
          const style = SEGMENT_STYLE[segment.kind]
          const left = ((segment.start - windowStart) / span) * 100
          const width = Math.max(0.3, ((segment.end - segment.start) / span) * 100)
          return (
            <div
              key={index}
              title={`${style.label} · ${hhmm(segment.start)} – ${hhmm(segment.end)}`}
              style={{
                position: 'absolute', top: 0, bottom: 0, left: `${left}%`, width: `${width}%`,
                background: style.bg, display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden',
              }}
            >
              {width > 7 ? <span style={{ fontSize: 11.5, fontWeight: 600, color: style.color, whiteSpace: 'nowrap' }}>{style.label}</span> : null}
            </div>
          )
        })}
      </div>
      <div style={{ position: 'relative', height: 18, marginTop: 4 }}>
        {ticks.map((tick, index) => (
          <span
            key={index}
            className="tabular"
            style={{ ...tabular, position: 'absolute', left: `${((tick.at - windowStart) / span) * 100}%`, transform: 'translateX(-50%)', fontSize: 11, color: V3.label3, whiteSpace: 'nowrap' }}
          >
            {tick.label}
          </span>
        ))}
      </div>
    </div>
  )
}
