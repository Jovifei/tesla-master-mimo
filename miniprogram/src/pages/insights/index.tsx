import { Button, Input, Picker, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow, useRouter, useUnload } from '@tarojs/taro'
import { useRef, useState } from 'react'
import { ApiError, ensureWechatSession, getApiOrigin, getApiSessionGeneration, matelinkApi } from '../../services/api'
import { analysisApi } from '../../services/analysis_api'
import { readAppSession } from '../../services/session'
import { useSessionRecoveryReload } from '../../services/use_session_recovery'
import { readCarsCache } from '../../services/cars_cache'
import { historyCacheKey, readHistoryCache, writeHistoryCache } from '../../services/history_cache'
import { carSelectionStorageKey, type Car, type CarStatus, type Charge, type Drive } from '../../services/types'
import { aggregateDriveHistory, historyRowKey, isHistoryIdentityAmbiguous, isHistoryQuarantined } from '../../domain/history'
import { isAppChargeAnalysisEligible, isAppDriveAnalysisEligible } from '../../domain/app_history_projection'
import { emptyHistoryQuery, filterHistory, historyDateTime, selectHistoryDate, type HistoryQuery } from '../../domain/history_query'
import { chargeCosts, insightDrive, makeManualTrip, manualTripSummary, personalizedRange, ratedRangeComparisons, rangeInfluences, readManualTrips,
  removeManualTrip, tripLegKey, writeManualTrip, type CapacityEvidence, type ManualTrip, type TripLeg, type TripScope } from '../../domain/insights'
import HistoryDateChips from '../../components/HistoryDateChips'
import { estimateTariffCost, readTariff } from '../../domain/tariff'
import { chargeEstimatePrice, overlayChargeCosts, type CostedCharge } from '../../services/charge_cost'
import MetricValue, { displayNumber } from '../../components/MetricValue'
import LoadingIndicator from '../../components/LoadingIndicator'
import SectionHeading from '../../components/SectionHeading'
import SubpageHeader from '../../components/SubpageHeader'
import { sourceLabel } from '../../domain/data_labels'
import './index.scss'

type Kind = 'battery' | 'range' | 'cost' | 'longTrips' | 'currentCharge'
const titles: Record<Kind, string> = { battery: '电池健康', range: '续航分析', cost: '费用分析', longTrips: '长途旅程', currentCharge: '正在充电' }
type Health = { maxRangeKm: number | null; currentRangeKm: number | null; maxCapacityKwh: number | null; currentCapacityKwh: number | null; healthPercentage: number | null; ratedEfficiencyWhKm: number | null }
type Evidence = { availability?: string | null; source?: string | null; qualityState?: string | null; qualityReason?: string | null; observedAt?: string | null }
type Coverage = { complete: boolean; pages: number; reason: string | null }
const emptyCoverage: Coverage = { complete: false, pages: 0, reason: 'not_loaded' }
const message = (reason: unknown) => reason instanceof ApiError ? reason.message : '数据暂时无法读取，请重试'
const carName = (car: Car | null) => car?.displayName || car?.name || '所选车辆'
const dataLabel = (leg: TripLeg) => leg.kind === 'drive' ? '行驶' : '充电'
const legRow = (leg: TripLeg) => leg.kind === 'drive' ? leg.drive : leg.charge

export default function InsightsPage() {
  const params = useRouter().params
  const kind: Kind = params.kind && Object.prototype.hasOwnProperty.call(titles, params.kind) ? params.kind as Kind : 'battery'
  const [cars, setCars] = useState<Car[]>([]), [car, setCar] = useState<Car | null>(null)
  const [drives, setDrives] = useState<Drive[]>([]), [charges, setCharges] = useState<Charge[]>([])
  const [health, setHealth] = useState<Health | null>(null), [healthEvidence, setHealthEvidence] = useState<Evidence | null>(null)
  const [status, setStatus] = useState<CarStatus | null>(null), [currentChargeRaw, setCurrentCharge] = useState<Charge | null>(null)
  const [driveCoverage, setDriveCoverage] = useState<Coverage>(emptyCoverage), [chargeCoverage, setChargeCoverage] = useState<Coverage>(emptyCoverage)
  const [query, setQuery] = useState<HistoryQuery>(emptyHistoryQuery()), [offline, setOffline] = useState(false)
  const [trips, setTrips] = useState<ManualTrip[]>([]), [selectedTripRaw, setSelectedTrip] = useState<ManualTrip | null>(null)
  const [creating, setCreating] = useState(params.mode === 'create'), [name, setName] = useState('')
  const [editTrip, setEditTrip] = useState<ManualTrip | null>(null), [chosen, setChosen] = useState<string[]>([])
  const [legPage, setLegPage] = useState(0), [loading, setLoading] = useState(false), [error, setError] = useState<string | null>(null)
  const [cacheWarning, setCacheWarning] = useState(false)
  const [rangeShown, setRangeShown] = useState(20)
  const epoch = useRef(0), currentCar = useRef<Car | null>(null), scope = useRef<TripScope | null>(null)
  const displayGeneration = useRef<number | null>(null), pageVisible = useRef(false)

  const invalidate = () => { epoch.current++; setLoading(false) }
  useDidHide(() => { pageVisible.current = false; invalidate() }); useUnload(() => { pageVisible.current = false; invalidate() })
  const clearDisplay = () => {
    setCars([]); setCar(null); setDrives([]); setCharges([]); setTrips([]); setSelectedTrip(null); setHealth(null); setHealthEvidence(null)
    setCurrentCharge(null); setStatus(null); currentCar.current = null; scope.current = null; setEditTrip(null); setChosen([]); setName(''); setCreating(false)
    setDriveCoverage(emptyCoverage); setChargeCoverage(emptyCoverage); setOffline(false)
    displayGeneration.current = null
  }
  const load = async (requested?: Car) => {
    const operation = ++epoch.current
    try {
      const current = readAppSession(Taro)
      if (scope.current && (scope.current.userId !== current?.userId || scope.current.origin !== getApiOrigin() || displayGeneration.current !== getApiSessionGeneration())) clearDisplay()
    } catch { clearDisplay() }
    setLoading(true)
    await ensureWechatSession()
    if (operation !== epoch.current) return
    const owner = readAppSession(Taro), generation = getApiSessionGeneration()
    if (!owner?.userId) { setLoading(false); setError('请先在“更多”中登录'); clearDisplay(); return }
    let origin: string
    try { origin = getApiOrigin() } catch (reason) { setLoading(false); setError(message(reason)); clearDisplay(); return }
    if (scope.current && (scope.current.userId !== owner.userId || scope.current.origin !== origin || requested && requested.stableId !== scope.current.stableId)) clearDisplay()
    const valid = () => { try { return operation === epoch.current && generation === getApiSessionGeneration() && readAppSession(Taro)?.userId === owner.userId && getApiOrigin() === origin } catch { return false } }
    setLoading(true); setError(null); setCacheWarning(false)
    let available: Car[]
    let remote = true
    try { available = await matelinkApi.getCars() }
    catch (reason) {
      if (!valid()) return
      available = readCarsCache(Taro, owner.userId, origin)
      remote = false
      if (!available.length) { setError(message(reason)); setLoading(false); setCar(null); setDrives([]); setCharges([]); setTrips([]); return }
    }
    if (!valid()) return
    const remembered = Taro.getStorageSync(carSelectionStorageKey(owner.userId))
    const next = available.find(row => row.stableId === requested?.stableId) ?? available.find(row => row.stableId === remembered) ?? available[0]
    setCars(available)
    if (!next) { setCar(null); setLoading(false); setError('账号下没有可读取的车辆'); return }
    const nextScope = { userId: owner.userId, origin, stableId: next.stableId }
    const changed = currentCar.current?.stableId !== next.stableId || scope.current?.userId !== owner.userId || scope.current?.origin !== origin
    currentCar.current = next; scope.current = nextScope; displayGeneration.current = generation; setCar(next); setOffline(!remote)
    const saved = readManualTrips(Taro, nextScope); setTrips(saved)
    if (changed) { setCreating(params.mode === 'create'); setEditTrip(null); setName(''); setChosen([]); setLegPage(0); setRangeShown(20); setSelectedTrip(saved.find(trip => trip.id === params.tripId) ?? null); setQuery(emptyHistoryQuery()) }
    setHealth(null); setHealthEvidence(null); setStatus(null); setCurrentCharge(null)
    setDriveCoverage({ ...emptyCoverage, reason: remote ? 'loading' : 'cached' }); setChargeCoverage({ ...emptyCoverage, reason: remote ? 'loading' : 'cached' })
    setDrives(readHistoryCache<Drive>(Taro, historyCacheKey('drives', owner.userId, next.stableId, origin)))
    setCharges(readHistoryCache<Charge>(Taro, historyCacheKey('charges', owner.userId, next.stableId, origin)))
    if (!remote) { setLoading(false); return }
    const work: Promise<void>[] = []
    const failed: string[] = []
    if (kind === 'range' || kind === 'longTrips') work.push(analysisApi.getHistory(next, 'drives', { isCurrent: valid }).then(result => {
      if (!valid()) return
      setDrives(result.items as Drive[]); setDriveCoverage({ complete: result.complete, pages: result.pages, reason: result.reason })
      if (!writeHistoryCache(Taro, historyCacheKey('drives', owner.userId, next.stableId, origin), result.items)) setCacheWarning(true)
    }).catch(reason => { if (valid()) failed.push(message(reason)) }))
    if (kind === 'cost' || kind === 'longTrips') work.push(analysisApi.getHistory(next, 'charges', { isCurrent: valid }).then(result => {
      if (!valid()) return
      setCharges(result.items as Charge[]); setChargeCoverage({ complete: result.complete, pages: result.pages, reason: result.reason })
      if (!writeHistoryCache(Taro, historyCacheKey('charges', owner.userId, next.stableId, origin), result.items)) setCacheWarning(true)
    }).catch(reason => { if (valid()) failed.push(message(reason)) }))
    if (kind === 'battery' || kind === 'range') {
      work.push(analysisApi.getBatteryHealth(next, { isCurrent: valid }).then(result => { if (valid()) { setHealth(result.batteryHealth); setHealthEvidence(result.meta) } }).catch(reason => { if (valid()) failed.push(message(reason)) }))
      work.push(matelinkApi.getCarStatus(next.id, next).then(result => { if (valid()) setStatus(result) }).catch(reason => { if (valid()) failed.push(message(reason)) }))
    }
    if (kind === 'currentCharge') work.push(analysisApi.getCurrentCharge(next, { isCurrent: valid }).then(result => { if (valid()) setCurrentCharge(result) }).catch(reason => { if (valid()) failed.push(message(reason)) }))
    await Promise.all(work)
    if (valid()) { setLoading(false); if (failed.length) setError([...new Set(failed)].join('；')) }
  }
  useSessionRecoveryReload(() => { if (pageVisible.current) void load() })
  useDidShow(() => { pageVisible.current = true; void load() })
  const filteredDrives = filterHistory(drives, query).map(insightDrive), filteredCharges = filterHistory(charges, query)
  const activeSession = readAppSession(Taro)
  const costScope = activeSession?.userId && scope.current?.userId === activeSession.userId ? scope.current : null
  const allCostCharges = [
    ...trips.flatMap(trip => trip.legs.flatMap(leg => leg.kind === 'charge' ? [leg.charge] : [])),
    ...charges,
    ...(currentChargeRaw ? [currentChargeRaw] : []),
  ]
  const costIdentity = (charge: Charge) => JSON.stringify([charge.source, historyRowKey(charge)])
  const costByKey = new Map((costScope ? overlayChargeCosts(Taro, costScope, allCostCharges, chargeEstimatePrice(Taro, costScope)) : []).map(charge => [costIdentity(charge), charge]))
  const displayCharge = (charge: Charge): Charge | CostedCharge => costByKey.get(costIdentity(charge)) ?? charge
  const currentCharge = currentChargeRaw && !isHistoryQuarantined(currentChargeRaw) && currentChargeRaw.isCharging !== false && currentChargeRaw.currentVehicleIdentityVerified !== false
    ? (costScope ? overlayChargeCosts(Taro, costScope, [currentChargeRaw])[0] : currentChargeRaw) : null
  const selectedTrip = selectedTripRaw ? { ...selectedTripRaw, legs: selectedTripRaw.legs.map(leg => leg.kind === 'charge' ? { kind: 'charge' as const, charge: displayCharge(leg.charge) } : leg) } : null
  // App analytics excludes incomplete/unverified history; manual long trips retain their saved raw legs.
  const analysisDrives = drives.filter(isAppDriveAnalysisEligible)
  const displayedCosts = filteredCharges.filter(isAppChargeAnalysisEligible).map(displayCharge), costs = chargeCosts(displayedCosts)
  const manualCostCount = displayedCosts.filter(charge => 'costProvenance' in charge && charge.costProvenance === 'manual').length
  const estimateIn = (currency: string, month?: string, address?: string) => displayedCosts.some(row => {
    if (!('costProvenance' in row) || row.costProvenance !== 'estimate' || (row.currencyCode ?? row.currency) !== currency) return false
    const date = row.startDate ? new Date(row.startDate) : null
    const rowMonth = date && Number.isFinite(date.getTime()) ? `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}` : '日期未知'
    return (!month || rowMonth === month) && (!address || row.address === address)
  })
  const costAmount = (value: number | null, estimated: boolean) => estimated && value != null ? `≈${displayNumber(value, 2)}` : value
  const tariff = activeSession?.userId && scope.current?.userId === activeSession.userId ? readTariff(Taro, activeSession.userId, scope.current.origin) : null
  const estimatedCosts = tariff ? displayedCosts.filter(charge => charge.cost == null || ('costProvenance' in charge && charge.costProvenance === 'estimate')).flatMap(charge => {
    const cost = estimateTariffCost(charge, tariff)
    return cost == null ? [] : [{ charge, cost }]
  }) : []
  const capacity: CapacityEvidence | null = health ? { currentCapacityKwh: health.currentCapacityKwh, ...healthEvidence } : null
  const range = personalizedRange(analysisDrives, capacity, status)
  const comparisons = ratedRangeComparisons(filteredDrives.filter(isAppDriveAnalysisEligible))
  const influences = rangeInfluences(analysisDrives)
  const averageDeviation = comparisons.length ? comparisons.reduce((sum, row) => sum + row.deviationPercent, 0) / comparisons.length : null
  const legs: TripLeg[] = [...filteredDrives.map(drive => ({ kind: 'drive' as const, drive })), ...filteredCharges.map(charge => ({ kind: 'charge' as const, charge }))]
  const candidates = new Map<string, TripLeg>()
  for (const leg of [...(editTrip?.legs ?? []), ...legs]) if (!isHistoryQuarantined(legRow(leg))) candidates.set(tripLegKey(leg), leg)
  const candidateLegsRaw = [...candidates.values()].sort((a, b) => Date.parse(legRow(a).startDate ?? '') - Date.parse(legRow(b).startDate ?? ''))
  const candidateLegs = candidateLegsRaw.map(leg => leg.kind === 'charge' ? { kind: 'charge' as const, charge: displayCharge(leg.charge) } : leg)
  const selectedLegs = candidateLegsRaw.filter(leg => chosen.includes(tripLegKey(leg)))
  const preview = makeManualTrip(editTrip?.id ?? 'preview', name || '未命名长途旅程', selectedLegs)
  const resetDraft = () => { setCreating(false); setEditTrip(null); setChosen([]); setName(''); setLegPage(0) }
  const saveTrip = () => {
    const owner = readAppSession(Taro), current = scope.current
    if (!current || owner?.userId !== current.userId || getApiOrigin() !== current.origin) { setError('会话已改变，请重新加载'); return }
    const result = makeManualTrip(editTrip?.id ?? `trip-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`, name, selectedLegs, editTrip?.createdAt)
    if (!result) { setError('请输入名称并选择至少一条有效行驶记录，每次最多50段'); return }
    if (!writeManualTrip(Taro, current, result)) { setError('本机长途旅程未保存：最多20条、64KiB且受共享缓存预算限制；已有记录已保留'); return }
    setTrips(readManualTrips(Taro, current)); setSelectedTrip(result); resetDraft(); setError(null)
  }
  const removeTrip = async (trip: ManualTrip) => {
    const current = scope.current, operation = epoch.current
    if (!current) return
    const result = await Taro.showModal({ title: '删除本机长途旅程', content: `仅删除“${trip.name}”的本机手动组合，原行驶与充电记录保留。` })
    if (!result.confirm || operation !== epoch.current || readAppSession(Taro)?.userId !== current.userId || scope.current !== current) return
    if (removeManualTrip(Taro, current, trip.id)) { setTrips(readManualTrips(Taro, current)); setSelectedTrip(null) } else setError('本机记录暂时不可写，请重试')
  }
  const summary = (trip: ManualTrip) => {
    const view = { ...trip, legs: trip.legs.map(leg => leg.kind === 'charge' ? { kind: 'charge' as const, charge: displayCharge(leg.charge) } : leg) }
    const result = manualTripSummary(view)
    return <View className="metric-grid">
      <MetricValue label="行驶距离" value={result.distance.value} unit="km" />
      <MetricValue label="行驶时长" value={result.duration.value} unit="分钟" />
      <MetricValue label="合格净能量" value={result.energy.value} unit="kWh" negative={(result.energy.value ?? 0) < 0} />
      <MetricValue label="配对平均能耗" value={result.efficiency.value} unit="kWh/100km" negative={(result.efficiency.value ?? 0) < 0} />
      <Text className="muted">{result.energy.total === 0 ? '暂无能耗样本' : `能量覆盖 ${result.energy.known}/${result.energy.total}`} · {result.efficiency.total === 0 ? '无配对' : `能耗配对 ${result.efficiency.known}/${result.efficiency.total}`} · 含估算 {result.estimatedEnergy} 段</Text>
      <Text className="muted">{result.distance.total === 0 ? '暂无里程样本' : `里程覆盖 ${result.distance.known}/${result.distance.total}`} · {result.duration.total === 0 ? '无时长' : `时长覆盖 ${result.duration.known}/${result.duration.total}`}</Text>
      <Text className="muted">{result.driveCount} 段行驶 · {result.chargeCount} 次充电</Text>
      {view.legs.some(leg => leg.kind === 'charge' && 'costProvenance' in leg.charge && leg.charge.costProvenance === 'manual') ? <Text className="muted">包含本机手动费用，原服务端账单保留。</Text> : null}
      {result.costs.groups.map(group => <MetricValue key={group.currency} label="所选充电费用" value={costAmount(group.cost, view.legs.some(leg => leg.kind === 'charge' && 'costProvenance' in leg.charge && leg.charge.costProvenance === 'estimate' && (leg.charge.currencyCode ?? leg.charge.currency) === group.currency))} unit={group.currency} />)}
      {result.costs.unknownCurrency ? <Text className="muted">{result.costs.unknownCurrency} 条费用币种未知，未并入合计</Text> : null}
    </View>
  }
  const coverage = (value: Coverage, noun: string, rows: number) => <Text className="insight-coverage">{offline ? '本机缓存' : value.complete ? '数据读取完成' : '部分覆盖'} · {noun} {rows} 条</Text>

  return <View className="page insights-page subpage-page">
    <SubpageHeader title={titles[kind]} subtitle={carName(car)} />
    {cars.length > 1 ? <Picker mode="selector" range={cars.map(value => carName(value))} value={Math.max(0, cars.findIndex(row => row.stableId === car?.stableId))} onChange={event => { const next = cars[Number(event.detail.value)]; if (next) void load(next) }}><View className="status">切换车辆：{carName(car)}</View></Picker> : null}
    <Button className="button-secondary" loading={loading} onClick={() => { void load(currentCar.current ?? undefined) }}>刷新数据</Button>
    {loading ? <LoadingIndicator label="正在读取所选车辆数据…" compact /> : null}
    {error ? <Text className="error">{error}</Text> : null}
    {cacheWarning ? <Text className="muted">数据已读取，本机缓存预算不足，本次未全部保存；已有缓存保留。</Text> : null}
    {drives.some(isHistoryIdentityAmbiguous) || charges.some(isHistoryIdentityAmbiguous) ? <Text className="muted">来源身份未确认的历史记录已保留，但未计入当前展示或统计</Text> : null}
    {!readAppSession(Taro)?.userId ? <Button className="button" onClick={() => Taro.switchTab({ url: '/pages/me/index' })}>前往登录</Button> : null}
    {car && (kind === 'range' || kind === 'cost' || kind === 'longTrips') ? <View className="insight-filters">
      <HistoryDateChips kind={kind === 'cost' ? 'charges' : 'drives'} query={query} onChange={patch => { setQuery({ ...query, ...patch }); setLegPage(0) }} />
      <View className="history-filter-row"><Picker mode="date" value={query.startDate} onChange={event => setQuery(selectHistoryDate(query, 'startDate', event.detail.value))}><View>开始：{query.startDate || '不限'}</View></Picker><Picker mode="date" value={query.endDate} onChange={event => setQuery(selectHistoryDate(query, 'endDate', event.detail.value))}><View>结束：{query.endDate || '不限'}</View></Picker></View>
      {kind !== 'cost' ? coverage(driveCoverage, '行驶', drives.filter(row => !isHistoryIdentityAmbiguous(row)).length) : null}{kind !== 'range' ? coverage(chargeCoverage, '充电', charges.filter(row => !isHistoryIdentityAmbiguous(row)).length) : null}
    </View> : null}

    {car && kind === 'battery' ? <>
      <View className="card"><SectionHeading icon="battery" title="电池容量" /><Text className="muted">容量与健康来自服务端实际报告，不使用当前SOC猜测。</Text>
        <View className="metric-grid"><MetricValue label="当前容量" value={health?.currentCapacityKwh ?? null} unit="kWh" /><MetricValue label="原始容量" value={health?.maxCapacityKwh ?? null} unit="kWh" /></View>
        <Text className="muted">{healthEvidence?.availability === 'unsupported' ? '此数据源尚未提供电池健康合同' : health ? '服务端容量报告' : '暂无合格容量报告'}</Text>
        <Text className="metric-source">{sourceLabel(healthEvidence?.source)} · {healthEvidence?.observedAt || '报告时间未提供'}</Text>
      </View>
      <View className="card"><SectionHeading icon="shield" title="电池健康" /><View className="metric-grid"><MetricValue label="服务端健康比例" value={health?.healthPercentage ?? null} unit="%" /><MetricValue label="容量衰减" value={health?.healthPercentage != null ? 100 - health.healthPercentage : null} unit="%" /><MetricValue label="额定能耗" value={health?.ratedEfficiencyWhKm != null ? health.ratedEfficiencyWhKm / 10 : null} unit="kWh/100km" /></View></View>
      <View className="card"><SectionHeading icon="distance" title="续航信息" /><View className="metric-grid"><MetricValue label="最大报告续航" value={health?.maxRangeKm ?? null} unit="km" /><MetricValue label="当前报告续航" value={health?.currentRangeKm ?? status?.ratedRange ?? null} unit="km" /></View><Text className="muted">当前车辆续航读数与电池健康证据分别展示，缺失健康不会被SOC替代。</Text></View>
      <View className="card"><SectionHeading icon="chart" title="健康趋势与检测" /><Text className="muted">只在来源返回合格的连续容量历史时绘制趋势；当前接口未提供趋势样本，不生成示例曲线。车辆服务模式检测请在车辆内进行。</Text></View>
    </> : null}
    {car && kind === 'range' ? <>
      <View className="card"><SectionHeading icon="route" title="个性化续航" /><MetricValue label="合格能耗与容量估算" value={range.rangeKm} unit="km" /><Text className="muted">近90天：同温度/速度条件至少5段且100km；不足时采用全部条件至少10段且300km。</Text>
        <Text className="muted">此模型固定使用近90天的正能量与正里程；下方日期筛选用于额定续航偏差记录。</Text>
        <Text className="muted">{range.source === 'unavailable' ? `样本不足：可用候选 ${range.candidateCount} 段` : `${range.source === 'grouped' ? '当前条件分组' : '全部条件'} · ${range.sampleCount} 段 / ${range.distanceKm.toFixed(1)} km · 含估算 ${range.estimated} 段`}</Text>
        {range.capacityKwh == null ? <Text className="muted">缺少带来源与报告时间的可用容量，个性化续航保持未知。</Text> : null}
        <View className="metric-grid"><MetricValue label="配对平均能耗" value={range.efficiencyKwh100Km} unit="kWh/100km" /><MetricValue label="车辆额定续航读数" value={status?.ratedRange ?? null} unit="km" /></View>
      </View>
      <View className="card"><SectionHeading icon="speed" title="额定续航偏差" /><MetricValue label="有效记录平均偏差" value={averageDeviation} unit="%" /><Text className="muted">逐段比较额定续航下降与实际里程；仅使用同时有两个端点及规范化公里数的记录，共 {comparisons.length} 段。</Text></View>
      <View className="card"><SectionHeading icon="cloud" title="续航影响因素" /><View className="metric-grid"><MetricValue label="夏季偏差（6–8月）" value={influences.summer} unit="%" /><MetricValue label="冬季偏差（12–2月）" value={influences.winter} unit="%" /><MetricValue label="低速偏差（≤40km/h）" value={influences.lowSpeed} unit="%" /><MetricValue label="高速偏差（>40km/h）" value={influences.highSpeed} unit="%" /></View><Text className="muted">沿用 App 的已加载记录分组；这是相关观察，不能确定单一原因。</Text></View>
      {comparisons.slice(0, rangeShown).map(item => <View className="card" key={historyRowKey(item.drive)}><Text>{historyDateTime(item.drive.startDate)}</Text><View className="metric-grid"><MetricValue label="额定续航下降" value={item.ratedDropKm} unit="km" /><MetricValue label="实际里程" value={item.distanceKm} unit="km" /><MetricValue label="偏差" value={item.deviationPercent} unit="%" /></View></View>)}
      {comparisons.length > rangeShown ? <Button className="button-secondary" onClick={() => setRangeShown(rangeShown + 20)}>查看更多续航记录（{rangeShown}/{comparisons.length}）</Button> : null}
      {!comparisons.length ? <Text className="muted">已加载记录尚缺额定续航起止端点，不能从SOC或当前续航补算历史偏差。</Text> : null}
    </> : null}
    {car && kind === 'cost' ? <>
      {!costs.groups.length ? <View className="card"><Text className="section-title">暂无可合计的费用</Text><Text className="muted">费用与币种同时明确才参与合计；真实0会保留。</Text></View> : null}
      <Text className="muted">已加载 {costs.total} 次；缺费用 {costs.missingCost} 次；币种未知 {costs.unknownCurrency} 次。不同币种分别统计。</Text>
      {manualCostCount ? <Text className="muted">含 {manualCostCount} 条本机手动费用；这是本机展示金额，服务端原账单保留。</Text> : null}
      {costs.groups.map(group => <View className="card" key={group.currency}><SectionHeading icon="money" title={`${group.currency} 费用`} /><View className="metric-grid">
        <MetricValue label="费用合计" value={costAmount(group.cost, estimateIn(group.currency))} unit={group.currency} /><MetricValue label="单次平均费用" value={costAmount(group.average, estimateIn(group.currency))} unit={group.currency} />
        <MetricValue label="费用与电池充入量配对" value={group.energyKwh} unit="kWh" /><MetricValue label="配对单位电量费用" value={costAmount(group.perKwh, estimateIn(group.currency))} unit={`${group.currency}/kWh`} /></View>
        <Text className="muted">费用已知 {group.known}/{group.total} 条；单位电量费用只用同次充电的费用与合格电池充入量。</Text>
        {estimateIn(group.currency) ? <Text className="muted">≈ 包含按默认电价估算的费用。</Text> : null}
        <Text className="section-title">月度费用</Text>{group.months.map(month => <View className="insight-table-row" key={month.month}><Text>{month.month}</Text><Text>{costAmount(month.cost, estimateIn(group.currency, month.month)) ?? '未知'} {group.currency}</Text><Text className="muted">AC {costAmount(month.ac, estimateIn(group.currency, month.month)) ?? '未知'} / DC {costAmount(month.dc, estimateIn(group.currency, month.month)) ?? '未知'} / 模式未知 {costAmount(month.unknownMode, estimateIn(group.currency, month.month)) ?? '无'} {group.currency}</Text></View>)}
        <Text className="section-title">常用充电地点</Text>{group.locations.length ? group.locations.map(location => <View className="insight-table-row" key={location.address}><Text>{location.address}</Text><Text>{costAmount(location.cost, estimateIn(group.currency, undefined, location.address)) ?? '未知'} {group.currency} · {location.count} 次</Text></View>) : <Text className="muted">未提供地点报告</Text>}
      </View>)}
    </> : null}
      {car && kind === 'cost' && tariff ? <View className="card"><SectionHeading icon="settings" title="自定义费率估算" /><Text className="muted">只估算缺少实际账单的充电；按保存费率时的本机时区（{tariff.timeZone || '未记录'}）与均匀AC输入电量估算，不写回实际费用。</Text>
      {estimatedCosts.length ? estimatedCosts.map(item => <View className="insight-table-row" key={historyRowKey(item.charge)}><Text>{historyDateTime(item.charge.startDate)}</Text><MetricValue label="费率估算" value={item.cost} unit={tariff.currency} /></View>) : <Text className="muted">当前没有同时缺账单且具备合格输入电量与起止时间的记录；真实0费用保留。</Text>}
    </View> : null}
    {car && kind === 'longTrips' ? <>
      {!creating && !selectedTrip ? <>
        <Button className="button" onClick={() => { setCreating(true); setSelectedTrip(null); setChosen([]); setName(''); setEditTrip(null) }}>创建长途旅程</Button>
        <Text className="muted">本机手动组合 · {trips.length}/20 条；原行驶与充电记录保留，不自动把全部历史拼成长途。</Text>
        {trips.length ? trips.map(trip => <View className="card" key={trip.id} onClick={() => setSelectedTrip(trip)}><SectionHeading icon="route" title={trip.name} /><Text className="muted">创建于 {historyDateTime(trip.createdAt)}</Text>{summary(trip)}</View>) : <View className="card"><SectionHeading icon="route" title="尚无手动长途旅程" /><Text className="muted">选择已有行驶与充电记录，预览后保存。</Text></View>}
      </> : null}
      {creating ? <View className="card"><SectionHeading icon="route" title={editTrip ? '编辑长途旅程' : '创建长途旅程'} /><Input className="insight-name" value={name} maxlength={80} placeholder="长途旅程名称" onInput={event => setName(event.detail.value)} />
        {preview ? summary(preview) : <Text className="muted">选择至少一段行驶记录预览；最多50段。</Text>}
        <Text className="muted">选择记录（{chosen.length}/50） · 第 {legPage + 1}/{Math.max(1, Math.ceil(candidateLegs.length / 20))} 页</Text>
        {candidateLegs.slice(legPage * 20, legPage * 20 + 20).map(leg => <Button className={`insight-leg ${chosen.includes(tripLegKey(leg)) ? 'insight-leg-selected' : ''}`} key={tripLegKey(leg)} onClick={() => setChosen(previous => previous.includes(tripLegKey(leg)) ? previous.filter(key => key !== tripLegKey(leg)) : previous.length < 50 ? [...previous, tripLegKey(leg)] : previous)}>{chosen.includes(tripLegKey(leg)) ? '已选' : '选择'} {dataLabel(leg)} · {historyDateTime(legRow(leg).startDate)}{leg.kind === 'drive' ? ` · ${leg.drive.distanceKm ?? '未知'} km` : ` · ${leg.charge.cost ?? '未知'} ${leg.charge.currencyCode || leg.charge.currency || '币种未知'}`}</Button>)}
        {legPage > 0 ? <Button className="button-secondary" onClick={() => setLegPage(legPage - 1)}>上一页记录</Button> : null}{(legPage + 1) * 20 < candidateLegs.length ? <Button className="button-secondary" onClick={() => setLegPage(legPage + 1)}>下一页记录</Button> : null}
        <Button className="button" disabled={!name.trim() || !preview} onClick={saveTrip}>保存本机长途旅程</Button><Button className="button-secondary" onClick={resetDraft}>取消编辑</Button>
      </View> : null}
      {selectedTrip && !creating ? <View className="card"><Button className="button-secondary" onClick={() => setSelectedTrip(null)}>返回长途列表</Button><SectionHeading icon="route" title={selectedTrip.name} />{summary(selectedTrip)}
        <Text className="section-title">行程时间线</Text>{selectedTrip.legs.map(leg => <View className="insight-table-row" key={tripLegKey(leg)}><Text>{dataLabel(leg)} · {historyDateTime(legRow(leg).startDate)}</Text><Text className="metric-source">{sourceLabel(legRow(leg).source)} · {historyDateTime(legRow(leg).endDate)}</Text>{leg.kind === 'drive' ? <MetricValue label="段平均能耗" value={aggregateDriveHistory([insightDrive(leg.drive)]).efficiency.value} unit="kWh/100km" /> : <MetricValue label="段费用" value={leg.charge.cost} unit={leg.charge.currencyCode || '币种未知'} />}<Button className="button-secondary" disabled={offline || car.remoteVerified !== true} onClick={() => Taro.navigateTo({ url: leg.kind === 'drive' ? `/pages/analysis/index?kind=curves&id=${encodeURIComponent(leg.drive.id)}` : `/pages/analysis/index?kind=timeline&chargeId=${encodeURIComponent(leg.charge.id)}` })}>查看原始{dataLabel(leg)}详情</Button></View>)}
        <Button className="button-secondary" onClick={() => { setEditTrip(selectedTripRaw); setName(selectedTrip.name); setChosen(selectedTrip.legs.map(tripLegKey)); setCreating(true); setLegPage(0) }}>编辑长途旅程</Button>
        <Button className="button-secondary" onClick={() => { void removeTrip(selectedTrip) }}>删除本机长途旅程</Button>
      </View> : null}
    </> : null}
    {car && kind === 'currentCharge' ? <View className="card"><SectionHeading icon="energy" title="正在充电" />{currentCharge ? <>
      <Text className="muted">{currentCharge.currentIsSnapshot ? '状态观测' : '开始'}：{historyDateTime(currentCharge.currentIsSnapshot ? currentCharge.currentObservedAt : currentCharge.startDate)} · {sourceLabel(currentCharge.source)}</Text>
      <View className="metric-grid"><MetricValue label="电池充入" value={currentCharge.currentEnergyAddedKwh ?? currentCharge.energyAddedKwh} unit="kWh" /><MetricValue label="当前电量" value={currentCharge.currentBatteryLevel} unit="%" /><MetricValue label="当前充电功率" value={currentCharge.chargerPowerKw} unit="kW" /><MetricValue label="已持续" value={currentCharge.durationMinutes} unit="分钟" /><MetricValue label="当前费用" value={currentCharge.cost} unit={currentCharge.currencyCode || currentCharge.currency || '币种未知'} /></View>
      <Text className="muted">充电功率与累计电量分别展示；刷新时只读服务端报告。</Text>
      {'costProvenance' in currentCharge && currentCharge.costProvenance === 'manual' ? <Text className="muted">当前显示本机手动费用，服务端账单保留。</Text> : null}
    </> : !loading ? <Text className="muted">{currentChargeRaw && currentChargeRaw.isCharging !== false && currentChargeRaw.currentVehicleIdentityVerified === false ? '当前充电的车辆身份未确认，请刷新重试。' : '当前没有服务端报告的充电记录，或车辆尚未提供该状态。'}</Text> : null}<Button className="button-secondary" onClick={() => Taro.switchTab({ url: '/pages/charges/index' })}>查看充电历史</Button></View> : null}
  </View>
}
