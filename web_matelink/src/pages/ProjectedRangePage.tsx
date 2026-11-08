import { useEffect, useState } from 'react'
import { getCarStatus } from '../api/matelink'
import type { WebCarStatus } from '../api/matelink-types'
import { StatTile } from '../components/panels'
import { CarPicker, ErrorNote, GapPanel, PageTitle } from '../components/tm'
import { useCar } from './useCar'

/**
 * 预计续航 — TeslaMate Projected Range 的复刻：满电预计续航三图
 * （对里程/对电量/对时间）。三图都需要逐次会话的额定续航边界，
 * 后端未保存 — 按选项 A 全部建成待补面板；当前续航值照常显示。
 */

export default function ProjectedRangePage() {
  const { cars, carId, setCarId, error: carError } = useCar()
  const [status, setStatus] = useState<WebCarStatus | null>(null)

  useEffect(() => {
    if (carId == null) return
    let cancelled = false
    getCarStatus(carId)
      .then(next => {
        if (!cancelled) setStatus(next)
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [carId])

  const need = '逐次行程/充电会话的额定续航起止值（当前仅有状态快照，未按会话保存）'

  return (
    <div>
      <PageTitle title="预计续航">
        <CarPicker cars={cars} carId={carId} onChange={setCarId} />
      </PageTitle>
      {carError ? <ErrorNote text={carError} /> : null}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12 }}>
          <StatTile label="额定续航（当前）" value={status?.ratedRangeKm != null ? Math.round(status.ratedRangeKm).toString() : '--'} unit="km" />
          <StatTile label="预计续航（当前）" value={status?.estimatedRangeKm != null ? Math.round(status.estimatedRangeKm).toString() : '--'} unit="km" />
          <StatTile label="充电限值" value={status?.chargeLimitSoc != null ? Math.round(status.chargeLimitSoc).toString() : '--'} unit="%" caption="车辆当前充电上限设置" />
          <StatTile label="当前电量" value={status?.batteryLevel != null ? Math.round(status.batteryLevel).toString() : '--'} unit="%" />
        </div>
        <GapPanel
          title="满电预计续航（对里程）"
          meaning="把每次会话的额定续航按 100% 电量折算后对里程作图，看满电续航的长期变化。"
          need={need}
          height={200}
        />
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: 16 }}>
          <GapPanel title="满电预计续航（对电量）" meaning="满电折算续航随当时电量的分布，检查低电量区间的折算偏差。" need={need} height={180} />
          <GapPanel title="满电预计续航（对时间）" meaning="满电折算续航随日历时间的变化。" need={need} height={180} />
        </div>
      </div>
    </div>
  )
}
