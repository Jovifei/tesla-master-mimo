import { Button, Input, Picker, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow, usePageScroll, useUnload } from '@tarojs/taro'
import { useRef, useState } from 'react'
import { historyRowKey, isHistoryQuarantined, pageCanContinue, sortHistoryByStartDate } from '../../domain/history'
import { acceptHistoryDetail, acceptHistoryPage, collectHistoryInterval, enrichHistoryInterval, reconcileHistoryCache, type HistoryInterval } from '../../domain/history_controller'
import { emptyHistoryQuery, filterHistory, historyDateTime, normalizeHistoryQuery, readHistoryQuery, writeHistoryQuery, type HistoryQuery } from '../../domain/history_query'
import { readCarsCache } from '../../services/cars_cache'
import './index.scss'
import { createRequestScopeGate } from '../../domain/request_scope'
import { ApiError, getApiOrigin, getApiSessionGeneration, matelinkApi } from '../../services/api'
import { carSelectionStorageKey, type Car, type Charge, type HistoryPage, type HistoryPageMeta } from '../../services/types'
import { readAppSession } from '../../services/session'
import { historyCacheKey, readHistoryCache, writeHistoryCache } from '../../services/history_cache'
import LoadingIndicator from '../../components/LoadingIndicator'
import HistoryIntervalSummary from '../../components/HistoryIntervalSummary'
import HistoryDateChips from '../../components/HistoryDateChips'
import { inspectForegroundEvents, observeForegroundEvents, type ForegroundNotice } from '../../services/foreground_events'
import MetricValue from '../../components/MetricValue'
import VisualIcon from '../../components/VisualIcon'
import { availabilityLabel, qualityLabel, sourceLabel } from '../../domain/data_labels'
import { resolveChargeEnergy } from '../../domain/drive_energy'
import { qualifiedAcEnergyBalance } from '../../domain/charge_energy_balance'
import { analysisApi } from '../../services/analysis_api'
import type { ChargeEvidence } from '../../services/analysis_types'
import { clearChargeCostOverride, overlayChargeCosts, readChargeCostOverride, writeChargeCostOverride, type ChargeCostScope } from '../../services/charge_cost'

const PAGE_SIZE = 20

function errorMessage(reason: unknown): string {
  if (reason instanceof ApiError) return reason.message
  return '无法读取充电数据'
}

function battery(number: number | null): string { return number != null && Number.isFinite(number) && number >= 0 && number <= 100 ? `${number}%` : '暂无电量' }

function verifiedEnergy(charge: Charge) {
  return charge.energy?.evidence ? resolveChargeEnergy({ source: charge.source, start_date: charge.startDate,
    end_date: charge.endDate, energy_contract: charge.energy.evidence }) : null
}
function inputLabel(charge: Charge): string { return verifiedEnergy(charge)?.acInputKwh != null ? 'AC 输入' : '输入电量（历史报告）' }
function inputNumber(charge: Charge): number | null {
  const verified = verifiedEnergy(charge)
  return verified ? verified.inputEnergyKwh : charge.energy ? charge.energy.inputEnergyKwh : charge.energyUsedKwh
}
function modeLabel(charge: Charge): string {
  const mode = verifiedEnergy(charge)?.chargeMode
  return mode === 'ac' ? 'AC 充电' : mode === 'dc' ? 'DC 充电' : '模式未确认'
}

function value(value: string | number | null | undefined): string {
  return value == null || value === '' || (typeof value === 'number' && !Number.isFinite(value)) ? '暂无数据' : String(value)
}

const emptyMeta: HistoryPageMeta = {
  page: 1, show: PAGE_SIZE, total: null, totalPages: null, availability: null,
  source: null, qualityState: null, qualityReason: null, hasMore: false,
}

export default function ChargesPage() {
  const [cars, setCars] = useState<Car[]>([])
  const [car, setCar] = useState<Car | null>(null)
  const [items, setItems] = useState<Charge[]>([])
  const [current, setCurrent] = useState<Charge | null>(null)
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
  const [costEditing, setCostEditing] = useState(false), [costAmount, setCostAmount] = useState(''), [costCurrency, setCostCurrency] = useState('')
  const [, setCostVersion] = useState(0)
  const selectedRef = useRef(selected), carRef = useRef(car)
  selectedRef.current = selected; carRef.current = car
  const costEdit = useRef<{ scope: ChargeCostScope; charge: Charge; generation: number; epoch: number; isCurrent: () => boolean } | null>(null)
  const [interval, setInterval] = useState<HistoryInterval<Charge> | null>(null)
  const [notices, setNotices] = useState<ForegroundNotice[]>([])
  const notice = notices[0] ?? null
  const [noticeLimited, setNoticeLimited] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [query, setQueryState] = useState<HistoryQuery>(emptyHistoryQuery())
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
  const scopeGate = useRef(createRequestScopeGate())

  const resetHistoryState = () => {
    itemsRef.current = []
    pagesRef.current = []
    pageRef.current = 0
    setItems([])
    setMeta(null)
    void setSelected(null)
    setSelectedEvidence(null); setCostEditing(false); costEdit.current = null
    setInterval(null)
    setNotices([])
    setNoticeLimited(false)
    setCacheWarning(false)
    queryScopeRef.current = null
    setQuery(emptyHistoryQuery())
  }

  const invalidatePage = () => {
    lifecycleEpoch.current += 1
    scopeGate.current.invalidate()
    loadingMoreRef.current = false
    setLoading(false)
    setLoadingMore(false)
    setNotices([])
    setNoticeLimited(false)
    setCostEditing(false); costEdit.current = null
  }

  const restoreCached = (nextCar: Car) => {
    const session = readAppSession(Taro)
    if (!session?.userId) return
    const key = historyCacheKey('charges', session.userId, nextCar.stableId, getApiOrigin())
    queryScopeRef.current = key
    setQuery(readHistoryQuery(Taro, key))
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
    try { scopeGate.current.begin('cost-editor') } catch { /* no bound remote scope */ }
    setSelectedEvidence(null); setCostEditing(false); costEdit.current = null
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
    setQuery(readHistoryQuery(Taro, cacheKey))

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
      setInterval(snapshot => snapshot ? { ...snapshot, items: snapshot.items.map(row => decision.state.items.find(item => historyRowKey(item) === historyRowKey(row) || item.id === row.id) ?? row) } : snapshot)
      const saved = writeHistoryCache(Taro, cacheKey, decision.state.items)
      setCacheWarning(!saved)
      setMeta({ ...result.meta, hasMore: decision.hasMore })
      setError(null)
      if (!append) {
        const admittedRows = result.items.map(row => decision.state.items.find(item => historyRowKey(item) === historyRowKey(row) || item.id === row.id) ?? row)
        const notices = observeForegroundEvents(Taro, { origin: apiOrigin, userId: session.userId, stableId: nextCar.stableId }, 'charges', admittedRows)
        setNotices(notices)
        setNoticeLimited(inspectForegroundEvents(Taro, { origin: apiOrigin, userId: session.userId, stableId: nextCar.stableId }, 'charges').limited)
        const intervalTicket = scopeGate.current.begin('interval')
        const canonicalPage = { ...result, items: enrichHistoryInterval(result.items, decision.state.items) }
        void collectHistoryInterval(canonicalPage, async page => {
          const response = await matelinkApi.getCharges(nextCar.id, page, PAGE_SIZE, nextCar)
          return { ...response, items: enrichHistoryInterval(response.items, itemsRef.current) }
        }, intervalTicket.isCurrent).then(snapshot => {
          if (snapshot && intervalTicket.isCurrent()) setInterval({ ...snapshot, items: snapshot.items.map(row => itemsRef.current.find(item => historyRowKey(item) === historyRowKey(row) || item.id === row.id) ?? row) })
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
      if (ticket.isCurrent()) setCurrent(result)
    } catch (reason) {
      if (ticket.isCurrent()) setError(previous => previous ?? errorMessage(reason))
    }
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
      invalidatePage()
      setCars([])
      setCar(null)
      setCurrent(null)
      resetHistoryState()
      setError('请先在“更多”中登录特斯拉账号')
      return
    }
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
      const row = state.items.find(item => historyRowKey(item) === historyRowKey(context.row) || item.id === context.row.id)
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
  useDidShow(() => { void showPage() })
  useDidHide(() => {
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
  useUnload(() => { pageVisible.current = false; detailContext.current = null; detailCacheBaseline.current = null; invalidatePage(); void nativeTabBar(false) })
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
    if (!verifiedCarRef.current || next.remoteVerified === false) { restoreCached(next); return }
    void Promise.all([loadHistory(next), loadCurrentCharge(next)])
  }

  const loadMore = () => {
    if (!verifiedCarRef.current || !car || loadingMore || loadingMoreRef.current || !pageCanContinue({ items, meta: meta || emptyMeta })) return
    void loadHistory(car, true)
  }

  const openDetail = async (item: Charge) => {
    if (!car) return
    const canonical = itemsRef.current.find(row => historyRowKey(row) === historyRowKey(item) || row.id === item.id)
    if (!canonical || canonical.id !== item.id || isHistoryQuarantined(canonical) || isHistoryQuarantined(item)) return
    let ticket: { isCurrent(): boolean }
    try { ticket = scopeGate.current.begin('detail') } catch { return }
    if (ticket.isCurrent()) { setSelected(canonical); setSelectedEvidence(null); setCostEditing(false); costEdit.current = null }
    try {
      if (!verifiedCarRef.current || car.remoteVerified === false) return
      const evidence = await analysisApi.getChargeEvidence(car, item.id, { isCurrent: ticket.isCurrent })
      const detail = evidence.charge
      if (ticket.isCurrent() && detail) {
        setSelectedEvidence(evidence)
        const state = acceptHistoryDetail({ pages: pagesRef.current, items: itemsRef.current, page: pageRef.current }, canonical, detail)
        if (state.items === itemsRef.current) return
        itemsRef.current = state.items
        pagesRef.current = state.pages
        setItems(state.items)
        setInterval(snapshot => snapshot ? { ...snapshot, items: snapshot.items.map(row => state.items.find(item => historyRowKey(item) === historyRowKey(row) || item.id === row.id) ?? row) } : snapshot)
        const selectedRow = state.items.find(row => historyRowKey(row) === historyRowKey(detail) || row.id === detail.id)
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
  const costRows = (rows: readonly Charge[]) => costScope ? overlayChargeCosts(Taro, costScope, rows) : [...rows]
  const selectedView = selected ? costRows([selected])[0] : null
  const intervalView = interval ? { ...interval, items: costRows(interval.items) } : null
  const costState = selected && costScope ? overlayChargeCosts(Taro, costScope, [selected])[0] : null
  const renderedCostEdit = costEdit.current
  const acEfficiency = selected ? qualifiedAcEnergyBalance(selected)?.efficiencyPercent ?? null : null
  const trustedItems = costRows(filterHistory(items, emptyHistoryQuery()))
  const visibleItems = filterHistory(trustedItems, query)
  const sampleStats = (key: 'powerKw' | 'voltageV' | 'currentA' | 'temperatureC') => {
    const values = (selectedEvidence?.points ?? []).map(point => point[key]).filter((value): value is number => value != null && Number.isFinite(value))
    return { max: values.length ? Math.max(...values) : null, min: values.length ? Math.min(...values) : null,
      average: values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : null, count: values.length }
  }
  const powerStats = sampleStats('powerKw'), voltageStats = sampleStats('voltageV'), currentStats = sampleStats('currentA'), temperatureStats = sampleStats('temperatureC')
  const startCostEdit = () => {
    const currentSession = readAppSession(Taro)
    if (!selected || !car || !currentSession?.userId) return
    let ticket: { isCurrent(): boolean }
    try { ticket = scopeGate.current.begin('cost-editor') } catch { return }
    const scope = { userId: currentSession.userId, origin: getApiOrigin(), stableId: car.stableId }
    const manual = readChargeCostOverride(Taro, scope, selected)
    costEdit.current = { scope, charge: selected, generation: getApiSessionGeneration(), epoch: lifecycleEpoch.current, isCurrent: ticket.isCurrent }
    setCostAmount(manual != null ? String(manual.amount) : '')
    setCostCurrency(manual?.currency ?? selected.currencyCode ?? selected.currency ?? '')
    setCostEditing(true)
  }
  const saveCost = (clear = false) => {
    const context = renderedCostEdit
    if (!context || context !== costEdit.current) return
    const isCurrent = () => {
      try {
        const currentSession = readAppSession(Taro), row = selectedRef.current
        return context.isCurrent() && context.epoch === lifecycleEpoch.current && context.generation === getApiSessionGeneration() &&
          currentSession?.userId === context.scope.userId && getApiOrigin() === context.scope.origin && carRef.current?.stableId === context.scope.stableId &&
          row?.source === context.charge.source && historyRowKey(row) === historyRowKey(context.charge)
      } catch { return false }
    }
    const input = costAmount.trim().replace(',', '.'), amount = /^(?:\d+(?:\.\d*)?|\.\d+)$/.test(input) ? Number(input) : NaN
    const success = clear ? clearChargeCostOverride(Taro, context.scope, context.charge, isCurrent)
      : writeChargeCostOverride(Taro, context.scope, context.charge, amount, costCurrency.trim().toUpperCase(), isCurrent)
    if (!success) { setError('费用未保存：请核对非负金额、三字母币种与当前会话；本机最多200条/64KiB且受共享缓存预算限制，已有记录保留。'); return }
    setCostVersion(value => value + 1); setCostEditing(false); costEdit.current = null; setError(null)
  }
  return (
    <View className="page history-page">
      <View className="history-page-header">
        <Text className="title">充电</Text>
        {cars.length > 1 ? (
          <Picker mode="selector" range={cars.map(item => item.displayName || item.name || `车辆 ${item.stableId}`)} value={Math.max(0, cars.findIndex(item => item.stableId === car?.stableId))} onChange={selectCar}>
            <View className="status"><Text className="status-label">车辆</Text><Text className="status-value">{car?.displayName || '请选择车辆'}</Text></View>
          </Picker>
        ) : null}
        {meta?.availability ? <Text className="muted">历史状态：{availabilityLabel(meta.availability)} · {sourceLabel(meta.source)}</Text> : null}
        {cacheWarning ? <Text className="error">在线数据已读到，但未保存到本机。</Text> : null}
      </View>

      {car ? <View className="history-controls">
        <HistoryDateChips kind="charges" query={query} onChange={updateQuery} />
        <View className="history-filter-row">
          <Picker mode="date" value={query.startDate} onChange={event => updateQuery({ startDate: event.detail.value })}><View>开始日期：{query.startDate || '不限'}</View></Picker>
          <Picker mode="date" value={query.endDate} onChange={event => updateQuery({ endDate: event.detail.value })}><View>结束日期：{query.endDate || '不限'}</View></Picker>
        </View>
        <View className="history-actions"><Button className="button" onClick={() => updateQuery(emptyHistoryQuery())}>清除筛选</Button><Button className="button" loading={loading} onClick={() => { void load() }}>刷新</Button></View>
        {offline ? <Text className="muted">本机缓存 · 未验证在线车辆</Text> : null}
      </View> : null}
      {notice ? <View className="card history-notice">
        <Text className="muted">前台发现一条新的已完成充电，仅本次打开时检查。</Text>
        <Button className="button" onClick={() => {
          const row = itemsRef.current.find(item => item.id === notice.id && item.sessionId === notice.sessionId && item.source === notice.source)
          setNotices(current => current.slice(1))
          if (row) void openDetail(row)
        }}>查看新充电</Button>
        <Button className="button" onClick={() => setNotices([])}>关闭提示</Button>
      </View> : null}
      {car ? <HistoryIntervalSummary interval={intervalView} query={query} kind="charges" /> : null}
      {noticeLimited ? <Text className="muted history-notice-limit">本机前台提示记录不可写或已达上限，后续提示暂停；历史仍可查看</Text> : null}

      {current && !isHistoryQuarantined(current) ? (
        <View className="history-list">
          <View className="visual-heading"><VisualIcon name="energy" /><Text className="section-title">正在充电</Text></View>
          <Text className="muted">开始：{historyDateTime(current.startDate)} · 地点：{value(current.address)}</Text>
          <View className="metric-grid">
            <MetricValue label="已充入" value={current.energyAddedKwh} unit="kWh" icon="battery" />
            <MetricValue label="功率" value={current.chargerPowerKw} unit="kW" icon="energy" />
          </View>
        </View>
      ) : null}

      {!readAppSession(Taro) ? (
        <View className="card empty-state"><VisualIcon name="account" /><Text className="section-title">等待特斯拉账号登录</Text><Text className="muted">请先在“更多”中登录特斯拉账号。</Text></View>
      ) : trustedItems.length === 0 && !loading ? (
        <View className="card empty-state"><VisualIcon name="energy" /><Text className="section-title">暂无可展示的充电记录</Text><Text className="muted">当前没有可展示的充电记录，空响应不会生成示例数据。</Text>
          {!offline && meta?.hasMore ? <Button className="button" loading={loadingMore} onClick={loadMore}>加载更多</Button> : null}
        </View>
      ) : (
        <View className="card">
          <Text className="section-title">充电记录 {trustedItems.length ? `（${trustedItems.length}）` : ''}</Text>
          {loading ? <LoadingIndicator label="正在读取充电记录…" compact /> : null}
          {visibleItems.length === 0 && trustedItems.length > 0 ? <Text className="muted">没有符合筛选的充电记录</Text> : null}
          {visibleItems.map(item => (
            <View key={historyRowKey(item)} className="history-record" onClick={() => { void openDetail(item) }}>
              <View className="history-charge-heading"><VisualIcon name="distance" /><View className="history-charge-location"><Text>{value(item.address)}</Text><Text className="history-record-date">{historyDateTime(item.startDate)}</Text></View><Text className="history-charge-mode">{modeLabel(item)}</Text></View>
              <View className="history-record-metrics">
                <MetricValue label="时长" value={item.durationMinutes != null && Number.isFinite(item.durationMinutes) && item.durationMinutes >= 0 ? item.durationMinutes : null} unit="分钟" icon="clock" digits={Number.isInteger(item.durationMinutes) ? 0 : 1} />
                <MetricValue label="电池充入" value={item.energyAddedKwh} unit="kWh" icon="battery" />
                <MetricValue label="费用" value={item.cost} unit={item.currency || '币种未知'} icon="account" />
                <MetricValue label="电量" value={`${battery(item.startBatteryLevel)} → ${battery(item.endBatteryLevel)}`} icon="battery" />
              </View>
              <Text className="history-source">{sourceLabel(item.source)} · {inputLabel(item)}：{inputNumber(item) == null ? '暂无数据' : `${inputNumber(item)} kWh`}</Text>
              {'costProvenance' in item && item.costProvenance === 'manual' ? <Text className="metric-source">本机手动费用 · 原服务端账单保留</Text> : null}
            </View>
          ))}
          {!offline && meta?.hasMore ? <Button className="button" loading={loadingMore} onClick={loadMore}>加载更多</Button> : null}
        </View>
      )}

      {selected && selectedView && !isHistoryQuarantined(selected) ? (
        <ScrollView className="history-detail-overlay" scrollY scrollTop={detailScrollTop.current} onScroll={event => { detailScrollTop.current = event.detail.scrollTop }} style={{ position: 'fixed', top: 0, left: 0, width: '100%', height: '100%' }} ariaLabel="充电详情">
        <View className="history-detail-surface">
          <View className="history-detail-heading"><Text className="section-title">充电详情</Text><Button className="button" onClick={closeDetail}>返回列表</Button></View>
          <Text className="muted">{value(selected.address)} · {historyDateTime(selected.startDate)} → {historyDateTime(selected.endDate)}</Text>
          <Text className="muted">{modeLabel(selected)}</Text>
          <Text className="section-title">电量</Text><View className="metric-grid">
            <MetricValue label="电池充入" value={selected.energyAddedKwh} unit="kWh" icon="battery" />
            <MetricValue label={inputLabel(selected)} value={inputNumber(selected)} unit="kWh" icon="energy" />
            <MetricValue label="AC输入效率" value={acEfficiency} unit="%" />
          </View>
          <Text className="section-title">电池</Text><View className="metric-grid">
            <MetricValue label="起始SOC" value={selected.startBatteryLevel} unit="%" /><MetricValue label="结束SOC" value={selected.endBatteryLevel} unit="%" />
            <MetricValue label="SOC变化" value={selected.startBatteryLevel != null && selected.endBatteryLevel != null ? selected.endBatteryLevel - selected.startBatteryLevel : null} unit="%" /><MetricValue label="充电时长" value={selected.durationMinutes} unit="分钟" />
          </View>
          <Text className="section-title">功率</Text><View className="metric-grid">
            <MetricValue label="报告功率" value={selectedEvidence?.summary.chargerPowerKw ?? selected.chargerPowerKw} unit="kW" icon="energy" />
            <MetricValue label="样本最高功率" value={powerStats.max} unit="kW" /><MetricValue label="样本最低功率" value={powerStats.min} unit="kW" /><MetricValue label="样本平均功率" value={powerStats.average} unit="kW" />
          </View>
          <Text className="section-title">电压与电流</Text><View className="metric-grid">
            <MetricValue label="报告电压" value={selectedEvidence?.summary.chargerVoltageV ?? null} unit="V" /><MetricValue label="报告电流" value={selectedEvidence?.summary.currentA ?? null} unit="A" />
            <MetricValue label="样本最高电压" value={voltageStats.max} unit="V" /><MetricValue label="样本最低电压" value={voltageStats.min} unit="V" /><MetricValue label="样本平均电压" value={voltageStats.average} unit="V" />
            <MetricValue label="样本最高电流" value={currentStats.max} unit="A" /><MetricValue label="样本最低电流" value={currentStats.min} unit="A" /><MetricValue label="样本平均电流" value={currentStats.average} unit="A" />
          </View>
          <Text className="section-title">温度</Text><View className="metric-grid"><MetricValue label="报告环境温度" value={selectedEvidence?.summary.outsideTempC ?? null} unit="°C" /><MetricValue label="样本最高温度" value={temperatureStats.max} unit="°C" /><MetricValue label="样本最低温度" value={temperatureStats.min} unit="°C" /><MetricValue label="样本平均温度" value={temperatureStats.average} unit="°C" /></View>
          <Text className="muted">仅基于本次响应的有效采样：功率 {powerStats.count} / 电压 {voltageStats.count} / 电流 {currentStats.count} / 温度 {temperatureStats.count} 点；缺失不补0，均值为样本均值。</Text>
          <Button className="button-secondary" disabled={offline || !verifiedCarRef.current} onClick={() => Taro.navigateTo({ url: `/pages/analysis/index?kind=chargeCurves&chargeId=${encodeURIComponent(selected.id)}` })}>查看SOC、功率、电压、电流、温度曲线</Button>
          <Text className="section-title">费用</Text><View className="metric-grid">
            <MetricValue label="费用" value={selectedView.cost} unit={selectedView.currencyCode || selectedView.currency || '币种未知'} icon="account" />
            <MetricValue label="每电池充入kWh费用" value={selectedView.cost != null && (selected.energyAddedKwh ?? 0) > 0 ? selectedView.cost / selected.energyAddedKwh! : null} unit={`${selectedView.currencyCode || selectedView.currency || '币种未知'}/kWh`} />
          </View>
          <Text className="muted">费用来源：{costState?.costProvenance === 'manual' ? '本机手动设置' : costState?.costProvenance === 'actual' ? selectedView.cost === 0 ? '服务端报告0费用' : '服务端账单报告' : '未知'}</Text>
          {costState?.costProvenance === 'manual' ? <Text className="metric-source">原服务端账单：{costState.reportedCost ?? '未知'} {costState.reportedCurrency || '币种未知'} · 本机编辑时间：{historyDateTime(costState.manualCostUpdatedAt)}</Text> : null}
          <Button className="button-secondary" onClick={startCostEdit}>编辑本机费用</Button>
          {costEditing ? <View className="card"><Text className="section-title">本机手动费用</Text><Text className="muted">仅保存当前记录的本机费用；不修改服务端账单或合格能量。</Text>
            <Input type="digit" value={costAmount} placeholder="非负总金额（允许0）" onInput={event => setCostAmount(event.detail.value)} />
            <Input value={costCurrency} maxlength={3} placeholder="币种代码，如 EUR / CNY" onInput={event => setCostCurrency(event.detail.value)} />
            <Button className="button" onClick={() => saveCost()}>保存本机费用</Button><Button className="button-secondary" onClick={() => saveCost(true)}>清除本机费用并恢复账单</Button><Button className="button-secondary" onClick={() => { setCostEditing(false); costEdit.current = null }}>取消费用编辑</Button>
          </View> : null}
          {selected.qualityState ? <Text className="muted">记录质量：{qualityLabel(selected.qualityState)}</Text> : null}
          {cacheWarning ? <Text className="error">当前详情尚未保存到本机。</Text> : null}
          {error ? <Text className="error">{error}</Text> : null}
        </View>
        </ScrollView>
      ) : null}
      {error ? <Text className="error">{error}</Text> : null}
    </View>
  )
}
