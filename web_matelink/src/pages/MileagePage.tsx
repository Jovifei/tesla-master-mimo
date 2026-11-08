import { useMemo } from 'react'
import { mileageSeries } from '../api/tm-data'
import { Panel } from '../components/v3'
import { DATA_COLORS, StackChart, StatTile, fmtNum, type ChartRow } from '../components/panels'
import { CarPicker, ErrorNote, LoadingNote, PageTitle } from '../components/tm'
import { useCar } from './useCar'
import { useHistory } from './useHistory'

/**
 * 里程 — TeslaMate Mileage 的复刻：里程表读数随时间的曲线，由
 * 每次行程的起止里程表读数拼接（连续相同读数去重）。
 */

export default function MileagePage() {
  const { cars, carId, setCarId, error: carError } = useCar()
  const history = useHistory(carId)
  const series = useMemo(() => mileageSeries(history.drives), [history.drives])
  const rows: ChartRow[] = useMemo(() => series.map(p => ({ t: p.t, value: p.value })), [series])
  const first = series[0]?.value ?? null
  const last = series[series.length - 1]?.value ?? null

  return (
    <div>
      <PageTitle title="里程">
        <CarPicker cars={cars} carId={carId} onChange={setCarId} />
      </PageTitle>
      {carError ? <ErrorNote text={carError} /> : null}
      {history.error ? <ErrorNote text={history.error} /> : null}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12 }}>
          <StatTile label="最新里程表" value={last != null ? Math.round(last).toLocaleString('zh-CN') : '--'} unit="km" />
          <StatTile label="记录期增量" value={first != null && last != null ? fmtNum(last - first, 0) : '--'} unit="km" caption="自最早一条带里程读数的行程起" />
          <StatTile label="里程采样点" value={series.length.toLocaleString('zh-CN')} caption="行程起止读数拼接（去重后）" />
        </div>
        <Panel title="里程表曲线">
          {history.loading && !history.drives.length ? (
            <LoadingNote />
          ) : rows.length >= 2 ? (
            <StackChart
              data={rows}
              series={[{ key: 'value', title: '里程表', unit: 'km', color: DATA_COLORS.teal, height: 320, digits: 0 }]}
            />
          ) : (
            <p style={{ fontSize: 13.5, color: '#8a8478', lineHeight: 1.7 }}>带里程表读数的行程不足，暂无法拼接曲线。</p>
          )}
        </Panel>
      </div>
    </div>
  )
}
