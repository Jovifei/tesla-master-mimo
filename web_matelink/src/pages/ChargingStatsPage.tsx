import { useEffect, useMemo, useState } from 'react'
import { getChargeDetail } from '../api/matelink'
import { chargeHeatmap, chargeStationStats } from '../api/tm-data'
import { Panel } from '../components/v3'
import { BarsPanel, DATA_COLORS, ScatterPanel, StatTile, fmtDuration, fmtNum } from '../components/panels'
import { CarPicker, DataTable, DonutShares, ErrorNote, GapTile, HeatmapGrid, LoadingNote, PageTitle } from '../components/tm'
import { useCar } from './useCar'
import { useHistory } from './useHistory'

/**
 * 充电统计 — TeslaMate Charging Stats 的复刻：统计瓷砖行 + 电量增量
 * 柱图 + 交流/直流电量与费用构成 + 充电时间热力图 + 直流充电曲线 +
 * 热门充电站表。成本口径只用有费用的真实记录。
 */

function monthlyEnergy(rows: readonly { startDate: string | null; energyAddedKwh: number | null }[]): { label: string; value: number }[] {
  const byMonth = new Map<string, { order: number; label: string; value: number }>()
  for (const row of rows) {
    if (!row.startDate) continue
    const date = new Date(row.startDate)
    if (Number.isNaN(date.getTime())) continue
    const key = `${date.getFullYear()}-${date.getMonth()}`
    const entry = byMonth.get(key) ?? { order: date.getFullYear() * 12 + date.getMonth(), label: `${date.getMonth() + 1}月`, value: 0 }
    entry.value += row.energyAddedKwh ?? 0
    byMonth.set(key, entry)
  }
  return [...byMonth.values()]
    .sort((a, b) => a.order - b.order)
    .slice(-6)
    .map(entry => ({ label: entry.label, value: Math.round(entry.value * 10) / 10 }))
}

export default function ChargingStatsPage() {
  const { cars, carId, setCarId, error: carError } = useCar()
  const history = useHistory(carId)
  const [dcPoints, setDcPoints] = useState<{ x: number; y: number }[]>([])

  const stats = useMemo(() => {
    const all = history.charges
    const totalDurationMin = all.reduce((sum, c) => sum + (c.durationMinutes ?? 0), 0)
    const totalAdded = all.reduce((sum, c) => sum + (c.energyAddedKwh ?? 0), 0)
    const dc = all.filter(c => c.chargeMode === 'dc')
    const ac = all.filter(c => c.chargeMode === 'ac')
    const sumEnergy = (list: typeof all) => list.reduce((sum, c) => sum + (c.energyAddedKwh ?? 0), 0)
    const sumCost = (list: typeof all) => list.reduce((sum, c) => sum + (c.cost ?? 0), 0)
    const superchargerCost = all.filter(c => c.isSupercharger).reduce((sum, c) => sum + (c.cost ?? 0), 0)
    const perKwh = (list: typeof all): number | null => {
      const priced = list.filter(c => c.cost != null && c.energyAddedKwh != null && c.energyAddedKwh > 0)
      const cost = priced.reduce((sum, c) => sum + (c.cost ?? 0), 0)
      const energy = priced.reduce((sum, c) => sum + (c.energyAddedKwh ?? 0), 0)
      return energy > 0 ? cost / energy : null
    }
    return {
      count: all.length,
      totalDurationMin,
      totalAdded,
      dcEnergy: sumEnergy(dc),
      acEnergy: sumEnergy(ac),
      dcCost: sumCost(dc),
      acCost: sumCost(ac),
      superchargerCost,
      hasSuperchargerCost: all.some(c => c.isSupercharger && c.cost != null),
      perKwhDc: perKwh(dc),
      perKwhAc: perKwh(ac),
    }
  }, [history.charges])

  const per100Cost = useMemo(() => {
    const totalCost = history.charges.reduce((sum, c) => sum + (c.cost ?? 0), 0)
    const distance = history.drives.reduce((sum, d) => sum + (d.distanceKm ?? 0), 0)
    const hasCost = history.charges.some(c => c.cost != null)
    return hasCost && distance > 0 ? (totalCost / distance) * 100 : null
  }, [history.charges, history.drives])

  const monthly = useMemo(() => monthlyEnergy(history.charges), [history.charges])
  const heatmap = useMemo(() => chargeHeatmap(history.charges), [history.charges])
  const stations = useMemo(() => chargeStationStats(history.charges), [history.charges])
  const byCount = useMemo(() => [...stations].sort((a, b) => b.count - a.count), [stations])
  const byEnergy = useMemo(() => [...stations].sort((a, b) => b.energyKwh - a.energyKwh), [stations])

  // DC charge curves: fetch a bounded number of DC charge details.
  useEffect(() => {
    if (carId == null) return
    const dcCharges = history.charges.filter(c => c.chargeMode === 'dc').slice(0, 8)
    if (!dcCharges.length) {
      setDcPoints([])
      return
    }
    let cancelled = false
    void (async () => {
      const collected: { x: number; y: number }[] = []
      for (const charge of dcCharges) {
        try {
          const detail = await getChargeDetail(carId, charge.id)
          for (const point of detail.points) {
            if (point.powerKw != null && point.batteryLevel != null) {
              collected.push({ x: point.batteryLevel, y: point.powerKw })
            }
          }
        } catch {
          /* skip failed detail */
        }
      }
      if (!cancelled) setDcPoints(collected)
    })()
    return () => {
      cancelled = true
    }
  }, [carId, history.charges])

  const fmtAddressRow = (row: { address: string; count: number; energyKwh: number; cost: number | null; avgEnergyKwh: number | null }): React.ReactNode[] => [
    row.address,
    row.count.toLocaleString('zh-CN'),
    `${fmtNum(row.energyKwh, 1)} kWh`,
    row.cost != null ? `${fmtNum(row.cost, 2)} 元` : '--',
    row.avgEnergyKwh != null ? `${fmtNum(row.avgEnergyKwh, 1)} kWh` : '--',
  ]

  return (
    <div>
      <PageTitle title="充电统计">
        <CarPicker cars={cars} carId={carId} onChange={setCarId} />
      </PageTitle>
      {carError ? <ErrorNote text={carError} /> : null}
      {history.error ? <ErrorNote text={history.error} /> : null}
      {history.loading && !history.charges.length ? (
        <LoadingNote />
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 12 }}>
            <StatTile label="充电次数" value={stats.count.toLocaleString('zh-CN')} />
            <StatTile label="总充电时长" value={fmtDuration(stats.totalDurationMin)} />
            <StatTile label="总充入电量" value={fmtNum(stats.totalAdded, 1)} unit="kWh" />
            <StatTile label="超充费用" value={stats.hasSuperchargerCost ? fmtNum(stats.superchargerCost, 2) : '--'} unit={stats.hasSuperchargerCost ? '元' : undefined} caption="标记为超充的记录费用合计" />
            {per100Cost != null ? (
              <StatTile label="每 100 公里成本" value={fmtNum(per100Cost, 2)} unit="元" caption="总费用 ÷ 已记录总里程 × 100（粗略口径）" />
            ) : (
              <GapTile label="每 100 公里成本" need="带费用的充电记录与足够里程" />
            )}
            <StatTile label="每度电成本（直流）" value={stats.perKwhDc != null ? fmtNum(stats.perKwhDc, 2) : '--'} unit={stats.perKwhDc != null ? '元/kWh' : undefined} />
            <StatTile label="每度电成本（交流）" value={stats.perKwhAc != null ? fmtNum(stats.perKwhAc, 2) : '--'} unit={stats.perKwhAc != null ? '元/kWh' : undefined} />
          </div>

          <Panel title="充电时间分布（星期 × 小时）">
            <HeatmapGrid cells={heatmap} />
          </Panel>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: 16 }}>
            <Panel title="电量增量（按月）">
              {monthly.length ? (
                <BarsPanel data={monthly} unit="kWh" color={DATA_COLORS.teal} height={190} />
              ) : (
                <p style={{ fontSize: 13.5, color: '#8a8478' }}>暂无充电记录。</p>
              )}
            </Panel>
            <Panel title="直流充电曲线（功率 — 电量）">
              {dcPoints.length >= 3 ? (
                <ScatterPanel points={dcPoints} xLabel="电量" yLabel="功率" height={190} />
              ) : (
                <p style={{ fontSize: 13.5, color: '#8a8478' }}>直流充电采样不足，无法绘制曲线。</p>
              )}
            </Panel>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 16 }}>
            <Panel title="充电电量构成（交流 / 直流）">
              <DonutShares
                slices={[
                  { label: '交流', value: stats.acEnergy, color: DATA_COLORS.teal },
                  { label: '直流', value: stats.dcEnergy, color: DATA_COLORS.tealDeep },
                ]}
                unit="kWh"
              />
            </Panel>
            <Panel title="充电费用构成（交流 / 直流）">
              <DonutShares
                slices={[
                  { label: '交流', value: stats.acCost, color: DATA_COLORS.teal },
                  { label: '直流', value: stats.dcCost, color: DATA_COLORS.tealDeep },
                ]}
                unit="元"
              />
            </Panel>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(340px, 1fr))', gap: 16 }}>
            <Panel title="热门充电站（按次数）">
              <DataTable
                columns={[{ label: '地址' }, { label: '次数' }, { label: '充入电量' }, { label: '费用' }, { label: '平均每次' }]}
                rows={byCount.slice(0, 10).map(fmtAddressRow)}
                emptyText="暂无充电记录。"
              />
            </Panel>
            <Panel title="热门充电站（按电量）">
              <DataTable
                columns={[{ label: '地址' }, { label: '次数' }, { label: '充入电量' }, { label: '费用' }, { label: '平均每次' }]}
                rows={byEnergy.slice(0, 10).map(fmtAddressRow)}
                emptyText="暂无充电记录。"
              />
            </Panel>
          </div>

          <Panel title="充电站明细">
            <DataTable
              columns={[{ label: '地址' }, { label: '次数' }, { label: '充入电量' }, { label: '费用' }, { label: '平均每次' }]}
              rows={stations.slice(0, 20).map(fmtAddressRow)}
              emptyText="暂无充电记录。"
            />
          </Panel>
        </div>
      )}
    </div>
  )
}
