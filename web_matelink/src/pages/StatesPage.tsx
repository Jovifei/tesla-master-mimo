import { useEffect, useMemo, useState } from 'react'
import { getCarStatus } from '../api/matelink'
import type { WebCarStatus } from '../api/matelink-types'
import { stateSegments } from '../api/panel-data'
import { Panel, V3 } from '../components/v3'
import { StatesBar, StatTile } from '../components/panels'
import { CarPicker, ErrorNote, LoadingNote, PageTitle } from '../components/tm'
import { useCar } from './useCar'
import { useHistory } from './useHistory'

/**
 * 状态 — TeslaMate States 的复刻：最近状态变化、当前状态、驻车占比
 * 与全宽状态时间线。状态区间由行程/充电记录推导，空缺即驻车。
 */

const DAY = 86_400_000
const STATE_LABELS: Record<string, string> = {
  online: '在线',
  asleep: '休眠',
  offline: '离线',
  driving: '行驶中',
  charging: '充电中',
}

export default function StatesPage() {
  const { cars, carId, setCarId, error: carError } = useCar()
  const [status, setStatus] = useState<WebCarStatus | null>(null)
  const [days, setDays] = useState(7)
  const history = useHistory(carId)

  useEffect(() => {
    if (carId == null) return
    let cancelled = false
    void (async () => {
      try {
        const next = await getCarStatus(carId)
        if (!cancelled) setStatus(next)
      } catch {
        /* status tiles degrade to history-only */
      }
    })()
    return () => {
      cancelled = true
    }
  }, [carId])

  const windowEnd = Date.now()
  const windowStart = windowEnd - days * DAY
  const segments = useMemo(
    () => stateSegments(history.drives, history.charges, windowStart, windowEnd),
    [history.drives, history.charges, windowStart, windowEnd]
  )
  const parkedShare = useMemo(() => {
    const parked = segments.filter(s => s.kind === 'parked').reduce((sum, s) => sum + (s.end - s.start), 0)
    return (parked / Math.max(1, windowEnd - windowStart)) * 100
  }, [segments, windowStart, windowEnd])

  const lastChange = useMemo(() => {
    const bounds: { t: number; label: string }[] = []
    for (const drive of history.drives) {
      if (drive.startDate) bounds.push({ t: Date.parse(drive.startDate), label: '开始行驶' })
      if (drive.endDate) bounds.push({ t: Date.parse(drive.endDate), label: '结束行驶' })
    }
    for (const charge of history.charges) {
      if (charge.startDate) bounds.push({ t: Date.parse(charge.startDate), label: '开始充电' })
      if (charge.endDate) bounds.push({ t: Date.parse(charge.endDate), label: '结束充电' })
    }
    const past = bounds.filter(b => Number.isFinite(b.t) && b.t <= Date.now()).sort((a, b) => b.t - a.t)
    return past[0] ?? null
  }, [history.drives, history.charges])

  const fmtDateTime = (t: number) => new Date(t).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false })

  return (
    <div>
      <PageTitle title="状态">
        <CarPicker cars={cars} carId={carId} onChange={setCarId} />
      </PageTitle>
      {carError ? <ErrorNote text={carError} /> : null}
      {history.error ? <ErrorNote text={history.error} /> : null}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 16 }}>
          <StatTile
            label="最近状态变化"
            value={lastChange ? fmtDateTime(lastChange.t) : '--'}
            caption={lastChange ? lastChange.label : '暂无记录'}
          />
          <StatTile
            label="当前状态"
            value={status ? (status.chargingState === 'Charging' ? '充电中' : STATE_LABELS[status.state ?? ''] ?? status.state ?? '未知') : '--'}
            caption={status?.observedAt ? `观测于 ${fmtDateTime(Date.parse(status.observedAt))}` : undefined}
          />
          <StatTile label="驻车占比" value={parkedShare.toFixed(1)} unit="%" caption={`近 ${days} 天内驻车时长占比`} />
        </div>
        <Panel
          title="状态时间线"
          action={
            <span style={{ display: 'inline-flex', gap: 6 }}>
              {[2, 7, 30].map(d => (
                <button
                  key={d}
                  onClick={() => setDays(d)}
                  style={{
                    padding: '4px 12px', borderRadius: 999, border: 'none', cursor: 'pointer', fontFamily: 'inherit',
                    fontSize: 12.5, fontWeight: days === d ? 600 : 400,
                    background: days === d ? 'rgba(0,113,227,0.10)' : 'transparent',
                    color: days === d ? V3.tint : V3.label2,
                  }}
                >
                  近 {d} 天
                </button>
              ))}
            </span>
          }
        >
          {history.loading && !history.drives.length ? <LoadingNote /> : <StatesBar segments={segments} windowStart={windowStart} windowEnd={windowEnd} />}
          <div style={{ fontSize: 12, color: V3.label3, marginTop: 8 }}>区间由行程与充电记录推导，两段记录之间的空缺计为驻车。</div>
        </Panel>
      </div>
    </div>
  )
}
