import { useMemo } from 'react'
import { resolveSummaryEnergy } from '../api/drive-energy.ts'
import { temperatureBuckets } from '../api/tm-data'
import { Panel } from '../components/v3'
import { BarGaugeList, CarPicker, ErrorNote, GapPanel, LoadingNote, PageTitle } from '../components/tm'
import { useCar } from './useCar'
import { useHistory } from './useHistory'

/**
 * 效率 — TeslaMate Efficiency 的复刻：按温度区间的平均能耗。每程
 * 能耗走本机口径（api/drive-energy.ts，与 App 一致：功率积分/
 * 电量差估算等），不再依赖后端恒为空的 consumption_net 字段；
 * 无任何可计算行程时整页按待补形态并说明缺的是什么。
 */

export default function EfficiencyPage() {
  const { cars, car, carId, setCarId, error: carError } = useCar()
  const history = useHistory(carId)
  const buckets = useMemo(() => temperatureBuckets(history.drives, car?.model ?? null), [history.drives, car?.model])
  const withEnergy = useMemo(
    () => history.drives.filter(d => resolveSummaryEnergy(d, car?.model ?? null).efficiencyWhKm != null),
    [history.drives, car?.model]
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
            能耗为本机口径（与 App 一致：功率积分 / 电量差估算 / 合格的后端报告值），共 {withEnergy.length} 次行程可计算并纳入，按行程距离加权平均；暂不可计算的行程不纳入。
          </p>
        </Panel>
      ) : (
        <GapPanel
          title="效率（按温度区间的平均能耗）"
          meaning="把行程按车外温度分组，比较各温度下的平均能耗（Wh/km），看低温对效率的影响。"
          need="可计算能耗的行程样本——本机口径下需要行程功率采样、电量变化或合格的后端能耗证据，当前记录尚不足"
          height={220}
        />
      )}
    </div>
  )
}
