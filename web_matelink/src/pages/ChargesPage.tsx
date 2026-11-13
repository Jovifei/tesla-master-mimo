import { ChargeCalendar } from '../components/ChargeCalendar'
import { usePagedHistory } from './usePagedHistory'
import { useEffect, useMemo, useState } from 'react'
import { ApiError, getCarStatus, getChargeDetail, getChargesPage, type ChargeDetail } from '../api/matelink'
import type { WebCarStatus, WebCharge } from '../api/matelink-types'
import { EMPTY_CHARGE_FILTER, chargeAddresses, filterCharges, type ChargeFilter } from '../api/charge-filter'
import { averagePowerKw, unitPricePerKwh } from '../api/panel-data'
import { BatteryPill, Icon, SegControl, Skeleton } from '../components/viz'
import { ChargeRuler, Panel, V3 } from '../components/v3'
import { DATA_COLORS, ScatterPanel, StackChart, StatTile, TILE_COLORS, fmtDuration, fmtNum, type ChartRow, type StackSeriesDef } from '../components/panels'
import { DataTable, GapTile, MapPanel } from '../components/tm'
import { useCar } from './useCar'
import { useHistory } from './useHistory'

/**
 * 充电记录 — TeslaMate Charges + Charge Details 的复刻（所有者
 * 2026-11-08 18:39 定版）：列表为 TeslaMate 式逐列参数表（位置/
 * 时间/类型/温度/功率/电量/费用/效率），点击进单次详情；详情以
 * 彩色大数字瓷砖呈现（费用绿、时长蓝、功率橙、温度紫），图为
 * 电量变化 + 功率变化曲线。充电中的会话每 20 秒实时刷新；电压、
 * 电池加热只有状态快照当前值，进实时区展示，历史不造假面板。
 */

const num = (value: number | null, digits = 0): string => (value == null ? '--' : value.toFixed(digits))

function formatTime(iso: string | null): string {
  if (!iso) return '--'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '--' : date.toLocaleString('zh-CN', { hour12: false })
}

function countOf(rows: ChartRow[], key: string): number {
  return rows.reduce((count, row) => (row[key] != null ? count + 1 : count), 0)
}

function ChargeDetailView({ carId, charge, onBack }: { carId: number; charge: WebCharge; onBack: () => void }) {
  const [detail, setDetail] = useState<ChargeDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [liveStatus, setLiveStatus] = useState<WebCarStatus | null>(null)

  useEffect(() => {
    const controller=new AbortController();let pending=false
    setDetail(null);setError(null)
    const load=async()=>{
      if(pending || controller.signal.aborted)return
      pending=true
      try{const result=await getChargeDetail(carId,charge.id,controller.signal);if(!controller.signal.aborted){setDetail(result);setError(null)}}
      catch(reason){if(!controller.signal.aborted)setError(reason instanceof ApiError?reason.message:'读取充电详情失败。')}
      finally{pending=false}
    }
    void load()
    const timer=charge.isCharging?window.setInterval(()=>void load(),20_000):null
    return()=>{controller.abort();if(timer!=null)window.clearInterval(timer)}
  },[carId,charge.id,charge.isCharging])

  // 实时区：状态快照的当前功率/电压/电流/电池加热（仅充电中存在当前值；
  // 电池加热无历史序列，历史详情不设加热面板）。
  useEffect(() => {
    if (!charge.isCharging) return
    let cancelled = false
    const fetchStatus = async () => {
      try {
        const next = await getCarStatus(carId)
        if (!cancelled) setLiveStatus(next)
      } catch {
        /* keep last good status */
      }
    }
    void fetchStatus()
    const timer = window.setInterval(() => void fetchStatus(), 20_000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [carId, charge.isCharging])

  const current = detail?.charge ?? charge
  const summary = detail?.summary
  const points = useMemo(() => detail?.points ?? [], [detail])

  const rows: ChartRow[] = useMemo(
    () =>
      points.map(point => ({
        t: Date.parse(point.date),
        power: point.powerKw,
        soc: point.batteryLevel,
        cum: point.energyAddedKwh,
        temp: point.temperatureC,
      })),
    [points]
  )

  const mainSeries: StackSeriesDef[] = [
    {
      key: 'power', title: '功率', unit: 'kW', color: DATA_COLORS.teal, height: 190, digits: 1, domain: [0, 'auto'],
      emptyReason: countOf(rows, 'power') === 1 ? '仅 1 个采样点，无法绘制曲线' : countOf(rows, 'power') === 0 ? '无功率采样数据' : undefined,
    },
    {
      key: 'soc', title: '电量', unit: '%', color: DATA_COLORS.tealDeep, height: 90, digits: 0, domain: [0, 100],
      emptyReason: countOf(rows, 'soc') === 1 ? '仅 1 个采样点，无法绘制曲线' : countOf(rows, 'soc') === 0 ? '无电量采样数据' : undefined,
    },
    {
      key: 'cum', title: '累计充入电量', unit: 'kWh', color: DATA_COLORS.graphite, height: 80, digits: 1,
      emptyReason: countOf(rows, 'cum') < 2 ? '采样不足' : undefined,
    },
  ]
  const tempSeries: StackSeriesDef[] = [
    { key: 'temp', title: '室外温度', unit: '°C', color: DATA_COLORS.graphite, height: 84, digits: 1, emptyReason: countOf(rows, 'temp') < 2 ? '采样不足' : undefined },
  ]

  const scatterPoints = useMemo(
    () =>
      points.flatMap(point =>
        point.powerKw != null && point.batteryLevel != null ? [{ x: point.batteryLevel, y: point.powerKw }] : []
      ),
    [points]
  )

  const samplePeak = useMemo(() => {
    const values = points.flatMap(point => (point.powerKw != null ? [point.powerKw] : []))
    return values.length ? Math.max(...values) : null
  }, [points])
  const peakPower = samplePeak ?? summary?.chargerPowerKw ?? current.maxPowerKw
  const unitPrice = unitPricePerKwh(current)
  const avgPower = averagePowerKw(current)
  const quarantined = detail?.reason === 'quarantined_history'

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div>
        <button className="apple-btn-secondary" onClick={onBack} style={{ display: 'inline-flex', alignItems: 'center', gap: 2 }}>
          <span style={{ display: 'inline-flex', transform: 'rotate(180deg)' }}><Icon name="chevron" size={15} /></span>
          返回列表
        </button>
        <h1 style={{ fontSize: 26, fontWeight: 700, marginTop: 12 }}>{current.address ?? '未知地点'}</h1>
        <p className="text-secondary" style={{ fontSize: 13, marginTop: 4 }}>
          {formatTime(current.startDate)} → {formatTime(current.endDate)}
          {current.chargeMode ? ` · ${current.chargeMode === 'dc' ? '直流快充' : '交流充电'}` : ''}
          {current.isCharging ? ' · 充电中（每 20 秒刷新）' : ''}
        </p>
      </div>

      {error ? <Panel><p style={{ color: V3.errorText, fontSize: 14.5 }}>{error}</p></Panel> : null}

      {charge.isCharging && liveStatus ? (
        <Panel title="实时充电" action={<span style={{ fontSize: 12, color: V3.label3 }}>状态快照当前值 · 每 20 秒刷新</span>}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: 12 }}>
            <StatTile label="当前功率" value={liveStatus.chargerPowerKw != null ? fmtNum(liveStatus.chargerPowerKw, 1) : '--'} unit="kW" accent={TILE_COLORS.orange} />
            {liveStatus.liveVoltageV != null ? <StatTile label="当前电压" value={String(Math.round(liveStatus.liveVoltageV))} unit="V" accent={TILE_COLORS.purple} /> : null}
            {liveStatus.liveCurrentA != null ? <StatTile label="当前电流" value={fmtNum(liveStatus.liveCurrentA, 1)} unit="A" /> : null}
            {liveStatus.batteryHeaterOn != null ? (
              <StatTile label="电池加热" value={liveStatus.batteryHeaterOn ? '开' : '关'} accent={liveStatus.batteryHeaterOn ? TILE_COLORS.red : undefined} caption="当前状态（无历史记录）" />
            ) : null}
            <StatTile label="当前电量" value={liveStatus.batteryLevel != null ? String(Math.round(liveStatus.batteryLevel)) : '--'} unit="%" accent={TILE_COLORS.green} />
          </div>
        </Panel>
      ) : null}

      <div className="ml-detail-columns" style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 8fr) minmax(0, 4fr)', gap: 16, alignItems: 'start' }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16, minWidth: 0 }}>
          {!detail ? (
            <>
              <Skeleton height={340} />
              <Skeleton height={240} />
            </>
          ) : quarantined ? (
            <Panel><p style={{ fontSize: 14, color: V3.label2 }}>这条记录处于隔离状态，曲线不可用。</p></Panel>
          ) : !points.length ? (
            <Panel><p style={{ fontSize: 14, color: V3.label2 }}>这次充电没有可用的采样曲线。</p></Panel>
          ) : (
            <>
              <Panel title="充电过程">
                <StackChart data={rows} series={mainSeries} syncKey={`charge-main-${charge.id}`} />
              </Panel>
              <Panel title="充电曲线（功率随电量变化）">
                {scatterPoints.length >= 3 ? (
                  <ScatterPanel points={scatterPoints} xLabel="电量" yLabel="功率" />
                ) : (
                  <p style={{ fontSize: 13.5, color: V3.label2 }}>采样点不足（{scatterPoints.length} 个），无法绘制充电曲线。</p>
                )}
              </Panel>
              {tempSeries[0].emptyReason == null ? (
                <Panel title="温度采样">
                  <StackChart data={rows} series={tempSeries} syncKey={`charge-temp-${charge.id}`} />
                </Panel>
              ) : null}
              <Panel title="电压与电流（历史采样）">
                <p style={{ fontSize: 13.5, color: V3.label3, lineHeight: 1.7 }}>
                  待补数据：充电逐点采样未记录电压与电流，历史电压/电流曲线暂不可用。需要后端在充电采样中记录电压、电流。
                  {summary?.chargerVoltageV != null || current.isCharging ? (
                    <><br />当前值：电压 {summary?.chargerVoltageV != null ? `${summary.chargerVoltageV} V` : '--'} · 电流 {summary?.currentA != null ? `${summary.currentA} A` : '--'}。</>
                  ) : null}
                </p>
              </Panel>
            </>
          )}
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 16, minWidth: 0 }}>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <StatTile label="费用" value={current.cost != null ? num(current.cost, 2) : '--'} unit={current.cost != null ? (current.currency ?? '元') : undefined} accent={TILE_COLORS.green} />
            <StatTile label="时长" value={fmtDuration(current.durationMinutes)} accent={TILE_COLORS.blue} />
            <StatTile label="充电类型" value={current.chargeMode === 'dc' ? '直流快充' : current.chargeMode === 'ac' ? '交流充电' : '未知'} />
            <StatTile label="充入电量" value={num(current.energyAddedKwh, 1)} unit="kWh" accent={TILE_COLORS.green} caption="进入电池的电量" />
            {current.energyUsedKwh != null ? (
              <StatTile label="用电量（桩侧）" value={num(current.energyUsedKwh, 1)} unit="kWh" accent={TILE_COLORS.amber} caption="交流输入侧计量" />
            ) : (
              <StatTile label="用电量（桩侧）" value="--" caption={current.energyUsedNote ?? '本次未发布桩侧计量'} />
            )}
            {current.acEfficiencyPct != null ? (
              <StatTile label="充电效率" value={fmtNum(current.acEfficiencyPct, 1)} unit="%" accent={TILE_COLORS.blue} caption={current.acLossKwh != null ? `交流损耗 ${fmtNum(current.acLossKwh, 1)} kWh` : '进电池 ÷ 桩侧输入'} />
            ) : null}
            {unitPrice != null ? <StatTile label="单价" value={fmtNum(unitPrice, 2)} unit="元/kWh" caption="费用 ÷ 充入电量" /> : null}
            <StatTile label="平均功率" value={avgPower != null ? fmtNum(avgPower, 1) : '--'} unit="kW" accent={TILE_COLORS.orange} caption="充入电量 ÷ 时长" />
            <StatTile label="峰值功率" value={peakPower != null ? fmtNum(peakPower, 1) : '--'} unit="kW" accent={TILE_COLORS.orange} caption={samplePeak != null ? '采样峰值' : '记录峰值'} />
            <StatTile label="电量变化">
              <div className="tabular" style={{ marginTop: 5, fontSize: 24, fontWeight: 700, lineHeight: 1.15, fontVariantNumeric: 'tabular-nums' }}>
                <span style={{ color: TILE_COLORS.green }}>{current.startBatteryLevel != null ? `${Math.round(current.startBatteryLevel)}%` : '--'}</span>
                <span style={{ color: V3.label3, fontSize: 17, margin: '0 4px' }}>→</span>
                <span style={{ color: TILE_COLORS.blue }}>{current.endBatteryLevel != null ? `${Math.round(current.endBatteryLevel)}%` : '--'}</span>
              </div>
            </StatTile>
            {summary?.outsideTempC != null ? <StatTile label="室外温度" value={fmtNum(summary.outsideTempC, 1)} unit="°C" accent={TILE_COLORS.purple} /> : null}
            {summary?.chargerVoltageV != null ? <StatTile label="当前电压" value={String(summary.chargerVoltageV)} unit="V" accent={TILE_COLORS.purple} caption="实时值（仅当前快照）" /> : null}
            {summary?.currentA != null ? <StatTile label="当前电流" value={fmtNum(summary.currentA, 1)} unit="A" caption="实时值（仅当前快照）" /> : null}
          </div>

          {current.latitude != null && current.longitude != null ? (
            <MapPanel title="充电地点" points={[{ lat: current.latitude, lng: current.longitude, label: current.address ?? '充电地点' }]} height={220} />
          ) : (
            <Panel title="充电地点">
              <p style={{ fontSize: 15, fontWeight: 500, lineHeight: 1.55 }}>{current.address ?? '未知地点'}</p>
              <p style={{ fontSize: 13, color: V3.label3, marginTop: 6, lineHeight: 1.6 }}>
                {current.chargeMode === 'dc' ? '直流快充' : current.chargeMode === 'ac' ? '交流充电' : '充电类型未知'}
                {current.isCharging ? ' · 充电进行中' : ''}
              </p>
              <p style={{ fontSize: 12, color: V3.label3, marginTop: 10, lineHeight: 1.6 }}>本次记录未包含坐标，地点以文字呈现。</p>
            </Panel>
          )}
        </div>
      </div>
    </div>
  )
}

export default function ChargesPage() {
  const { cars, car, carId, setCarId, error: carError } = useCar()
  const {rows, page, totalPages, loading, error, reload, loadMore} = usePagedHistory<WebCharge>(carId,getChargesPage)
  const [filter, setFilter] = useState<ChargeFilter>(EMPTY_CHARGE_FILTER)
  const [selected, setSelected] = useState<WebCharge | null>(null)
  const [status, setStatus] = useState<WebCarStatus | null>(null)
  const history = useHistory(carId)

  useEffect(() => {
    const controller=new AbortController(); setSelected(null);setStatus(null)
    if(carId!=null)getCarStatus(carId,controller.signal).then(s=>{if(!controller.signal.aborted)setStatus(s)}).catch(()=>{})
    return()=>controller.abort()
  },[carId])

  const visible = useMemo(() => filterCharges(rows, filter), [rows, filter])
  const addresses = useMemo(() => chargeAddresses(rows), [rows])
  const dailyRows = useMemo(() => filterCharges(history.charges, filter), [history.charges, filter])

  const summary = useMemo(() => {
    const all = history.charges
    const totalDurationMin = all.reduce((sum, c) => sum + (c.durationMinutes ?? 0), 0)
    const totalAdded = all.reduce((sum, c) => sum + (c.energyAddedKwh ?? 0), 0)
    const usedValues = all.flatMap(c => (c.energyUsedKwh != null ? [c.energyUsedKwh] : []))
    const totalCost = all.reduce((sum, c) => sum + (c.cost ?? 0), 0)
    return {
      count: all.length,
      totalDurationMin,
      totalAdded,
      totalUsed: usedValues.length ? usedValues.reduce((a, b) => a + b, 0) : null,
      usedCount: usedValues.length,
      totalCost,
      hasCost: all.some(c => c.cost != null),
    }
  }, [history.charges])

  const incomplete = useMemo(
    () => history.charges.filter(c => c.endDate == null || c.isCharging),
    [history.charges]
  )

  if (selected && carId != null) {
    return <ChargeDetailView carId={carId} charge={selected} onBack={() => setSelected(null)} />
  }

  const chargingNow = status?.chargingState?.toLowerCase() === 'charging'
  const snapshot = status?.source === 'telemetry_cache'

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12 }}>
        <button className="apple-btn-secondary" disabled={loading} onClick={() => {void reload();void history.reload()}}>刷新记录</button>
        <h1 style={{ fontSize: 28, fontWeight: 700 }}>充电记录</h1>
        {cars.length > 1 ? (
          <select className="apple-input" style={{ width: 'auto' }} value={carId ?? ''} onChange={event => setCarId(Number(event.target.value))}>
            {cars.map(item => (
              <option key={item.id} value={item.id}>{item.name}</option>
            ))}
          </select>
        ) : null}
      </div>
      {carError ? <Panel><p style={{ color: V3.errorText, fontSize: 14.5 }}>{carError}</p></Panel> : null}
      {error ? <Panel><p style={{ color: V3.errorText, fontSize: 14.5 }}>{error}</p></Panel> : null}

      {chargingNow && status ? (
        <Panel title="正在充电" action={<span style={{ fontSize: 13, color: V3.label3 }}>{car?.name ?? ''} · 实时</span>}>
          <div style={{ display: 'flex', alignItems: 'flex-end', gap: 22, flexWrap: 'wrap' }}>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 4 }}>
              <span className="tabular" style={{ fontSize: 44, fontWeight: 600, lineHeight: 1, color: V3.label }}>
                {status.batteryLevel != null ? Math.round(status.batteryLevel) : '--'}
              </span>
              <span style={{ fontSize: 17, fontWeight: 500, color: V3.label2 }}>%</span>
              <span style={{ fontSize: 12.5, color: V3.label3, marginLeft: 6 }}>当前电量</span>
            </div>
            <div style={{ flex: '1 1 300px', minWidth: 240 }}>
              {status.batteryLevel != null ? <ChargeRuler soc={status.batteryLevel} snapshot={snapshot} /> : null}
            </div>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 12, marginTop: 16 }}>
            <StatTile label="当前功率" value={num(status.chargerPowerKw, 1)} unit="kW" accent={TILE_COLORS.orange} />
            <StatTile label="已充入" value={num(status.chargeEnergyAddedKwh, 1)} unit="kWh" accent={TILE_COLORS.green} />
            <StatTile label="预计续航" value={num(status.estimatedRangeKm)} unit="km" caption="按当前电量估算" />
            {status.liveVoltageV != null ? <StatTile label="当前电压" value={String(Math.round(status.liveVoltageV))} unit="V" accent={TILE_COLORS.purple} /> : null}
            {status.liveCurrentA != null ? <StatTile label="当前电流" value={num(status.liveCurrentA, 1)} unit="A" /> : null}
            {status.batteryHeaterOn != null ? <StatTile label="电池加热" value={status.batteryHeaterOn ? '开' : '关'} accent={status.batteryHeaterOn ? TILE_COLORS.red : undefined} caption="当前状态（无历史记录）" /> : null}
          </div>
        </Panel>
      ) : null}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 12 }}>
        <StatTile label="充电次数" value={summary.count.toLocaleString('zh-CN')} caption={history.complete ? '全部记录' : '已加载记录'} />
        <StatTile label="总充电时长" value={fmtDuration(summary.totalDurationMin)} accent={TILE_COLORS.blue} />
        <StatTile label="总充入电量" value={fmtNum(summary.totalAdded, 1)} unit="kWh" accent={TILE_COLORS.green} />
        {summary.totalUsed != null ? (
          <StatTile label="总用电量（桩侧）" value={fmtNum(summary.totalUsed, 1)} unit="kWh" accent={TILE_COLORS.amber} caption={`基于 ${summary.usedCount} 次已发布桩侧计量的充电`} />
        ) : (
          <GapTile label="总用电量（桩侧）" need="交流充电的桩侧输入计量（本次数据尚无已发布值）" />
        )}
        <StatTile label="总费用" value={summary.hasCost ? fmtNum(summary.totalCost, 2) : '--'} unit={summary.hasCost ? '元' : undefined} accent={TILE_COLORS.green} />
      </div>

      {incomplete.length ? (
        <Panel title="未完成充电">
          <DataTable
            columns={[{ label: '开始时间' }, { label: '地点' }, { label: '当前电量' }]}
            rows={incomplete.map(c => [
              formatTime(c.startDate),
              c.address ?? '未知地点',
              c.endBatteryLevel != null ? `${Math.round(c.endBatteryLevel)}%` : '--',
            ])}
          />
        </Panel>
      ) : null}

      <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center' }}>
        <SegControl
          options={[
            { value: 'all', label: '全部' },
            { value: 'ac', label: '交流' },
            { value: 'dc', label: '直流' },
          ]}
          value={filter.mode}
          onChange={mode => setFilter(current => ({ ...current, mode }))}
        />
        <select
          className="apple-input"
          style={{ width: 'auto' }}
          value={filter.address ?? ''}
          onChange={event => setFilter(current => ({ ...current, address: event.target.value || null }))}
        >
          <option value="">全部地点</option>
          {addresses.map(address => (
            <option key={address} value={address}>{address}</option>
          ))}
        </select>
        <button
          onClick={() => setFilter(current => ({ ...current, withCostOnly: !current.withCostOnly }))}
          style={{
            display: 'inline-flex', alignItems: 'center', gap: 6, cursor: 'pointer',
            border: filter.withCostOnly ? '1px solid transparent' : '1px solid var(--apple-separator)',
            background: filter.withCostOnly ? 'var(--apple-teal-soft)' : 'var(--apple-surface)',
            color: filter.withCostOnly ? 'var(--apple-teal)' : 'var(--apple-label-secondary)',
            borderRadius: 999, padding: '6px 14px', fontSize: 13, fontWeight: 600,
            transition: 'all 180ms ease',
          }}
        >
          <Icon name="wallet" size={14} />
          仅看有费用
        </button>
      </div>

      <ChargeCalendar rows={dailyRows} />
      <p className="text-secondary" role="status" style={{fontSize:13,lineHeight:1.7}}>
        {rows[0]?.startDate ? `当前已返回的最新记录：${formatTime(rows[0].startDate)}。` : '尚未收到充电历史。'}
        {visible.length !== rows.length ? `筛选隐藏了 ${rows.length-visible.length} 条。` : ''}
        {page < totalPages ? '还有后续分页，可加载更多。' : '当前接口分页已读完，但不证明车辆同步完整。'}
        期间有充电却未显示时，请核对账号、车辆、筛选和服务端同步；不要清除历史。
      </p>

      {loading && !visible.length ? (
        <>
          <Skeleton height={132} />
          <Skeleton height={132} />
          <Skeleton height={132} />
        </>
      ) : (
        <Panel title="充电记录" action={<span style={{ fontSize: 12, color: V3.label3 }}>逐列参数 · 点击任意一行查看单次充电详情</span>}>
          <DataTable
            columns={[
              { label: '开始时间' },
              { label: '地点' },
              { label: '类型' },
              { label: '时长', align: 'right' },
              { label: '充入 (kWh)', align: 'right' },
              { label: '用电量 (kWh)', align: 'right' },
              { label: '效率', align: 'right' },
              { label: '平均功率 (kW)', align: 'right' },
              { label: '峰值功率 (kW)', align: 'right' },
              { label: '电量变化' },
              { label: '温度', align: 'right' },
              { label: '单价 (元/kWh)', align: 'right' },
              { label: '费用', align: 'right' },
            ]}
            rows={visible.map(charge => {
              const unitPrice = unitPricePerKwh(charge)
              const avgPower = averagePowerKw(charge)
              return [
                formatTime(charge.startDate),
                charge.address ?? '未知地点',
                charge.chargeMode === 'dc' ? '直流' : charge.chargeMode === 'ac' ? '交流' : '--',
                fmtDuration(charge.durationMinutes),
                num(charge.energyAddedKwh, 1),
                num(charge.energyUsedKwh, 1),
                charge.acEfficiencyPct != null ? `${fmtNum(charge.acEfficiencyPct, 1)}%` : '--',
                avgPower != null ? fmtNum(avgPower, 1) : '--',
                num(charge.maxPowerKw, 1),
                <BatteryPill key={charge.id} from={charge.startBatteryLevel} to={charge.endBatteryLevel} />,
                charge.outsideTempAvgC != null ? `${fmtNum(charge.outsideTempAvgC, 1)}°C` : '--',
                unitPrice != null ? fmtNum(unitPrice, 2) : '--',
                charge.cost != null ? `${num(charge.cost, 2)} ${charge.currency ?? '元'}` : '--',
              ]
            })}
            emptyText="没有符合条件的充电记录。"
            onRowClick={index => {
              const charge = visible[index]
              if (charge) setSelected(charge)
            }}
          />
        </Panel>
      )}
      {loading && visible.length ? <Skeleton height={72} radius={14} /> : null}

      {page < totalPages ? (
        <button className="apple-btn-secondary" disabled={loading} onClick={() => void loadMore()}>
          加载更多
        </button>
      ) : null}
    </div>
  )
}
