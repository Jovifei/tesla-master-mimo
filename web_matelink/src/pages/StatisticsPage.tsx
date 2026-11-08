import { useMemo, useState } from 'react'
import { periodStats } from '../api/tm-data'
import { SegControl } from '../components/viz'
import { Panel } from '../components/v3'
import { fmtNum } from '../components/panels'
import { CarPicker, DataTable, ErrorNote, LoadingNote, PageTitle } from '../components/tm'
import { useCar } from './useCar'
import { useHistory } from './useHistory'

/**
 * 统计 — TeslaMate Statistics 的复刻：按日/周/月汇总行驶距离、
 * 行程次数、充入电量、充电次数与费用。
 */

type Granularity = 'day' | 'week' | 'month'

export default function StatisticsPage() {
  const { cars, carId, setCarId, error: carError } = useCar()
  const [granularity, setGranularity] = useState<Granularity>('day')
  const history = useHistory(carId)
  const rows = useMemo(
    () => periodStats(history.drives, history.charges, granularity).slice(0, 62),
    [history.drives, history.charges, granularity]
  )

  return (
    <div>
      <PageTitle title="统计">
        <CarPicker cars={cars} carId={carId} onChange={setCarId} />
      </PageTitle>
      {carError ? <ErrorNote text={carError} /> : null}
      {history.error ? <ErrorNote text={history.error} /> : null}
      <div style={{ marginBottom: 16 }}>
        <SegControl
          options={[
            { value: 'day', label: '按日' },
            { value: 'week', label: '按周' },
            { value: 'month', label: '按月' },
          ]}
          value={granularity}
          onChange={value => setGranularity(value as Granularity)}
        />
      </div>
      <Panel title={granularity === 'day' ? '每日汇总' : granularity === 'week' ? '每周汇总' : '每月汇总'}>
        {history.loading && !history.drives.length && !history.charges.length ? (
          <LoadingNote />
        ) : (
          <DataTable
            columns={[{ label: '周期' }, { label: '行驶距离' }, { label: '行程次数' }, { label: '充入电量' }, { label: '充电次数' }, { label: '费用' }]}
            rows={rows.map(r => [
              r.label,
              r.distanceKm != null ? `${fmtNum(r.distanceKm, 1)} km` : '--',
              r.driveCount.toLocaleString('zh-CN'),
              r.chargedKwh != null ? `${fmtNum(r.chargedKwh, 1)} kWh` : '--',
              r.chargeCount.toLocaleString('zh-CN'),
              r.cost != null ? `${fmtNum(r.cost, 2)} 元` : '--',
            ])}
            emptyText="暂无可汇总的记录。"
          />
        )}
        {!history.complete ? (
          <p style={{ fontSize: 12, color: '#8a8478', marginTop: 8 }}>历史记录较多，统计基于已加载部分。</p>
        ) : null}
      </Panel>
    </div>
  )
}
