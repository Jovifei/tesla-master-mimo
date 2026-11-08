import { useEffect, useMemo, useState } from 'react'
import { getCarStatus } from '../api/matelink'
import type { WebCarStatus } from '../api/matelink-types'
import { dailyTempEnvelope } from '../api/tm-data'
import { Panel } from '../components/v3'
import { StatTile, fmtNum } from '../components/panels'
import { CarPicker, DataTable, ErrorNote, LoadingNote, PageTitle, RangeBand } from '../components/tm'
import { useCar } from './useCar'
import { useHistory } from './useHistory'

/**
 * 温度 — TeslaMate Temperatures 的复刻：车外温度的每日区间带 +
 * 按月平均温度表 + 当前温度。来源为行程记录的车外平均温度与
 * 状态快照的当前值。
 */

export default function TemperaturesPage() {
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

  const envelope = useMemo(() => dailyTempEnvelope(history.drives), [history.drives])
  const monthly = useMemo(() => {
    const byMonth = new Map<string, { order: number; label: string; sum: number; count: number }>()
    for (const drive of history.drives) {
      if (!drive.startDate || drive.outsideTempAvgC == null) continue
      const date = new Date(drive.startDate)
      if (Number.isNaN(date.getTime())) continue
      const key = `${date.getFullYear()}-${date.getMonth()}`
      const entry = byMonth.get(key) ?? {
        order: date.getFullYear() * 12 + date.getMonth(),
        label: `${date.getFullYear()}年${date.getMonth() + 1}月`,
        sum: 0,
        count: 0,
      }
      entry.sum += drive.outsideTempAvgC
      entry.count++
      byMonth.set(key, entry)
    }
    return [...byMonth.values()].sort((a, b) => b.order - a.order)
  }, [history.drives])

  return (
    <div>
      <PageTitle title="温度">
        <CarPicker cars={cars} carId={carId} onChange={setCarId} />
      </PageTitle>
      {carError ? <ErrorNote text={carError} /> : null}
      {history.error ? <ErrorNote text={history.error} /> : null}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 12 }}>
          <StatTile label="当前车外温度" value={status?.outsideTempC != null ? fmtNum(status.outsideTempC, 1) : '--'} unit="°C" />
          <StatTile label="当前车内温度" value={status?.insideTempC != null ? fmtNum(status.insideTempC, 1) : '--'} unit="°C" />
          <StatTile label="驾驶员设定温度" value={status?.driverTempC != null ? fmtNum(status.driverTempC, 1) : '--'} unit="°C" />
        </div>
        <Panel title="车外温度（每日区间）">
          {history.loading && !history.drives.length ? (
            <LoadingNote />
          ) : envelope.length ? (
            <RangeBand data={envelope} unit="°C" height={260} />
          ) : (
            <p style={{ fontSize: 13.5, color: '#8a8478' }}>暂无带温度的行程记录。</p>
          )}
          <p style={{ fontSize: 12, color: '#8a8478', marginTop: 8 }}>色带为当日各行程车外平均温度的最低—最高区间。</p>
        </Panel>
        <Panel title="按月平均车外温度">
          <DataTable
            columns={[{ label: '月份' }, { label: '平均车外温度' }, { label: '样本行程' }]}
            rows={monthly.map(m => [m.label, `${fmtNum(m.sum / m.count, 1)} °C`, m.count.toLocaleString('zh-CN')])}
            emptyText="暂无带温度的行程记录。"
          />
        </Panel>
      </div>
    </div>
  )
}
