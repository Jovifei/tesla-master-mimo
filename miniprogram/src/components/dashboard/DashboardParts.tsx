import { createElement } from 'react'
import { Button, Text, View } from '@tarojs/components'
import type { Car, CarStatus } from '../../services/types'
import { restoreTpmsSnapshot, TPMS_WHEELS, type TpmsReport, type TpmsSnapshot } from '../../domain/tpms'
import type { TpmsAlert } from '../../domain/tpms_alerts'
import DashboardIcon, { type DashboardIconName } from './DashboardIcon'
import { dashboardMetric, dashboardNumber, dashboardPower, dashboardRange, dashboardShift, dashboardState, dashboardTpmsPlaceholder, hudRemaining, rawChargeHours } from './presentation'

export function DashboardInfoCard({ label, value, icon, onClick, warning = false, hint }: { label: string; value: string; icon?: DashboardIconName; onClick?: () => void; warning?: boolean; hint?: string | null }) {
  return <View className="dashboard-info-card" onClick={onClick} ariaLabel={`${label} ${value}`}>
    <View className="dashboard-info-heading">{icon ? <DashboardIcon name={icon} /> : null}<Text className="dashboard-info-label">{label}</Text></View>
    <Text className="dashboard-info-value" style={warning ? { color: 'var(--dashboard-warning)' } : undefined}>{value}</Text>
    {hint ? <View className={`dashboard-info-hint${warning ? ' dashboard-info-hint-warning' : ''}`}>{warning ? <DashboardIcon name="warning" tone="warning" /> : null}<Text>{hint}</Text></View> : null}
  </View>
}

export function DashboardStatusChip({ icon, value, yes, no }: { icon: DashboardIconName; value: boolean | null; yes: string; no: string }) {
  const label = value == null ? '状态未知' : value ? yes : no
  return <View className="dashboard-state-chip" style={{ backgroundColor: value === true ? 'var(--dashboard-primary-container)' : value === false ? 'var(--dashboard-neutral-soft)' : 'var(--dashboard-neutral-unknown)' }} ariaLabel={label}>
    <DashboardIcon name={icon} tone={value === true ? 'primary' : 'muted'} /><Text className="dashboard-chip-label">{label}</Text>
  </View>
}

function HudValue({ label, value, className = '' }: { label: string; value: string; className?: string }) {
  return <View className={`dashboard-hud-value ${className}`} ariaLabel={`${label} ${value}`}><Text className="dashboard-hud-number">{value}</Text><Text className="dashboard-hud-label">{label}</Text></View>
}

export function DashboardHud({ status, dismissed, onDismiss, onExpand, onOpen }: { status: CarStatus; dismissed: boolean; onDismiss: () => void; onExpand: () => void; onOpen: () => void }) {
  const driving = status.state?.toLowerCase() === 'driving' || (status.speed ?? 0) > 0
  const charging = status.chargingState?.toLowerCase() === 'charging'
  if (!driving && !charging) return null
  const title = driving ? '正在行驶' : '正在充电'
  if (dismissed) return <View className="dashboard-hud-collapsed" onClick={onExpand}><View><DashboardIcon name={driving ? 'speed' : 'bolt'} /><Text>{title}</Text></View><Text>展开浮窗</Text></View>
  const power = dashboardPower(status.power), shift = dashboardShift(status.shiftState)
  return <View className={`dashboard-hud dashboard-hud-${driving ? 'driving' : 'charging'}`} onClick={onOpen}>
    <View className="dashboard-hud-heading"><View><View className="dashboard-live-dot" /><Text>{title}</Text>{!driving && status.chargerPower != null ? <Text className="dashboard-hud-inline">{dashboardMetric(status.chargerPower, 'kW')}</Text> : null}</View><Button className="dashboard-text-button" onClick={event => { event.stopPropagation(); onDismiss() }}>关闭浮窗</Button></View>
    <View className="dashboard-hud-row">{driving ? <>
      <HudValue label="速度" value={dashboardMetric(status.speed, 'km/h', 0)} className="dashboard-hud-primary" />
      {shift ? <View className="dashboard-hud-gear"><Text>{shift}</Text><Text>挡位</Text></View> : null}
      {status.power != null ? <HudValue label={power.label} value={dashboardMetric(power.value, 'kW')} className="dashboard-hud-right" /> : null}
    </> : <>
      <HudValue label="已充入" value={dashboardMetric(status.chargeEnergyAdded, 'kWh')} className="dashboard-hud-primary" />
      {status.chargerVoltage != null ? <HudValue label="充电电压电流" value={`${dashboardMetric(status.chargerVoltage, 'V', 0)} / ${dashboardMetric(status.chargerActualCurrent, 'A', 0)}`} /> : null}
      {status.timeToFullChargeHours != null ? <HudValue label="剩余时间" value={hudRemaining(status.timeToFullChargeHours)} className="dashboard-hud-right" /> : null}
    </>}</View>
  </View>
}

export function DashboardVehicleArt({ car, imageSrc, imageLoading, imageError, onRetryImage, onManagePhoto, appearanceLabel }: { car: Pick<Car, 'model'>; imageSrc: string | null; imageLoading: boolean; imageError: boolean; onRetryImage: () => void; onManagePhoto?: () => void; appearanceLabel?: string | null }) {
 return <View className="dashboard-vehicle-art">
      {createElement('vehicle-asset', { id: 'matelink-vehicle-asset', className: 'dashboard-vehicle-image', 'aria-label': `${car.model ? `Model ${car.model}` : '车辆'} 车型外观` })}
      {!imageSrc ? <View className="dashboard-vehicle-placeholder dashboard-art-placeholder"><DashboardIcon name="car" tone="muted" /><Text>{imageLoading ? '正在载入车辆外观…' : imageError ? '车辆外观暂时无法载入' : '车型外观尚未识别'}</Text>{imageError ? <Button className="dashboard-text-button" onClick={event => { event.stopPropagation(); onRetryImage() }}>重试车辆图片</Button> : null}</View> : null}
      {appearanceLabel ? <Text className="dashboard-appearance-label">{appearanceLabel}</Text> : null}
      <Button className="dashboard-camera-button" ariaLabel="选择车辆外观" disabled={!onManagePhoto} onClick={event => { event.stopPropagation(); onManagePhoto?.() }}><DashboardIcon name="camera" /></Button>
    </View>
}

export function DashboardHero({ car, status, imageSrc, imageLoading, imageError, onRetryImage, onOpenBattery, onManagePhoto, appearanceLabel }: {
  car: Car; status: CarStatus; imageSrc: string | null; imageLoading: boolean; imageError: boolean; onRetryImage: () => void; onOpenBattery: () => void; onManagePhoto?: () => void; appearanceLabel?: string | null
}) {
  const power = dashboardPower(status.power), shift = dashboardShift(status.shiftState), driving = status.state?.toLowerCase() === 'driving'
  const openings = [{ value: status.doorsOpen, text: '车门未关' }, { value: status.windowsOpen, text: '车窗未关' }, { value: status.frunkOpen, text: '前备箱未关' }, { value: status.trunkOpen, text: '后备箱未关' }].filter(item => item.value === true)
  return <View className="dashboard-hero" onClick={onOpenBattery}>
    <View className="dashboard-hero-heading"><View><DashboardIcon name="car" /><Text>车辆</Text></View><Text className="dashboard-vehicle-state">{dashboardState(status.state)}</Text></View>
    <DashboardVehicleArt car={car} imageSrc={imageSrc} imageLoading={imageLoading} imageError={imageError} onRetryImage={onRetryImage} onManagePhoto={onManagePhoto} appearanceLabel={appearanceLabel} />
    {driving ? <View className="dashboard-driving-strip">
      {status.speed != null ? <View ariaLabel={`速度 ${dashboardMetric(status.speed, 'km/h', 0)}`}><DashboardIcon name="speed" /><Text>{dashboardMetric(status.speed, 'km/h', 0)}</Text><Text>速度</Text></View> : null}
      {status.power != null ? <View ariaLabel={`${power.label} ${dashboardMetric(power.value, 'kW')}，原始功率 ${status.power} kW`}><DashboardIcon name="bolt" tone={status.power < 0 ? 'primary' : 'warning'} /><Text>{dashboardMetric(power.value, 'kW')}</Text><Text>{power.label}</Text></View> : null}
      {shift ? <View ariaLabel={`挡位 ${shift}`}><DashboardIcon name="car" /><Text>{shift}</Text><Text>挡位</Text></View> : null}
    </View> : null}
    <View className="dashboard-battery-row"><View><Text className="dashboard-soc-value">{status.batteryLevel == null ? '--' : `${dashboardNumber(status.batteryLevel, 0)}%`}</Text><Text className="dashboard-soc-label">电量</Text></View>
      <View className="dashboard-range" ariaLabel={`续航 ${dashboardMetric(dashboardRange(status), 'km', 0)}`}><Text>{dashboardRange(status) == null ? '--' : dashboardMetric(dashboardRange(status), 'km', 0)}</Text><Text>续航</Text></View>
    </View>
    {status.batteryLevel != null ? <View className="dashboard-battery-track"><View style={{ width: `${Math.max(0, Math.min(100, status.batteryLevel))}%` }} /></View> : null}
    {status.chargeLimitSoc != null ? <Text className="dashboard-charge-target">{status.batteryLevel != null ? `当前 ${dashboardNumber(status.batteryLevel, 0)}% · 充电目标 ${dashboardNumber(status.chargeLimitSoc, 0)}%` : `限制: ${dashboardNumber(status.chargeLimitSoc, 0)}%`}</Text> : null}
    <View className="dashboard-state-row"><DashboardStatusChip icon={status.locked === false ? 'unlock' : 'lock'} value={status.locked} yes="已锁" no="未锁" /><DashboardStatusChip icon="bolt" value={status.pluggedIn} yes="已插枪" no="未插枪" /><DashboardStatusChip icon="climate" value={status.isClimateOn} yes="空调开启" no="空调关闭" /><DashboardStatusChip icon="security" value={status.sentryMode} yes="哨兵已开启" no="哨兵关闭" /></View>
    {openings.length ? <View className="dashboard-opening-alert"><View><DashboardIcon name="warning" tone="warning" /><Text>车门与开口提醒</Text></View><View className="dashboard-opening-grid">{openings.map(opening => <View key={opening.text}><DashboardIcon name="warning" tone="warning" /><Text>{opening.text}</Text></View>)}</View></View> : null}
  </View>
}

export function DashboardTpms({ snapshot, report, warnings, readiness, onOpen }: { snapshot?: TpmsSnapshot; report?: TpmsReport; warnings: readonly TpmsAlert[]; readiness?: { status: string; lastObservedAt: string | null } | null; onOpen: () => void }) {
  const current = restoreTpmsSnapshot(snapshot), names = { fl: '左前轮', fr: '右前轮', rl: '左后轮', rr: '右后轮' }
  return <View className="dashboard-tpms"><Text className="dashboard-section-title">胎压</Text><View className="dashboard-tpms-row">{TPMS_WHEELS.map(wheel => {
    const row = current.wheels[wheel], warning = warnings.find(item => item.wheel === wheel)
    const pressure = row.pressureBar ?? report?.[wheel] ?? null
    const hint = warning ? '本机规则提示' : row.state === 'stale' ? '旧观测' : row.pressureBar == null && pressure != null ? '观测未核验' : null
    // An available readiness with no per-wheel value stays "暂无数据"; any other
    // readiness shows the accurate status wording — never a fabricated reason.
    const missing = readiness && readiness.status !== 'available' ? dashboardTpmsPlaceholder(readiness) : '暂无数据'
    return <DashboardInfoCard key={wheel} label={names[wheel]} value={pressure == null ? missing : dashboardMetric(pressure, 'bar')} onClick={onOpen} warning={Boolean(warning)} hint={hint} />
  })}</View></View>
}

export function DashboardCurrentCharge({ status, onOpen }: { status: CarStatus; onOpen: () => void }) {
  return <View className="dashboard-current-charge" onClick={onOpen}><View className="dashboard-current-heading"><DashboardIcon name="bolt" tone="warning" /><Text>正在充电</Text></View><View className="dashboard-current-row">
    <HudValue label="充电功率" value={dashboardMetric(status.chargerPower, 'kW')} /><HudValue label="已充入" value={dashboardMetric(status.chargeEnergyAdded, 'kWh')} /><HudValue label="剩余时间" value={rawChargeHours(status.timeToFullChargeHours)} />
  </View></View>
}
