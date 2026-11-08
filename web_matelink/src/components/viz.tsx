import { useEffect, useId, useState, type ReactNode } from 'react'
import { Area, AreaChart, Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

/**
 * MateLink Web visual component library (design system v2, see
 * docs/WEB-DESIGN.md). Apple-HIG-inspired: rings, tiles, gradient
 * charts, semantic tones. Pages consume these; do not fork them.
 */

export const TONES = {
  teal: '#157a86',
  green: '#34c759',
  blue: '#0a84ff',
  amber: '#ff9f0a',
  red: '#ff3b30',
  purple: '#bf5af2',
  gray: '#8e8e93',
} as const
export type Tone = keyof typeof TONES

/* ------------------------------ Icon ------------------------------ */

const PATHS: Record<string, ReactNode> = {
  gauge: (<><path d="M4 14a8 8 0 1 1 16 0" /><path d="M12 14l4.5-4.5" /><path d="M2.5 14h2M19.5 14h2M12 4.5v2" /></>),
  bolt: (<path d="M13 2.5L4.5 13.5H11L9.8 21.5L19 10h-6.5L13 2.5z" />),
  route: (<><circle cx="6" cy="19" r="2.2" /><circle cx="18" cy="5" r="2.2" /><path d="M8 19h7a3.5 3.5 0 0 0 0-7h-6a3.5 3.5 0 0 1 0-7h1" /></>),
  remote: (<><path d="M12 20h.01" /><path d="M8.5 16.4a5 5 0 0 1 7 0" /><path d="M5.6 13.5a9.5 9.5 0 0 1 12.8 0" /><path d="M2.7 10.6a14 14 0 0 1 18.6 0" /></>),
  chart: (<><path d="M4 4v16h16" /><path d="M8 15l3.5-4 3 2.5L19 8" /></>),
  person: (<><circle cx="12" cy="8" r="3.6" /><path d="M5 20c1.2-3.2 3.8-4.8 7-4.8s5.8 1.6 7 4.8" /></>),
  lock: (<><rect x="5.5" y="10.5" width="13" height="9.5" rx="2.5" /><path d="M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5" /><path d="M12 15.2v1.6" /></>),
  unlock: (<><rect x="5.5" y="10.5" width="13" height="9.5" rx="2.5" /><path d="M8.5 10.5V8a3.5 3.5 0 0 1 6.8-1.2" /><path d="M12 15.2v1.6" /></>),
  zap: (<path d="M13 2.5L4.5 13.5H11L9.8 21.5L19 10h-6.5L13 2.5z" />),
  speaker: (<><path d="M4 9.5v5h3.5L13 19V5L7.5 9.5H4z" /><path d="M16.5 9a4.2 4.2 0 0 1 0 6M19 6.5a8 8 0 0 1 0 11" /></>),
  climate: (<><path d="M12 3v18M12 3l-2 2.5M12 3l2 2.5M12 21l-2-2.5M12 21l2-2.5M4.2 7.5l15.6 9M4.2 7.5L4 10.8M4.2 7.5l3.3.8M19.8 16.5l.2-3.3M19.8 16.5l-3.3-.8M19.8 7.5l-15.6 9M19.8 7.5l.2 3.3M19.8 7.5l-3.3.8M4.2 16.5l-.2-3.3M4.2 16.5l3.3-.8" /></>),
  plug: (<><path d="M8.5 3v5M15.5 3v5" /><path d="M6 8h12v3a6 6 0 0 1-12 0V8z" /><path d="M12 17v4" /></>),
  play: (<path d="M8 5.5v13l11-6.5-11-6.5z" />),
  stop: (<rect x="6.5" y="6.5" width="11" height="11" rx="2" />),
  window: (<><rect x="4" y="5" width="16" height="13" rx="2.5" /><path d="M12 5v13M4 11.5h16" /></>),
  shield: (<><path d="M12 3l7 2.8v5.4c0 4.6-3 7.7-7 9.3-4-1.6-7-4.7-7-9.3V5.8L12 3z" /><path d="M9.5 11.8l2 2 3.5-3.8" /></>),
  pin: (<><path d="M12 21s-6.5-5.4-6.5-10.4a6.5 6.5 0 0 1 13 0C18.5 15.6 12 21 12 21z" /><circle cx="12" cy="10.3" r="2.3" /></>),
  nav: (<path d="M21 3L10.2 13.8M21 3l-6.8 18-3.4-7.6L3 10 21 3z" />),
  battery: (<><rect x="2.5" y="8" width="17" height="9" rx="2.5" /><path d="M22 11.5v2" /><rect x="4.5" y="10" width="9" height="5" rx="1.2" fill="currentColor" stroke="none" /></>),
  thermo: (<><path d="M10 4a2 2 0 0 1 4 0v9.3a4.2 4.2 0 1 1-4 0V4z" /><circle cx="12" cy="17.5" r="1.6" /></>),
  calendar: (<><rect x="3.5" y="5" width="17" height="16" rx="2.5" /><path d="M3.5 10h17M8 3v4M16 3v4" /></>),
  download: (<><path d="M12 4v11M7.5 11l4.5 4.5L16.5 11" /><path d="M4.5 19.5h15" /></>),
  logout: (<><path d="M14 4h-6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h6" /><path d="M10.5 12H21M17.5 8l3.5 4-3.5 4" /></>),
  refresh: (<><path d="M20 12a8 8 0 1 1-2.3-5.6" /><path d="M20.5 3.5v4h-4" /></>),
  chevron: (<path d="M9.5 5.5L16 12l-6.5 6.5" />),
  sun: (<><circle cx="12" cy="12" r="3.6" /><path d="M12 2.8v2.2M12 19v2.2M2.8 12H5M19 12h2.2M5 5l1.6 1.6M17.4 17.4L19 19M19 5l-1.6 1.6M6.6 17.4L5 19" /></>),
  car: (<><path d="M4 16.5l1.6-6A3 3 0 0 1 8.5 8.3h7a3 3 0 0 1 2.9 2.2l1.6 6" /><path d="M3 16.5h18a1 1 0 0 1 1 1V19a1 1 0 0 1-1 1h-1.5a1 1 0 0 1-1-1v-.5h-13V19a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1v-1.5a1 1 0 0 1 1-1z" /><path d="M7.5 12.5h.01M16.5 12.5h.01" /></>),
  key: (<><circle cx="8" cy="14.5" r="4.5" /><path d="M11.5 11L20 2.5M16.5 6l3 3M14 8.5l2.2 2.2" /></>),
  clock: (<><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 2" /></>),
  sliders: (<><path d="M5 7h9M18 7h1" /><circle cx="16" cy="7" r="2.2" /><path d="M3 12h3M10 12h11" /><circle cx="8" cy="12" r="2.2" /><path d="M5 17h11M20 17h1" /><circle cx="18" cy="17" r="2.2" /></>),
  alert: (<><path d="M12 4L2.8 20h18.4L12 4z" /><path d="M12 10v4.2" /><path d="M12 17.2h.01" /></>),
  info: (<><circle cx="12" cy="12" r="8.5" /><path d="M12 11v5" /><path d="M12 7.8h.01" /></>),
  check: (<><circle cx="12" cy="12" r="8.5" /><path d="M8.8 12.4l2.2 2.2 4.2-4.6" /></>),
  external: (<><path d="M14 4h6v6" /><path d="M20 4L11 13" /><path d="M19 13.5V19a1.5 1.5 0 0 1-1.5 1.5h-12A1.5 1.5 0 0 1 4 19V7a1.5 1.5 0 0 1 1.5-1.5H11" /></>),
  eye: (<><path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z" /><circle cx="12" cy="12" r="3" /></>),
  moon: (<path d="M20 13.5A8.5 8.5 0 1 1 10.5 4a6.8 6.8 0 0 0 9.5 9.5z" />),
  wallet: (<><path d="M3.5 7a2 2 0 0 1 2-2h12a1.5 1.5 0 0 1 0 3" /><path d="M3.5 7v10.5a2 2 0 0 0 2 2h14a1 1 0 0 0 1-1v-9a1 1 0 0 0-1-1h-14" /><path d="M16.8 14h.01" /></>),
}

export type IconName = keyof typeof PATHS

export function Icon({ name, size = 20, strokeWidth = 1.7 }: { name: IconName; size?: number; strokeWidth?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      {PATHS[name] ?? null}
    </svg>
  )
}

/* ------------------------------ Ring ------------------------------ */

export function Ring({
  value,
  size = 168,
  thickness = 13,
  label,
  sub,
  tone = 'teal',
}: {
  value: number // 0..100
  size?: number
  thickness?: number
  label?: string
  sub?: string
  tone?: Tone
}) {
  const [shown, setShown] = useState(0)
  useEffect(() => {
    const frame = requestAnimationFrame(() => setShown(Math.max(0, Math.min(100, value))))
    return () => cancelAnimationFrame(frame)
  }, [value])
  const gradientId = useId().replace(/[^a-zA-Z0-9]/g, '')
  const radius = (size - thickness) / 2
  const circumference = 2 * Math.PI * radius
  const color = TONES[tone]
  return (
    <div style={{ position: 'relative', width: size, height: size }}>
      <svg width={size} height={size} style={{ transform: 'rotate(-90deg)' }}>
        <defs>
          <linearGradient id={`ring-${gradientId}`} x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor={color} />
            <stop offset="100%" stopColor={tone === 'teal' ? TONES.green : color} stopOpacity="0.75" />
          </linearGradient>
        </defs>
        <circle cx={size / 2} cy={size / 2} r={radius} fill="none" stroke="var(--apple-surface-tertiary)" strokeWidth={thickness} />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke={`url(#ring-${gradientId})`}
          strokeWidth={thickness}
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={circumference * (1 - shown / 100)}
          style={{ transition: 'stroke-dashoffset 700ms cubic-bezier(0.22, 1, 0.36, 1)' }}
        />
      </svg>
      <div style={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
        <div className="tabular" style={{ fontSize: size * 0.26, fontWeight: 700, lineHeight: 1 }}>
          {Math.round(shown)}
          <span style={{ fontSize: size * 0.11, fontWeight: 600, color: 'var(--apple-label-secondary)' }}>%</span>
        </div>
        {label ? <div style={{ fontSize: 13, fontWeight: 600, marginTop: 5 }}>{label}</div> : null}
        {sub ? <div className="text-tertiary tabular" style={{ fontSize: 12, marginTop: 2 }}>{sub}</div> : null}
      </div>
    </div>
  )
}

/* --------------------------- BatteryPill --------------------------- */

export function BatteryPill({ from, to, width = 92 }: { from: number | null; to: number | null; width?: number }) {
  if (from == null || to == null) return <span className="text-tertiary">--</span>
  const delta = to - from
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
      <span style={{ position: 'relative', display: 'inline-block', width, height: 10, borderRadius: 5, background: 'var(--apple-surface-tertiary)', overflow: 'hidden' }}>
        <span style={{ position: 'absolute', inset: 0, width: `${Math.min(100, Math.max(0, to))}%`, background: delta >= 0 ? '#157a86' : '#ff9f0a', borderRadius: 5, transition: 'width 600ms ease' }} />
        <span style={{ position: 'absolute', top: 0, bottom: 0, left: `${Math.min(100, Math.max(0, from))}%`, width: 2, background: 'rgba(255,255,255,0.85)' }} />
      </span>
      <span className="tabular text-secondary" style={{ fontSize: 12.5 }}>
        {Math.round(from)}% → {Math.round(to)}%
      </span>
    </span>
  )
}

/* ------------------------------ Spark ------------------------------ */

export function Spark({ data, tone = 'teal', width = 96, height = 30 }: { data: number[]; tone?: Tone; width?: number; height?: number }) {
  const values = data.filter(value => Number.isFinite(value))
  if (values.length < 2) return <span className="text-tertiary" style={{ fontSize: 12 }}>—</span>
  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = max - min || 1
  const points = values.map((value, index) => `${(index / (values.length - 1)) * width},${height - 3 - ((value - min) / span) * (height - 6)}`).join(' ')
  const color = TONES[tone]
  return (
    <svg width={width} height={height} aria-hidden>
      <polyline points={points} fill="none" stroke={color} strokeWidth="1.8" strokeLinejoin="round" strokeLinecap="round" />
      <circle cx={width} cy={height - 3 - ((values[values.length - 1] - min) / span) * (height - 6)} r="2.4" fill={color} />
    </svg>
  )
}

/* ----------------------------- Charts ------------------------------ */

type ChartPoint = { x: string; y: number }

function ChartTooltip({ active, payload, unit }: { active?: boolean; payload?: { payload: ChartPoint }[]; unit: string }) {
  if (!active || !payload?.length) return null
  const point = payload[0].payload
  return (
    <div style={{ background: 'rgba(28,28,30,0.92)', color: '#fff', borderRadius: 14, padding: '7px 11px', fontSize: 12.5 }}>
      <span className="tabular">{point.y} {unit}</span>
      <span style={{ opacity: 0.65, marginLeft: 6 }}>{point.x}</span>
    </div>
  )
}

export function AreaSeries({ points, unit, tone = 'teal', height = 180 }: { points: ChartPoint[]; unit: string; tone?: Tone; height?: number }) {
  const color = TONES[tone]
  const id = `area-${useId().replace(/[^a-zA-Z0-9]/g, '')}`
  if (!points.length) return <p className="text-tertiary" style={{ fontSize: 13 }}>暂无数据。</p>
  return (
    <ResponsiveContainer width="100%" height={height}>
      <AreaChart data={points} margin={{ top: 8, right: 6, bottom: 0, left: -14 }}>
        <defs>
          <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={color} stopOpacity={0.32} />
            <stop offset="100%" stopColor={color} stopOpacity={0.02} />
          </linearGradient>
        </defs>
        <CartesianGrid vertical={false} stroke="var(--apple-separator)" strokeDasharray="3 5" />
        <XAxis dataKey="x" tick={{ fontSize: 11, fill: 'var(--apple-label-tertiary)' }} axisLine={false} tickLine={false} minTickGap={28} />
        <YAxis tick={{ fontSize: 11, fill: 'var(--apple-label-tertiary)' }} axisLine={false} tickLine={false} width={52} />
        <Tooltip content={<ChartTooltip unit={unit} />} cursor={{ stroke: color, strokeOpacity: 0.3 }} />
        <Area type="monotone" dataKey="y" stroke={color} strokeWidth={2.2} fill={`url(#${id})`} animationDuration={650} dot={false} activeDot={{ r: 3.5 }} />
      </AreaChart>
    </ResponsiveContainer>
  )
}

export function Bars({ data, unit, tone = 'teal', height = 180 }: { data: { label: string; value: number }[]; unit: string; tone?: Tone; height?: number }) {
  const color = TONES[tone]
  if (!data.length) return <p className="text-tertiary" style={{ fontSize: 13 }}>暂无数据。</p>
  return (
    <ResponsiveContainer width="100%" height={height}>
      <BarChart data={data} margin={{ top: 8, right: 6, bottom: 0, left: -14 }}>
        <CartesianGrid vertical={false} stroke="var(--apple-separator)" strokeDasharray="3 5" />
        <XAxis dataKey="label" tick={{ fontSize: 11, fill: 'var(--apple-label-tertiary)' }} axisLine={false} tickLine={false} />
        <YAxis tick={{ fontSize: 11, fill: 'var(--apple-label-tertiary)' }} axisLine={false} tickLine={false} width={52} />
        <Tooltip
          content={<ChartTooltip unit={unit} />}
          cursor={{ fill: color, fillOpacity: 0.06 }}
          formatter={(value) => [value, unit]}
        />
        <Bar dataKey="value" radius={[6, 6, 2, 2]} animationDuration={650} maxBarSize={38}>
          {data.map((_entry, index) => (
            <Cell key={index} fill={color} fillOpacity={index === data.length - 1 ? 1 : 0.55} />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  )
}

/* ------------------------------- Kpi ------------------------------- */

export function Kpi({ label, value, unit, icon, tone = 'teal', spark }: { label: string; value: string; unit?: string; icon?: IconName; tone?: Tone; spark?: number[] }) {
  return (
    <div className="hover-lift" style={{ background: 'var(--apple-surface-secondary)', borderRadius: 20, padding: '15px 17px', minWidth: 0 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--apple-label-secondary)', fontSize: 12.5, fontWeight: 600 }}>
        {icon ? <span style={{ color: TONES[tone], display: 'inline-flex' }}><Icon name={icon} size={15} /></span> : null}
        {label}
      </div>
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 8, marginTop: 7 }}>
        <div className="tabular" style={{ fontSize: 28, fontWeight: 800, lineHeight: 1.05 }}>
          {value}
          {unit ? <span className="text-secondary" style={{ fontSize: 12, fontWeight: 500, marginLeft: 4 }}>{unit}</span> : null}
        </div>
        {spark ? <Spark data={spark} tone={tone} width={64} height={24} /> : null}
      </div>
    </div>
  )
}

/* ------------------------------- Tile ------------------------------ */

export function Tile({
  icon,
  label,
  value,
  active,
  busy,
  danger,
  onClick,
  children,
}: {
  icon: IconName
  label: string
  value?: string
  active?: boolean
  busy?: boolean
  danger?: boolean
  onClick?: () => void
  children?: ReactNode
}) {
  const accent = danger ? TONES.red : TONES.teal
  return (
    <div
      className="hover-lift"
      style={{
        borderRadius: 20,
        padding: '15px 17px',
        background: active ? `linear-gradient(135deg, ${accent}1f, ${accent}0d)` : 'var(--apple-surface-secondary)',
        border: active ? `1px solid ${accent}55` : '1px solid transparent',
        display: 'flex',
        flexDirection: 'column',
        gap: 10,
        minHeight: 96,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <span
          style={{
            width: 34, height: 34, borderRadius: 17, display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
            background: active ? accent : 'var(--apple-surface-tertiary)', color: active ? '#fff' : 'var(--apple-label-secondary)',
            transition: 'background 200ms ease, color 200ms ease',
          }}
        >
          <Icon name={icon} size={18} />
        </span>
        {value ? <span className="tabular text-secondary" style={{ fontSize: 12.5, fontWeight: 600 }}>{value}</span> : null}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
        <span style={{ fontSize: 14.5, fontWeight: 600 }}>{label}</span>
        {onClick ? (
          <button
            className={active ? 'apple-btn-secondary' : 'apple-btn-primary'}
            style={{ padding: '5px 13px', fontSize: 12.5, borderRadius: 999 }}
            disabled={busy}
            onClick={onClick}
          >
            {busy ? '执行中…' : active ? '关闭' : '开启'}
          </button>
        ) : null}
      </div>
      {children}
    </div>
  )
}

/* ---------------------------- SegControl ---------------------------- */

export function SegControl<T extends string>({ options, value, onChange }: { options: { value: T; label: string }[]; value: T; onChange: (value: T) => void }) {
  return (
    <div style={{ display: 'inline-flex', background: 'var(--apple-surface-tertiary)', borderRadius: 999, padding: 3, gap: 2 }}>
      {options.map(option => (
        <button
          key={option.value}
          onClick={() => onChange(option.value)}
          style={{
            border: 'none', cursor: 'pointer', borderRadius: 999, padding: '5px 14px', fontSize: 13, fontWeight: 600,
            background: value === option.value ? 'var(--apple-surface)' : 'transparent',
            color: value === option.value ? 'var(--apple-label)' : 'var(--apple-label-secondary)',
            boxShadow: value === option.value ? '0 1px 4px rgba(0,0,0,0.12)' : 'none',
            transition: 'all 180ms ease',
          }}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}

/* ----------------------------- TpmsCar ----------------------------- */

export function TpmsCar({ fl, fr, rl, rr }: { fl: number | null; fr: number | null; rl: number | null; rr: number | null }) {
  const bubble = (value: number | null, x: number, y: number) => {
    const tone = value == null ? 'var(--apple-label-tertiary)' : value < 2.2 ? TONES.red : value < 2.4 ? TONES.amber : TONES.green
    return (
      <g>
        <circle cx={x} cy={y} r="17" fill="var(--apple-surface)" stroke={tone} strokeWidth="2" />
        <text x={x} y={y + 1} textAnchor="middle" fontSize="10.5" fontWeight="700" fill="var(--apple-label)" fontFamily="inherit">
          {value != null ? value.toFixed(1) : '--'}
        </text>
        <text x={x} y={y + 10} textAnchor="middle" fontSize="6.5" fill="var(--apple-label-tertiary)">bar</text>
      </g>
    )
  }
  return (
    <svg viewBox="0 0 200 240" style={{ width: 190, maxWidth: '100%', display: 'block', margin: '0 auto' }} role="img" aria-label="车辆俯视图胎压">
      <rect x="58" y="14" width="84" height="212" rx="34" fill="var(--apple-surface-secondary)" stroke="var(--apple-separator)" />
      <rect x="68" y="52" width="64" height="34" rx="10" fill="var(--apple-surface-tertiary)" opacity="0.7" />
      <rect x="68" y="150" width="64" height="40" rx="10" fill="var(--apple-surface-tertiary)" opacity="0.5" />
      <rect x="50" y="58" width="9" height="26" rx="4.5" fill="var(--apple-label)" opacity="0.75" />
      <rect x="141" y="58" width="9" height="26" rx="4.5" fill="var(--apple-label)" opacity="0.75" />
      <rect x="50" y="156" width="9" height="26" rx="4.5" fill="var(--apple-label)" opacity="0.75" />
      <rect x="141" y="156" width="9" height="26" rx="4.5" fill="var(--apple-label)" opacity="0.75" />
      {bubble(fl, 30, 71)}
      {bubble(fr, 170, 71)}
      {bubble(rl, 30, 169)}
      {bubble(rr, 170, 169)}
    </svg>
  )
}

/* ----------------------------- Skeleton ----------------------------- */

export function Skeleton({ height = 120, radius = 20 }: { height?: number; radius?: number }) {
  return <div className="skeleton" style={{ height, borderRadius: radius }} />
}
