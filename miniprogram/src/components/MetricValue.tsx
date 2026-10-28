import { Text, View } from '@tarojs/components'
import { sourceLabel } from '../domain/data_labels'
import VisualIcon, { type VisualIconName } from './VisualIcon'
import type { Drive } from '../services/types'

type Props = {
  label: string
  value: string | number | null | undefined
  source?: string | null
  unit?: string
  icon?: VisualIconName
  negative?: boolean
  digits?: number
  presentation?: 'telemetry'
}

export function displayNumber(value: number, digits = 2): string {
  if (!Number.isFinite(value)) return '暂无数据'
  const rounded = Number(value.toFixed(digits))
  // Small non-zero observations must not become a displayed genuine zero.
  return rounded === 0 && value !== 0 ? String(value) : String(rounded)
}
export function peakSpeedKmh(item: Drive): number | null {
  const number = item.speedMaxKmh
  return (item.speedUnit === 'km/h' || item.speedUnit === 'kph') &&
    number != null && Number.isFinite(number) && number >= 0 ? number : null
}

export function formatSocRange(start: number | null | undefined, end: number | null | undefined, unavailable = '暂无数据'): string {
  const first = start != null && Number.isFinite(start) && start >= 0 && start <= 100 ? `${Math.round(start)}%` : null
  const last = end != null && Number.isFinite(end) && end >= 0 && end <= 100 ? `${Math.round(end)}%` : null
  if (!first && !last) return unavailable
  return `${first ?? '—'} → ${last ?? '—'}`
}

export default function MetricValue({ label, value, source, unit, icon, negative = false, digits = 2 }: Props) {
  const valid = value != null && value !== '' && (typeof value !== 'number' || Number.isFinite(value))
  const shown = valid ? (typeof value === 'number' ? displayNumber(value, digits) : value) : '暂无数据'
  const shownUnit = valid ? unit : undefined
  return (
    <View className="metric" ariaLabel={`${label} ${shown}${shownUnit ? `${shownUnit === '%' ? '' : ' '}${shownUnit}` : ''}`}>
      <View className="metric-heading">{icon ? <VisualIcon name={icon} /> : null}</View>
      <View className={`metric-reading ${negative && valid ? 'history-energy-negative' : ''}`}>
        <Text className="metric-value">{shown}</Text>
        {shownUnit ? <Text className="metric-unit">{shownUnit}</Text> : null}
      </View>
      <Text className="metric-label">{label}</Text>
      {source !== undefined ? <Text className="metric-source">{sourceLabel(source)}</Text> : null}
    </View>
  )
}
