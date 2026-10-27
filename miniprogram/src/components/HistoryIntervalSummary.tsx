import { Button, Text, View } from '@tarojs/components'
import { useState } from 'react'
import { aggregateDriveHistory, coveredTotal, isHistoryQuarantined, type CoveredMetric } from '../domain/history'
import { filterHistory, type HistoryQuery } from '../domain/history_query'
import type { HistoryInterval } from '../domain/history_controller'
import type { Charge, Drive } from '../services/types'
import type { CostedCharge } from '../services/charge_cost'
import MetricValue, { peakSpeedKmh } from './MetricValue'

function coverage(label: string, metric: CoveredMetric) { return `${label}覆盖 ${metric.known}/${metric.total} 条` }

export default function HistoryIntervalSummary({ interval, query, kind }: {
  interval: HistoryInterval<Drive> | HistoryInterval<Charge> | null; query: HistoryQuery; kind: 'drives' | 'charges'
}) {
  const [expanded, setExpanded] = useState(false)
  if (!interval) return <View className="card history-interval"><Text className="section-title">摘要</Text><Text className="muted">正在读取区间分页（最多 500 条 / 25 页）…</Text></View>
  // App SummaryCard: metrics first; a single coverage line only when data is partial.
  const complete = interval.complete
  const rows = filterHistory(interval.items.filter(row => !isHistoryQuarantined(row)), query)
  const drives = kind === 'drives' ? aggregateDriveHistory(rows as Drive[]) : null
  const charges = rows as Charge[]
  const battery = kind === 'charges' ? coveredTotal(charges, row => row.energyAddedKwh != null && row.energyAddedKwh >= 0 ? row.energyAddedKwh : null) : null
  const input = kind === 'charges' ? coveredTotal(charges, row => row.energyUsedKwh != null && row.energyUsedKwh >= 0 ? row.energyUsedKwh : null) : null
  const speeds = kind === 'drives' ? (rows as Drive[]).map(peakSpeedKmh).filter((number): number is number => number != null) : []
  const chargeCost = (row: Charge) => row.cost != null && Number.isFinite(row.cost) && row.cost >= 0 ? row.cost : null
  const costSource = (row: Charge) => (row as Partial<CostedCharge>).costProvenance
  const actualCount = kind === 'charges' ? charges.filter(row => chargeCost(row) != null && costSource(row) !== 'estimate' && costSource(row) !== 'unknown').length : 0
  const estimateCount = kind === 'charges' ? charges.filter(row => chargeCost(row) != null && costSource(row) === 'estimate').length : 0
  const currencies = kind === 'charges' ? [...new Set(charges.filter(row => chargeCost(row) != null).map(row => row.currencyCode ?? row.currency).filter((value): value is string => Boolean(value)))] : []
  const currency = currencies.length === 1 ? currencies[0]! : currencies.length > 1 ? '多币种' : '币种未知'
  const cost = coveredTotal(charges, row => kind === 'charges' && currencies.length === 1 && (row.currencyCode ?? row.currency) === currency ? chargeCost(row) : null)
  const hasEstimateInTotal = cost.value != null && estimateCount > 0 && currencies.length === 1
  const groups = currencies.map(code => {
    const values = charges.filter(row => (row.currencyCode ?? row.currency) === code && chargeCost(row) != null)
    return { code, total: values.reduce((sum, row) => sum + chargeCost(row)!, 0), estimated: values.filter(row => costSource(row) === 'estimate').length,
      actual: values.filter(row => costSource(row) !== 'estimate' && costSource(row) !== 'unknown').length }
  })
  return <View className="card history-interval">
    <Text className="section-title">摘要</Text>
    <View className="history-summary-primary metric-grid">
      {drives ? <>
        <MetricValue label="总行程数" value={rows.length} unit="次" icon="route" digits={0} />
        <MetricValue label="总距离" value={drives.distance.value} unit="km" icon="distance" />
        <MetricValue label="总时间" value={drives.duration.value} unit="分钟" icon="clock" />
        <MetricValue label="最高速度" value={speeds.length ? Math.max(...speeds) : null} unit="km/h" icon="speed" />
      </> : <>
        <MetricValue label="充电次数" value={rows.length} unit="次" icon="energy" digits={0} />
        <MetricValue label="能量" value={battery!.value} unit="kWh" icon="energy" />
        <MetricValue label={hasEstimateInTotal ? '费用（含估算）' : '费用'} value={hasEstimateInTotal ? `≈${cost.value!.toFixed(2)}` : cost.value} unit={currency} icon="account" />
        <MetricValue label={hasEstimateInTotal ? '单次均价（含估算）' : '单次均价'} value={cost.value != null && cost.known > 0 ? hasEstimateInTotal ? `≈${(cost.value / cost.known).toFixed(2)}` : cost.value / cost.known : null} unit={currency} icon="account" />
      </>}
    </View>
    {!complete && (drives
      ? <Text className="muted history-statistics-coverage">{`数据覆盖率 · 总距离: ${drives.distance.known}/${drives.distance.total} · 总时间: ${drives.duration.known}/${drives.duration.total}`}</Text>
      : <Text className="muted history-statistics-coverage">{`费用覆盖 ${actualCount + estimateCount}/${charges.length} 条${hasEstimateInTotal ? ` · 估算 ${estimateCount} 条` : ''}`}</Text>)}
    <Button className="history-evidence-toggle" onClick={() => setExpanded(!expanded)}>{expanded ? '收起统计明细' : kind === 'drives' ? '统计口径与能耗' : '统计口径与输入'}</Button>
    {expanded ? drives ? <>
      <View className="metric-grid history-summary-secondary">
        <MetricValue label="区间平均能耗" value={drives.efficiency.value} unit="kWh/100km" digits={1} negative={(drives.efficiency.value ?? 0) < 0} />
        <MetricValue label="已知净能量合计" value={drives.energy.value} unit="kWh" negative={(drives.energy.value ?? 0) < 0} />
        <MetricValue label="已知距离合计" value={drives.distance.value} unit="km" />
        <MetricValue label="已知时长合计" value={drives.duration.value} unit="分钟" />
      </View>
      <Text className="muted">{`配对平均覆盖 ${drives.efficiency.known}/${drives.efficiency.total} 条；含估算 ${drives.efficiency.estimated} 条`}</Text>
    </> : <>
      <View className="metric-grid"><MetricValue label="已知电池充入合计" value={battery!.value} unit="kWh" /><MetricValue label="已知输入电量合计" value={input!.value} unit="kWh" /></View>
      <Text className="muted">{`${coverage('电池充入', battery!)}；${coverage('输入电量', input!)}`}</Text>
    </> : null}
  </View>
}
