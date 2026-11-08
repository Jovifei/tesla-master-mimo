import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { getCarStatus } from '../api/matelink'
import type { WebCarStatus } from '../api/matelink-types'
import { aggregateEfficiency, estimateCapacityKwh } from '../api/drive-energy.ts'
import { Panel, V3 } from '../components/v3'
import { TILE_COLORS, fmtNum } from '../components/panels'
import { CarPicker, DonutShares, ErrorNote, GapPanel, LoadingNote, PageTitle } from '../components/tm'
import { useCar } from './useCar'
import { useHistory } from './useHistory'

/**
 * 电池健康 — 完全照 TeslaMate Battery Health 的构成复刻（所有者
 * 2026-11-08 18:39 指令「完全抄他的」）：第一行 电池容量 / 额定
 * 续航 / 行程统计 三组键值大数字 + 交直流用电量饼图（跨两行）；
 * 第二行 预计衰减仪表 / 电池健康度渐变条 / 当前电量分段格 / 效率 /
 * 当前储存电量 / 充电统计（黄色数值）；整行 电池容量与里程关系图。
 * 容量与衰减类参数后端无数据来源（/battery-health 对原生车恒返回
 * 空），按待补形态保留原图位——版式与原版一致，数字不造假。
 */

const tabular = { fontVariantNumeric: 'tabular-nums' } as const
const GAP_VALUE = '待补数据'

function KvPanel({ title, rows, valueColor }: { title: string; rows: { label: string; value: ReactNode; gap?: boolean }[]; valueColor?: string }) {
  return (
    <section style={{ background: V3.surface, borderRadius: 20, padding: 17, boxShadow: V3.cardShadow, border: `0.5px solid ${V3.separator}`, minWidth: 0 }}>
      <h2 style={{ fontSize: 15, fontWeight: 600, color: V3.label, marginBottom: 12 }}>{title}</h2>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 13 }}>
        {rows.map((row, i) => (
          <div key={i} style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: 12 }}>
            <span style={{ fontSize: 13.5, color: V3.label2 }}>{row.label}</span>
            {row.gap ? (
              <span style={{ fontSize: 12.5, color: V3.label3, fontWeight: 600 }}>{GAP_VALUE}</span>
            ) : (
              <span className="tabular" style={{ ...tabular, fontSize: 20, fontWeight: 700, color: valueColor ?? V3.label, whiteSpace: 'nowrap' }}>{row.value}</span>
            )}
          </div>
        ))}
      </div>
    </section>
  )
}

function fmtKm(value: number | null): string {
  return value != null ? `${Math.round(value).toLocaleString('zh-CN')} km` : '--'
}

function fmtEnergy(kwh: number): string {
  return kwh >= 1000 ? `${(kwh / 1000).toFixed(2)} MWh` : `${fmtNum(kwh, 1)} kWh`
}

/** Current SOC as TeslaMate's segmented cell bar (cells colored by position). */
function SocCells({ soc }: { soc: number | null }) {
  const cells = 24
  const filled = soc != null ? Math.round((Math.max(0, Math.min(100, soc)) / 100) * cells) : 0
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
      <div style={{ display: 'flex', gap: 2.5, flex: 1 }}>
        {Array.from({ length: cells }, (_, i) => {
          const f = i / cells
          const color = f < 0.2 ? TILE_COLORS.red : f < 0.45 ? '#e8a33d' : TILE_COLORS.green
          return (
            <span
              key={i}
              style={{ flex: 1, height: 16, borderRadius: 3, background: i < filled ? color : 'rgba(60,50,35,0.10)' }}
            />
          )
        })}
      </div>
      <span className="tabular" style={{ ...tabular, fontSize: 14, fontWeight: 700, color: TILE_COLORS.green }}>
        {soc != null ? `${fmtNum(soc, 1)}%` : '--'}
      </span>
    </div>
  )
}

function MiniPanel({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section style={{ background: V3.surface, borderRadius: 20, padding: '13px 15px', boxShadow: V3.cardShadow, border: `0.5px solid ${V3.separator}`, minWidth: 0 }}>
      <div style={{ fontSize: 12.5, fontWeight: 600, color: V3.label3, marginBottom: 8 }}>{title}</div>
      {children}
    </section>
  )
}

function GapMiniPanel({ title, children, need }: { title: string; children: ReactNode; need: string }) {
  return (
    <section style={{ background: V3.surface2, borderRadius: 20, padding: '13px 15px', border: '1px dashed rgba(60,50,35,0.25)', minWidth: 0 }}>
      <div style={{ fontSize: 12.5, fontWeight: 600, color: V3.label3, marginBottom: 8 }}>{title}</div>
      {children}
      <div style={{ fontSize: 11.5, color: V3.label3, marginTop: 8, lineHeight: 1.5 }}>
        <span style={{ fontWeight: 700, color: V3.warningText }}>待补数据</span>：{need}
      </div>
    </section>
  )
}

export default function BatteryPage() {
  const { cars, car, carId, setCarId, error: carError } = useCar()
  const [status, setStatus] = useState<WebCarStatus | null>(null)
  const history = useHistory(carId)

  useEffect(() => {
    if (carId == null) return
    let cancelled = false
    getCarStatus(carId)
      .then(next => {
        if (!cancelled) setStatus(next)
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [carId])

  const driveStats = useMemo(() => {
    const logged = history.drives.reduce((sum, d) => sum + (d.distanceKm ?? 0), 0)
    const starts = history.drives.flatMap(d => (d.odometerStartKm != null ? [d.odometerStartKm] : []))
    const ends = history.drives.flatMap(d => (d.odometerEndKm != null ? [d.odometerEndKm] : []))
    const span = starts.length && ends.length ? Math.max(...ends) - Math.min(...starts) : null
    return {
      logged,
      span,
      lost: span != null && span >= logged ? span - logged : null,
    }
  }, [history.drives])

  const chargingStats = useMemo(() => {
    const all = history.charges
    const totalAdded = all.reduce((sum, c) => sum + (c.energyAddedKwh ?? 0), 0)
    const usedRows = all.filter(c => c.energyUsedKwh != null)
    const totalUsed = usedRows.reduce((sum, c) => sum + (c.energyUsedKwh ?? 0), 0)
    return {
      count: all.length,
      totalAdded,
      totalUsed: usedRows.length ? totalUsed : null,
      usedCount: usedRows.length,
      efficiencyPct: usedRows.length && totalUsed > 0 ? (totalAdded / totalUsed) * 100 : null,
    }
  }, [history.charges])

  // AC/DC pie follows the original panel （用电量）: pile-side energy
  // where published, charge-in energy otherwise (caliber in caption).
  const pieEnergy = useMemo(() => {
    const byMode = (mode: 'ac' | 'dc') =>
      history.charges
        .filter(c => c.chargeMode === mode)
        .reduce((sum, c) => sum + (c.energyUsedKwh ?? c.energyAddedKwh ?? 0), 0)
    return { ac: byMode('ac'), dc: byMode('dc') }
  }, [history.charges])

  const efficiency = useMemo(() => aggregateEfficiency(history.drives, car?.model ?? null), [history.drives, car?.model])
  const soc = status?.batteryLevel ?? null
  const storedEstimate = useMemo(() => {
    if (soc == null) return null
    const capacity = estimateCapacityKwh(car?.model ?? null)
    return { kwh: (capacity.kwh * soc) / 100, capacityKwh: capacity.kwh }
  }, [soc, car?.model])

  return (
    <div>
      <PageTitle title="电池健康">
        <CarPicker cars={cars} carId={carId} onChange={setCarId} />
      </PageTitle>
      {carError ? <ErrorNote text={carError} /> : null}
      {history.error ? <ErrorNote text={history.error} /> : null}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, minmax(0, 1fr))', gap: 16, alignItems: 'stretch' }}>
          <KvPanel
            title="电池容量"
            rows={[
              { label: '可用容量（新车）', value: '', gap: true },
              { label: '可用容量（当前）', value: '', gap: true },
              { label: '差值', value: '', gap: true },
            ]}
          />
          <KvPanel
            title="续航（额定）"
            rows={[
              { label: '最大续航（新车）', value: '', gap: true },
              { label: '最大续航（当前）', value: '', gap: true },
              { label: '已损失续航', value: '', gap: true },
            ]}
          />
          <KvPanel
            title="行程统计"
            rows={[
              { label: '已记录里程', value: fmtKm(driveStats.logged) },
              { label: '覆盖里程', value: fmtKm(driveStats.span) },
              { label: '里程表', value: fmtKm(status?.odometerKm ?? null) },
              { label: '未记录里程', value: fmtKm(driveStats.lost) },
            ]}
          />
          <div style={{ gridRow: 'span 2', minWidth: 0, display: 'flex' }}>
            <Panel title="交流 / 直流用电量" style={{ flex: 1 }}>
              {history.loading && !history.charges.length ? (
                <LoadingNote />
              ) : (
                <DonutShares
                  parts={[
                    { label: '交流', value: pieEnergy.ac, color: TILE_COLORS.green },
                    { label: '直流', value: pieEnergy.dc, color: TILE_COLORS.orange },
                  ]}
                  unit="kWh"
                  size={170}
                />
              )}
              <p style={{ fontSize: 12, color: V3.label3, marginTop: 8, lineHeight: 1.6 }}>按桩侧用电量统计，未发布桩侧计量的充电以充入电量计入。</p>
            </Panel>
          </div>

          <GapMiniPanel title="预计衰减" need="新车与当前的可用容量（电池健康证据序列）">
            <svg viewBox="0 0 200 112" style={{ width: '100%', maxWidth: 230, display: 'block', margin: '2px auto 0' }} role="img" aria-label="预计衰减仪表（待补）">
              <path d="M 18 100 A 82 82 0 0 1 182 100" fill="none" stroke="rgba(60,50,35,0.12)" strokeWidth={15} strokeLinecap="round" />
              <text x={100} y={92} textAnchor="middle" fontSize={17} fontWeight={600} fill={V3.label3}>-- %</text>
            </svg>
          </GapMiniPanel>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 12, minWidth: 0 }}>
            <GapMiniPanel title="电池健康度" need="同上：容量保持率序列">
              <div style={{ height: 18, borderRadius: 6, background: 'linear-gradient(90deg, rgba(217,83,79,0.35), rgba(232,163,61,0.35), rgba(78,158,87,0.35))' }} />
            </GapMiniPanel>
            <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 7fr) minmax(0, 5fr)', gap: 12 }}>
              <MiniPanel title="当前电量">
                <SocCells soc={soc} />
              </MiniPanel>
              <MiniPanel title="效率">
                <div className="tabular" style={{ ...tabular, fontSize: 27, fontWeight: 700, color: V3.label, lineHeight: 1.1 }}>
                  {efficiency.whPerKm != null ? Math.round(efficiency.whPerKm) : '--'}
                  <span style={{ fontSize: 12.5, fontWeight: 500, color: V3.label3 }}> Wh/km</span>
                </div>
                <div style={{ fontSize: 11.5, color: V3.label3, marginTop: 4 }}>
                  {efficiency.totalCount > 0 ? `本机口径 · ${efficiency.resolvedCount}/${efficiency.totalCount} 次行程` : '暂无行程'}
                </div>
              </MiniPanel>
            </div>
            <MiniPanel title="当前储存电量">
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <div style={{ flex: 1, height: 14, borderRadius: 5, background: 'rgba(60,50,35,0.08)', overflow: 'hidden' }}>
                  <div
                    style={{
                      height: '100%',
                      width: soc != null ? `${Math.max(0, Math.min(100, soc))}%` : '0%',
                      background: 'linear-gradient(90deg, #d9534f, #e8a33d, #4e9e57, #2f7fc4)',
                      backgroundSize: '300px 100%',
                    }}
                  />
                </div>
                <span className="tabular" style={{ ...tabular, fontSize: 14, fontWeight: 700, color: TILE_COLORS.blue }}>
                  {storedEstimate != null ? `${fmtNum(storedEstimate.kwh, 1)} kWh` : '--'}
                </span>
              </div>
              <div style={{ fontSize: 11.5, color: V3.label3, marginTop: 6 }}>
                {storedEstimate != null ? `估算：按车型容量 ${fmtNum(storedEstimate.capacityKwh, 1)} kWh × 当前电量` : '等待状态快照'}
              </div>
            </MiniPanel>
          </div>

          <KvPanel
            title="充电统计"
            valueColor={TILE_COLORS.amber}
            rows={[
              { label: '充电次数', value: chargingStats.count.toLocaleString('zh-CN') },
              { label: '充电循环', value: '', gap: true },
              { label: '总充入电量', value: fmtEnergy(chargingStats.totalAdded) },
              { label: '总用电量', value: chargingStats.totalUsed != null ? fmtEnergy(chargingStats.totalUsed) : '--' },
              { label: '充电效率', value: chargingStats.efficiencyPct != null ? `${fmtNum(chargingStats.efficiencyPct, 1)}%` : '--' },
            ]}
          />
        </div>
        {chargingStats.totalUsed != null ? (
          <p style={{ fontSize: 12, color: V3.label3, marginTop: -6 }}>
            总用电量与充电效率基于 {chargingStats.usedCount} 次已发布桩侧计量的充电；充电循环 = 总充入电量 ÷ 可用容量，容量数据待补后自动给出。
          </p>
        ) : null}

        <GapPanel
          title="电池容量与里程关系"
          meaning="每次容量估算对累计里程的散点与趋势线——看容量随里程衰减的轨迹（原版整行大图）。"
          need="电池健康证据序列——后端 /battery-health 对原生车辆恒返回空；证据目前仅存在于 TeslaMate 归档导入的车辆"
          height={250}
        />
      </div>
    </div>
  )
}
