import { Button, Picker, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow, useUnload } from '@tarojs/taro'
import { useRef, useState } from 'react'
import { historyRowKey, isHistoryQuarantined, pageCanContinue, sortHistoryByStartDate } from '../../domain/history'
import { acceptHistoryDetail, acceptHistoryPage } from '../../domain/history_controller'
import { emptyHistoryQuery, filterHistory, historyDateTime, normalizeHistoryQuery, readHistoryQuery, writeHistoryQuery, type HistoryQuery } from '../../domain/history_query'
import { readCarsCache } from '../../services/cars_cache'
import './index.scss'
import { createRequestScopeGate } from '../../domain/request_scope'
import { ApiError, getApiOrigin, getApiSessionGeneration, matelinkApi } from '../../services/api'
import { carSelectionStorageKey, type Car, type Charge, type HistoryPage, type HistoryPageMeta } from '../../services/types'
import { readAppSession } from '../../services/session'
import { historyCacheKey, readHistoryCache, writeHistoryCache } from '../../services/history_cache'
import LoadingIndicator from '../../components/LoadingIndicator'
import { availabilityLabel, qualityLabel, sourceLabel } from '../../domain/data_labels'
import { resolveChargeEnergy } from '../../domain/drive_energy'

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
function inputValue(charge: Charge): string {
  const verified = verifiedEnergy(charge)
  const input = verified ? verified.inputEnergyKwh : charge.energy ? charge.energy.inputEnergyKwh : charge.energyUsedKwh
  return input == null ? '暂无数据' : `${input} kWh`
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
  const [selected, setSelected] = useState<Charge | null>(null)
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
    setSelected(null)
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
    setMeta({ ...emptyMeta, availability: 'cached', source: 'local_history', hasMore: false })
  }

  const updateQuery = (patch: Partial<HistoryQuery>) => {
    const next = normalizeHistoryQuery({ ...queryRef.current, ...patch })
    if (queryScopeRef.current) writeHistoryQuery(Taro, queryScopeRef.current, next)
    setQuery(next)
  }
  const closeDetail = () => {
    try { scopeGate.current.begin('detail') } catch { /* no bound remote scope */ }
    setSelected(null)
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
      const saved = writeHistoryCache(Taro, cacheKey, decision.state.items)
      setCacheWarning(!saved)
      setMeta({ ...result.meta, hasMore: decision.hasMore })
      setError(null)
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
      setError('请先在“我的”中完成微信登录')
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

  useDidShow(() => { void load() })
  useDidHide(invalidatePage)
  useUnload(invalidatePage)

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
    if (ticket.isCurrent()) setSelected(canonical)
    try {
      if (!verifiedCarRef.current || car.remoteVerified === false) return
      const detail = await matelinkApi.getChargeDetail(car.id, item.id, car)
      if (ticket.isCurrent() && detail) {
        const state = acceptHistoryDetail({ pages: pagesRef.current, items: itemsRef.current, page: pageRef.current }, canonical, detail)
        if (state.items === itemsRef.current) return
        itemsRef.current = state.items
        pagesRef.current = state.pages
        setItems(state.items)
        const selectedRow = state.items.find(row => historyRowKey(row) === historyRowKey(detail) || row.id === detail.id)
        setSelected(selectedRow && !isHistoryQuarantined(selectedRow) ? selectedRow : null)
        const session = readAppSession(Taro)
        if (session?.userId) setCacheWarning(!writeHistoryCache(Taro, historyCacheKey('charges', session.userId, car.stableId, getApiOrigin()), state.items))
      }
    } catch (reason) {
      if (ticket.isCurrent()) setError(errorMessage(reason))
    }
  }

  const trustedItems = filterHistory(items, emptyHistoryQuery())
  const visibleItems = filterHistory(trustedItems, query)
  return (
    <View className="page history-page">
      <View className="card">
        <Text className="title">充电</Text>
        <Text className="muted">{car ? `${car.displayName || '当前车辆'} · ` : ''}只展示服务端返回的真实充电记录；能量、费用、功率和地点缺失时保持暂无数据。</Text>
        {cars.length > 1 ? (
          <Picker mode="selector" range={cars.map(item => item.displayName || item.name || `车辆 ${item.stableId}`)} value={Math.max(0, cars.findIndex(item => item.stableId === car?.stableId))} onChange={selectCar}>
            <View className="status"><Text className="status-label">车辆</Text><Text className="status-value">{car?.displayName || '请选择车辆'}</Text></View>
          </Picker>
        ) : null}
        {meta?.availability ? <Text className="muted">历史状态：{availabilityLabel(meta.availability)} · {sourceLabel(meta.source)}</Text> : null}
        <Text className="muted">本机缓存保留最近最多 500 条且不超过 800 KiB，其他记录请在线加载。</Text>
        {cacheWarning ? <Text className="error">在线数据已读到，但未保存到本机。</Text> : null}
      </View>

      {car ? <View className="card history-controls">
        <View className="history-filter-row">
          <Picker mode="date" value={query.startDate} onChange={event => updateQuery({ startDate: event.detail.value })}><View>开始日期：{query.startDate || '不限'}</View></Picker>
          <Picker mode="date" value={query.endDate} onChange={event => updateQuery({ endDate: event.detail.value })}><View>结束日期：{query.endDate || '不限'}</View></Picker>
        </View>
        <View className="history-actions"><Button className="button" onClick={() => updateQuery(emptyHistoryQuery())}>清除筛选</Button><Button className="button" loading={loading} onClick={() => { void load() }}>刷新</Button></View>
        {offline ? <Text className="muted">本机缓存 · 未验证在线车辆</Text> : null}
      </View> : null}

      {current && !isHistoryQuarantined(current) ? (
        <View className="card">
          <Text className="section-title">正在充电</Text>
          <Text className="muted">开始：{historyDateTime(current.startDate)} · 地点：{value(current.address)}</Text>
          <View className="metric-grid">
            <View className="metric"><Text className="metric-label">已充入</Text><Text className="metric-value">{current.energyAddedKwh == null ? '暂无数据' : `${current.energyAddedKwh} kWh`}</Text></View>
            <View className="metric"><Text className="metric-label">功率</Text><Text className="metric-value">{current.chargerPowerKw == null ? '暂无数据' : `${current.chargerPowerKw} kW`}</Text></View>
          </View>
        </View>
      ) : null}

      {!readAppSession(Taro) ? (
        <View className="card"><Text className="section-title">等待微信登录</Text><Text className="muted">请先在“我的”中建立会话。</Text></View>
      ) : trustedItems.length === 0 && !loading ? (
        <View className="card"><Text className="section-title">暂无可展示的充电记录</Text><Text className="muted">当前没有可展示的充电记录，空响应不会生成示例数据。</Text>
          {!offline && meta?.hasMore ? <Button className="button" loading={loadingMore} onClick={loadMore}>加载更多</Button> : null}
        </View>
      ) : (
        <View className="card">
          <Text className="section-title">充电记录 {trustedItems.length ? `（${trustedItems.length}）` : ''}</Text>
          {loading ? <LoadingIndicator label="正在读取充电记录…" compact /> : null}
          {visibleItems.length === 0 && trustedItems.length > 0 ? <Text className="muted">没有符合筛选的充电记录</Text> : null}
          {visibleItems.map(item => (
            <View key={historyRowKey(item)} className="history-record" onClick={() => { void openDetail(item) }}>
              <View className="history-record-top"><Text>{historyDateTime(item.startDate)}</Text><Text>{battery(item.startBatteryLevel)} → {battery(item.endBatteryLevel)}</Text></View>
              <Text className="muted">{value(item.address)}</Text>
              <Text className="history-source">{sourceLabel(item.source)}</Text>
              <Text className="muted">{modeLabel(item)}</Text>
              <View className="history-record-metrics">
                <View><Text className="metric-label">耗时</Text><Text>{item.durationMinutes == null ? '暂无数据' : `${item.durationMinutes} 分钟`}</Text></View>
                <View><Text className="metric-label">电池充入</Text><Text>{item.energyAddedKwh == null ? '暂无数据' : `${item.energyAddedKwh} kWh`}</Text></View>
                <View><Text className="metric-label">{inputLabel(item)}</Text><Text>{inputValue(item)}</Text></View>
                <View><Text className="metric-label">费用</Text><Text>{item.cost == null ? '暂无数据' : `${item.cost}${item.currency ? ` ${item.currency}` : '（币种未知）'}`}</Text></View>
              </View>
            </View>
          ))}
          {!offline && meta?.hasMore ? <Button className="button" loading={loadingMore} onClick={loadMore}>加载更多</Button> : null}
        </View>
      )}

      {selected && !isHistoryQuarantined(selected) ? (
        <View className="card">
          <View className="history-detail-heading"><Text className="section-title">充电详情</Text><Button className="button" onClick={closeDetail}>返回列表</Button></View>
          <Text className="muted">{value(selected.address)} · {historyDateTime(selected.startDate)} → {historyDateTime(selected.endDate)}</Text>
          <Text className="muted">{modeLabel(selected)}</Text>
          <View className="metric-grid">
            <View className="metric"><Text className="metric-label">电池充入</Text><Text className="metric-value">{selected.energyAddedKwh == null ? '暂无数据' : `${selected.energyAddedKwh} kWh`}</Text></View>
            <View className="metric"><Text className="metric-label">{inputLabel(selected)}</Text><Text className="metric-value">{inputValue(selected)}</Text></View>
            <View className="metric"><Text className="metric-label">费用</Text><Text className="metric-value">{selected.cost == null ? '暂无数据' : `${selected.cost}${selected.currency ? ` ${selected.currency}` : '（币种未知）'}`}</Text></View>
            <View className="metric"><Text className="metric-label">功率</Text><Text className="metric-value">{selected.chargerPowerKw == null ? '暂无数据' : `${selected.chargerPowerKw} kW`}</Text></View>
          </View>
          {selected.qualityState ? <Text className="muted">记录质量：{qualityLabel(selected.qualityState)}</Text> : null}
        </View>
      ) : null}
      {error ? <Text className="error">{error}</Text> : null}
    </View>
  )
}
