import { Button, Canvas, Slider, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useEffect, useState } from 'react'
import { displayNumber } from './MetricValue'
import { selectCurveSamples, type CurveSourcePoint } from '../domain/analysis'
import { historyDateTime } from '../domain/history_query'
import type { ChargeEvidencePoint, ChargeSampleChannel } from '../services/analysis_types'
import './EvidenceCurve.scss'

const known = (value: number | null | undefined) => (value != null && Number.isFinite(value) ? value : null)
const shown = (value: number | null | undefined, unit = '') =>
  known(value) == null ? '暂无数据' : `${displayNumber(value!, 2)}${unit ? ` ${unit}` : ''}`

/** Only another reported channel is evidence of an asynchronous, rather than missing, field. */
export function chargeCurveProvenance(point: ChargeEvidencePoint, channel: ChargeSampleChannel) {
  return { fieldState: point.fieldState[channel], sourceConflict: point.sourceConflict,
    otherChannel: Object.entries(point.fieldState).some(([name, state]) => name !== channel && state === 'reported') }
}

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
  provenanceOf,
  forceDisconnected,
}: {
  id: string
  title: string
  unit: string
  points: readonly T[]
  valueOf: (point: T) => number | null
  selected: number
  onSelect: (index: number) => void
  isCurrent: () => boolean
  provenanceOf?: (point: T) => Pick<CurveSourcePoint, 'fieldState' | 'otherChannel' | 'sourceConflict'>
  forceDisconnected?: boolean
}) {
  const [fullscreen, setFullscreen] = useState(false)
  const data = points.map(point => {
    const provenance = provenanceOf?.(point)
    return { time: Date.parse(point.date), value: provenance?.sourceConflict ? null : known(valueOf(point)), date: point.date, ...provenance }
  })
  const plot = selectCurveSamples(data, { inferAcrossOtherChannels: Boolean(provenanceOf), forceDisconnected })
  const values = data.flatMap(point => (point.value == null || !Number.isFinite(point.time) ? [] : [point.value]))
  const times = data.flatMap(point => Number.isFinite(point.time) ? [point.time] : [])
  const minimum = values.length ? Math.min(0, ...values) : 0,
    maximum = values.length ? Math.max(0, ...values) : 0
  const range = maximum - minimum || 1,
    first = times[0] ?? 0,
    last = times[times.length - 1] ?? first
  useEffect(() => {
    if (values.length === 0) setFullscreen(false)
  }, [values.length])
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
        if (plot.points.some(point => point.inferredBefore) && typeof context.setLineDash === 'function') {
          context.setLineDash([4, 4], 0)
          context.beginPath()
          plot.points.forEach((point, index) => {
            const previous = plot.points[index - 1]
            if (!point.inferredBefore || !previous) return
            const x = (time: number) => pad + (last === first ? 0 : (time - first) / (last - first)) * (width - pad * 2)
            const y = (value: number) => pad + ((maximum - value) / range) * (height - pad * 2)
            context.moveTo(x(previous.time), y(previous.value))
            context.lineTo(x(point.time), y(point.value))
          })
          context.stroke()
          context.setLineDash([], 0)
        }
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
  }, [id, points, title, unit, fullscreen, selected, provenanceOf, forceDisconnected])
  const point = data[Math.min(selected, Math.max(0, data.length - 1))]
  return (
    <View className={`card analysis-curve${fullscreen ? ' analysis-curve-fullscreen' : ''}`}>
      <View className="analysis-curve-header">
        <Text className="section-title">{title}</Text>
        {values.length ? <Button className="analysis-curve-expand" onClick={() => setFullscreen(!fullscreen)}>
          {fullscreen ? '退出全屏' : '全屏查看'}
        </Button> : null}
      </View>
      {values.length ? (
        <>
          <Text className="muted">
            范围 {displayNumber(minimum)} 至 {displayNumber(maximum)} {unit}
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
          {plot.points.some(point => point.inferredBefore) ? <Text className="muted">虚线连接观测点，仅供参考；中间采样未知。</Text> : null}
          {values.length < data.length ? <Text className="muted">未确认的数据保持空白。</Text> : null}
          {plot.thinned ? (
            <Text className="muted analysis-plot-thinned">
              图形已简化，滑动可查看完整数据。
            </Text>
          ) : null}
        </>
      ) : (
        <Text className="muted">缺少合格的{title}数据，暂时无法显示。</Text>
      )}
    </View>
  )
}
