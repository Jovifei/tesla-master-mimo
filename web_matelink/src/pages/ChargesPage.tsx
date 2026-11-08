import { useCallback, useEffect, useMemo, useState } from 'react'
import { ApiError, getCarStatus, getChargeDetail, getChargesPage, type ChargeDetail } from '../api/matelink'
import type { WebCarStatus, WebCharge } from '../api/matelink-types'
import { EMPTY_CHARGE_FILTER, chargeAddresses, filterCharges, type ChargeFilter } from '../api/charge-filter'
import { averagePowerKw, unitPricePerKwh } from '../api/panel-data'
import { BatteryPill, Icon, SegControl, Skeleton, TONES, type IconName } from '../components/viz'
import { ChargeRuler, Panel, V3 } from '../components/v3'
import { BarsPanel, DATA_COLORS, ScatterPanel, StackChart, StatTile, fmtDuration, fmtNum, type ChartRow, type StackSeriesDef } from '../components/panels'
import { DataTable, GapTile, MapPanel } from '../components/tm'
import { useCar } from './useCar'
import { useHistory } from './useHistory'

/**
 * 充电记录 — TeslaMate Charges + Charge Details 的复刻：列表页有汇总
 * 统计与未完成充电表；详情页是 TeslaMate Charge Details 的构成
 * （堆叠功率/电量/累计电量大图 + 大数字瓷砖 + 充电曲线 + 地图）。
 * 充电中的会话每 20 秒实时刷新（所有者选项 A）。电压/电流历史采样
 * 后端不记录——仅实时区给出当前值，历史曲线标注待补。
 */

const num = (value: number | null, digits = 0): string => (value == null ? '--' : value.toFixed(digits))

function formatTime(iso: string | null): string {
  if (!iso) return '--'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '--' : date.toLocaleString('zh-CN', { hour12: false })
}

function monthlyEnergy(rows: WebCharge[]): { label: string; value: number }[] {
  const byMonth = new Map<string, { order: number; label: string; value: number }>()
  for (const row of rows) {
    if (!row.startDate) continue
    const date = new Date(row.startDate)
    if (Number.isNaN(date.getTime())) continue
    const key = `${date.getFullYear()}-${date.getMonth()}`
    const entry = byMonth.get(key) ?? { order: date.getFullYear() * 12 + date.getMonth(), label: `${date.getMonth() + 1}月`, value: 0 }
    entry.value += row.energyAddedKwh ?? 0
    byMonth.set(key, entry)
  }
  return [...byMonth.values()]
    .sort((a, b) => a.order - b.order)
    .slice(-6)
    .map(entry => ({ label: entry.label, value: Math.round(entry.value * 10) / 10 }))
}

function modeVisual(mode: WebCharge['chargeMode']): { icon: IconName; color: string } {
  if (mode === 'dc') return { icon: 'zap', color: DATA_COLORS.tealDeep }
  if (mode === 'ac') return { icon: 'plug', color: DATA_COLORS.teal }
  return { icon: 'battery', color: TONES.gray }
}

function countOf(rows: ChartRow[], key: string): number {
  return rows.reduce((count, row) => (row[key] != null ? count + 1 : count), 0)
}

function ChargeDetailView({ carId, charge, onBack }: { carId: number; charge: WebCharge; onBack: () => void }) {
  const [detail, setDetail] = useState<ChargeDetail | null>(null)
  const [error, setError] = useState<string | null>(null)

  const loadDetail = useCallback(async () => {
    try {
      const result = await getChargeDetail(carId, charge.id)
      setDetail(result)
      setError(null)
    } catch (reason: unknown) {
      setError(reason instanceof ApiError ? reason.message : '读取充电详情失败。')
    }
  }, [carId, charge.id])

  useEffect(() => {
    void loadDetail()
  }, [loadDetail])

  // 动态：充电中的会话每 20 秒刷新，曲线实时延伸。
  useEffect(() => {
    if (!charge.isCharging) return
    const timer = window.setInterval(() => void loadDetail(), 20_000)
    return () => window.clearInterval(timer)
  }, [charge.isCharging, loadDetail])

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

      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 8fr) minmax(0, 4fr)', gap: 16, alignItems: 'start' }}>
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
            <StatTile label="费用" value={current.cost != null ? num(current.cost, 2) : '--'} unit={current.cost != null ? (current.currency ?? '元') : undefined} />
            <StatTile label="时长" value={fmtDuration(current.durationMinutes)} />
            <StatTile label="充入电量" value={num(current.energyAddedKwh, 1)} unit="kWh" accent={V3.teal} caption="进入电池的电量" />
            {current.energyUsedKwh != null ? (
              <StatTile label="用电量（桩侧）" value={num(current.energyUsedKwh, 1)} unit="kWh" caption="交流输入侧计量" />
            ) : (
              <StatTile label="用电量（桩侧）" value="--" caption={current.energyUsedNote ?? '本次未发布桩侧计量'} />
            )}
            {current.acEfficiencyPct != null ? (
              <StatTile label="充电效率" value={fmtNum(current.acEfficiencyPct, 1)} unit="%" caption={current.acLossKwh != null ? `交流损耗 ${fmtNum(current.acLossKwh, 1)} kWh` : '进电池 ÷ 桩侧输入'} />
            ) : null}
            {unitPrice != null ? <StatTile label="单价" value={fmtNum(unitPrice, 2)} unit="元/kWh" caption="费用 ÷ 充入电量" /> : null}
            <StatTile label="平均功率" value={avgPower != null ? fmtNum(avgPower, 1) : '--'} unit="kW" caption="充入电量 ÷ 时长" />
            <StatTile label="峰值功率" value={peakPower != null ? fmtNum(peakPower, 1) : '--'} unit="kW" caption={samplePeak != null ? '采样峰值' : '记录峰值'} />
            <StatTile label="电量变化">
              <div className="tabular" style={{ marginTop: 5, fontSize: 24, fontWeight: 700, lineHeight: 1.15, fontVariantNumeric: 'tabular-nums' }}>
                {current.startBatteryLevel != null ? `${Math.round(current.startBatteryLevel)}%` : '--'}
                <span style={{ color: V3.label3, fontSize: 17, margin: '0 4px' }}>→</span>
                {current.endBatteryLevel != null ? `${Math.round(current.endBatteryLevel)}%` : '--'}
              </div>
            </StatTile>
            {summary?.outsideTempC != null ? <StatTile label="室外温度" value={fmtNum(summary.outsideTempC, 1)} unit="°C" /> : null}
            {summary?.chargerVoltageV != null ? <StatTile label="当前电压" value={String(summary.chargerVoltageV)} unit="V" caption="实时值（仅当前快照）" /> : null}
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
  const [rows, setRows] = useState<WebCharge[]>([])
  const [page, setPage] = useState(1)
  const [totalPages, setTotalPages] = useState(1)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [filter, setFilter] = useState<ChargeFilter>(EMPTY_CHARGE_FILTER)
  const [selected, setSelected] = useState<WebCharge | null>(null)
  const [status, setStatus] = useState<WebCarStatus | null>(null)
  const history = useHistory(carId)

  const loadPage = useCallback(async (targetCar: number, targetPage: number, append: boolean) => {
    setLoading(true)
    setError(null)
    try {
      const result = await getChargesPage(targetCar, targetPage)
      setRows(previous => (append ? [...previous, ...result.rows] : result.rows))
      setPage(targetPage)
      setTotalPages(result.totalPages)
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : '读取充电记录失败。')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (carId == null) return
    setSelected(null)
    setRows([])
    void loadPage(carId, 1, false)
    getCarStatus(carId).then(setStatus).catch(() => setStatus(null))
  }, [carId, loadPage])

  const visible = useMemo(() => filterCharges(rows, filter), [rows, filter])
  const addresses = useMemo(() => chargeAddresses(rows), [rows])
  const monthly = useMemo(() => monthlyEnergy(rows), [rows])

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
  const monthlyTotal = monthly.reduce((sum, entry) => sum + entry.value, 0)
  const snapshot = status?.source === 'telemetry_cache'

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12 }}>
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
            <StatTile label="当前功率" value={num(status.chargerPowerKw, 1)} unit="kW" accent={V3.teal} />
            <StatTile label="已充入" value={num(status.chargeEnergyAddedKwh, 1)} unit="kWh" />
            <StatTile label="预计续航" value={num(status.estimatedRangeKm)} unit="km" caption="按当前电量估算" />
            {status.liveVoltageV != null ? <StatTile label="当前电压" value={String(status.liveVoltageV)} unit="V" /> : null}
            {status.liveCurrentA != null ? <StatTile label="当前电流" value={num(status.liveCurrentA, 1)} unit="A" /> : null}
          </div>
        </Panel>
      ) : null}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 12 }}>
        <StatTile label="充电次数" value={summary.count.toLocaleString('zh-CN')} caption={history.complete ? '全部记录' : '已加载记录'} />
        <StatTile label="总充电时长" value={fmtDuration(summary.totalDurationMin)} />
        <StatTile label="总充入电量" value={fmtNum(summary.totalAdded, 1)} unit="kWh" accent={V3.teal} />
        {summary.totalUsed != null ? (
          <StatTile label="总用电量（桩侧）" value={fmtNum(summary.totalUsed, 1)} unit="kWh" caption={`基于 ${summary.usedCount} 次已发布桩侧计量的充电`} />
        ) : (
          <GapTile label="总用电量（桩侧）" need="交流充电的桩侧输入计量（本次数据尚无已发布值）" />
        )}
        <StatTile label="总费用" value={summary.hasCost ? fmtNum(summary.totalCost, 2) : '--'} unit={summary.hasCost ? '元' : undefined} />
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

      {monthly.length ? (
        <Panel title="近六个月充入电量">
          <BarsPanel
            data={monthly}
            unit="kWh"
            color={DATA_COLORS.teal}
            height={170}
            summary={<span>基于已加载记录 · 合计 <span className="tabular" style={{ fontWeight: 600, color: V3.label }}>{monthlyTotal.toFixed(1)} kWh</span></span>}
          />
        </Panel>
      ) : null}

      {loading && !visible.length ? (
        <>
          <Skeleton height={132} />
          <Skeleton height={132} />
          <Skeleton height={132} />
        </>
      ) : (
        <section
          style={{
            background: V3.surface, borderRadius: 20, boxShadow: V3.cardShadow,
            border: `0.5px solid ${V3.separator}`, overflow: 'hidden',
          }}
        >
          {visible.map(charge => {
            const visual = modeVisual(charge.chargeMode)
            return (
              <button
                key={charge.id}
                onClick={() => setSelected(charge)}
                style={{ display: 'flex', alignItems: 'center', gap: 14, width: '100%', textAlign: 'left', background: 'none', border: 'none', borderTop: `0.5px solid ${V3.separator}`, padding: '14px 16px', cursor: 'pointer', color: 'inherit' }}
              >
                <span style={{ flexShrink: 0, width: 40, height: 40, borderRadius: 20, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', background: `${visual.color}1f`, color: visual.color }}>
                  <Icon name={visual.icon} size={20} />
                </span>
                <span style={{ flex: 1, minWidth: 0 }}>
                  <span style={{ display: 'block', fontSize: 14.5, fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{charge.address ?? '未知地点'}</span>
                  <span className="text-secondary tabular" style={{ display: 'block', fontSize: 12.5, marginTop: 3 }}>
                    {formatTime(charge.startDate)}
                    {charge.chargeMode ? ` · ${charge.chargeMode === 'dc' ? '直流快充' : '交流充电'}` : ''}
                  </span>
                  <span style={{ display: 'block', marginTop: 7 }}>
                    <BatteryPill from={charge.startBatteryLevel} to={charge.endBatteryLevel} />
                  </span>
                </span>
                <span style={{ flexShrink: 0, textAlign: 'right' }}>
                  <span className="tabular" style={{ display: 'block', fontSize: 20, fontWeight: 700, lineHeight: 1.1 }}>
                    {num(charge.energyAddedKwh, 1)}
                    <span className="text-secondary" style={{ fontSize: 12, fontWeight: 500, marginLeft: 3 }}>kWh</span>
                  </span>
                  {charge.cost != null ? (
                    <span style={{ display: 'inline-block', marginTop: 6, background: 'var(--apple-teal-soft)', color: 'var(--apple-teal)', borderRadius: 999, padding: '2px 9px', fontSize: 12, fontWeight: 600 }} className="tabular">
                      {num(charge.cost, 2)} {charge.currency ?? '元'}
                    </span>
                  ) : null}
                </span>
                <span className="text-tertiary" style={{ flexShrink: 0, display: 'inline-flex' }}><Icon name="chevron" size={16} /></span>
              </button>
            )
          })}
          {!visible.length && !loading ? (
            <p className="text-secondary" style={{ fontSize: 14, padding: 16 }}>没有符合条件的充电记录。</p>
          ) : null}
        </section>
      )}
      {loading && visible.length ? <Skeleton height={72} radius={14} /> : null}

      {page < totalPages ? (
        <button className="apple-btn-secondary" disabled={loading} onClick={() => carId != null && void loadPage(carId, page + 1, true)}>
          加载更多
        </button>
      ) : null}
    </div>
  )
}
