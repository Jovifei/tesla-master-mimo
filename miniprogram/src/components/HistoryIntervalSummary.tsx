import { Text, View } from '@tarojs/components'
import { aggregateDriveHistory, coveredTotal, isHistoryQuarantined } from '../domain/history'
import { filterHistory, type HistoryQuery } from '../domain/history_query'
import type { HistoryInterval } from '../domain/history_controller'
import type { Charge, Drive } from '../services/types'
import type { CostedCharge } from '../services/charge_cost'
import MetricValue, { peakSpeedKmh } from './MetricValue'
import LoadingIndicator from './LoadingIndicator'
import { copy } from '../i18n/copy'

export default function HistoryIntervalSummary({ interval, query, kind }: {
  interval: HistoryInterval<Drive> | HistoryInterval<Charge> | null
  query: HistoryQuery
  kind: 'drives' | 'charges'
}) {
  if (!interval) {
    return (
      <View className="card history-interval">
        <Text className="section-title">{copy('common.summary')}</Text>
        <LoadingIndicator label={copy('common.loadingSummary')} compact />
      </View>
    )
  }

  const rows = filterHistory(interval.items.filter(row => !isHistoryQuarantined(row)), query)
  const drives = kind === 'drives' ? aggregateDriveHistory(rows as Drive[]) : null
  const charges = rows as Charge[]
  const battery = kind === 'charges' ? coveredTotal(charges, row => row.energyAddedKwh != null && row.energyAddedKwh >= 0 ? row.energyAddedKwh : null) : null
  const speeds = kind === 'drives' ? (rows as Drive[]).map(peakSpeedKmh).filter((number): number is number => number != null) : []
  const chargeCost = (row: Charge) => row.cost != null && Number.isFinite(row.cost) && row.cost >= 0 ? row.cost : null
  const estimateCount = kind === 'charges' ? charges.filter(row => chargeCost(row) != null && (row as Partial<CostedCharge>).costProvenance === 'estimate').length : 0
  const currencies = kind === 'charges' ? [...new Set(charges.filter(row => chargeCost(row) != null).map(row => row.currencyCode ?? row.currency).filter((value): value is string => Boolean(value)))] : []
  const currency = currencies.length === 1 ? currencies[0]! : currencies.length > 1 ? '多币种' : '币种未知'
  const cost = coveredTotal(charges, row => kind === 'charges' && currencies.length === 1 && (row.currencyCode ?? row.currency) === currency ? chargeCost(row) : null)
  const hasEstimateInTotal = cost.value != null && estimateCount > 0 && currencies.length === 1
  const status = interval.complete ? null : drives
    ? `数据覆盖 · 距离 ${drives.distance.known}/${drives.distance.total} · 时长 ${drives.duration.known}/${drives.duration.total}`
    : `数据覆盖 · 费用 ${cost.known}/${charges.length} · 电量 ${battery!.known}/${battery!.total}`
  return (
    <View className="card history-interval">
      <Text className="section-title">{copy('common.summary')}</Text>
      <View className="history-summary-primary metric-grid">
        {drives ? (
          <>
            <MetricValue label="总行程数" value={rows.length} unit="次" icon="route" digits={0} />
            <MetricValue label={copy('history.totalDistance')} value={drives.distance.value} unit="km" icon="distance" />
            <MetricValue label={copy('history.totalTime')} value={drives.duration.value} unit="分钟" icon="clock" />
            <MetricValue label={copy('history.speed')} value={speeds.length ? Math.max(...speeds) : null} unit="km/h" icon="speed" />
          </>
        ) : (
          <>
            <MetricValue label="充电次数" value={rows.length} unit="次" icon="energy" digits={0} />
            <MetricValue label={copy('history.energy')} value={battery?.value} unit="kWh" icon="energy" />
            <MetricValue label={copy('history.cost')} value={hasEstimateInTotal ? `≈${cost.value!.toFixed(2)}` : cost.value} unit={currency} icon="account" />
            <MetricValue label="单次均价" value={cost.value != null && cost.known > 0 ? hasEstimateInTotal ? `≈${(cost.value / cost.known).toFixed(2)}` : cost.value / cost.known : null} unit={currency} icon="account" />
          </>
        )}
      </View>
      {status ? <Text className="muted history-statistics-coverage">{status}</Text> : null}
    </View>
  )
}
