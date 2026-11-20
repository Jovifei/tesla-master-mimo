import type { ReactNode } from 'react'
import { Text, View } from '@tarojs/components'
import { aggregateDriveHistory, coveredTotal, isHistoryQuarantined } from '../domain/history'
import { filterHistory, type HistoryQuery } from '../domain/history_query'
import type { HistoryInterval } from '../domain/history_controller'
import type { Charge, Drive } from '../services/types'
import type { CostedCharge } from '../services/charge_cost'
import MetricValue, { peakSpeedKmh } from './MetricValue'
import VisualIcon from './VisualIcon'
import LoadingIndicator from './LoadingIndicator'
import { copy } from '../i18n/copy'

export default function HistoryIntervalSummary({ interval, query, kind, headerAction, footerAction }: {
  interval: HistoryInterval<Drive> | HistoryInterval<Charge> | null
  query: HistoryQuery
  kind: 'drives' | 'charges'
  headerAction?: ReactNode
  footerAction?: ReactNode
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
  const currencySymbol = currency === 'CNY' ? '¥' : currency === 'EUR' ? '€' : currency === 'USD' ? '$' : currency === 'GBP' ? '£' : currency
  const cost = coveredTotal(charges, row => kind === 'charges' && currencies.length === 1 && (row.currencyCode ?? row.currency) === currency ? chargeCost(row) : null)
  const hasEstimateInTotal = cost.value != null && estimateCount > 0 && currencies.length === 1
  return (
    <View className="card history-interval">
      <View className="history-summary-header">
        <Text className="history-summary-title">{copy('common.summary')}</Text>
        {headerAction}
      </View>
      <View className="history-summary-strip">
        {drives ? (
          <>
            <MetricValue presentation="strip" label="总行程数" value={rows.length} unit="次" icon="metricCount" digits={0} />
            <MetricValue presentation="strip" label={copy('history.totalDistance')} value={drives.distance.value} unit="km" icon="metricDistanceSummary" />
            <MetricValue presentation="strip" label={copy('history.totalTime')} value={drives.duration.value} unit="分钟" icon="metricTime" />
            <MetricValue presentation="strip" label={copy('history.speed')} value={speeds.length ? Math.max(...speeds) : null} unit="km/h" icon="metricSpeed" />
          </>
        ) : (
          <>
            <MetricValue presentation="strip" label="充电次数" value={rows.length} unit="次" icon="metricChargeCount" digits={0} />
            <MetricValue presentation="strip" label="能量" value={battery?.value != null && battery.value > 999 ? battery.value / 1000 : battery?.value} unit={battery?.value != null && battery.value > 999 ? 'MWh' : '度'} icon="metricChargeEnergy" digits={1} ariaLabel={`充电量 ${battery?.value != null ? String(Number(battery.value.toFixed(2))) : '暂无数据'}${battery?.value != null ? ' kWh' : ''}`} />
            <MetricValue presentation="strip" label={copy('history.cost')} value={hasEstimateInTotal ? `≈${cost.value!.toFixed(2)}` : cost.value} unit={currencySymbol} icon="metricCost" ariaLabel={`费用 ${cost.value != null ? `${hasEstimateInTotal ? '≈' : ''}${cost.value.toFixed(2)}` : '暂无数据'}${cost.value != null ? ` ${currency}` : ''}`} />
            <MetricValue presentation="strip" label="单次均价" value={cost.value != null && cost.known > 0 ? hasEstimateInTotal ? `≈${(cost.value / cost.known).toFixed(2)}` : cost.value / cost.known : null} unit={currencySymbol} icon="metricAvgCost" ariaLabel={`单次均价 ${cost.value != null && cost.known > 0 ? `${hasEstimateInTotal ? '≈' : ''}${(cost.value / cost.known).toFixed(2)}` : '暂无数据'}${cost.value != null && cost.known > 0 ? ` ${currency}` : ''}`} />
          </>
        )}
      </View>
      {kind === 'charges' && (() => {
        const avgPricePerKwh = cost.value != null && battery?.value != null && battery.value > 0.05
          ? cost.value / battery.value
          : null
        const avgEnergyPerCharge = battery?.value != null && charges.length > 0
          ? battery.value / charges.length
          : null
        if (avgPricePerKwh == null && avgEnergyPerCharge == null) return null
        return <View className="history-summary-averages">
          {avgPricePerKwh != null ? <View className="history-summary-avg"><VisualIcon name="metricCost" /><Text>{`平均电价: ${currencySymbol}${avgPricePerKwh.toFixed(2)} / 度`}</Text></View> : null}
          {avgEnergyPerCharge != null ? <View className="history-summary-avg"><VisualIcon name="metricTime" /><Text>{`单次平均: ${avgEnergyPerCharge.toFixed(1)} 度 / 次`}</Text></View> : null}
        </View>
      })()}
      {footerAction}
    </View>
  )
}
