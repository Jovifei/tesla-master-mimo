import { Button, Picker, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow, useUnload } from '@tarojs/taro'
import { useRef, useState } from 'react'
import VisualIcon from '../../components/VisualIcon'
import ReadinessChecklist from '../../components/ReadinessChecklist'
import MetricValue from '../../components/MetricValue'
import LoadingIndicator from '../../components/LoadingIndicator'
import { readinessViewFor } from '../../domain/readiness'
import { ApiError, getApiOrigin, getApiSessionGeneration, matelinkApi } from '../../services/api'
import { carSelectionStorageKey, type Car, type CarStatus, type ReadinessItem, type TelemetryPairing } from '../../services/types'
import { readAppSession } from '../../services/session'
import { createRequestScopeGate } from '../../domain/request_scope'
import { readCarsCache } from '../../services/cars_cache'
import { readVehicleCache, writeVehicleCache } from '../../services/vehicle_cache'

function messageFor(error: unknown): string {
  if (error instanceof ApiError) return error.message
  return '无法读取 MateLink 服务，请稍后重试'
}

function booleanLabel(value: boolean | null, yes: string, no: string): string | null {
  return value == null ? null : value ? yes : no
}

function coordinateLabel(location: CarStatus['location']): string | null {
  if (!location) return null
  return `${location.latitude.toFixed(5)}, ${location.longitude.toFixed(5)}`
}
function stateLabel(value: string | null, charging = false): string | null {
  const states: Record<string, string> = charging ? { charging: '充电中', stopped: '已停止', complete: '已完成', disconnected: '未连接', nopower: '无供电', starting: '准备中' }
    : { online: '在线', offline: '离线', asleep: '休眠', waking: '连接中', driving: '行驶', charging: '充电', parked: '驻车' }
  const key = value?.toLowerCase()
  return key && Object.prototype.hasOwnProperty.call(states, key) ? states[key] : null
}

export default function VehiclePage() {
  const [cars, setCars] = useState<Car[]>([])
  const [car, setCar] = useState<Car | null>(null)
  const [status, setStatus] = useState<CarStatus | null>(null)
  const [readinessItems, setReadinessItems] = useState<ReadinessItem[]>([])
  const [pairing, setPairing] = useState<TelemetryPairing | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [cached, setCached] = useState(false)
  const requestVersion = useRef(0)
  const scopeGate = useRef(createRequestScopeGate())
  const pageOwner = useRef<string | null>(null)
  const selectedStableId = useRef<string | null>(null)
  const readSelection = (userId: string) => {
    try { return String(Taro.getStorageSync(carSelectionStorageKey(userId)) ?? '') } catch { return '' }
  }
  const saveSelection = (userId: string, stableId: string) => {
    selectedStableId.current = stableId
    try { Taro.setStorageSync(carSelectionStorageKey(userId), stableId) } catch { /* optional preference */ }
  }

  const invalidatePage = () => {
    requestVersion.current += 1
    scopeGate.current.invalidate()
    setLoading(false)
  }

  const load = async () => {
    const version = ++requestVersion.current
    const sessionGeneration = getApiSessionGeneration()
    const session = readAppSession(Taro)
    const owner = `${session?.userId ?? ''}:${sessionGeneration}`
    if (pageOwner.current !== owner) {
      pageOwner.current = owner
      selectedStableId.current = null
      setCars([])
      setCar(null)
    }
    scopeGate.current.invalidate()
    setStatus(null)
    setReadinessItems([])
    setPairing(null)
    setCached(false)
    if (!session?.userId) {
      scopeGate.current.invalidate()
      setCars([])
      setCar(null)
      setStatus(null)
      setReadinessItems([])
      setPairing(null)
      setCached(false)
      setError('请先在“我的”中完成微信登录')
      return
    }
    setLoading(true)
    setError(null)
    let origin: string | null = null
    try {
      origin = getApiOrigin()
      const nextCars = await matelinkApi.getCars()
      const currentSession = readAppSession(Taro)
      if (version !== requestVersion.current || sessionGeneration !== getApiSessionGeneration() || currentSession?.userId !== session.userId) return
      setCars(nextCars)
      setCached(false)
      const storedStableId = selectedStableId.current ?? readSelection(session.userId)
      const selected = nextCars.find(item => item.stableId === String(storedStableId)) ?? nextCars[0] ?? null
      setCar(selected)
      if (!selected) {
        scopeGate.current.invalidate()
        setStatus(null)
        setReadinessItems([])
        setPairing(null)
        return
      }
      scopeGate.current.bind({
        accountId: session.userId,
        stableVehicleId: selected.stableId,
        apiOrigin: origin,
        sessionGeneration,
      })
      saveSelection(session.userId, selected.stableId)
      const [statusResult, readinessResult, pairingResult] = await Promise.allSettled([
        (async () => { const ticket = scopeGate.current.begin('status'); const value = await matelinkApi.getCarStatus(selected.id, selected); return { ticket, value } })(),
        (async () => { const ticket = scopeGate.current.begin('readiness'); const value = await matelinkApi.getReadiness(selected.id, selected); return { ticket, value } })(),
        (async () => { const ticket = scopeGate.current.begin('pairing'); const value = await matelinkApi.getTelemetryPairing(selected.id, selected); return { ticket, value } })(),
      ])
      if (version !== requestVersion.current || sessionGeneration !== getApiSessionGeneration() || readAppSession(Taro)?.userId !== session.userId) return
      if (statusResult.status === 'fulfilled' && statusResult.value.ticket.isCurrent()) {
        const nextStatus = statusResult.value.value
        setStatus(nextStatus)
        writeVehicleCache(Taro, session.userId, origin, selected.stableId, nextStatus)
        setCar(current => current ? {
          ...current,
          model: nextStatus.model ?? current.model,
          trim: nextStatus.trim ?? current.trim,
          exteriorColor: nextStatus.exteriorColor ?? current.exteriorColor,
          wheelType: nextStatus.wheelType ?? current.wheelType,
        } : current)
      } else {
        setStatus(readVehicleCache(Taro, session.userId, origin, selected.stableId))
        setCached(true)
      }
      if (readinessResult.status === 'fulfilled' && readinessResult.value.ticket.isCurrent()) setReadinessItems(readinessResult.value.value.items)
      else setReadinessItems([])
      if (pairingResult.status === 'fulfilled' && pairingResult.value.ticket.isCurrent()) setPairing(pairingResult.value.value)
      else setPairing(null)
      const failed = [statusResult, readinessResult, pairingResult].find(item => item.status === 'rejected')
      if (failed?.status === 'rejected') setError(messageFor(failed.reason))
    } catch (reason) {
      if (version === requestVersion.current && sessionGeneration === getApiSessionGeneration() && readAppSession(Taro)?.userId === session.userId) {
        setError(messageFor(reason))
        const offlineCars = origin ? readCarsCache(Taro, session.userId, origin) : []
        const stored = selectedStableId.current ?? readSelection(session.userId)
        const selected = offlineCars.find(item => item.stableId === String(stored)) ?? offlineCars[0] ?? null
        setCars(offlineCars)
        setCar(selected)
        setStatus(selected && origin ? readVehicleCache(Taro, session.userId, origin, selected.stableId) : null)
        setReadinessItems([])
        setPairing(null)
        setCached(true)
      }
    } finally {
      if (version === requestVersion.current && sessionGeneration === getApiSessionGeneration()) setLoading(false)
    }
  }

  useDidShow(() => { void load() })
  useDidHide(invalidatePage)
  useUnload(invalidatePage)

  const selectCar = (event: { detail: { value: number | string } }) => {
    const index = Number(event.detail.value)
    const next = cars[index]
    const session = readAppSession(Taro)
    if (!next || !session?.userId) return
    scopeGate.current.invalidate()
    saveSelection(session.userId!, next.stableId)
    setCar(next)
    setStatus(null)
    setReadinessItems([])
    setPairing(null)
    if (next.remoteVerified === false) {
      requestVersion.current += 1
      setLoading(false)
      setCached(true)
      try { setStatus(readVehicleCache(Taro, session.userId!, getApiOrigin(), next.stableId)) }
      catch { setStatus(null) }
      return
    }
    void load()
  }

  const copyOfficialLink = () => {
    const url = pairing?.virtual_key_url
    if (!url) {
      Taro.showToast({ title: '请在“我的”中重新授权 Tesla', icon: 'none' })
      void Taro.switchTab({ url: '/pages/me/index' })
      return
    }
    Taro.setClipboardData({ data: url })
    Taro.showToast({ title: '官方入口已复制，请在浏览器打开', icon: 'none' })
  }

  const retryTelemetry = async () => {
    if (!car) return
    const operation = ++requestVersion.current
    setLoading(true)
    setError(null)
    try {
      await matelinkApi.configureTelemetry(car.id, car)
      if (operation !== requestVersion.current) return
      await load()
    } catch (reason) {
      if (operation === requestVersion.current) setError(messageFor(reason))
    } finally {
      if (operation === requestVersion.current) setLoading(false)
    }
  }

  const readiness = readinessViewFor(readinessItems)
  return (
    <View className="page">
      <View className="hero card">
        <Text className="eyebrow">MATELINK / WECHAT</Text>
        <View className="visual-heading"><VisualIcon name="vehicle" /><Text className="title">车辆</Text></View>
        <Text className="muted">展示已有状态报告，缺失字段保留未知。</Text>
        <Button className="button button-secondary" loading={loading} onClick={() => { void load() }}>刷新车辆</Button>
      </View>

      <ReadinessChecklist items={readinessItems} pairing={pairing} sessionReady={Boolean(readAppSession(Taro))} />

      {cars.length > 1 ? (
        <View className="card">
          <Text className="section-title">选择车辆</Text>
          <Picker
            mode="selector"
            range={cars.map(item => item.displayName || item.name || `车辆 ${item.stableId}`)}
            value={Math.max(0, cars.findIndex(item => item.stableId === car?.stableId))}
            onChange={selectCar}
          >
            <View className="status">
              <Text className="status-label">当前车辆</Text>
              <Text className="status-value">{car?.displayName || '请选择车辆'}</Text>
            </View>
          </Picker>
        </View>
      ) : null}

      {car ? (
        <View className="card">
          <Text className="section-title">{car.displayName || car.name || '车辆名称待确认'}</Text>
          <Text className="muted">车型：{car.model || '车型待识别'}{car.trim ? ` · ${car.trim}` : ''}</Text>
          <Text className="state-pill">{cached ? '缓存快照 · 尚未确认最新状态' : 'API 状态快照'}</Text>
          {car.exteriorColor ? <Text className="muted">外观：{car.exteriorColor}</Text> : null}
          {loading ? <LoadingIndicator label="正在读取最新数据…" compact /> : null}
          {status ? (
            <View className="metric-grid">
              <MetricValue label="状态" value={stateLabel(status.state)} source={status.source} />
              <MetricValue label="电量" value={status.batteryLevel} unit="%" icon="battery" source={status.source} digits={Number.isInteger(status.batteryLevel) ? 0 : 1} />
              <MetricValue label="续航" value={status.ratedRange} unit="km" icon="distance" source={status.source} digits={1} />
              <MetricValue label="里程" value={status.odometer} unit="km" icon="distance" source={status.source} digits={1} />
              <MetricValue label="充电状态" value={stateLabel(status.chargingState, true)} source={status.source} />
              <MetricValue label="充电功率" value={status.chargerPower} unit="kW" icon="energy" source={status.source} />
              <MetricValue label="速度" value={status.speed} unit="km/h" icon="speed" source={status.source} digits={Number.isInteger(status.speed) ? 0 : 1} />
              <MetricValue label="功率" value={status.power} unit="kW" icon="energy" source={status.source} />
              <MetricValue label="锁车" value={booleanLabel(status.locked, '已锁', '未锁')} source={status.source} />
              <MetricValue label="空调" value={booleanLabel(status.isClimateOn, '开启', '关闭')} source={status.source} />
            </View>
          ) : <Text className="muted">暂无车辆状态观测。</Text>}
          {status?.location ? <Text className="muted">位置：{coordinateLabel(status.location)}（状态快照）</Text> : <Text className="muted">位置：暂无有效坐标</Text>}
          {status?.observedAt && Number.isFinite(Date.parse(status.observedAt)) ? <Text className="muted">状态报告时间：{new Date(status.observedAt).toLocaleString()}（未提供逐字段更新时间）</Text> : <Text className="muted">状态报告时间未提供，不能确认字段是否最新。</Text>}
          {readiness.status === 'pairing_required' || readiness.status === 'permission_required' ? (
            <Button className="button" onClick={copyOfficialLink}>打开 Tesla 官方授权入口</Button>
          ) : null}
          {readiness.status === 'telemetry_not_configured' ? <Text className="muted">持续采集需要服务端先完成配置，请联系管理员。</Text> : null}
          {readiness.status === 'telemetry_error' ? (
            <Button className="button" loading={loading} onClick={retryTelemetry}>重新配置持续采集</Button>
          ) : null}
        </View>
      ) : (
        <View className="card">
          <View className="visual-heading"><VisualIcon name="vehicle" /><Text className="section-title">尚未绑定车辆</Text></View>
          <Text className="muted">请先建立微信会话并完成 Tesla 官方关联。没有真实响应时不显示示例车辆。</Text>
          {error ? <Text className="error">{error}</Text> : null}
          <Button className="button" onClick={() => Taro.switchTab({ url: '/pages/me/index' })}>去完成登录与授权</Button>
        </View>
      )}
      {error && car ? <Text className="error">{error}</Text> : null}
    </View>
  )
}
