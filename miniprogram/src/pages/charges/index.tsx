import { Button, Picker, Text, View } from '@tarojs/components'
import Taro, { useDidShow, useUnload } from '@tarojs/taro'
import { useRef, useState } from 'react'
import { matelinkApi, ApiError } from '../../services/api'
import { carSelectionStorageKey, type Car, type Charge, type HistoryPageMeta } from '../../services/types'
import { readAppSession } from '../../services/session'
import { historyRowKey, mergeHistoryByKey, sortHistoryByStartDate } from '../../domain/history'
import { historyCacheKey, readHistoryCache, writeHistoryCache } from '../../services/history_cache'

const PAGE_SIZE = 20

function errorMessage(reason: unknown): string {
  if (reason instanceof ApiError) return reason.message
  return reason instanceof Error ? reason.message : '无法读取充电数据'
}

function value(value: string | number | null): string {
  return value == null || value === '' ? '暂无数据' : String(value)
}

export default function ChargesPage() {
  const [cars, setCars] = useState<Car[]>([])
  const [car, setCar] = useState<Car | null>(null)
  const [items, setItems] = useState<Charge[]>([])
  const [current, setCurrent] = useState<Charge | null>(null)
  const [meta, setMeta] = useState<HistoryPageMeta | null>(null)
  const [selected, setSelected] = useState<Charge | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const itemsRef = useRef<Charge[]>([])
  const loadingMoreRef = useRef(false)
  const pageRef = useRef(0)
  const scopeRef = useRef<string | null>(null)
  const requestVersion = useRef(0)

  const loadHistory = async (nextCar: Car, version: number, append = false) => {
    const nextPage = append ? pageRef.current + 1 : 1
    const session = readAppSession(Taro)
    const cacheKey = historyCacheKey('charges', session?.userId ?? null, nextCar.stableId)
    if (!append) {
      const cached = readHistoryCache<Charge>(Taro, cacheKey)
      if (cached.length > 0 && version === requestVersion.current) {
        itemsRef.current = cached
        setItems(cached)
        setMeta(current => current ?? { page: 1, show: PAGE_SIZE, total: null, totalPages: null, availability: 'cached', source: 'local_history', qualityState: null, qualityReason: null, hasMore: true })
      }
    }
    if (append) {
      if (loadingMoreRef.current) return
      loadingMoreRef.current = true
      setLoadingMore(true)
    }
    else setLoading(true)
    try {
      const result = await matelinkApi.getCharges(nextCar.id, nextPage, PAGE_SIZE)
      if (version !== requestVersion.current) return
      const merged = mergeHistoryByKey(itemsRef.current, result.items, historyRowKey)
      const sortedItems = sortHistoryByStartDate(merged.items)
      itemsRef.current = sortedItems
      setItems(sortedItems)
      writeHistoryCache(Taro, cacheKey, sortedItems)
      setMeta(result.meta)
      pageRef.current = nextPage
      setError(null)
    } catch (reason) {
      if (version !== requestVersion.current) return
      setError(errorMessage(reason))
      if (append && itemsRef.current.length > 0) setMeta(existing => existing ? { ...existing, hasMore: true } : existing)
    } finally {
      if (append) loadingMoreRef.current = false
      if (version === requestVersion.current) {
        setLoading(false)
        setLoadingMore(false)
      }
    }
  }

  const load = async () => {
    const version = ++requestVersion.current
    const session = readAppSession(Taro)
    if (!session) {
      setCars([]); setCar(null); itemsRef.current = []; setItems([]); setCurrent(null); setMeta(null); setSelected(null); setError('请先在“我的”中完成微信登录')
      return
    }
    setError(null)
    try {
      const nextCars = await matelinkApi.getCars()
      if (version !== requestVersion.current) return
      const scope = `${session.userId || 'anonymous'}:${nextCars.map(item => item.stableId).join(',')}`
      if (scopeRef.current !== scope) {
        scopeRef.current = scope
        itemsRef.current = []; setItems([]); setCurrent(null); setMeta(null); setSelected(null); pageRef.current = 0
      }
      setCars(nextCars)
      const stored = Taro.getStorageSync(carSelectionStorageKey(session.userId))
      const nextCar = nextCars.find(item => item.stableId === String(stored)) ?? nextCars[0] ?? null
      setCar(nextCar)
      if (!nextCar) {
        itemsRef.current = []; setItems([]); setCurrent(null); setMeta(null); setError('当前账号尚未绑定车辆')
        return
      }
      Taro.setStorageSync(carSelectionStorageKey(session.userId), nextCar.stableId)
      const [history, currentCharge] = await Promise.allSettled([
        loadHistory(nextCar, version),
        matelinkApi.getCurrentCharge(nextCar.id),
      ])
      if (version !== requestVersion.current) return
      if (currentCharge.status === 'fulfilled') setCurrent(currentCharge.value)
      else setCurrent(null)
      if (history.status === 'rejected') setError(errorMessage(history.reason))
      if (currentCharge.status === 'rejected') setError(previous => previous ?? errorMessage(currentCharge.reason))
    } catch (reason) {
      if (version === requestVersion.current) setError(errorMessage(reason))
    }
  }

  useDidShow(() => { void load() })
  useUnload(() => { requestVersion.current += 1; loadingMoreRef.current = false })

  const selectCar = (event: { detail: { value: number | string } }) => {
    const next = cars[Number(event.detail.value)]
    const session = readAppSession(Taro)
    if (!next || !session) return
    Taro.setStorageSync(carSelectionStorageKey(session.userId), next.stableId)
    setCar(next); itemsRef.current = []; setItems([]); setCurrent(null); setMeta(null); setSelected(null); pageRef.current = 0
    const version = ++requestVersion.current
    void Promise.allSettled([loadHistory(next, version), matelinkApi.getCurrentCharge(next.id)]).then(([history, active]) => {
      if (version !== requestVersion.current) return
      if (active.status === 'fulfilled') setCurrent(active.value)
      if (history.status === 'rejected') setError(errorMessage(history.reason))
      if (active.status === 'rejected') setError(previous => previous ?? errorMessage(active.reason))
    })
  }

  const loadMore = () => {
    if (car && meta?.hasMore && !loadingMore && !loadingMoreRef.current) void loadHistory(car, requestVersion.current, true)
  }

  const openDetail = async (item: Charge) => {
    if (!car) return
    setSelected(item)
    try {
      const detail = await matelinkApi.getChargeDetail(car.id, item.id)
      if (detail) setSelected(detail)
    } catch (reason) {
      setError(errorMessage(reason))
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
          {loading ? <Text className="muted">正在读取…</Text> : null}
          {items.map(item => (
            <View key={item.id} className="status" onClick={() => { void openDetail(item) }}>
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
