import { Button, Canvas, Slider, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useEffect, useState } from 'react'
import { displayNumber } from './MetricValue'
import { selectCurveSamples } from '../domain/analysis'
import { historyDateTime } from '../domain/history_query'

const known = (value: number | null | undefined) => (value != null && Number.isFinite(value) ? value : null)
const shown = (value: number | null | undefined, unit = '') =>
  known(value) == null ? '暂无数据' : `${displayNumber(value!, 2)}${unit ? ` ${unit}` : ''}`

/** Native canvas uses actual evidence instants and breaks its line at missing samples. */
export default function EvidenceCurve<T extends { date: string }>({
  id,
  title,
  unit,
  points,
  valueOf,
  selected,
  onSelect,
  isCurrent,
}: {
  id: string
  title: string
  unit: string
  points: readonly T[]
  valueOf: (point: T) => number | null
  selected: number
  onSelect: (index: number) => void
  isCurrent: () => boolean
}) {
  const [fullscreen, setFullscreen] = useState(false)
  const data = points.map(point => ({ time: Date.parse(point.date), value: known(valueOf(point)), date: point.date }))
  const plot = selectCurveSamples(data)
  const values = data.flatMap(point => (point.value == null ? [] : [point.value]))
  const minimum = values.length ? Math.min(0, ...values) : 0,
    maximum = values.length ? Math.max(0, ...values) : 0
  const range = maximum - minimum || 1,
    first = data[0]?.time ?? 0,
    last = data[data.length - 1]?.time ?? first
  useEffect(() => {
    if (values.length === 0) return
    if (typeof Taro.createCanvasContext !== 'function' || typeof Taro.createSelectorQuery !== 'function') return
    let cancelled = false
    Taro.createSelectorQuery()
      .select(`#${id}`)
      .boundingClientRect(result => {
        if (cancelled || !isCurrent() || !result || Array.isArray(result)) return
        const width = result.width,
          height = result.height,
          pad = 16
        if (!width || !height) return
        const context = Taro.createCanvasContext(id)
        context.clearRect(0, 0, width, height)
        context.setStrokeStyle('#dce3e8')
        context.setLineWidth(1)
        context.beginPath()
        const baseline = pad + (maximum / range) * (height - pad * 2)
        context.moveTo(pad, baseline)
        context.lineTo(width - pad, baseline)
        context.stroke()
        context.setStrokeStyle('#157a86')
        context.setLineWidth(2)
        context.beginPath()
        for (const point of plot.points) {
          const x = pad + (last === first ? 0 : (point.time - first) / (last - first)) * (width - pad * 2)
          const y = pad + ((maximum - point.value) / range) * (height - pad * 2)
          if (point.breakBefore) context.moveTo(x, y)
          else context.lineTo(x, y)
        }
        context.stroke()
        context.setFillStyle('#157a86')
        context.beginPath()
        plot.points.forEach((point, index) => {
          if (!point.breakBefore || (index + 1 < plot.points.length && !plot.points[index + 1].breakBefore)) return
          const x = pad + (last === first ? 0 : (point.time - first) / (last - first)) * (width - pad * 2)
          const y = pad + ((maximum - point.value) / range) * (height - pad * 2)
          context.moveTo(x + 2, y)
          context.arc(x, y, 2, 0, Math.PI * 2)
        })
        context.fill()
        const selectedPoint = data[Math.min(selected, Math.max(0, data.length - 1))]
        if (selectedPoint && Number.isFinite(selectedPoint.time)) {
          const x = pad + (last === first ? 0 : (selectedPoint.time - first) / (last - first)) * (width - pad * 2)
          context.setStrokeStyle('#b45309')
          context.setLineWidth(1)
          context.beginPath()
          context.moveTo(x, pad)
          context.lineTo(x, height - pad)
          context.stroke()
        }
        context.draw()
      })
      .exec()
    return () => {
      cancelled = true
    }
  }, [id, points, title, unit, fullscreen, selected])
  const point = data[Math.min(selected, Math.max(0, data.length - 1))]
  return (
    <View className={`card analysis-curve${fullscreen ? ' analysis-curve-fullscreen' : ''}`}>
      <Text className="section-title">{title}</Text>
      <Button className="analysis-curve-expand" onClick={() => setFullscreen(!fullscreen)}>
        {fullscreen ? '退出全屏' : '全屏查看'}
      </Button>
      {values.length ? (
        <>
          <Text className="muted">
            纵轴 {displayNumber(minimum)} 至 {displayNumber(maximum)} {unit} · 横轴为实际采样时间
          </Text>
          <Canvas id={id} canvasId={id} className="analysis-canvas" />
          <View className="analysis-axis">
            <Text>{historyDateTime(points[0]?.date)}</Text>
            <Text>{historyDateTime(points[points.length - 1]?.date)}</Text>
          </View>
          {data.length > 1 ? (
            <Slider
              min={0}
              max={data.length - 1}
              step={1}
              value={Math.min(selected, data.length - 1)}
              onChange={event => onSelect(Number(event.detail.value))}
            />
          ) : null}
          <Text className="analysis-cursor">{point ? `${historyDateTime(point.date)} · ${shown(point.value, unit)}` : '暂无采样'}</Text>
          <Text className="muted">有效值 {values.length}/{data.length} 个；缺失采样断开曲线，保留真实零值与负值。</Text>
          {plot.thinned ? (
            <Text className="muted analysis-plot-thinned">
              图形部分绘制：从 {plot.sourceCount} 个原始采样中选取 {plot.points.length} 个真实点（最多600），保留极值、零值和未知断点；游标仍读取完整原始采样。
            </Text>
          ) : null}
        </>
      ) : (
        <Text className="muted">缺少合格的{title}采样，未生成曲线。</Text>
      )}
    </View>
  )
}
