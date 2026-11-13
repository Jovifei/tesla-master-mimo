import { useMemo } from 'react'
import { Panel } from '../components/v3'
import { StatTile } from '../components/panels'
import { CarPicker, ErrorNote, LoadingNote, PageTitle } from '../components/tm'
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
        <details className="ml-disclosure"><summary>为什么还没有新充电记录？</summary><div>
          <p>这里仅统计当前账号和车辆已返回的记录。接口可访问、Tesla 授权有效和完整历史已同步，是三个不同状态。</p>
          <p>先核对充电页的筛选和最新记录日期，再加载更多或刷新。未返回记录不能解释为未充电；需要服务端同步证据才能判断数据链路。</p>
          <button className="apple-btn-secondary" disabled={history.loading} onClick={() => void history.reload()}>重新读取历史</button>
        </div></details>
        <details className="ml-disclosure"><summary>高级诊断与数据库信息</summary><div>
          <p>网页当前接口不提供数据库表行数、同步队列、证书或 Fleet 配置的完整报告。未检查不等于故障，也不会显示为已通过。</p>
          <p>不会通过此页面唤醒车辆、补造充电或修改历史。</p>
        </div></details>
      </div>
    </div>
  )
}
