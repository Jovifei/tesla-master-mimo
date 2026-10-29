import { Button, Canvas, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useEffect, useMemo, useState } from 'react'
import { TPMS_WHEELS, tpmsInstant, type TpmsPoint, type TpmsWheel } from '../domain/tpms'
import type { TpmsHistory } from '../services/tpms_cache'

export const TPMS_CHART_MAX_POINTS = 600
const wheelNames = { fl: '左前轮', fr: '右前轮', rl: '左后轮', rr: '右后轮' }
const colors: Record<TpmsWheel, string> = { fl: '#157a86', fr: '#2563eb', rl: '#b45309', rr: '#7c3aed' }
type Window = '24h' | '7d' | '30d'
const windows: Record<Window, number> = { '24h': 24 * 60 * 60 * 1000, '7d': 7 * 24 * 60 * 60 * 1000, '30d': 30 * 24 * 60 * 60 * 1000 }

/** Pick existing points only, retaining first/last and true extrema for every wheel. */
function thin(rows: TpmsPoint[], limit: number): TpmsPoint[] {
  if (rows.length <= limit) return rows
  let min = 0, max = 0
  rows.forEach((row, index) => { if (row.pressureBar < rows[min].pressureBar) min = index; if (row.pressureBar > rows[max].pressureBar) max = index })
  const indices = new Set([0, rows.length - 1, min, max])
  for (let index = 0; index < limit && indices.size < limit; index++) indices.add(Math.round(index * (rows.length - 1) / (limit - 1)))
  for (let index = 0; indices.size < limit && index < rows.length; index++) indices.add(index)
  return [...indices].sort((a, b) => a - b).map(index => rows[index])
}
export function tpmsChartData(input: readonly unknown[], window: Window, now: number) {
  const unique = new Map<string, TpmsPoint>()
  for (const value of input) {
    if (!value || typeof value !== 'object') continue
    const row = value as Partial<TpmsPoint>, at = tpmsInstant(row.observedAt)
    if (!Number.isFinite(now) || !TPMS_WHEELS.includes(row.wheel as TpmsWheel) || row.source !== 'telemetry_mqtt' || row.timeBasis !== 'collector_received_at' ||
      typeof row.pressureBar !== 'number' || !Number.isFinite(row.pressureBar) || row.pressureBar < 0 || row.pressureBar > 15 || at == null || at > now || at < now - windows[window]) continue
    const point: TpmsPoint = { wheel: row.wheel as TpmsWheel, pressureBar: row.pressureBar, observedAt: new Date(at).toISOString(), source: 'telemetry_mqtt', timeBasis: 'collector_received_at' }
    unique.set(`${point.wheel}:${point.observedAt}`, point)
  }
  const source = [...unique.values()].sort((a, b) => Date.parse(a.observedAt) - Date.parse(b.observedAt))
  const groups = TPMS_WHEELS.map(wheel => { const rows = source.filter(point => point.wheel === wheel); return { wheel, sourceCount: rows.length, points: thin(rows, TPMS_CHART_MAX_POINTS / 4) } })
  return { source, groups, plotted: groups.reduce((sum, group) => sum + group.points.length, 0), thinned: groups.some(group => group.points.length < group.sourceCount) }
}
let nextCanvas = 0
export default function TpmsHistoryChart({ history, now = Date.now() }: { history?: TpmsHistory | null; now?: number }) {
  const [window, setWindow] = useState<Window>('24h'), [cursorIndex, setCursorIndex] = useState(Number.MAX_SAFE_INTEGER)
  const [canvasId] = useState(() => `tpms-history-${++nextCanvas}`)
  const plot = useMemo(() => tpmsChartData(history?.points ?? [], window, now), [history?.points, window, now])
  const native = typeof Taro.createCanvasContext === 'function' && typeof Taro.createSelectorQuery === 'function'
  const index = Math.max(0, Math.min(cursorIndex, plot.source.length - 1)), point = plot.source[index]
  useEffect(() => {
    if (!native || !plot.source.length) return
    let active = true
    const query = Taro.createSelectorQuery()
    query.select(`#${canvasId}`).boundingClientRect(rect => {
      if (!active || Array.isArray(rect) || !rect || !Number.isFinite(rect.width) || rect.width <= 0) return
      const width = rect.width, height = rect.height || 200, context = Taro.createCanvasContext(canvasId)
      const left = 40, right = Math.max(left + 1, width - 12), top = 18, bottom = height - 28
      const values = plot.groups.flatMap(group => group.points.map(row => row.pressureBar)), minimum = Math.min(...values), maximum = Math.max(...values)
      const low = minimum === maximum ? Math.max(0, minimum - 0.1) : minimum, high = maximum === low ? low + 0.1 : maximum
      const first = Date.parse(plot.source[0].observedAt), last = Date.parse(plot.source[plot.source.length - 1].observedAt)
      context.setFillStyle('#ffffff'); context.fillRect(0, 0, width, height)
      context.setStrokeStyle('#e9edf2'); context.beginPath(); context.moveTo(left, top); context.lineTo(left, bottom); context.lineTo(right, bottom); context.stroke()
      context.setFillStyle('#6b7280'); context.setFontSize(10); context.fillText(`${high.toFixed(2)} bar`, 0, top + 4); context.fillText(`${low.toFixed(2)} bar`, 0, bottom)
      for (const group of plot.groups) {
        context.setFillStyle(colors[group.wheel])
        for (const row of group.points) {
          const x = first === last ? (left + right) / 2 : left + (Date.parse(row.observedAt) - first) / (last - first) * (right - left)
          const y = bottom - (row.pressureBar - low) / (high - low) * (bottom - top)
          context.beginPath(); context.arc(x, y, 2.5, 0, Math.PI * 2); context.fill()
        }
      }
      context.draw()
    }).exec()
    return () => { active = false }
  }, [native, plot, canvasId])
  return <View className="tpms-history-chart">
    <Text className="section-title">本机胎压观测图</Text><Text className="muted">本机前台离散观测 · 单位 bar · 仅画真实点，不连线推断连续胎压。</Text>
    <View className="history-date-chips">{(['24h', '7d', '30d'] as Window[]).map(value => <Button key={value} className={`history-chip ${window === value ? 'history-chip-selected' : ''}`} onClick={() => { setWindow(value); setCursorIndex(Number.MAX_SAFE_INTEGER) }}>{value === '24h' ? '24小时' : value === '7d' ? '7天' : '30天'}</Button>)}</View>
    {plot.groups.map(group => <Text key={group.wheel} style={{ color: colors[group.wheel], display: 'block', fontSize: '12px' }}>{wheelNames[group.wheel]} · 绘制 {group.points.length}/{group.sourceCount} 点</Text>)}
    {plot.source.length ? <>
      {native ? <Canvas id={canvasId} canvasId={canvasId} style={{ width: '100%', height: '200px' }} /> : <Text className="muted">当前环境未提供原生画布，可使用下方游标查看真实观测。</Text>}
      <Text className="metric-source">时间范围：{plot.source[0].observedAt} 至 {plot.source[plot.source.length - 1].observedAt}</Text>
      <Text className="metric-source">绘制 {plot.plotted}/{plot.source.length} 个有效观测{plot.thinned ? ' · 已抽样，逐轮保留首末与极值；全图最多600点' : ''}{history?.limited ? ' · 本机保留范围已截取' : ''}</Text>
      <View className="tpms-history-cursor"><Text>{wheelNames[point.wheel]} · {point.pressureBar} bar</Text><Text className="metric-source">{point.observedAt} · {now - Date.parse(point.observedAt) > 15 * 60 * 1000 ? '旧观测' : '已观测'} · 游标 {index + 1}/{plot.source.length}</Text></View>
      {plot.source.length > 1 ? <View><Button className="button-secondary" disabled={index <= 0} onClick={() => setCursorIndex(Math.max(0, index - 1))}>前一个观测</Button><Button className="button-secondary" disabled={index >= plot.source.length - 1} onClick={() => setCursorIndex(Math.min(plot.source.length - 1, index + 1))}>后一个观测</Button></View> : null}
    </> : <Text className="muted">当前窗口没有合格本机离散观测；不生成胎压曲线或0占位。</Text>}
  </View>
}
