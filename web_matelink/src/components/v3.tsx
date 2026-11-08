import { useEffect, useState, type ReactNode } from 'react'
import { Icon } from './viz'

/**
 * Design system v3 primitives (docs/WEB-DESIGN-V3.md). Light theme only
 * this phase; tokens are explicit values, not the v1/v2 flipping vars.
 * Motion character: still — the only orchestrated animation in the
 * whole system is the Charge Ruler's first sweep.
 */

export const V3 = {
  label: '#1c1c1e',
  label2: '#3a3a3c',
  label3: '#6e6e73',
  tint: '#0071e3',
  teal: '#157a86',
  tealDeep: '#0f5c66',
  graphite: '#48484a',
  surface: '#ffffff',
  /* Warm surfaces (v4 unification with the 家庭健康台 baseline). */
  pageBg: '#ede9e3',
  surface2: '#f5f1ea',
  separator: 'rgba(60, 50, 35, 0.10)',
  cardShadow: '0 1px 2px rgba(43,36,28,0.04), 0 10px 28px rgba(43,36,28,0.05)',
  success: '#1d7a35',
  warningText: '#6e4200',
  warningSoft: '#fff6e0',
  errorText: '#84251e',
  errorSoft: '#fdecea',
} as const

export function relativeTime(iso: string | null): string {
  if (!iso) return ''
  const then = new Date(iso).getTime()
  if (Number.isNaN(then)) return ''
  const minutes = Math.max(0, Math.round((Date.now() - then) / 60000))
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  return `${Math.floor(hours / 24)} 天前`
}

export function clockTime(iso: string | null): string {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
}

/* --------------------------- PremiseStrip --------------------------- */

export function PremiseStrip({
  source,
  observedAt,
  onRefresh,
}: {
  source: string | null
  observedAt: string | null
  onRefresh: () => void
}) {
  const snapshot = source === 'telemetry_cache'
  const [spinning, setSpinning] = useState(false)
  const refresh = () => {
    setSpinning(true)
    onRefresh()
    window.setTimeout(() => setSpinning(false), 900)
  }
  return (
    <div
      style={{
        height: 32,
        display: 'flex',
        alignItems: 'center',
        gap: 16,
        padding: '0 32px',
        fontSize: 12.5,
        background: snapshot ? V3.warningSoft : 'color-mix(in srgb, #fffdf8 72%, transparent)',
        backdropFilter: snapshot ? undefined : 'blur(14px) saturate(180%)',
        color: snapshot ? V3.warningText : V3.label2,
        borderBottom: `0.5px solid ${V3.separator}`,
        transition: 'background 195ms ease',
      }}
    >
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7, fontWeight: 600 }}>
        <span style={{ width: 8, height: 8, borderRadius: 4, background: snapshot ? '#b25e00' : '#34c759', flexShrink: 0 }} />
        {snapshot ? '车辆休眠中 · 显示最近一次快照' : source ? '实时数据' : '数据来源未知'}
      </span>
      {observedAt ? (
        <span style={{ color: snapshot ? V3.warningText : V3.label3 }}>
          观测于 {clockTime(observedAt)}（{relativeTime(observedAt)}）
        </span>
      ) : null}
      <span style={{ marginLeft: 'auto' }}>
        <button
          onClick={refresh}
          style={{
            display: 'inline-flex', alignItems: 'center', gap: 5, background: 'none', border: 'none', cursor: 'pointer',
            color: V3.tint, fontSize: 12.5, fontWeight: 600, padding: '2px 4px',
          }}
        >
          <span style={{ display: 'inline-flex', transform: spinning ? 'rotate(360deg)' : 'none', transition: spinning ? 'transform 900ms linear' : 'none' }}>
            <Icon name="refresh" size={14} />
          </span>
          刷新
        </button>
      </span>
    </div>
  )
}

/* ---------------------------- AnomalyBand ---------------------------- */

export function AnomalyBand({ title, detail }: { title: string; detail?: string }) {
  return (
    <div
      style={{
        display: 'flex', gap: 12, alignItems: 'flex-start',
        background: V3.errorSoft, color: V3.errorText,
        borderRadius: 18, padding: '15px 17px',
      }}
    >
      <span style={{ display: 'inline-flex', marginTop: 1 }}><Icon name="alert" size={24} /></span>
      <span>
        <span style={{ display: 'block', fontSize: 15, fontWeight: 600 }}>{title}</span>
        {detail ? <span style={{ display: 'block', fontSize: 13, marginTop: 2, lineHeight: 1.55 }}>{detail}</span> : null}
      </span>
    </div>
  )
}

/* ---------------------------- ChargeRuler ---------------------------- */

const SWEPT_KEY = 'matelink-v3-ruler-swept'

export function ChargeRuler({ soc, snapshot }: { soc: number; snapshot: boolean }) {
  const pct = Math.max(0, Math.min(100, Math.round(soc)))
  const playSweep = !snapshot && typeof window !== 'undefined' && !window.sessionStorage.getItem(SWEPT_KEY)
  const [width, setWidth] = useState(playSweep ? 0 : pct)
  useEffect(() => {
    if (!playSweep) {
      setWidth(pct)
      return
    }
    const frame = requestAnimationFrame(() => {
      setWidth(pct)
      window.sessionStorage.setItem(SWEPT_KEY, '1')
    })
    return () => cancelAnimationFrame(frame)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pct])
  const ticks = Array.from({ length: 11 }, (_, index) => index * 10)
  return (
    <div style={{ position: 'relative', height: 34, marginTop: 4 }} aria-hidden>
      {/* reading line */}
      <div style={{ position: 'absolute', left: `calc(${pct}% - 1px)`, top: 0, bottom: 10, width: 2, background: V3.tealDeep, borderRadius: 1 }} />
      {/* track + fill */}
      <div style={{ position: 'absolute', left: 0, right: 0, bottom: 12, height: 8, borderRadius: 4, background: 'rgba(0,0,0,0.07)', overflow: 'hidden' }}>
        <div
          style={{
            height: '100%', width: `${width}%`, background: V3.teal, borderRadius: 4,
            transition: playSweep ? 'width 720ms cubic-bezier(0.22, 1, 0.36, 1)' : 'width 300ms linear',
          }}
        />
      </div>
      {/* ticks */}
      {ticks.map(tick => (
        <div
          key={tick}
          style={{
            position: 'absolute', bottom: 4, left: `calc(${tick}% - 0.5px)`, width: 1,
            height: tick % 50 === 0 ? 7 : 4, background: 'rgba(0,0,0,0.28)',
          }}
        />
      ))}
      {[0, 50, 100].map(tick => (
        <span key={tick} style={{ position: 'absolute', bottom: -9, left: `${tick}%`, transform: tick === 0 ? 'none' : tick === 100 ? 'translateX(-100%)' : 'translateX(-50%)', fontSize: 10, color: V3.label3 }}>
          {tick}
        </span>
      ))}
    </div>
  )
}

/* ----------------------------- Panel shell ----------------------------- */

export function Panel({ title, action, children, style }: { title?: string; action?: ReactNode; children: ReactNode; style?: React.CSSProperties }) {
  return (
    <section
      style={{
        background: V3.surface, borderRadius: 20, padding: 17,
        boxShadow: V3.cardShadow, border: `0.5px solid ${V3.separator}`,
        ...style,
      }}
    >
      {title ? (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', height: 24, marginBottom: 10 }}>
          <h2 style={{ fontSize: 17, fontWeight: 600, color: V3.label }}>{title}</h2>
          {action}
        </div>
      ) : null}
      {children}
    </section>
  )
}
