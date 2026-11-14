import { APP_DISPLAY_CONTRACT } from '../../domain/app_display_contract'
import { createElement, useState } from 'react'
import { Button, Text, View } from '@tarojs/components'
import type { Car, CarStatus, Drive } from '../../services/types'
import { recentDriveCardModel } from '../../domain/recent_drive'
import { restoreTpmsSnapshot, TPMS_WHEELS, type TpmsReport, type TpmsSnapshot } from '../../domain/tpms'
import type { TpmsAlert } from '../../domain/tpms_alerts'
import DashboardIcon, { type DashboardIconName } from './DashboardIcon'
import { climateTargets, mediaPlayback, scheduledEvents, softwareUpdateUi, vehicleModes } from '../../domain/extended_status'
import { dashboardMetric, dashboardNumber, dashboardPower, dashboardRange, dashboardShift, dashboardState, dashboardTpmsPlaceholder, hudRemaining, rawChargeHours } from './presentation'

export function DashboardInfoCard({ label, value, icon, onClick, warning = false }: { label: string; value: string; icon?: DashboardIconName; onClick?: () => void; warning?: boolean }) {
  return <View className="dashboard-info-card" onClick={onClick} ariaLabel={`${label} ${value}`}>
    <View className="dashboard-info-heading">{icon ? <DashboardIcon name={icon} /> : null}<Text className="dashboard-info-label">{label}</Text></View>
    <Text className="dashboard-info-value" style={warning ? { color: 'var(--dashboard-warning)' } : undefined}>{value}</Text>
  </View>
}

/** App DashboardScreen.StatusChip: icon + label, a null state reads 未知,
 * and when commands are ready the chip itself opens the confirm dialog. */
export function DashboardStatusChip({ icon, value, yes, no, onClick, loading = false }: { icon: DashboardIconName; value: boolean | null; yes: string; no: string; onClick?: () => void; loading?: boolean }) {
  const label = value == null ? '未知' : value ? yes : no
  return <View className="dashboard-state-chip" style={{ backgroundColor: value === true ? 'var(--dashboard-primary-container)' : value === false ? 'var(--dashboard-neutral-soft)' : 'var(--dashboard-neutral-unknown)' }} ariaLabel={label} onClick={onClick}>
    {loading ? <View className="dashboard-chip-spinner" /> : <DashboardIcon name={icon} tone={value === true ? 'primary' : 'muted'} />}<Text className="dashboard-chip-label">{label}</Text>
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
  // App HUD chrome: 收起 (close_hud) / 展开 (expand_hud).
  if (dismissed) return <View className="dashboard-hud-collapsed" onClick={onExpand}><View><DashboardIcon name={driving ? 'speed' : 'bolt'} /><Text>{title}</Text></View><Text>展开</Text></View>
  const power = dashboardPower(status.power), shift = dashboardShift(status.shiftState)
  return <View className={`dashboard-hud dashboard-hud-${driving ? 'driving' : 'charging'}`} onClick={onOpen}>
    <View className="dashboard-hud-heading"><View><View className="dashboard-live-dot" /><Text>{title}</Text>{!driving && status.chargerPower != null ? <View className="dashboard-hud-power"><Text className="dashboard-hud-number">{dashboardMetric(status.chargerPower, 'kW')}</Text><Text className="dashboard-hud-label">功率</Text></View> : null}</View><Button className="dashboard-text-button" onClick={event => { event.stopPropagation(); onDismiss() }}>收起</Button></View>
    <View className="dashboard-hud-row">{driving ? <>
      <HudValue label="速度" value={dashboardMetric(status.speed, 'km/h', 0)} className="dashboard-hud-primary" />
      {shift ? <View className="dashboard-hud-gear"><Text>{shift}</Text><Text>挡位</Text></View> : null}
      {status.power != null ? <HudValue label={power.label} value={dashboardMetric(power.value, 'kW')} className="dashboard-hud-right" /> : null}
    </> : <>
      <HudValue label="已充入" value={dashboardMetric(status.chargeEnergyAdded, 'kWh')} className="dashboard-hud-primary" />
      {status.chargerVoltage != null || status.chargerActualCurrent != null ? (
        <HudValue
          label="电压 / 实际电流"
          value={`${dashboardMetric(status.chargerVoltage, 'V', 0)} / ${dashboardMetric(status.chargerActualCurrent, 'A', 0)}`}
        />
      ) : null}
      {status.timeToFullChargeHours != null ? <HudValue label="剩余时间" value={hudRemaining(status.timeToFullChargeHours)} className="dashboard-hud-right" /> : null}
    </>}</View>
  </View>
}

export function DashboardVehicleArt({ car, imageSrc, imageLoading, imageError, onRetryImage, onManagePhoto, appearanceLabel }: { car: Pick<Car, 'model'>; imageSrc: string | null; imageLoading: boolean; imageError: boolean; onRetryImage: () => void; onManagePhoto?: () => void; appearanceLabel?: string | null }) {
 return <View className="dashboard-vehicle-art" style={{ height: `${APP_DISPLAY_CONTRACT.vehicleHeroHeight}px` }}>
      {createElement('vehicle-asset', { id: 'matelink-vehicle-asset', className: 'dashboard-vehicle-image', style: `height:${APP_DISPLAY_CONTRACT.vehicleImageHeight}px`, 'aria-label': `${car.model ? `Model ${car.model}` : '车辆'} 车型外观` })}
      {!imageSrc ? <View className="dashboard-vehicle-placeholder dashboard-art-placeholder"><DashboardIcon name="car" tone="muted" /><Text>{imageLoading ? '正在载入车辆外观…' : imageError ? '车辆外观暂时无法载入' : '车型外观尚未识别'}</Text>{imageError ? <Button className="dashboard-text-button" onClick={event => { event.stopPropagation(); onRetryImage() }}>重试车辆图片</Button> : null}</View> : null}
      {appearanceLabel ? <Text className="dashboard-appearance-label">{appearanceLabel}</Text> : null}
      <Button className="dashboard-camera-button" ariaLabel="选择车辆外观" disabled={!onManagePhoto} onClick={event => { event.stopPropagation(); onManagePhoto?.() }}><DashboardIcon name="camera" /></Button>
    </View>
}

export function DashboardHero({ car, status, imageSrc, imageLoading, imageError, onRetryImage, onOpenBattery, onManagePhoto, appearanceLabel, historyFallback = false, commandsReady = false, busyCommand = null, onChipAction }: {
  car: Car; status: CarStatus; imageSrc: string | null; imageLoading: boolean; imageError: boolean; onRetryImage: () => void; onOpenBattery: () => void; onManagePhoto?: () => void; appearanceLabel?: string | null; historyFallback?: boolean; commandsReady?: boolean; busyCommand?: string | null; onChipAction?: (chip: 'lock' | 'plug' | 'climate' | 'sentry') => void
}) {
  const power = dashboardPower(status.power), shift = dashboardShift(status.shiftState), driving = status.state?.toLowerCase() === 'driving'
  const openings = [{ value: status.doorsOpen, text: '车门打开' }, { value: status.windowsOpen, text: '车窗打开' }, { value: status.frunkOpen, text: '前备箱打开' }, { value: status.trunkOpen, text: '后备箱打开' }].filter(item => item.value === true)
  const [openingsExpanded, setOpeningsExpanded] = useState(false)
  const media = mediaPlayback(status)
  const update = softwareUpdateUi(status)
  const scheduled = scheduledEvents(status)
  const modes = vehicleModes(status)
  const targets = climateTargets(status)
  const climateTargetText = targets
    ? [targets.driver, targets.passenger].filter(v => v != null).map(v => Number.isInteger(v) ? String(v) : (v as number).toFixed(1)).join(' / ')
    : null
  const statePill = dashboardState(status.state) ?? (historyFallback ? dashboardState('asleep') : null)
  return <View className="dashboard-hero" onClick={onOpenBattery}>
    <View className="dashboard-hero-heading"><View><DashboardIcon name="car" /><Text>车辆</Text></View>{statePill ? <Text className="dashboard-vehicle-state">{statePill}</Text> : null}</View>
    <DashboardVehicleArt car={car} imageSrc={imageSrc} imageLoading={imageLoading} imageError={imageError} onRetryImage={onRetryImage} onManagePhoto={onManagePhoto} appearanceLabel={appearanceLabel} />
    {driving ? <View className="dashboard-driving-strip">
      {status.speed != null ? <View ariaLabel={`速度 ${dashboardMetric(status.speed, 'km/h', 0)}`}><DashboardIcon name="speed" /><Text>{dashboardMetric(status.speed, 'km/h', 0)}</Text><Text>速度</Text></View> : null}
      {status.power != null ? <View ariaLabel={`${power.label} ${dashboardMetric(power.value, 'kW')}，原始功率 ${status.power} kW`}><DashboardIcon name="bolt" tone={status.power < 0 ? 'primary' : 'warning'} /><Text>{dashboardMetric(power.value, 'kW')}</Text><Text>{power.label}</Text></View> : null}
      {shift ? <View ariaLabel={`挡位 ${shift}`}><DashboardIcon name="car" /><Text>{shift}</Text><Text>挡位</Text></View> : null}
    </View> : null}
    <View className="dashboard-battery-row"><View><Text className="dashboard-soc-value">{status.batteryLevel == null ? '--' : `${dashboardNumber(status.batteryLevel, 0)}%`}</Text><Text className="dashboard-soc-label">电量</Text></View>
      <View className="dashboard-range" ariaLabel={`续航 ${dashboardMetric(dashboardRange(status), 'km', 0)}`}><Text>{dashboardRange(status) == null ? '--' : dashboardMetric(dashboardRange(status), 'km', 0)}{status.chargingState?.toLowerCase() === 'charging' ? ' ⚡' : ''}</Text><Text>续航</Text></View>
    </View>
    {status.batteryLevel != null ? <View className="dashboard-battery-track"><View style={{ width: `${Math.max(0, Math.min(100, status.batteryLevel))}%` }} /></View> : null}
    {status.chargeLimitSoc != null ? <Text className="dashboard-charge-target">{status.batteryLevel != null ? `当前 ${dashboardNumber(status.batteryLevel, 0)}% · 充电目标 ${dashboardNumber(status.chargeLimitSoc, 0)}%` : `限制: ${dashboardNumber(status.chargeLimitSoc, 0)}%`}</Text> : null}
    // App: chips open the confirm dialogs when command scopes are ready; the
    // lock chip spins while lock/unlock is in flight, and so on per command.
    <View className="dashboard-state-row">
      <DashboardStatusChip icon={status.locked === false ? 'unlock' : 'lock'} value={status.locked} yes="已锁" no="未锁" loading={busyCommand === 'lock' || busyCommand === 'unlock'} onClick={commandsReady ? () => onChipAction?.('lock') : undefined} />
      <DashboardStatusChip icon="bolt" value={status.pluggedIn} yes="已插枪" no="未插枪" loading={['charge_port_open', 'charge_port_close', 'charge_start', 'charge_stop'].includes(busyCommand ?? '')} onClick={commandsReady ? () => onChipAction?.('plug') : undefined} />
      <DashboardStatusChip icon="climate" value={status.isClimateOn} yes="空调开启" no="空调关闭" loading={busyCommand === 'climate_on' || busyCommand === 'climate_off'} onClick={commandsReady ? () => onChipAction?.('climate') : undefined} />
      <DashboardStatusChip icon="security" value={status.sentryMode} yes="哨兵已开启" no="哨兵关闭" loading={busyCommand === 'sentry_on' || busyCommand === 'sentry_off'} onClick={commandsReady ? () => onChipAction?.('sentry') : undefined} />
    </View>
    {openings.length ? <View className="dashboard-opening-alert">
      <View><DashboardIcon name="warning" tone="warning" /><Text>车辆未关闭</Text></View>
      <Button className="dashboard-text-button" onClick={event => { event.stopPropagation(); setOpeningsExpanded(value => !value) }}>{openingsExpanded ? '收起车身位置示意' : '查看车身位置示意'}</Button>
      {openingsExpanded ? <View className="dashboard-opening-grid">{openings.map(opening => <View key={opening.text}><DashboardIcon name="warning" tone="warning" /><Text>{opening.text}</Text></View>)}</View> : null}
    </View> : null}
    {media ? <View className="dashboard-extended-row"><DashboardIcon name="speed" /><Text className="dashboard-extended-text">正在播放：{media.displayText}</Text></View> : null}
    {update ? (
      <View className="dashboard-extended-update">
        <Text className="dashboard-extended-text">
          {update.percent == null
            ? '软件更新进行中'
            : update.phase === 'downloading'
            ? `正在下载更新 · ${update.percent}%`
            : `正在安装更新 · ${update.percent}%`}
        </Text>
        {update.percent != null ? (
          <View className="dashboard-update-track">
            <View style={{ width: `${update.percent}%` }} />
          </View>
        ) : null}
      </View>
    ) : null}
    {scheduled.map(ev => (
      <View key={ev.kind} className="dashboard-extended-row">
        <DashboardIcon name={ev.kind === 'charging_start' ? 'bolt' : 'speed'} />
        <Text className="dashboard-extended-text">{ev.kind === 'charging_start' ? `预约充电 ${ev.timeText} 开始` : `预约出发 ${ev.timeText}`}</Text>
      </View>
    ))}
    {modes?.valetOn ? (
      <View className="dashboard-extended-row">
        <DashboardIcon name="lock" />
        <Text className="dashboard-extended-text">代客模式已开启</Text>
      </View>
    ) : null}
    {modes?.speedLimitKmh != null ? (
      <View className="dashboard-extended-row">
        <DashboardIcon name="speed" />
        <Text className="dashboard-extended-text">限速 {modes.speedLimitKmh} km/h</Text>
      </View>
    ) : null}
    {climateTargetText ? (
      <View className="dashboard-extended-row">
        <DashboardIcon name="climate" />
        <Text className="dashboard-extended-text">空调目标 {climateTargetText}°</Text>
      </View>
    ) : null}
  </View>
}

export function DashboardTpms({ snapshot, report, warnings, readiness, onOpen }: { snapshot?: TpmsSnapshot; report?: TpmsReport; warnings: readonly TpmsAlert[]; readiness?: { status: string; lastObservedAt: string | null } | null; onOpen: () => void }) {
  const current = restoreTpmsSnapshot(snapshot), names = { fl: '左前轮', fr: '右前轮', rl: '左后轮', rr: '右后轮' }
  return <View className="dashboard-tpms"><Text className="dashboard-section-title">胎压</Text><View className="dashboard-tpms-row">{TPMS_WHEELS.map(wheel => {
    const row = current.wheels[wheel], warning = warnings.find(item => item.wheel === wheel)
    const pressure = row.pressureBar ?? report?.[wheel] ?? null
    // An available readiness with no per-wheel value stays "暂无数据"; any other
    // readiness shows the accurate status wording — never a fabricated reason.
    // App dashboard InfoCards carry no hint text — only the warning tint.
    const missing = readiness && readiness.status !== 'available' ? dashboardTpmsPlaceholder(readiness) : '暂无数据'
    return <DashboardInfoCard key={wheel} label={names[wheel]} value={pressure == null ? missing : dashboardMetric(pressure, 'bar')} onClick={onOpen} warning={Boolean(warning)} />
  })}</View></View>
}

export function DashboardRecentDrive({ drive, onOpen }: { drive: Drive; onOpen: () => void }) {
  const model = recentDriveCardModel(drive)
  return <View className="dashboard-recent-drive" onClick={onOpen}>
    <View className="recent-drive-header">
      <View className="recent-drive-title"><DashboardIcon name="car" /><Text className="dashboard-section-title">最近行程</Text></View>
      <Text className="recent-drive-time">{model.endTimeText ? `${model.endTimeText} ›` : '›'}</Text>
    </View>
    <View className="recent-drive-route-row">
      <Text className="recent-drive-route">{model.routeText}</Text>
      <View className="recent-drive-dist">
        <Text className="recent-drive-distance">{model.distanceText}</Text>
        {model.socRangeText ? <Text className="recent-drive-soc">{model.socRangeText}</Text> : null}
      </View>
    </View>
    <View className="recent-drive-capsules">
      {model.capsules.map(capsule => <View className="recent-drive-capsule" key={capsule.label}>
        <Text className="recent-drive-capsule-label">{capsule.label}</Text>
        <Text className="recent-drive-capsule-value">{capsule.value}</Text>
      </View>)}
    </View>
  </View>
}

export function DashboardCurrentCharge({ status, onOpen }: { status: CarStatus; onOpen: () => void }) {
  return <View className="dashboard-current-charge" onClick={onOpen}><View className="dashboard-current-heading"><DashboardIcon name="bolt" tone="warning" /><Text>正在充电</Text></View><View className="dashboard-current-row">
    <HudValue label="充电功率" value={dashboardMetric(status.chargerPower, 'kW')} /><HudValue label="已充入" value={dashboardMetric(status.chargeEnergyAdded, 'kWh')} /><HudValue label="剩余时间" value={rawChargeHours(status.timeToFullChargeHours)} />
  </View></View>
}
