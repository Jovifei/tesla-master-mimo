import { useMemo } from 'react'
import { Panel } from '../components/v3'
import { StatTile } from '../components/panels'
import { CarPicker, ErrorNote, GapPanel, LoadingNote, PageTitle } from '../components/tm'
import { useCar } from './useCar'
import { useHistory } from './useHistory'

/**
 * 数据概况 — TeslaMate Database Information 的复刻：客户端可推导
 * 的记录概况照常显示；数据库内部指标（表行数、库大小等）后端
 * 无接口 — 待补面板。
 */

export default function DataInfoPage() {
  const { cars, carId, setCarId, error: carError } = useCar()
  const history = useHistory(carId)

  const range = useMemo(() => {
    const times: number[] = []
    for (const d of history.drives) {
      const t = Date.parse(d.startDate ?? '')
      if (Number.isFinite(t)) times.push(t)
    }
    for (const c of history.charges) {
      const t = Date.parse(c.startDate ?? '')
      if (Number.isFinite(t)) times.push(t)
    }
    if (!times.length) return null
    return { from: Math.min(...times), to: Math.max(...times) }
  }, [history.drives, history.charges])

  const fmtDate = (t: number) => new Date(t).toLocaleDateString('zh-CN', { year: 'numeric', month: 'numeric', day: 'numeric' })

  return (
    <div>
      <PageTitle title="数据概况">
        <CarPicker cars={cars} carId={carId} onChange={setCarId} />
      </PageTitle>
      {carError ? <ErrorNote text={carError} /> : null}
      {history.error ? <ErrorNote text={history.error} /> : null}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <Panel title="记录概况（由已加载数据统计）">
          {history.loading && !history.drives.length && !history.charges.length ? (
            <LoadingNote />
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 12 }}>
              <StatTile label="车辆数" value={cars.length.toLocaleString('zh-CN')} />
              <StatTile label="行程记录" value={history.drives.length.toLocaleString('zh-CN')} caption={history.complete ? '已全部加载' : '已加载部分（上限内）'} />
              <StatTile label="充电记录" value={history.charges.length.toLocaleString('zh-CN')} caption={history.complete ? '已全部加载' : '已加载部分（上限内）'} />
              <StatTile
                label="记录时间范围"
                value={range ? `${fmtDate(range.from)} 起` : '--'}
                caption={range ? `最近一条 ${fmtDate(range.to)}` : undefined}
              />
            </div>
          )}
        </Panel>
        <GapPanel
          title="数据库内部指标"
          meaning="数据库各表行数、库文件大小、位置点总数等运维口径的概况。"
          need="后端提供数据库运维指标接口（当前无此接口，优先级低）"
          height={180}
        />
      </div>
    </div>
  )
}
