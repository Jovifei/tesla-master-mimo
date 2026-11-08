import { useMemo } from 'react'
import { temperatureBuckets } from '../api/tm-data'
import { Panel } from '../components/v3'
import { BarGaugeList, CarPicker, ErrorNote, GapPanel, LoadingNote, PageTitle } from '../components/tm'
import { useCar } from './useCar'
import { useHistory } from './useHistory'

/**
 * 效率 — TeslaMate Efficiency 的复刻：按温度区间的平均能耗。核心
 * 输入是行程净能耗——后端当前明确不提供可信来源，整页按所有者
 * 选项 A 建成待补形态；个别带能耗证据的行程（如归档导入）会进入
 * 温度桶统计并注明样本数。
 */

export default function EfficiencyPage() {
  const { cars, carId, setCarId, error: carError } = useCar()
  const history = useHistory(carId)
  const buckets = useMemo(() => temperatureBuckets(history.drives), [history.drives])
  const withEnergy = useMemo(
    () => history.drives.filter(d => d.consumptionWhPerKm != null && d.distanceKm != null && d.distanceKm > 0),
    [history.drives]
  )
  const usable = buckets.filter(b => b.avgConsumptionWhPerKm != null)

  return (
    <div>
      <PageTitle title="效率">
        <CarPicker cars={cars} carId={carId} onChange={setCarId} />
      </PageTitle>
      {carError ? <ErrorNote text={carError} /> : null}
      {history.error ? <ErrorNote text={history.error} /> : null}
      {history.loading && !history.drives.length ? (
        <LoadingNote />
      ) : usable.length ? (
        <Panel title="各温度区间平均能耗">
          <BarGaugeList
            items={usable.map(b => ({
              label: b.label,
              value: b.avgConsumptionWhPerKm ?? 0,
              display: `${Math.round(b.avgConsumptionWhPerKm ?? 0)} Wh/km · ${b.driveCount} 次行程`,
            }))}
          />
          <p style={{ fontSize: 12, color: '#8a8478', marginTop: 10, lineHeight: 1.6 }}>
            仅统计带净能耗证据的 {withEnergy.length} 次行程，按行程距离加权平均；其余行程后端未提供净能耗，不纳入。
          </p>
        </Panel>
      ) : (
        <GapPanel
          title="效率（按温度区间的平均能耗）"
          meaning="把行程按车外温度分组，比较各温度下的平均净能耗（Wh/km），看低温对效率的影响。"
          need="行程净能耗的可信计量——后端当前明确不提供（代码注释禁止用充电电量替代行程消耗）"
          height={220}
        />
      )}
    </div>
  )
}
