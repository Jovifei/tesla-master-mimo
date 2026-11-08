import { useMemo, useRef, useState } from 'react'

/**
 * A small SVG line chart in the Apple aesthetic: hairline grid,
 * soft area fill, min/max captions and a hover readout. Null values
 * break the line into segments (samples are never invented).
 */
export type ChartPoint = { date: string; value: number | null }

export default function MiniChart({
  points,
  unit,
  color = 'var(--apple-teal)',
  height = 120,
  digits = 0,
}: {
  points: ChartPoint[]
  unit: string
  color?: string
  height?: number
  digits?: number
}) {
  const [hover, setHover] = useState<number | null>(null)
  const svgRef = useRef<SVGSVGElement>(null)

  const model = useMemo(() => {
    const values = points.map(point => point.value).filter((value): value is number => value != null)
    if (!values.length) return null
    const min = Math.min(...values)
    const max = Math.max(...values)
    const span = max - min || 1
    const width = 720
    const pad = 6
    const x = (index: number) => (points.length <= 1 ? width / 2 : pad + (index / (points.length - 1)) * (width - pad * 2))
    const y = (value: number) => pad + (1 - (value - min) / span) * (height - pad * 2)
    const segments: string[] = []
    let current: string[] = []
    points.forEach((point, index) => {
      if (point.value == null) {
        if (current.length) segments.push(current.join(' '))
        current = []
      } else {
        current.push(`${x(index)},${y(point.value)}`)
      }
    })
    if (current.length) segments.push(current.join(' '))
    return { min, max, segments, x, y, width }
  }, [points, height])

  if (!model) {
    return <p className="text-tertiary" style={{ fontSize: 13 }}>暂无样本数据。</p>
  }

  const hoverPoint = hover != null ? points[hover] : null

  const onMove = (event: React.MouseEvent) => {
    const rect = svgRef.current?.getBoundingClientRect()
    if (!rect || points.length <= 1) return
    const ratio = (event.clientX - rect.left) / rect.width
    const index = Math.round(ratio * (points.length - 1))
    setHover(Math.max(0, Math.min(points.length - 1, index)))
  }

  return (
    <div>
      <div className="tabular text-secondary" style={{ fontSize: 12, display: 'flex', justifyContent: 'space-between', minHeight: 18 }}>
        <span>
          {hoverPoint && hoverPoint.value != null
            ? `${new Date(hoverPoint.date).toLocaleTimeString('zh-CN', { hour12: false })} · ${hoverPoint.value.toFixed(digits)} ${unit}`
            : '移动鼠标查看采样点'}
        </span>
        <span>
          {model.min.toFixed(digits)} – {model.max.toFixed(digits)} {unit}
        </span>
      </div>
      <svg
        ref={svgRef}
        viewBox={`0 0 ${model.width} ${height}`}
        style={{ width: '100%', height, display: 'block' }}
        onMouseMove={onMove}
        onMouseLeave={() => setHover(null)}
      >
        {model.segments.map((segment, index) => (
          <polyline key={index} points={segment} fill="none" stroke={color} strokeWidth="1.8" strokeLinejoin="round" strokeLinecap="round" />
        ))}
        {hoverPoint && hoverPoint.value != null ? (
          <circle cx={model.x(hover ?? 0)} cy={model.y(hoverPoint.value)} r="3.5" fill={color} />
        ) : null}
      </svg>
    </div>
  )
}
