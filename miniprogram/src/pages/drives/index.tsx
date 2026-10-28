import { Button, Map as TaroMap, Picker, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow, usePageScroll, useUnload } from '@tarojs/taro'
import { useRef, useState } from 'react'
import { historyRowKey, isHistoryQuarantined, pageCanContinue, sortHistoryByStartDate } from '../../domain/history'
import { buildDriveHistoryItems, type DriveHistoryItem } from '../../domain/drive_history'
import { toChineseDisplayAddress } from '../../domain/address_display'
import { getAppNavGeometry } from '../../services/nav_geometry'
import { acceptHistoryDetail, acceptHistoryPage, collectHistoryInterval, enrichHistoryInterval, reconcileHistoryCache, type HistoryInterval } from '../../domain/history_controller'
import { emptyHistoryQuery, filterHistory, historyDateTime, historyDetailDateTime, historyEditorialDate, normalizeHistoryQuery, readHistoryQuery, writeHistoryQuery, type HistoryQuery } from '../../domain/history_query'
import { readCarsCache } from '../../services/cars_cache'
import './index.scss'
import { createRequestScopeGate } from '../../domain/request_scope'
import { ApiError, getApiOrigin, getApiSessionGeneration, matelinkApi } from '../../services/api'
import { analysisApi } from '../../services/analysis_api'
import type { ParkedDetail } from '../../services/analysis_types'
import { carSelectionStorageKey, type Car, type Drive, type HistoryPage, type HistoryPageMeta } from '../../services/types'
import { readAppSession } from '../../services/session'
import { historyCacheKey, readHistoryCache, writeHistoryCache } from '../../services/history_cache'
import LoadingIndicator from '../../components/LoadingIndicator'
import HistoryIntervalSummary from '../../components/HistoryIntervalSummary'
import HistoryDateChips from '../../components/HistoryDateChips'
import { inspectForegroundEvents, observeForegroundEvents, type ForegroundNotice } from '../../services/foreground_events'
import MetricValue, { displayNumber, formatSocRange, peakSpeedKmh } from '../../components/MetricValue'
import VisualIcon from '../../components/VisualIcon'
import LoadedDriveChart from '../../components/LoadedDriveChart'
import { availabilityLabel, capacityLabel, energyMethodLabel, qualityLabel, sourceLabel } from '../../domain/data_labels'
import { isAppRouteDrive, isAppStatisticsDrive } from './drive_parity'
import AppDriveMonthChart from './AppDriveMonthChart'

const PAGE_SIZE = 20

function errorMessage(reason: unknown): string {
  if (reason instanceof ApiError) return reason.message
  return '无法读取行程数据'
}

function battery(number: number | null): string { return number != null && Number.isFinite(number) && number >= 0 && number <= 100 ? `${number}%` : '暂无电量' }

function value(value: string | number | null | undefined): string {
  return value == null || value === '' || (typeof value === 'number' && !Number.isFinite(value)) ? '暂无数据' : String(value)
}

function energyValue(item: Drive): number | null {
  const number = item.energy?.quality === 'unknown' || !item.energy ? null : item.energy.energyKwh
  return number != null && Number.isFinite(number) ? number : null
}
function energyLabel(item: Drive): string {
  return item.energy?.quality === 'estimated' ? '估算' : item.energy?.quality === 'reported' ? '报告值' : '能耗缺失'
}
function metric(number: number | null | undefined, unit: string): string {
  return number == null || !Number.isFinite(number) ? '暂无数据' : `${displayNumber(number)} ${unit}`
}
function efficiency(item: Drive): number | null {
  const number = item.energy?.quality === 'unknown' ? null : item.energy?.efficiencyWhKm
  return number != null && Number.isFinite(number) ? number / 10 : null
}
function duration(minutes: number | null) {
  return { value: minutes != null && Number.isFinite(minutes) && minutes >= 0 ? minutes : null, unit: '分钟' }
}

const emptyMeta: HistoryPageMeta = {
  page: 1, show: PAGE_SIZE, total: null, totalPages: null, availability: null,
  source: null, qualityState: null, qualityReason: null, hasMore: false,
}

export default function DrivesPage() {
  const [cars, setCars] = useState<Car[]>([])
  const [car, setCar] = useState<Car | null>(null)
  const [items, setItems] = useState<Drive[]>([])
  const [meta, setMeta] = useState<HistoryPageMeta | null>(null)
  const [selected, setSelectedState] = useState<Drive | null>(null)
  const [selectedEvidence, setSelectedEvidence] = useState<import('../../services/analysis_types').DriveEvidence | null>(null)
  const [mapFailed, setMapFailed] = useState(false)
  // Parked window detail: local facts render instantly, cloud enrichment
  // (temperatures, power, linked charge) fills in from the exact endpoint.
  const [parkedOpen, setParkedOpen] = useState<DriveHistoryItem | null>(null)
  const [parkedCloud, setParkedCloud] = useState<ParkedDetail | null>(null)
  const [parkedCloudFailed, setParkedCloudFailed] = useState(false)
  // A late cloud response must never overwrite a different parked card opened next.
  const parkedRequestRef = useRef<string | null>(null)
  const detailContext = useRef<{ user: string; origin: string; uid: string; generation: number; row: Drive } | null>(null)
  const detailCacheBaseline = useRef<Map<string, string> | null>(null)
  const listScrollTop = useRef(0), detailScrollTop = useRef(0), pageVisible = useRef(false)
  const nativeTabBar = async (hidden: boolean) => {
    try {
      const action = hidden ? Taro.hideTabBar : Taro.showTabBar
      if (typeof action === 'function') await action({ animation: false })
    } catch { /* Non-tab hosts and unavailable native APIs keep the panel usable. */ }
  }
  const setSelected = (row: Drive | null) => {
    if (row) {
      const session = readAppSession(Taro)
      if (!session?.userId || !car) return Promise.resolve()
      if (!detailContext.current || historyRowKey(detailContext.current.row) !== historyRowKey(row)) detailScrollTop.current = 0
      detailContext.current = { user: session.userId, origin: getApiOrigin(), uid: car.stableId, generation: getApiSessionGeneration(), row }
    } else { detailContext.current = null; detailCacheBaseline.current = null }
    setSelectedState(row)
    return nativeTabBar(Boolean(row))
  }
  const [interval, setInterval] = useState<HistoryInterval<Drive> | null>(null)
  const [notices, setNotices] = useState<ForegroundNotice[]>([])
  const notice = notices[0] ?? null
  const [noticeLimited, setNoticeLimited] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [query, setQueryState] = useState<HistoryQuery>(emptyHistoryQuery())
  const queryRef = useRef(query)
  const setQuery = (next: HistoryQuery) => { queryRef.current = next; setQueryState(next) }
  const [offline, setOffline] = useState(false)
  const [sourceExplanationVisible, setSourceExplanationVisible] = useState(false)
  const verifiedCarRef = useRef(false)
  const queryScopeRef = useRef<string | null>(null)
  const [cacheWarning, setCacheWarning] = useState(false)
  const [loading, setLoading] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const itemsRef = useRef<Drive[]>([])
  const pagesRef = useRef<HistoryPage<Drive>[]>([])
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
  }

  const restoreCached = (nextCar: Car) => {
    const session = readAppSession(Taro)
    if (!session?.userId) return
    const key = historyCacheKey('drives', session.userId, nextCar.stableId, getApiOrigin())
    queryScopeRef.current = key
    setQuery(readHistoryQuery(Taro, key))
    const cached = sortHistoryByStartDate(readHistoryCache<Drive>(Taro, key))
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
    const cacheKey = historyCacheKey('drives', session.userId, nextCar.stableId, apiOrigin)
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
      const cached = sortHistoryByStartDate(readHistoryCache<Drive>(Taro, cacheKey))
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
      const result = await matelinkApi.getDrives(nextCar.id, nextPage, PAGE_SIZE, nextCar)
      if (!ticket.isCurrent()) return
      const decision = acceptHistoryPage({ pages: pagesRef.current, items: itemsRef.current, page: pageRef.current }, result, nextPage, historyRowKey)
      if (!decision.accepted) {
        const reason = decision.errorReason ?? 'pagination_incomplete'
        setMeta(current => append && current
          ? { ...current, hasMore: true, qualityReason: current.qualityReason ?? reason }
          : { ...result.meta, hasMore: false, qualityReason: result.meta.qualityReason ?? reason })
        setError('行程分页响应不完整，请重新加载')
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
        const notices = observeForegroundEvents(Taro, { origin: apiOrigin, userId: session.userId, stableId: nextCar.stableId }, 'drives', admittedRows)
        setNotices(notices)
        setNoticeLimited(inspectForegroundEvents(Taro, { origin: apiOrigin, userId: session.userId, stableId: nextCar.stableId }, 'drives').limited)
        const intervalTicket = scopeGate.current.begin('interval')
        const canonicalPage = { ...result, items: enrichHistoryInterval(result.items, decision.state.items) }
        void collectHistoryInterval(canonicalPage, async page => {
          const response = await matelinkApi.getDrives(nextCar.id, page, PAGE_SIZE, nextCar)
          return { ...response, items: enrichHistoryInterval(response.items, itemsRef.current) }
        }, intervalTicket.isCurrent).then(snapshot => {
          if (snapshot && intervalTicket.isCurrent()) setInterval({ ...snapshot, items: snapshot.items.map(row => itemsRef.current.find(item => historyRowKey(item) === historyRowKey(row) || item.id === row.id) ?? row) })
        })
      }
    } catch (reason) {
      if (!ticket.isCurrent()) return
      setError(errorMessage(reason))
      if (append && itemsRef.current.length > 0) setMeta(current => current ? { ...current, hasMore: true } : current)
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

  const load = async () => {
    invalidatePage()
    verifiedCarRef.current = false
    setOffline(false)
    setCars([])
    setCar(null)
    resetHistoryState()
    const pageEpoch = lifecycleEpoch.current
    const sessionGeneration = getApiSessionGeneration()
    const session = readAppSession(Taro)
    if (!session?.userId) {
      invalidatePage()
      setCars([])
      setCar(null)
      resetHistoryState()
      setError('请先在“更多”中完成特斯拉账号登录')
      return
    }
    setError(null)
    setLoading(true)
    try {
      const nextCars = await matelinkApi.getCars()
      const current = readAppSession(Taro)
      if (pageEpoch !== lifecycleEpoch.current || sessionGeneration !== getApiSessionGeneration() || current?.userId !== session.userId) return
      const stored = Taro.getStorageSync(carSelectionStorageKey(session.userId))
      const nextCar = nextCars.find(item => item.stableId === String(stored)) ?? nextCars[0] ?? null
      setCars(nextCars)
      verifiedCarRef.current = true
      if (!nextCar) {
        scopeGate.current.invalidate()
        setCar(null)
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
      await loadHistory(nextCar)
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
      const cached = readHistoryCache<Drive>(Taro, historyCacheKey('drives', context.user, context.uid, context.origin))
        .filter(row => baseline !== null && baseline.get(historyRowKey(row)) !== JSON.stringify(row))
      const replay = (initial: { pages: HistoryPage<Drive>[]; items: Drive[]; page: number }) => reconcileHistoryCache(initial, cached)
      const state = replay({ pages: pagesRef.current, items: itemsRef.current, page: pageRef.current })
      itemsRef.current = state.items; pagesRef.current = state.pages
      setItems(state.items)
      setInterval(snapshot => snapshot ? { ...snapshot, items: replay({ pages: [], items: snapshot.items, page: 0 }).items } : snapshot)
      return state.items.find(item => historyRowKey(item) === historyRowKey(context.row) || item.id === context.row.id)
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
        const key = historyCacheKey('drives', context.user, context.uid, context.origin)
        Taro.getStorageSync(key) // An unreadable baseline cannot prove a later cache record is newer.
        detailCacheBaseline.current = new Map(readHistoryCache<Drive>(Taro, key).map(row => [historyRowKey(row), JSON.stringify(row)]))
      } catch { /* Fail closed: keep current memory rather than replay an unproven disk version. */ }
    }
    pageVisible.current = false; invalidatePage(); setSelectedState(null); closeParked(); void nativeTabBar(false)
  })
  useUnload(() => { pageVisible.current = false; detailContext.current = null; detailCacheBaseline.current = null; invalidatePage(); closeParked(); void nativeTabBar(false) })
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
    resetHistoryState()
    setError(null)
    if (!verifiedCarRef.current || next.remoteVerified === false) { restoreCached(next); return }
    void loadHistory(next)
  }

  const loadMore = () => {
    if (!verifiedCarRef.current || !car || loadingMore || loadingMoreRef.current || !pageCanContinue({ items, meta: meta || emptyMeta })) return
    void loadHistory(car, true)
  }

  const openParked = async (item: DriveHistoryItem & { kind: 'parked' }) => {
    if (!car) return
    const requestKey = item.key
    parkedRequestRef.current = requestKey
    setParkedOpen(item); setParkedCloud(null); setParkedCloudFailed(false)
    try {
      const result = await analysisApi.getParkedDetail(car, item.older.id, item.newer.id)
      if (parkedRequestRef.current !== requestKey) return
      setParkedCloud(result.detail)
      setParkedCloudFailed(result.detail == null)
    } catch {
      if (parkedRequestRef.current !== requestKey) return
      // Local facts stay on screen; cloud-only fields remain honestly missing.
      setParkedCloudFailed(true)
    }
  }
  const closeParked = () => { parkedRequestRef.current = null; setParkedOpen(null); setParkedCloud(null); setParkedCloudFailed(false) }

  const openDetail = async (item: Drive) => {
    if (!car) return
    const canonical = itemsRef.current.find(row => historyRowKey(row) === historyRowKey(item) || row.id === item.id)
    if (!canonical || canonical.id !== item.id || isHistoryQuarantined(canonical) || isHistoryQuarantined(item)) return
    let ticket: { isCurrent(): boolean }
    try { ticket = scopeGate.current.begin('detail') } catch { return }
    if (ticket.isCurrent()) { setSelected(canonical); setSelectedEvidence(null); setMapFailed(false) }
    try {
      if (!verifiedCarRef.current || car.remoteVerified === false) return
      void analysisApi.getDriveEvidence(car, item.id, { isCurrent: ticket.isCurrent }).then(evidence => {
        if (ticket.isCurrent()) setSelectedEvidence(evidence)
      }).catch(() => {})
      const detail = await matelinkApi.getDriveDetail(car.id, item.id, car)
      if (ticket.isCurrent() && detail) {
        const state = acceptHistoryDetail({ pages: pagesRef.current, items: itemsRef.current, page: pageRef.current }, canonical, detail)
        if (state.items === itemsRef.current) return
        itemsRef.current = state.items
        pagesRef.current = state.pages
        setItems(state.items)
        setInterval(snapshot => snapshot ? { ...snapshot, items: snapshot.items.map(row => state.items.find(item => historyRowKey(item) === historyRowKey(row) || item.id === row.id) ?? row) } : snapshot)
        const selectedRow = state.items.find(row => historyRowKey(row) === historyRowKey(detail) || row.id === detail.id)
        setSelected(selectedRow && !isHistoryQuarantined(selectedRow) ? selectedRow : null)
        const session = readAppSession(Taro)
        if (session?.userId) setCacheWarning(!writeHistoryCache(Taro, historyCacheKey('drives', session.userId, car.stableId, getApiOrigin()), state.items))
      }
    } catch (reason) {
      if (ticket.isCurrent()) setError(errorMessage(reason))
    }
  }

  const trustedItems = filterHistory(items.filter(isAppRouteDrive), emptyHistoryQuery())
  const visibleItems = filterHistory(trustedItems, query)
  // App DrivesScreen builds list rows (drives + parking windows) from the visible set.
  const historyItems = buildDriveHistoryItems(visibleItems)
  const statisticsInterval = interval ? { ...interval, items: interval.items.filter(isAppStatisticsDrive) } : null
  const chartRows = statisticsInterval ? filterHistory(statisticsInterval.items, query) : []
  const navGeo = getAppNavGeometry()
  return (
    <View className="page history-page drives-page">
      <View className="history-page-header" style={{ paddingTop: `${navGeo.navBarTop}px`, paddingRight: `${navGeo.capsuleRightPadding}px` }}>
        <Text className="title">行程记录</Text>
        {cars.length > 1 ? (
          <Picker mode="selector" range={cars.map(item => item.displayName || item.name || `车辆 ${item.stableId}`)} value={Math.max(0, cars.findIndex(item => item.stableId === car?.stableId))} onChange={selectCar}>
            <View className="status"><Text className="status-label">车辆</Text><Text className="status-value">{car?.displayName || '请选择车辆'}</Text></View>
          </Picker>
        ) : null}
        {cacheWarning ? <Text className="error">在线数据已读到，但未保存到本机。</Text> : null}
      </View>

      {car ? <View className="history-controls">
        <HistoryDateChips kind="drives" query={query} onChange={updateQuery} />
        <View className="history-distance-chips">
          {[{ label: '全部', min: 0, max: undefined }, { label: '通勤 (< 50km)', min: 0, max: 50 },
            { label: '短途 (50–200km)', min: 50, max: 200 }, { label: '长途 (200km+)', min: 200, max: undefined }].map(option =>
            <Button key={option.label} className={`history-chip ${query.minDistanceKm === option.min && query.maxDistanceKm === option.max ? 'history-chip-selected' : ''}`} onClick={() => updateQuery({ minDistanceKm: option.min, maxDistanceKm: option.max })}>{option.label}</Button>)}
        </View>
        <View className="history-actions-hidden">
          <Picker mode="date" value={query.startDate} onChange={event => updateQuery({ startDate: event.detail.value })}><View>开始日期：{query.startDate || '不限'}</View></Picker>
          <Picker mode="date" value={query.endDate} onChange={event => updateQuery({ endDate: event.detail.value })}><View>结束日期：{query.endDate || '不限'}</View></Picker>
          <Picker mode="selector" range={['全部里程', '至少 1 km', '至少 5 km', '至少 10 km']} value={Math.max(0, [0, 1, 5, 10].indexOf(query.minDistanceKm))} onChange={event => updateQuery({ minDistanceKm: [0, 1, 5, 10][Number(event.detail.value)] ?? 0, maxDistanceKm: undefined })}><View className="history-custom-distance">自定义最少里程：{query.minDistanceKm ? `至少 ${query.minDistanceKm} km` : '不限'}</View></Picker>
          <Button className="button" onClick={() => updateQuery(emptyHistoryQuery())}>清除筛选</Button>
          <Button className="button" loading={loading} onClick={() => { void load() }}>刷新</Button>
        </View>
        {offline ? <Text className="muted">本机缓存 · 未验证在线车辆</Text> : null}
      </View> : null}
      {notice ? <View className="card history-notice">
        <Text className="muted">前台发现一条新的已完成行程，仅本次打开时检查。</Text>
        <Button className="button" onClick={() => {
          const row = itemsRef.current.find(item => item.id === notice.id && item.sessionId === notice.sessionId && item.source === notice.source)
          setNotices(current => current.slice(1))
          if (row) void openDetail(row)
        }}>查看新行程</Button>
        <Button className="button" onClick={() => setNotices([])}>关闭提示</Button>
      </View> : null}
      {car && !loading ? <HistoryIntervalSummary interval={statisticsInterval} query={query} kind="drives" /> : null}
      {noticeLimited ? <Text className="muted history-notice-limit">本机前台提示记录不可写或已达上限，后续提示暂停；历史仍可查看</Text> : null}

      {car && chartRows.length > 0 && !query.startDate && !query.endDate
        ? <AppDriveMonthChart rows={chartRows} partial={!statisticsInterval?.complete} />
        : car && visibleItems.length > 0 && (query.startDate || query.endDate) ? <LoadedDriveChart items={visibleItems.filter(isAppStatisticsDrive)} /> : null}

      {!readAppSession(Taro) ? (
        <View className="card empty-state"><VisualIcon name="account" /><Text className="section-title">等待特斯拉账号登录</Text><Text className="muted">请先在“更多”中建立会话。</Text></View>
      ) : trustedItems.length === 0 && !loading ? (
        <View className="card empty-state"><VisualIcon name="route" /><Text className="section-title">暂无可展示的行程</Text><Text className="muted">当前没有可展示的行程记录，空响应不会生成示例数据。</Text>
          {!offline && meta?.hasMore ? <Button className="button" loading={loadingMore} onClick={loadMore}>加载更多</Button> : null}
        </View>
      ) : (
        <View className="history-list">
          <Text className="section-title">行程历史</Text>
          {loading ? <LoadingIndicator label="正在读取行程…" compact /> : null}
          {historyItems.length === 0 && visibleItems.length === 0 && trustedItems.length > 0 ? <Text className="muted">没有符合筛选的行程</Text> : null}
          {historyItems.map(item => item.kind === 'parked' ? (
            <View key={item.key} className="history-record history-parked" onClick={() => { void openParked(item) }}>
              <View className="parked-heading">
                <View className="parked-icon"><VisualIcon name="vehicle" /></View>
                <View className="parked-main"><Text className="parked-title">驻车于 {toChineseDisplayAddress(item.address) ?? '未知位置'}</Text>
                  <Text className="parked-date">{historyEditorialDate(item.startDate)}</Text></View>
                <View className="parked-end"><Text className="parked-duration">{item.minutes >= 60 ? `${Math.floor(item.minutes / 60)}h ${item.minutes % 60}m` : `${item.minutes}m`}</Text>
                  {item.older.endBatteryLevel != null && item.newer.startBatteryLevel != null
                    ? <Text className="parked-soc">{item.older.endBatteryLevel}% → {item.newer.startBatteryLevel}%</Text> : null}</View>
              </View>
            </View>
          ) : (
            <View key={item.key} className="history-record" onClick={() => { void openDetail(item.drive) }}>
              <Text className="history-record-date">{historyEditorialDate(item.drive.startDate)}</Text>
              <View className="history-record-evidence">
                <Text className="history-soc">{formatSocRange(item.drive.startBatteryLevel, item.drive.endBatteryLevel)}</Text>
                {item.drive.energy?.quality === 'estimated' ? <Text className="history-source">估算能耗</Text> : null}
              </View>
              <View className="history-route">
                <View className="route-indicator-dots">
                  <View className="dot-start" />
                  <View className="line-connector" />
                  <View className="dot-end" />
                </View>
                <View className="history-route-stops">
                  <Text className="history-route-start">{value(toChineseDisplayAddress(item.drive.startAddress))}</Text>
                  <Text className="history-route-end">{value(toChineseDisplayAddress(item.drive.endAddress))}</Text>
                </View>
              </View>
              <View className="history-record-metrics">
                <MetricValue label="能量" value={efficiency(item.drive)} unit={efficiency(item.drive) != null ? "kWh/100km" : undefined} icon="energy" digits={1} negative={(efficiency(item.drive) ?? 0) < 0} />
                <MetricValue label="距离" value={item.drive.distanceKm} unit="km" icon="distance" digits={1} />
                <MetricValue label="最高速度" value={peakSpeedKmh(item.drive)} unit="km/h" icon="speed" digits={Number.isInteger(peakSpeedKmh(item.drive)) ? 0 : 1} />
                <MetricValue label="时长" value={duration(item.drive.durationMinutes).value} unit={duration(item.drive.durationMinutes).unit} icon="clock" digits={Number.isInteger(item.drive.durationMinutes) ? 0 : 1} />
              </View>
            </View>
          ))}
          {!offline && meta?.hasMore ? <Button className="button" loading={loadingMore} onClick={loadMore}>加载更多</Button> : null}
        </View>
      )}

      {selected && !isHistoryQuarantined(selected) ? (
        <ScrollView className="history-detail-overlay" scrollY scrollTop={detailScrollTop.current} onScroll={event => { detailScrollTop.current = event.detail.scrollTop }} style={{ position: 'fixed', top: 0, left: 0, width: '100%', height: '100%' }} ariaLabel="行程详情">
        <View className="history-detail-surface">
          <View className="history-detail-heading" style={{ paddingTop: `${navGeo.navBarTop}px`, paddingRight: `${navGeo.capsuleRightPadding}px`, minHeight: `${navGeo.headerTotalHeight}px` }}>
            <Button className="detail-back" onClick={closeDetail}>‹ 返回</Button>
            <Text className="section-title">行程详情</Text>
            <Button className="detail-curves-btn" onClick={() => { void Taro.navigateTo({ url: `/pages/analysis/index?kind=curves&id=${encodeURIComponent(selected.id)}` }) }}>行程曲线</Button>
          </View>

          {/* 1. 路线地图 */}
          {(() => {
            const points = selectedEvidence?.points ?? []
            const validPoints = points.filter(p => p.latitude != null && p.longitude != null).map(p => ({ latitude: p.latitude!, longitude: p.longitude! }))
            const startMarker = validPoints.length > 0 ? { id: 1, latitude: validPoints[0].latitude, longitude: validPoints[0].longitude, title: '起点', iconPath: '/assets/tabs/route-selected.png', width: 24, height: 24 } : null
            const endMarker = validPoints.length > 1 ? { id: 2, latitude: validPoints[validPoints.length - 1].latitude, longitude: validPoints[validPoints.length - 1].longitude, title: '终点', iconPath: '/assets/tabs/route-selected.png', width: 24, height: 24 } : null
            const markers = [startMarker, endMarker].filter((m): m is NonNullable<typeof m> => Boolean(m))

            return <View className="card drive-map-card">
              <Text className="drive-map-title">路线图</Text>
              {validPoints.length >= 2 && !mapFailed ? (
                <TaroMap id="drive-detail-map" className="drive-detail-map" latitude={validPoints[0].latitude} longitude={validPoints[0].longitude} scale={13} showLocation={false} markers={markers} polyline={[{ points: validPoints, color: '#157a86', width: 4 }]} onError={() => setMapFailed(true)} />
              ) : (
                <View className="drive-map-insufficient">
                  <VisualIcon name="map" />
                  <Text className="muted">路径点不足（{validPoints.length} 点），无法生成连续路线</Text>
                </View>
              )}
            </View>
          })()}

          {/* 2. 行程起止卡片 */}
          <View className="card route-header-card">
            <View className="route-header-top">
              <VisualIcon name="route" />
              <Text>路线</Text>
            </View>
            <View className="history-route">
              <View className="route-indicator-dots">
                <View className="dot-start" />
                <View className="line-connector" />
                <View className="dot-end" />
              </View>
              <View className="history-route-stops">
                <Text className="history-route-start">{value(toChineseDisplayAddress(selected.startAddress))}</Text>
                <Text className="history-route-end">{value(toChineseDisplayAddress(selected.endAddress))}</Text>
              </View>
            </View>
            <View className="route-time-row">
              <View className="route-time-col"><Text className="time-label">开始时间</Text><Text className="time-val">{historyDetailDateTime(selected.startDate)}</Text></View>
              <View className="route-time-col"><Text className="time-label">结束时间</Text><Text className="time-val">{historyDetailDateTime(selected.endDate)}</Text></View>
              <View className="route-time-col"><Text className="time-label">时长</Text><Text className="time-val">{duration(selected.durationMinutes).value ? `${duration(selected.durationMinutes).value} 分钟` : '暂无数据'}</Text></View>
            </View>
          </View>

          {/* 3. 分组面板：行程 */}
          <View className="section-card">
            <View className="section-card-header">
              <View className="section-card-title"><VisualIcon name="route" /><Text>行程</Text></View>
              <Button className="section-card-action" onClick={() => { void Taro.navigateTo({ url: `/pages/analysis/index?kind=curves&id=${encodeURIComponent(selected.id)}` }) }}>行程曲线 ›</Button>
            </View>
            <View className="telemetry-metric-strip">
              <MetricValue label="距离" value={selected.distanceKm} unit="km" digits={1} />
              <MetricValue label="时长" value={duration(selected.durationMinutes).value} unit={duration(selected.durationMinutes).unit} digits={Number.isInteger(selected.durationMinutes) ? 0 : 1} />
              <MetricValue label="平均能耗" value={efficiency(selected)} unit="kWh/100km" digits={1} negative={(efficiency(selected) ?? 0) < 0} />
            </View>
          </View>

          {/* 4. 分组面板：速度 */}
          {(() => {
            const avgDistSpeed = (selected.distanceKm && selected.durationMinutes && selected.durationMinutes > 0)
              ? Number((selected.distanceKm / (selected.durationMinutes / 60)).toFixed(0)) : null
            return <View className="section-card">
              <View className="section-card-header">
                <View className="section-card-title"><VisualIcon name="speed" /><Text>速度</Text></View>
              </View>
              <View className="telemetry-metric-strip">
                <MetricValue label="最高速度" value={peakSpeedKmh(selected)} unit="km/h" digits={0} />
                <MetricValue label="报告均速" value={selected.speedAvgKmh} unit="km/h" digits={0} />
                <MetricValue label="距离均速" value={avgDistSpeed} unit="km/h" digits={0} />
              </View>
            </View>
          })()}

          {/* 5. 分组面板：功率 */}
          {(() => {
            const points = selectedEvidence?.points ?? []
            const powers = points.map(p => p.powerKw).filter((p): p is number => p != null && Number.isFinite(p))
            const maxPower = powers.length ? Math.max(...powers) : null
            const minPower = powers.length ? Math.min(...powers) : null
            const avgPower = powers.length ? Number((powers.reduce((a, b) => a + b, 0) / powers.length).toFixed(1)) : null
            return <View className="section-card">
              <View className="section-card-header">
                <View className="section-card-title"><VisualIcon name="energy" /><Text>功率</Text></View>
              </View>
              <View className="telemetry-metric-strip">
                <MetricValue label="峰值输出" value={maxPower} unit="kW" digits={0} />
                <MetricValue label="峰值回收" value={minPower} unit="kW" digits={0} />
                <MetricValue label="平均功率" value={avgPower} unit="kW" digits={1} />
              </View>
            </View>
          })()}

          {/* 6. 分组面板：电量 */}
          {(() => {
            const usedSoc = (selected.startBatteryLevel != null && selected.endBatteryLevel != null)
              ? selected.startBatteryLevel - selected.endBatteryLevel : null
            return <View className="section-card">
              <View className="section-card-header">
                <View className="section-card-title"><VisualIcon name="battery" /><Text>电量</Text></View>
              </View>
              <View className="telemetry-metric-strip">
                <MetricValue label="起始电量" value={selected.startBatteryLevel} unit="%" digits={0} />
                <MetricValue label="结束电量" value={selected.endBatteryLevel} unit="%" digits={0} />
                <MetricValue label="已用电量" value={usedSoc} unit="%" digits={0} />
                <MetricValue label="总电耗" value={energyValue(selected)} unit="kWh" negative={(energyValue(selected) ?? 0) < 0} />
              </View>
              {selected.energy?.evidence?.method ? (
                <View style={{ marginTop: '16rpx' }}>
                  <Text className="history-source">计算依据：{energyMethodLabel(selected.energy.evidence.method)}</Text>
                </View>
              ) : selected.energy?.quality === 'estimated' ? (
                <View style={{ marginTop: '16rpx' }}>
                  <Text className="history-source">估算能耗</Text>
                </View>
              ) : null}
            </View>
          })()}

          {/* 7. 分组面板：环境温度 */}
          {selected.outsideTempC != null ? (
            <View className="section-card">
              <View className="section-card-header">
                <View className="section-card-title"><VisualIcon name="cloud" /><Text>环境温度</Text></View>
              </View>
              <View className="telemetry-metric-strip">
                <MetricValue label="车外温度" value={selected.outsideTempC} unit="°C" digits={1} />
              </View>
            </View>
          ) : null}

          {cacheWarning ? <Text className="error">当前详情尚未保存到本机。</Text> : null}
          {error ? <Text className="error">{error}</Text> : null}
        </View>
        </ScrollView>
      ) : null}
      {parkedOpen && parkedOpen.kind === 'parked' ? (
        <ScrollView className="history-detail-overlay" scrollY style={{ position: 'fixed', top: 0, left: 0, width: '100%', height: '100%' }} ariaLabel="驻车详情">
        <View className="history-detail-surface">
          <View className="history-detail-heading" style={{ paddingTop: `${navGeo.navBarTop}px`, paddingRight: `${navGeo.capsuleRightPadding}px`, minHeight: `${navGeo.headerTotalHeight}px` }}><Button className="detail-back" onClick={closeParked}>‹ 返回</Button><Text className="section-title">驻车详情</Text></View>
          {(() => {
            // App build70 layered design: hero (address/time/duration/badge) →
            // battery boundaries → cloud detail that shows one honest pending
            // line instead of a wall of per-cell "no data".
            const delta = parkedCloud?.batteryDelta ?? (parkedOpen.older.endBatteryLevel != null && parkedOpen.newer.startBatteryLevel != null
              ? parkedOpen.newer.startBatteryLevel - parkedOpen.older.endBatteryLevel : null)
            const duration = parkedOpen.minutes >= 60 ? `${Math.floor(parkedOpen.minutes / 60)}h ${parkedOpen.minutes % 60}m` : `${parkedOpen.minutes}m`
            const cloudDetail = parkedCloud != null && (parkedCloud.energyKwh != null || parkedCloud.averagePowerW != null ||
              parkedCloud.insideTempC != null || parkedCloud.outsideTempC != null || (parkedCloud.sampleCount ?? 0) > 0)
            return <>
              <View className="card parked-detail-summary">
                <Text className="parked-detail-hero-title">驻车于 {toChineseDisplayAddress(parkedOpen.address) ?? '未知位置'}</Text>
                <Text className="parked-detail-timeline">{historyDetailDateTime(parkedOpen.startDate)} → {historyDetailDateTime(parkedOpen.endDate)}</Text>
                <Text className="parked-detail-note">共驻车 {duration}{delta != null ? ' ' : ''}{delta != null ? (delta > 0
                  ? <Text className="parked-detail-badge charge">⚡ 充电 +{delta}%</Text>
                  : <Text className="parked-detail-badge drain">耗电 −{Math.abs(delta)}%</Text>) : null}</Text>
              </View>
              <View className="section-card">
                <View className="section-card-header">
                  <View className="section-card-title"><VisualIcon name="battery" /><Text>起止电量</Text></View>
                </View>
                <View className="telemetry-metric-strip">
                  <MetricValue label="起始电量" value={parkedOpen.older.endBatteryLevel} unit="%" icon="battery" digits={0} />
                  <MetricValue label="结束电量" value={parkedOpen.newer.startBatteryLevel} unit="%" icon="battery" digits={0} />
                  <MetricValue label="电量变化" value={delta} unit="%" icon="battery" digits={0} />
                </View>
              </View>
              <View className="section-card">
                <View className="section-card-header">
                  <View className="section-card-title"><VisualIcon name="cloud" /><Text>能耗与环境</Text></View>
                </View>
                {cloudDetail && parkedCloud ? <View className="telemetry-metric-strip">
                  <MetricValue label="估算能耗" value={parkedCloud.energyKwh} unit="kWh" icon="energy" digits={2} />
                  <MetricValue label="平均功率" value={parkedCloud.averagePowerW} unit="W" icon="energy" digits={0} />
                  <MetricValue label="车内温度" value={parkedCloud.insideTempC} unit="°C" icon="cloud" digits={1} />
                  <MetricValue label="车外温度" value={parkedCloud.outsideTempC} unit="°C" icon="cloud" digits={1} />
                </View> : <Text className="parked-detail-cloud-pending">{parkedCloudFailed
                  ? '云端明细暂不可用；以上为本机相邻行程推得的驻车事实。'
                  : '云端明细待同步：温度、功率与采样分析将在网络恢复后补全；可下拉重试。'}</Text>}
                <Text className="muted" style={{ display: 'block', fontSize: '22rpx', marginTop: '12rpx' }}>{parkedCloud ? `数据来源：${sourceLabel(parkedCloud.source)}`
                  : parkedCloudFailed ? '数据来源：本机相邻行程推得的驻车事实' : '正在读取云端驻车明细（温度、功率与关联充电）…'}</Text>
              </View>
            </>
          })()}
          {error ? <Text className="error">{error}</Text> : null}
        </View>
        </ScrollView>
      ) : null}
      {error && readAppSession(Taro) ? <Text className="error">{error}</Text> : null}
    </View>
  )
}
