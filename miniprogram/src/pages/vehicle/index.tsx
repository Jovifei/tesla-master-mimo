import { Button, Image, Picker, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow, useUnload } from '@tarojs/taro'
import { useRef, useState } from 'react'
import VisualIcon from '../../components/VisualIcon'
import ReadinessChecklist from '../../components/ReadinessChecklist'
import MetricValue from '../../components/MetricValue'
import TpmsPanel from '../../components/TpmsPanel'
import { observeTpmsCache, readTpmsCache } from '../../services/tpms_cache'
import LoadingIndicator from '../../components/LoadingIndicator'
import { readinessViewFor } from '../../domain/readiness'
import { ApiError, getApiOrigin, getApiSessionGeneration, matelinkApi } from '../../services/api'
import { carSelectionStorageKey, type Car, type CarStatus, type ReadinessItem, type TelemetryPairing } from '../../services/types'
import { readAppSession } from '../../services/session'
import { createRequestScopeGate } from '../../domain/request_scope'
import { readCarsCache } from '../../services/cars_cache'
import { readVehicleCache, writeVehicleCache } from '../../services/vehicle_cache'
import { inspectHistoryCache, type HistoryCacheEvidence } from '../../services/history_cache'
import model3Image from '../../assets/vehicle/model-3.png'
import modelYImage from '../../assets/vehicle/model-y.png'
import modelSImage from '../../assets/vehicle/model-s.png'
import modelXImage from '../../assets/vehicle/model-x.png'
import { readTpmsProfile } from '../../services/tpms_profile'
import { evaluateTpmsAlerts } from '../../domain/tpms_alerts'

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
  const [tpmsCacheReason, setTpmsCacheReason] = useState<string | null>(null)
  const [showDiagnostics, setShowDiagnostics] = useState(false)
  const [historyEvidence, setHistoryEvidence] = useState<HistoryCacheEvidence | null>(null)
  const requestVersion = useRef(0)
  const scopeGate = useRef(createRequestScopeGate())
  const pageOwner = useRef<string | null>(null)
  const pageOrigin = useRef<string | null>(null)
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
    try { getApiOrigin() } catch (reason) {
      scopeGate.current.invalidate()
      setCars([]); setCar(null); setStatus(null); setHistoryEvidence(null); setReadinessItems([]); setPairing(null)
      setLoading(false); setError(messageFor(reason))
      return
    }
    const session = readAppSession(Taro)
    const owner = `${session?.userId ?? ''}:${sessionGeneration}`
    if (pageOwner.current !== owner) {
      pageOwner.current = owner
      selectedStableId.current = null
      setCars([])
      setCar(null)
    }
    scopeGate.current.invalidate()
    pageOrigin.current = null
    setStatus(null)
    setCar(null)
    setHistoryEvidence(null)
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
      setError('请先在“更多”中完成微信登录')
      return
    }
    setLoading(true)
    setError(null)
    let origin: string | null = null
    const ownerIsCurrent = () => {
      try { return version === requestVersion.current && sessionGeneration === getApiSessionGeneration() &&
        readAppSession(Taro)?.userId === session.userId && origin === getApiOrigin() } catch { return false }
    }
    try {
      origin = getApiOrigin()
      const nextCars = await matelinkApi.getCars()
      if (!ownerIsCurrent()) return
      pageOrigin.current = origin
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
      setHistoryEvidence(inspectHistoryCache(Taro, session.userId, selected.stableId, origin))
      const [statusResult, readinessResult, pairingResult] = await Promise.allSettled([
        (async () => { const ticket = scopeGate.current.begin('status'); const value = await matelinkApi.getCarStatus(selected.id, selected); return { ticket, value } })(),
        (async () => { const ticket = scopeGate.current.begin('readiness'); const value = await matelinkApi.getReadiness(selected.id, selected); return { ticket, value } })(),
        (async () => { const ticket = scopeGate.current.begin('pairing'); const value = await matelinkApi.getTelemetryPairing(selected.id, selected); return { ticket, value } })(),
      ])
      if (!ownerIsCurrent()) return
      if (statusResult.status === 'fulfilled' && statusResult.value.ticket.isCurrent()) {
        const nextStatus = statusResult.value.value
        if (nextStatus.tpms) {
          const observed = observeTpmsCache(Taro, session.userId, origin!, selected.stableId, nextStatus.tpms)
          setTpmsCacheReason(observed.reason)
        } else setTpmsCacheReason(null)
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
      if (ownerIsCurrent() || (origin === null && version === requestVersion.current && sessionGeneration === getApiSessionGeneration() && readAppSession(Taro)?.userId === session.userId)) {
        setError(messageFor(reason))
        const offlineCars = origin ? readCarsCache(Taro, session.userId, origin) : []
        const stored = selectedStableId.current ?? readSelection(session.userId)
        const selected = offlineCars.find(item => item.stableId === String(stored)) ?? offlineCars[0] ?? null
        pageOrigin.current = origin
        setCars(offlineCars)
        setCar(selected)
        setStatus(selected && origin ? readVehicleCache(Taro, session.userId, origin, selected.stableId) : null)
        setHistoryEvidence(selected && origin ? inspectHistoryCache(Taro, session.userId, selected.stableId, origin) : null)
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
    let currentOrigin: string | null = null
    try { currentOrigin = getApiOrigin() } catch { /* invalid origin cannot own a vehicle selection */ }
    if (pageOwner.current !== `${session.userId}:${getApiSessionGeneration()}` || !currentOrigin || pageOrigin.current !== currentOrigin) {
      invalidatePage()
      setCars([])
      setCar(null)
      setStatus(null)
      setHistoryEvidence(null)
      setReadinessItems([])
      setPairing(null)
      return
    }
    scopeGate.current.invalidate()
    saveSelection(session.userId!, next.stableId)
    setCar(next)
    setStatus(null)
    setHistoryEvidence(null)
    setReadinessItems([])
    setPairing(null)
    if (next.remoteVerified === false) {
      requestVersion.current += 1
      setLoading(false)
      setCached(true)
      try {
        const origin = getApiOrigin()
        setStatus(readVehicleCache(Taro, session.userId!, origin, next.stableId))
        setHistoryEvidence(inspectHistoryCache(Taro, session.userId!, next.stableId, origin))
      }
      catch { setStatus(null) }
      return
    }
    void load()
  }

  const copyOfficialLink = () => {
    const url = pairing?.virtual_key_url
    if (!url) {
      Taro.showToast({ title: '请在“更多”中重新授权 Tesla', icon: 'none' })
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
  const currentSession = readAppSession(Taro)
  const tpmsHistory = car && currentSession?.userId && pageOrigin.current &&
    pageOwner.current === `${currentSession.userId}:${getApiSessionGeneration()}`
    ? readTpmsCache(Taro, currentSession.userId, pageOrigin.current, car.stableId) : null
  const tpmsProfile = car && currentSession?.userId && pageOrigin.current && pageOwner.current === `${currentSession.userId}:${getApiSessionGeneration()}`
    ? readTpmsProfile(Taro, { origin: pageOrigin.current, userId: currentSession.userId, stableId: car.stableId }) : null
  const tpmsAlerts = evaluateTpmsAlerts(tpmsProfile, status?.tpms, tpmsHistory?.points).warnings
  const model = car?.model?.toUpperCase().replace(/^MODEL\s*/, '')
  const heroImage = model === '3' ? model3Image : model === 'Y' ? modelYImage : model === 'S' ? modelSImage : model === 'X' ? modelXImage : null
  const activeDriving = status?.state?.toLowerCase() === 'driving' || (status?.speed ?? 0) > 0
  const activeCharging = status?.chargingState?.toLowerCase() === 'charging'
  const battery = status?.batteryLevel
  return (
    <View className="page vehicle-page">
      <View className="app-toolbar">
        <View className="toolbar-title"><Text className="title">{car?.displayName || car?.name || '车辆'}</Text>
          <Text className="muted">{car?.model ? `Model ${model}` : '车型待识别'}{car?.trim ? ` · ${car.trim}` : ''}</Text></View>
        <View className="toolbar-actions"><Text className="state-pill">{cached ? '缓存快照' : stateLabel(status?.state ?? null) || '状态未知'}</Text>
          <Button className="toolbar-button" loading={loading} onClick={() => { void load() }}>刷新车辆</Button>
          <Button className="toolbar-button" onClick={() => Taro.navigateTo({ url: '/pages/tools/index?kind=settings' })}>设置</Button></View>
      </View>
      {cars.length > 1 ? <Picker mode="selector" range={cars.map(item => item.displayName || item.name || '车辆')} value={Math.max(0, cars.findIndex(item => item.stableId === car?.stableId))} onChange={selectCar}>
        <View className="vehicle-selector"><Text>当前车辆 · {car?.displayName || car?.name || '请选择车辆'}</Text><Text>切换⌄</Text></View>
      </Picker> : null}
      {car ? <>
        {activeDriving || activeCharging ? <View className="card live-hud" onClick={() => Taro.switchTab({ url: activeDriving ? '/pages/drives/index' : '/pages/charges/index' })}>
          <Text className="section-title">{activeDriving ? '正在行驶' : '正在充电'}</Text>
          <View className="metric-grid"><MetricValue label={activeDriving ? '速度' : '充电功率'} value={activeDriving ? status?.speed : status?.chargerPower} unit={activeDriving ? 'km/h' : 'kW'} />
            <MetricValue label={activeDriving ? '功率' : '已充入电量'} value={activeDriving ? status?.power : status?.chargeEnergyAdded} unit={activeDriving ? 'kW' : 'kWh'} negative={(status?.power ?? 0) < 0} /></View>
        </View> : null}
        <View className="card vehicle-hero">
          <View className="status vehicle-hero-heading"><View className="visual-heading"><VisualIcon name="vehicle" /><Text className="section-title">车辆</Text></View><Text className="state-pill">{stateLabel(status?.state ?? null) || '状态未知'}</Text></View>
          {heroImage ? <Image className="vehicle-image" src={heroImage} mode="aspectFit" ariaLabel="车型示意" onClick={() => Taro.navigateTo({ url: '/pages/tools/index?kind=vehiclePreview' })} /> : <View className="vehicle-image vehicle-unknown"><VisualIcon name="vehicle" /><Text className="muted">车型尚未识别</Text></View>}
          {heroImage ? <Text className="vehicle-image-note">车型示意</Text> : null}
          {activeDriving ? <View className="vehicle-live-strip"><MetricValue label="速度" value={status?.speed} unit="km/h" /><MetricValue label="功率" value={status?.power} unit="kW" negative={(status?.power ?? 0) < 0} /><MetricValue label="挡位" value={status?.shiftState} /></View> : null}
          <View className="vehicle-battery-row"><MetricValue label="电量" value={battery} unit="%" digits={Number.isInteger(battery) ? 0 : 1} />
            <MetricValue label="续航" value={status?.ratedRange} unit="km" digits={1} /></View>
          {battery != null && Number.isFinite(battery) ? <View className="vehicle-battery-track"><View className="vehicle-battery-fill" style={{ width: `${Math.max(0, Math.min(100, battery))}%` }} /></View> : null}
          <View className="vehicle-state-chips"><Text>{booleanLabel(status?.locked ?? null, '已锁', '未锁') || '锁车未知'}</Text><Text>{booleanLabel(status?.pluggedIn ?? null, '已插枪', '未插枪') || '充电连接未知'}</Text><Text>{booleanLabel(status?.sentryMode ?? null, '哨兵开启', '哨兵关闭') || '哨兵未知'}</Text></View>
          {cached ? <Text className="muted">缓存快照 · 尚未确认最新状态</Text> : null}
          {loading ? <LoadingIndicator label="正在读取最新数据…" compact /> : null}
        </View>
        <View className="vehicle-info-row"><View className="card" onClick={() => Taro.navigateTo({ url: '/pages/analysis/index?kind=mileage' })}><MetricValue label="里程" value={status?.odometer} unit="km" icon="distance" digits={1} /></View>
          <View className="card" onClick={() => Taro.navigateTo({ url: '/pages/tools/index?kind=location' })}><MetricValue label="位置" value={coordinateLabel(status?.location ?? null)} icon="route" /></View></View>
        <View className="vehicle-info-row" onClick={() => Taro.navigateTo({ url: '/pages/tools/index?kind=temperature' })}><View className="card"><MetricValue label="车内温度" value={status?.insideTemp} unit="°C" digits={1} /></View><View className="card"><MetricValue label="车外温度" value={status?.outsideTemp} unit="°C" digits={1} /></View></View>
        <TpmsPanel snapshot={status?.tpms} history={tpmsHistory} cacheReason={tpmsCacheReason} />
        {tpmsAlerts.length ? <View className="card"><Text className="section-title">本机胎压规则提示</Text>{tpmsAlerts.map(alert => <Text className="muted" key={`${alert.wheel}-${alert.kind}`}>{({ fl: '左前轮', fr: '右前轮', rl: '左后轮', rr: '右后轮' })[alert.wheel]} · {alert.pressureBar} bar · {alert.kind === 'low' ? '低于本机下限' : alert.kind === 'high' ? '高于本机上限' : '达到本机变化阈值'} · {alert.observedAt}</Text>)}<Text className="metric-source">仅比较已有逐轮观测，不代表后台通知已送达。</Text></View> : null}
        <Button className="toolbar-button" onClick={() => Taro.navigateTo({ url: '/pages/tools/index?kind=tpms' })}>查看胎压历史与趋势</Button>
        <View className="card vehicle-history-entry" onClick={() => Taro.navigateTo({ url: '/pages/insights/index?kind=battery' })}><Text className="section-title">电池历史</Text><Text className="muted">查看已记录的电池历史与容量详情 ›</Text></View>
        {activeCharging ? <View className="card"><Text className="section-title">当前充电</Text><View className="metric-grid"><MetricValue label="充电功率" value={status?.chargerPower} unit="kW" /><MetricValue label="已充入电量" value={status?.chargeEnergyAdded} unit="kWh" /></View></View> : null}
        <View className="vehicle-report"><Text className="metric-source">{status?.source ? `来源：${status.source}` : '来源未标明'}</Text>
          <Text className="metric-source">{status?.observedAt && Number.isFinite(Date.parse(status.observedAt)) ? `状态报告时间：${status.observedAt}（未提供逐字段更新时间）` : '状态报告时间未提供，不能确认字段是否最新。'}</Text></View>
      </> : <View className="card empty-state"><VisualIcon name="vehicle" /><Text className="section-title">尚未绑定车辆</Text><Text className="muted">请先建立微信会话并完成 Tesla 官方关联。没有真实响应时不显示示例车辆。</Text><Button className="button" onClick={() => Taro.switchTab({ url: '/pages/me/index' })}>去完成登录与授权</Button></View>}
      {readiness.status === 'pairing_required' || readiness.status === 'permission_required' ? <Button className="button" onClick={copyOfficialLink}>打开 Tesla 官方授权入口</Button> : null}
      {readiness.status === 'telemetry_not_configured' ? <Text className="muted">持续采集需要服务端先完成配置，请联系管理员。</Text> : null}
      {readiness.status === 'telemetry_error' ? <Button className="button" loading={loading} onClick={retryTelemetry}>重新配置持续采集</Button> : null}
      {error ? <Text className="error">{error}</Text> : null}
      <Button className="button button-secondary" onClick={() => setShowDiagnostics(!showDiagnostics)}>{showDiagnostics ? '收起数据状态' : '查看数据状态'}</Button>
      {showDiagnostics ? <>
        <ReadinessChecklist items={readinessItems} pairing={pairing} sessionReady={Boolean(currentSession)} diagnostics={{ car, status, cached, onlineError: Boolean(error), history: historyEvidence }} />
        {status ? <View className="card"><Text className="section-title">状态报告详情</Text><View className="metric-grid"><MetricValue label="速度" value={status.speed} unit="km/h" /><MetricValue label="功率" value={status.power} unit="kW" negative={(status.power ?? 0) < 0} /><MetricValue label="空调" value={booleanLabel(status.isClimateOn, '开启', '关闭')} /></View></View> : null}
      </> : null}
    </View>
  )
}
