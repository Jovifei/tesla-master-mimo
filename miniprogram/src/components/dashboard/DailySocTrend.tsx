import { Button, Canvas, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { useEffect } from 'react'
import DashboardIcon from './DashboardIcon'
import type { SocDailyTrendResult } from '../../domain/soc_daily_trend'

/**
 * Inline daily battery-level trend on the dashboard. The App entry opens the
 * recorded battery history in place instead of sending the user to a separate
 * statistics screen; this card does the same. One point per day with a
 * recorded drive endpoint, no interpolation across days.
 */
export default function DailySocTrend({
  trend,
  windowDays,
  loading,
  onWindowChange,
}: {
  trend: SocDailyTrendResult
  windowDays: 7 | 30
  loading: boolean
  onWindowChange: (days: 7 | 30) => void
}) {
  const points = trend.points
  const values = points.map(point => point.soc)
  const minimum = values.length ? Math.min(...values) : 0
  const maximum = values.length ? Math.max(...values) : 0

  useEffect(() => {
    if (values.length === 0) return
    if (typeof Taro.createCanvasContext !== 'function' || typeof Taro.createSelectorQuery !== 'function') return
    let cancelled = false
    Taro.createSelectorQuery()
      .select('#dashboard-soc-trend-canvas')
      .boundingClientRect(result => {
        if (cancelled || !result || Array.isArray(result)) return
        const width = result.width
        const height = result.height
        if (!width || !height) return
        const padX = 16
        const padY = 14
        const context = Taro.createCanvasContext('dashboard-soc-trend-canvas')
        context.clearRect(0, 0, width, height)
        const low = Math.max(0, minimum - 4)
        const high = Math.min(100, Math.max(maximum + 4, low + 8))
        const span = high - low || 1
        const first = Date.parse(points[0]!.date)
        const last = Date.parse(points[points.length - 1]!.date)
        const x = (time: number) => padX + (last === first ? (width - padX * 2) / 2 : (time - first) / (last - first) * (width - padX * 2))
        const y = (soc: number) => padY + (high - soc) / span * (height - padY * 2)
        context.setStrokeStyle('rgba(21,122,134,0.16)')
        context.setLineWidth(1)
        context.beginPath()
        context.moveTo(padX, height - padY)
        context.lineTo(width - padX, height - padY)
        context.stroke()
        context.setStrokeStyle('#157a86')
        context.setLineWidth(2)
        context.beginPath()
        points.forEach((point, index) => {
          const px = x(Date.parse(point.date))
          const py = y(point.soc)
          if (index === 0) context.moveTo(px, py)
          else context.lineTo(px, py)
        })
        context.stroke()
        context.setFillStyle('#157a86')
        points.forEach(point => {
          context.beginPath()
          context.arc(x(Date.parse(point.date)), y(point.soc), 2.5, 0, Math.PI * 2)
          context.fill()
        })
        context.draw()
      })
      .exec()
    return () => { cancelled = true }
  }, [points, minimum, maximum])

  const change = trend.change
  const changeText = change == null ? null : `${change > 0 ? '+' : ''}${Math.round(change)}%`
  return (
    <View className="dashboard-soc-trend">
      <View className="dashboard-soc-trend-head">
        <View className="dashboard-soc-trend-title">
          <DashboardIcon name="bolt" tone="primary" />
          <Text>每日电量趋势</Text>
        </View>
        <View className="dashboard-soc-trend-windows">
          <Button className={`dashboard-soc-window ${windowDays === 7 ? 'active' : ''}`} onClick={() => onWindowChange(7)}>近7天</Button>
          <Button className={`dashboard-soc-window ${windowDays === 30 ? 'active' : ''}`} onClick={() => onWindowChange(30)}>近30天</Button>
        </View>
      </View>
      {loading && points.length === 0 ? (
        <Text className="muted dashboard-soc-empty">正在读取电量记录…</Text>
      ) : points.length === 0 ? (
        <Text className="muted dashboard-soc-empty">
          {trend.unavailableReason === 'no_history_in_window'
            ? `近${windowDays}天没有已完成行程，暂无每日电量记录。`
            : '窗口内的行程没有携带可验证的起止电量，未生成曲线。'}
        </Text>
      ) : (
        <>
          <View className="dashboard-soc-summary">
            <Text className="dashboard-soc-latest">{Math.round(trend.latestSoc ?? points[points.length - 1]!.soc)}%</Text>
            <Text className="dashboard-soc-caption">
              最近记录 · 共 {points.length} 个记录日
              {changeText ? ` · 窗口变化 ${changeText}` : ''}
            </Text>
          </View>
          <Canvas id="dashboard-soc-trend-canvas" canvasId="dashboard-soc-trend-canvas" className="dashboard-soc-canvas" />
          <Text className="muted dashboard-soc-note">每点为当日最后一次已完成行程结束时的电量；没有记录的日期不补点。</Text>
        </>
      )}
    </View>
  )
}