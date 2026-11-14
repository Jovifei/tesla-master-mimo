import { ChargeActivityGrid } from '../../components/PresentationControls'
import { calendarHistoryGroups, civilDay, dayCaption, historyDayGroups } from '../../domain/ui_presentation'
import { useAppearance } from '../../services/appearance'
import { MOCK_FIXTURE_ENABLED } from '../../services/mock_fixture'
import { Button, Map as TaroMap, Picker, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow, usePageScroll, useUnload } from '@tarojs/taro'
import { useEffect, useRef, useState } from 'react'
import { formatParkedAddress, toChineseDisplayAddress } from '../../domain/address_display'
import { getAppNavGeometry } from '../../services/nav_geometry'
import { copy } from '../../i18n/copy'
import { historyRowKey, isHistoryIdentityAmbiguous, isHistoryQuarantined, pageCanContinue, sameHistoryIdentity, sortHistoryByStartDate } from '../../domain/history'
import { acceptHistoryDetail, acceptHistoryPage, collectHistoryInterval, enrichHistoryInterval, reconcileHistoryCache, type HistoryInterval } from '../../domain/history_controller'
import { defaultChargeHistoryQuery, emptyHistoryQuery, filterHistory, historyDateTime, historyDetailDateTime, historyEditorialDate, normalizeHistoryQuery, readHistoryQuery, selectHistoryDate, writeHistoryQuery, type HistoryQuery } from '../../domain/history_query'
import { readCarsCache } from '../../services/cars_cache'
import './index.scss'
import { createRequestScopeGate } from '../../domain/request_scope'
import { ApiError, ensureWechatSession, getApiOrigin, getApiSessionGeneration, matelinkApi } from '../../services/api'
import { carSelectionStorageKey, type Car, type Charge, type HistoryPage, type HistoryPageMeta } from '../../services/types'
import { readAppSession } from '../../services/session'
import { useSessionRecoveryReload } from '../../services/use_session_recovery'
import { historyCacheKey, readHistoryCache, writeHistoryCache } from '../../services/history_cache'
import LoadingIndicator from '../../components/LoadingIndicator'
import EvidenceCurve, { chargeCurveProvenance } from '../../components/EvidenceCurve'
import HistoryIntervalSummary from '../../components/HistoryIntervalSummary'
import HistoryDateChips from '../../components/HistoryDateChips'
import { inspectForegroundEvents, observeForegroundEvents, type ForegroundNotice } from '../../services/foreground_events'
import MetricValue, { displayNumber, formatSocRange, formatSocRangeMetric } from '../../components/MetricValue'
import VisualIcon from '../../components/VisualIcon'
import { availabilityLabel, qualityLabel, sourceLabel } from '../../domain/data_labels'
import { resolveChargeEnergy } from '../../domain/drive_energy'
import { qualifiedAcEnergyBalance } from '../../domain/charge_energy_balance'
import { analysisApi } from '../../services/analysis_api'
import type { ChargeEvidence } from '../../services/analysis_types'
import { chargeEstimatePrice, overlayChargeCosts, type CostedCharge } from '../../services/charge_cost'
import { mapCoordinate } from '../../domain/map_coordinate'
import { appChargeListRows, appChargeStatisticsRows } from '../../domain/app_history_projection'

const PAGE_SIZE = 20
// App CurrentChargeViewModel cadence: 30s steady, 4s while waiting for a
// just-started charge session to materialize in the API.
const CURRENT_CHARGE_REFRESH_INTERVAL_MS = 30_000
const CHARGE_STARTING_REFRESH_INTERVAL_MS = 4_000

function errorMessage(reason: unknown): string {
  if (reason instanceof ApiError) return reason.message
  return '无法读取充电数据'
}

function battery(number: number | null): string { return number != null && Number.isFinite(number) && number >= 0 && number <= 100 ? `${number}%` : copy('common.noBattery') }

function verifiedEnergy(charge: Charge) {
  return charge.energy?.evidence ? resolveChargeEnergy({ source: charge.source, start_date: charge.startDate,
    end_date: charge.endDate, energy_contract: charge.energy.evidence }) : null
}
function inputLabel(charge: Charge): string { return charge.chargeModeConflict != null && charge.chargeModeConflict !== false ? '输入电量（模式未确认）' : verifiedEnergy(charge)?.acInputKwh != null ? 'AC 输入' : '输入电量（历史报告）' }
function inputNumber(charge: Charge): number | null {
  const verified = verifiedEnergy(charge)
  return verified ? verified.inputEnergyKwh : charge.energy ? charge.energy.inputEnergyKwh : charge.energyUsedKwh
}
function chargeMode(charge: Charge): 'ac' | 'dc' | 'unknown' {
  if (charge.chargeModeConflict != null && charge.chargeModeConflict !== false) return 'unknown'
  const mode = charge.energy?.evidence ? verifiedEnergy(charge)?.chargeMode : charge.chargeMode
  return mode === 'ac' || mode === 'dc' ? mode : 'unknown'
}
function modeLabel(charge: Charge): string {
  const mode = chargeMode(charge)
  return mode === 'dc' ? copy('charges.dc') : copy('charges.ac')
}
function chargeModeLabel(filter: 'all' | 'ac' | 'dc'): string {
  return filter === 'all' ? '全部' : filter === 'ac' ? copy('charges.ac') : copy('charges.dc')
}
function displayAddress(address: string | null): string {
  if (!address || !address.trim()) return '位置未知'
  return formatParkedAddress(address, '位置未知')
}
function costLabel(charge: CostedCharge): { value: string | number | null; unit?: string } {
  if (charge.partialEstimatedCost != null) return charge.currencyCode === 'CNY'
    ? { value: `≈¥${charge.partialEstimatedCost.toFixed(2)}` }
    : { value: `≈${charge.partialEstimatedCost.toFixed(2)}`, unit: charge.currencyCode || '币种未知' }
  if (charge.costProvenance === 'free') return { value: '免费' }
  if (charge.costProvenance === 'estimate' && charge.cost != null) return charge.currencyCode === 'CNY'
    ? { value: `≈¥${charge.cost.toFixed(2)}` } : { value: `≈${charge.cost.toFixed(2)}`, unit: charge.currencyCode || '币种未知' }
  return { value: charge.cost, unit: charge.currencyCode || charge.currency || '币种未知' }
}
function estimateBasis(charge: CostedCharge): string {
  if (charge.costProvenance !== 'estimate' || charge.estimatedUnitPrice == null) return ''
  const price = charge.currencyCode === 'CNY' ? `¥${charge.estimatedUnitPrice}` : `${charge.estimatedUnitPrice} ${charge.currencyCode}`
  return `${charge.estimatePriceSource === 'saved_default' ? '已保存默认电价' : 'App 默认'} ${price}/kWh`
}
function oneDecimal(value: number | null): string | null {
  if (value == null || !Number.isFinite(value)) return null
  return Number(value.toFixed(1)) === 0 && value !== 0 ? String(value) : value.toFixed(1)
}

function value(value: string | number | null | undefined): string {
  return value == null || value === '' || (typeof value === 'number' && !Number.isFinite(value)) ? copy('common.noData') : String(value)
}

const emptyMeta: HistoryPageMeta = {
  page: 1, show: PAGE_SIZE, total: null, totalPages: null, availability: null,
  source: null, qualityState: null, qualityReason: null, hasMore: false,
}

export default function ChargesPage() {
  const appearance = useAppearance()
  const [cars, setCars] = useState<Car[]>([])
  const [car, setCar] = useState<Car | null>(null)
  const [items, setItems] = useState<Charge[]>([])
  const [showShortCharges, setShowShortCharges] = useState(true)
  const [current, setCurrent] = useState<Charge | null>(null)
  // App CurrentChargeViewModel polls while visible; a fast loop covers the
  // window where the car reports charging but the session is not materialized.
  const [chargeStarting, setChargeStarting] = useState(false)
  const pollTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const pollCarRef = useRef<Car | null>(null)
  const pollStartingRef = useRef(false)
  const [meta, setMeta] = useState<HistoryPageMeta | null>(null)
  const [selected, setSelectedState] = useState<Charge | null>(null)
  const detailContext = useRef<{ user: string; origin: string; uid: string; generation: number; row: Charge } | null>(null)
  const detailCacheBaseline = useRef<Map<string, string> | null>(null)
  const listScrollTop = useRef(0), detailScrollTop = useRef(0), pageVisible = useRef(false)
  const nativeTabBar = async (hidden: boolean) => {
    try {
      const action = hidden ? Taro.hideTabBar : Taro.showTabBar
      if (typeof action === 'function') await action({ animation: false })
    } catch { /* Non-tab hosts and unavailable native APIs keep the panel usable. */ }
  }
  const setSelected = (row: Charge | null) => {
    if (row) {
      const session = readAppSession(Taro)
      if (!session?.userId || !car) return Promise.resolve()
      if (!detailContext.current || historyRowKey(detailContext.current.row) !== historyRowKey(row)) detailScrollTop.current = 0
      detailContext.current = { user: session.userId, origin: getApiOrigin(), uid: car.stableId, generation: getApiSessionGeneration(), row }
    } else { detailContext.current = null; detailCacheBaseline.current = null }
    setSelectedState(row)
    return nativeTabBar(Boolean(row))
  }
  const [selectedEvidence, setSelectedEvidence] = useState<ChargeEvidence | null>(null)
  const [curveSampleIndex, setCurveSampleIndex] = useState(0)
  const selectedIdRef = useRef<string | null>(null)
  useEffect(() => {
    selectedIdRef.current = selected?.id ?? null
  }, [selected])
  const [chartKind, setChartKind] = useState<'energy' | 'cost' | 'count'>('energy')
  const [chargeTypeFilter, setChargeTypeFilter] = useState<'all' | 'ac' | 'dc'>('all')
  const [costFilter, setCostFilter] = useState<'all' | 'has' | 'none'>('all')
  const [selectedLocation, setSelectedLocation] = useState<string | null>(null)
  const [mapFailed, setMapFailed] = useState(false)
  const [, setCostVersion] = useState(0)
  const selectedRef = useRef(selected), carRef = useRef(car)
  selectedRef.current = selected; carRef.current = car
  const [interval, setInterval] = useState<HistoryInterval<Charge> | null>(null)
  const [notices, setNotices] = useState<ForegroundNotice[]>([])
  const notice = notices[0] ?? null
  const [noticeLimited, setNoticeLimited] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [query, setQueryState] = useState<HistoryQuery>(defaultChargeHistoryQuery())
  const [filtersExpanded, setFiltersExpanded] = useState(false)
  const queryRef = useRef(query)
  const setQuery = (next: HistoryQuery) => { queryRef.current = next; setQueryState(next) }
  const [offline, setOffline] = useState(false)
  const verifiedCarRef = useRef(false)
  const queryScopeRef = useRef<string | null>(null)
  const [cacheWarning, setCacheWarning] = useState(false)
  const [loading, setLoading] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const itemsRef = useRef<Charge[]>([])
  const pagesRef = useRef<HistoryPage<Charge>[]>([])
  const loadingMoreRef = useRef(false)
  const pageRef = useRef(0)
  const lifecycleEpoch = useRef(0)
  const displayOwner = useRef<string | null>(null)
  const scopeGate = useRef(createRequestScopeGate())
  const currentOwner = () => {
    try { const current = readAppSession(Taro); return current?.userId ? JSON.stringify([getApiOrigin(), current.userId, getApiSessionGeneration()]) : null }
    catch { return null }
  }

  const resetHistoryState = () => {
    itemsRef.current = []
    pagesRef.current = []
    pageRef.current = 0
    setItems([])
    setMeta(null)
    void setSelected(null)
    setSelectedEvidence(null); setMapFailed(false)
    setInterval(null)
    setNotices([])
    setNoticeLimited(false)
    setCacheWarning(false)
    queryScopeRef.current = null
    setQuery(defaultChargeHistoryQuery())
  }

  const invalidatePage = () => {
    lifecycleEpoch.current += 1
    scopeGate.current.invalidate()
    loadingMoreRef.current = false
    setLoading(false)
    setLoadingMore(false)
    setNotices([])
    setNoticeLimited(false)
  }

  const restoreCached = (nextCar: Car) => {
    const session = readAppSession(Taro)
    if (!session?.userId) return
    const key = historyCacheKey('charges', session.userId, nextCar.stableId, getApiOrigin())
    queryScopeRef.current = key
    setQuery(readHistoryQuery(Taro, key, defaultChargeHistoryQuery()))
    const cached = sortHistoryByStartDate(readHistoryCache<Charge>(Taro, key))
    itemsRef.current = cached
    pagesRef.current = []
    pageRef.current = 0
    setItems(cached)
    setInterval({ items: cached, complete: false, receivedPages: 0, reason: 'cached' })
    setMeta({ ...emptyMeta, availability: 'cached', source: 'local_history', hasMore: false })
  }

  const updateQuery = (patch: Partial<HistoryQuery>) => {
    const next = normalizeHistoryQuery({ ...queryRef.current, ...patch })
    if (queryScopeRef.current) writeHistoryQuery(Taro, queryScopeRef.current, next)
    setQuery(next)
  }
  const closeDetail = () => {
    const scrollEpoch = lifecycleEpoch.current
    try { scopeGate.current.begin('detail') } catch { /* no bound remote scope */ }
    setSelectedEvidence(null)
    void setSelected(null).then(() => {
      if (scrollEpoch !== lifecycleEpoch.current || !pageVisible.current || detailContext.current || typeof Taro.pageScrollTo !== 'function') return
      try { void Taro.pageScrollTo({ scrollTop: listScrollTop.current, duration: 0 }).catch(() => {}) } catch { /* optional native scroll restoration */ }
    })
  }

  const loadHistory = async (nextCar: Car, append = false) => {
    const session = readAppSession(Taro)
    if (!session?.userId) {
      setError('会话缺少账号标识，请重新登录')
      setLoading(false)
      return
    }
    if (!verifiedCarRef.current || nextCar.remoteVerified === false) return
    if (append && loadingMoreRef.current) return
    const nextPage = append ? pageRef.current + 1 : 1
    let apiOrigin: string
    let ticket: { isCurrent: () => boolean }
    try {
      apiOrigin = getApiOrigin()
      ticket = scopeGate.current.begin('history')
    } catch (reason) {
      setError(errorMessage(reason))
      return
    }
    const cacheKey = historyCacheKey('charges', session.userId, nextCar.stableId, apiOrigin)
    queryScopeRef.current = cacheKey
    setQuery(readHistoryQuery(Taro, cacheKey, defaultChargeHistoryQuery()))

    if (!append) {
      setInterval(null)
      setNotices([])
      setNoticeLimited(false)
      scopeGate.current.begin('interval')
      loadingMoreRef.current = false
      itemsRef.current = []
      pagesRef.current = []
      pageRef.current = 0
      if (ticket.isCurrent()) {
        setItems([])
        setMeta(null)
        setSelected(null)
        setCacheWarning(false)
        setLoadingMore(false)
      }
      const cached = sortHistoryByStartDate(readHistoryCache<Charge>(Taro, cacheKey))
      if (ticket.isCurrent() && cached.length > 0) {
        itemsRef.current = cached
        setItems(cached)
        setMeta({ ...emptyMeta, availability: 'cached', source: 'local_history', hasMore: false })
      }
    }

    if (append) {
      loadingMoreRef.current = true
      setLoadingMore(true)
    } else {
      setLoading(true)
    }

    try {
      const result = await matelinkApi.getCharges(nextCar.id, nextPage, PAGE_SIZE, nextCar)
      if (!ticket.isCurrent()) return
      const decision = acceptHistoryPage({ pages: pagesRef.current, items: itemsRef.current, page: pageRef.current }, result, nextPage, historyRowKey)
      if (!decision.accepted) {
        const reason = decision.errorReason ?? 'pagination_incomplete'
        setMeta(current => append && current
          ? { ...current, hasMore: true, qualityReason: current.qualityReason ?? reason }
          : { ...result.meta, hasMore: false, qualityReason: result.meta.qualityReason ?? reason })
        setError('充电分页响应不完整，请重新加载')
        return
      }
      itemsRef.current = decision.state.items
      pagesRef.current = decision.state.pages
      pageRef.current = decision.state.page
      setItems(decision.state.items)
      setInterval(snapshot => snapshot ? { ...snapshot, items: snapshot.items.map(row => decision.state.items.find(item => sameHistoryIdentity(item, row)) ?? row) } : snapshot)
      const saved = writeHistoryCache(Taro, cacheKey, decision.state.items)
      setCacheWarning(!saved)
      setMeta({ ...result.meta, hasMore: decision.hasMore })
      setError(null)
      if (!append) {
        const admittedRows = result.items.map(row => decision.state.items.find(item => sameHistoryIdentity(item, row)) ?? row)
        const notices = observeForegroundEvents(Taro, { origin: apiOrigin, userId: session.userId, stableId: nextCar.stableId }, 'charges', admittedRows)
        setNotices(notices)
        setNoticeLimited(inspectForegroundEvents(Taro, { origin: apiOrigin, userId: session.userId, stableId: nextCar.stableId }, 'charges').limited)
        const intervalTicket = scopeGate.current.begin('interval')
        const canonicalPage = { ...result, items: enrichHistoryInterval(result.items, decision.state.items) }
        void collectHistoryInterval(canonicalPage, async page => {
          const response = await matelinkApi.getCharges(nextCar.id, page, PAGE_SIZE, nextCar)
          return { ...response, items: enrichHistoryInterval(response.items, itemsRef.current) }
        }, intervalTicket.isCurrent).then(snapshot => {
          if (snapshot && intervalTicket.isCurrent()) setInterval({ ...snapshot, items: snapshot.items.map(row => itemsRef.current.find(item => sameHistoryIdentity(item, row)) ?? row) })
        })
      }
    } catch (reason) {
      if (!ticket.isCurrent()) return
      setError(errorMessage(reason))
      if (append && itemsRef.current.length > 0) setMeta(existing => existing ? { ...existing, hasMore: true } : existing)
    } finally {
      if (ticket.isCurrent()) {
        setLoading(false)
        if (append) {
          loadingMoreRef.current = false
          setLoadingMore(false)
        }
      }
    }
  }

  const loadCurrentCharge = async (nextCar: Car) => {
    if (!verifiedCarRef.current || nextCar.remoteVerified === false) return
    const ticket = scopeGate.current.begin('current')
    try {
      const result = await matelinkApi.getCurrentCharge(nextCar.id, nextCar)
      if (ticket.isCurrent()) {
        setCurrent(result?.isCharging !== false && result?.currentVehicleIdentityVerified !== false ? result : null)
        if (result && result.isCharging !== false && result.currentVehicleIdentityVerified === false) setError('当前充电的车辆身份未确认，请刷新重试。')
      }
    } catch (reason) {
      if (ticket.isCurrent()) setError(previous => previous ?? errorMessage(reason))
    }
  }

  const stopCurrentPolling = () => {
    if (pollTimer.current) { clearTimeout(pollTimer.current); pollTimer.current = null }
    pollCarRef.current = null
    setChargeStarting(false); pollStartingRef.current = false
  }
  const pollCurrentTick = async () => {
    const nextCar = pollCarRef.current
    if (!nextCar) return
    let ticket: { isCurrent(): boolean }
    try { ticket = scopeGate.current.begin('current') } catch { stopCurrentPolling(); return }
    try {
      const [chargeResult, statusResult] = await Promise.allSettled([
        matelinkApi.getCurrentCharge(nextCar.id, nextCar),
        matelinkApi.getCarSnapshot(nextCar.id, nextCar),
      ])
      if (!ticket.isCurrent() || pollCarRef.current !== nextCar) return
      const charge = chargeResult.status === 'fulfilled' ? chargeResult.value : null
      const status = statusResult.status === 'fulfilled' ? statusResult.value : null
      if (chargeResult.status === 'fulfilled') {
        setCurrent(charge && charge.isCharging !== false && charge.currentVehicleIdentityVerified !== false ? charge : null)
        if (charge && charge.isCharging !== false && charge.currentVehicleIdentityVerified === false) setError('当前充电的车辆身份未确认，请刷新重试。')
      } else {
        setCurrent(null)
        setError(previous => previous ?? errorMessage(chargeResult.reason))
      }
      // App: "car charging but TeslaMate has not materialized the session yet".
      // Also handles starting and charging_started provider states
      const state = status?.chargingState?.toLowerCase()
      const starting = chargeResult.status === 'fulfilled' && (charge == null || charge.isCharging === false) &&
        (state === 'charging' || state === 'starting' || state === 'charging_started')
      setChargeStarting(starting); pollStartingRef.current = starting
    } catch { /* App keeps the last data on screen and keeps polling. */ }
    if (pollTimer.current) clearTimeout(pollTimer.current)
    pollTimer.current = setTimeout(() => { void pollCurrentTick() },
      pollStartingRef.current ? CHARGE_STARTING_REFRESH_INTERVAL_MS : CURRENT_CHARGE_REFRESH_INTERVAL_MS)
  }
  const startCurrentPolling = (nextCar: Car | null) => {
    if (pollTimer.current) { clearTimeout(pollTimer.current); pollTimer.current = null }
    pollCarRef.current = nextCar
    if (nextCar) void pollCurrentTick()
  }

  const load = async () => {
    invalidatePage()
    verifiedCarRef.current = false
    setOffline(false)
    setCars([])
    setCar(null)
    resetHistoryState()
    setCurrent(null)
    const pageEpoch = lifecycleEpoch.current
    const sessionGeneration = getApiSessionGeneration()
    const session = readAppSession(Taro)
    if (!session?.userId) {
      displayOwner.current = null
      invalidatePage()
      setCars([])
      setCar(null)
      setCurrent(null)
      resetHistoryState()
      setError('请先在“更多”中登录特斯拉账号')
      return
    }
    displayOwner.current = currentOwner()
    setError(null)
    setLoading(true)
    try {
      const nextCars = await matelinkApi.getCars()
      const currentSession = readAppSession(Taro)
      if (pageEpoch !== lifecycleEpoch.current || sessionGeneration !== getApiSessionGeneration() || currentSession?.userId !== session.userId) return
      const stored = Taro.getStorageSync(carSelectionStorageKey(session.userId))
      const nextCar = nextCars.find(item => item.stableId === String(stored)) ?? nextCars[0] ?? null
      setCars(nextCars)
      verifiedCarRef.current = true
      if (!nextCar) {
        scopeGate.current.invalidate()
        setCar(null)
        setCurrent(null)
        resetHistoryState()
        setError('当前账号尚未绑定车辆')
        setLoading(false)
        return
      }
      scopeGate.current.bind({
        accountId: session.userId,
        stableVehicleId: nextCar.stableId,
        apiOrigin: getApiOrigin(),
        sessionGeneration,
      })
      try { Taro.setStorageSync(carSelectionStorageKey(session.userId), nextCar.stableId) } catch { /* selection remains usable online */ }
      setCar(nextCar)
      loadingMoreRef.current = false
      await Promise.all([loadHistory(nextCar), loadCurrentCharge(nextCar)])
      startCurrentPolling(nextCar)
    } catch (reason) {
      if (pageEpoch !== lifecycleEpoch.current || sessionGeneration !== getApiSessionGeneration() || readAppSession(Taro)?.userId !== session.userId) return
      verifiedCarRef.current = false
      setLoading(false)
      setOffline(true)
      try {
        const cachedCars = readCarsCache(Taro, session.userId, getApiOrigin())
        const stored = Taro.getStorageSync(carSelectionStorageKey(session.userId))
        const cachedCar = cachedCars.find(item => item.stableId === String(stored)) ?? cachedCars[0] ?? null
        setCars(cachedCars)
        setCar(cachedCar)
        if (cachedCar) {
          scopeGate.current.bind({ accountId: session.userId, stableVehicleId: cachedCar.stableId, apiOrigin: getApiOrigin(), sessionGeneration })
          restoreCached(cachedCar)
        }
      } catch { resetHistoryState() }
      setError('车辆暂时无法验证；可查看本机历史，刷新验证后才能读取在线详情或更多记录。')
    }
  }

  const showPage = async () => {
    pageVisible.current = true
    if (displayOwner.current && displayOwner.current !== currentOwner()) {
      invalidatePage(); stopCurrentPolling(); displayOwner.current = null
      setCars([]); setCar(null); setCurrent(null); resetHistoryState(); setError(null)
    }
    const showEpoch = ++lifecycleEpoch.current
    setLoading(true)
    await ensureWechatSession()
    if (!pageVisible.current || showEpoch !== lifecycleEpoch.current) return
    const context = detailContext.current
    const sameOwner = () => {
      try {
        const session = readAppSession(Taro), stored = session?.userId ? Taro.getStorageSync(carSelectionStorageKey(session.userId)) : null
        return Boolean(context && session?.userId === context.user && getApiOrigin() === context.origin &&
          getApiSessionGeneration() === context.generation && (!stored || String(stored) === context.uid))
      } catch { return false }
    }
    if (!context || !sameOwner()) { void setSelected(null); await load(); return }
    const baseline = detailCacheBaseline.current
    const reconcileCached = () => {
      const cached = readHistoryCache<Charge>(Taro, historyCacheKey('charges', context.user, context.uid, context.origin))
        .filter(row => baseline !== null && baseline.get(historyRowKey(row)) !== JSON.stringify(row))
      const replay = (initial: { pages: HistoryPage<Charge>[]; items: Charge[]; page: number }) => reconcileHistoryCache(initial, cached)
      const state = replay({ pages: pagesRef.current, items: itemsRef.current, page: pageRef.current })
      itemsRef.current = state.items; pagesRef.current = state.pages
      setItems(state.items)
      setInterval(snapshot => snapshot ? { ...snapshot, items: replay({ pages: [], items: snapshot.items, page: 0 }).items } : snapshot)
      const row = state.items.find(item => sameHistoryIdentity(item, context.row))
      if (row && selectedEvidence?.charge && ['id', 'source', 'qualityState', 'startDate', 'endDate', 'chargerPowerKw', 'startBatteryLevel', 'endBatteryLevel', 'energy'].some(key =>
        JSON.stringify((row as unknown as Record<string, unknown>)[key] ?? null) !== JSON.stringify((selectedEvidence.charge as unknown as Record<string, unknown>)[key] ?? null))) setSelectedEvidence(null)
      return row
    }
    invalidatePage()
    const epoch = lifecycleEpoch.current
    setLoading(true)
    try {
      const nextCars = await matelinkApi.getCars()
      if (epoch !== lifecycleEpoch.current || context !== detailContext.current) return
      const nextCar = nextCars.find(item => item.stableId === context.uid)
      if (!sameOwner() || !nextCar) { void setSelected(null); await load(); return }
      scopeGate.current.bind({ accountId: context.user, stableVehicleId: nextCar.stableId, apiOrigin: context.origin, sessionGeneration: context.generation })
      setCars(nextCars); setCar(nextCar); verifiedCarRef.current = true; setOffline(false)
      const row = reconcileCached()
      if (!row || isHistoryQuarantined(row)) { void setSelected(null); return }
      void setSelected(row)
    } catch {
      if (epoch !== lifecycleEpoch.current) return
      if (!sameOwner()) { void setSelected(null); await load(); return }
      verifiedCarRef.current = false; setOffline(true)
      scopeGate.current.bind({ accountId: context.user, stableVehicleId: context.uid, apiOrigin: context.origin, sessionGeneration: context.generation })
      const row = reconcileCached()
      if (!row || isHistoryQuarantined(row)) { void setSelected(null); return }
      void setSelected(row)
      setError('在线车辆暂时无法重新验证；当前为本机已读取详情。')
    } finally { if (epoch === lifecycleEpoch.current) setLoading(false) }
  }
  useSessionRecoveryReload(() => { if (pageVisible.current) void showPage() })
  useDidShow(() => { void showPage(); if (car) startCurrentPolling(car) })
  useDidHide(() => { stopCurrentPolling()
    const context = detailContext.current
    detailCacheBaseline.current = null
    if (context) {
      try {
        const key = historyCacheKey('charges', context.user, context.uid, context.origin)
        Taro.getStorageSync(key) // An unreadable baseline cannot prove a later cache record is newer.
        detailCacheBaseline.current = new Map(readHistoryCache<Charge>(Taro, key).map(row => [historyRowKey(row), JSON.stringify(row)]))
      } catch { /* Fail closed: keep current memory rather than replay an unproven disk version. */ }
    }
    pageVisible.current = false; invalidatePage(); setSelectedState(null); void nativeTabBar(false)
  })
  useUnload(() => { pageVisible.current = false; detailContext.current = null; detailCacheBaseline.current = null; invalidatePage(); stopCurrentPolling(); void nativeTabBar(false) })
  usePageScroll(event => { if (pageVisible.current && !detailContext.current) listScrollTop.current = event.scrollTop })

  const selectCar = (event: { detail: { value: number | string } }) => {
    const next = cars[Number(event.detail.value)]
    const session = readAppSession(Taro)
    if (!next || !session?.userId) return
    invalidatePage()
    try {
      scopeGate.current.bind({ accountId: session.userId, stableVehicleId: next.stableId, apiOrigin: getApiOrigin(), sessionGeneration: getApiSessionGeneration() })
    } catch (reason) {
      setError(errorMessage(reason))
      return
    }
    try { Taro.setStorageSync(carSelectionStorageKey(session.userId), next.stableId) } catch { /* optional persistence */ }
    setCar(next)
    setCurrent(null)
    resetHistoryState()
    setError(null)
    if (!verifiedCarRef.current || next.remoteVerified === false) { restoreCached(next); stopCurrentPolling(); return }
    void Promise.all([loadHistory(next), loadCurrentCharge(next)])
    startCurrentPolling(next)
  }

  const loadMore = () => {
    if (!verifiedCarRef.current || !car || loadingMore || loadingMoreRef.current || !pageCanContinue({ items, meta: meta || emptyMeta })) return
    void loadHistory(car, true)
  }

  const openDetail = async (item: Charge) => {
    if (!car) return
    const canonical = itemsRef.current.find(row => sameHistoryIdentity(row, item))
    if (!canonical || canonical.id !== item.id || isHistoryQuarantined(canonical) || isHistoryQuarantined(item)) return
    let ticket: { isCurrent(): boolean }
    try { ticket = scopeGate.current.begin('detail') } catch { return }
    if (ticket.isCurrent()) { setSelected(canonical); setSelectedEvidence(null); setMapFailed(false); setCurveSampleIndex(0) }
    try {
      if (!verifiedCarRef.current || car.remoteVerified === false) return
      const evidence = await analysisApi.getChargeEvidence(car, item.id, { isCurrent: ticket.isCurrent })
      const detail = evidence.charge
      if (ticket.isCurrent() && detail) {
        if (!sameHistoryIdentity(canonical, detail)) { setError('充电记录来源信息不一致，请刷新后重试。'); return }
        const state = acceptHistoryDetail({ pages: pagesRef.current, items: itemsRef.current, page: pageRef.current }, canonical, detail)
        if (state.items === itemsRef.current) return
        setSelectedEvidence(evidence)
        itemsRef.current = state.items
        pagesRef.current = state.pages
        setItems(state.items)
        setInterval(snapshot => snapshot ? { ...snapshot, items: snapshot.items.map(row => state.items.find(item => sameHistoryIdentity(item, row)) ?? row) } : snapshot)
        const selectedRow = state.items.find(row => sameHistoryIdentity(row, detail))
        setSelected(selectedRow && !isHistoryQuarantined(selectedRow) ? selectedRow : null)
        const session = readAppSession(Taro)
        if (session?.userId) setCacheWarning(!writeHistoryCache(Taro, historyCacheKey('charges', session.userId, car.stableId, getApiOrigin()), state.items))
      }
    } catch (reason) {
      if (ticket.isCurrent()) setError(errorMessage(reason))
    }
  }

  const session = readAppSession(Taro)
  const costScope = car && session?.userId ? { userId: session.userId, origin: getApiOrigin(), stableId: car.stableId } : null
  const costRows = (rows: readonly Charge[]): CostedCharge[] => costScope ? overlayChargeCosts(Taro, costScope, rows, chargeEstimatePrice(Taro, costScope)) : []
  const selectedView = selected ? costRows([selected])[0] : null
  const statisticsRows = (rows: readonly Charge[]) => costRows(appChargeListRows(appChargeStatisticsRows(rows), showShortCharges))
  const matchesSecondaryFilters = (row: CostedCharge) => {
    const mode = chargeMode(row)
    if (chargeTypeFilter !== 'all' && mode !== chargeTypeFilter) return false
    if (costFilter === 'has' && row.cost == null) return false
    if (costFilter === 'none' && row.cost != null) return false
    return !selectedLocation || displayAddress(row.address) === selectedLocation
  }
  const intervalView = interval ? { ...interval, items: statisticsRows(interval.items).filter(matchesSecondaryFilters) } : null
  const costState = selectedView
  const acEfficiency = selected ? qualifiedAcEnergyBalance(selected)?.efficiencyPercent ?? null : null
  const trustedItems = costRows(appChargeListRows(filterHistory(items, emptyHistoryQuery()), showShortCharges))
  const visibleItems = filterHistory(trustedItems.filter(matchesSecondaryFilters), query)
  const chartSource = (intervalView?.items ?? statisticsRows(items)).filter(matchesSecondaryFilters)
  const chartItems = filterHistory(chartSource, query)
  const unclassifiedCount = filterHistory(chartSource, { ...query, startDate: '', endDate: '' })
    .filter(row => typeof row.startDate !== 'string' || civilDay(row.startDate) === null).length
  const chartRows = chartItems.flatMap(row => {
    const day = typeof row.startDate === 'string' ? civilDay(row.startDate) : null
    return day ? [{ row, day }] : []
  })
  const chartCurrencies = [...new Set(chartRows.map(({ row }) => row).filter(row => row.cost != null && Number.isFinite(row.cost) && row.cost >= 0).map(row => row.currencyCode ?? row.currency).filter((code): code is string => Boolean(code)))]
  const chartCurrency = chartCurrencies.length === 1 ? chartCurrencies[0] : null
  const locationOptions = ['全部地点', ...new Set(items.map(item => displayAddress(item.address)).filter(value => value !== '位置未知'))]
  const months = new Map<string, { label: string; energy: number; energyKnown: number; cost: number; costKnown: number; costEstimated: boolean; count: number }>()
  for (const { row, day } of chartRows) {
    const month = months.get(day) ?? { label: day, energy: 0, energyKnown: 0, cost: 0, costKnown: 0, costEstimated: false, count: 0 }
    month.count++
    if (row.energyAddedKwh != null && Number.isFinite(row.energyAddedKwh) && row.energyAddedKwh >= 0) { month.energy += row.energyAddedKwh; month.energyKnown++ }
    if (row.cost != null && Number.isFinite(row.cost) && row.cost >= 0 && chartCurrency != null && (row.currencyCode ?? row.currency) === chartCurrency) { month.cost += row.cost; month.costKnown++; month.costEstimated ||= row.costProvenance === 'estimate' }
    months.set(day, month)
  }
  const chartMonths = [...months.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([, month]) => month)
  const chartUnit = chartKind === 'energy' ? '度' : chartKind === 'count' ? '次' : chartCurrency === 'CNY' ? '¥' : chartCurrency || '币种待确认'
  // App ChargesViewModel granularity: <=30d day, <=90d week, else month
  const chartGranularity = (() => {
    if (query.startDate && query.endDate) {
      const days = Math.round((new Date(`${query.endDate}T00:00:00`).getTime() - new Date(`${query.startDate}T00:00:00`).getTime()) / 86400000)
      if (Number.isFinite(days)) return days <= 30 ? 'day' : days <= 90 ? 'week' : 'month'
    }
    if (query.relativeDays != null) return query.relativeDays <= 30 ? 'day' : query.relativeDays <= 90 ? 'week' : 'month'
    return 'month'
  })()
  const chartTitle = `按${chartGranularity === 'day' ? '日' : chartGranularity === 'week' ? '周' : '月'}${chartKind === 'energy' ? '充电量' : chartKind === 'cost' ? '费用' : '充电次数'}`
  const chartBuckets = (() => {
    const map = new Map<string, number | null>()
    for (const { row, day } of chartRows) {
      let key = day
      if (chartGranularity === 'week') {
        const monday = new Date(`${day}T00:00:00`); monday.setDate(monday.getDate() - ((monday.getDay() + 6) % 7))
        const pad = (n: number) => String(n).padStart(2, '0')
        key = `${monday.getFullYear()}-${pad(monday.getMonth() + 1)}-${pad(monday.getDate())}`
      } else if (chartGranularity === 'month') key = day.slice(0, 7)
      if (chartKind === 'count') { map.set(key, (map.get(key) ?? 0) + 1); continue }
      const raw = chartKind === 'energy' ? row.energyAddedKwh : row.cost != null && chartCurrency != null && (row.currencyCode ?? row.currency) === chartCurrency ? row.cost : null
      if (raw == null || !Number.isFinite(raw)) { map.set(key, null); continue }
      map.set(key, (map.get(key) ?? 0) + raw)
    }
    return [...map.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([key, value]) => {
      let label = key
      if (chartGranularity === 'day') label = `${Number(key.slice(5, 7))}/${Number(key.slice(8, 10))}`
      else if (chartGranularity === 'week') {
        const end = new Date(`${key}T00:00:00`); end.setDate(end.getDate() + 6)
        label = `${Number(key.slice(5, 7))}/${Number(key.slice(8, 10))}–${end.getMonth() + 1}/${end.getDate()}`
      } else label = `${Number(key.slice(5, 7))}月`
      return { key, label, value }
    })
  })()
  const chartMax = Math.max(0, ...chartBuckets.map(bucket => bucket.value ?? 0))
  const dailyCells = calendarHistoryGroups(chartMonths.map(day => ({ key: day.label, label: day.label, items: [day] })), query.startDate, query.endDate)
    .filter(group => group.key !== 'unknown').sort((a, b) => a.key.localeCompare(b.key)).map(group => {
      const day = group.items[0]
      const known = day ? chartKind === 'energy' ? day.energyKnown : chartKind === 'cost' ? day.costKnown : day.count : 0
      const value = day ? chartKind === 'energy' ? day.energy : chartKind === 'cost' ? day.cost : day.count : 0
      const estimated = Boolean(day && chartKind === 'cost' && day.costEstimated)
      return { key: group.key, label: group.key.slice(8), value: known ? value : null,
        precision: chartKind === 'count' ? 0 : chartKind === 'cost' ? 2 : 1,
        approximate: Boolean(day && (estimated || known < day.count)),
        display: day ? `${known ? `${estimated ? '含估算 ' : ''}${value.toFixed(chartKind === 'count' ? 0 : 2)} ${chartUnit}` : '未知'} · 已知 ${known} / ${day.count} 条` : '尚未读到该日记录，不代表未充电' }
    })
  const detailCoordinate = selectedEvidence?.charge ? mapCoordinate(selectedEvidence.charge.latitude, selectedEvidence.charge.longitude) : null
  const sampleStats = (key: 'powerKw' | 'voltageV' | 'currentA' | 'temperatureC') => {
    const values = (selectedEvidence?.points ?? []).map(point => point[key]).filter((value): value is number => value != null && Number.isFinite(value))
    return { max: values.length ? Math.max(...values) : null, min: values.length ? Math.min(...values) : null,
      average: values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : null, count: values.length }
  }
  const powerStats = sampleStats('powerKw'), voltageStats = sampleStats('voltageV'), currentStats = sampleStats('currentA'), temperatureStats = sampleStats('temperatureC')
  const navGeo = getAppNavGeometry()
  return (
    <View className={`page history-page charges-page ${appearance.className}`}>
      <View className="history-page-header" style={{ paddingTop: `${navGeo.navBarTop}px`, paddingRight: `${navGeo.capsuleRightPadding}px` }}>
        <Text className="title">{copy('charges.title')}</Text>
        {cacheWarning ? <Text className="error">在线数据已读到，但未保存到本机。</Text> : null}
      </View>

      {MOCK_FIXTURE_ENABLED ? <View className="card fixture-data-notice" style={{ backgroundColor: '#fff7ed', borderColor: '#c2410c' }}><Text className="section-title" style={{ color: '#9a3412', marginBottom: 0 }}>合成测试数据，未连接真实车辆</Text></View> : null}
      {car ? <View className="history-controls">

        <ScrollView scrollX className="history-filter-scroll"><HistoryDateChips kind="charges" query={query} onChange={updateQuery} /></ScrollView>
        <View className="history-charge-filter-row">
          <Picker mode="selector" range={['全部', '交流', '直流']} value={chargeTypeFilter === 'all' ? 0 : chargeTypeFilter === 'ac' ? 1 : 2} onChange={event => setChargeTypeFilter((['all', 'ac', 'dc'] as const)[Number(event.detail.value)] || 'all')}>
            <Button className="history-filter-button"><VisualIcon name="energy" />{chargeModeLabel(chargeTypeFilter)}</Button>
          </Picker>
          <Picker mode="selector" range={['全部', '有费用', '无费用']} value={costFilter === 'all' ? 0 : costFilter === 'has' ? 1 : 2} onChange={event => setCostFilter((['all', 'has', 'none'] as const)[Number(event.detail.value)] || 'all')}>
            <Button className="history-filter-button"><VisualIcon name="money" />{costFilter === 'all' ? '全部' : costFilter === 'has' ? '有费用' : '无费用'}</Button>
          </Picker>
          <Picker mode="selector" range={locationOptions} value={Math.max(0, locationOptions.indexOf(selectedLocation || '全部地点'))} onChange={event => setSelectedLocation(Number(event.detail.value) === 0 ? null : locationOptions[Number(event.detail.value)] || null)}>
            <Button className="history-filter-button"><VisualIcon name="map" />{selectedLocation || '全部地点'}</Button>
          </Picker>
        </View>
        {offline ? <Text className="muted">{copy('history.localCache')}</Text> : null}
      </View> : null}
      {items.some(isHistoryIdentityAmbiguous) ? <Text className="muted history-identity-note">来源身份未确认的历史记录已保留，但未计入当前展示或统计</Text> : null}
      {notice ? <View className="card history-notice">
        <Text className="muted">前台发现一条新的已完成充电，仅本次打开时检查。</Text>
        <Button className="button" onClick={() => {
          const matches = itemsRef.current.filter(item => item.id === notice.id && item.sessionId === notice.sessionId && item.source === notice.source)
          setNotices(current => current.slice(1))
          if (matches.length === 1) void openDetail(matches[0])
        }}>查看新充电</Button>
        <Button className="button" onClick={() => setNotices([])}>关闭提示</Button>
      </View> : null}
      {car && !loading ? <HistoryIntervalSummary interval={intervalView} query={query} kind="charges" /> : null}
      {car && chartRows.length > 0 ? <View className="card charge-month-chart">
        {/* App ChargesScreen chart: 按日充电量 / 按日费用 / 按日充电次数 */}
        <Text className="section-title">{chartTitle}</Text>
        <Text className="muted">单位： {chartUnit}</Text>
        {!(intervalView?.complete ?? true) ? <Text className="muted">部分记录缺少数据</Text> : null}
        <View className="charge-chart-tabs">
          {([['energy', '充电量'], ['cost', '费用'], ['count', '次数']] as const).map(([kind, label]) =>
            <Button key={kind} className={`charge-chart-tab ${chartKind === kind ? 'charge-chart-tab-active' : ''}`} onClick={() => setChartKind(kind)}>{label}</Button>)}
        </View>
        {chartBuckets.length ? <View className="app-drive-month-scroll"><View className="app-drive-month-bars">{chartBuckets.map(({ key, label, value }) =>
          <View className="app-drive-month-column" key={key} ariaLabel={`${label} ${value == null ? '未知' : `${displayNumber(value, chartKind === 'count' ? 0 : chartKind === 'cost' ? 2 : 1)} ${chartUnit}`}`}>
            <Text className="app-drive-month-value">{value == null ? '—' : displayNumber(value, chartKind === 'count' ? 0 : 1)}</Text>
            <View className="app-drive-month-track"><View className="app-drive-month-bar" style={{ height: `${Math.max(0, value ?? 0) / (chartMax || 1) * 100}%` }} /></View>
            <Text className="app-drive-month-label">{label}</Text>
          </View>)}</View></View> : <Text className="muted">暂无可按日期归类的充电记录。</Text>}
      </View> : null}
      {noticeLimited ? <Text className="muted history-notice-limit">本机前台提示记录不可写或已达上限，后续提示暂停；历史仍可查看</Text> : null}

      {!readAppSession(Taro) && loading ? <LoadingIndicator label="正在恢复会话…" /> : !readAppSession(Taro) ? (
        <View className="card empty-state"><VisualIcon name="account" /><Text className="section-title">等待特斯拉账号登录</Text><Text className="muted">请先在“更多”中登录特斯拉账号。</Text><Button className="button" onClick={() => Taro.switchTab({ url: '/pages/me/index' })}>前往登录与授权</Button></View>
      ) : trustedItems.length === 0 && !loading ? (
        <View className="card empty-state"><VisualIcon name="energy" /><Text className="section-title">未找到充电记录</Text><Text className="muted">请切换时间范围，或选择“全部”。</Text>
          {(query.startDate || query.endDate || chargeTypeFilter !== 'all' || costFilter !== 'all' || selectedLocation) ? <Button className="button" onClick={() => { setChargeTypeFilter('all'); setCostFilter('all'); setSelectedLocation(null); updateQuery(emptyHistoryQuery()) }}>{copy('common.clearFilters')}</Button> : null}
          {!offline && meta?.hasMore ? <Button className="button" loading={loadingMore} onClick={loadMore}>{copy('common.loadMore')}</Button> : null}
        </View>
      ) : (
        <View className="history-list charge-history-list">
          <Text className="section-title charge-history-heading">充电历史</Text>
          {loading ? <LoadingIndicator label="正在读取充电记录…" compact /> : null}
          {visibleItems.length === 0 && trustedItems.length > 0 ? <Text className="muted">{copy('history.noMatchedCharges')}</Text> : null}
          {historyDayGroups(visibleItems, item => item.startDate).map(group => (
            <View key={group.key} className="charge-history-day">
              <Text className="charge-history-day-title">{dayCaption(group.key)}</Text>
              {group.items.map(item => (
            <View key={historyRowKey(item)} className="history-record" onClick={() => { void openDetail(item) }}>
            <View className="charge-row">
              {(() => {
                // App ChargeRow: circle bolt + address + editorial date · Xm +
                // 交流/直流 · ¥X.XX/度; right column energy ≈X.X 度 / SOC / cost pill.
                const mode = chargeMode(item)
                const durationMinutes = item.durationMinutes != null && Number.isFinite(item.durationMinutes) && item.durationMinutes >= 0 ? Math.round(item.durationMinutes) : null
                const unitPrice = item.cost != null && item.cost >= 0 && item.energyAddedKwh != null && item.energyAddedKwh > 0.05 ? item.cost / item.energyAddedKwh : null
                const currency = item.currencyCode === 'CNY' ? '¥' : item.currencyCode || item.currency || ''
                const energy = oneDecimal(item.energyAddedKwh)
                const energyPrefix = item.partialEstimatedCost != null || inputNumber(item) != null ? '≈' : '+'
                const soc = formatSocRange(item.startBatteryLevel, item.endBatteryLevel, '')
                const cost = costLabel(item)
                const costText = (() => {
                  if (cost.value == null) return null
                  if (typeof cost.value === 'string') {
                    // A foreign-currency estimate keeps its unit (≈20.00 EUR)
                    return cost.unit && cost.unit !== 'CNY' && cost.unit !== '币种未知' ? `${cost.value} ${cost.unit}` : cost.value
                  }
                  if (cost.unit === 'CNY') return `¥${Number(cost.value).toFixed(2)}`
                  // App hides the cost when the currency is unresolved
                  if (!cost.unit || cost.unit === '币种未知') return null
                  return `${cost.unit} ${Number(cost.value).toFixed(2)}`
                })()
                const costTone = item.costProvenance === 'free' ? 'free' : item.partialEstimatedCost != null || item.costProvenance === 'estimate' ? 'estimate' : 'known'
                return <>
                  <View className={`charge-row-icon charge-row-icon-${mode}`}><VisualIcon name="energy" /></View>
                  <View className="charge-row-main">
                    <Text className="charge-row-address">{displayAddress(item.address)}</Text>
                    <View className="charge-row-meta"><Text>{historyEditorialDate(item.startDate)}</Text>{durationMinutes != null ? <Text> · {durationMinutes}m</Text> : null}</View>
                    <View className="charge-row-type"><Text className={`charge-row-mode charge-row-mode-${mode}`}>{modeLabel(item)}</Text>{unitPrice != null && currency ? <Text> · {currency}{unitPrice.toFixed(2)}/度</Text> : null}</View>
                  </View>
                  <View className="charge-row-side">
                    {energy != null ? <Text className="charge-row-energy">{energyPrefix}{energy} 度</Text> : null}
                    {soc ? <Text className="charge-row-soc">{soc}</Text> : null}
                    {costText != null ? <Text className={`charge-row-cost charge-row-cost-${costTone}`}>{costText}</Text> : null}
                  </View>
                  <Text className="history-record-chevron">›</Text>
                </>
              })()}
            </View>
            </View>
              ))}
            </View>
          ))}
          {!offline && meta?.hasMore ? <Button className="button" loading={loadingMore} onClick={loadMore}>{copy('common.loadMore')}</Button> : null}
        </View>
      )}

      {selected && selectedView && !isHistoryQuarantined(selected) ? (
        <ScrollView className="history-detail-overlay" scrollY scrollTop={detailScrollTop.current} onScroll={event => { detailScrollTop.current = event.detail.scrollTop }} style={{ position: 'fixed', top: 0, left: 0, width: '100%', height: '100%' }} ariaLabel="充电详情">
        <View className="history-detail-surface">
          <View className="history-detail-heading" style={{ paddingTop: `${navGeo.navBarTop}px`, paddingRight: `${navGeo.capsuleRightPadding}px`, minHeight: `${navGeo.headerTotalHeight}px` }}><Button className="detail-back" onClick={closeDetail}>‹ 返回</Button><Text className="section-title">充电详情</Text></View>
          {MOCK_FIXTURE_ENABLED ? <View className="card fixture-data-notice" style={{ backgroundColor: '#fff7ed', borderColor: '#c2410c' }}><Text className="section-title" style={{ color: '#9a3412', marginBottom: 0 }}>合成测试数据，未连接真实车辆</Text></View> : null}
          {detailCoordinate ? <View className="card charge-detail-map-card"><Text className="section-title">充电地点</Text>
            {mapFailed ? <Text className="muted">地图暂时无法载入</Text> : <TaroMap className="charge-detail-map" latitude={detailCoordinate.latitude} longitude={detailCoordinate.longitude} scale={15} showLocation={false} markers={[{ id: 1, ...detailCoordinate, iconPath: '/assets/tabs/energy-selected.png', width: 24, height: 24 }]} onError={() => setMapFailed(true)} />}
            <Text className="muted">仅展示本次在线详情返回的有效位置。</Text></View> : null}
          <View className="card charge-detail-intro"><Text className="section-title">充电概览</Text><Text className="charge-detail-address">{displayAddress(selected.address)}</Text>
            <Text className="muted">开始 {historyDetailDateTime(selected.startDate)} · 结束 {historyDetailDateTime(selected.endDate)} · {modeLabel(selected)}</Text>
            <View className="history-record-metrics charge-overview-metrics">
              <MetricValue label="电池充入" value={selected.energyAddedKwh} unit="kWh" icon="battery" />
              <MetricValue label="SOC" {...formatSocRangeMetric(selected.startBatteryLevel, selected.endBatteryLevel, "暂无数据")} icon="battery" />
              <MetricValue label="充电费用" {...costLabel(selectedView)} icon="account" />
              <MetricValue label="报告功率" value={selectedEvidence?.summary.chargerPowerKw ?? selected.chargerPowerKw} unit="kW" icon="energy" />
            </View>
            <Text className="muted">费用来源：{costState?.costProvenance === 'manual' ? '本机手动设置' : costState?.costProvenance === 'free' ? '免费（免费超级充电 · DC）' : costState?.costProvenance === 'actual' ? selectedView.cost === 0 ? '服务端报告0费用' : '服务端账单报告' : costState?.costProvenance === 'estimate' ? `按 ${estimateBasis(costState)} 与${costState.partialEstimatedCost != null ? "已采集部分电量" : "合格电池充入量"}估算，不是实际账单` : costState?.reportedCost === 0 && selected.source === 'teslamate_archive' ? 'TeslaMate 原始0表示未填，当前无合格估算' : '未知'}</Text>
          </View>
          <Text className="section-title">电量</Text>
          <View className="telemetry-metric-strip metric-grid">
            <MetricValue label={inputLabel(selected)} value={inputNumber(selected)} unit="kWh" icon="energy" />
            <MetricValue label="AC输入效率" value={acEfficiency} unit="%" />
          </View>
          <Text className="section-title">电池</Text>
          <View className="telemetry-metric-strip metric-grid">
            <MetricValue label="起始SOC" value={selected.startBatteryLevel} unit="%" /><MetricValue label="结束SOC" value={selected.endBatteryLevel} unit="%" />
            <MetricValue label="SOC变化" value={selected.startBatteryLevel != null && selected.endBatteryLevel != null ? selected.endBatteryLevel - selected.startBatteryLevel : null} unit="%" /><MetricValue label="充电时长" value={selected.durationMinutes} unit="分钟" />
          </View>
          <Text className="section-title">功率</Text>
          <View className="telemetry-metric-strip metric-grid">
            <MetricValue label="样本最高功率" value={powerStats.max} unit="kW" /><MetricValue label="样本最低功率" value={powerStats.min} unit="kW" /><MetricValue label="样本平均功率" value={powerStats.average} unit="kW" />
          </View>
          {(voltageStats.count > 0 || currentStats.count > 0 || selectedEvidence?.summary.chargerVoltageV != null || selectedEvidence?.summary.currentA != null) ? <><Text className="section-title">电压与电流</Text><View className="telemetry-metric-strip metric-grid">
            <MetricValue label="报告电压" value={selectedEvidence?.summary.chargerVoltageV ?? null} unit="V" /><MetricValue label="报告电流" value={selectedEvidence?.summary.currentA ?? null} unit="A" />
            <MetricValue label="样本最高电压" value={voltageStats.max} unit="V" /><MetricValue label="样本最低电压" value={voltageStats.min} unit="V" /><MetricValue label="样本平均电压" value={voltageStats.average} unit="V" />
            <MetricValue label="样本最高电流" value={currentStats.max} unit="A" /><MetricValue label="样本最低电流" value={currentStats.min} unit="A" /><MetricValue label="样本平均电流" value={currentStats.average} unit="A" />
          </View></> : null}
          <Text className="section-title">温度</Text>
          <View className="telemetry-metric-strip metric-grid"><MetricValue label="报告环境温度" value={selectedEvidence?.summary.outsideTempC ?? null} unit="°C" /><MetricValue label="样本最高温度" value={temperatureStats.max} unit="°C" /><MetricValue label="样本最低温度" value={temperatureStats.min} unit="°C" /><MetricValue label="样本平均温度" value={temperatureStats.average} unit="°C" /></View>
          <Text className="muted">功率、电压、电流与环境温度按已有记录显示，缺失数据保持未知。</Text>
          {selectedEvidence ? <>
            <EvidenceCurve id="charge-detail-soc" provenanceOf={point => chargeCurveProvenance(point, 'batteryLevel')} forceDisconnected={selectedEvidence.hasUnplaceableSamples} title="充电电量变化" unit="%" points={selectedEvidence.points} valueOf={point => point.batteryLevel} selected={curveSampleIndex} onSelect={setCurveSampleIndex} isCurrent={() => selectedIdRef.current === selected.id} />
            <EvidenceCurve id="charge-detail-power" provenanceOf={point => chargeCurveProvenance(point, 'powerKw')} forceDisconnected={selectedEvidence.hasUnplaceableSamples} title="充电功率" unit="kW" points={selectedEvidence.points} valueOf={point => point.powerKw} selected={curveSampleIndex} onSelect={setCurveSampleIndex} isCurrent={() => selectedIdRef.current === selected.id} />
            <EvidenceCurve id="charge-detail-voltage" provenanceOf={point => chargeCurveProvenance(point, 'voltageV')} forceDisconnected={selectedEvidence.hasUnplaceableSamples} title="充电电压" unit="V" points={selectedEvidence.points} valueOf={point => point.voltageV} selected={curveSampleIndex} onSelect={setCurveSampleIndex} isCurrent={() => selectedIdRef.current === selected.id} />
            <EvidenceCurve id="charge-detail-current" provenanceOf={point => chargeCurveProvenance(point, 'currentA')} forceDisconnected={selectedEvidence.hasUnplaceableSamples} title="充电电流" unit="A" points={selectedEvidence.points} valueOf={point => point.currentA} selected={curveSampleIndex} onSelect={setCurveSampleIndex} isCurrent={() => selectedIdRef.current === selected.id} />
            <EvidenceCurve id="charge-detail-temperature" provenanceOf={point => chargeCurveProvenance(point, 'temperatureC')} forceDisconnected={selectedEvidence.hasUnplaceableSamples} title="环境温度" unit="°C" points={selectedEvidence.points} valueOf={point => point.temperatureC} selected={curveSampleIndex} onSelect={setCurveSampleIndex} isCurrent={() => selectedIdRef.current === selected.id} />
          </> : null}
          {selected.qualityState ? <Text className="muted">记录质量：{MOCK_FIXTURE_ENABLED && selected.source === 'mock_fixture' && selected.qualityState.trim().toLowerCase() === 'observed' ? '合成观测' : qualityLabel(selected.qualityState)}</Text> : null}

          {cacheWarning ? <Text className="error">当前详情尚未保存到本机。</Text> : null}
          {error ? <Text className="error">{error}</Text> : null}
        </View>
        </ScrollView>
      ) : null}
      {error && readAppSession(Taro) ? <Text className="error">{error}</Text> : null}
    </View>
  )
}
