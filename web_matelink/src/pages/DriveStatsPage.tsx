import { useMemo } from 'react'
import { pairedEfficiency } from '../api/panel-data'
import { topDestinations } from '../api/tm-data'
import { Panel } from '../components/v3'
import { BarsPanel, DATA_COLORS, StatTile, fmtDuration, fmtNum } from '../components/panels'
import { CarPicker, DataTable, ErrorNote, LoadingNote, PageTitle } from '../components/tm'
import { useCar } from './useCar'
import { useHistory } from './useHistory'

/**
 * 行程统计 — TeslaMate Drive Stats 的复刻：距离/次数/时长统计、
 * 月度行驶距离、平均能耗、热门目的地表。平均能耗走本机口径
 * （api/drive-energy.ts，与 App 一致），以覆盖数注记可信度。
 */

function monthlyDistance(rows: readonly { startDate: string | null; distanceKm: number | null }[]): { label: string; value: number }[] {
  const byMonth = new Map<string, { order: number; label: string; value: number }>()
  for (const row of rows) {
    if (!row.startDate) continue
    const date = new Date(row.startDate)
    if (Number.isNaN(date.getTime())) continue
    const key = `${date.getFullYear()}-${date.getMonth()}`
    const entry = byMonth.get(key) ?? { order: date.getFullYear() * 12 + date.getMonth(), label: `${date.getMonth() + 1}月`, value: 0 }
    entry.value += row.distanceKm ?? 0
    byMonth.set(key, entry)
  }
  return [...byMonth.values()]
    .sort((a, b) => a.order - b.order)
    .slice(-6)
    .map(entry => ({ label: entry.label, value: Math.round(entry.value) }))
}

export default function DriveStatsPage() {
  const { cars, car, carId, setCarId, error: carError } = useCar()
  const history = useHistory(carId)

  const stats = useMemo(() => {
    const all = history.drives
    const distance = all.reduce((sum, d) => sum + (d.distanceKm ?? 0), 0)
    const durationMin = all.reduce((sum, d) => sum + (d.durationMinutes ?? 0), 0)
    return {
      count: all.length,
      distance,
      durationMin,
      avgDistance: all.length ? distance / all.length : null,
      avgDuration: all.length ? durationMin / all.length : null,
    }
  }, [history.drives])
  const efficiency = useMemo(() => pairedEfficiency(history.drives, car?.model ?? null), [history.drives, car?.model])
  const monthly = useMemo(() => monthlyDistance(history.drives), [history.drives])
  const destinations = useMemo(() => topDestinations(history.drives, 10), [history.drives])

  return (
    <div>
      <PageTitle title="行程统计">
        <CarPicker cars={cars} carId={carId} onChange={setCarId} />
      </PageTitle>
      {carError ? <ErrorNote text={carError} /> : null}
      {history.error ? <ErrorNote text={history.error} /> : null}
      {history.loading && !history.drives.length ? (
        <LoadingNote />
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 12 }}>
            <StatTile label="总里程" value={fmtNum(stats.distance, 0)} unit="km" />
            <StatTile label="行程次数" value={stats.count.toLocaleString('zh-CN')} />
            <StatTile label="总行驶时长" value={fmtDuration(stats.durationMin)} />
            <StatTile label="平均每次距离" value={stats.avgDistance != null ? fmtNum(stats.avgDistance, 1) : '--'} unit="km" />
            <StatTile label="平均每次时长" value={stats.avgDuration != null ? fmtDuration(stats.avgDuration) : '--'} />
            <StatTile
              label="平均能耗"
              value={efficiency.kwhPer100km != null ? fmtNum(efficiency.kwhPer100km, 1) : '--'}
              unit="kWh/100km"
              caption={efficiency.totalCount > 0 ? `本机口径 · 基于 ${efficiency.pairedCount}/${efficiency.totalCount} 次可计算行程` : '暂无行程'}
            />
          </div>

          <Panel title="行驶距离（按月）">
            {monthly.length ? (
              <BarsPanel data={monthly} unit="km" color={DATA_COLORS.teal} height={200} />
            ) : (
              <p style={{ fontSize: 13.5, color: '#8a8478' }}>暂无行程记录。</p>
            )}
          </Panel>

          <Panel title="热门目的地">
            <DataTable
              columns={[{ label: '目的地' }, { label: '次数' }, { label: '累计里程' }]}
              rows={destinations.map(d => [d.address, d.count.toLocaleString('zh-CN'), `${fmtNum(d.distanceKm, 1)} km`])}
              emptyText="暂无行程记录。"
            />
          </Panel>
        </div>
      )}
    </div>
  )
}
