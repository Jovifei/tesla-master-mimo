import { useMemo } from 'react'
import { parkedGaps } from '../api/tm-data'
import { Panel } from '../components/v3'
import { StatTile, fmtDuration } from '../components/panels'
import { CarPicker, DataTable, ErrorNote, LoadingNote, PageTitle } from '../components/tm'
import { useCar } from './useCar'
import { useHistory } from './useHistory'

/**
 * 驻车掉电 — TeslaMate Vampire Drain 的复刻：逐段驻车区间表。
 * 原生车辆的驻车耗电量后端不提供可信计量 — 本页呈现可得部分
 * （区间、时长、起止电量与电量差），耗电量列标注待补；不沿用
 * 后端无对应实现的旧资格校验逻辑（数据审计结论）。
 */

export default function VampirePage() {
  const { cars, carId, setCarId, error: carError } = useCar()
  const history = useHistory(carId)
  const gaps = useMemo(
    () => parkedGaps(history.drives, history.charges).filter(g => g.durationMinutes >= 60).slice(0, 60),
    [history.drives, history.charges]
  )
  const totalDrop = useMemo(() => {
    const drops = gaps.flatMap(g => (g.deltaSoc != null && g.deltaSoc < 0 ? [-g.deltaSoc] : []))
    return drops.length ? drops.reduce((a, b) => a + b, 0) / drops.length : null
  }, [gaps])

  const fmtDateTime = (t: number) =>
    new Date(t).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false })

  return (
    <div>
      <PageTitle title="驻车掉电">
        <CarPicker cars={cars} carId={carId} onChange={setCarId} />
      </PageTitle>
      {carError ? <ErrorNote text={carError} /> : null}
      {history.error ? <ErrorNote text={history.error} /> : null}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12 }}>
          <StatTile label="统计驻车段" value={gaps.length.toLocaleString('zh-CN')} caption="时长 ≥ 1 小时的行程/充电间隙" />
          <StatTile label="平均每段电量变化" value={totalDrop != null ? `−${totalDrop.toFixed(1)}` : '--'} unit={totalDrop != null ? '%' : undefined} caption="仅统计电量下降的段" />
          <StatTile label="驻车耗电量" value="待补数据" caption="需要后端提供原生车辆的可信驻车耗电计量" />
        </div>
        <Panel title="驻车区间">
          {history.loading && !history.drives.length ? (
            <LoadingNote />
          ) : (
            <DataTable
              columns={[{ label: '开始' }, { label: '结束' }, { label: '时长' }, { label: '地点' }, { label: '电量' }, { label: '电量差' }, { label: '耗电量' }]}
              rows={gaps.map(g => [
                fmtDateTime(g.start),
                fmtDateTime(g.end),
                fmtDuration(g.durationMinutes),
                g.address ?? '未知地点',
                `${g.startSoc != null ? Math.round(g.startSoc) : '--'}% → ${g.endSoc != null ? Math.round(g.endSoc) : '--'}%`,
                g.deltaSoc != null ? `${g.deltaSoc > 0 ? '+' : ''}${g.deltaSoc.toFixed(1)}%` : '--',
                '待补',
              ])}
              emptyText="暂无足够长的驻车区间。"
            />
          )}
          <p style={{ fontSize: 12, color: '#8a8478', marginTop: 10, lineHeight: 1.6 }}>
            口径：驻车段＝相邻两次行程/充电之间的间隙；电量差为间隙两端记录电量之差。耗电量（kWh）后端对原生车辆不发布可信值，故标注待补，不用电量差反推。
          </p>
        </Panel>
      </div>
    </div>
  )
}
