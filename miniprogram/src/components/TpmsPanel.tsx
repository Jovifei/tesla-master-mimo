import { Button, Text, View } from '@tarojs/components'
import { useState } from 'react'
import { restoreTpmsSnapshot, tpmsMissingLabel, TPMS_WHEELS, type TpmsReport, type TpmsSnapshot } from '../domain/tpms'
import type { TpmsHistory } from '../services/tpms_cache'
import MetricValue from './MetricValue'
import TpmsHistoryChart from './TpmsHistoryChart'

const wheelNames = { fl: '左前轮', fr: '右前轮', rl: '左后轮', rr: '右后轮' }
type Props = { snapshot?: TpmsSnapshot | null; report?: TpmsReport | null; history?: TpmsHistory | null; cacheReason?: string | null; now?: number }

export default function TpmsPanel({ snapshot, report, history, cacheReason, now = Date.now() }: Props) {
  const [evidenceExpanded, setEvidenceExpanded] = useState(false)
  const [expanded, setExpanded] = useState(false)
  const [historyPage, setHistoryPage] = useState(0)
  const current = restoreTpmsSnapshot(snapshot, now)
  const points = [...(history?.points ?? [])].sort((a, b) => Date.parse(b.observedAt) - Date.parse(a.observedAt))
  const pages = Math.ceil(points.length / 20)
  const page = Math.max(0, Math.min(historyPage, pages - 1))
  return <View className="tpms-section">
    <View className="tpms-heading"><Text className="section-title">胎压</Text></View>
    <View className="tpms-grid">{TPMS_WHEELS.map(wheel => {
      const row = current.wheels[wheel]
      return <View className="tpms-wheel-card" key={wheel}>
        <MetricValue label={wheelNames[wheel]} value={row.pressureBar ?? report?.[wheel] ?? null} unit="bar" digits={2} />
        {row.state === 'stale' ? <Text className="tpms-age">旧观测</Text> : null}
        {row.pressureBar == null && report?.[wheel] != null ? <Text className="tpms-age">观测未核验</Text> : null}
      </View>
    })}</View>
    {history?.limited ? <Text className="metric-source">本机胎压记录已按保留上限截取。</Text> : null}
    {cacheReason === 'cache_budget' || cacheReason === 'storage_unavailable' || cacheReason === 'ambiguous_archive' ? <Text className="metric-source">本次胎压观测未保存；已有本机记录保留。</Text> : null}
    <Button className="tpms-evidence-toggle" onClick={() => setEvidenceExpanded(!evidenceExpanded)}>{evidenceExpanded ? '收起观测依据与本机历史' : '观测依据与本机历史'}</Button>
    {evidenceExpanded ? <View className="tpms-evidence">
      <Text className="muted">时间为各轮遥测到达采集端的时间；超过15分钟标记旧观测，不代表轮胎传感器采样时间。</Text>
      {TPMS_WHEELS.map(wheel => {
        const row = current.wheels[wheel]
        return <View key={wheel}>
          <Text className="metric-source">{wheelNames[wheel]} · {row.state === 'unknown' ? `${tpmsMissingLabel(row.reason)}${report?.[wheel] != null ? '；另有仅供显示的胎压报告值' : ''}` : `车辆遥测 · ${row.state === 'stale' ? '旧观测' : '已观测'} · ${row.observedAt}`}</Text>
          <Text className="metric-source">本机记录 {history?.points.filter(point => point.wheel === wheel).length ?? 0} 个合格样本</Text>
        </View>
      })}
    <Text className="muted">本机前台观测历史 · 最多30天、每轮500点；退出微信后不保证连续采集，不能代替 App 的已保存历史。</Text>
    {history?.points.length ? <Text className="metric-source">本机覆盖：{history.oldestAt} 至 {history.latestAt} · {history.points.length} 点{history.limited ? ' · 已按保留上限截取' : ''}</Text>
      : <Text className="muted">暂无合格的本机前台历史；缺少逐轮值、bar单位、来源或独立时间时不生成样本。</Text>}
    <TpmsHistoryChart history={history} now={now} />
    {points.length ? <Button className="button-secondary" onClick={() => { setExpanded(!expanded); setHistoryPage(0) }}>{expanded ? '收起本机记录' : '查看本机记录'}</Button> : null}
    {expanded ? <View>
      {points.slice(page * 20, (page + 1) * 20).map(point => <View className="readiness-item" key={`${point.wheel}-${point.observedAt}`}>
        <Text>{wheelNames[point.wheel]} · {point.pressureBar} bar</Text>
        <Text className="metric-source">车辆遥测 · {point.observedAt}</Text>
      </View>)}
      <Text className="metric-source">本机记录第 {page + 1}/{pages} 页 · 每页最多20点</Text>
      {page > 0 ? <Button className="button-secondary" onClick={() => setHistoryPage(page - 1)}>较新记录</Button> : null}
      {page + 1 < pages ? <Button className="button-secondary" onClick={() => setHistoryPage(page + 1)}>较早记录</Button> : null}
    </View> : null}
    {cacheReason === 'cache_budget' || cacheReason === 'storage_unavailable' ? <Text className="muted">本机缓存空间不足或不可写，本次观测尚未保存。</Text> : null}
    {cacheReason === 'ambiguous_archive' ? <Text className="muted">已有本机胎压记录格式暂无法核对；原记录已保留，本次未覆盖。</Text> : null}
    </View> : null}
  </View>
}
