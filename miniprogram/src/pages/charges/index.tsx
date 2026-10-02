import { Button, Picker, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow, useUnload } from '@tarojs/taro'
import { useRef, useState } from 'react'
import { historyRowKey, pageCanContinue, sortHistoryByStartDate } from '../../domain/history'
import { acceptHistoryPage } from '../../domain/history_controller'
import { createRequestScopeGate } from '../../domain/request_scope'
import { ApiError, getApiOrigin, getApiSessionGeneration, matelinkApi } from '../../services/api'
import { carSelectionStorageKey, type Car, type Charge, type HistoryPage, type HistoryPageMeta } from '../../services/types'
import { readAppSession } from '../../services/session'
import { historyCacheKey, readHistoryCache, writeHistoryCache } from '../../services/history_cache'
import LoadingIndicator from '../../components/LoadingIndicator'

const PAGE_SIZE = 20

function errorMessage(reason: unknown): string {
  if (reason instanceof ApiError) return reason.message
  return reason instanceof Error ? reason.message : '无法读取充电数据'
}

function value(value: string | number | null): string {
  return value == null || value === '' ? '暂无数据' : String(value)
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
  }

  const invalidatePage = () => {
    lifecycleEpoch.current += 1
    scopeGate.current.invalidate()
    loadingMoreRef.current = false
    setLoading(false)
    setLoadingMore(false)
  }

  const loadHistory = async (nextCar: Car, append = false) => {
    const session = readAppSession(Taro)
    if (!session?.userId) {
      setError('会话缺少账号标识，请重新登录')
      return
    }
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
        setMeta({ ...emptyMeta, availability: 'cached', source: 'local_history', hasMore: true })
      }
    }

    if (append) {
      loadingMoreRef.current = true
      setLoadingMore(true)
    } else {
      setLoading(true)
    }

    try {
      const result = await matelinkApi.getCharges(nextCar.id, nextPage, PAGE_SIZE)
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
    const ticket = scopeGate.current.begin('current')
    try {
      const result = await matelinkApi.getCurrentCharge(nextCar.id)
      if (ticket.isCurrent()) setCurrent(result)
    } catch (reason) {
      if (ticket.isCurrent()) setError(previous => previous ?? errorMessage(reason))
    }
  }

  const load = async () => {
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
    try {
      const nextCars = await matelinkApi.getCars()
      const currentSession = readAppSession(Taro)
      if (pageEpoch !== lifecycleEpoch.current || sessionGeneration !== getApiSessionGeneration() || currentSession?.userId !== session.userId) return
      const stored = Taro.getStorageSync(carSelectionStorageKey(session.userId))
      const nextCar = nextCars.find(item => item.stableId === String(stored)) ?? nextCars[0] ?? null
      setCars(nextCars)
      if (!nextCar) {
        scopeGate.current.invalidate()
        setCar(null)
        setCurrent(null)
        resetHistoryState()
        setError('当前账号尚未绑定车辆')
        return
      }
      scopeGate.current.bind({
        accountId: session.userId,
        stableVehicleId: nextCar.stableId,
        apiOrigin: getApiOrigin(),
        sessionGeneration,
      })
      Taro.setStorageSync(carSelectionStorageKey(session.userId), nextCar.stableId)
      setCar(nextCar)
      loadingMoreRef.current = false
      await Promise.all([loadHistory(nextCar), loadCurrentCharge(nextCar)])
    } catch (reason) {
      if (pageEpoch === lifecycleEpoch.current && sessionGeneration === getApiSessionGeneration()) setError(errorMessage(reason))
    }
  }

  useDidShow(() => { void load() })
  useDidHide(invalidatePage)
  useUnload(invalidatePage)

  const selectCar = (event: { detail: { value: number | string } }) => {
    const next = cars[Number(event.detail.value)]
    const session = readAppSession(Taro)
    if (!next || !session?.userId) return
    lifecycleEpoch.current += 1
    try {
      scopeGate.current.bind({ accountId: session.userId, stableVehicleId: next.stableId, apiOrigin: getApiOrigin(), sessionGeneration: getApiSessionGeneration() })
    } catch (reason) {
      setError(errorMessage(reason))
      return
    }
    Taro.setStorageSync(carSelectionStorageKey(session.userId), next.stableId)
    setCar(next)
    setCurrent(null)
    resetHistoryState()
    setError(null)
    void Promise.all([loadHistory(next), loadCurrentCharge(next)])
  }

  const loadMore = () => {
    if (!car || loadingMore || loadingMoreRef.current || !pageCanContinue({ items, meta: meta || emptyMeta })) return
    void loadHistory(car, true)
  }

  const openDetail = async (item: Charge) => {
    if (!car) return
    const ticket = scopeGate.current.begin('detail')
    if (ticket.isCurrent()) setSelected(item)
    try {
      const detail = await matelinkApi.getChargeDetail(car.id, item.id)
      if (ticket.isCurrent() && detail) setSelected(detail)
    } catch (reason) {
      if (ticket.isCurrent()) setError(errorMessage(reason))
    }
  }

  return (
    <View className="page">
      <View className="card">
        <Text className="title">充电</Text>
        <Text className="muted">{car ? `${car.displayName || '当前车辆'} · ` : ''}只展示服务端返回的真实充电记录；能量、费用、功率和地点缺失时保持暂无数据。</Text>
        {cars.length > 1 ? (
          <Picker mode="selector" range={cars.map(item => item.displayName || item.name || `车辆 ${item.stableId}`)} value={Math.max(0, cars.findIndex(item => item.stableId === car?.stableId))} onChange={selectCar}>
            <View className="status"><Text className="status-label">车辆</Text><Text className="status-value">{car?.displayName || '请选择车辆'}</Text></View>
          </Picker>
        ) : null}
        {meta?.availability ? <Text className="muted">历史状态：{meta.availability}{meta.source ? ` · 来源：${meta.source}` : ''}</Text> : null}
        {cacheWarning ? <Text className="error">在线数据已读到，但未保存到本机。</Text> : null}
      </View>

      {current ? (
        <View className="card">
          <Text className="section-title">正在充电</Text>
          <Text className="muted">开始：{value(current.startDate)} · 地点：{value(current.address)}</Text>
          <View className="metric-grid">
            <View className="metric"><Text className="metric-label">已充入</Text><Text className="metric-value">{current.energyAddedKwh == null ? '暂无数据' : `${current.energyAddedKwh} kWh`}</Text></View>
            <View className="metric"><Text className="metric-label">功率</Text><Text className="metric-value">{current.chargerPowerKw == null ? '暂无数据' : `${current.chargerPowerKw} kW`}</Text></View>
          </View>
        </View>
      ) : null}

      {!readAppSession(Taro) ? (
        <View className="card"><Text className="section-title">等待微信登录</Text><Text className="muted">请先在“我的”中建立会话。</Text></View>
      ) : items.length === 0 && !loading ? (
        <View className="card"><Text className="section-title">暂无充电记录</Text><Text className="muted">服务端尚未采集到可展示的充电记录，空响应不会生成示例数据。</Text></View>
      ) : (
        <View className="card">
          <Text className="section-title">充电记录 {items.length ? `（${items.length}）` : ''}</Text>
          {loading ? <LoadingIndicator label="正在读取充电记录…" compact /> : null}
          {items.map(item => (
            <View key={`${item.id}-${item.sessionId || item.startDate || ''}`} className="status" onClick={() => { void openDetail(item) }}>
              <View>
                <Text className="status-label">{value(item.address)}</Text>
                <Text className="muted">{value(item.startDate)} · {value(item.durationMinutes)} 分钟</Text>
              </View>
              <Text className="status-value">{item.energyAddedKwh == null ? '暂无能量' : `${item.energyAddedKwh} kWh`}</Text>
            </View>
          ))}
          {meta?.hasMore ? <Button className="button" loading={loadingMore} onClick={loadMore}>加载更多</Button> : null}
        </View>
      )}

      {selected ? (
        <View className="card">
          <Text className="section-title">充电详情</Text>
          <Text className="muted">{value(selected.address)} · {value(selected.startDate)} → {value(selected.endDate)}</Text>
          <View className="metric-grid">
            <View className="metric"><Text className="metric-label">充入能量</Text><Text className="metric-value">{selected.energyAddedKwh == null ? '暂无数据' : `${selected.energyAddedKwh} kWh`}</Text></View>
            <View className="metric"><Text className="metric-label">使用能量</Text><Text className="metric-value">{selected.energyUsedKwh == null ? '暂无数据' : `${selected.energyUsedKwh} kWh`}</Text></View>
            <View className="metric"><Text className="metric-label">费用</Text><Text className="metric-value">{selected.cost == null ? '暂无数据' : `${selected.cost}`}</Text></View>
            <View className="metric"><Text className="metric-label">功率</Text><Text className="metric-value">{selected.chargerPowerKw == null ? '暂无数据' : `${selected.chargerPowerKw} kW`}</Text></View>
          </View>
          {selected.qualityState ? <Text className="muted">质量：{selected.qualityState}{selected.qualityReason ? ` · ${selected.qualityReason}` : ''}</Text> : null}
        </View>
      ) : null}
      {error ? <Text className="error">{error}</Text> : null}
    </View>
  )
}
