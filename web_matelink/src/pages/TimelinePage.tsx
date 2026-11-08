import { useMemo } from 'react'
import { Link } from 'react-router-dom'
import { sessionTimeline } from '../api/tm-data'
import { Panel, V3 } from '../components/v3'
import { CarPicker, DataTable, ErrorNote, LoadingNote, PageTitle } from '../components/tm'
import { useCar } from './useCar'
import { useHistory } from './useHistory'

/**
 * 时间线 — TeslaMate Timeline 的复刻：行程与充电会话合并成一张
 * 按时间倒序的事件表。
 */

const fmtDateTime = (t: number) =>
  new Date(t).toLocaleString('zh-CN', { year: 'numeric', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false })

export default function TimelinePage() {
  const { cars, carId, setCarId, error: carError } = useCar()
  const history = useHistory(carId)
  const entries = useMemo(
    () => sessionTimeline(history.drives, history.charges),
    [history.drives, history.charges]
  )

  return (
    <div>
      <PageTitle title="时间线">
        <CarPicker cars={cars} carId={carId} onChange={setCarId} />
      </PageTitle>
      {carError ? <ErrorNote text={carError} /> : null}
      {history.error ? <ErrorNote text={history.error} /> : null}
      <Panel title="全部事件">
        {history.loading && !entries.length ? (
          <LoadingNote />
        ) : (
          <DataTable
            columns={[{ label: '类型' }, { label: '开始时间' }, { label: '结束时间' }, { label: '内容' }, { label: '摘要' }, { label: '' }]}
            rows={entries.map(entry => [
              entry.kind === 'drive' ? '行驶' : '充电',
              fmtDateTime(entry.start),
              entry.end ? fmtDateTime(entry.end) : '进行中',
              entry.title,
              entry.subtitle,
              <Link
                key={entry.id}
                to={entry.kind === 'drive' ? '/drives' : '/charges'}
                style={{ color: V3.tint, textDecoration: 'none', fontWeight: 600 }}
              >
                查看
              </Link>,
            ])}
            emptyText="暂无行程或充电记录。"
          />
        )}
        {!history.complete ? (
          <div style={{ fontSize: 12, color: V3.label3, marginTop: 8 }}>历史记录较多，当前仅加载了最近部分。</div>
        ) : null}
      </Panel>
    </div>
  )
}
