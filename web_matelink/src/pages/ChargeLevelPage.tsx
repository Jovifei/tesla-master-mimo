import { useEffect, useMemo, useState } from 'react'
import { getChargeDetail } from '../api/matelink'
import type { WebChargePoint } from '../api/matelink-types'
import { socSegments } from '../api/tm-data'
import { Panel, V3 } from '../components/v3'
import { CarPicker, ErrorNote, LoadingNote, PageTitle, SocStitchChart } from '../components/tm'
import { useCar } from './useCar'
import { useHistory } from './useHistory'

/**
 * 电量历史 — TeslaMate Charge Level 的复刻：全宽电量曲线。我们没有
 * 独立的电量序列表，曲线由会话边界电量与充电会话内采样拼接，
 * 驻车时段断开（页面明示口径）。
 */

const DAY = 86_400_000
const WINDOWS = [
  { days: 2, label: '近 2 天' },
  { days: 7, label: '近 7 天' },
  { days: 30, label: '近 30 天' },
  { days: 90, label: '近 90 天' },
]

export default function ChargeLevelPage() {
  const { cars, carId, setCarId, error: carError } = useCar()
  const [days, setDays] = useState(7)
  const history = useHistory(carId)
  const [chargePoints, setChargePoints] = useState<ReadonlyMap<string, readonly WebChargePoint[]>>(new Map())

  const windowEnd = Date.now()
  const windowStart = windowEnd - days * DAY

  useEffect(() => {
    if (carId == null || !history.charges.length) return
    const inWindow = history.charges
      .filter(c => {
        const end = Date.parse(c.endDate ?? c.startDate ?? '')
        return Number.isFinite(end) && end >= windowStart
      })
      .slice(0, 10)
    let cancelled = false
    void (async () => {
      const map = new Map<string, readonly WebChargePoint[]>()
      for (const charge of inWindow) {
        try {
          const detail = await getChargeDetail(carId, charge.id)
          if (detail.points.length >= 2) map.set(charge.id, detail.points)
        } catch {
          /* boundary stitch still applies */
        }
      }
      if (!cancelled) setChargePoints(map)
    })()
    return () => {
      cancelled = true
    }
  }, [carId, history.charges, windowStart])

  const segments = useMemo(
    () => socSegments(history.charges, history.drives, chargePoints, windowStart, windowEnd),
    [history.charges, history.drives, chargePoints, windowStart, windowEnd]
  )

  return (
    <div>
      <PageTitle title="电量历史">
        <CarPicker cars={cars} carId={carId} onChange={setCarId} />
      </PageTitle>
      {carError ? <ErrorNote text={carError} /> : null}
      {history.error ? <ErrorNote text={history.error} /> : null}
      <Panel
        title="电量曲线"
        action={
          <span style={{ display: 'inline-flex', gap: 6 }}>
            {WINDOWS.map(w => (
              <button
                key={w.days}
                onClick={() => setDays(w.days)}
                style={{
                  padding: '4px 12px', borderRadius: 999, border: 'none', cursor: 'pointer', fontFamily: 'inherit',
                  fontSize: 12.5, fontWeight: days === w.days ? 600 : 400,
                  background: days === w.days ? 'rgba(0,113,227,0.10)' : 'transparent',
                  color: days === w.days ? V3.tint : V3.label2,
                }}
              >
                {w.label}
              </button>
            ))}
          </span>
        }
      >
        {history.loading && !history.drives.length && !history.charges.length ? (
          <LoadingNote />
        ) : segments.length ? (
          <SocStitchChart segments={segments} height={420} />
        ) : (
          <div style={{ fontSize: 13, color: V3.label3, lineHeight: 1.7 }}>
            该时段没有可拼接的电量记录。电量曲线由每次行程/充电的起止电量与充电采样拼接而成；后端若提供连续电量序列，此图可直接换用真实采样。
          </div>
        )}
        <div style={{ fontSize: 12, color: V3.label3, marginTop: 10, lineHeight: 1.6 }}>
          口径：实线＝充电会话内的真实采样；虚线＝行程起止电量的边界拼接（行程内无逐点电量）；驻车时段无数据，曲线断开。
        </div>
      </Panel>
    </div>
  )
}
