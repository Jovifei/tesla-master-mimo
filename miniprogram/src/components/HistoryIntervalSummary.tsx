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
  const status = interval.complete ? '已完成服务器分页；统计为当前筛选下返回记录，字段覆盖分别计算。'
    : interval.reason === 'limit' ? '部分覆盖：达到 500 条或 25 页上限，可继续手动加载历史。'
      : interval.reason === 'cached' ? '部分覆盖：仅本机缓存，尚未验证服务器完整区间。'
        : '部分覆盖：区间分页未完成，请刷新重试。'
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
        <MetricValue label="充电量" value={battery!.value} unit="kWh" icon="battery" />
        <MetricValue label={hasEstimateInTotal ? '总费用（含估算）' : '总费用'} value={hasEstimateInTotal ? `≈${cost.value!.toFixed(2)}` : cost.value} unit={currency} icon="account" />
        <MetricValue label={hasEstimateInTotal ? '平均每次费用（含估算）' : '平均每次费用'} value={cost.value != null && cost.known > 0 ? hasEstimateInTotal ? `≈${(cost.value / cost.known).toFixed(2)}` : cost.value / cost.known : null} unit={currency} icon="account" />
      </>}
    </View>
    <Text className="muted history-statistics-coverage">{status}</Text>
    <Text className="muted">已读取 {interval.receivedPages} 页；当前筛选 {rows.length} 条。日期使用本机时区。</Text>
    {drives ? <Text className="muted">{`${coverage('距离', drives.distance)}；${coverage('时长', drives.duration)}；最高速度覆盖 ${speeds.length}/${rows.length} 条`}</Text> : <>
      <Text className="muted">{`${coverage('电池充入', battery!)}；实际/手填费用覆盖 ${actualCount}/${charges.length} 条；估算费用覆盖 ${estimateCount}/${charges.length} 条`}</Text>
      {currencies.length > 1 ? <Text className="muted">多币种分开统计：{groups.map(group => `${group.code} ${group.estimated ? '≈' : ''}${group.total.toFixed(2)}（实际/手填 ${group.actual} 条，估算 ${group.estimated} 条）`).join('；')}。未跨币种相加。</Text> : hasEstimateInTotal ? <Text className="muted">合计与平均含明确估算；估算未写入服务端账单。</Text> : null}
    </>}
    <Button className="history-evidence-toggle" onClick={() => setExpanded(!expanded)}>{expanded ? '收起统计明细' : kind === 'drives' ? '统计口径与能耗' : '统计口径与输入'}</Button>
    {expanded ? drives ? <>
      <View className="metric-grid history-summary-secondary">
        <MetricValue label="区间平均能耗" value={drives.efficiency.value} unit="kWh/100km" digits={1} negative={(drives.efficiency.value ?? 0) < 0} />
        <MetricValue label="已知净能量合计" value={drives.energy.value} unit="kWh" negative={(drives.energy.value ?? 0) < 0} />
        <MetricValue label="已知距离合计" value={drives.distance.value} unit="km" />
        <MetricValue label="已知时长合计" value={drives.duration.value} unit="分钟" />
      </View>
      <Text className="muted">{`配对平均覆盖 ${drives.efficiency.known}/${drives.efficiency.total} 条；含估算 ${drives.efficiency.estimated} 条`}</Text>
      <Text className="muted">{`${coverage('净能量', drives.energy)}；含估算 ${drives.estimatedEnergy} 条`}</Text>
      <Text className="muted">{`${coverage('距离', drives.distance)}；${coverage('时长', drives.duration)}`}</Text>
    </> : <>
      <View className="metric-grid"><MetricValue label="已知电池充入合计" value={battery!.value} unit="kWh" /><MetricValue label="已知输入电量合计" value={input!.value} unit="kWh" /></View>
      <Text className="muted">{`${coverage('电池充入', battery!)}；${coverage('输入电量', input!)}`}</Text>
      <Text className="muted">实际/手填费用覆盖 {actualCount}/{charges.length} 条；估算费用覆盖 {estimateCount}/{charges.length} 条；平均仅使用同币种已知金额，含估算时显示≈。</Text>
      <Text className="muted">输入保留各条记录的测量口径；费用按各条实际币种展示，未跨币种相加。</Text>
    </> : null}
  </View>
}
