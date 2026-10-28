import { Button, Canvas, Picker, Slider, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow, useRouter, useUnload } from '@tarojs/taro'
import { useEffect, useRef, useState } from 'react'
import MetricValue, { displayNumber, peakSpeedKmh } from '../../components/MetricValue'
import HistoryDateChips from '../../components/HistoryDateChips'
import LoadingIndicator from '../../components/LoadingIndicator'
import { analysisDay, analysisGroups, analysisKind, analysisTitles, buildParkedWindows, calendarCells, distanceCurveEstimates, efficiencyDistribution, efficiencySpeedGroups, selectCurveSamples } from '../../domain/analysis'
import { isAppDriveAnalysisEligible } from '../../domain/app_history_projection'
import { aggregateDriveHistory, coveredTotal, historyRowKey, isHistoryQuarantined, mergeHistoryByKey } from '../../domain/history'
import { acceptHistoryDetail } from '../../domain/history_controller'
import { emptyHistoryQuery, filterHistory, historyDateTime, historyDetailDateTime, normalizeHistoryQuery, type HistoryQuery } from '../../domain/history_query'
import { sourceLabel } from '../../domain/data_labels'
import { toChineseDisplayAddress } from '../../domain/address_display'
import { analysisApi } from '../../services/analysis_api'
import type { ChargeEvidence, DriveEvidence, ParkedDetail } from '../../services/analysis_types'
import { ApiError, getApiOrigin, getApiSessionGeneration, matelinkApi } from '../../services/api'
import { readAppSession } from '../../services/session'
import { readCarsCache } from '../../services/cars_cache'
import { historyCacheKey, readHistoryCache, writeHistoryCache } from '../../services/history_cache'
import { overlayChargeCosts, type CostedCharge } from '../../services/charge_cost'
import { carSelectionStorageKey, type Car, type Charge, type Drive } from '../../services/types'
import './index.scss'

type HistoryStatus = { drivesComplete: boolean; chargesComplete: boolean; offline: boolean; cached: boolean; reason: string | null }
const initialStatus: HistoryStatus = { drivesComplete: false, chargesComplete: false, offline: false, cached: false, reason: null }
const known = (value: number | null | undefined) => value != null && Number.isFinite(value) ? value : null
const shown = (value: number | null | undefined, unit = '') => known(value) == null ? '暂无数据' : `${displayNumber(value!, 2)}${unit ? ` ${unit}` : ''}`
const costSource = (row: CostedCharge) => row.costProvenance === 'manual' ? '本机手动金额' : row.costProvenance === 'actual' ? '上游实际金额' : '费用未知'

/** Native canvas uses actual evidence instants and breaks its line at missing samples. */
function EvidenceCurve<T extends { date: string }>({ id, title, unit, points, valueOf, selected, onSelect, isCurrent }: { id: string; title: string; unit: string; points: readonly T[]; valueOf: (point: T) => number | null; selected: number; onSelect: (index: number) => void; isCurrent: () => boolean }) {
  const [fullscreen, setFullscreen] = useState(false)
  const data = points.map(point => ({ time: Date.parse(point.date), value: known(valueOf(point)), date: point.date }))
  const plot = selectCurveSamples(data)
  const values = data.flatMap(point => point.value == null ? [] : [point.value])
  const minimum = values.length ? Math.min(0, ...values) : 0, maximum = values.length ? Math.max(0, ...values) : 0
  const range = maximum - minimum || 1, first = data[0]?.time ?? 0, last = data[data.length - 1]?.time ?? first
  useEffect(() => {
    if (values.length === 0) return
    if (typeof Taro.createCanvasContext !== 'function' || typeof Taro.createSelectorQuery !== 'function') return
    let cancelled = false
    Taro.createSelectorQuery().select(`#${id}`).boundingClientRect(result => {
      if (cancelled || !isCurrent() || !result || Array.isArray(result)) return
      const width = result.width, height = result.height, pad = 16
      if (!width || !height) return
      const context = Taro.createCanvasContext(id)
      context.clearRect(0, 0, width, height)
      context.setStrokeStyle('#dce3e8'); context.setLineWidth(1); context.beginPath()
      const baseline = pad + maximum / range * (height - pad * 2)
      context.moveTo(pad, baseline); context.lineTo(width - pad, baseline); context.stroke()
      context.setStrokeStyle('#157a86'); context.setLineWidth(2); context.beginPath()
      for (const point of plot.points) {
        const x = pad + (last === first ? 0 : (point.time - first) / (last - first)) * (width - pad * 2)
        const y = pad + (maximum - point.value) / range * (height - pad * 2)
        if (point.breakBefore) context.moveTo(x, y); else context.lineTo(x, y)
      }
      context.stroke()
      context.setFillStyle('#157a86'); context.beginPath()
      plot.points.forEach((point, index) => {
        if (!point.breakBefore || (index + 1 < plot.points.length && !plot.points[index + 1].breakBefore)) return
        const x = pad + (last === first ? 0 : (point.time - first) / (last - first)) * (width - pad * 2)
        const y = pad + (maximum - point.value) / range * (height - pad * 2)
        context.moveTo(x + 2, y); context.arc(x, y, 2, 0, Math.PI * 2)
      })
      context.fill()
      const selectedPoint = data[Math.min(selected, Math.max(0, data.length - 1))]
      if (selectedPoint && Number.isFinite(selectedPoint.time)) {
        const x = pad + (last === first ? 0 : (selectedPoint.time - first) / (last - first)) * (width - pad * 2)
        context.setStrokeStyle('#b45309'); context.setLineWidth(1); context.beginPath()
        context.moveTo(x, pad); context.lineTo(x, height - pad); context.stroke()
      }
      context.draw()
    }).exec()
    return () => { cancelled = true }
  }, [id, points, title, unit, fullscreen, selected])
  const point = data[Math.min(selected, Math.max(0, data.length - 1))]
  return <View className={`card analysis-curve${fullscreen ? ' analysis-curve-fullscreen' : ''}`}>
    <Text className="section-title">{title}</Text>
    <Button className="analysis-curve-expand" onClick={() => setFullscreen(!fullscreen)}>{fullscreen ? '退出全屏' : '全屏查看'}</Button>
    {values.length ? <>
      <Text className="muted">纵轴 {displayNumber(minimum)} 至 {displayNumber(maximum)} {unit} · 横轴为实际采样时间</Text>
      <Canvas id={id} canvasId={id} className="analysis-canvas" />
      <View className="analysis-axis"><Text>{historyDateTime(points[0]?.date)}</Text><Text>{historyDateTime(points[points.length - 1]?.date)}</Text></View>
      {data.length > 1 ? <Slider min={0} max={data.length - 1} step={1} value={Math.min(selected, data.length - 1)} onChange={event => onSelect(Number(event.detail.value))} /> : null}
      <Text className="analysis-cursor">{point ? `${historyDateTime(point.date)} · ${shown(point.value, unit)}` : '暂无采样'}</Text>
      <Text className="muted">有效值 {values.length}/{data.length} 个；缺失采样断开曲线，保留真实零值与负值。</Text>
      {plot.thinned ? <Text className="muted analysis-plot-thinned">图形部分绘制：从 {plot.sourceCount} 个原始采样中选取 {plot.points.length} 个真实点（最多600），保留极值、零值和未知断点；游标仍读取完整原始采样。</Text> : null}
    </> : <Text className="muted">缺少合格的{title}采样，未生成曲线。</Text>}
  </View>
}

function GroupBars({ title, groups, metric = 'distance' }: { title: string; groups: ReturnType<typeof analysisGroups>; metric?: 'distance' | 'efficiency' }) {
  const shownGroups = groups.slice(-31), unit = metric === 'distance' ? 'km' : 'kWh/100km'
  const max = Math.max(0, ...shownGroups.map(group => Math.abs(group[metric].value ?? 0)))
  return <View className="card analysis-bars"><Text className="section-title">{title}</Text>
    <Text className="muted">当前读取范围内最近 {shownGroups.length} 个有记录区间；未知日期不放入图表。</Text>
    {shownGroups.length ? shownGroups.map(group => <View key={group.key} className="analysis-bar-row">
      <Text>{group.key}</Text><View className="analysis-bar-track">{group[metric].value == null ? null : <View className={`analysis-bar ${group[metric].value! < 0 ? 'analysis-bar-negative' : ''}`} style={{ width: max ? `${Math.abs(group[metric].value!) / max * 100}%` : '0%' }} />}</View>
      <Text>{shown(group[metric].value, unit)}</Text>
    </View>) : <Text className="muted">暂无带合格日期的数据。</Text>}
  </View>
}

export default function AnalysisPage() {
  const params = useRouter().params, kind = analysisKind(params.kind)
  const targetDay = normalizeHistoryQuery({ startDate: params.day }).startDate
  const [cars, setCars] = useState<Car[]>([]), [car, setCar] = useState<Car | null>(null)
  const [drives, setDrives] = useState<Drive[]>([]), [charges, setCharges] = useState<Charge[]>([])
  const [status, setStatus] = useState<HistoryStatus>(initialStatus), [loading, setLoading] = useState(false), [error, setError] = useState<string | null>(null)
  const [query, setQuery] = useState<HistoryQuery>(emptyHistoryQuery())
  const [year, setYear] = useState<string | null>(targetDay ? targetDay.slice(0, 4) : null), [month, setMonth] = useState<string | null>(targetDay ? targetDay.slice(0, 7) : null), [day, setDay] = useState<string | null>(targetDay || null)
  const [eventKind, setEventKind] = useState<'all' | 'drive' | 'charge' | 'parked'>('all')
  const [evidence, setEvidence] = useState<DriveEvidence | null>(null), [parked, setParked] = useState<ParkedDetail | null>(null), [standby, setStandby] = useState<ParkedDetail[]>([])
  const [chargeEvidence, setChargeEvidence] = useState<ChargeEvidence | null>(null)
  const [chargeDetail, setChargeDetail] = useState<Charge | null>(null), [detailLoading, setDetailLoading] = useState(false)
  const [sampleIndex, setSampleIndex] = useState(0), [recordCategory, setRecordCategory] = useState<'distance' | 'duration' | 'energy' | 'charge'>('distance')
  const evidenceEpoch = useRef<number | null>(null)
  const requestEpoch = useRef(0), detailEpoch = useRef(0), selectedStable = useRef<string | null>(null)
  const owner = useRef<{ user: string; origin: string; generation: number; epoch: number } | null>(null)
  const drivesRef = useRef<Drive[]>([]), chargesRef = useRef<Charge[]>([])
  const current = () => {
    try { const token = owner.current; return Boolean(token && token.epoch === requestEpoch.current && token.generation === getApiSessionGeneration() && token.origin === getApiOrigin() && token.user === readAppSession(Taro)?.userId) } catch { return false }
  }
  const invalidate = () => { requestEpoch.current++; detailEpoch.current++; owner.current = null; setLoading(false); setDetailLoading(false) }
  const clear = () => { setCars([]); setCar(null); setDrives([]); setCharges([]); drivesRef.current = []; chargesRef.current = []; setEvidence(null); setChargeEvidence(null); evidenceEpoch.current = null; setSampleIndex(0); setParked(null); setStandby([]); setChargeDetail(null); setStatus(initialStatus) }
  const updateQuery = (patch: Partial<HistoryQuery>) => setQuery(previous => normalizeHistoryQuery({ ...previous, ...patch }))
  const load = async () => {
    invalidate(); clear(); setError(null)
    const epoch = requestEpoch.current, session = readAppSession(Taro)
    if (!session?.userId) { setError('请先在“更多”中完成特斯拉账号登录'); return }
    let origin: string
    try { origin = getApiOrigin() } catch { setError('无法验证当前服务地址'); return }
    owner.current = { user: session.userId, origin, generation: getApiSessionGeneration(), epoch }
    const active = () => requestEpoch.current === epoch && current()
    setLoading(true)
    const restore = (vehicle: Car) => {
      const driveRows = readHistoryCache<Drive>(Taro, historyCacheKey('drives', session.userId!, vehicle.stableId, origin))
      const chargeRows = readHistoryCache<Charge>(Taro, historyCacheKey('charges', session.userId!, vehicle.stableId, origin))
      drivesRef.current = driveRows; chargesRef.current = chargeRows; setDrives(driveRows); setCharges(chargeRows)
      setStatus({ ...initialStatus, offline: true, cached: true, reason: 'cached' })
    }
    try {
      const vehicles = await matelinkApi.getCars()
      if (!active()) return
      setCars(vehicles)
      const stored = selectedStable.current ?? String(Taro.getStorageSync(carSelectionStorageKey(session.userId)) ?? '')
      const selected = vehicles.find(item => item.stableId === stored) ?? vehicles[0]
      if (!selected) { setError('当前账号尚未绑定车辆'); return }
      setCar(selected); selectedStable.current = selected.stableId
      try { Taro.setStorageSync(carSelectionStorageKey(session.userId), selected.stableId) } catch { /* online use remains available */ }
      const results = await Promise.allSettled([
        analysisApi.getHistory(selected, 'drives', { isCurrent: active }),
        analysisApi.getHistory(selected, 'charges', { isCurrent: active }),
      ])
      if (!active()) return
      const remoteDrives = results[0], remoteCharges = results[1]
      const dkey = historyCacheKey('drives', session.userId, selected.stableId, origin), ckey = historyCacheKey('charges', session.userId, selected.stableId, origin)
      const oldDrives = readHistoryCache<Drive>(Taro, dkey), oldCharges = readHistoryCache<Charge>(Taro, ckey)
      const dr = remoteDrives.status === 'fulfilled' ? remoteDrives.value.items.map(row => mergeHistoryByKey(oldDrives.filter(old => historyRowKey(old) === historyRowKey(row) || old.id === row.id), [row], historyRowKey).items[0]) : oldDrives
      const ch = remoteCharges.status === 'fulfilled' ? remoteCharges.value.items.map(row => mergeHistoryByKey(oldCharges.filter(old => historyRowKey(old) === historyRowKey(row) || old.id === row.id), [row], historyRowKey).items[0]) : oldCharges
      drivesRef.current = dr; chargesRef.current = ch; setDrives(dr); setCharges(ch)
      let saved = true
      if (remoteDrives.status === 'fulfilled') saved = writeHistoryCache(Taro, dkey, mergeHistoryByKey(oldDrives, dr, historyRowKey).items) && saved
      if (remoteCharges.status === 'fulfilled') saved = writeHistoryCache(Taro, ckey, mergeHistoryByKey(oldCharges, ch, historyRowKey).items) && saved
      setStatus({ drivesComplete: remoteDrives.status === 'fulfilled' && remoteDrives.value.complete,
        chargesComplete: remoteCharges.status === 'fulfilled' && remoteCharges.value.complete,
        offline: false, cached: remoteDrives.status === 'rejected' || remoteCharges.status === 'rejected',
        reason: remoteDrives.status === 'fulfilled' ? remoteDrives.value.reason ?? (remoteCharges.status === 'fulfilled' ? remoteCharges.value.reason : 'request_failed') : 'request_failed' })
      if (!saved) setError('在线数据可读，但本次结果未完整保存到本机。')
      if (kind === 'curves' && params.id) {
        setDetailLoading(true)
        try { const detail = await analysisApi.getDriveEvidence(selected, params.id, { isCurrent: active }); if (active()) {
          if (isHistoryQuarantined(detail.drive)) { setEvidence(null); setError('该行程当前被隔离，未展示采样或统计。') }
          else { evidenceEpoch.current = epoch; setEvidence(detail) }
        } }
        catch (reason) { if (active()) setError(reason instanceof ApiError ? reason.message : '无法读取当前行程曲线') }
        finally { if (active()) setDetailLoading(false) }
      }
      if (kind === 'timeline' && params.chargeId) {
        try {
          const detail = await matelinkApi.getChargeDetail(selected.id, params.chargeId, selected)
          if (active()) {
            if (detail && !isHistoryQuarantined(detail)) {
              setChargeDetail(detail)
              writeHistoryCache(Taro, ckey, mergeHistoryByKey(mergeHistoryByKey(oldCharges, ch, historyRowKey).items, [detail], historyRowKey).items)
            } else setError('当前充电详情尚无可验证记录。')
          }
        } catch (reason) { if (active()) setError(reason instanceof ApiError ? reason.message : '当前充电详情暂时不可用。') }
      }
      if (kind === 'chargeCurves' && params.chargeId) {
        setDetailLoading(true)
        try {
          const detail = await analysisApi.getChargeEvidence(selected, params.chargeId, { isCurrent: active })
          if (active()) {
            if (isHistoryQuarantined(detail.charge)) { setChargeEvidence(null); setError('该充电记录当前被隔离，未展示采样或统计。') }
            else { evidenceEpoch.current = epoch; setChargeEvidence(detail) }
          }
        } catch (reason) { if (active()) setError(reason instanceof ApiError ? reason.message : '当前充电采样暂时不可用。') }
        finally { if (active()) setDetailLoading(false) }
      }
      if (kind === 'parked' && params.olderId && params.newerId) {
        try { const result = await analysisApi.getParkedDetail(selected, params.olderId, params.newerId, { isCurrent: active }); if (active()) setParked(result.detail) }
        catch (reason) { if (active()) setError(reason instanceof ApiError ? reason.message : '驻车详情暂时不可用，可查看相邻行程窗口。') }
      }
      if (kind === 'vampire') {
        try { const result = await analysisApi.getStandby(selected, { isCurrent: active }); if (active()) setStandby(result.windows) }
        catch { if (active()) setError('服务端驻车采样暂时不可用；相邻行程窗口不代表合格损耗。') }
      }
    } catch (reason) {
      if (!active()) return
      const cachedCars = readCarsCache(Taro, session.userId, origin)
      setCars(cachedCars)
      const stored = selectedStable.current ?? String(Taro.getStorageSync(carSelectionStorageKey(session.userId)) ?? '')
      const selected = cachedCars.find(item => item.stableId === stored) ?? cachedCars[0] ?? null
      setCar(selected)
      if (selected) restore(selected)
      setError(reason instanceof ApiError ? reason.message : '在线车辆验证失败，仅可查看当前账号本机缓存。')
    } finally {
      if (active()) setLoading(false)
      else if (requestEpoch.current === epoch) { setLoading(false); clear(); setError('会话或服务地址已变化，请重新验证登录。') }
    }
  }
  useDidShow(() => { void load() }); useDidHide(invalidate); useUnload(invalidate)
  const navigateDrive = (row: Drive) => { void Taro.navigateTo({ url: `/pages/analysis/index?kind=curves&id=${encodeURIComponent(row.id)}` }) }
  const navigateParked = (olderId: string, newerId: string) => { void Taro.navigateTo({ url: `/pages/analysis/index?kind=parked&olderId=${encodeURIComponent(olderId)}&newerId=${encodeURIComponent(newerId)}` }) }
  const openCharge = async (row: Charge) => {
    const canonical = chargesRef.current.find(item => item.id === row.id && item.source === row.source && historyRowKey(item) === historyRowKey(row))
    if (!canonical) return
    setChargeDetail(canonical)
    if (status.offline || !car || car.remoteVerified === false || !current()) return
    const sequence = ++detailEpoch.current
    try {
      const result = await matelinkApi.getChargeDetail(car.id, row.id, car)
      if (!current() || sequence !== detailEpoch.current || !result) return
      const state = acceptHistoryDetail({ pages: [], items: chargesRef.current, page: 0 }, canonical, result)
      if (state.items === chargesRef.current) return
      chargesRef.current = state.items; setCharges(state.items); setChargeDetail(state.items.find(item => item.id === result.id) ?? null)
      const token = owner.current
      if (token) writeHistoryCache(Taro, historyCacheKey('charges', token.user, car.stableId, token.origin), state.items)
    } catch (reason) { if (current() && sequence === detailEpoch.current) setError(reason instanceof ApiError ? reason.message : '充电详情暂时不可用') }
    finally { if (sequence === detailEpoch.current && !current()) { invalidate(); clear(); setError('会话或服务地址已变化，请重新验证登录。') } }
  }
  const costScope = owner.current && car ? { userId: owner.current.user, origin: owner.current.origin, stableId: car.stableId } : null
  const displayCharges = costScope ? overlayChargeCosts(Taro, costScope, charges) : []
  const displayedChargeDetail = chargeDetail && costScope ? overlayChargeCosts(Taro, costScope, [chargeDetail])[0] : null
  const displayedCurveCharge = chargeEvidence?.charge && costScope ? overlayChargeCosts(Taro, costScope, [chargeEvidence.charge])[0] : null
  const visibleDrives = filterHistory(drives, query), visibleCharges = filterHistory(displayCharges, query)
  // The App's efficiency consumer uses qualified history; its mileage and timeline consumers use their own projections.
  const efficiencyDrives = visibleDrives.filter(isAppDriveAnalysisEligible)
  const driveCount = status.drivesComplete || drives.length ? visibleDrives.length : null
  const chargeCount = status.chargesComplete || charges.length ? visibleCharges.length : null
  const totals = aggregateDriveHistory(visibleDrives), efficiencyTotals = aggregateDriveHistory(efficiencyDrives)
  const distribution = efficiencyDistribution(efficiencyDrives)
  const yearly = analysisGroups(visibleDrives, 'year'), monthly = analysisGroups(visibleDrives, 'month')
  const efficiencyMonthly = analysisGroups(efficiencyDrives, 'month')
  const windows = buildParkedWindows(drives, charges, status.chargesComplete).filter(window => filterHistory([{ startDate: window.startDate }], { ...query, minDistanceKm: 0 }).length > 0)
  const eligibleWindows = windows.filter(window => !window.chargeOverlap && window.chargeCoverageComplete)
  const selectedWindow = windows.find(window => window.olderId === params.olderId && window.newerId === params.newerId)
  const events = [...visibleDrives.map(row => ({ kind: 'drive' as const, date: row.startDate, row })), ...visibleCharges.map(row => ({ kind: 'charge' as const, date: row.startDate, row })),
    ...windows.map(row => ({ kind: 'parked' as const, date: row.startDate, row }))].sort((a, b) => Date.parse(b.date ?? '') - Date.parse(a.date ?? ''))
    .filter(event => eventKind === 'all' || event.kind === eventKind)
  const moneyCurrencies = [...new Set(visibleCharges.filter(row => known(row.cost) != null && row.currency).map(row => row.currency!))]
  const sources = [...new Set([...visibleDrives, ...visibleCharges].map(row => sourceLabel(row.source)))]
  const chargeEnergy = coveredTotal(visibleCharges, row => row.energyAddedKwh != null && row.energyAddedKwh >= 0 ? row.energyAddedKwh : null)
  const standbyEligible = standby.filter(row => row.linkedChargeId == null && row.startDate && row.endDate && filterHistory([{ startDate: row.startDate }], { ...query, minDistanceKm: 0 }).length)
  const standbyEnergy = coveredTotal(standbyEligible, row => row.energyKwh)
  const standbySoc = coveredTotal(standbyEligible, row => row.batteryDelta)
  const qualifiedHours = standbyEligible.filter(row => row.energyKwh != null && row.startDate && row.endDate && Date.parse(row.endDate) > Date.parse(row.startDate))
    .reduce((sum, row) => sum + (Date.parse(row.endDate!) - Date.parse(row.startDate!)) / 3600000, 0)
  const estimatedDistances = distanceCurveEstimates(evidence?.points ?? [], evidence?.drive?.distanceKm)
  const distanceByPoint = new Map((evidence?.points ?? []).map((point, index) => [point, estimatedDistances[index]]))
  const curveProps = { selected: sampleIndex, onSelect: setSampleIndex, isCurrent: () => evidenceEpoch.current === requestEpoch.current && current() }
  const recordValue = (row: Drive) => recordCategory === 'duration' ? known(row.durationMinutes) : recordCategory === 'energy' ? row.energy?.quality !== 'unknown' ? known(row.energy?.energyKwh) : null : known(row.distanceKm)
  const recordDrive = [...visibleDrives].filter(row => recordValue(row) != null).sort((a, b) => recordValue(b)! - recordValue(a)!)[0]
  const recordCharge = [...visibleCharges].filter(row => known(row.energyAddedKwh) != null).sort((a, b) => b.energyAddedKwh! - a.energyAddedKwh!)[0]
  const temperatureGroups = [{ label: '< 10 °C', min: -Infinity, max: 10 }, { label: '10–25 °C', min: 10, max: 25 }, { label: '≥ 25 °C', min: 25, max: Infinity }].map(group => {
    const rows = efficiencyDrives.filter(row => known(row.outsideTempC) != null && row.outsideTempC! >= group.min && row.outsideTempC! < group.max)
    return { ...group, ...aggregateDriveHistory(rows).efficiency }
  })
  const driveRows = (rows: readonly Drive[]) => rows.map(row => <View className="analysis-record" key={historyRowKey(row)}>
    <Text>{historyDateTime(row.startDate)}</Text><Text className="muted">{toChineseDisplayAddress(row.startAddress) || '起点未知'} → {toChineseDisplayAddress(row.endAddress) || '终点未知'}</Text>
    <Text className="muted">{shown(row.distanceKm, 'km')} · {shown(row.energy?.energyKwh, 'kWh')} · {sourceLabel(row.source)}{row.energy?.quality === 'estimated' ? ' · 估算' : ''}</Text>
    <Button className="button-secondary" onClick={() => navigateDrive(row)}>查看行程 {row.id}</Button>
  </View>)
  return <View className="page analysis-page">
    <View className="page-header"><Text className="title">{analysisTitles[kind]}</Text></View>
    {cars.length > 1 ? <Picker mode="selector" range={cars.map(row => row.displayName || row.name || '车辆')} onChange={event => { selectedStable.current = cars[Number(event.detail.value)]?.stableId ?? null; void load() }}><View className="analysis-car">{car?.displayName || car?.name || '选择车辆'}</View></Picker> : null}
    {car ? <View className="analysis-controls"><HistoryDateChips kind="drives" query={query} onChange={updateQuery} /><View className="analysis-date-range">
      <Picker mode="date" value={query.startDate} onChange={event => updateQuery({ startDate: event.detail.value })}><View>开始：{query.startDate || '不限'}</View></Picker>
      <Picker mode="date" value={query.endDate} onChange={event => updateQuery({ endDate: event.detail.value })}><View>结束：{query.endDate || '不限'}</View></Picker>
      <Button className="button-secondary" loading={loading} onClick={() => { void load() }}>刷新</Button>
    </View></View> : null}
    {loading ? <LoadingIndicator label="正在读取当前车辆分析…" compact /> : null}
    {car ? <View className="analysis-coverage"><Text>{status.offline ? '本机缓存' : status.cached ? '在线验证 · 缓存部分覆盖' : status.drivesComplete && status.chargesComplete ? '数据读取完成' : status.reason === 'request_failed' ? '部分数据读取失败' : '数据正在补全'} · 行程 {drives.length} 条 · 充电 {charges.length} 条</Text></View> : null}
    {error ? <Text className="error">{error}</Text> : null}
    {car && kind === 'stats' ? <>
      <View className="card"><Text className="section-title">综合分析</Text><View className="metric-grid">
        <MetricValue label="总距离" value={totals.distance.value} unit="km" icon="distance" /><MetricValue label="平均能耗" value={totals.efficiency.value} unit="kWh/100km" icon="energy" />
        <MetricValue label="合格净能量" value={totals.energy.value} unit="kWh" icon="battery" /><MetricValue label="电池充入" value={chargeEnergy.value} unit="kWh" icon="battery" />
      </View><Text className="muted">配对平均 {totals.efficiency.known}/{totals.efficiency.total} 条，含估算 {totals.efficiency.estimated} 条；距离 {totals.distance.known}/{totals.distance.total}，净能量 {totals.energy.known}/{totals.energy.total}。</Text></View>
      <View className="card"><Text className="section-title">记录</Text><Text className="muted">当前范围的真实记录，可进入对应行程。</Text>
        <View className="history-date-chips">{[{ key: 'distance', label: '最长距离' }, { key: 'duration', label: '最长时间' }, { key: 'energy', label: '最多净能量' }, { key: 'charge', label: '最多充电量' }].map(choice => <Button className="history-chip" key={choice.key} onClick={() => setRecordCategory(choice.key as typeof recordCategory)}>{choice.label}</Button>)}</View>
        {recordCategory === 'charge' ? recordCharge ? <View className="analysis-record"><Text>{historyDateTime(recordCharge.startDate)} · {shown(recordCharge.energyAddedKwh, 'kWh')}</Text><Button className="button-secondary" onClick={() => { void openCharge(recordCharge) }}>查看充电 {recordCharge.id}</Button></View> : <Text className="muted">暂无合格充电量记录。</Text>
          : recordDrive ? <><Text>{shown(recordValue(recordDrive), recordCategory === 'duration' ? '分钟' : recordCategory === 'energy' ? 'kWh' : 'km')}</Text>{driveRows([recordDrive])}</> : <Text className="muted">暂无该指标的合格记录。</Text>}
      </View>
      <View className="card"><Text className="section-title">行程概览</Text><View className="metric-grid"><MetricValue label="行程次数" value={driveCount} unit="次" icon="route" /><MetricValue label="总时间" value={totals.duration.value} unit="分钟" icon="clock" /><MetricValue label="平均行程距离" value={totals.distance.value != null && totals.distance.known ? totals.distance.value / totals.distance.known : null} unit="km" icon="distance" /></View></View>
      <View className="card"><Text className="section-title">充电概览</Text><View className="metric-grid"><MetricValue label="充电次数" value={chargeCount} unit="次" icon="energy" /><MetricValue label="电池充入" value={chargeEnergy.value} unit="kWh" icon="battery" /></View>
        {moneyCurrencies.map(currency => { const total = coveredTotal(visibleCharges, row => row.currency === currency ? row.cost : null); return <MetricValue key={currency} label={visibleCharges.length === 0 ? `已知费用 · ${currency}` : `已知费用 ${total.known}/${visibleCharges.length} 条`} value={total.value} unit={currency} icon="account" /> })}
        <Text className="muted">未知币种不折合人民币；多币种分别显示，不自动换汇。</Text>
        <Text className="muted">费用来源：本机手动 {visibleCharges.filter(row => row.costProvenance === 'manual').length} 条 · 上游实际 {visibleCharges.filter(row => row.costProvenance === 'actual').length} 条 · 未知 {visibleCharges.filter(row => row.costProvenance === 'unknown').length} 条。</Text>
      </View>
      <View className="card"><Text className="section-title">AC / DC 充电</Text><View className="metric-grid">{(['ac', 'dc', 'unknown'] as const).map(mode => {
        const rows = visibleCharges.filter(row => (row.energy?.chargeMode ?? row.chargeMode ?? 'unknown') === mode)
        const energy = coveredTotal(rows, row => row.energyAddedKwh)
        return <View key={mode}><MetricValue label={mode === 'unknown' ? '模式未知' : mode.toUpperCase()} value={chargeCount == null ? null : rows.length} unit="次" icon="energy" /><Text className="muted">{shown(energy.value, 'kWh')} · {rows.length === 0 ? '暂无可计算样本' : `电量覆盖 ${energy.known}/${rows.length} 条`}</Text></View>
      })}</View><Text className="muted">仅已确认边界模式计入 AC/DC；其他记录单列未知。</Text></View>
      <View className="card"><Text className="section-title">温度与能耗</Text><View className="metric-grid">{temperatureGroups.map(group => <MetricValue key={group.label} label={group.total === 0 ? group.label : `${group.label} · 配对${group.known}/${group.total}`} value={group.value} unit="kWh/100km" />)}</View><Text className="muted">按实际行程外温字段分组，使用同条合格能量与里程；未知外温不分配到温度组。</Text></View>
      <GroupBars title="里程趋势" groups={monthly} />
    </> : null}
    {car && kind === 'mileage' ? <>
      <View className="card"><Text className="section-title">年度里程</Text><View className="metric-grid"><MetricValue label="已知总里程" value={totals.distance.value} unit="km" icon="distance" /><MetricValue label="年均里程" value={totals.distance.value != null && yearly.some(group => group.distance.known) ? totals.distance.value / yearly.filter(group => group.distance.known).length : null} unit="km" icon="distance" /><MetricValue label="行程次数" value={driveCount} unit="次" icon="route" /><MetricValue label="合格净能量" value={totals.energy.value} unit="kWh" icon="battery" /></View><Text className="muted">{totals.distance.total === 0 ? '暂无记录' : `覆盖 ${totals.distance.known}/${totals.distance.total} 条`}；不等于车辆终身里程表。费用需实际关联证据，未按默认电价推算。</Text></View>
      {!year ? <><GroupBars title="年度对比" groups={yearly} />{[...yearly].reverse().map(group => <View className="card" key={group.key}><Button className="button-secondary" onClick={() => setYear(group.key)}>{group.key} 年</Button><Text>{shown(group.distance.value, 'km')} · {group.count} 次</Text></View>)}</> : null}
      {year && !month ? <View className="card"><Button className="button-secondary" onClick={() => setYear(null)}>返回年度</Button><Text className="section-title">{year} 月度里程</Text>{monthly.filter(group => group.key.startsWith(year)).map(group => <View key={group.key} className="analysis-record"><Button className="button-secondary" onClick={() => setMonth(group.key)}>{group.key}</Button><Text>{shown(group.distance.value, 'km')} · {group.count} 次</Text></View>)}</View> : null}
      {month && !day ? <View className="card"><View className="analysis-date-range"><Button className="button-secondary" onClick={() => setMonth(null)}>返回月份</Button><Picker mode="date" fields="month" value={month} onChange={event => setMonth(event.detail.value.slice(0, 7))}><View>{month} ▾</View></Picker></View>
        <View className="calendar-week">{['日', '一', '二', '三', '四', '五', '六'].map(label => <Text key={label}>{label}</Text>)}</View>
        <View className="analysis-calendar">{calendarCells(month, visibleDrives).map((cell, index) => cell.day ? <Button key={cell.day} className="calendar-day" ariaLabel={`${cell.day} ${cell.count}条 ${shown(cell.distance.value, 'km')}`} onClick={() => setDay(cell.day)}><Text>{cell.day.slice(-2)}</Text><Text className="calendar-distance">{cell.count ? shown(cell.distance.value, 'km') : '未记录'}</Text></Button> : <View key={`empty-${index}`} />)}</View>
      </View> : null}
      {day ? <View className="card"><Button className="button-secondary" onClick={() => setDay(null)}>返回日历</Button><Text className="section-title">{day} 行程</Text>{driveRows(visibleDrives.filter(row => analysisDay(row.startDate) === day))}{visibleDrives.every(row => analysisDay(row.startDate) !== day) ? <Text className="muted">当日暂无已加载行程，不代表车辆未行驶。</Text> : null}</View> : null}
    </> : null}
    {car && kind === 'efficiency' ? <>
      <View className="card analysis-headline"><Text className="section-title">平均能耗</Text><MetricValue label="平均能耗" value={efficiencyTotals.efficiency.value} unit="kWh/100km" icon="energy" negative={(efficiencyTotals.efficiency.value ?? 0) < 0} /><Text className="muted">配对覆盖 {efficiencyTotals.efficiency.known}/{efficiencyTotals.efficiency.total} 条，含估算 {efficiencyTotals.efficiency.estimated} 条；按合格净能量/同条正里程加权。</Text></View>
      <View className="card metric-grid"><MetricValue label="合格行程" value={driveCount == null ? null : efficiencyTotals.efficiency.known} unit="次" icon="route" /><MetricValue label="已知距离" value={efficiencyTotals.distance.value} unit="km" icon="distance" /></View>
      <GroupBars title="能耗趋势" groups={efficiencyMonthly} metric="efficiency" />
      <View className="card"><Text className="section-title">时间窗口</Text><View className="metric-grid">{[{ label: '近90天', rows: efficiencyDrives.filter(row => Date.parse(row.startDate ?? '') >= Date.now() - 90 * 86400000) },
        { label: '夏季（6–8月）', rows: efficiencyDrives.filter(row => [6, 7, 8].includes(new Date(row.startDate ?? '').getMonth() + 1)) }, { label: '冬季（12–2月）', rows: efficiencyDrives.filter(row => [12, 1, 2].includes(new Date(row.startDate ?? '').getMonth() + 1)) }].map(window => <MetricValue key={window.label} label={window.label} value={aggregateDriveHistory(window.rows).efficiency.value} unit="kWh/100km" icon="energy" />)}</View></View>
      <View className="card"><Text className="section-title">个人历史位置</Text><View className="metric-grid"><MetricValue label="最低能耗" value={distribution.min} unit="kWh/100km" /><MetricValue label="中位数" value={distribution.median} unit="kWh/100km" /><MetricValue label="最高能耗" value={distribution.max} unit="kWh/100km" /></View><Text className="muted">合格样本 {distribution.count} 段；仅比较本车当前范围，不代表公开车型排名。</Text></View>
      <View className="card"><Text className="section-title">按行程平均速度</Text>{efficiencySpeedGroups(efficiencyDrives).map(group => <MetricValue key={group.label} label={`${group.label} · ${group.known}条`} value={group.value} unit="kWh/100km" />)}<Text className="muted">平均速度仅由该条合格距离/有效时长计算，不把峰值当平均速度。</Text></View>
      <View className="card"><Text className="section-title">个人行程记录</Text>{distribution.trips.slice(0, 20).map(trip => <View key={historyRowKey(trip.row)}><Text className="muted">P{trip.percentile} · {shown(trip.value, 'kWh/100km')}</Text><View className="analysis-percentile-track"><View style={{ width: `${trip.percentile}%` }} /></View>{driveRows([trip.row])}</View>)}<Text className="muted">百分位来自当前合格行程能耗分布；数值越高表示能耗越高，不能作为车型公开排名。</Text></View>
    </> : null}
    {car && kind === 'timeline' ? <View className="analysis-timeline"><Text className="section-title">行程与充电</Text><View className="history-date-chips">{[{ key: 'all', label: '全部' }, { key: 'drive', label: '行程' }, { key: 'charge', label: '充电' }, { key: 'parked', label: '驻车窗口' }].map(choice => <Button key={choice.key} className="history-chip" onClick={() => setEventKind(choice.key as typeof eventKind)}>{choice.label}</Button>)}</View>
      {events.map((event, index) => <View key={`${event.kind}:${event.kind === 'parked' ? `${event.row.olderId}:${event.row.newerId}` : event.row.id}`} className="timeline-group">
        {index === 0 || analysisDay(event.date) !== analysisDay(events[index - 1].date) ? <View className="timeline-day"><Text>{analysisDay(event.date) ?? '日期未知'}</Text>{analysisDay(event.date) ? <Button className="button-secondary" onClick={() => { void Taro.navigateTo({ url: `/pages/analysis/index?kind=mileage&day=${analysisDay(event.date)}` }) }}>查看当日里程</Button> : null}</View> : null}
        <View className="card timeline-event"><Text className="timeline-date">{historyDateTime(event.date)}</Text>
        {event.kind === 'drive' ? <><Text className="section-title">行程</Text><Text>{event.row.startAddress || '起点未知'} → {event.row.endAddress || '终点未知'}</Text><Text>{shown(event.row.distanceKm, 'km')} · {sourceLabel(event.row.source)}</Text><Button className="button-secondary" onClick={() => navigateDrive(event.row)}>查看行程 {event.row.id}</Button></>
          : event.kind === 'charge' ? <><Text className="section-title">充电</Text><Text>{event.row.address || '地点未知'}</Text><Text>{shown(event.row.energyAddedKwh, 'kWh')} · {shown(event.row.cost, event.row.currency || '币种未知')}</Text><Text className="muted">{costSource(event.row)}</Text><Button className="button-secondary" onClick={() => { void openCharge(event.row) }}>查看充电 {event.row.id}</Button></>
            : <><Text className="section-title">{event.row.chargeOverlap ? '含充电的驻车窗口' : '驻车窗口'}</Text><Text>{shown(event.row.minutes, '分钟')} · SOC变化 {shown(event.row.socDelta, '%')}</Text><Button className="button-secondary" onClick={() => navigateParked(event.row.olderId, event.row.newerId)}>查看驻车详情</Button></>}
      </View></View>)}{events.length === 0 ? <View className="card"><Text className="muted">当前读取范围没有对应事件；未生成示例记录。</Text></View> : null}
    </View> : null}
    {car && kind === 'vampire' ? <>
      <View className="card"><Text className="section-title">观测证据</Text><Text>服务端 {standby.length} 个采样窗口 · 相邻行程 {windows.length} 个窗口</Text><Text className="muted">合格能量 {standbyEnergy.known}/{standbyEnergy.total} 个，{shown(qualifiedHours, '小时')}。{standbyEnergy.known < 3 ? '样本尚不足以形成稳定结论。' : '结论仅限当前合格观测范围。'}</Text></View>
      <View className="card"><Text className="section-title">累计驻车电量变化</Text><MetricValue label="已知SOC变化" value={standbySoc.value} unit="%" icon="battery" /><Text className="muted">SOC变化不等于能量；缺少合格储能证据时不猜电池容量。</Text></View>
      <View className="card metric-grid"><MetricValue label="平均功率" value={standbyEnergy.value != null && qualifiedHours > 0 ? standbyEnergy.value * 1000 / qualifiedHours : null} unit="W" icon="energy" /><MetricValue label="合格窗口" value={standbyEnergy.known} unit="个" /><MetricValue label="合格能量变化" value={standbyEnergy.value} unit="kWh" icon="battery" /></View>
      <View className="card"><Text className="section-title">最近驻车窗口</Text>{standbyEligible.map((row, index) => <View className="analysis-record" key={`${row.startDate}:${index}`}><Text>{historyDateTime(row.startDate)} → {historyDateTime(row.endDate)}</Text><Text>{shown(row.batteryDelta, '%')} · {shown(row.energyKwh, 'kWh')} · {sourceLabel(row.source)}</Text>{row.olderDriveId && row.newerDriveId ? <Button className="button-secondary" onClick={() => navigateParked(row.olderDriveId!, row.newerDriveId!)}>查看驻车详情</Button> : null}</View>)}
        {eligibleWindows.map(window => <View key={`${window.olderId}:${window.newerId}`} className="analysis-record"><Text>{historyDateTime(window.startDate)} → {historyDateTime(window.endDate)}</Text><Text>SOC变化 {shown(window.socDelta, '%')} · 净能量暂无数据</Text><Text className="muted">相邻行程观测窗口；未证明持续休眠，不纳入合格损耗合计。</Text><Button className="button-secondary" onClick={() => navigateParked(window.olderId, window.newerId)}>查看驻车详情</Button></View>)}
        {windows.some(window => window.chargeOverlap) ? <Text className="muted">含充电重叠的窗口已排除损耗计算。</Text> : null}{!status.chargesComplete ? <Text className="muted">充电历史覆盖未完整，不能确认窗口没有充电；未纳入损耗计算。</Text> : null}
        {!standbyEligible.length && !eligibleWindows.length ? <Text className="muted">尚无可确认的驻车损耗观测，暂无数据。</Text> : null}
      </View>
      <View className="card"><Text className="section-title">归因与计算依据</Text><Text className="muted">当前响应未提供完整空调、哨兵和持续休眠证据；不归因为具体耗电功能。计算只采用合格储能变化与同窗口时长。</Text></View>
    </> : null}
    {car && kind === 'curves' ? <>
      {!params.id ? <View className="card"><Text className="section-title">选择行程</Text>{driveRows(visibleDrives)}</View> : null}
      {detailLoading ? <LoadingIndicator label="正在读取行程采样…" compact /> : null}
      {evidence?.drive ? <View className="card"><Text className="section-title">行程概览</Text><Text>开始 {historyDetailDateTime(evidence.drive.startDate)} · 结束 {historyDetailDateTime(evidence.drive.endDate)}</Text><View className="metric-grid"><MetricValue label="距离" value={evidence.drive.distanceKm} unit="km" /><MetricValue label="时长" value={evidence.drive.durationMinutes} unit="分钟" /><MetricValue label="最高速度" value={peakSpeedKmh(evidence.drive)} unit="km/h" /><MetricValue label="合格净能量" value={evidence.drive.energy?.energyKwh} unit="kWh" /></View><Text className="muted">{sourceLabel(evidence.source)} · {evidence.points.length} 个实际采样 · {evidence.pointsComplete ? '本响应采样完整' : '采样部分覆盖'}</Text></View> : null}
      {evidence ? <><EvidenceCurve {...curveProps} id="analysis-speed" title="速度变化" unit="km/h" points={evidence.points} valueOf={point => point.speedKmh} /><EvidenceCurve {...curveProps} id="analysis-distance" title="行程距离变化 · 估算" unit="km" points={evidence.points} valueOf={point => distanceByPoint.get(point) ?? null} /><View className="analysis-distance-basis"><Text className="muted">距离分布按实际时间间隔积分速度，并以合格总距离归一；属于估算，缺失速度不作均匀分配。</Text></View><EvidenceCurve {...curveProps} id="analysis-power" title="功率输出与回收" unit="kW" points={evidence.points} valueOf={point => point.powerKw} /><EvidenceCurve {...curveProps} id="analysis-soc" title="电量变化" unit="%" points={evidence.points} valueOf={point => point.batteryLevel} /><EvidenceCurve {...curveProps} id="analysis-elevation" title="海拔高度" unit="m" points={evidence.points} valueOf={point => point.elevationM} /></> : params.id && !detailLoading ? <View className="card"><Text className="muted">暂无可验证的行程采样；离线缓存不保存精确位置或轨迹。</Text></View> : null}
    </> : null}
    {car && kind === 'parked' ? <>
      {!params.olderId || !params.newerId ? <View className="card"><Text className="section-title">选择驻车窗口</Text>{windows.map(window => <View key={`${window.olderId}:${window.newerId}`} className="analysis-record"><Text>{historyDateTime(window.startDate)} → {historyDateTime(window.endDate)}</Text><Button className="button-secondary" onClick={() => navigateParked(window.olderId, window.newerId)}>查看驻车详情</Button></View>)}</View> : null}
      <View className="card"><Text className="section-title">{parked?.address || selectedWindow?.address || '地点未知'}</Text><Text>{historyDateTime(parked?.startDate ?? selectedWindow?.startDate)} → {historyDateTime(parked?.endDate ?? selectedWindow?.endDate)}</Text>
        {parked?.linkedChargeId || selectedWindow?.chargeOverlap ? <Text className="muted">该驻车窗口与充电记录重叠，不作为纯驻车损耗。</Text> : null}
        {parked?.linkedChargeId ? <Button className="button-secondary" onClick={() => { void Taro.navigateTo({ url: `/pages/analysis/index?kind=timeline&chargeId=${encodeURIComponent(parked.linkedChargeId!)}` }) }}>查看关联充电</Button> : null}
      </View>
      <View className="card"><Text className="section-title">驻车时间与电量</Text><View className="metric-grid"><MetricValue label="开始电量" value={parked ? parked.startBatteryLevel : selectedWindow?.startSoc} unit="%" /><MetricValue label="结束电量" value={parked ? parked.endBatteryLevel : selectedWindow?.endSoc} unit="%" /><MetricValue label="电量变化" value={parked ? parked.batteryDelta : selectedWindow?.socDelta} unit="%" /><MetricValue label="合格储能变化" value={parked?.energyKwh} unit="kWh" /><MetricValue label="平均功率" value={parked?.averagePowerW} unit="W" /><MetricValue label="峰值功率" value={parked?.peakPowerW != null ? parked.peakPowerW / 1000 : null} unit="kW" /></View></View>
      <View className="card"><Text className="section-title">环境与数据</Text><View className="metric-grid"><MetricValue label="车内温度" value={parked?.insideTempC} unit="°C" /><MetricValue label="车外温度" value={parked?.outsideTempC} unit="°C" /><MetricValue label="采样数" value={parked?.sampleCount} unit="个" /><MetricValue label="覆盖率" value={parked?.coverageRatio != null ? parked.coverageRatio * 100 : null} unit="%" /></View><Text className="muted">数据来源：{sourceLabel(parked?.source ?? selectedWindow?.source)}。{parked?.energyKwh == null ? '缺少合格储能证据；不由SOC变化猜容量或能量。' : '储能变化只适用于已验证采样窗口。'}</Text></View>
    </> : null}
    {car && kind === 'chargeCurves' ? <>
      {!params.chargeId ? <View className="card"><Text className="section-title">选择充电记录</Text>{visibleCharges.map(row => <View className="analysis-record" key={historyRowKey(row)}><Text>{historyDateTime(row.startDate)} · {row.address || '地点未知'}</Text><Button className="button-secondary" onClick={() => { void Taro.navigateTo({ url: `/pages/analysis/index?kind=chargeCurves&chargeId=${encodeURIComponent(row.id)}` }) }}>查看充电曲线 {row.id}</Button></View>)}</View> : null}
      {detailLoading ? <LoadingIndicator label="正在读取充电采样…" compact /> : null}
      {chargeEvidence?.charge && displayedCurveCharge ? <View className="card"><Text className="section-title">充电概览</Text><Text>开始 {historyDetailDateTime(chargeEvidence.charge.startDate)} · 结束 {historyDetailDateTime(chargeEvidence.charge.endDate)}</Text><Text>{chargeEvidence.charge.address || '地点未知'}</Text><View className="metric-grid"><MetricValue label="电池充入" value={chargeEvidence.charge.energyAddedKwh} unit="kWh" /><MetricValue label="时长" value={chargeEvidence.charge.durationMinutes} unit="分钟" /><MetricValue label="费用" value={displayedCurveCharge.cost} unit={displayedCurveCharge.currency || '币种未知'} /><MetricValue label="采样数" value={chargeEvidence.points.length} unit="个" /></View><Text className="muted">{sourceLabel(chargeEvidence.source)} · {costSource(displayedCurveCharge)} · 响应采样部分覆盖；不把功率、电压或电流冒充充入电量。</Text></View> : null}
      {chargeEvidence ? <>
        <EvidenceCurve {...curveProps} id="charge-soc" title="充电电量变化" unit="%" points={chargeEvidence.points} valueOf={point => point.batteryLevel} />
        <EvidenceCurve {...curveProps} id="charge-power" title="充电功率" unit="kW" points={chargeEvidence.points} valueOf={point => point.powerKw} />
        <EvidenceCurve {...curveProps} id="charge-voltage" title="充电电压" unit="V" points={chargeEvidence.points} valueOf={point => point.voltageV} />
        <EvidenceCurve {...curveProps} id="charge-current" title="充电电流" unit="A" points={chargeEvidence.points} valueOf={point => point.currentA} />
        <EvidenceCurve {...curveProps} id="charge-temperature" title="充电温度" unit="°C" points={chargeEvidence.points} valueOf={point => point.temperatureC} />
      </> : params.chargeId && !detailLoading ? <View className="card"><Text className="muted">暂无可验证的充电采样；离线缓存不保存充电原始曲线。</Text></View> : null}
    </> : null}
    {displayedChargeDetail ? <View className="card analysis-charge-detail"><Text className="section-title">充电详情</Text><Button className="button-secondary" onClick={() => { detailEpoch.current++; setChargeDetail(null) }}>关闭充电详情</Button><Text>{displayedChargeDetail.address || '地点未知'} · {historyDateTime(displayedChargeDetail.startDate)}</Text><View className="metric-grid"><MetricValue label="电池充入" value={displayedChargeDetail.energyAddedKwh} unit="kWh" /><MetricValue label="输入电量" value={displayedChargeDetail.energyUsedKwh} unit="kWh" /><MetricValue label="费用" value={displayedChargeDetail.cost} unit={displayedChargeDetail.currency || '币种未知'} /><MetricValue label="功率" value={displayedChargeDetail.chargerPowerKw} unit="kW" /></View><Text className="muted">{costSource(displayedChargeDetail)} · 原始上游账单 {shown(displayedChargeDetail.reportedCost, displayedChargeDetail.reportedCurrency || '币种未知')}</Text></View> : null}
  </View>
}
