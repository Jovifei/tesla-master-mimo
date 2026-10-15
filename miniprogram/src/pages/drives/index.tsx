import { Button, Picker, ScrollView, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow, usePageScroll, useUnload } from '@tarojs/taro'
import { useRef, useState } from 'react'
import { historyRowKey, isHistoryQuarantined, pageCanContinue, sortHistoryByStartDate } from '../../domain/history'
import { acceptHistoryDetail, acceptHistoryPage, collectHistoryInterval, enrichHistoryInterval, reconcileHistoryCache, type HistoryInterval } from '../../domain/history_controller'
import { emptyHistoryQuery, filterHistory, historyDateTime, normalizeHistoryQuery, readHistoryQuery, writeHistoryQuery, type HistoryQuery } from '../../domain/history_query'
import { readCarsCache } from '../../services/cars_cache'
import './index.scss'
import { createRequestScopeGate } from '../../domain/request_scope'
import { ApiError, getApiOrigin, getApiSessionGeneration, matelinkApi } from '../../services/api'
import { carSelectionStorageKey, type Car, type Drive, type HistoryPage, type HistoryPageMeta } from '../../services/types'
import { readAppSession } from '../../services/session'
import { historyCacheKey, readHistoryCache, writeHistoryCache } from '../../services/history_cache'
import LoadingIndicator from '../../components/LoadingIndicator'
import HistoryIntervalSummary from '../../components/HistoryIntervalSummary'
import HistoryDateChips from '../../components/HistoryDateChips'
import { inspectForegroundEvents, observeForegroundEvents, type ForegroundNotice } from '../../services/foreground_events'
import MetricValue, { displayNumber, peakSpeedKmh } from '../../components/MetricValue'
import VisualIcon from '../../components/VisualIcon'
import LoadedDriveChart from '../../components/LoadedDriveChart'
import { availabilityLabel, capacityLabel, energyMethodLabel, qualityLabel, sourceLabel } from '../../domain/data_labels'

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
      setError('请先在“更多”中完成微信登录')
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
    resetHistoryState()
    setError(null)
    if (!verifiedCarRef.current || next.remoteVerified === false) { restoreCached(next); return }
    void loadHistory(next)
  }

  const loadMore = () => {
    if (!verifiedCarRef.current || !car || loadingMore || loadingMoreRef.current || !pageCanContinue({ items, meta: meta || emptyMeta })) return
    void loadHistory(car, true)
  }

  const openDetail = async (item: Drive) => {
    if (!car) return
    const canonical = itemsRef.current.find(row => historyRowKey(row) === historyRowKey(item) || row.id === item.id)
    if (!canonical || canonical.id !== item.id || isHistoryQuarantined(canonical) || isHistoryQuarantined(item)) return
    let ticket: { isCurrent(): boolean }
    try { ticket = scopeGate.current.begin('detail') } catch { return }
    if (ticket.isCurrent()) setSelected(canonical)
    try {
      if (!verifiedCarRef.current || car.remoteVerified === false) return
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

  const trustedItems = filterHistory(items, emptyHistoryQuery())
  const visibleItems = filterHistory(trustedItems, query)
  return (
    <View className="page history-page">
      <View className="history-page-header">
        <Text className="title">行程</Text>
        {cars.length > 1 ? (
          <Picker mode="selector" range={cars.map(item => item.displayName || item.name || `车辆 ${item.stableId}`)} value={Math.max(0, cars.findIndex(item => item.stableId === car?.stableId))} onChange={selectCar}>
            <View className="status"><Text className="status-label">车辆</Text><Text className="status-value">{car?.displayName || '请选择车辆'}</Text></View>
          </Picker>
        ) : null}
        {meta?.availability ? <Text className="muted">历史状态：{availabilityLabel(meta.availability)} · {sourceLabel(meta.source)}</Text> : null}
        {cacheWarning ? <Text className="error">在线数据已读到，但未保存到本机。</Text> : null}
      </View>

      {car ? <View className="history-controls">
        <HistoryDateChips kind="drives" query={query} onChange={updateQuery} />
        <View className="history-distance-chips">
          {[{ label: '全部里程', min: 0, max: undefined }, { label: '通勤 (< 10km)', min: 0, max: 10 },
            { label: '短途 (10–100km)', min: 10, max: 100 }, { label: '长途 (100km+)', min: 100, max: undefined }].map(option =>
            <Button key={option.label} className={`history-chip ${query.minDistanceKm === option.min && query.maxDistanceKm === option.max ? 'history-chip-selected' : ''}`} onClick={() => updateQuery({ minDistanceKm: option.min, maxDistanceKm: option.max })}>{option.label}</Button>)}
        </View>
        <View className="history-filter-row">
          <Picker mode="date" value={query.startDate} onChange={event => updateQuery({ startDate: event.detail.value })}><View>开始日期：{query.startDate || '不限'}</View></Picker>
          <Picker mode="date" value={query.endDate} onChange={event => updateQuery({ endDate: event.detail.value })}><View>结束日期：{query.endDate || '不限'}</View></Picker>
        </View>
        <Picker mode="selector" range={['全部里程', '至少 1 km', '至少 5 km', '至少 10 km']} value={Math.max(0, [0, 1, 5, 10].indexOf(query.minDistanceKm))} onChange={event => updateQuery({ minDistanceKm: [0, 1, 5, 10][Number(event.detail.value)] ?? 0, maxDistanceKm: undefined })}><View className="history-custom-distance">自定义最少里程：{query.minDistanceKm ? `至少 ${query.minDistanceKm} km` : '不限'}</View></Picker>
        <View className="history-actions"><Button className="button" onClick={() => updateQuery(emptyHistoryQuery())}>清除筛选</Button><Button className="button" loading={loading} onClick={() => { void load() }}>刷新</Button></View>
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
      {car ? <HistoryIntervalSummary interval={interval} query={query} kind="drives" /> : null}
      {noticeLimited ? <Text className="muted history-notice-limit">本机前台提示记录不可写或已达上限，后续提示暂停；历史仍可查看</Text> : null}

      {car && visibleItems.length > 0 ? <LoadedDriveChart items={visibleItems} /> : null}

      {!readAppSession(Taro) ? (
        <View className="card empty-state"><VisualIcon name="account" /><Text className="section-title">等待微信登录</Text><Text className="muted">请先在“更多”中建立会话。</Text></View>
      ) : trustedItems.length === 0 && !loading ? (
        <View className="card empty-state"><VisualIcon name="route" /><Text className="section-title">暂无可展示的行程</Text><Text className="muted">当前没有可展示的行程记录，空响应不会生成示例数据。</Text>
          {!offline && meta?.hasMore ? <Button className="button" loading={loadingMore} onClick={loadMore}>加载更多</Button> : null}
        </View>
      ) : (
        <View className="history-list">
          <Text className="section-title">行程记录 {trustedItems.length ? `（${trustedItems.length}）` : ''}</Text>
          {loading ? <LoadingIndicator label="正在读取行程…" compact /> : null}
          {visibleItems.length === 0 && trustedItems.length > 0 ? <Text className="muted">没有符合筛选的行程</Text> : null}
          {visibleItems.map(item => (
            <View key={historyRowKey(item)} className="history-record" onClick={() => { void openDetail(item) }}>
              <Text className="history-record-date">{historyDateTime(item.startDate)}</Text>
              <View className="history-record-evidence"><Text className="history-soc">{battery(item.startBatteryLevel)} → {battery(item.endBatteryLevel)}</Text><Text className="history-source">{energyLabel(item)} · {sourceLabel(item.source)}</Text></View>
              <View className="history-route"><View className="history-route-stops"><Text className="history-route-start">{value(item.startAddress)}</Text><Text className="history-route-end">{value(item.endAddress)}</Text></View><VisualIcon name="route" /></View>
              <View className="history-record-metrics">
                <MetricValue label="时长" value={duration(item.durationMinutes).value} unit={duration(item.durationMinutes).unit} icon="clock" digits={Number.isInteger(item.durationMinutes) ? 0 : 1} />
                <MetricValue label="距离" value={item.distanceKm} unit="km" icon="distance" digits={1} />
                <MetricValue label="最高速度" value={peakSpeedKmh(item)} unit="km/h" icon="speed" digits={Number.isInteger(peakSpeedKmh(item)) ? 0 : 1} />
                <MetricValue label="平均能耗" value={efficiency(item)} unit="kWh/100km" icon="energy" digits={1} negative={(efficiency(item) ?? 0) < 0} />
              </View>
              {peakSpeedKmh(item) != null ? <Text className="metric-source">最高速度来自行程观测样本</Text> : null}
            </View>
          ))}
          {!offline && meta?.hasMore ? <Button className="button" loading={loadingMore} onClick={loadMore}>加载更多</Button> : null}
        </View>
      )}

      {selected && !isHistoryQuarantined(selected) ? (
        <ScrollView className="history-detail-overlay" scrollY scrollTop={detailScrollTop.current} onScroll={event => { detailScrollTop.current = event.detail.scrollTop }} style={{ position: 'fixed', top: 0, left: 0, width: '100%', height: '100%' }} ariaLabel="行程详情">
        <View className="history-detail-surface">
          <View className="history-detail-heading"><Text className="section-title">行程详情</Text><Button className="button" onClick={closeDetail}>返回列表</Button></View>
          <Button className="button-secondary" onClick={() => { void Taro.navigateTo({ url: `/pages/analysis/index?kind=curves&id=${encodeURIComponent(selected.id)}` }) }}>行程曲线</Button>
          <Text className="muted">{value(selected.startAddress)} → {value(selected.endAddress)}</Text>
          <View className="metric-grid">
            <MetricValue label="距离" value={selected.distanceKm} unit="km" icon="distance" digits={1} />
            <MetricValue label="时长" value={duration(selected.durationMinutes).value} unit={duration(selected.durationMinutes).unit} icon="clock" digits={Number.isInteger(selected.durationMinutes) ? 0 : 1} />
            <MetricValue label="最高速度" value={peakSpeedKmh(selected)} unit="km/h" icon="speed" digits={Number.isInteger(peakSpeedKmh(selected)) ? 0 : 1} />
            <MetricValue label="平均能耗" value={efficiency(selected)} unit="kWh/100km" icon="energy" digits={1} negative={(efficiency(selected) ?? 0) < 0} />
            <MetricValue label="总电耗" value={energyValue(selected)} unit="kWh" icon="energy" negative={(energyValue(selected) ?? 0) < 0} />
            <MetricValue label="起始电量" value={selected.startBatteryLevel} unit="%" icon="battery" digits={Number.isInteger(selected.startBatteryLevel) ? 0 : 1} />
            <MetricValue label="结束电量" value={selected.endBatteryLevel} unit="%" icon="battery" digits={Number.isInteger(selected.endBatteryLevel) ? 0 : 1} />
          </View>
          <Text className="history-source">{energyLabel(selected)} · {sourceLabel(selected.source)}</Text>
          <Text className="muted">计算依据：{energyMethodLabel(selected.energy?.evidence?.method)}</Text>
          {selected.energy?.evidence?.capacity_kwh != null ? <Text className="muted">容量：{metric(selected.energy.evidence.capacity_kwh, 'kWh')} · {capacityLabel(selected.energy.evidence.capacity_basis)}</Text> : null}
          <Text className="muted">覆盖：{metric(selected.energy?.coverageRatio == null ? null : selected.energy.coverageRatio * 100, '%')} · {metric(selected.energy?.coverageSeconds, '秒')}</Text>
          {selected.energy?.quality === 'unknown' ? <Text className="muted">能耗证据不足，暂无可用数值。</Text> : null}
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
