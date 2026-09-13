import { Button, Picker, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow, useUnload } from '@tarojs/taro'
import { useRef, useState } from 'react'
import ReadinessChecklist from '../../components/ReadinessChecklist'
import MetricValue from '../../components/MetricValue'
import { readinessViewFor } from '../../domain/readiness'
import { ApiError, getApiOrigin, getApiSessionGeneration, matelinkApi } from '../../services/api'
import { carSelectionStorageKey, type Car, type CarStatus, type ReadinessItem, type TelemetryPairing } from '../../services/types'
import { readAppSession } from '../../services/session'
import { createRequestScopeGate } from '../../domain/request_scope'

function messageFor(error: unknown): string {
  if (error instanceof ApiError) return error.message
  return error instanceof Error ? error.message : '无法读取 MateLink 服务'
}

function booleanLabel(value: boolean | null, yes: string, no: string): string | null {
  return value == null ? null : value ? yes : no
}

function coordinateLabel(location: CarStatus['location']): string | null {
  if (!location) return null
  return `${location.latitude.toFixed(5)}, ${location.longitude.toFixed(5)}`
}

export default function VehiclePage() {
  const [cars, setCars] = useState<Car[]>([])
  const [car, setCar] = useState<Car | null>(null)
  const [status, setStatus] = useState<CarStatus | null>(null)
  const [readinessItems, setReadinessItems] = useState<ReadinessItem[]>([])
  const [pairing, setPairing] = useState<TelemetryPairing | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const requestVersion = useRef(0)
  const scopeGate = useRef(createRequestScopeGate())

  const invalidatePage = () => {
    requestVersion.current += 1
    scopeGate.current.invalidate()
    setLoading(false)
  }

  const load = async () => {
    const version = ++requestVersion.current
    const sessionGeneration = getApiSessionGeneration()
    const session = readAppSession(Taro)
    if (!session?.userId) {
      scopeGate.current.invalidate()
      setCars([])
      setCar(null)
      setStatus(null)
      setReadinessItems([])
      setPairing(null)
      setError('请先在“我的”中完成微信登录')
      return
    }
    setLoading(true)
    setError(null)
    try {
      const nextCars = await matelinkApi.getCars()
      const currentSession = readAppSession(Taro)
      if (version !== requestVersion.current || sessionGeneration !== getApiSessionGeneration() || currentSession?.userId !== session.userId) return
      setCars(nextCars)
      const storedStableId = Taro.getStorageSync(carSelectionStorageKey(session.userId))
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
        apiOrigin: getApiOrigin(),
        sessionGeneration,
      })
      Taro.setStorageSync(carSelectionStorageKey(session.userId), selected.stableId)
      const [statusResult, readinessResult, pairingResult] = await Promise.allSettled([
        (async () => { const ticket = scopeGate.current.begin('status'); const value = await matelinkApi.getCarStatus(selected.id); return { ticket, value } })(),
        (async () => { const ticket = scopeGate.current.begin('readiness'); const value = await matelinkApi.getReadiness(selected.id); return { ticket, value } })(),
        (async () => { const ticket = scopeGate.current.begin('pairing'); const value = await matelinkApi.getTelemetryPairing(selected.id); return { ticket, value } })(),
      ])
      if (version !== requestVersion.current || sessionGeneration !== getApiSessionGeneration()) return
      if (statusResult.status === 'fulfilled' && statusResult.value.ticket.isCurrent()) {
        const nextStatus = statusResult.value.value
        setStatus(nextStatus)
        setCar(current => current ? {
          ...current,
          model: nextStatus.model ?? current.model,
          trim: nextStatus.trim ?? current.trim,
          exteriorColor: nextStatus.exteriorColor ?? current.exteriorColor,
          wheelType: nextStatus.wheelType ?? current.wheelType,
        } : current)
      } else setStatus(null)
      if (readinessResult.status === 'fulfilled' && readinessResult.value.ticket.isCurrent()) setReadinessItems(readinessResult.value.value.items)
      else setReadinessItems([])
      if (pairingResult.status === 'fulfilled' && pairingResult.value.ticket.isCurrent()) setPairing(pairingResult.value.value)
      else setPairing(null)
      const failed = [statusResult, readinessResult, pairingResult].find(item => item.status === 'rejected')
      if (failed?.status === 'rejected') setError(messageFor(failed.reason))
    } catch (reason) {
      if (version === requestVersion.current && sessionGeneration === getApiSessionGeneration()) setError(messageFor(reason))
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
    if (!next || !session) return
    scopeGate.current.invalidate()
    Taro.setStorageSync(carSelectionStorageKey(session.userId), next.stableId)
    setCar(next)
    setStatus(null)
    setReadinessItems([])
    setPairing(null)
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
    setLoading(true)
    setError(null)
    try {
      await matelinkApi.configureTelemetry(car.id)
      await load()
    } catch (reason) {
      setError(messageFor(reason))
    } finally {
      setLoading(false)
    }
  }

  const readiness = readinessViewFor(readinessItems)
  return (
    <View className="page">
      <View className="hero card">
        <Text className="eyebrow">MATELINK / WECHAT</Text>
        <Text className="title">车辆</Text>
        <Text className="muted">只展示服务端已确认的车辆观测，不用占位数据填充缺失字段。</Text>
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
          {car.exteriorColor ? <Text className="muted">外观：{car.exteriorColor}</Text> : null}
          {loading ? <Text className="muted">正在读取最新数据…</Text> : null}
          {status ? (
            <View className="metric-grid">
              <MetricValue label="状态" value={status.state} source={status.source} />
              <MetricValue label="电量" value={status.batteryLevel == null ? null : `${status.batteryLevel}%`} source={status.source} />
              <MetricValue label="续航" value={status.ratedRange == null ? null : `${status.ratedRange} km`} source={status.source} />
              <MetricValue label="里程" value={status.odometer == null ? null : `${status.odometer} km`} source={status.source} />
              <MetricValue label="充电状态" value={status.chargingState} source={status.source} />
              <MetricValue label="充电功率" value={status.chargerPower == null ? null : `${status.chargerPower} kW`} source={status.source} />
              <MetricValue label="速度" value={status.speed == null ? null : `${status.speed} km/h`} source={status.source} />
              <MetricValue label="功率" value={status.power == null ? null : `${status.power} kW`} source={status.source} />
              <MetricValue label="锁车" value={booleanLabel(status.locked, '已锁', '未锁')} source={status.source} />
              <MetricValue label="空调" value={booleanLabel(status.isClimateOn, '开启', '关闭')} source={status.source} />
            </View>
          ) : <Text className="muted">暂无车辆状态观测。</Text>}
          {status?.location ? <Text className="muted">位置：{coordinateLabel(status.location)}（来源：{status.source || '服务端'}）</Text> : <Text className="muted">位置：暂无有效坐标</Text>}
          {status?.observedAt ? <Text className="muted">最近观测：{status.observedAt}</Text> : null}
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
          <Text className="section-title">尚未绑定车辆</Text>
          <Text className="muted">请先建立微信会话并完成 Tesla 官方关联。没有真实响应时不显示示例车辆。</Text>
          {error ? <Text className="error">{error}</Text> : null}
          <Button className="button" onClick={() => Taro.switchTab({ url: '/pages/me/index' })}>去完成登录与授权</Button>
        </View>
      )}
      {error && car ? <Text className="error">{error}</Text> : null}
    </View>
  )
}
