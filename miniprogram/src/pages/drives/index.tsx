import { Button, Picker, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow, useUnload } from '@tarojs/taro'
import { useRef, useState } from 'react'
import { historyRowKey, mergeHistoryByKey, mergeHistoryPages, pageCanContinue, sortHistoryByStartDate } from '../../domain/history'
import { createRequestScopeGate } from '../../domain/request_scope'
import { ApiError, getApiOrigin, getApiSessionGeneration, matelinkApi } from '../../services/api'
import { carSelectionStorageKey, type Car, type Drive, type HistoryPage, type HistoryPageMeta } from '../../services/types'
import { readAppSession } from '../../services/session'
import { historyCacheKey, readHistoryCache, writeHistoryCache } from '../../services/history_cache'

const PAGE_SIZE = 20

function errorMessage(reason: unknown): string {
  if (reason instanceof ApiError) return reason.message
  return reason instanceof Error ? reason.message : '无法读取行程数据'
}

function value(value: string | number | null): string {
  return value == null || value === '' ? '暂无数据' : String(value)
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
  const [selected, setSelected] = useState<Drive | null>(null)
  const [error, setError] = useState<string | null>(null)
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
    const cacheKey = historyCacheKey('drives', session.userId, nextCar.stableId, apiOrigin)

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
      const cached = sortHistoryByStartDate(readHistoryCache<Drive>(Taro, cacheKey))
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
      const result = await matelinkApi.getDrives(nextCar.id, nextPage, PAGE_SIZE)
      if (!ticket.isCurrent()) return
      const pages = append ? [...pagesRef.current, result] : [result]
      const pageState = mergeHistoryPages([], pages, historyRowKey)
      const pageNumberValid = result.meta.page === nextPage
      const paginationBroken = !pageNumberValid || !pageState.validPrefix
      if (paginationBroken) {
        const reason = pageState.errorReason ?? 'pagination_incomplete'
        setMeta(current => append && current
          ? { ...current, hasMore: true, qualityReason: current.qualityReason ?? reason }
          : { ...result.meta, hasMore: false, qualityReason: result.meta.qualityReason ?? reason })
        setError('行程分页响应不完整，请重新加载')
        return
      }
      const merged = mergeHistoryByKey(itemsRef.current, result.items, historyRowKey)
      const sortedItems = sortHistoryByStartDate(merged.items)
      itemsRef.current = sortedItems
      pagesRef.current = pages
      pageRef.current = result.meta.page
      setItems(sortedItems)
      const saved = writeHistoryCache(Taro, cacheKey, sortedItems)
      setCacheWarning(!saved)
      setMeta({ ...result.meta, hasMore: pageState.hasMore })
      setError(null)
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
    const pageEpoch = lifecycleEpoch.current
    const sessionGeneration = getApiSessionGeneration()
    const session = readAppSession(Taro)
    if (!session?.userId) {
      invalidatePage()
      setCars([])
      setCar(null)
      resetHistoryState()
      setError('请先在“我的”中完成微信登录')
      return
    }
    setError(null)
    try {
      const nextCars = await matelinkApi.getCars()
      const current = readAppSession(Taro)
      if (pageEpoch !== lifecycleEpoch.current || sessionGeneration !== getApiSessionGeneration() || current?.userId !== session.userId) return
      const stored = Taro.getStorageSync(carSelectionStorageKey(session.userId))
      const nextCar = nextCars.find(item => item.stableId === String(stored)) ?? nextCars[0] ?? null
      setCars(nextCars)
      if (!nextCar) {
        scopeGate.current.invalidate()
        setCar(null)
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
      await loadHistory(nextCar)
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
    resetHistoryState()
    setError(null)
    void loadHistory(next)
  }

  const loadMore = () => {
    if (!car || loadingMore || loadingMoreRef.current || !pageCanContinue({ items, meta: meta || emptyMeta })) return
    void loadHistory(car, true)
  }

  const openDetail = async (item: Drive) => {
    if (!car) return
    const ticket = scopeGate.current.begin('detail')
    if (ticket.isCurrent()) setSelected(item)
    try {
      const detail = await matelinkApi.getDriveDetail(car.id, item.id)
      if (ticket.isCurrent() && detail) setSelected(detail)
    } catch (reason) {
      if (ticket.isCurrent()) setError(errorMessage(reason))
    }
  }

  return (
    <View className="page">
      <View className="card">
        <Text className="title">行程</Text>
        <Text className="muted">{car ? `${car.displayName || '当前车辆'} · ` : ''}只展示服务端返回的真实记录；缺失路线、能耗或地址会保留为暂无数据。</Text>
        {cars.length > 1 ? (
          <Picker mode="selector" range={cars.map(item => item.displayName || item.name || `车辆 ${item.stableId}`)} value={Math.max(0, cars.findIndex(item => item.stableId === car?.stableId))} onChange={selectCar}>
            <View className="status"><Text className="status-label">车辆</Text><Text className="status-value">{car?.displayName || '请选择车辆'}</Text></View>
          </Picker>
        ) : null}
        {meta?.availability ? <Text className="muted">历史状态：{meta.availability}{meta.source ? ` · 来源：${meta.source}` : ''}</Text> : null}
        {cacheWarning ? <Text className="error">在线数据已读到，但未保存到本机。</Text> : null}
      </View>

      {!readAppSession(Taro) ? (
        <View className="card"><Text className="section-title">等待微信登录</Text><Text className="muted">请先在“我的”中建立会话。</Text></View>
      ) : items.length === 0 && !loading ? (
        <View className="card"><Text className="section-title">暂无行程</Text><Text className="muted">服务端尚未采集到可展示的行程记录，空响应不会生成示例数据。</Text></View>
      ) : (
        <View className="card">
          <Text className="section-title">行程记录 {items.length ? `（${items.length}）` : ''}</Text>
          {loading ? <Text className="muted">正在读取…</Text> : null}
          {items.map(item => (
            <View key={`${item.id}-${item.sessionId || item.startDate || ''}`} className="status" onClick={() => { void openDetail(item) }}>
              <View>
                <Text className="status-label">{value(item.startAddress)} → {value(item.endAddress)}</Text>
                <Text className="muted">{value(item.startDate)} · {value(item.durationMinutes)} 分钟</Text>
              </View>
              <Text className="status-value">{item.distanceKm == null ? '暂无距离' : `${item.distanceKm} km`}</Text>
            </View>
          ))}
          {meta?.hasMore ? <Button className="button" loading={loadingMore} onClick={loadMore}>加载更多</Button> : null}
        </View>
      )}

      {selected ? (
        <View className="card">
          <Text className="section-title">行程详情</Text>
          <Text className="muted">{value(selected.startAddress)} → {value(selected.endAddress)}</Text>
          <View className="metric-grid">
            <View className="metric"><Text className="metric-label">距离</Text><Text className="metric-value">{selected.distanceKm == null ? '暂无数据' : `${selected.distanceKm} km`}</Text></View>
            <View className="metric"><Text className="metric-label">能耗</Text><Text className="metric-value">{selected.energyConsumedKwh == null ? '暂无数据' : `${selected.energyConsumedKwh} kWh`}</Text></View>
            <View className="metric"><Text className="metric-label">起始电量</Text><Text className="metric-value">{selected.startBatteryLevel == null ? '暂无数据' : `${selected.startBatteryLevel}%`}</Text></View>
            <View className="metric"><Text className="metric-label">结束电量</Text><Text className="metric-value">{selected.endBatteryLevel == null ? '暂无数据' : `${selected.endBatteryLevel}%`}</Text></View>
          </View>
          {selected.qualityState ? <Text className="muted">质量：{selected.qualityState}{selected.qualityReason ? ` · ${selected.qualityReason}` : ''}</Text> : null}
        </View>
      ) : null}
      {error ? <Text className="error">{error}</Text> : null}
    </View>
  )
}
