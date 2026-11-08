import { useEffect, useState } from 'react'
import { getCarStatus } from '../api/matelink'
import type { WebCarStatus } from '../api/matelink-types'
import { StatTile } from '../components/panels'
import { CarPicker, ErrorNote, GapPanel, GapTile, PageTitle } from '../components/tm'
import { useCar } from './useCar'

/**
 * 软件更新 — TeslaMate Updates 的复刻：当前固件版本 + 更新历史。
 * 后端只在状态快照里（不稳定地）带当前版本，没有版本变更历史 —
 * 历史部分按选项 A 建成待补面板。
 */

export default function UpdatesPage() {
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

  const need = '后端在车辆状态中稳定提供软件版本（car_version），并保存版本变更历史（版本、变更时间）'

  return (
    <div>
      <PageTitle title="软件更新">
        <CarPicker cars={cars} carId={carId} onChange={setCarId} />
      </PageTitle>
      {carError ? <ErrorNote text={carError} /> : null}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 12 }}>
          {status?.firmware ? (
            <StatTile label="当前固件版本" value={status.firmware} />
          ) : (
            <GapTile label="当前固件版本" need="在车辆状态中提供软件版本（car_version）" />
          )}
          <StatTile label="当前里程表" value={status?.odometerKm != null ? Math.round(status.odometerKm).toLocaleString('zh-CN') : '--'} unit="km" />
        </div>
        <GapPanel
          title="更新历史"
          meaning="历次软件版本变更的时间线：版本号、更新时间、更新时里程。"
          need={need}
          height={200}
        />
      </div>
    </div>
  )
}
