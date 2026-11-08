import { Fragment, useEffect, useRef, useState, type ReactNode } from 'react'
import { Area, CartesianGrid, ComposedChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Panel, V3 } from './v3'
import { DATA_COLORS, fmtNum } from './panels'
import type { WebCar } from '../api/matelink-types'
import type { HeatCell } from '../api/tm-data'

/**
 * Components for the TeslaMate panel reproduction: arc gauges in our
 * warm style, honest 待补数据 (missing-data) panels, data tables, the
 * AMap panel, and the composition widgets TeslaMate uses that panels.tsx
 * does not cover (donut shares, bar gauges, heatmap, range band).
 */

const tabular = { fontVariantNumeric: 'tabular-nums' } as const

/* ------------------------------ CarPicker ----------------------------- */

export function CarPicker({ cars, carId, onChange }: { cars: WebCar[]; carId: number | null; onChange: (id: number) => void }) {
  if (cars.length <= 1) return null
  return (
    <select
      value={carId ?? ''}
      onChange={event => onChange(Number(event.target.value))}
      style={{ padding: '7px 12px', borderRadius: 999, border: `0.5px solid ${V3.separator}`, background: V3.surface, fontSize: 13.5, color: V3.label, cursor: 'pointer' }}
      aria-label="选择车辆"
    >
      {cars.map(car => (
        <option key={car.id} value={car.id}>{car.name}</option>
      ))}
    </select>
  )
}

/* ------------------------------ GaugeArc ------------------------------ */

/**
 * Arc gauge in the TeslaMate Overview style (240° sweep), redrawn on
 * our warm surface. When the value is missing the arc stays empty and
 * the panel says 待补数据 with the reason — never a fabricated zero.
 */
export function GaugeArc({
  label,
  value,
  min = 0,
  max = 100,
  unit,
  digits = 0,
  gapReason,
}: {
  label: string
  value: number | null
  min?: number
  max?: number
  unit: string
  digits?: number
  gapReason?: string
}) {
  const size = 132
  const stroke = 11
  const radius = (size - stroke) / 2 - 2
  const center = size / 2
  const startAngle = -210
  const sweep = 240
  const polar = (angleDeg: number) => {
    const rad = ((angleDeg - 90) * Math.PI) / 180
    return { x: center + radius * Math.cos(rad), y: center + radius * Math.sin(rad) }
  }
  const arc = (from: number, to: number) => {
    const s = polar(from)
    const e = polar(to)
    const large = to - from > 180 ? 1 : 0
    return `M ${s.x.toFixed(2)} ${s.y.toFixed(2)} A ${radius} ${radius} 0 ${large} 1 ${e.x.toFixed(2)} ${e.y.toFixed(2)}`
  }
  const ratio = value == null ? 0 : Math.max(0, Math.min(1, (value - min) / (max - min || 1)))
  return (
    <div style={{ background: V3.surface, borderRadius: 20, padding: '13px 15px', boxShadow: V3.cardShadow, border: `0.5px solid ${V3.separator}`, display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
      <div style={{ fontSize: 12.5, fontWeight: 600, color: V3.label3, alignSelf: 'flex-start' }}>{label}</div>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} role="img" aria-label={label}>
        <path d={arc(startAngle, startAngle + sweep)} fill="none" stroke="rgba(60,50,35,0.10)" strokeWidth={stroke} strokeLinecap="round" />
        {value != null && ratio > 0 ? (
          <path d={arc(startAngle, startAngle + sweep * ratio)} fill="none" stroke={DATA_COLORS.teal} strokeWidth={stroke} strokeLinecap="round" />
        ) : null}
        {value != null ? (
          <>
            <text x={center} y={center + 4} textAnchor="middle" fontSize={27} fontWeight={700} fill={V3.label} style={tabular}>
              {fmtNum(value, digits)}
            </text>
            <text x={center} y={center + 24} textAnchor="middle" fontSize={12} fill={V3.label2}>{unit}</text>
          </>
        ) : (
          <text x={center} y={center + 8} textAnchor="middle" fontSize={14} fontWeight={600} fill={V3.label3}>待补数据</text>
        )}
      </svg>
      {value == null && gapReason ? (
        <div style={{ fontSize: 11.5, color: V3.label3, lineHeight: 1.5, textAlign: 'center', marginTop: -6 }}>{gapReason}</div>
      ) : null}
    </div>
  )
}

/* --------------------------- Gap （待补数据） --------------------------- */

/**
 * Owner option A: a panel that exists in TeslaMate's composition but
 * whose data our backend does not store yet. It shows the parameter,
 * what it means, and exactly what the backend would need to record.
 */
export function GapPanel({ title, meaning, need, height }: { title: string; meaning: string; need: string; height?: number }) {
  return (
    <section
      style={{
        background: V3.surface2, borderRadius: 20, padding: 17, minHeight: height,
        border: `1px dashed rgba(60,50,35,0.25)`, display: 'flex', flexDirection: 'column',
      }}
    >
      <h2 style={{ fontSize: 17, fontWeight: 600, color: V3.label2 }}>{title}</h2>
      <div style={{ marginTop: 8, fontSize: 13, color: V3.label2, lineHeight: 1.6 }}>{meaning}</div>
      <div style={{ marginTop: 'auto', paddingTop: 10, fontSize: 12.5, color: V3.label3, lineHeight: 1.6 }}>
        <span style={{ fontWeight: 700, color: V3.warningText }}>待补数据</span>：需要后端记录 {need}。
      </div>
    </section>
  )
}

/** Tile-shaped gap for stat rows (same footprint as StatTile). */
export function GapTile({ label, need }: { label: string; need: string }) {
  return (
    <div style={{ background: V3.surface2, borderRadius: 20, padding: '15px 17px', border: `1px dashed rgba(60,50,35,0.25)`, display: 'flex', flexDirection: 'column', justifyContent: 'center' }}>
      <div style={{ fontSize: 12.5, fontWeight: 600, color: V3.label3 }}>{label}</div>
      <div style={{ marginTop: 5, fontSize: 15, fontWeight: 600, color: V3.label3 }}>待补数据</div>
      <div style={{ fontSize: 11.5, color: V3.label3, marginTop: 5, lineHeight: 1.5 }}>需要后端记录 {need}</div>
    </div>
  )
}

/* ------------------------------ DataTable ----------------------------- */

export function DataTable({
  columns,
  rows,
  emptyText = '暂无数据',
}: {
  columns: { label: string; align?: 'left' | 'right' | 'center' }[]
  rows: ReactNode[][]
  emptyText?: string
}) {
  return (
    <div style={{ overflowX: 'auto' }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13.5 }}>
        <thead>
          <tr>
            {columns.map((col, i) => (
              <th key={i} style={{ textAlign: col.align ?? 'left', padding: '8px 10px', color: V3.label3, fontWeight: 600, fontSize: 12.5, borderBottom: `0.5px solid ${V3.separator}`, whiteSpace: 'nowrap' }}>
                {col.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr>
              <td colSpan={columns.length} style={{ padding: '18px 10px', color: V3.label3, textAlign: 'center' }}>{emptyText}</td>
            </tr>
          ) : (
            rows.map((row, i) => (
              <tr key={i}>
                {row.map((cell, j) => (
                  <td key={j} className="tabular" style={{ ...tabular, padding: '8px 10px', color: V3.label, borderBottom: `0.5px solid ${V3.separator}`, textAlign: columns[j]?.align ?? 'left', whiteSpace: 'nowrap' }}>
                    {cell}
                  </td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  )
}

/* ------------------------------- MapPanel ------------------------------ */

export type MapPoint = { lat: number; lng: number; label?: string }
export type MapLine = { lat: number; lng: number }

const viteEnv = (import.meta as unknown as { env?: Record<string, string | undefined> }).env ?? {}
export const AMAP_KEY = viteEnv.VITE_AMAP_KEY ?? ''

/** WGS-84 → GCJ-02 (the coordinate system AMap tiles use), standard public algorithm. */
export function wgs84ToGcj02(lat: number, lng: number): { lat: number; lng: number } {
  const a = 6378245.0
  const ee = 0.00669342162296594323
  const transformLat = (x: number, y: number) => {
    let ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
    ret += ((20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0) / 3.0
    ret += ((20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin((y / 3.0) * Math.PI)) * 2.0) / 3.0
    ret += ((160.0 * Math.sin((y / 12.0) * Math.PI) + 320 * Math.sin((y * Math.PI) / 30.0)) * 2.0) / 3.0
    return ret
  }
  const transformLng = (x: number, y: number) => {
    let ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
    ret += ((20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0) / 3.0
    ret += ((20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin((x / 3.0) * Math.PI)) * 2.0) / 3.0
    ret += ((150.0 * Math.sin((x / 12.0) * Math.PI) + 300.0 * Math.sin((x / 30.0) * Math.PI)) * 2.0) / 3.0
    return ret
  }
  const dLat = transformLat(lng - 105.0, lat - 35.0)
  const dLng = transformLng(lng - 105.0, lat - 35.0)
  const radLat = (lat / 180.0) * Math.PI
  let magic = Math.sin(radLat)
  magic = 1 - ee * magic * magic
  const sqrtMagic = Math.sqrt(magic)
  const mgLat = lat + (dLat * 180.0) / (((a * (1 - ee)) / (magic * sqrtMagic)) * Math.PI)
  const mgLng = lng + (dLng * 180.0) / ((a / sqrtMagic) * Math.cos(radLat) * Math.PI)
  return { lat: mgLat, lng: mgLng }
}

type AmapWindow = Window & { AMap?: unknown; _AMapSecurityConfig?: unknown }
let amapLoader: Promise<unknown> | null = null
function loadAmap(key: string): Promise<unknown> {
  const w = window as AmapWindow
  if (w.AMap) return Promise.resolve(w.AMap)
  if (!amapLoader) {
    amapLoader = new Promise((resolve, reject) => {
      const script = document.createElement('script')
      script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}`
      script.onload = () => resolve((window as AmapWindow).AMap)
      script.onerror = () => reject(new Error('amap_load_failed'))
      document.head.appendChild(script)
    })
  }
  return amapLoader
}

/**
 * Map panel. With an AMap Web JS key configured (VITE_AMAP_KEY) it
 * renders the real map with the polyline and points (converted to
 * GCJ-02); without a key it renders a clean placeholder panel listing
 * the points — the data exists, only the tile key is missing. No key
 * is ever hardcoded.
 */
export function MapPanel({
  title = '地图',
  points,
  polyline,
  height = 300,
}: {
  title?: string
  points: MapPoint[]
  polyline?: MapLine[]
  height?: number
}) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const [failed, setFailed] = useState(false)
  const usable = points.filter(p => Number.isFinite(p.lat) && Number.isFinite(p.lng))
  const line = (polyline ?? []).filter(p => Number.isFinite(p.lat) && Number.isFinite(p.lng))

  useEffect(() => {
    if (!AMAP_KEY || !containerRef.current || (!usable.length && !line.length)) return
    let destroyed = false
    let map: { add: (x: unknown) => void; setFitView: () => void; destroy?: () => void } | null = null
    loadAmap(AMAP_KEY)
      .then(AMapUnknown => {
        if (destroyed || !containerRef.current) return
        const AMap = AMapUnknown as Record<string, never> & {
          Map: new (el: HTMLElement, opts: Record<string, unknown>) => { add: (x: unknown) => void; setFitView: () => void; destroy: () => void }
          Marker: new (opts: Record<string, unknown>) => unknown
          Polyline: new (opts: Record<string, unknown>) => unknown
          LngLat: new (lng: number, lat: number) => unknown
        }
        const toLngLat = (p: MapPoint | MapLine) => {
          const g = wgs84ToGcj02(p.lat, p.lng)
          return new AMap.LngLat(g.lng, g.lat)
        }
        const center = usable[0] ?? line[0]
        const centerG = wgs84ToGcj02(center.lat, center.lng)
        map = new AMap.Map(containerRef.current, { zoom: 13, center: [centerG.lng, centerG.lat] })
        const overlays: unknown[] = []
        if (line.length >= 2) {
          overlays.push(new AMap.Polyline({ path: line.map(toLngLat), strokeColor: '#157a86', strokeWeight: 4, strokeOpacity: 0.9 }))
        }
        for (const point of usable) {
          overlays.push(new AMap.Marker({ position: toLngLat(point), title: point.label ?? '' }))
        }
        for (const overlay of overlays) map.add(overlay)
        if (overlays.length) map.setFitView()
      })
      .catch(() => setFailed(true))
    return () => {
      destroyed = true
      map?.destroy?.()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [AMAP_KEY, usable.length, line.length])

  if (!usable.length && line.length < 2) {
    return (
      <Panel title={title}>
        <div style={{ fontSize: 13, color: V3.label3, lineHeight: 1.6 }}>暂无可定位的坐标数据。</div>
      </Panel>
    )
  }
  if (AMAP_KEY && !failed) {
    return (
      <Panel title={title}>
        <div ref={containerRef} style={{ width: '100%', height, borderRadius: 14, overflow: 'hidden' }} />
        <div style={{ fontSize: 11.5, color: V3.label3, marginTop: 8 }}>底图为高德地图，坐标已按 GCJ-02 转换。</div>
      </Panel>
    )
  }
  return (
    <Panel title={title}>
      <div style={{ borderRadius: 14, background: V3.surface2, padding: '14px 16px' }}>
        <div style={{ fontSize: 13.5, fontWeight: 600, color: V3.label2 }}>地图待接入</div>
        <div style={{ fontSize: 12.5, color: V3.label3, lineHeight: 1.6, marginTop: 4 }}>
          轨迹与点位坐标已就绪；配置高德 Web JS key（VITE_AMAP_KEY）后即可显示底图与轨迹线。当前先以点位列表呈现：
        </div>
        <ul style={{ margin: '10px 0 0', padding: 0, listStyle: 'none', display: 'flex', flexDirection: 'column', gap: 6 }}>
          {usable.slice(0, 8).map((point, i) => (
            <li key={i} style={{ fontSize: 12.5, color: V3.label2, display: 'flex', justifyContent: 'space-between', gap: 12 }}>
              <span>{point.label ?? `点位 ${i + 1}`}</span>
              <span className="tabular" style={tabular}>{point.lat.toFixed(5)}, {point.lng.toFixed(5)}</span>
            </li>
          ))}
          {line.length >= 2 ? (
            <li style={{ fontSize: 12.5, color: V3.label2 }}>行驶轨迹：共 {line.length} 个轨迹点</li>
          ) : null}
        </ul>
      </div>
    </Panel>
  )
}

/* ----------------------------- DonutShares ----------------------------- */

export function DonutShares({
  parts,
  unit,
  size = 148,
}: {
  parts: { label: string; value: number; color: string }[]
  unit: string
  size?: number
}) {
  const total = parts.reduce((sum, p) => sum + Math.max(0, p.value), 0)
  const radius = (size - 22) / 2
  const center = size / 2
  let cursor = -Math.PI / 2
  const arcs = parts
    .filter(p => p.value > 0 && total > 0)
    .map(p => {
      const angle = (p.value / total) * Math.PI * 2
      const from = cursor
      cursor += angle
      const large = angle > Math.PI ? 1 : 0
      const x0 = center + radius * Math.cos(from)
      const y0 = center + radius * Math.sin(from)
      const x1 = center + radius * Math.cos(cursor - 0.001)
      const y1 = center + radius * Math.sin(cursor - 0.001)
      return { ...p, path: `M ${x0.toFixed(2)} ${y0.toFixed(2)} A ${radius} ${radius} 0 ${large} 1 ${x1.toFixed(2)} ${y1.toFixed(2)}` }
    })
  return (
    <div style={{ display: 'flex', gap: 18, alignItems: 'center', flexWrap: 'wrap' }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} role="img" aria-label="构成">
        <circle cx={center} cy={center} r={radius} fill="none" stroke="rgba(60,50,35,0.08)" strokeWidth={17} />
        {arcs.map((arc, i) => (
          <path key={i} d={arc.path} fill="none" stroke={arc.color} strokeWidth={17} />
        ))}
        <text x={center} y={center + 5} textAnchor="middle" fontSize={16} fontWeight={700} fill={V3.label} style={tabular}>
          {fmtNum(total, 1)}
        </text>
        <text x={center} y={center + 22} textAnchor="middle" fontSize={11} fill={V3.label3}>{unit} 合计</text>
      </svg>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8, minWidth: 130 }}>
        {parts.map((part, i) => (
          <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: V3.label2 }}>
            <span style={{ width: 10, height: 10, borderRadius: 3, background: part.color, flexShrink: 0 }} />
            <span>{part.label}</span>
            <span className="tabular" style={{ ...tabular, marginLeft: 'auto', fontWeight: 600, color: V3.label }}>
              {fmtNum(part.value, 1)} {unit}
              {total > 0 ? <span style={{ color: V3.label3, fontWeight: 500 }}> · {((part.value / total) * 100).toFixed(0)}%</span> : null}
            </span>
          </div>
        ))}
        {total <= 0 ? <div style={{ fontSize: 12.5, color: V3.label3 }}>暂无可统计的数据。</div> : null}
      </div>
    </div>
  )
}

/* ----------------------------- BarGaugeList ---------------------------- */

export function BarGaugeList({
  items,
  color = DATA_COLORS.teal,
}: {
  items: { label: string; value: number; display: string }[]
  color?: string
}) {
  const max = Math.max(0, ...items.map(item => item.value))
  if (!items.length) return <div style={{ fontSize: 13, color: V3.label3 }}>暂无数据。</div>
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      {items.map((item, i) => (
        <div key={i}>
          <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, fontSize: 13, marginBottom: 4 }}>
            <span style={{ color: V3.label2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{item.label}</span>
            <span className="tabular" style={{ ...tabular, fontWeight: 600, color: V3.label }}>{item.display}</span>
          </div>
          <div style={{ height: 8, borderRadius: 4, background: 'rgba(60,50,35,0.08)', overflow: 'hidden' }}>
            <div style={{ height: '100%', width: max > 0 ? `${(item.value / max) * 100}%` : '0%', background: color, borderRadius: 4 }} />
          </div>
        </div>
      ))}
    </div>
  )
}

/* ----------------------------- HeatmapGrid ----------------------------- */

const WEEKDAY_LABELS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

export function HeatmapGrid({ grid, metric }: { grid: HeatCell[][]; metric: 'count' | 'kwh' }) {
  const [active, setActive] = useState<{ day: number; hour: number } | null>(null)
  const max = Math.max(0, ...grid.flat().map(cell => (metric === 'count' ? cell.count : cell.kwh)))
  const cellOf = active ? grid[active.day]?.[active.hour] : null
  return (
    <div>
      <div className="tabular" style={{ ...tabular, minHeight: 20, fontSize: 12.5, color: V3.label2, marginBottom: 8 }}>
        {active && cellOf ? (
          <span>
            <span style={{ fontWeight: 600, color: V3.label }}>{WEEKDAY_LABELS[active.day]} {active.hour} 时</span>
            {' · '}充电 {cellOf.count} 次 · 充入 {fmtNum(cellOf.kwh, 1)} kWh
          </span>
        ) : (
          <span style={{ color: V3.label3 }}>颜色越深表示该时段充电越多（按{metric === 'count' ? '次数' : '电量'}）</span>
        )}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '34px repeat(24, 1fr)', gap: 3, alignItems: 'center' }}>
        <span />
        {Array.from({ length: 24 }, (_, hour) => (
          <span key={hour} style={{ fontSize: 9.5, color: V3.label3, textAlign: 'center' }}>{hour % 6 === 0 ? hour : ''}</span>
        ))}
        {grid.map((row, day) => (
          <Fragment key={day}>
            <span style={{ fontSize: 11, color: V3.label3 }}>{WEEKDAY_LABELS[day]}</span>
            {row.map((cell, hour) => {
              const value = metric === 'count' ? cell.count : cell.kwh
              const intensity = max > 0 ? value / max : 0
              return (
                <div
                  key={hour}
                  onMouseEnter={() => setActive({ day, hour })}
                  onMouseLeave={() => setActive(null)}
                  title={`${WEEKDAY_LABELS[day]} ${hour} 时`}
                  style={{
                    height: 15, borderRadius: 4,
                    background: intensity > 0 ? `rgba(21, 122, 134, ${0.12 + intensity * 0.83})` : 'rgba(60,50,35,0.05)',
                  }}
                />
              )
            })}
          </Fragment>
        ))}
      </div>
    </div>
  )
}

/* ------------------------------ RangeBand ------------------------------ */

/** Daily min/max band （温度包络）: base area invisible + span area translucent. */
export function RangeBand({ data, unit, color = DATA_COLORS.teal, height = 200 }: { data: { t: number; min: number; max: number }[]; unit: string; color?: string; height?: number }) {
  const rows = data.map(d => ({ t: d.t, base: d.min, span: Math.max(0, d.max - d.min), min: d.min, max: d.max }))
  const [active, setActive] = useState<number | null>(null)
  const onMove = (state: { activeTooltipIndex?: unknown } | null) => {
    const index = state?.activeTooltipIndex
    setActive(typeof index === 'number' && index >= 0 && index < rows.length ? index : null)
  }
  const row = active != null ? rows[active] : null
  const fmtDay = (t: number) => {
    const d = new Date(t)
    return `${d.getMonth() + 1}/${d.getDate()}`
  }
  return (
    <div>
      <div className="tabular" style={{ ...tabular, minHeight: 20, fontSize: 12.5, color: V3.label2, marginBottom: 6 }}>
        {row ? (
          <span>
            <span style={{ fontWeight: 600, color: V3.label }}>{fmtDay(row.t)}</span>
            {' · '}最低 {fmtNum(row.min, 1)}{unit} · 最高 {fmtNum(row.max, 1)}{unit}
          </span>
        ) : (
          <span style={{ color: V3.label3 }}>色带为每日最低至最高{unit}</span>
        )}
      </div>
      <ResponsiveContainer width="100%" height={height}>
        <ComposedChart data={rows} margin={{ top: 4, right: 8, bottom: 0, left: 0 }} onMouseMove={onMove} onMouseLeave={() => setActive(null)}>
          <CartesianGrid vertical={false} stroke={V3.separator} />
          <XAxis dataKey="t" type="number" domain={['dataMin', 'dataMax']} tickFormatter={(v: number) => fmtDay(v)} tick={{ fontSize: 11, fill: V3.label3 }} axisLine={false} tickLine={false} height={22} minTickGap={50} />
          <YAxis tick={{ fontSize: 11, fill: V3.label3 }} axisLine={false} tickLine={false} width={40} />
          <Tooltip content={() => null} cursor={{ stroke: V3.label3, strokeWidth: 1 }} />
          <Area type="linear" dataKey="base" stackId="band" stroke="none" fill="transparent" isAnimationActive={false} />
          <Area type="linear" dataKey="span" stackId="band" stroke={color} strokeWidth={1.2} fill={color} fillOpacity={0.22} isAnimationActive={false} />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  )
}

/* ------------------------------ PageShell ------------------------------ */

export function PageTitle({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 14, marginBottom: 16, flexWrap: 'wrap' }}>
      <h1 style={{ fontSize: 26, fontWeight: 700, color: V3.label }}>{title}</h1>
      <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 10 }}>{children}</div>
    </div>
  )
}

export function LoadingNote({ text = '正在加载数据…' }: { text?: string }) {
  return <div style={{ padding: '34px 0', textAlign: 'center', color: V3.label3, fontSize: 14 }}>{text}</div>
}

export function ErrorNote({ text }: { text: string }) {
  return (
    <div style={{ background: V3.errorSoft, color: V3.errorText, borderRadius: 16, padding: '13px 16px', fontSize: 13.5, lineHeight: 1.6 }}>{text}</div>
  )
}

export { Panel }

/* ---------------------------- SocStitchChart --------------------------- */

import { Line, LineChart } from 'recharts'
import type { SocSegment } from '../api/tm-data'

/**
 * SOC history rendered from stitched segments: real charge samples are
 * solid lines, boundary-only segments (drives, unfetched charges) are
 * dashed to mark them as stitches, and parked intervals stay blank.
 */
export function SocStitchChart({ segments, height = 260 }: { segments: SocSegment[]; height?: number }) {
  const [active, setActive] = useState<number | null>(null)
  const times = new Set<number>()
  for (const segment of segments) for (const point of segment.points) times.add(point.t)
  const sorted = [...times].sort((a, b) => a - b)
  const data = sorted.map(t => {
    const row: { t: number } & Record<string, number | null> = { t }
    segments.forEach((segment, i) => {
      const hit = segment.points.find(p => p.t === t)
      row[`seg${i}`] = hit ? hit.soc : null
    })
    return row
  })
  const fmtTime = (t: number) => {
    const d = new Date(t)
    return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  }
  const activeRow = active != null ? data[active] : null
  const activeSoc = activeRow ? segments.map((_, i) => activeRow[`seg${i}`]).find(v => v != null) : null
  return (
    <div>
      <div className="tabular" style={{ ...tabular, minHeight: 20, fontSize: 12.5, color: V3.label2, marginBottom: 6 }}>
        {activeRow && activeSoc != null ? (
          <span>
            <span style={{ fontWeight: 600, color: V3.label }}>{fmtTime(activeRow.t)}</span>
            {' · '}电量 {fmtNum(activeSoc)}%
          </span>
        ) : (
          <span style={{ color: V3.label3 }}>实线为充电采样，虚线为行程起止电量拼接，空白为驻车时段</span>
        )}
      </div>
      <ResponsiveContainer width="100%" height={height}>
        <LineChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: 0 }} onMouseMove={state => {
          const index = (state as { activeTooltipIndex?: unknown } | null)?.activeTooltipIndex
          setActive(typeof index === 'number' && index >= 0 && index < data.length ? index : null)
        }} onMouseLeave={() => setActive(null)}>
          <CartesianGrid vertical={false} stroke={V3.separator} />
          <XAxis dataKey="t" type="number" domain={['dataMin', 'dataMax']} tickFormatter={(v: number) => fmtTime(v)} tick={{ fontSize: 11, fill: V3.label3 }} axisLine={false} tickLine={false} height={22} minTickGap={60} />
          <YAxis domain={[0, 100]} tick={{ fontSize: 11, fill: V3.label3 }} axisLine={false} tickLine={false} width={40} tickFormatter={(v: number) => `${v}%`} />
          <Tooltip content={() => null} cursor={{ stroke: V3.label3, strokeWidth: 1 }} />
          {segments.map((segment, i) => (
            <Line
              key={i}
              type="linear"
              dataKey={`seg${i}`}
              stroke={DATA_COLORS.teal}
              strokeWidth={2}
              strokeDasharray={segment.approx ? '5 4' : undefined}
              dot={false}
              activeDot={{ r: 3.5, fill: DATA_COLORS.teal, stroke: '#fff', strokeWidth: 1.5 }}
              connectNulls
              isAnimationActive={false}
            />
          ))}
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}
