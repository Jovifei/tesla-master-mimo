import { useMemo } from 'react'
import { addressStats } from '../api/tm-data'
import { Panel } from '../components/v3'
import { CarPicker, DataTable, ErrorNote, LoadingNote, PageTitle } from '../components/tm'
import { useCar } from './useCar'
import { useHistory } from './useHistory'

/**
 * 地点 — TeslaMate Locations 的复刻：到访过的地址按次数聚合。
 * 我们的记录只有地址文本，没有结构化的城市/省份/国家字段 —
 * 按城市维度的统计标注在页脚（待补）。
 */

export default function LocationsPage() {
  const { cars, carId, setCarId, error: carError } = useCar()
  const history = useHistory(carId)
  const stats = useMemo(
    () => addressStats(history.drives, history.charges),
    [history.drives, history.charges]
  )
  const byVisits = useMemo(() => [...stats].sort((a, b) => b.visits - a.visits), [stats])

  const fmtDate = (t: number) =>
    t > 0 ? new Date(t).toLocaleDateString('zh-CN', { year: 'numeric', month: 'numeric', day: 'numeric' }) : '--'

  return (
    <div>
      <PageTitle title="地点">
        <CarPicker cars={cars} carId={carId} onChange={setCarId} />
      </PageTitle>
      {carError ? <ErrorNote text={carError} /> : null}
      {history.error ? <ErrorNote text={history.error} /> : null}
      <Panel title="到访地点">
        {history.loading && !history.drives.length && !history.charges.length ? (
          <LoadingNote />
        ) : (
          <DataTable
            columns={[{ label: '地址' }, { label: '到访次数' }, { label: '最近到访' }]}
            rows={byVisits.slice(0, 50).map(s => [s.address, s.visits.toLocaleString('zh-CN'), fmtDate(s.lastAt)])}
            emptyText="暂无带地址的记录。"
          />
        )}
        <p style={{ fontSize: 12, color: '#8a8478', marginTop: 10, lineHeight: 1.6 }}>
          待补：按城市/省份维度的统计需要后端在记录中保存结构化地址字段（城市、省份、国家）；当前只有地址文本，故只做到访次数聚合。
        </p>
      </Panel>
    </div>
  )
}
