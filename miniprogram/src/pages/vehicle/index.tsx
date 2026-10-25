import { Button, Map, Picker, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow, usePageScroll, useUnload } from '@tarojs/taro'
import { useEffect, useRef, useState } from 'react'
import VisualIcon from '../../components/VisualIcon'
import ReadinessChecklist from '../../components/ReadinessChecklist'
import MetricValue from '../../components/MetricValue'
import { DashboardVehicleArt, DashboardHero, DashboardHud, DashboardInfoCard, DashboardTpms, DashboardCurrentCharge } from '../../components/dashboard/DashboardParts'
import DashboardIcon from '../../components/dashboard/DashboardIcon'
import { dashboardMetric, dashboardSource, dashboardSnapshot, dashboardReportTime, dashboardVehicleSubtitle, dashboardTemperature } from '../../components/dashboard/presentation'
import { mapCoordinate } from '../../domain/map_coordinate'
import { mergeVehicleVisualConfig, resolveVehicleVisual } from '../../domain/vehicle_visual'
import { ensureVehicleAsset } from '../../services/vehicle_assets'
import AppearanceSelector from '../../components/dashboard/AppearanceSelector'
import { DEFAULT_VEHICLE_APPEARANCE, clearVehicleAppearance, readVehicleAppearance, writeVehicleAppearance, type VehicleAppearanceScope } from '../../services/vehicle_appearance'
import './index.scss'
import { observeTpmsCache, readTpmsCache } from '../../services/tpms_cache'
import LoadingIndicator from '../../components/LoadingIndicator'
import { findReadinessItem, readinessViewFor, toReadinessView } from '../../domain/readiness'
import { ApiError, getApiOrigin, getApiSessionGeneration, matelinkApi } from '../../services/api'
import { carSelectionStorageKey, type Car, type CarStatus, type ReadinessItem, type TelemetryPairing } from '../../services/types'
import { readAppSession } from '../../services/session'
import { consumeDataStatus } from '../../services/data_status_intent'
import { createRequestScopeGate } from '../../domain/request_scope'
import { readCarsCache } from '../../services/cars_cache'
import { readVehicleCache, writeVehicleCache } from '../../services/vehicle_cache'
import { inspectHistoryCache, type HistoryCacheEvidence } from '../../services/history_cache'
import { readTpmsProfile } from '../../services/tpms_profile'
import { evaluateTpmsAlerts } from '../../domain/tpms_alerts'
import { vehicleSnapshotDelay } from '../../domain/vehicle_polling'

function messageFor(error: unknown): string {
  if (error instanceof ApiError) return error.message
  return '无法读取 MateLink 服务，请稍后重试'
}

function booleanLabel(value: boolean | null, yes: string, no: string): string | null {
  return value == null ? null : value ? yes : no
}

function dashboardHeaderGeometry(): { top: number; toolbarOffset: number; coverHeight: number; capsuleCover: { top: number; left: number; height: number } | null } {
  try {
    const menu = Taro.getMenuButtonBoundingClientRect?.()
    const bottom = menu?.bottom
    const top = menu?.top
    if (typeof top === 'number' && Number.isFinite(top) && top > 0 && typeof bottom === 'number' && Number.isFinite(bottom) && bottom > top) {
      const contentTop = Math.ceil(top) + 8
      const left = menu?.left
      const capsuleCover = typeof left === 'number' && Number.isFinite(left) && left > 0
        ? { top: Math.floor(top), left: Math.max(0, Math.floor(left) - 24), height: Math.ceil(bottom - top) + 4 } : null
      return { top: contentTop, toolbarOffset: Math.max(0, Math.ceil(bottom) - contentTop + 8), coverHeight: Math.ceil(top), capsuleCover }
    }
    if (typeof bottom === 'number' && Number.isFinite(bottom) && bottom > 0) return { top: Math.ceil(bottom) + 4, toolbarOffset: 0, coverHeight: Math.min(44, Math.ceil(bottom)), capsuleCover: null }
  } catch { /* fallback for hosts without a WeChat capsule */ }
  return { top: 88, toolbarOffset: 0, coverHeight: 44, capsuleCover: null }
}

export default function VehiclePage() {
  const [coverSystemBar, setCoverSystemBar] = useState(false)
  usePageScroll(event => setCoverSystemBar(event.scrollTop > 4))
  const [cars, setCars] = useState<Car[]>([])
  const [car, setCar] = useState<Car | null>(null)
  const [status, setStatus] = useState<CarStatus | null>(null)
  const [readinessItems, setReadinessItems] = useState<ReadinessItem[]>([])
  const [pairing, setPairing] = useState<TelemetryPairing | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [cached, setCached] = useState(false)
  const [visible, setVisible] = useState(false)
  const [foregroundNow, setForegroundNow] = useState(Date.now)
  const foreground = useRef(false)
  const statusRef = useRef(status)
  statusRef.current = status
  const pollBusy = useRef(false)
  const [hudDismissed, setHudDismissed] = useState(false)
  const [mapFailed, setMapFailed] = useState(false)
  const [imageSrc, setImageSrc] = useState<string | null>(null)
  const [imageLoading, setImageLoading] = useState(false)
  const [imageError, setImageError] = useState(false)
  const [imageRetry, setImageRetry] = useState(0)
  const [imageActivation, setImageActivation] = useState(0)
  const [appearanceRevision, setAppearanceRevision] = useState(0)
  const [appearanceOpen, setAppearanceOpen] = useState(false)
  const appearanceTicket = useRef<{ version: number; generation: number; owner: string | null } | null>(null)
  const imageEpoch = useRef(0)
  const clearNativeImage = () => {
    try { const component = Taro.getCurrentInstance?.().page?.selectComponent?.('#matelink-vehicle-asset') as { clearAsset?: () => void } | null; component?.clearAsset?.() } catch { /* native host may be absent in a non-WeChat renderer */ }
  }
  const clearImage = () => { imageEpoch.current += 1; clearNativeImage(); setImageSrc(null); setImageLoading(false); setImageError(false) }
  const [tpmsCacheReason, setTpmsCacheReason] = useState<string | null>(null)
  const [showDiagnostics, setShowDiagnostics] = useState(false)
  const focusDiagnostics = useRef(false)
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
    clearImage()
    setAppearanceOpen(false)
    requestVersion.current += 1
    scopeGate.current.invalidate()
    setLoading(false)
  }

  const load = async () => {
    clearImage()
    setImageActivation(value => value + 1)
    setAppearanceOpen(false)
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
    setHudDismissed(false)
    setMapFailed(false)
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
      setError(null)
      return
    }
    setLoading(true)
    setError(null)
    let origin: string | null = null
    const ownerIsCurrent = () => {
      try { return foreground.current && version === requestVersion.current && sessionGeneration === getApiSessionGeneration() &&
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
        (async () => { const ticket = scopeGate.current.begin('status'); const value = await matelinkApi.getCarSnapshot(selected.id, selected); return { ticket, value } })(),
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
        selectedStableId.current = selected?.stableId ?? null
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

  useDidShow(() => {
    foreground.current = true; setVisible(true); setForegroundNow(Date.now())
    try {
      if (consumeDataStatus(Taro, readAppSession(Taro)?.userId ?? null, getApiOrigin())) {
        focusDiagnostics.current = true
        setShowDiagnostics(true)
      }
    } catch { /* invalid origin or unavailable storage cannot authorize a navigation hint */ }
    void load()
  })
  useEffect(() => {
    if (!visible || !showDiagnostics || !focusDiagnostics.current) return
    focusDiagnostics.current = false
    void Taro.pageScrollTo({ selector: '.dashboard-readiness-panel', duration: 250 }).catch(() => {})
  }, [visible, showDiagnostics])
  const leavePage = () => { foreground.current = false; setVisible(false); invalidatePage() }
  useDidHide(leavePage)
  useUnload(leavePage)

  // This clock is independent of network completion, so a hung request cannot keep a live badge fresh.
  useEffect(() => {
    if (!visible) return
    const timer = setInterval(() => {
      setForegroundNow(Date.now())
      let originChanged = false
      try { originChanged = Boolean(pageOrigin.current && pageOrigin.current !== getApiOrigin()) } catch { originChanged = true }
      if (pageOwner.current && (originChanged || pageOwner.current !== `${readAppSession(Taro)?.userId ?? ''}:${getApiSessionGeneration()}`)) {
        invalidatePage(); setCars([]); setCar(null); setStatus(null); setReadinessItems([]); setPairing(null); setHistoryEvidence(null)
        pageOwner.current = null
      }
    }, 1000)
    return () => clearInterval(timer)
  }, [visible])

  // Poll only the snapshot. Preserve image, HUD, selected car, readiness and the user's open editor.
  useEffect(() => {
    if (!visible || loading || !car || car.remoteVerified === false) return
    const selected = car, version = requestVersion.current, generation = getApiSessionGeneration()
    const owner = readAppSession(Taro)?.userId, origin = pageOrigin.current
    if (!owner || !origin) return
    let stopped = false, timer: ReturnType<typeof setTimeout> | undefined
    const current = () => {
      try { return !stopped && foreground.current && version === requestVersion.current && generation === getApiSessionGeneration() &&
        owner === readAppSession(Taro)?.userId && origin === getApiOrigin() && selectedStableId.current === selected.stableId } catch { return false }
    }
    const schedule = () => { if (current()) timer = setTimeout(() => { void refreshSnapshot() }, vehicleSnapshotDelay(statusRef.current)) }
    const refreshSnapshot = async () => {
      if (!current()) return
      if (pollBusy.current) { schedule(); return }
      pollBusy.current = true
      try {
        const ticket = scopeGate.current.begin('status'), nextStatus = await matelinkApi.getCarSnapshot(selected.id, selected)
        if (!current() || !ticket.isCurrent()) return
        statusRef.current = nextStatus
        setStatus(nextStatus); setCached(false); setError(null)
        writeVehicleCache(Taro, owner, origin, selected.stableId, nextStatus)
        setTpmsCacheReason(nextStatus.tpms ? observeTpmsCache(Taro, owner, origin, selected.stableId, nextStatus.tpms).reason : null)
        setCar(value => value?.stableId === selected.stableId ? { ...value, model: nextStatus.model ?? value.model,
          trim: nextStatus.trim ?? value.trim, exteriorColor: nextStatus.exteriorColor ?? value.exteriorColor,
          wheelType: nextStatus.wheelType ?? value.wheelType } : value)
      } catch (reason) { if (current()) setError(messageFor(reason)) }
      finally { pollBusy.current = false; schedule() }
    }
    schedule()
    return () => { stopped = true; if (timer !== undefined) clearTimeout(timer) }
  }, [visible, loading, car?.id, car?.stableId, car?.remoteVerified])

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
    clearImage()
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

  const currentSession = readAppSession(Taro)
  let appearanceOrigin: string | null = null
  try { appearanceOrigin = getApiOrigin() } catch { /* Invalid configuration already has a page error. */ }
  const appearanceScope: VehicleAppearanceScope | null = appearanceOrigin && (!car || currentSession?.userId && pageOwner.current === `${currentSession.userId}:${getApiSessionGeneration()}` && selectedStableId.current === car.stableId)
    ? { origin: appearanceOrigin, userId: car ? currentSession!.userId : null, stableId: car?.stableId ?? null } : null
  const manualAppearance = appearanceScope ? readVehicleAppearance(Taro, appearanceScope) : null
  const detectedAppearance = mergeVehicleVisualConfig(car, status)
  const defaultAppearance = !manualAppearance && !detectedAppearance.model && !detectedAppearance.exteriorColor
  const appearance = manualAppearance ?? (defaultAppearance ? DEFAULT_VEHICLE_APPEARANCE : detectedAppearance)
  const visual = resolveVehicleVisual(appearance)
  const appearanceLabel = manualAppearance ? `手动外观${manualAppearance.modelYear ? ` · ${manualAppearance.modelYear}款` : ''}` : defaultAppearance ? '默认外观 · 黑色 Model Y 焕新版' : null
  const openAppearance = () => {
    appearanceTicket.current = { version: requestVersion.current, generation: getApiSessionGeneration(), owner: readAppSession(Taro)?.userId ?? null }
    setAppearanceOpen(true)
  }
  const appearanceEditorIsCurrent = () => {
    const ticket = appearanceTicket.current
    try { return Boolean(appearanceScope && ticket && ticket.version === requestVersion.current && ticket.generation === getApiSessionGeneration() && ticket.owner === (readAppSession(Taro)?.userId ?? null) && getApiOrigin() === appearanceScope.origin && (!car || selectedStableId.current === car.stableId)) } catch { return false }
  }
  useEffect(() => {
    const assetEpoch = ++imageEpoch.current, version = requestVersion.current, generation = getApiSessionGeneration()
    const owner = readAppSession(Taro)?.userId ?? null, origin = appearanceOrigin, stableId = car?.stableId ?? null
    clearNativeImage(); setImageSrc(null); setImageError(false); setImageLoading(false)
    if (!visual.assetKey || !origin || !appearanceScope || (!car && loading)) return
    const isCurrent = () => {
      try { return imageEpoch.current === assetEpoch && requestVersion.current === version && getApiSessionGeneration() === generation && (readAppSession(Taro)?.userId ?? null) === owner && getApiOrigin() === origin && (!stableId || selectedStableId.current === stableId) } catch { return false }
    }
    setImageLoading(true)
    void ensureVehicleAsset(visual, { isCurrent }).then(src => { if (isCurrent()) { setImageSrc(src); setImageLoading(false) } }).catch(() => { if (isCurrent()) { clearNativeImage(); setImageError(true); setImageLoading(false) } })
    return () => { if (imageEpoch.current === assetEpoch) { imageEpoch.current += 1; clearNativeImage() } }
  }, [visual.assetKey, car?.stableId, imageRetry, appearanceRevision, loading, imageActivation])

  const readiness = readinessViewFor(readinessItems)
  // Per-wheel readiness wording mirrors App readinessDashboardValue: a missing
  // "tpms" item reads as 等待车辆, while an existing unknown item stays 暂不可用.
  const tpmsReadiness = (() => {
    const item = findReadinessItem(readinessItems, 'tpms')
    if (!item) return { status: 'waiting_vehicle', lastObservedAt: null }
    const view = toReadinessView(item.status, item)
    return { status: view.status, lastObservedAt: view.lastObservedAt }
  })()
  const tpmsHistory = car && currentSession?.userId && pageOrigin.current &&
    pageOwner.current === `${currentSession.userId}:${getApiSessionGeneration()}`
    ? readTpmsCache(Taro, currentSession.userId, pageOrigin.current, car.stableId) : null
  const tpmsProfile = car && currentSession?.userId && pageOrigin.current && pageOwner.current === `${currentSession.userId}:${getApiSessionGeneration()}`
    ? readTpmsProfile(Taro, { origin: pageOrigin.current, userId: currentSession.userId, stableId: car.stableId }) : null
  const tpmsAlerts = evaluateTpmsAlerts(tpmsProfile, status?.tpms, tpmsHistory?.points).warnings
  const model = car?.model?.toUpperCase().replace(/^MODEL\s*/, '')
  const activeDriving = status?.state?.toLowerCase() === 'driving' || (status?.speed ?? 0) > 0
  const activeCharging = status?.chargingState?.toLowerCase() === 'charging'
  const snapshot = dashboardSnapshot(status, cached, foregroundNow)
  const map = !cached && status?.location ? mapCoordinate(status.location.latitude, status.location.longitude) : null
  const navigate = (url: string) => { void Taro.navigateTo({ url }) }
  const settings = () => navigate('/pages/tools/index?kind=settings')
  const openBattery = () => navigate('/pages/insights/index?kind=battery')
  const location = status?.geofence?.trim() || (status?.location
    ? `${status.location.latitude.toFixed(4)}, ${status.location.longitude.toFixed(4)}` : '暂无数据')
  const headerGeometry = dashboardHeaderGeometry()
  return (
    <View className="page vehicle-page" style={{ paddingTop: `${headerGeometry.top}px` }}>
      {coverSystemBar ? <View className="dashboard-system-bar-cover" style={{ height: `${headerGeometry.coverHeight}px` }} /> : null}
      {coverSystemBar && headerGeometry.capsuleCover ? <View className="dashboard-capsule-cover" style={{ top: `${headerGeometry.capsuleCover.top}px`, left: `${headerGeometry.capsuleCover.left}px`, height: `${headerGeometry.capsuleCover.height}px` }} /> : null}
      <View className="dashboard-toolbar">
        <View className="dashboard-title"><Text>{car?.displayName || car?.name || '车辆'}</Text><Text>{dashboardVehicleSubtitle(car?.model, car?.trim) || '车型待识别'}</Text></View>
        <View className="dashboard-toolbar-right" style={{ paddingTop: `${headerGeometry.toolbarOffset}px` }}><View className="dashboard-toolbar-actions"><Text className={`dashboard-snapshot-badge dashboard-snapshot-${snapshot.kind}`} data-kind={snapshot.kind}>{snapshot.label}</Text><Button className="dashboard-icon-button" ariaLabel="刷新车辆" loading={loading} onClick={() => { void load() }}><DashboardIcon name="refresh" tone="muted" /></Button><Button className="dashboard-icon-button" ariaLabel="设置" onClick={settings}><DashboardIcon name="settings" tone="muted" /></Button></View><Text className="dashboard-report-time">{dashboardReportTime(status?.observedAt)}</Text></View>
      </View>
      {cars.length > 1 ? <Picker mode="selector" range={cars.map(item => item.displayName || item.name || '车辆')} value={Math.max(0, cars.findIndex(item => item.stableId === car?.stableId))} onChange={selectCar}><View className="vehicle-selector"><Text>当前车辆 · {car?.displayName || car?.name || '请选择车辆'}</Text><Text>切换⌄</Text></View></Picker> : null}
      {car && status ? <>
        <DashboardHud status={status} dismissed={hudDismissed} onDismiss={() => setHudDismissed(true)} onExpand={() => setHudDismissed(false)} onOpen={() => { void Taro.switchTab({ url: activeDriving ? '/pages/drives/index' : '/pages/charges/index' }) }} />
        <DashboardHero car={car} status={status} imageSrc={imageSrc} imageLoading={imageLoading} imageError={imageError} onRetryImage={() => setImageRetry(value => value + 1)} onOpenBattery={openBattery} onManagePhoto={appearanceScope ? openAppearance : undefined} appearanceLabel={appearanceLabel} />
        {loading ? <LoadingIndicator label="正在读取最新数据…" compact /> : null}
        <View className="dashboard-location-row"><DashboardInfoCard label="里程" value={dashboardMetric(status.odometer, 'km', 0)} icon="speed" onClick={() => navigate('/pages/analysis/index?kind=mileage')} /><DashboardInfoCard label="位置" value={location} icon="location" onClick={() => { if (map) navigate('/pages/tools/index?kind=location'); else { focusDiagnostics.current = true; setShowDiagnostics(true) } }} /></View>
        <View className="dashboard-temperature-row"><DashboardInfoCard label="车内" value={dashboardTemperature(status.insideTemp)} icon="thermostat" onClick={() => navigate('/pages/tools/index?kind=temperature')} /><DashboardInfoCard label="车外" value={dashboardTemperature(status.outsideTemp)} icon="thermostat" onClick={() => navigate('/pages/tools/index?kind=temperature')} /></View>
        <DashboardTpms snapshot={status.tpms} report={status.tpmsReport} warnings={tpmsAlerts} readiness={tpmsReadiness} onOpen={() => navigate('/pages/tools/index?kind=tpms')} />
        {map ? <View className="dashboard-map-card">{!mapFailed ? <Map id="dashboard-map" latitude={map.latitude} longitude={map.longitude} scale={15} showLocation={false} markers={[{ id: 1, ...map, iconPath: '/assets/tabs/vehicle-selected.png', width: 24, height: 24, title: car.displayName || car.name || '车辆' }]} onError={() => setMapFailed(true)} /> : <View className="dashboard-map-unavailable"><Text>地图暂时无法载入</Text><Button className="dashboard-text-button" onClick={() => setMapFailed(false)}>重试地图</Button></View>}</View> : null}
        <View className="dashboard-history-entry" onClick={() => navigate('/pages/analysis/index?kind=stats')}><DashboardIcon name="bolt" /><View><Text>7天电量趋势</Text><Text>查看已记录的电池历史与容量详情</Text></View></View>
        {activeCharging ? <DashboardCurrentCharge status={status} onOpen={() => { void Taro.switchTab({ url: '/pages/charges/index' }) }} /> : null}
      </> : car ? <View className="dashboard-partial">
        <View className="dashboard-partial-hero"><DashboardVehicleArt car={car} imageSrc={imageSrc} imageLoading={imageLoading} imageError={imageError} onRetryImage={() => setImageRetry(value => value + 1)} onManagePhoto={appearanceScope ? openAppearance : undefined} appearanceLabel={appearanceLabel} /><Text className="dashboard-section-title">车辆状态暂不可用</Text><Text>当前仅显示已确认的车辆资料，状态数据尚未返回。</Text></View>
        <View className="dashboard-location-row"><DashboardInfoCard label="车型" value={car.model ? `Model ${model}` : '暂无数据'} icon="car" /><DashboardInfoCard label="外观" value={car.exteriorColor || '暂无数据'} /></View>
        <View className="dashboard-temperature-row"><DashboardInfoCard label="总行程" value="暂无数据" /><DashboardInfoCard label="总充电" value="暂无数据" /></View>
        <DashboardInfoCard label="数据来源" value={cached ? '缓存车辆资料' : '已关联车辆资料'} />
        <View className="dashboard-partial-actions"><Button className="button" onClick={() => { void load() }}>刷新车辆</Button><Button className="button button-secondary" onClick={settings}>设置</Button></View>
      </View> : <View className="card empty-state"><Text className="section-title">车辆外观预览</Text><DashboardVehicleArt car={{ model: appearance.model ?? null }} imageSrc={imageSrc} imageLoading={imageLoading} imageError={imageError} onRetryImage={() => setImageRetry(value => value + 1)} onManagePhoto={appearanceScope ? openAppearance : undefined} appearanceLabel={appearanceLabel} /><Text className="section-title">尚未绑定车辆</Text><Text className="muted">请先登录特斯拉账号并完成 Tesla 官方授权。外观预览不代表已绑定车辆，没有真实响应时不显示车辆数据。</Text><Button className="button" onClick={() => Taro.switchTab({ url: '/pages/me/index' })}>去完成登录与授权</Button></View>}
      {appearanceOpen && appearanceScope ? <AppearanceSelector key={`${appearanceScope.userId ?? 'preview'}:${appearanceScope.stableId ?? ''}`} initial={appearance} preview={!car} onClose={() => setAppearanceOpen(false)} onSave={value => {
        if (!appearanceEditorIsCurrent() || !writeVehicleAppearance(Taro, appearanceScope, value)) return false
        setAppearanceRevision(value => value + 1); return true
      }} onAutomatic={() => {
        if (!appearanceEditorIsCurrent() || !clearVehicleAppearance(Taro, appearanceScope)) return false
        setAppearanceRevision(value => value + 1); return true
      }} /> : null}
      {readiness.status === 'pairing_required' || readiness.status === 'permission_required' ? <Button className="button" onClick={copyOfficialLink}>打开 Tesla 官方授权入口</Button> : null}
      {readiness.status === 'telemetry_not_configured' ? <Text className="muted">持续采集需要服务端先完成配置，请联系管理员。</Text> : null}
      {readiness.status === 'telemetry_error' ? <Button className="button" loading={loading} onClick={retryTelemetry}>重新配置持续采集</Button> : null}
      {error ? <Text className="error">{error}</Text> : null}
      {showDiagnostics ? <View className="dashboard-readiness-panel">
        <Button className="button button-secondary" onClick={() => setShowDiagnostics(false)}>收起数据状态</Button>
        <View className="dashboard-evidence"><Text>{dashboardSource(status?.source)}</Text><Text>{status?.observedAt ? `状态报告时间：${status.observedAt}（未提供逐字段更新时间）` : '状态报告时间未提供，不能确认字段是否最新。'}</Text>{tpmsCacheReason ? <Text>{tpmsCacheReason}</Text> : null}</View>
        <ReadinessChecklist items={readinessItems} pairing={pairing} sessionReady={Boolean(currentSession)} diagnostics={{ car, status, cached, onlineError: Boolean(error), history: historyEvidence }} />
        {status ? <View className="card"><Text className="section-title">状态报告详情</Text><View className="metric-grid"><MetricValue label="速度" value={status.speed} unit="km/h" /><MetricValue label="功率" value={status.power} unit="kW" negative={(status.power ?? 0) < 0} /><MetricValue label="空调" value={booleanLabel(status.isClimateOn, '开启', '关闭')} /></View></View> : null}
      </View> : null}
    </View>
  )
}
