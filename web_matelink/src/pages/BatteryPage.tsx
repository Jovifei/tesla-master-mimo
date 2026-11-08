import { useEffect, useMemo, useState } from 'react'
import { getCarStatus } from '../api/matelink'
import type { WebCarStatus } from '../api/matelink-types'
import { Panel } from '../components/v3'
import { DATA_COLORS, StatTile, fmtNum } from '../components/panels'
import { CarPicker, DonutShares, ErrorNote, GapPanel, LoadingNote, PageTitle } from '../components/tm'
import { useCar } from './useCar'
import { useHistory } from './useHistory'

/**
 * 电池健康 — TeslaMate Battery Health 的复刻：最大容量、按里程的
 * 衰减、按时间的衰减三图。后端 /battery-health 对原生车恒返回空，
 * 证据只存在于归档导入车辆 — 本页按选项 A 建成：可得的当前值与
 * 充放电构成照常显示，三张衰减图为待补面板。
 */

export default function BatteryPage() {
  const { cars, carId, setCarId, error: carError } = useCar()
  const [status, setStatus] = useState<WebCarStatus | null>(null)
  const history = useHistory(carId)

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

  const energy = useMemo(() => {
    const ac = history.charges.filter(c => c.chargeMode === 'ac').reduce((sum, c) => sum + (c.energyAddedKwh ?? 0), 0)
    const dc = history.charges.filter(c => c.chargeMode === 'dc').reduce((sum, c) => sum + (c.energyAddedKwh ?? 0), 0)
    return { ac, dc }
  }, [history.charges])

  return (
    <div>
      <PageTitle title="电池健康">
        <CarPicker cars={cars} carId={carId} onChange={setCarId} />
      </PageTitle>
      {carError ? <ErrorNote text={carError} /> : null}
      {history.error ? <ErrorNote text={history.error} /> : null}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12 }}>
          <StatTile label="当前里程表" value={status?.odometerKm != null ? Math.round(status.odometerKm).toLocaleString('zh-CN') : '--'} unit="km" />
          <StatTile label="当前电量" value={status?.batteryLevel != null ? Math.round(status.batteryLevel).toString() : '--'} unit="%" />
          <StatTile label="额定续航（当前）" value={status?.ratedRangeKm != null ? fmtNum(status.ratedRangeKm, 0) : '--'} unit="km" caption="车辆上报的额定续航" />
          <StatTile label="预计续航（当前）" value={status?.estimatedRangeKm != null ? fmtNum(status.estimatedRangeKm, 0) : '--'} unit="km" caption="按当前电量估算" />
        </div>

        <Panel title="充入电量构成（交流 / 直流）">
          {history.loading && !history.charges.length ? (
            <LoadingNote />
          ) : (
            <DonutShares
              parts={[
                { label: '交流', value: energy.ac, color: DATA_COLORS.teal },
                { label: '直流', value: energy.dc, color: DATA_COLORS.tealDeep },
              ]}
              unit="kWh"
            />
          )}
          <p style={{ fontSize: 12, color: '#8a8478', marginTop: 8 }}>直流快充占比是电池使用画像的一部分，仅作构成展示，不作健康结论。</p>
        </Panel>

        <GapPanel
          title="最大容量（随里程）"
          meaning="用多次满充/大区间充放电估算电池当前最大可用容量，并与里程对照看衰减。"
          need="电池健康证据序列——后端 /battery-health 对原生车辆恒返回空；证据目前仅存在于 TeslaMate 归档导入的车辆"
          height={200}
        />
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: 16 }}>
          <GapPanel
            title="电池衰减（按里程）"
            meaning="容量保持率随累计里程的变化曲线。"
            need="同上：需要后端保存并开放容量估算序列"
            height={180}
          />
          <GapPanel
            title="电池衰减（按时间）"
            meaning="容量保持率随日历时间的变化曲线。"
            need="同上：需要后端保存并开放容量估算序列"
            height={180}
          />
        </div>
      </div>
    </div>
  )
}
